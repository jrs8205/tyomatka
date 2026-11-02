import contextlib
import io
import json
import os
import re
import sys
import tempfile
import unittest
import urllib.error
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import afir  # noqa: E402
import border  # noqa: E402

FIXTURES = os.path.join(os.path.dirname(__file__), "fixtures")


def load(name):
    with open(os.path.join(FIXTURES, name), encoding="utf-8") as handle:
        return json.load(handle)


def rows():
    return afir.rows_from_afir(load("afir_locations.json")["features"], load("afir_tariffs.json")["tariffs"], "2026-10-23")


def by_name(name):
    return next(r for r in rows() if r["name"] == name)


class RowsTest(unittest.TestCase):
    def test_four_locations_give_three_rows_and_sea_is_dropped(self):
        result = rows()
        self.assertEqual(3, len(result))
        self.assertNotIn("Rakennus 7", [r["name"] for r in result])

    def test_ids_follow_the_c_pattern_and_rows_are_charging(self):
        pattern = re.compile(r"^c-[A-Z]{2}[A-Z0-9]{3}-[A-Za-z0-9._-]{1,64}$")
        for row in rows():
            self.assertRegex(row["id"], pattern)
            self.assertEqual("charging", row["cat"])
            self.assertEqual(0, row["hgv"])
        self.assertEqual("c-FITSL-46e8271d-b83e", by_name("Lataus Kylätie")["id"])

    def test_connectors_by_type_count_and_power_descending(self):
        tags = by_name("Lataus Kylätie")["tags"]
        self.assertEqual("CCS2:4:150;Type 2:2:22", tags["afir:connectors"])
        self.assertEqual("yes", tags["afir:dc"])
        self.assertEqual("Testilataus Oy", tags["afir:operator"])
        self.assertEqual("parking_lot", tags["afir:parking"])
        self.assertEqual("24/7", tags["afir:hours"])
        self.assertEqual("2026-10-23", tags["afir:date"])
        self.assertNotIn("afir:dc", by_name("Kauppakeskus P2")["tags"])

    def test_address_number_first_is_corrected_and_city_trimmed(self):
        self.assertEqual("Kylätie 9A, 01620 Vantaa", by_name("Lataus Kylätie")["addr"])

    def test_regular_hours_become_osm_format(self):
        self.assertEqual("Mo-Fr 07:00-21:00; Sa 09:00-18:00", by_name("Kauppakeskus P2")["tags"]["afir:hours"])

    def test_price_only_from_unambiguous_energy_tariff_with_vat(self):
        self.assertEqual("0.64 €/kWh", by_name("Lataus Kylätie")["tags"]["afir:price"])
        self.assertNotIn("afir:price", by_name("Kauppakeskus P2")["tags"])
        unnamed = next(r for r in rows() if r["id"] == "c-FIIOY-8f4db7d8-0cc7")
        self.assertNotIn("afir:price", unnamed["tags"])
        self.assertNotIn("afir:hours", unnamed["tags"])
        self.assertNotIn("afir:operator", unnamed["tags"])

    def test_missing_name_uses_operator_or_nothing(self):
        unnamed = next(r for r in rows() if r["id"] == "c-FIIOY-8f4db7d8-0cc7")
        self.assertIsNone(unnamed["name"])

    def test_other_countries_are_dropped(self):
        locations = load("afir_locations.json")["features"]
        locations[0]["properties"]["address"]["countryCode"] = "SWE"
        result = afir.rows_from_afir(locations, [], "2026-10-23")
        self.assertNotIn("c-FITSL-46e8271d-b83e", [r["id"] for r in result])


class MergeTest(unittest.TestCase):
    def osm(self, oid, cat, lat, lon):
        return {"id": oid, "cat": cat, "lat": lat, "lon": lon, "name": None, "brand": None, "addr": None, "hgv": 0, "tags": {}}

    def test_nearby_osm_charging_point_is_replaced_and_far_one_kept(self):
        station = by_name("Lataus Kylätie")
        near = self.osm("n1", "charging", station["lat"] + 0.00054, station["lon"])  # noin 60 m
        far = self.osm("n2", "charging", station["lat"] + 0.0027, station["lon"])  # noin 300 m
        fuel = self.osm("n3", "fuel", station["lat"], station["lon"])
        merged = afir.merge([near, far, fuel], [station])
        self.assertEqual(["n2", "n3", station["id"]], [r["id"] for r in merged])


class FetchTest(unittest.TestCase):
    def test_request_identifies_the_pipeline_and_accepts_gzip(self):
        seen = {}

        class Response(io.BytesIO):
            headers = {"Content-Encoding": "identity"}

            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

        def fake_urlopen(request, timeout):
            seen["url"] = request.full_url
            seen["headers"] = {k.lower(): v for k, v in request.header_items()}
            return Response(b'{"tariffs": []}')

        with mock.patch("urllib.request.urlopen", fake_urlopen):
            self.assertEqual({"tariffs": []}, afir.fetch("tariffs/all"))
        self.assertEqual(afir.BASE + "/tariffs/all", seen["url"])
        self.assertEqual("tyomatka-poi-pipeline", seen["headers"]["digitraffic-user"])
        self.assertEqual("gzip", seen["headers"]["accept-encoding"])

    def test_server_errors_are_retried_and_then_fail(self):
        calls = []

        def failing(request, timeout):
            calls.append(1)
            raise urllib.error.HTTPError(request.full_url, 503, "busy", {}, None)

        with mock.patch("urllib.request.urlopen", failing):
            with self.assertRaises(SystemExit):
                afir.fetch("locations/all", sleep=lambda s: None)
        self.assertEqual(afir.ATTEMPTS, len(calls))


