"""Suomen rajaus: Suomen ote sisältää myös rajan takaisia kohteita, joten rivit rajataan Suomen todelliseen rajaan.

Raja on OSM-relaatio 54224 (Suomi, admin_level 2, sisältää Ahvenanmaan ja aluevedet) tiedostona
data/finland-border.geojson; lähde ja lisenssi ovat tiedoston properties-kentässä.
Käyttö: border.py --clip ROWS.jsonl [--border data/finland-border.geojson]   (kirjoittaa tiedoston uudelleen)

Rajatiedoston teko (osmium-tool):
  curl -o raja.osm https://api.openstreetmap.org/api/0.6/relation/54224/full
  osmium export raja.osm --geometry-types=polygon -f geojson -o finland-border.geojson
ja properties-kenttään nimi, lähde hakupäivineen ja lisenssi.
"""
import argparse
import json
import math
import os
import sys

DEFAULT_BORDER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "finland-border.geojson")
# Reunat jaetaan leveysvyöhykkeisiin, jotta piste vertautuu vain omalla leveydellään oleviin reunoihin.
BAND_DEGREES = 0.02
# Normaalisti rajan takaa putoaa alle prosentti riveistä; suurempi pudotus kertoo rikkinäisestä rajatiedostosta tai aineistosta.
MAX_DROP_SHARE = 0.05


class Border:
    """Alue monikulmioiden renkaista. Parillisuussääntö hoitaa reiät ja erilliset alueet; reuna kuuluu sisään."""

    def __init__(self, rings):
        self._south = min(point[1] for ring in rings for point in ring)
        self._bands = {}
        for ring in rings:
            for (x1, y1), (x2, y2) in zip(ring, ring[1:]):
                for band in range(self._band(min(y1, y2)), self._band(max(y1, y2)) + 1):
                    self._bands.setdefault(band, []).append((x1, y1, x2, y2))

    @classmethod
    def load(cls, path):
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
        rings = []
        for feature in data["features"]:
            geometry = feature["geometry"]
            if geometry["type"] == "Polygon":
                polygons = [geometry["coordinates"]]
            elif geometry["type"] == "MultiPolygon":
                polygons = geometry["coordinates"]
            else:
                raise ValueError(f"Rajatiedostossa on muu kuin alue: {geometry['type']}")
            rings += [[(point[0], point[1]) for point in ring] for polygon in polygons for ring in polygon]
        if not rings:
            raise ValueError(f"Rajatiedostossa ei ole alueita: {path}")
        return cls(rings)

    def _band(self, lat):
        return math.floor((lat - self._south) / BAND_DEGREES)

    def contains(self, lat, lon):
        inside = False
        for x1, y1, x2, y2 in self._bands.get(self._band(lat), ()):
            if _on_edge(lat, lon, x1, y1, x2, y2):
                return True
            # Säde itään; puoliavoin vertailu laskee säteen kulkiessa kulman kautta kulman kerran.
            if (y1 > lat) != (y2 > lat) and lon < x1 + (lat - y1) * (x2 - x1) / (y2 - y1):
                inside = not inside
        return inside


def _on_edge(lat, lon, x1, y1, x2, y2):
    if not (min(y1, y2) <= lat <= max(y1, y2) and min(x1, x2) <= lon <= max(x1, x2)):
        return False
    return (x2 - x1) * (lat - y1) == (y2 - y1) * (lon - x1)


def clip(rows, border):
    """Rajan sisällä olevat rivit ja pudotettujen määrä."""
    kept = [row for row in rows if border.contains(row["lat"], row["lon"])]
    return kept, len(rows) - len(kept)


def clip_checked(rows, border):
    """Kuten clip, mutta pysäyttää ajon, jos rivejä ei jää tai pudotus on epätavallisen suuri."""
    kept, dropped = clip(rows, border)
    if not kept or dropped > len(rows) * MAX_DROP_SHARE:
        raise ValueError(f"Rajaus pudottaisi {dropped} riviä {len(rows)}:stä: tarkista rajatiedosto ja aineisto ennen lähetystä.")
    return kept, dropped


def main(argv=None):
    parser = argparse.ArgumentParser(description="Rajaa rivit Suomen rajan sisäpuolelle")
    parser.add_argument("--clip", required=True, help="rivitiedosto, kirjoitetaan uudelleen")
    parser.add_argument("--border", default=DEFAULT_BORDER, help="rajatiedosto (GeoJSON)")
    args = parser.parse_args(argv)
    # Raja luetaan ensin: rikkinäinen rajatiedosto pysäyttää maan ennen lähetystä eikä koske riveihin.
    border = Border.load(args.border)
    with open(args.clip, encoding="utf-8") as handle:
        rows = [json.loads(line) for line in handle if line.strip()]
    kept, dropped = clip_checked(rows, border)
    with open(args.clip, "w", encoding="utf-8") as out:
        for row in kept:
            out.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"Suomen rajan ulkopuolelta pudotettu {dropped} riviä, jäljellä {len(kept)}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
