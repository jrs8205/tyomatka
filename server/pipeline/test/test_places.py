"""Yrityshaun putki: julkaisun valinta, suodatus, kaksoiskappaleet ja tuonnin kutsujärjestys valepalvelimella."""
import io
import json
import os
import sys
import unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import places  # noqa: E402

LISTING = """<?xml version="1.0" encoding="UTF-8"?>
<ListBucketResult><Prefix>release/</Prefix>
<CommonPrefixes><Prefix>release/2026-09-23.0/</Prefix></CommonPrefixes>
<CommonPrefixes><Prefix>release/2026-10-21.0/</Prefix></CommonPrefixes>
<CommonPrefixes><Prefix>release/2026-09-23.1/</Prefix></CommonPrefixes>
<CommonPrefixes><Prefix>release/latest-beta/</Prefix></CommonPrefixes>
</ListBucketResult>"""


def row(name="Askolan Apteekki", street="Pappilantie 2", conf=0.9, lat=60.5, lon=25.6, **extra):
    base = {
        "id": "37f9b45c-9227-4fa9-ad8d-9fae83427804",
        "name": name,
        "category": "pharmacy_and_drug_store",
        "street": street,
        "postcode": "07500",
        "locality": "Askola",
        "confidence": conf,
        "operating_status": "open",
        "lat": lat,
        "lon": lon,
    }
    base.update(extra)
    return base


class ReleaseTest(unittest.TestCase):
    def test_latest_release_picks_newest_dated_release(self):
        self.assertEqual(places.latest_release(places.parse_releases(LISTING), today="2026-10-28"), "2026-10-21.0")

    def test_minor_version_counts(self):
        self.assertEqual(places.latest_release(["2026-09-23.0", "2026-09-23.1"], today="2026-10-28"), "2026-09-23.1")

    def test_release_of_today_may_still_be_uploading(self):
        self.assertEqual(places.latest_release(["2026-09-23.1", "2026-10-21.0"], today="2026-10-21"), "2026-09-23.1")

    def test_no_release_is_an_error(self):
        with self.assertRaises(SystemExit):
            places.latest_release([], today="2026-10-28")
        with self.assertRaises(SystemExit):
            places.latest_release(["2026-10-28.0"], today="2026-10-28")

    def test_extract_sql_names_release_and_countries(self):
        sql = places.extract_sql("2026-10-21.0")
        self.assertIn("s3://overturemaps-us-west-2/release/2026-10-21.0/theme=places/type=place/*", sql)
        self.assertIn("'FI'", sql)
        self.assertIn("'AX'", sql)
        self.assertNotIn("->", sql)


class KeepTest(unittest.TestCase):
    def test_rejects_nameless_closed_unsure_and_nature(self):
        self.assertFalse(places.keep(row(name="")))
        self.assertFalse(places.keep(row(name="   ")))
        self.assertFalse(places.keep(row(name=" 	")))
        self.assertFalse(places.keep(row(operating_status="permanently_closed")))
        self.assertFalse(places.keep(row(conf=0.29)))
        self.assertFalse(places.keep(row(category="lake")))

    def test_accepts_unknown_status_and_threshold(self):
        self.assertTrue(places.keep(row(operating_status=None)))
        self.assertTrue(places.keep(row(conf=0.3)))
        self.assertTrue(places.keep(row(category=None)))


class TokensTest(unittest.TestCase):
    def test_same_words_as_the_server(self):
        path = os.path.join(os.path.dirname(__file__), "..", "..", "test", "tokens-cases.json")
        with open(path, encoding="utf-8") as f:
            cases = json.load(f)
        self.assertGreater(len(cases), 10)
        for case in cases:
            with self.subTest(text=ascii(case["text"])):
                self.assertEqual(places.tokens(case["text"]), case["words"])


