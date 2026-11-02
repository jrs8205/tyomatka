"""Fintrafficin latauspisteet (Digitraffic AFIR, CC BY 4.0) Suomen ajoon.

Hakee rajapinnasta kaikki kohteet ja hinnastot, muuntaa Suomen kohteet karttakohteiden riveiksi (`afir:`-tagit),
rajaa ne Suomen rajaan ja korvaa niillä OSM-latauspisteet 100 metrin säteellä. Ei logoja eikä hakuhetken
vapaa/varattu-tilaa. Vain standardikirjasto.

Käyttö: afir.py --merge FI.jsonl [--date YYYY-MM-DD]
"""

import argparse
import datetime
import gzip
import http.client
import json
import math
import re
import sys
import time
import urllib.error
import urllib.request

import border

BASE = "https://afir.digitraffic.fi/api/charging-network/v1"
HEADERS = {"User-Agent": "tyomatka-poi-pipeline/1.0", "Digitraffic-User": "tyomatka-poi-pipeline", "Accept-Encoding": "gzip"}
ATTEMPTS = 5
RETRY_CODES = {429, 500, 502, 503, 504}
MIN_LAT = 59.7
MAX_VALUE_LEN = 200
ADDRESS_PLACEHOLDERS = {"unknown", "n/a", "-", "x"}
LOCATION_ID = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
PARTY = re.compile(r"^[A-Z]{2}[A-Z0-9]{3}$")
STANDARDS = {
    "IEC_62196_T2_COMBO": "CCS2",
    "IEC_62196_T2": "Type 2",
    "CHADEMO": "CHAdeMO",
    "IEC_62196_T1_COMBO": "CCS1",
    "IEC_62196_T1": "Type 1",
    "DOMESTIC_F": "Schuko",
    "TESLA_S": "Tesla",
}
DAYS = ("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")
OSM_DAYS = ("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")


def fetch(path, sleep=time.sleep):
    request = urllib.request.Request(f"{BASE}/{path}", headers=HEADERS)
    for attempt in range(ATTEMPTS):
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                body = response.read()
                if (response.headers.get("Content-Encoding") or "").lower() == "gzip":
                    body = gzip.decompress(body)
                return json.loads(body.decode("utf-8"))
        except urllib.error.HTTPError as error:
            if error.code not in RETRY_CODES or attempt == ATTEMPTS - 1:
                raise SystemExit(f"AFIR {path}: HTTP {error.code}")
        except (OSError, http.client.HTTPException, ValueError) as error:
            if attempt == ATTEMPTS - 1:
                raise SystemExit(f"AFIR {path}: {getattr(error, 'reason', error)}")
        sleep(2 * (2 ** attempt))
    raise SystemExit(f"AFIR {path}: ei vastausta")


def _text(value):
    if isinstance(value, str):
        value = " ".join(value.split())
        return value[:MAX_VALUE_LEN] if value else None
    return None


def _address_part(value):
    """Fintrafficin aineistossa puuttuvan tiedon paikalla voi olla sana, jota ei saa näyttää osoitteena."""
    value = _text(value)
    return None if value is None or value.casefold() in ADDRESS_PLACEHOLDERS else value


def street(value):
    """"9A, Kylätie" → "Kylätie 9A"; muut sellaisenaan."""
    value = _text(value)
    if not value:
        return None
    match = re.match(r"^(\d+[A-Za-z]?(?:-\d+[A-Za-z]?)?),\s*(.+)$", value)
    return f"{match.group(2)} {match.group(1)}" if match else value


def address(raw):
    raw = raw or {}
    road = street(_address_part(raw.get("street")))
    # Ilman katua kohde saa osoitteen käänteishausta (fill_addr.py) kuten muutkin.
    if not road:
        return None
    place = " ".join(p for p in (_address_part(raw.get("postalCode")), _address_part(raw.get("city"))) if p)
    return ", ".join(p for p in (road, place) if p)


def _kw(watts):
    kw = round(watts / 1000, 1)
    return str(int(kw)) if kw == int(kw) else str(kw)


