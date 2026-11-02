import io
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import transform  # noqa: E402
import upload  # noqa: E402

FIXTURE = os.path.join(os.path.dirname(__file__), "fixtures", "sample.geojsonl")


def rows_by_id():
    rows = transform.dedupe(r for f in transform.read_geojsonseq(FIXTURE) for r in transform.feature_to_rows(f))
    out = {}
    for row in rows:
        out.setdefault(row["id"], []).append(row)
    return out


class ClassifyTest(unittest.TestCase):
    def setUp(self):
        self.rows = rows_by_id()

    def test_truck_station_is_fuel_with_hgv_and_lowercase_tags(self):
        (row,) = self.rows["n1001"]
        self.assertEqual("fuel", row["cat"])
        self.assertEqual(1, row["hgv"])
        self.assertEqual("yes", row["tags"]["fuel:hgv_diesel"])
        self.assertEqual("yes", row["tags"]["fuel:adblue"])
        self.assertNotIn("fuel:HGV_diesel", row["tags"])
        self.assertNotIn("name:sv", row["tags"])
        self.assertEqual("Neste Truck Vantaa Hakkila", row["name"])
        self.assertEqual("Neste", row["brand"])
        self.assertEqual("Esimerkkikaari 2, 01380 Vantaa", row["addr"])
        self.assertAlmostEqual(60.3301, row["lat"])
        self.assertAlmostEqual(25.0512, row["lon"])

    def test_lpg_only_station_is_gas_only(self):
        self.assertEqual(["gas"], [r["cat"] for r in self.rows["n1002"]])

    def test_diesel_and_cng_station_is_both_fuel_and_gas(self):
        self.assertEqual(["fuel", "gas"], sorted(r["cat"] for r in self.rows["n1003"]))

    def test_charging_polygon_uses_centroid(self):
        (row,) = self.rows["w2001"]
        self.assertEqual("charging", row["cat"])
        self.assertAlmostEqual(24.801, row["lon"], places=4)
        self.assertAlmostEqual(60.5005, row["lat"], places=4)
        self.assertEqual("4", row["tags"]["socket:type2_combo"])

    def test_private_access_is_dropped(self):
        self.assertNotIn("n1004", self.rows)

    def test_unnamed_truck_parking_without_capacity_is_dropped(self):
        self.assertNotIn("w2002", self.rows)

    def test_truck_parking_with_capacity_is_rest_and_hgv(self):
        (row,) = self.rows["w2003"]
        self.assertEqual("rest", row["cat"])
        self.assertEqual(1, row["hgv"])

    def test_services_is_rest_with_city_only_address(self):
        (row,) = self.rows["w2004"]
        self.assertEqual("rest", row["cat"])
        self.assertEqual(0, row["hgv"])
        self.assertEqual("Alavus", row["addr"])

    def test_rest_area_linestring_uses_midpoint(self):
        (row,) = self.rows["w2005"]
        self.assertEqual("rest", row["cat"])
        self.assertAlmostEqual(24.301, row["lon"], places=4)
        self.assertAlmostEqual(61.0005, row["lat"], places=4)

    def test_plain_parking_is_not_a_poi(self):
        self.assertNotIn("n1005", self.rows)

    def test_duplicate_id_and_cat_is_kept_once(self):
        self.assertEqual(1, len(self.rows["n1001"]))

    def test_unnamed_station_has_no_name_and_values_are_truncated(self):
        (row,) = self.rows["n1006"]
        self.assertIsNone(row["name"])
        self.assertEqual("St1", row["brand"])
        self.assertNotIn("description", row["tags"])
        self.assertEqual(200, len(row["tags"]["opening_hours"]))


