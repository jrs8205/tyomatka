"""Muuntaa osmium exportin GeoJSON-rivit (geojsonseq) karttakohteiden riveiksi Workerin tuontirajapintaa varten.

Luokat: fuel (huoltoasema), charging (latauspiste), gas (kaasuasema), rest (lepopaikka tai rekkaparkki). Yksi rivi
luokkaa kohti; sama asema voi olla sekä fuel että gas. Vain standardikirjasto.
"""

import argparse
import datetime
import json
import re
import sys

CATS = ("fuel", "charging", "gas", "rest")

LIQUID = (
    "fuel:diesel", "fuel:hgv_diesel", "fuel:octane_95", "fuel:octane_98", "fuel:octane_100", "fuel:e10", "fuel:e5",
    "fuel:petrol", "fuel:biodiesel",
)
GAS = ("fuel:lpg", "fuel:cng", "fuel:lng", "fuel:h2")

KEEP_PREFIXES = (
    "name", "brand", "operator", "addr:", "opening_hours", "phone", "contact:phone", "website", "contact:website",
    "brand:website", "fuel:", "hgv", "maxheight", "maxweight", "toilets", "shower", "food", "restaurant", "fast_food",
    "cafe", "shop", "socket:", "capacity", "fee", "access", "parking", "overnight", "charging_station:output",
    "amenity", "highway",
)
# Käännökset ja tietokantalinkit eivät kuulu korttiin.
DROP_PREFIXES = ("name:", "brand:wikidata", "brand:wikipedia", "operator:wikidata", "operator:wikipedia")
LOWERCASE_PREFIXES = ("fuel:", "socket:")
MAX_VALUE_LEN = 200

ID_PATTERN = re.compile(r"^[nwr][0-9]+$")
DATE_PATTERN = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T")


def normalize_tags(tags):
    out = {}
    for key, value in tags.items():
        if not isinstance(value, str):
            continue
        if key.lower().startswith(LOWERCASE_PREFIXES):
            key = key.lower()
        if key.startswith(DROP_PREFIXES):
            continue
        if not key.startswith(KEEP_PREFIXES):
            continue
        out[key] = value[:MAX_VALUE_LEN]
    return out


def _yes(tags, key):
    return tags.get(key) == "yes"


def classify(tags):
    if tags.get("access") == "private":
        return []
    cats = []
    amenity = tags.get("amenity")
    highway = tags.get("highway")
    if amenity == "charging_station":
        cats.append("charging")
    if amenity == "fuel":
        liquid = any(_yes(tags, k) for k in LIQUID)
        gas = any(_yes(tags, k) for k in GAS)
        if liquid or not gas:
            cats.append("fuel")
        if gas:
            cats.append("gas")
    if highway in ("rest_area", "services"):
        cats.append("rest")
    elif amenity == "parking" and tags.get("hgv") in ("yes", "designated") and (tags.get("name") or tags.get("capacity:hgv")):
        cats.append("rest")
    return cats


def is_hgv(tags, cats):
    if tags.get("hgv") in ("yes", "designated"):
        return True
    if _yes(tags, "fuel:hgv_diesel"):
        return True
    if any(k.startswith("fuel:adblue") and v != "no" for k, v in tags.items()):
        return True
    return "capacity:hgv" in tags


def address(tags):
    street = tags.get("addr:street", "").strip()
    number = tags.get("addr:housenumber", "").strip()
    postcode = tags.get("addr:postcode", "").strip()
    city = tags.get("addr:city", "").strip()
    if not street and not city:
        return None
    parts = []
    if street:
        parts.append(f"{street} {number}".strip())
    if city or postcode:
        parts.append(f"{postcode} {city}".strip())
    return ", ".join(p for p in parts if p)


def _ring_points(ring):
    points = list(ring)
    if len(points) > 1 and points[0] == points[-1]:
        points = points[:-1]
    return points