def connectors(location):
    """`tyyppi:määrä:kW;…` suurimman tehon mukaan laskevasti, saman tehon sisällä nimen mukaan."""
    groups = {}
    for evse in location.get("evses") or []:
        for connector in evse.get("connectors") or []:
            power = connector.get("maxElectricPower")
            if not isinstance(power, (int, float)) or power <= 0:
                continue
            name = STANDARDS.get(connector.get("standard"), None)
            if name is None:
                continue
            count, best = groups.get(name, (0, 0))
            groups[name] = (count + 1, max(best, power))
    ordered = sorted(groups.items(), key=lambda item: (-item[1][1], item[0]))
    return ";".join(f"{name}:{count}:{_kw(best)}" for name, (count, best) in ordered)


def hours(location):
    opening = location.get("openingTimes")
    if not isinstance(opening, dict):
        return None
    if opening.get("twentyFourSeven") is True:
        return "24/7"
    week = {}
    for period in opening.get("regularHours") or []:
        day = period.get("weekday")
        begin, end = period.get("periodBegin"), period.get("periodEnd")
        if day not in DAYS or not isinstance(begin, str) or not isinstance(end, str):
            return None
        week.setdefault(DAYS.index(day), []).append(f"{begin}-{end}")
    if not week:
        return None
    entries = []
    day = 0
    while day < 7:
        if day not in week:
            day += 1
            continue
        spans = ",".join(sorted(week[day]))
        last = day
        while last + 1 < 7 and last + 1 in week and ",".join(sorted(week[last + 1])) == spans:
            last += 1
        days = OSM_DAYS[day] if last == day else f"{OSM_DAYS[day]}-{OSM_DAYS[last]}"
        entries.append(f"{days} {spans}")
        day = last + 1
    if entries == ["Mo-Su 00:00-24:00"]:
        return "24/7"
    return "; ".join(entries)


def _energy_price(tariff):
    """Yksiselitteinen hinta: yksi rajoituksiton elementti ja siinä yksi ENERGY-komponentti euroina, verollisena."""
    if not tariff or tariff.get("currency") != "EUR":
        return None
    elements = tariff.get("elements") or []
    if len(elements) != 1 or elements[0].get("restrictions"):
        return None
    components = elements[0].get("priceComponents") or []
    if len(components) != 1 or components[0].get("type") != "ENERGY":
        return None
    price = components[0].get("price")
    if not isinstance(price, (int, float)) or price <= 0:
        return None
    if tariff.get("taxIncluded") == "YES":
        return price
    vat = components[0].get("vat")
    if isinstance(vat, (int, float)) and vat >= 0:
        return price * (1 + vat / 100)
    return None


def price(location, tariffs):
    """Suurimman tehon liitinten ad hoc -hinta, kun kaikki niiden hinnastot antavat saman yksiselitteisen hinnan."""
    best = 0
    ids = []
    for evse in location.get("evses") or []:
        for connector in evse.get("connectors") or []:
            power = connector.get("maxElectricPower") or 0
            if power > best:
                best, ids = power, []
            if power == best:
                ids.extend(connector.get("tariffIds") or [])
    if not ids:
        return None
    candidates = [tariffs.get(tid) for tid in ids]
    ad_hoc = [t for t in candidates if t and t.get("type") == "AD_HOC_PAYMENT"]
    prices = {_energy_price(t) for t in (ad_hoc or candidates)}
    if len(prices) != 1 or None in prices:
        return None
    return f"{prices.pop():.2f} €/kWh"


def row_id(location):
    operator = location.get("operator") or {}
    party = f"{operator.get('countryCode', '')}{operator.get('partyId', '')}"
    lid = location.get("id")
    if not PARTY.match(party) or not isinstance(lid, str) or not LOCATION_ID.match(lid):
        return None
    return f"c-{party}-{lid}"


