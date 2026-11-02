"""Overture Places → Workerin yrityshaku.

Lukee Overturen uusimman julkaisun Suomen ja Ahvenanmaan paikat DuckDB:llä suoraan S3:sta, suodattaa ja yhdistää
kaksoiskappaleet ja lähettää ne Workerin tuontirajapintaan uutena sukupolvena: status → begin → import 500 rivin
erissä → swap → prune, kunnes mitään ei jää. Jos kannassa on jo sama julkaisu, mitään ei lähetetä.

Ympäristö: POI_BASE_URL ja POI_ADMIN_TOKEN (tunnusta ei tulosteta), POI_FORCE=1 hyväksyy yli puolen pudotuksen.
Käyttö: places.py [--dry-run] [--import-anyway] [--force]
"""

import argparse
import datetime
import json
import math
import os
import re
import sys
import unicodedata
import urllib.request

import upload

RELEASES_URL = "https://overturemaps-us-west-2.s3.amazonaws.com/?list-type=2&prefix=release/&delimiter=/"
RELEASE = re.compile(r"^release/(\d{4}-\d{2}-\d{2}\.\d+)/$")
RELEASE_NAME = re.compile(r"^(\d{4}-\d{2}-\d{2})\.(\d+)$")
# Palvelimen tarkistukset (src/places.ts): rivi, jonka palvelin hylkäisi, kaataisi koko erän.
UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
COUNTRIES = ("FI", "AX")
# Suomen ja Ahvenanmaan rajaava suorakulmio (minLon, minLat, maxLon, maxLat); maa rajataan osoitteesta.
BBOX = (19.0, 59.7, 31.6, 70.1)
MIN_CONFIDENCE = 0.3
# Luontokohteet eivät ole stoppeja (Suomessa noin 13 700 riviä, enimmäkseen järviä).
NATURE = frozenset({
    "lake", "river", "island", "mountain", "beach", "forest", "waterfall", "nature_reserve", "national_park",
    "recreational_trail_or_path",
})
# Sama luettelo kuin palvelimen haussa (src/places.ts): yhtiömuoto ei erota kahta riviä toisistaan.
LEGAL_FORMS = frozenset({"oy", "oyj", "ab", "abp", "ky", "ay", "tmi", "ry", "osk", "ltd", "as"})
SAME_PLACE_M = 50
# Palvelin hylkää koko erän, jos kenttä on pidempi (src/places.ts); Suomessa pisin katuosoite on 221 merkkiä.
MAX_FIELD = 200
CHUNK_SIZE = 500


def parse_releases(listing):
    """S3-listauksen julkaisut ("release/2026-10-21.0/" → "2026-10-21.0"); muut prefiksit ohitetaan."""
    found = []
    for prefix in re.findall(r"<Prefix>([^<]*)</Prefix>", listing):
        match = RELEASE.match(prefix)
        if match:
            found.append(match.group(1))
    return found


