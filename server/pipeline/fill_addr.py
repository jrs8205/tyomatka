"""Täydentää karttakohteiden puuttuvat katuosoitteet Digitransitin käänteishaulla samassa muodossa kuin sovelluksen stopissa.

Tulokset säilyvät Workerin taulussa addr_fill, joten viikkoajo hakee vain uudet, siirtyneet ja vanhentuneet kohteet.
Kohteen oma katuosoite voittaa aina. Kohteet lähetetään, vaikka avain puuttuisi tai Digitransit tai välimuisti ei
vastaisi: nämä häiriöt eivät muuta paluuarvoa, vaan niistä kertoo GitHubin varoitus. Aineisto © Digitransit 2026 (CC BY 4.0; OpenStreetMap-pohjaiset osoitteet ODbL).

Käyttö: fill_addr.py ROWS.jsonl   (ympäristö POI_BASE_URL, POI_ADMIN_TOKEN, DIGITRANSIT_KEY; arvoja ei tulosteta koskaan)
"""
import argparse
import datetime
import http.client
import json
import os
import sys
import time
import urllib.parse
import urllib.request

from afir import _metres as metres
from upload import USER_AGENT, post

REVERSE_URL = "https://api.digitransit.fi/geocoding/v1/reverse"
MAX_DISTANCE_KM = 0.3
# Palvelimen raja addr_fill-taulun osoitteelle.
MAX_ADDR_LENGTH = 200
MAX_MOVE_METRES = 25
MAX_AGE_DAYS = 180
MAX_FAILURES_IN_A_ROW = 20
# Työnkulun aikaraja on 90 minuuttia: hidas Digitransit ei saa estää kohteiden lähetystä. Tavallisesti haut vievät alle 25 min.
MAX_LOOKUP_SECONDS = 45 * 60
# Näin monesta hausta ilman yhtään osoitetta kerrotaan varoituksella: rajapinta on voinut muuttua.
SUSPICIOUS_EMPTY_LOOKUPS = 20
# Digitransitin ohje eräajoille.
MIN_INTERVAL_SECONDS = 0.5
TIMEOUT_SECONDS = 30
PUT_CHUNK = 500


def address_from_feature(feature):
    """Vastauksen kohteesta osoite kuten PeliasClient.resolve; yli 300 m:n päässä ei osoitetta, ja etäisyyden puute on virhe."""
    props = feature["properties"]
    distance = props.get("distance")
    if distance is None:
        raise ValueError("etäisyys puuttuu")
    if distance > MAX_DISTANCE_KM:
        return None
    primary = props.get("name") or " ".join(part for part in (props.get("street"), props.get("housenumber")) if part)
    if not primary:
        return None
    place = props.get("locality") or props.get("localadmin") or props.get("region")
    city = " ".join(part for part in (props.get("postalcode"), place) if part)
    address = ", ".join(part for part in (primary, city) if part)
    return address if len(address) <= MAX_ADDR_LENGTH else None


class DigitransitLookup:
    """lookup(lat, lon) → osoite tai None; nostaa LookupError-virheen, kun vastausta ei saada tai sitä ei voi lukea."""

    def __init__(self, key, opener=urllib.request.urlopen, clock=time.monotonic, sleep=time.sleep):
        self._key = key
        self._opener = opener
        self._clock = clock
        self._sleep = sleep
        self._earliest = 0.0

    def __call__(self, lat, lon):
        wait = self._earliest - self._clock()
        if wait > 0:
            self._sleep(wait)
        self._earliest = self._clock() + MIN_INTERVAL_SECONDS
        query = urllib.parse.urlencode({"point.lat": lat, "point.lon": lon, "layers": "address", "size": 1})
        request = urllib.request.Request(
            f"{REVERSE_URL}?{query}",
            headers={"digitransit-subscription-key": self._key, "User-Agent": USER_AGENT},
        )
        try:
            with self._opener(request, timeout=TIMEOUT_SECONDS) as response:
                features = json.loads(response.read().decode("utf-8"))["features"]
            # Vain aito tyhjä lista on "ei osoitetta": muuten rajapinnan häiriö lukitsisi kohteet ilman osoitetta puoleksi vuodeksi.
            if not isinstance(features, list):
                raise TypeError("features ei ole lista")
            return address_from_feature(features[0]) if features else None
        except (OSError, http.client.HTTPException) as error:
            raise LookupError(f"Digitransit: {error}") from None
        except (ValueError, KeyError, TypeError, AttributeError) as error:
            raise LookupError(f"Digitransit: {_safe_text(error)}") from None


def _safe_text(error):
    """Virheen selitys lokiin. http.client toistaa rikkinäisen otsakkeen arvon ValueError-tekstissä, joten sitä ei tulosteta."""
    return str(error) if isinstance(error, SystemExit) else type(error).__name__


def _has_street_address(row):
    """Pelkkä kunta tai postinumero ei ole katuosoite. Fintrafficin rivillä ei ole OSM-tageja, ja afir.py jättää vain kadulliset."""
    return bool(row.get("addr")) and (row["id"].startswith("c-") or bool(row.get("tags", {}).get("addr:street")))


def _cache_row(row, cache):
    """Välimuistin rivi (vanhentunutkin), jos se on luettavissa ja kohde on paikallaan."""
    hit = cache.get(row["id"])
    if hit is None:
        return None
    try:
        moved = metres(row["lat"], row["lon"], hit["lat"], hit["lon"])
    except (KeyError, TypeError):
        return None
    address = hit.get("addr", "")
    readable = address is None or (isinstance(address, str) and 0 < len(address) <= MAX_ADDR_LENGTH)
    return hit if readable and moved <= MAX_MOVE_METRES else None


