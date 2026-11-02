"""Lähettää muunnetut rivit Workerin tuontirajapintaan: begin → import 500 rivin erissä → swap (atominen maavaihto).

Ympäristö: POI_BASE_URL (esim. https://poi.jrs8205.com) ja POI_ADMIN_TOKEN. Tunnusta ei tulosteta koskaan.
"""

import argparse
import http.client
import json
import os
import sys
import time
import urllib.error
import urllib.request

CHUNK_SIZE = 500
ATTEMPTS = 5
RETRY_CODES = {429, 500, 502, 503, 504}
API_FIELDS = ("id", "cat", "lat", "lon", "name", "brand", "addr", "hgv", "tags")
# Oma tunniste: Cloudflaren selaintarkistus torjuu Pythonin oletusarvoisen User-Agentin (virhe 1010).
USER_AGENT = "tyomatka-poi-pipeline/1.0"


def headers(token):
    return {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json; charset=utf-8",
        "User-Agent": USER_AGENT,
    }


def chunks(rows, size):
    batch = []
    for row in rows:
        batch.append(row)
        if len(batch) == size:
            yield batch
            batch = []
    if batch:
        yield batch


def backoff_seconds(attempt):
    return 2 * (2 ** attempt)


def payload(country, rows):
    cleaned = [{k: row[k] for k in API_FIELDS if k in row and row[k] is not None} for row in rows]
    return json.dumps({"country": country, "rows": cleaned}, ensure_ascii=False)


def post(base_url, token, path, body, sleep=time.sleep):
    request = urllib.request.Request(
        base_url.rstrip("/") + path,
        data=body.encode("utf-8"),
        method="POST",
        headers=headers(token),
    )
    for attempt in range(ATTEMPTS):
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                return json.loads(response.read().decode("utf-8") or "{}")
        except urllib.error.HTTPError as error:
            if error.code not in RETRY_CODES or attempt == ATTEMPTS - 1:
                raise SystemExit(f"{path}: HTTP {error.code} {server_reason(error)}".rstrip())
        except (OSError, http.client.HTTPException) as error:
            # URLError, aikakatkaisu, katkennut tai nollattu yhteys: yksi katko ei saa kaataa maata viikoksi.
            if attempt == ATTEMPTS - 1:
                raise SystemExit(f"{path}: verkkovirhe {getattr(error, 'reason', error)}")
        sleep(backoff_seconds(attempt))
    raise SystemExit(f"{path}: ei vastausta")


def server_reason(error):
    """Palvelimen virheteksti JSON-rungon error-kentästä; tunnus ei koskaan ole vastauksessa."""
    try:
        return str(json.loads(error.read().decode("utf-8")).get("error", ""))[:200]
    except (OSError, ValueError, AttributeError, http.client.HTTPException):
        return ""


def read_rows(path):
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                yield json.loads(line)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Rivit Workerin tuontirajapintaan")
    parser.add_argument("--country", required=True)
    parser.add_argument("--extract-date", required=True, help="Geofabrikin otteen päiväys (YYYY-MM-DD)")
    parser.add_argument("--force", action="store_true", help="hyväksy yli puolen pudotus edellisestä rivimäärästä")
    parser.add_argument("rows")
    args = parser.parse_args(argv)
    base_url = os.environ.get("POI_BASE_URL", "").strip()
    token = os.environ.get("POI_ADMIN_TOKEN", "").strip()
    if not base_url or not token:
        raise SystemExit("POI_BASE_URL ja POI_ADMIN_TOKEN puuttuvat ympäristöstä")

    post(base_url, token, "/admin/begin", json.dumps({"country": args.country}))
    total = 0
    for batch in chunks(read_rows(args.rows), CHUNK_SIZE):
        result = post(base_url, token, "/admin/import", payload(args.country, batch))
        total += int(result.get("inserted", len(batch)))
        print(f"{args.country}: {total} riviä lähetetty", file=sys.stderr)
    swap = {"country": args.country, "extractDate": args.extract_date, "rows": total}
    # POI_FORCE=1 välittää pakotuksen run.sh:n ja työnkulun kautta (käsiajon force-valinta).
    if args.force or os.environ.get("POI_FORCE", "").strip() == "1":
        swap["force"] = True
    # Palvelin vaihtaa vain, jos välitaulussa on rivejä; saman otteen uusinta 5xx:n jälkeen kuitataan jo vaihdetuksi.
    post(base_url, token, "/admin/swap", json.dumps(swap))
    print(f"{args.country}: vaihdettu, {total} riviä", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