def list_releases():
    request = urllib.request.Request(RELEASES_URL, headers={"User-Agent": upload.USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return parse_releases(response.read().decode("utf-8"))


def release_key(release):
    """Järjestysavain ("2026-09-23.1" > "2026-09-23.0"); tuntematon muoto on vanhin."""
    match = RELEASE_NAME.match(release or "")
    return (match.group(1), int(match.group(2))) if match else ("", -1)


def latest_release(releases, today):
    """Uusin julkaisu ennen tätä päivää (UTC): julkaisupäivänä S3:n lataus voi olla vielä kesken."""
    ready = [r for r in releases if release_key(r)[0] and release_key(r)[0] < today]
    if not ready:
        raise SystemExit("Overturen valmiita julkaisuja ei löytynyt")
    return max(ready, key=release_key)


def extract_sql(release):
    """Rivit kaikkine kenttineen; sijainti bbox-keskipisteestä (piste), joten spatial-laajennusta ei tarvita."""
    min_lon, min_lat, max_lon, max_lat = BBOX
    countries = ", ".join(f"'{c}'" for c in COUNTRIES)
    return f"""
SELECT id,
       names."primary" AS name,
       basic_category AS category,
       addresses[1].freeform AS street,
       addresses[1].postcode AS postcode,
       addresses[1].locality AS locality,
       confidence,
       operating_status,
       (bbox.ymin + bbox.ymax) / 2 AS lat,
       (bbox.xmin + bbox.xmax) / 2 AS lon
FROM read_parquet('s3://overturemaps-us-west-2/release/{release}/theme=places/type=place/*', hive_partitioning = 1)
WHERE bbox.xmin BETWEEN {min_lon} AND {max_lon}
  AND bbox.ymin BETWEEN {min_lat} AND {max_lat}
  AND addresses[1].country IN ({countries})
"""


def extract(release):
    import duckdb  # vain varsinaisessa ajossa; testit eivät tarvitse sitä

    con = duckdb.connect()
    con.execute("INSTALL httpfs; LOAD httpfs; SET s3_region = 'us-west-2';")
    cursor = con.execute(extract_sql(release))
    columns = [d[0] for d in cursor.description]
    return [dict(zip(columns, values)) for values in cursor.fetchall()]


def js_length(text):
    """Pituus kuten palvelimen JavaScript laskee (UTF-16-yksiköt): emoji on kaksi."""
    return len(text.encode("utf-16-le")) // 2


def keep(row):
    name = (row.get("name") or "").strip()
    if not name or js_length(name) > MAX_FIELD:
        return False
    if row.get("operating_status") not in (None, "open"):
        return False
    if (row.get("confidence") or 0) < MIN_CONFIDENCE:
        return False
    return row.get("category") not in NATURE


_APOSTROPHES = frozenset("'\u2019\u02bc`\u00b4")


def _word_char(ch):
    return unicodedata.category(ch)[0] in "LN"


def _tmi(text):
    """'t:mi' sanana (ei kirjaimen tai numeron vieressä) → 'tmi', kuten palvelimen TMI-lauseke."""
    out = []
    i = 0
    while i < len(text):
        if (
            text.startswith("t:mi", i)
            and (i == 0 or not _word_char(text[i - 1]))
            and (i + 4 == len(text) or not _word_char(text[i + 4]))
        ):
            out.append("tmi")
            i += 4
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def tokens(text):
    """
    Palvelimen tokens() (server/src/places.ts) Pythonina: NFC, pienet kirjaimet, yhdistävät merkit ja heittomerkit pois,
    t:mi → tmi, muut kuin kirjaimet ja numerot erottimina, yhtiömuodot pois. Yhteiset tapaukset:
    server/test/tokens-cases.json.
    """
    text = unicodedata.normalize("NFC", text or "").lower()
    text = "".join(ch for ch in text if not unicodedata.category(ch).startswith("M") and ch not in _APOSTROPHES)
    text = _tmi(text)
    words = "".join(ch if _word_char(ch) else " " for ch in text).split()
    return [w for w in words if w not in LEGAL_FORMS]


def _key(text):
    """Vertailuavain: palvelimen sanat (toistot pois kuten kannan nwords), joten sama nimi on sama kummassakin."""
    return " ".join(dict.fromkeys(tokens(text)))


def _distance_m(a, b):
    lat = math.radians((a["lat"] + b["lat"]) / 2)
    dx = math.radians(b["lon"] - a["lon"]) * math.cos(lat)
    dy = math.radians(b["lat"] - a["lat"])
    return 6371000 * math.hypot(dx, dy)


def _same_town(a, b):
    """Sama postinumero; kun toiselta puuttuu, sama paikkakunta. Samanniminen katu on monessa kunnassa."""
    if a.get("postcode") and b.get("postcode"):
        return a["postcode"].strip() == b["postcode"].strip()
    return bool(_key(a.get("locality"))) and _key(a.get("locality")) == _key(b.get("locality"))


def dedupe(rows):
    """
    Sama nimi ja sama katuosoite samassa kunnassa tai sama nimi alle 50 m:n päässä on sama paikka; jäljelle suurin
    luotettavuus.
    """
    kept = []
    by_name = {}
    for row in sorted(rows, key=lambda r: -(r.get("confidence") or 0)):
        name = _key(row["name"])
        street = _key(row.get("street"))
        twins = by_name.setdefault(name, [])
        if any(
            (street and street == _key(t.get("street")) and _same_town(row, t)) or _distance_m(row, t) < SAME_PLACE_M
            for t in twins
        ):
            continue
        twins.append(row)
        kept.append(row)
    return kept


def api_row(row):
    out = {"id": row["id"], "name": row["name"].strip()}
    for field in ("street", "postcode", "locality"):
        value = (row.get(field) or "").strip()
        # Liian pitkä valinnainen kenttä on lähes aina roskaa: se jätetään pois, rivi säilyy.
        if value and js_length(value) <= MAX_FIELD:
            out[field] = value
    out.update(lat=row["lat"], lon=row["lon"], conf=row["confidence"])
    return out


def valid_row(api):
    """Samat ehdot kuin palvelimen `validatePlaceRow`."""
    def coordinate(value, limit):
        return isinstance(value, (int, float)) and math.isfinite(value) and abs(value) <= limit

    conf = api.get("conf")
    return (
        bool(UUID.match(api.get("id") or ""))
        and bool(api.get("name")) and js_length(api["name"]) <= MAX_FIELD
        and coordinate(api.get("lat"), 90) and coordinate(api.get("lon"), 180)
        and isinstance(conf, (int, float)) and math.isfinite(conf) and 0 <= conf <= 1
    )


def prepare(raw):
    """Suodatus, kaksoiskappaleet ja palvelimen tarkistus: (lähetettävät rivit, palvelimen ehtoihin sopimattomat)."""
    rows = [api_row(r) for r in dedupe([r for r in raw if keep(r)])]
    good = [r for r in rows if valid_row(r)]
    return good, len(rows) - len(good)


def prune(post, base_url, token):
    """Vanhat sukupolvet pois erissä; palvelin säilyttää aktiivisen ja tuonnissa olevan."""
    while int(post(base_url, token, "/admin/places/prune", "{}").get("deleted", 0)) > 0:
        pass


def main(argv=None, post=upload.post, releases=list_releases, extract=extract, today=None):
    parser = argparse.ArgumentParser(description="Overture Places Workerin yrityshakuun")
    parser.add_argument("--dry-run", action="store_true", help="hae ja suodata, tulosta rivimäärä, älä lähetä")
    parser.add_argument("--import-anyway", action="store_true", help="tuo, vaikka kannassa on jo sama julkaisu")
    parser.add_argument("--force", action="store_true", help="hyväksy yli puolen pudotus edellisestä rivimäärästä")
    args = parser.parse_args(argv)
    today = today or datetime.datetime.now(datetime.timezone.utc).date().isoformat()
    release = latest_release(releases(), today)

    if args.dry_run:
        rows, dropped = prepare(extract(release))
        print(f"{release}: {len(rows)} riviä, {dropped} hylätty palvelimen ehdoilla", file=sys.stderr)
        return 0

    base_url = os.environ.get("POI_BASE_URL", "").strip()
    token = os.environ.get("POI_ADMIN_TOKEN", "").strip()
    if not base_url or not token:
        raise SystemExit("POI_BASE_URL ja POI_ADMIN_TOKEN puuttuvat ympäristöstä")

    status = post(base_url, token, "/admin/places/status", "{}")
    stored = status.get("release")
    if stored and release_key(stored) >= release_key(release) and not args.import_anyway:
        # Edellisen ajon jäänteet siivotaan silloinkin, kun tuotavaa ei ole.
        prune(post, base_url, token)
        print(f"{release}: ajan tasalla (kannassa {stored}, {status.get('rows')} riviä)", file=sys.stderr)
        return 0

    rows, dropped = prepare(extract(release))
    if dropped:
        print(f"{release}: {dropped} riviä jätetty pois palvelimen ehdoilla", file=sys.stderr)
    gen = post(base_url, token, "/admin/places/begin", json.dumps({"release": release}))["gen"]
    # Epäonnistuneiden ajojen sukupolvet pois ennen uutta tuontia (tuonnissa oleva säilyy).
    prune(post, base_url, token)
    sent = 0
    for batch in upload.chunks(rows, CHUNK_SIZE):
        post(base_url, token, "/admin/places/import", json.dumps({"gen": gen, "rows": batch}, ensure_ascii=False))
        sent += len(batch)
        print(f"{release}: {sent}/{len(rows)} riviä lähetetty", file=sys.stderr)
    swap = {"gen": gen, "release": release}
    if args.force or os.environ.get("POI_FORCE", "").strip() == "1":
        swap["force"] = True
    post(base_url, token, "/admin/places/swap", json.dumps(swap))
    prune(post, base_url, token)
    print(f"{release}: vaihdettu, {len(rows)} riviä", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