def _is_current(hit, today):
    try:
        return (today - datetime.date.fromisoformat(hit["checked"])).days < MAX_AGE_DAYS
    except (KeyError, TypeError, ValueError):
        return False


def _with_address(row, address):
    """Osoite riville; ilman osoitetta rivi sellaisenaan, jolloin mahdollinen oma pelkkä kunta säilyy."""
    return {**row, "addr": address} if address else row


def fill(rows, cache, lookup, now, store=None, clock=time.monotonic):
    """Palauttaa rivit täydennettyinä, uudet välimuistirivit ja tilaston.

    Ilman lookupia (avain puuttuu) käytetään vain välimuistia. Vanhentunut osoite kelpaa, kunnes uusi haku onnistuu, joten
    katko ei pudota osoitteita. [store] saa jokaisen uuden tuloksen heti, jotta katkennut ajo ei hukkaa valmiita eriä.
    """
    today = now.date()
    started = clock()
    # Sama kohde voi olla kahdessa luokassa: haku tehdään kerran, ja sen tulos kelpaa saman ajon toiselle riville.
    cache = dict(cache)
    stats = {"cached": 0, "looked_up": 0, "found": 0, "failed": 0, "skipped": 0}
    filled = []
    fresh = []
    failures = 0
    for row in rows:
        if _has_street_address(row):
            filled.append(row)
            continue
        hit = _cache_row(row, cache)
        if hit is not None and _is_current(hit, today):
            stats["cached"] += 1
            filled.append(_with_address(row, hit["addr"]))
            continue
        previous = hit["addr"] if hit is not None else None
        if lookup is None or failures >= MAX_FAILURES_IN_A_ROW or clock() - started >= MAX_LOOKUP_SECONDS:
            stats["skipped"] += 1
            filled.append(_with_address(row, previous))
            continue
        try:
            address = lookup(row["lat"], row["lon"])
        except LookupError as error:
            failures += 1
            stats["failed"] += 1
            print(f"{row['id']}: {error}", file=sys.stderr)
            filled.append(_with_address(row, previous))
            continue
        failures = 0
        stats["looked_up"] += 1
        stats["found"] += 1 if address else 0
        filled.append(_with_address(row, address))
        result = {"id": row["id"], "lat": row["lat"], "lon": row["lon"], "addr": address, "checked": today.isoformat()}
        cache[row["id"]] = result
        fresh.append(result)
        if store is not None:
            store(result)
    return filled, fresh, stats


class CacheStore:
    """Uudet tulokset palvelimelle erissä. Epäonnistunut erä ei keskeytä hakuja: sen kohteet haetaan seuraavalla kerralla."""

    def __init__(self, base_url, token):
        self._base_url = base_url
        self._token = token
        self._pending = []

    def add(self, result):
        self._pending.append(result)
        if len(self._pending) >= PUT_CHUNK:
            self.flush()

    def flush(self):
        if not self._pending:
            return
        batch, self._pending = self._pending, []
        try:
            post(self._base_url, self._token, "/admin/addr/put", json.dumps({"rows": batch}, ensure_ascii=False))
        except (SystemExit, ValueError) as error:
            print(f"::warning::Uusia osoitteita ei saatu välimuistiin ({_safe_text(error)}); ne haetaan uudelleen seuraavalla kerralla.")


def _load_cache(base_url, token):
    """Välimuisti palvelimelta; None, jos sitä ei saada (vanha Worker, taulu puuttuu, katko tai outo vastaus)."""
    try:
        return {row["id"]: row for row in post(base_url, token, "/admin/addr/list", "{}")["rows"]}
    except (SystemExit, ValueError, KeyError, TypeError) as error:
        print(f"::warning::Osoitteiden täydennys ohitettu: välimuistia ei saatu ({_safe_text(error)}).")
        return None


def main(argv=None):
    parser = argparse.ArgumentParser(description="Karttakohteiden puuttuvat katuosoitteet Digitransitista")
    parser.add_argument("rows", help="rivitiedosto, kirjoitetaan uudelleen")
    args = parser.parse_args(argv)
    base_url = os.environ.get("POI_BASE_URL", "").strip()
    token = os.environ.get("POI_ADMIN_TOKEN", "").strip()
    key = os.environ.get("DIGITRANSIT_KEY", "").strip()
    if not (base_url and token):
        print("::warning::Osoitteiden täydennys ohitettu: POI_BASE_URL tai POI_ADMIN_TOKEN puuttuu.")
        return 0
    cache = _load_cache(base_url, token)
    if cache is None:
        return 0
    if not key:
        print("::warning::DIGITRANSIT_KEY puuttuu: osoitteet vain välimuistista.")
    with open(args.rows, encoding="utf-8") as handle:
        rows = [json.loads(line) for line in handle if line.strip()]

    store = CacheStore(base_url, token)
    now = datetime.datetime.now(datetime.timezone.utc)
    filled, _, stats = fill(rows, cache, DigitransitLookup(key) if key else None, now, store.add)

    with open(args.rows, "w", encoding="utf-8") as out:
        for row in filled:
            out.write(json.dumps(row, ensure_ascii=False) + "\n")
    store.flush()
    print(
        f"Osoitteet: välimuistista {stats['cached']}, haettu {stats['looked_up']}, löytyi {stats['found']}, "
        f"virheitä {stats['failed']}, hakematta {stats['skipped']}."
    )
    if key and (stats["failed"] or stats["skipped"]):
        print(f"::warning::Osoitteiden haku jäi vajaaksi: virheitä {stats['failed']}, hakematta {stats['skipped']}.")
    if stats["looked_up"] >= SUSPICIOUS_EMPTY_LOOKUPS and not stats["found"]:
        print(f"::warning::Yksikään {stats['looked_up']} hausta ei antanut osoitetta: tarkista Digitransitin vastaus.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