class HelpersTest(unittest.TestCase):
    def test_address_requires_street_or_city(self):
        self.assertIsNone(transform.address({"addr:housenumber": "2"}))
        self.assertEqual("Esimerkkikaari 2, Vantaa", transform.address({"addr:street": "Esimerkkikaari", "addr:housenumber": "2", "addr:city": "Vantaa"}))
        self.assertEqual("Esimerkkikaari, 01380 Vantaa", transform.address({"addr:street": "Esimerkkikaari", "addr:postcode": "01380", "addr:city": "Vantaa"}))

    def test_read_geojsonseq_skips_record_separators_and_blank_lines(self):
        with tempfile.NamedTemporaryFile("w", suffix=".geojsonseq", delete=False, encoding="utf-8") as f:
            f.write('\x1e{"type":"Feature","id":"n1","geometry":{"type":"Point","coordinates":[1,2]},"properties":{}}\n\n')
            f.write('\x1e{"type":"Feature","id":"n2","geometry":{"type":"Point","coordinates":[3,4]},"properties":{}}\n')
            path = f.name
        try:
            self.assertEqual(["n1", "n2"], [f["id"] for f in transform.read_geojsonseq(path)])
        finally:
            os.unlink(path)

    def test_osm_timestamp_becomes_date_tag(self):
        feature = {
            "type": "Feature",
            "id": "n42",
            "geometry": {"type": "Point", "coordinates": [24.9, 60.2]},
            "properties": {"amenity": "fuel", "@timestamp": "2026-09-03T10:00:00Z"},
        }
        (row,) = transform.feature_to_rows(feature)
        self.assertEqual("2026-09-03", row["tags"]["osm_ts"])
        self.assertNotIn("@timestamp", row["tags"])
        feature["properties"]["@timestamp"] = 1788429600  # 2026-09-03T10:00:00Z sekunteina
        (row,) = transform.feature_to_rows(feature)
        self.assertEqual("2026-09-03", row["tags"]["osm_ts"])
        feature["properties"]["@timestamp"] = "eilen"
        (row,) = transform.feature_to_rows(feature)
        self.assertNotIn("osm_ts", row["tags"])

    def test_summary_counts_rows_per_category(self):
        rows = [{"cat": "fuel"}, {"cat": "fuel"}, {"cat": "rest"}]
        self.assertEqual({"fuel": 2, "charging": 0, "gas": 0, "rest": 1}, transform.summary(rows))


class UploadTest(unittest.TestCase):
    def test_chunks_of_500(self):
        rows = list(range(1201))
        chunks = list(upload.chunks(rows, 500))
        self.assertEqual([500, 500, 201], [len(c) for c in chunks])

    def test_backoff_doubles_from_two_seconds(self):
        self.assertEqual([2, 4, 8, 16, 32], [upload.backoff_seconds(i) for i in range(5)])

    def test_request_headers_identify_the_pipeline(self):
        headers = upload.headers("salainen")
        self.assertEqual("Bearer salainen", headers["Authorization"])
        self.assertTrue(headers["User-Agent"].startswith("tyomatka-poi-pipeline/"))
        self.assertIn("application/json", headers["Content-Type"])

    def test_rows_to_payload_keeps_only_api_fields(self):
        payload = json.loads(upload.payload("FI", [{"id": "n1", "cat": "fuel", "lat": 60.0, "lon": 24.0, "name": None, "brand": "X", "addr": None, "hgv": 1, "tags": {"a": "b"}, "extra": 1}]))
        self.assertEqual("FI", payload["country"])
        self.assertEqual({"id": "n1", "cat": "fuel", "lat": 60.0, "lon": 24.0, "brand": "X", "hgv": 1, "tags": {"a": "b"}}, payload["rows"][0])


