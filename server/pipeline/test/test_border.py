"""Suomen raja: pistetesti oikealla rajatiedostolla, rivien rajaus ja komentorivi."""
import contextlib
import io
import json
import os
import random
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import border  # noqa: E402

REAL_BORDER = os.path.join(os.path.dirname(__file__), "..", "data", "finland-border.geojson")


def write_geojson(directory, geometry):
    path = os.path.join(directory, "raja.geojson")
    with open(path, "w", encoding="utf-8") as out:
        json.dump({"type": "FeatureCollection", "features": [{"type": "Feature", "properties": {}, "geometry": geometry}]}, out)
    return path


def square(west, south, east, north):
    return [[west, south], [east, south], [east, north], [west, north], [west, south]]


class RealBorderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.border = border.Border.load(REAL_BORDER)

    def test_finnish_places_are_inside(self):
        for name, lat, lon in (
            ("Helsinki", 60.1699, 24.9384),
            ("Maarianhamina", 60.0973, 19.9348),
            ("Utsjoki", 69.9077, 27.0265),
            ("Tornio", 65.8500, 24.1800),
            ("Karigasniemi", 69.3986, 25.8442),
        ):
            self.assertTrue(self.border.contains(lat, lon), name)

    def test_places_abroad_are_outside(self):
        for name, lat, lon in (
            ("Haaparanta", 65.8330, 24.1100),
            ("Luulaja", 65.5848, 22.1547),
            ("Karasjok", 69.4719, 25.5112),
            ("Tallinna", 59.4370, 24.7536),
            ("Viipuri", 60.7096, 28.7490),
        ):
            self.assertFalse(self.border.contains(lat, lon), name)

    def test_ten_thousand_points_take_under_five_seconds(self):
        rng = random.Random(1)
        points = [(rng.uniform(59.0, 71.0), rng.uniform(18.0, 33.0)) for _ in range(10_000)]

        started = time.perf_counter()
        found = sum(self.border.contains(lat, lon) for lat, lon in points)
        elapsed = time.perf_counter() - started

        self.assertLess(elapsed, 5.0)
        # Suomi peittää selvän osan laatikosta, muttei kaikkea.
        self.assertGreater(found, 1000)
        self.assertLess(found, 9000)

    def test_file_names_its_source_and_license(self):
        with open(REAL_BORDER, encoding="utf-8") as handle:
            properties = json.load(handle)["features"][0]["properties"]

        self.assertIn("54224", properties["source"])
        self.assertIn("ODbL", properties["license"])
        self.assertIn("https://opendatacommons.org/licenses/odbl/1-0/", properties["license"])


class ShapeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def load(self, geometry):
        return border.Border.load(write_geojson(self.tmp.name, geometry))

    def test_multipolygon_with_hole(self):
        shape = self.load({
            "type": "MultiPolygon",
            "coordinates": [[square(10, 10, 12, 12), square(10.5, 10.5, 11.5, 11.5)], [square(20, 20, 21, 21)]],
        })

        self.assertTrue(shape.contains(10.2, 10.2))
        self.assertFalse(shape.contains(11.0, 11.0), "reikä")
        self.assertTrue(shape.contains(20.5, 20.5), "toinen alue")
        self.assertFalse(shape.contains(15.0, 15.0), "alueiden välissä")
        self.assertFalse(shape.contains(10.5, 9.0), "länsipuolella samalla leveydellä")
        self.assertFalse(shape.contains(10.5, 13.0), "itäpuolella samalla leveydellä")
        self.assertFalse(shape.contains(9.0, 10.5), "eteläpuolella")
        self.assertFalse(shape.contains(13.0, 10.5), "pohjoispuolella")

    def test_polygon_geometry_is_accepted(self):
        shape = self.load({"type": "Polygon", "coordinates": [square(10, 10, 12, 12)]})

        self.assertTrue(shape.contains(11.0, 11.0))
        self.assertFalse(shape.contains(13.0, 11.0))

    def test_edge_belongs_to_the_area(self):
        shape = self.load({"type": "Polygon", "coordinates": [square(10, 10, 12, 12)]})

        for name, lat, lon in (
            ("etelä", 10.0, 11.0),
            ("pohjoinen", 12.0, 11.0),
            ("länsi", 11.0, 10.0),
            ("itä", 11.0, 12.0),
            ("kulma", 12.0, 12.0),
            ("toinen kulma", 10.0, 10.0),
        ):
            self.assertTrue(shape.contains(lat, lon), name)
        # Pystysuoran reunan jatke samalla leveysvyöhykkeellä ei ole reunaa.
        self.assertFalse(shape.contains(12.005, 10.0))

    def test_sloping_edge_belongs_to_the_area(self):
        shape = self.load({"type": "Polygon", "coordinates": [[[0, 0], [4, 0], [0, 4], [0, 0]]]})

        self.assertTrue(shape.contains(2.0, 2.0))
        self.assertFalse(shape.contains(2.01, 2.01))

    def test_point_level_with_a_corner_is_counted_correctly(self):
        # Vaakasuora säde kulkee salmiakin kulmien kautta: kulma ei saa laskea kahdesti.
        shape = self.load({"type": "Polygon", "coordinates": [[[0, -1], [1, 0], [0, 1], [-1, 0], [0, -1]]]})

        self.assertTrue(shape.contains(0.0, 0.0))
        self.assertFalse(shape.contains(0.0, -2.0))
        self.assertFalse(shape.contains(0.0, 2.0))

    def test_file_without_area_is_an_error(self):
        path = os.path.join(self.tmp.name, "tyhja.geojson")
        with open(path, "w", encoding="utf-8") as out:
            json.dump({"type": "FeatureCollection", "features": []}, out)

        with self.assertRaises(ValueError):
            border.Border.load(path)

    def test_other_geometry_beside_an_area_is_an_error(self):
        path = os.path.join(self.tmp.name, "seka.geojson")
        with open(path, "w", encoding="utf-8") as out:
            json.dump({"type": "FeatureCollection", "features": [
                {"type": "Feature", "properties": {}, "geometry": {"type": "Polygon", "coordinates": [square(10, 10, 12, 12)]}},
                {"type": "Feature", "properties": {}, "geometry": {"type": "LineString", "coordinates": [[0, 0], [1, 1]]}},
            ]}, out)

        with self.assertRaises(ValueError):
            border.Border.load(path)


class ClipTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.border_path = write_geojson(self.tmp.name, {"type": "Polygon", "coordinates": [square(10, 10, 12, 12)]})
        self.rows = [
            {"id": "a", "lat": 11.0, "lon": 11.0, "name": "Äänekoski"},
            {"id": "b", "lat": 13.0, "lon": 11.0, "name": "Ulkona"},
            {"id": "c", "lat": 10.5, "lon": 10.5, "name": "Mukana"},
        ]

    def test_clip_drops_outside_rows_and_tells_how_many(self):
        kept, dropped = border.clip(self.rows, border.Border.load(self.border_path))

        self.assertEqual(["a", "c"], [row["id"] for row in kept])
        self.assertEqual(self.rows[0], kept[0])
        self.assertEqual(1, dropped)

    def write_rows(self, rows):
        path = os.path.join(self.tmp.name, "rivit.jsonl")
        with open(path, "w", encoding="utf-8") as out:
            for row in rows:
                out.write(json.dumps(row, ensure_ascii=False) + "\n")
        return path

    def read_text(self, path):
        with open(path, encoding="utf-8") as handle:
            return handle.read()

    def inside(self, count):
        return [{"id": f"s{i}", "lat": 11.0, "lon": 11.0} for i in range(count)]

    def outside(self, count):
        return [{"id": f"u{i}", "lat": 13.0, "lon": 11.0} for i in range(count)]

    def test_command_rewrites_the_file_and_reports_the_count(self):
        path = self.write_rows(self.rows + self.inside(40))
        printed = io.StringIO()

        with contextlib.redirect_stdout(printed):
            status = border.main(["--clip", path, "--border", self.border_path])

        self.assertEqual(0, status)
        lines = self.read_text(path).splitlines()
        self.assertEqual(["a", "c"], [json.loads(line)["id"] for line in lines[:2]])
        self.assertEqual(42, len(lines))
        self.assertIn("Äänekoski", lines[0])
        self.assertIn("pudotettu 1 ", printed.getvalue())

    def test_command_uses_the_finnish_border_by_default(self):
        finnish = [{"id": f"fi{i}", "lat": 60.1699, "lon": 24.9384} for i in range(40)]
        path = self.write_rows(finnish + [{"id": "se", "lat": 65.8330, "lon": 24.1100}])

        with contextlib.redirect_stdout(io.StringIO()):
            border.main(["--clip", path])

        self.assertEqual(finnish, [json.loads(line) for line in self.read_text(path).splitlines()])

    def test_drop_of_five_percent_passes(self):
        path = self.write_rows(self.inside(19) + self.outside(1))

        with contextlib.redirect_stdout(io.StringIO()):
            status = border.main(["--clip", path, "--border", self.border_path])

        self.assertEqual(0, status)
        self.assertEqual(19, len(self.read_text(path).splitlines()))

    def test_larger_drop_stops_the_command_and_leaves_rows_alone(self):
        # Normaali pudotus on alle prosentti: suurempi kertoo rikkinäisestä rajatiedostosta tai aineistosta.
        path = self.write_rows(self.inside(18) + self.outside(1))
        before = self.read_text(path)

        with self.assertRaises(ValueError):
            border.main(["--clip", path, "--border", self.border_path])

        self.assertEqual(before, self.read_text(path))

    def test_empty_file_stops_the_command(self):
        path = self.write_rows([])

        with self.assertRaises(ValueError):
            border.main(["--clip", path, "--border", self.border_path])

    def test_broken_border_stops_the_command_and_leaves_rows_alone(self):
        path = self.write_rows(self.rows)
        before = self.read_text(path)
        broken = os.path.join(self.tmp.name, "rikki.geojson")
        with open(broken, "w", encoding="utf-8") as out:
            out.write("{")

        with self.assertRaises(ValueError):
            border.main(["--clip", path, "--border", broken])

        self.assertEqual(before, self.read_text(path))


if __name__ == "__main__":
    unittest.main()