class DedupeTest(unittest.TestCase):
    def test_tmi_and_unicode_forms_do_not_separate(self):
        kept = places.dedupe([row(name="T:mi Autoapu", id="a"), row(name="Autoapu", id="b", conf=0.5)])
        self.assertEqual([r["id"] for r in kept], ["a"])
        kept = places.dedupe([row(name="M\u00e4ki", id="a"), row(name="Ma\u0308ki", id="b", conf=0.5)])
        self.assertEqual([r["id"] for r in kept], ["a"])

    def test_same_name_and_street_keeps_most_confident(self):
        kept = places.dedupe([row(name="Askolan apteekki", conf=0.95, id="a"), row(name="Askolan Apteekki", conf=0.98, id="b")])
        self.assertEqual([r["id"] for r in kept], ["b"])

    def test_same_name_without_street_close_is_one(self):
        kept = places.dedupe([row(street=None, id="a"), row(street=None, id="b", lat=60.50027)])  # noin 30 m
        self.assertEqual(len(kept), 1)

    def test_same_name_without_street_far_is_two(self):
        kept = places.dedupe([row(street=None, id="a"), row(street=None, id="b", lat=60.5018)])  # noin 200 m
        self.assertEqual(len(kept), 2)

    def test_legal_form_does_not_separate(self):
        kept = places.dedupe([row(name="Autokiri Oy", id="a"), row(name="Autokiri", id="b", conf=0.5)])
        self.assertEqual([r["id"] for r in kept], ["a"])

    def test_same_name_and_street_in_another_town_is_two(self):
        kept = places.dedupe([
            row(name="Neste", street="Asematie 1", postcode="07500", locality="Askola", id="a"),
            row(name="Neste", street="Asematie 1", postcode="04600", locality="Mäntsälä", id="b", lat=61.4),
        ])
        self.assertEqual(len(kept), 2)

    def test_same_street_without_postcode_compares_locality(self):
        kept = places.dedupe([
            row(name="Neste", street="Asematie 1", postcode=None, locality="Askola", id="a"),
            row(name="Neste", street="Asematie 1", postcode=None, locality="ASKOLA", id="b", lat=60.51, conf=0.5),
        ])
        self.assertEqual([r["id"] for r in kept], ["a"])

    def test_different_street_far_apart_is_two(self):
        kept = places.dedupe([row(name="Lidl", street="Mannerheimintie 1", id="a"), row(name="Lidl", street="Hämeentie 2", id="b", lat=60.6)])
        self.assertEqual(len(kept), 2)


class LongFieldTest(unittest.TestCase):
    """Palvelin hylkää koko 500 rivin erän, jos yksikin kenttä on yli 200 merkkiä (Suomessa katu enimmillään 221)."""

    def test_overlong_name_is_dropped(self):
        self.assertFalse(places.keep(row(name="A" * 201)))
        self.assertTrue(places.keep(row(name="A" * 200)))

    def test_overlong_optional_field_is_left_out(self):
        api = places.api_row(row(street="Katu " * 45))
        self.assertNotIn("street", api)
        self.assertEqual(api["locality"], "Askola")


class ServerRulesTest(unittest.TestCase):
    """Rivi, jonka palvelin hylkäisi, kaataisi koko erän (400 ei uusiudu): se jätetään pois ennen lähetystä."""

    def test_length_counts_utf16_units_like_the_server(self):
        emoji_name = "🚚" * 101  # 101 merkkiä Pythonissa, 202 UTF-16-yksikköä
        self.assertFalse(places.keep(row(name=emoji_name)))
        self.assertNotIn("street", places.api_row(row(street="🚚" * 101)))

    def test_valid_row_matches_server_checks(self):
        good = places.api_row(row())
        self.assertTrue(places.valid_row(good))
        self.assertFalse(places.valid_row({**good, "id": good["id"].upper()}))
        self.assertFalse(places.valid_row({**good, "id": "x"}))
        self.assertFalse(places.valid_row({**good, "lat": float("nan")}))
        self.assertFalse(places.valid_row({**good, "lon": 181.0}))
        self.assertFalse(places.valid_row({**good, "conf": 1.5}))


class ApiRowTest(unittest.TestCase):
    def test_api_row_renames_confidence_and_omits_missing(self):
        self.assertEqual(
            places.api_row(row(street=None, postcode=None)),
            {"id": "37f9b45c-9227-4fa9-ad8d-9fae83427804", "name": "Askolan Apteekki", "locality": "Askola", "lat": 60.5, "lon": 25.6, "conf": 0.9},
        )


class FakeServer:
    def __init__(self, release, prune=(5,)):
        self.calls = []
        self.release = release
        self.prune = list(prune)

    def post(self, base_url, token, path, body, sleep=None):
        self.calls.append((path, json.loads(body)))
        if path == "/admin/places/status":
            return {"active": 1, "staging": None, "release": self.release, "rows": 10}
        if path == "/admin/places/begin":
            return {"gen": 2}
        if path == "/admin/places/import":
            return {"inserted": len(json.loads(body)["rows"])}
        if path == "/admin/places/swap":
            return {"ok": True, "rows": 3}
        if path == "/admin/places/prune":
            return {"deleted": self.prune.pop(0) if self.prune else 0}
        raise AssertionError(path)