class FakeResponse:
    def __init__(self, body=b"{}"):
        self.body = body

    def read(self):
        return self.body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class PostRetryTest(unittest.TestCase):
    def setUp(self):
        self.original = upload.urllib.request.urlopen
        self.sleeps = []

    def tearDown(self):
        upload.urllib.request.urlopen = self.original

    def respond(self, *outcomes):
        calls = []

        def fake(request, timeout):
            calls.append(request.full_url)
            outcome = outcomes[len(calls) - 1]
            if isinstance(outcome, BaseException):
                raise outcome
            return outcome

        upload.urllib.request.urlopen = fake
        return calls

    def post(self, path="/admin/import"):
        return upload.post("https://poi.example", "t", path, "{}", sleep=self.sleeps.append)

    def test_timeout_reset_and_dropped_connection_are_retried(self):
        calls = self.respond(
            TimeoutError("timed out"),
            ConnectionResetError("reset"),
            upload.http.client.RemoteDisconnected("closed"),
            FakeResponse(b'{"inserted": 3}'),
        )
        self.assertEqual({"inserted": 3}, self.post())
        self.assertEqual(4, len(calls))
        self.assertEqual([2, 4, 8], self.sleeps)

    def test_network_errors_give_up_after_all_attempts(self):
        self.respond(*[ConnectionResetError("reset")] * upload.ATTEMPTS)
        with self.assertRaises(SystemExit) as raised:
            self.post()
        self.assertIn("verkkovirhe", str(raised.exception))

    def test_truncated_error_body_still_reports_the_status(self):
        class BrokenBody(io.BytesIO):
            def read(self, *args):
                raise upload.http.client.IncompleteRead(b"{")

        conflict = upload.urllib.error.HTTPError("https://poi.example/admin/swap", 409, "Conflict", {}, BrokenBody())
        self.respond(conflict)
        with self.assertRaises(SystemExit) as raised:
            self.post("/admin/swap")
        self.assertEqual("/admin/swap: HTTP 409", str(raised.exception))

    def test_conflict_is_not_retried_and_reports_server_reason(self):
        conflict = upload.urllib.error.HTTPError(
            "https://poi.example/admin/swap", 409, "Conflict", {}, io.BytesIO('{"error": "välitaulu on tyhjä"}'.encode())
        )
        calls = self.respond(conflict)
        with self.assertRaises(SystemExit) as raised:
            self.post("/admin/swap")
        self.assertEqual(1, len(calls))
        self.assertIn("409", str(raised.exception))
        self.assertIn("välitaulu on tyhjä", str(raised.exception))


class MainTest(unittest.TestCase):
    def setUp(self):
        self.original = upload.urllib.request.urlopen
        self.sent = []

        def fake(request, timeout):
            self.sent.append((request.full_url.rsplit("/", 1)[-1], json.loads(request.data.decode("utf-8"))))
            return FakeResponse(b'{"inserted": 1}')

        upload.urllib.request.urlopen = fake
        os.environ["POI_BASE_URL"] = "https://poi.example"
        os.environ["POI_ADMIN_TOKEN"] = "t"
        handle = tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False, encoding="utf-8")
        handle.write(json.dumps({"id": "n1", "cat": "fuel", "lat": 60.0, "lon": 24.0, "hgv": 0, "tags": {}}) + "\n")
        handle.close()
        self.rows = handle.name

    def tearDown(self):
        upload.urllib.request.urlopen = self.original
        os.unlink(self.rows)
        del os.environ["POI_BASE_URL"], os.environ["POI_ADMIN_TOKEN"]

    def test_swap_is_not_forced_by_default(self):
        upload.main(["--country", "FI", "--extract-date", "2026-10-20", self.rows])
        self.assertEqual(["begin", "import", "swap"], [path for path, _ in self.sent])
        self.assertNotIn("force", self.sent[-1][1])

    def test_force_from_environment_for_run_sh_and_workflow(self):
        os.environ["POI_FORCE"] = "1"
        try:
            upload.main(["--country", "FI", "--extract-date", "2026-10-20", self.rows])
        finally:
            del os.environ["POI_FORCE"]
        self.assertIs(True, self.sent[-1][1]["force"])

    def test_force_flag_allows_a_steep_drop(self):
        upload.main(["--country", "FI", "--extract-date", "2026-10-20", "--force", self.rows])
        self.assertIs(True, self.sent[-1][1]["force"])


if __name__ == "__main__":
    unittest.main()