def rows_from_afir(locations, tariffs, fetched):
    by_id = {t.get("id"): t for t in tariffs if isinstance(t, dict)}
    out = []
    for feature in locations:
        props = feature.get("properties") or {}
        coords = (feature.get("geometry") or {}).get("coordinates") or []
        if len(coords) < 2 or not all(isinstance(c, (int, float)) for c in coords[:2]):
            continue
        lon, lat = coords[0], coords[1]
        if (props.get("address") or {}).get("countryCode") != "FIN" or not (MIN_LAT <= lat <= 70.2 and 19 <= lon <= 32):
            continue
        rid = row_id(props)
        plugs = connectors(props)
        if rid is None or not plugs:
            continue
        tags = {"afir:connectors": plugs, "afir:date": fetched}
        if any(c.get("powerType") == "DC" for e in props.get("evses") or [] for c in e.get("connectors") or []):
            tags["afir:dc"] = "yes"
        operator_name = _text(((props.get("operator") or {}).get("details") or {}).get("name"))
        if operator_name:
            tags["afir:operator"] = operator_name
        opening = hours(props)
        if opening:
            tags["afir:hours"] = opening
        if isinstance(props.get("parkingType"), str):
            tags["afir:parking"] = props["parkingType"].lower()
        cost = price(props, by_id)
        if cost:
            tags["afir:price"] = cost
        out.append({
            "id": rid,
            "cat": "charging",
            "lat": round(lat, 6),
            "lon": round(lon, 6),
            "name": _text(props.get("name")),
            "brand": None,
            "addr": address(props.get("address")),
            "hgv": 0,
            "tags": tags,
        })
    return out


def _metres(a_lat, a_lon, b_lat, b_lon):
    x = math.radians(b_lon - a_lon) * math.cos(math.radians((a_lat + b_lat) / 2))
    y = math.radians(b_lat - a_lat)
    return 6371000 * math.hypot(x, y)


def merge(osm_rows, afir_rows, radius_m=100.0):
    """OSM-latauspisteet, joiden lähellä on AFIR-kohde, jäävät pois; AFIR-rivit lisätään loppuun."""
    cell = 0.01
    grid = {}
    for row in afir_rows:
        grid.setdefault((math.floor(row["lat"] / cell), math.floor(row["lon"] / cell)), []).append(row)

    def near(row):
        y, x = math.floor(row["lat"] / cell), math.floor(row["lon"] / cell)
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                for other in grid.get((y + dy, x + dx), []):
                    if _metres(row["lat"], row["lon"], other["lat"], other["lon"]) <= radius_m:
                        return True
        return False

    kept = [row for row in osm_rows if row.get("cat") != "charging" or not near(row)]
    return kept + list(afir_rows)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Fintrafficin latauspisteet Suomen riveihin")
    parser.add_argument("--merge", required=True, help="transform.py:n rivitiedosto, kirjoitetaan uudelleen")
    parser.add_argument("--date", default=None, help="hakupäivä (oletus tänään, UTC)")
    args = parser.parse_args(argv)
    fetched = args.date or datetime.datetime.now(datetime.timezone.utc).date().isoformat()
    locations = fetch("locations/all").get("features") or []
    tariffs = fetch("tariffs/all").get("tariffs") or []
    afir_rows = rows_from_afir(locations, tariffs, fetched)
    if not afir_rows:
        raise SystemExit("AFIR: ei yhtään Suomen latauspistettä, rivejä ei muuteta")
    # Rajan takana oleva rivi ei saa korvata Suomen puolen OSM-latauspistettä: rajaus pudottaisi korvaajan, ja piste katoaisi.
    afir_rows, outside = border.clip_checked(afir_rows, border.Border.load(border.DEFAULT_BORDER))
    with open(args.merge, encoding="utf-8") as handle:
        osm_rows = [json.loads(line) for line in handle if line.strip()]
    merged = merge(osm_rows, afir_rows)
    with open(args.merge, "w", encoding="utf-8") as out:
        for row in merged:
            out.write(json.dumps(row, ensure_ascii=False) + "\n")
    replaced = len(osm_rows) + len(afir_rows) - len(merged)
    print(f"AFIR: {len(afir_rows)} latauspistettä, {replaced} OSM-latauspistettä korvattu, {outside} rajan ulkopuolella", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