ENV = {"POI_BASE_URL": "https://poi.example", "POI_ADMIN_TOKEN": "salainen"}


def run_main(server, rows, argv=(), env=ENV):
    with mock.patch.dict(os.environ, env, clear=False), redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
        return places.main(list(argv), post=server.post, releases=lambda: ["2026-10-21.0"], extract=lambda release: rows, today="2026-10-28")


class MainTest(unittest.TestCase):
    def setUp(self):
        os.environ.pop("POI_FORCE", None)

    def test_same_release_only_prunes_leftovers(self):
        server = FakeServer("2026-10-21.0")
        self.assertEqual(run_main(server, [row()]), 0)
        self.assertEqual([c[0] for c in server.calls], ["/admin/places/status", "/admin/places/prune", "/admin/places/prune"])

    def test_older_release_on_s3_does_not_replace_newer(self):
        server = FakeServer("2026-11-18.0")
        self.assertEqual(run_main(server, [row()]), 0)
        self.assertNotIn("/admin/places/begin", [c[0] for c in server.calls])

    def test_new_release_imports_swaps_and_prunes(self):
        server = FakeServer("2026-09-23.1")
        rows = [row(name=f"Yritys {i}", id=f"{i:08d}-0000-4000-8000-000000000000", street=None, lat=60 + i * 0.01) for i in range(501)]
        self.assertEqual(run_main(server, rows), 0)
        paths = [c[0] for c in server.calls]
        self.assertEqual(
            paths,
            ["/admin/places/status", "/admin/places/begin", "/admin/places/prune", "/admin/places/prune",
             "/admin/places/import", "/admin/places/import", "/admin/places/swap", "/admin/places/prune"],
        )
        self.assertEqual([len(c[1]["rows"]) for c in server.calls if c[0] == "/admin/places/import"], [500, 1])
        self.assertTrue(all(c[1]["gen"] == 2 for c in server.calls if c[0] == "/admin/places/import"))
        self.assertEqual(server.calls[6][1], {"gen": 2, "release": "2026-10-21.0"})

    def test_filters_and_dedupes_before_import(self):
        server = FakeServer("old")
        a, b, c = (f"00000000-0000-4000-8000-00000000000{x}" for x in "abc")
        run_main(server, [row(id=a), row(id=b, conf=0.95), row(id=c, category="lake", name="Iso järvi")])
        imported = [r["id"] for c in server.calls if c[0] == "/admin/places/import" for r in c[1]["rows"]]
        self.assertEqual(imported, [b])

    def test_invalid_rows_are_left_out(self):
        server = FakeServer("old")
        run_main(server, [row(id="a"), row(name="Toinen", id="37f9b45c-9227-4fa9-ad8d-9fae83427805", lat=61.0)])
        imported = [r["id"] for c in server.calls if c[0] == "/admin/places/import" for r in c[1]["rows"]]
        self.assertEqual(imported, ["37f9b45c-9227-4fa9-ad8d-9fae83427805"])

    def test_force_flag(self):
        server = FakeServer("old")
        run_main(server, [row()], argv=["--force"])
        self.assertEqual([c[1].get("force") for c in server.calls if c[0] == "/admin/places/swap"], [True])

    def test_import_anyway_and_force(self):
        server = FakeServer("2026-10-21.0")
        run_main(server, [row()], argv=["--import-anyway"], env={**ENV, "POI_FORCE": "1"})
        swap = [c[1] for c in server.calls if c[0] == "/admin/places/swap"]
        self.assertEqual(swap, [{"gen": 2, "release": "2026-10-21.0", "force": True}])

    def test_dry_run_sends_nothing(self):
        server = FakeServer("old")
        self.assertEqual(run_main(server, [row()], argv=["--dry-run"], env={}), 0)
        self.assertEqual(server.calls, [])

    def test_missing_credentials_stop_before_any_call(self):
        server = FakeServer("old")
        with mock.patch.dict(os.environ, {"POI_BASE_URL": "", "POI_ADMIN_TOKEN": ""}):
            with self.assertRaises(SystemExit):
                places.main([], post=server.post, releases=lambda: ["2026-10-21.0"], extract=lambda release: [], today="2026-10-28")
        self.assertEqual(server.calls, [])


if __name__ == "__main__":
    unittest.main()