def centroid(geometry):
    if not geometry:
        return None
    kind = geometry.get("type")
    coords = geometry.get("coordinates")
    if kind == "Point":
        points = [coords]
    elif kind == "LineString":
        points = list(coords)
    elif kind == "Polygon":
        points = _ring_points(coords[0]) if coords else []
    elif kind == "MultiPolygon":
        points = [p for polygon in coords for p in _ring_points(polygon[0])]
    else:
        return None
    points = [p for p in points if isinstance(p, (list, tuple)) and len(p) >= 2]
    if not points:
        return None
    lon = sum(p[0] for p in points) / len(points)
    lat = sum(p[1] for p in points) / len(points)
    return (lon, lat)


def _feature_id(feature):
    fid = feature.get("id")
    if isinstance(fid, str) and ID_PATTERN.match(fid):
        return fid
    props = feature.get("properties") or {}
    kind, oid = props.get("@type"), props.get("@id")
    if isinstance(kind, str) and kind and oid is not None:
        candidate = f"{kind[0]}{oid}"
        if ID_PATTERN.match(candidate):
            return candidate
    return None


def _text(value):
    if isinstance(value, str):
        value = value.strip()
        return value[:MAX_VALUE_LEN] if value else None
    return None


def osm_date(value):
    """Osmiumin `@timestamp` (ISO-merkkijono tai sekunnit) päivämääräksi YYYY-MM-DD; muu → None."""
    if isinstance(value, str) and DATE_PATTERN.match(value):
        return value[:10]
    if isinstance(value, (int, float)) and not isinstance(value, bool) and value > 0:
        return datetime.datetime.fromtimestamp(value, datetime.timezone.utc).date().isoformat()
    return None


def feature_to_rows(feature):
    fid = _feature_id(feature)
    props = feature.get("properties") or {}
    if fid is None or not isinstance(props, dict):
        return []
    tags = {k: v for k, v in props.items() if isinstance(v, str)}
    cats = classify(tags)
    if not cats:
        return []
    point = centroid(feature.get("geometry"))
    if point is None:
        return []
    lon, lat = point
    if not (-180 <= lon <= 180 and -90 <= lat <= 90):
        return []
    kept = normalize_tags(tags)
    hgv = 1 if is_hgv(kept, cats) else 0
    # Kohteen viimeisin muutos OSM:ssä: sovellus vertaa sitä kertatuonnin ja ilmoitusten päivämääriin.
    stamp = osm_date(props.get("@timestamp"))
    if stamp:
        kept["osm_ts"] = stamp
    return [
        {
            "id": fid,
            "cat": cat,
            "lat": round(lat, 6),
            "lon": round(lon, 6),
            "name": _text(tags.get("name")),
            "brand": _text(tags.get("brand")),
            "addr": address(tags),
            "hgv": hgv,
            "tags": kept,
        }
        for cat in cats
    ]


def read_geojsonseq(path):
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip().lstrip("\x1e").strip()
            if not line:
                continue
            yield json.loads(line)


def dedupe(rows):
    seen = set()
    out = []
    for row in rows:
        key = (row["id"], row["cat"])
        if key in seen:
            continue
        seen.add(key)
        out.append(row)
    return out


def summary(rows):
    counts = {cat: 0 for cat in CATS}
    for row in rows:
        counts[row["cat"]] = counts.get(row["cat"], 0) + 1
    return counts


def main(argv=None):
    parser = argparse.ArgumentParser(description="GeoJSON-rivit karttakohteiden riveiksi")
    parser.add_argument("--country", required=True, help="ISO 3166 -koodi (vain lokitukseen)")
    parser.add_argument("--summary", action="store_true", help="tulosta rivimäärät luokittain JSONina")
    parser.add_argument("source")
    parser.add_argument("target")
    args = parser.parse_args(argv)
    rows = dedupe(row for feature in read_geojsonseq(args.source) for row in feature_to_rows(feature))
    with open(args.target, "w", encoding="utf-8") as out:
        for row in rows:
            out.write(json.dumps(row, ensure_ascii=False) + "\n")
    counts = summary(rows)
    if args.summary:
        print(json.dumps({"country": args.country, **counts}))
    else:
        print(f"{args.country}: {len(rows)} riviä ({counts})", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