class MergeFileTest(unittest.TestCase):
    def test_merge_cli_rewrites_rows_file_with_afir_rows(self):
        station = by_name("Lataus Kylätie")
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "FI.jsonl")
            near = {"id": "n1", "cat": "charging", "lat": station["lat"], "lon": station["lon"], "hgv": 0, "tags": {}}
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(json.dumps(near) + "\n")
            data = {"locations/all": load("afir_locations.json"), "tariffs/all": load("afir_tariffs.json")}
            with mock.patch.object(afir, "fetch", lambda p, **kw: data[p]):
                self.assertEqual(0, afir.main(["--merge", path, "--date", "2026-10-23"]))
            with open(path, encoding="utf-8") as handle:
                ids = [json.loads(line)["id"] for line in handle if line.strip()]
        self.assertNotIn("n1", ids)
        self.assertEqual(3, len(ids))


class BorderTest(unittest.TestCase):
    # Tornion kohdalla raja kulkee näiden välissä: länsipuoli on Ruotsia ja itäpuoli Suomea.
    FINLAND = (65.85, 24.1281)
    SWEDEN = (65.85, 24.1262)

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.path = os.path.join(tmp.name, "FI.jsonl")
        osm = {"id": "n1", "cat": "charging", "lat": self.FINLAND[0], "lon": self.FINLAND[1], "hgv": 0, "tags": {}}
        with open(self.path, "w", encoding="utf-8") as handle:
            handle.write(json.dumps(osm) + "\n")

    def location(self, lid, lat, lon):
        feature = load("afir_locations.json")["features"][0]
        feature["properties"]["id"] = lid
        feature["geometry"]["coordinates"] = [lon, lat]
        return feature

    def merge(self, finnish_rows):
        """Fintrafficin aineistossa on Helsingin rivejä ja yksi rivi rajan väärällä puolella."""
        features = [self.location(f"hki-{i}", 60.1699, 24.9384) for i in range(finnish_rows)]
        features.append(self.location("raja", *self.SWEDEN))
        data = {"locations/all": {"features": features}, "tariffs/all": {"tariffs": []}}
        log = io.StringIO()
        with mock.patch.object(afir, "fetch", lambda p, **kw: data[p]), contextlib.redirect_stderr(log):
            afir.main(["--merge", self.path, "--date", "2026-10-31"])
        return log.getvalue()

    def ids(self):
        with open(self.path, encoding="utf-8") as handle:
            return [json.loads(line)["id"] for line in handle if line.strip()]

    def test_points_are_close_and_on_different_sides_of_the_border(self):
        finland = border.Border.load(border.DEFAULT_BORDER)
        self.assertLess(afir._metres(*self.FINLAND, *self.SWEDEN), 100)
        self.assertTrue(finland.contains(*self.FINLAND))
        self.assertFalse(finland.contains(*self.SWEDEN))

    def test_row_across_the_border_does_not_take_the_finnish_point_with_it(self):
        log = self.merge(19)
        with contextlib.redirect_stdout(io.StringIO()):
            border.main(["--clip", self.path])

        ids = self.ids()
        self.assertIn("n1", ids)
        self.assertNotIn("c-FITSL-raja", ids)
        self.assertEqual(20, len(ids))
        self.assertIn("19 latauspistettä", log)
        self.assertIn("0 OSM-latauspistettä korvattu", log)
        self.assertIn("1 rajan ulkopuolella", log)

    def test_too_many_rows_across_the_border_stop_and_leave_rows_alone(self):
        with self.assertRaises(ValueError):
            self.merge(18)

        self.assertEqual(["n1"], self.ids())

    def test_broken_border_stops_and_leaves_rows_alone(self):
        broken = os.path.join(os.path.dirname(self.path), "rikki.geojson")
        with open(broken, "w", encoding="utf-8") as out:
            out.write("{")

        with mock.patch.object(border, "DEFAULT_BORDER", broken), self.assertRaises(ValueError):
            self.merge(19)

        self.assertEqual(["n1"], self.ids())


class AddressTest(unittest.TestCase):
    def test_placeholder_words_are_not_shown_as_an_address(self):
        for raw, expected in (
            ({"street": "Jukolantie 1", "postalCode": "UNKNOWN", "city": "Vaasa"}, "Jukolantie 1, Vaasa"),
            ({"street": "Mallikuja 2", "postalCode": "UNKNOWN", "city": "N/A"}, "Mallikuja 2"),
            ({"street": "Kaivopuistontie 14", "postalCode": "26100", "city": "N/A"}, "Kaivopuistontie 14, 26100"),
            ({"street": "Katu 3", "postalCode": "00100", "city": "x"}, "Katu 3, 00100"),
            ({"street": "Katu 4", "postalCode": "-", "city": "unknown"}, "Katu 4"),
        ):
            self.assertEqual(expected, afir.address(raw), raw)

    def test_address_needs_a_street(self):
        # Ilman katua kohde saa osoitteen käänteishausta kuten muutkin.
        for raw in (
            {"street": "Unknown", "postalCode": "00880", "city": "N/A"},
            {"street": "UNKNOWN", "postalCode": "UNKNOWN", "city": "UNKNOWN"},
            {"street": "-", "postalCode": "-", "city": "-"},
            {"postalCode": "00100", "city": "Helsinki"},
            {},
            None,
        ):
            self.assertIsNone(afir.address(raw), raw)

    def test_real_address_stays(self):
        self.assertEqual("Kylätie 9A, 01620 Vantaa", afir.address({"street": "9A, Kylätie", "city": " Vantaa ", "postalCode": "01620"}))


if __name__ == "__main__":
    unittest.main()
