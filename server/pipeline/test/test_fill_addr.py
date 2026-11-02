"""Osoitteiden täydennys: Digitransitin vastauksen muoto, välimuistin säännöt, virheiden sieto ja komentorivi."""
import contextlib
import copy
import datetime
import io
import json
import os
import sys
import tempfile
import unittest
import urllib.error
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import fill_addr  # noqa: E402

FIXTURES = os.path.join(os.path.dirname(__file__), "fixtures")
NOW = datetime.datetime(2026, 11, 2, 2, 0, tzinfo=datetime.timezone.utc)
# Leveysaste on noin 111,2 km; pituusaste leveydellä 60,3 on noin 0,4955 kertaa se.
DEGREE_METRES = 111_195.0
LON_DEGREE_METRES = DEGREE_METRES * 0.4955
OWN_STREET = {"addr:street": "Oma katu", "addr:city": "Helsinki"}


def feature(name):
    with open(os.path.join(FIXTURES, name), encoding="utf-8") as handle:
        return json.load(handle)["features"][0]


def row(id_="n1", lat=60.3, lon=24.6, **extra):
    return {"id": id_, "cat": "fuel", "lat": lat, "lon": lon, "name": None, "brand": None, "addr": None, "hgv": 0, "tags": {}, **extra}


def cached(id_="n1", lat=60.3, lon=24.6, addr="Vanhakatu 1, 01600 Vantaa", days_ago=30):
    checked = (NOW.date() - datetime.timedelta(days=days_ago)).isoformat()
    return {id_: {"id": id_, "lat": lat, "lon": lon, "addr": addr, "checked": checked}}


class Lookup:
    """Lookup, joka kirjaa kutsut; vastaukset annetaan järjestyksessä, ja poikkeus nostetaan."""

    def __init__(self, *answers, default="Uusikatu 2, 01600 Vantaa"):
        self.calls = []
        self.answers = list(answers)
        self.default = default

    def __call__(self, lat, lon):
        self.calls.append((lat, lon))
        answer = self.answers.pop(0) if self.answers else self.default
        if isinstance(answer, Exception):
            raise answer
        return answer


class AddressFromFeatureTest(unittest.TestCase):
    def test_real_answers(self):
        self.assertEqual("Esimerkkikuja 2, 01660 Vantaa", fill_addr.address_from_feature(feature("digitransit_vantaa.json")))
        # Ahvenanmaalla on vain hallinnollinen kunta.
        self.assertEqual("Björkmansgränd 1, 22100 Maarianhamina", fill_addr.address_from_feature(feature("digitransit_mariehamn.json")))

    def with_properties(self, **changes):
        changed = copy.deepcopy(feature("digitransit_vantaa.json"))
        for key, value in changes.items():
            if value is None:
                changed["properties"].pop(key, None)
            else:
                changed["properties"][key] = value
        return changed

    def test_distance_limit_is_300_metres(self):
        self.assertIsNotNone(fill_addr.address_from_feature(self.with_properties(distance=0.3)))
        self.assertIsNone(fill_addr.address_from_feature(self.with_properties(distance=0.301)))

    def test_missing_distance_is_an_error_and_not_a_missing_address(self):
        with self.assertRaises(ValueError):
            fill_addr.address_from_feature(self.with_properties(distance=None))

    def test_primary_comes_from_street_when_name_is_missing(self):
        self.assertEqual(
            "Esimerkkikuja 2, 01660 Vantaa",
            fill_addr.address_from_feature(self.with_properties(name=None)),
        )
        self.assertEqual(
            "Esimerkkikuja, 01660 Vantaa",
            fill_addr.address_from_feature(self.with_properties(name=None, housenumber=None)),
        )
        self.assertIsNone(fill_addr.address_from_feature(self.with_properties(name=None, street=None, housenumber=None)))

    def test_city_falls_back_from_locality_to_localadmin_and_region(self):
        self.assertEqual("Esimerkkikuja 2, 01660 Kunta", fill_addr.address_from_feature(self.with_properties(locality=None, localadmin="Kunta")))
        self.assertEqual("Esimerkkikuja 2, 01660 Uusimaa", fill_addr.address_from_feature(self.with_properties(locality=None, localadmin=None)))

    def test_missing_city_parts_are_left_out(self):
        self.assertEqual("Esimerkkikuja 2, Vantaa", fill_addr.address_from_feature(self.with_properties(postalcode=None)))
        self.assertEqual(
            "Esimerkkikuja 2",
            fill_addr.address_from_feature(self.with_properties(postalcode=None, locality=None, localadmin=None, region=None)),
        )

    def test_address_longer_than_the_server_accepts_is_not_used(self):
        self.assertIsNotNone(fill_addr.address_from_feature(self.with_properties(name="x" * 180)))
        self.assertIsNone(fill_addr.address_from_feature(self.with_properties(name="x" * 201)))


class FillTest(unittest.TestCase):
    def fill(self, rows, cache=None, lookup=None, **kwargs):
        return fill_addr.fill(rows, cache or {}, lookup or Lookup(), NOW, **kwargs)

    def test_own_street_address_stays_and_is_not_looked_up(self):
        lookup = Lookup()
        own = row(addr="Oma katu 1, 00100 Helsinki", tags=OWN_STREET)

        # Vanha välimuistirivi ei voita kohteen omaa osoitetta.
        rows, fresh, stats = self.fill([own], cached(), lookup)

        self.assertEqual([own], rows)
        self.assertEqual([], fresh)
        self.assertEqual([], lookup.calls)
        self.assertEqual(0, stats["cached"])

    def test_fintraffic_address_is_its_own(self):
        lookup = Lookup()
        charger = row("c-FI001-1", addr="Katu 1, 00100 Helsinki")

        rows, _, _ = self.fill([charger], lookup=lookup)

        self.assertEqual([charger], rows)
        self.assertEqual([], lookup.calls)

    def test_fintraffic_row_without_address_is_looked_up(self):
        lookup = Lookup("Katu 2, 00100 Helsinki")

        rows, _, _ = self.fill([row("c-FI001-2")], lookup=lookup)

        self.assertEqual("Katu 2, 00100 Helsinki", rows[0]["addr"])

    def test_town_alone_is_not_a_street_address(self):
        lookup = Lookup("Kauppakatu 1, 45100 Kouvola")

        rows, fresh, _ = self.fill([row(addr="Kouvola", tags={"addr:city": "Kouvola"})], lookup=lookup)

        self.assertEqual("Kauppakatu 1, 45100 Kouvola", rows[0]["addr"])
        self.assertEqual(1, len(lookup.calls))
        self.assertEqual("Kauppakatu 1, 45100 Kouvola", fresh[0]["addr"])

    def test_town_stays_when_no_street_address_is_found(self):
        town = row(addr="Kouvola", tags={"addr:city": "Kouvola"})

        rows, fresh, _ = self.fill([town], lookup=Lookup(None))
        self.assertEqual("Kouvola", rows[0]["addr"])
        self.assertIsNone(fresh[0]["addr"])

        rows, _, _ = self.fill([town], cached(addr=None))
        self.assertEqual("Kouvola", rows[0]["addr"])

        rows, _, _ = self.fill([town], lookup=Lookup(LookupError("katko")))
        self.assertEqual("Kouvola", rows[0]["addr"])

    def test_missing_address_is_looked_up_and_stored(self):
        lookup = Lookup("Uusikatu 2, 01600 Vantaa")

        rows, fresh, stats = self.fill([row(tags={"amenity": "fuel"})], lookup=lookup)

        self.assertEqual("Uusikatu 2, 01600 Vantaa", rows[0]["addr"])
        self.assertEqual({"amenity": "fuel"}, rows[0]["tags"])
        self.assertEqual([(60.3, 24.6)], lookup.calls)
        self.assertEqual([{"id": "n1", "lat": 60.3, "lon": 24.6, "addr": "Uusikatu 2, 01600 Vantaa", "checked": "2026-11-02"}], fresh)
        self.assertEqual({"cached": 0, "looked_up": 1, "found": 1, "failed": 0, "skipped": 0}, stats)

    def test_answer_without_address_is_stored_as_null(self):
        rows, fresh, stats = self.fill([row()], lookup=Lookup(None))

        self.assertIsNone(rows[0]["addr"])
        self.assertIsNone(fresh[0]["addr"])
        self.assertEqual(1, stats["looked_up"])
        self.assertEqual(0, stats["found"])

    def test_cache_hit_within_ten_metres_and_thirty_days(self):
        lookup = Lookup()
        moved = 60.3 + 10 / DEGREE_METRES

        rows, fresh, stats = self.fill([row(lat=moved)], cached(), lookup)

        self.assertEqual("Vanhakatu 1, 01600 Vantaa", rows[0]["addr"])
        self.assertEqual([], lookup.calls)
        self.assertEqual([], fresh)
        self.assertEqual(1, stats["cached"])

    def test_stored_no_address_is_also_a_hit(self):
        lookup = Lookup()

        rows, fresh, stats = self.fill([row()], cached(addr=None), lookup)

        self.assertIsNone(rows[0]["addr"])
        self.assertEqual([], lookup.calls)
        self.assertEqual(1, stats["cached"])

    def test_moved_more_than_25_metres_north_is_looked_up_again(self):
        lookup = Lookup()
        within = 60.3 + 24 / DEGREE_METRES
        beyond = 60.3 + 26 / DEGREE_METRES

        self.fill([row(lat=within)], cached(), lookup)
        self.assertEqual([], lookup.calls)
        rows, fresh, _ = self.fill([row(lat=beyond)], cached(), lookup)

        self.assertEqual(1, len(lookup.calls))
        self.assertEqual("Uusikatu 2, 01600 Vantaa", rows[0]["addr"])
        self.assertEqual(beyond, fresh[0]["lat"])

    def test_moved_more_than_25_metres_east_is_looked_up_again(self):
        lookup = Lookup()

        self.fill([row(lon=24.6 + 24 / LON_DEGREE_METRES)], cached(), lookup)
        self.assertEqual([], lookup.calls)
        self.fill([row(lon=24.6 + 26 / LON_DEGREE_METRES)], cached(), lookup)
        self.assertEqual(1, len(lookup.calls))

    def test_cache_older_than_180_days_is_looked_up_again(self):
        lookup = Lookup()

        self.fill([row()], cached(days_ago=179), lookup)
        self.assertEqual([], lookup.calls)
        self.fill([row()], cached(days_ago=180), lookup)
        self.assertEqual(1, len(lookup.calls))
        self.fill([row()], cached(days_ago=181), lookup)
        self.assertEqual(2, len(lookup.calls))

    def test_old_address_survives_a_failed_lookup(self):
        rows, fresh, stats = self.fill([row()], cached(days_ago=181), Lookup(LookupError("katko")))

        self.assertEqual("Vanhakatu 1, 01600 Vantaa", rows[0]["addr"])
        self.assertEqual([], fresh)
        self.assertEqual(1, stats["failed"])

    def test_old_address_survives_when_lookups_have_stopped(self):
        lookup = Lookup(*[LookupError("katko")] * 20)
        rows = [row(f"n{i}") for i in range(20)] + [row("vanha")]

        out, fresh, stats = self.fill(rows, cached("vanha", days_ago=181), lookup)

        self.assertEqual("Vanhakatu 1, 01600 Vantaa", out[-1]["addr"])
        self.assertEqual([], fresh)
        self.assertEqual(1, stats["skipped"])

    def test_old_address_of_a_moved_place_is_not_kept(self):
        rows, _, _ = self.fill([row(lat=60.3 + 26 / DEGREE_METRES)], cached(days_ago=181), Lookup(LookupError("katko")))

        self.assertIsNone(rows[0]["addr"])

    def test_found_answer_replaces_an_old_address(self):
        rows, fresh, _ = self.fill([row()], cached(days_ago=181), Lookup(None))

        self.assertIsNone(rows[0]["addr"])
        self.assertIsNone(fresh[0]["addr"])

    def test_unreadable_cache_rows_are_looked_up_again(self):
        for name, change in (
            ("päiväys", {"checked": "eilen"}),
            ("päiväys puuttuu", {"checked": None}),
            ("osoite numero", {"addr": 5}),
            ("osoite tyhjä", {"addr": ""}),
            ("osoite liian pitkä", {"addr": "x" * 201}),
            ("sijainti puuttuu", {"lat": None}),
        ):
            lookup = Lookup()
            cache = cached()
            cache["n1"].update(change)

            rows, _, _ = self.fill([row()], cache, lookup)

            self.assertEqual(1, len(lookup.calls), name)
            self.assertEqual("Uusikatu 2, 01600 Vantaa", rows[0]["addr"], name)

    def test_cache_row_without_address_field_is_looked_up_again(self):
        lookup = Lookup()
        cache = cached()
        del cache["n1"]["addr"]

        self.fill([row()], cache, lookup)

        self.assertEqual(1, len(lookup.calls))

    def test_unreadable_old_address_is_not_kept_when_lookup_fails(self):
        cache = cached(days_ago=181)
        cache["n1"]["addr"] = 5

        rows, _, _ = self.fill([row()], cache, Lookup(LookupError("katko")))

        self.assertIsNone(rows[0]["addr"])

    def test_failed_lookup_leaves_row_without_address_and_cache_row(self):
        rows, fresh, stats = self.fill([row("n1"), row("n2")], lookup=Lookup(LookupError("429"), "Toinen 2, 01600 Vantaa"))

        self.assertIsNone(rows[0]["addr"])
        self.assertEqual("Toinen 2, 01600 Vantaa", rows[1]["addr"])
        self.assertEqual(["n2"], [r["id"] for r in fresh])
        self.assertEqual({"cached": 0, "looked_up": 1, "found": 1, "failed": 1, "skipped": 0}, stats)

    def test_twenty_failures_in_a_row_stop_the_lookups(self):
        lookup = Lookup(*[LookupError("katko")] * 30)
        rows = [row(f"n{i}") for i in range(25)]

        out, fresh, stats = self.fill(rows, lookup=lookup)

        self.assertEqual(20, len(lookup.calls))
        self.assertEqual(25, len(out))
        self.assertEqual([], fresh)
        self.assertEqual(20, stats["failed"])
        self.assertEqual(5, stats["skipped"])

    def test_cache_hits_still_work_after_lookups_stop(self):
        lookup = Lookup(*[LookupError("katko")] * 20)
        rows = [row(f"n{i}") for i in range(20)] + [row("hit")]

        out, _, stats = self.fill(rows, cached("hit"), lookup)

        self.assertEqual("Vanhakatu 1, 01600 Vantaa", out[-1]["addr"])
        self.assertEqual(1, stats["cached"])

    def test_same_id_in_two_categories_is_looked_up_once(self):
        lookup = Lookup()
        rows = [row("n1", cat="fuel"), row("n1", cat="charging")]

        out, fresh, stats = self.fill(rows, lookup=lookup)

        self.assertEqual(1, len(lookup.calls))
        self.assertEqual(["Uusikatu 2, 01600 Vantaa"] * 2, [r["addr"] for r in out])
        self.assertEqual(["fuel", "charging"], [r["cat"] for r in out])
        self.assertEqual(1, len(fresh))
        self.assertEqual(1, stats["looked_up"])
        self.assertEqual(1, stats["cached"])

    def test_success_resets_the_failure_count(self):
        answers = [LookupError("katko")] * 19 + ["Katu 1, 01600 Vantaa"] + [LookupError("katko")] * 19 + ["Katu 2, 01600 Vantaa"]
        lookup = Lookup(*answers)

        _, fresh, stats = self.fill([row(f"n{i}") for i in range(40)], lookup=lookup)

        self.assertEqual(40, len(lookup.calls))
        self.assertEqual(2, len(fresh))
        self.assertEqual(0, stats["skipped"])

    def test_total_time_limit_stops_slow_lookups(self):
        now = [0.0]
        lookup = Lookup()

        def slow(lat, lon):
            now[0] += 1000.0
            return lookup(lat, lon)

        rows = [row(f"n{i}") for i in range(5)] + [row("hit")]
        out, fresh, stats = self.fill(rows, cached("hit"), slow, clock=lambda: now[0])

        # 45 minuuttia on 2 700 s: haut alkavat hetkillä 0, 1 000 ja 2 000.
        self.assertEqual(3, len(lookup.calls))
        self.assertEqual(3, len(fresh))
        self.assertEqual(2, stats["skipped"])
        self.assertEqual("Vanhakatu 1, 01600 Vantaa", out[-1]["addr"])

    def test_lookup_starting_just_before_the_limit_is_made(self):
        now = [fill_addr.MAX_LOOKUP_SECONDS - 1.0]
        started = [True]

        def clock():
            # Ajo alkaa hetkellä 0, ja ensimmäinen raja katsotaan juuri ennen 45 minuutin täyttymistä.
            if started[0]:
                started[0] = False
                return 0.0
            return now[0]

        lookup = Lookup()
        self.fill([row()], lookup=lookup, clock=clock)

        self.assertEqual(1, len(lookup.calls))

    def test_each_new_result_goes_to_the_store_at_once(self):
        stored = []
        lookup = Lookup()

        def checked(lat, lon):
            # Edellisen haun tulos on tallessa ennen seuraavaa hakua.
            self.assertEqual(len(lookup.calls), len(stored))
            return lookup(lat, lon)

        _, fresh, _ = self.fill([row("n1"), row("n2")], lookup=checked, store=stored.append)

        self.assertEqual(fresh, stored)

    def test_without_lookup_cache_is_used_and_rest_are_skipped(self):
        rows = [row("hit"), row("vanha"), row("uusi")]
        cache = {**cached("hit"), **cached("vanha", days_ago=181)}

        out, fresh, stats = fill_addr.fill(rows, cache, None, NOW)

        self.assertEqual(["Vanhakatu 1, 01600 Vantaa", "Vanhakatu 1, 01600 Vantaa", None], [r["addr"] for r in out])
        self.assertEqual([], fresh)
        self.assertEqual({"cached": 1, "looked_up": 0, "found": 0, "failed": 0, "skipped": 2}, stats)


class Response(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


class DigitransitLookupTest(unittest.TestCase):
    def setUp(self):
        with open(os.path.join(FIXTURES, "digitransit_vantaa.json"), "rb") as handle:
            self.answer = handle.read()
        self.requests = []
        self.now = 100.0
        self.sleeps = []

    def opener(self, request, timeout):
        self.requests.append((request, timeout, self.now))
        return Response(self.answer)

    def lookup(self, opener=None):
        return fill_addr.DigitransitLookup("avain-123", opener=opener or self.opener, clock=lambda: self.now, sleep=self.sleep)

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds

    def test_request_and_answer(self):
        self.assertEqual("Esimerkkikuja 2, 01660 Vantaa", self.lookup()(60.26455, 24.811014))

        request, timeout, _ = self.requests[0]
        self.assertEqual(
            "https://api.digitransit.fi/geocoding/v1/reverse?point.lat=60.26455&point.lon=24.811014&layers=address&size=1",
            request.full_url,
        )
        self.assertEqual("avain-123", request.get_header("Digitransit-subscription-key"))
        self.assertEqual("tyomatka-poi-pipeline/1.0", request.get_header("User-agent"))
        self.assertNotIn("avain-123", request.full_url)
        self.assertGreater(timeout, 0)

    def test_requests_are_at_least_half_a_second_apart(self):
        lookup = self.lookup()

        lookup(60.0, 24.0)
        self.now += 0.1
        lookup(60.1, 24.0)
        self.now += 2.0
        lookup(60.2, 24.0)

        self.assertEqual(100.0, self.requests[0][2])
        self.assertAlmostEqual(100.5, self.requests[1][2])
        self.assertAlmostEqual(102.5, self.requests[2][2])
        self.assertEqual(1, len(self.sleeps))
        self.assertAlmostEqual(0.4, self.sleeps[0])

    def test_empty_answer_is_no_address(self):
        self.answer = b'{"features": []}'

        self.assertIsNone(self.lookup()(60.0, 24.0))

    def test_network_errors_become_lookup_errors_without_the_key(self):
        def http_error(request, timeout):
            raise urllib.error.HTTPError(request.full_url, 429, "Too Many Requests", {}, None)

        def network_error(request, timeout):
            raise OSError("yhteys katkesi")

        for opener in (http_error, network_error):
            with self.assertRaises(LookupError) as caught:
                self.lookup(opener)(60.0, 24.0)
            self.assertNotIn("avain-123", str(caught.exception))

    def test_unusable_answers_are_errors_and_not_missing_addresses(self):
        for broken in (
            b"ei jsonia",
            b"[]",
            b"{}",
            b'{"geocoding": {"errors": ["virhe"]}}',
            b'{"features": "ei listaa"}',
            b'{"features": null}',
            b'{"features": [5]}',
            b'{"features": [{}]}',
            b'{"features": [{"properties": null}]}',
            b'{"features": [{"properties": 5}]}',
            b'{"features": [{"properties": {"name": "Katu 1"}}]}',
            b'{"features": [{"properties": {"distance": "lahella"}}]}',
        ):
            self.answer = broken
            with self.assertRaises(LookupError, msg=broken):
                self.lookup()(60.0, 24.0)

    def test_header_error_text_is_not_repeated(self):
        # Rikkinäinen avain: http.client kertoo arvon omassa virheilmoituksessaan.
        def invalid_header(request, timeout):
            raise ValueError("Invalid header value b'avain-123\\n'")

        with self.assertRaises(LookupError) as caught:
            self.lookup(invalid_header)(60.0, 24.0)

        self.assertNotIn("avain-123", str(caught.exception))


class MainTest(unittest.TestCase):
    ENV = {"POI_BASE_URL": "https://poi.example", "POI_ADMIN_TOKEN": "t", "DIGITRANSIT_KEY": "avain-123"}

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = os.path.join(self.tmp.name, "FI.jsonl")
        self.write([row("n1"), row("n2", addr="Oma 1, 00100 Helsinki", tags=OWN_STREET)])
        with open(self.path, encoding="utf-8") as handle:
            self.original = handle.read()
        self.calls = []
        self.auth = []
        self.keys = []
        self.printed = io.StringIO()

    def write(self, rows):
        with open(self.path, "w", encoding="utf-8") as out:
            for r in rows:
                out.write(json.dumps(r, ensure_ascii=False) + "\n")

    def read(self):
        with open(self.path, encoding="utf-8") as handle:
            return [json.loads(line) for line in handle]

    def read_text(self):
        with open(self.path, encoding="utf-8") as handle:
            return handle.read()

    def post(self, list_rows=(), fail=()):
        def fake(base_url, token, path, body, **_):
            self.auth.append((base_url, token))
            self.calls.append((path, json.loads(body)))
            if path in fail:
                raise SystemExit(f"{path}: HTTP 500")
            return {"rows": list(list_rows)} if path == "/admin/addr/list" else {"inserted": 1}
        return fake

    def run_main(self, env=None, post=None, lookup=None, argv=None):
        lookup = lookup or Lookup("Uusikatu 2, Vantaa")

        def make_lookup(key):
            self.keys.append(key)
            return lookup

        with mock.patch.dict(os.environ, env if env is not None else self.ENV, clear=True), \
                mock.patch.object(fill_addr, "post", post or self.post()), \
                mock.patch.object(fill_addr, "DigitransitLookup", make_lookup), \
                contextlib.redirect_stdout(self.printed), contextlib.redirect_stderr(io.StringIO()):
            return fill_addr.main(argv if argv is not None else [self.path])

    def today(self):
        return datetime.datetime.now(datetime.timezone.utc).date().isoformat()

    def test_fills_rows_and_stores_new_results(self):
        status = self.run_main()

        self.assertEqual(0, status)
        self.assertEqual(["Uusikatu 2, Vantaa", "Oma 1, 00100 Helsinki"], [r["addr"] for r in self.read()])
        self.assertEqual(["avain-123"], self.keys)
        self.assertEqual({("https://poi.example", "t")}, set(self.auth))
        self.assertEqual("/admin/addr/list", self.calls[0][0])
        self.assertEqual({}, self.calls[0][1])
        self.assertEqual("/admin/addr/put", self.calls[1][0])
        stored = self.calls[1][1]["rows"]
        self.assertEqual(["n1"], [r["id"] for r in stored])
        self.assertEqual("Uusikatu 2, Vantaa", stored[0]["addr"])
        self.assertEqual(self.today(), stored[0]["checked"])
        self.assertIn("Osoitteet: välimuistista 0, haettu 1, löytyi 1, virheitä 0, hakematta 0.", self.printed.getvalue())
        self.assertNotIn("::warning::", self.printed.getvalue())

    def test_cache_from_server_is_used(self):
        lookup = Lookup()

        self.run_main(post=self.post([{"id": "n1", "lat": 60.3, "lon": 24.6, "addr": "Välimuisti 1, Vantaa", "checked": self.today()}]), lookup=lookup)

        self.assertEqual([], lookup.calls)
        self.assertEqual("Välimuisti 1, Vantaa", self.read()[0]["addr"])
        self.assertEqual(["/admin/addr/list"], [c[0] for c in self.calls])

    def test_new_results_are_stored_in_batches_of_500_during_the_lookups(self):
        self.write([row(f"n{i}") for i in range(1001)])
        lookup = Lookup()
        lookups_at_store = []
        base = self.post()

        def fake(base_url, token, path, body, **kwargs):
            if path == "/admin/addr/put":
                lookups_at_store.append(len(lookup.calls))
            return base(base_url, token, path, body, **kwargs)

        self.run_main(post=fake, lookup=lookup)

        self.assertEqual([500, 500, 1], [len(body["rows"]) for path, body in self.calls if path == "/admin/addr/put"])
        # Katkennut ajo ei hukkaa jo tallennettuja eriä.
        self.assertEqual([500, 1000, 1001], lookups_at_store)

    def test_failed_batch_does_not_stop_the_lookups(self):
        self.write([row(f"n{i}") for i in range(600)])
        lookup = Lookup()
        failed = []
        base = self.post()

        def fake(base_url, token, path, body, **kwargs):
            if path == "/admin/addr/put" and not failed:
                failed.append(True)
                raise SystemExit("/admin/addr/put: HTTP 500")
            return base(base_url, token, path, body, **kwargs)

        status = self.run_main(post=fake, lookup=lookup)

        self.assertEqual(0, status)
        self.assertEqual(600, len(lookup.calls))
        self.assertEqual("Uusikatu 2, 01600 Vantaa", self.read()[599]["addr"])
        self.assertEqual([100], [len(body["rows"]) for path, body in self.calls if path == "/admin/addr/put"])
        self.assertIn("::warning::", self.printed.getvalue())

    def test_missing_key_still_uses_the_cache(self):
        env = {k: v for k, v in self.ENV.items() if k != "DIGITRANSIT_KEY"}
        lookup = Lookup()
        self.write([row("n1"), row("n3")])

        status = self.run_main(env=env, post=self.post([{"id": "n1", "lat": 60.3, "lon": 24.6, "addr": "Välimuisti 1, Vantaa", "checked": self.today()}]), lookup=lookup)

        self.assertEqual(0, status)
        self.assertEqual([], self.keys)
        self.assertEqual([], lookup.calls)
        self.assertEqual(["Välimuisti 1, Vantaa", None], [r["addr"] for r in self.read()])
        self.assertEqual(["/admin/addr/list"], [c[0] for c in self.calls])
        self.assertIn("::warning::", self.printed.getvalue())
        self.assertIn("DIGITRANSIT_KEY", self.printed.getvalue())

    def test_missing_server_settings_leave_the_file_alone(self):
        for missing in ("POI_BASE_URL", "POI_ADMIN_TOKEN"):
            env = {k: v for k, v in self.ENV.items() if k != missing}
            lookup = Lookup()

            self.assertEqual(0, self.run_main(env=env, lookup=lookup), missing)

            self.assertEqual([], lookup.calls)
            self.assertEqual(self.original, self.read_text())
        self.assertEqual([], self.calls)
        self.assertNotIn("avain-123", self.printed.getvalue())
        self.assertIn("::warning::", self.printed.getvalue())

    def test_unreachable_cache_leaves_the_file_alone(self):
        lookup = Lookup()

        status = self.run_main(post=self.post(fail={"/admin/addr/list"}), lookup=lookup)

        self.assertEqual(0, status)
        self.assertEqual([], lookup.calls)
        self.assertEqual(self.original, self.read_text())
        self.assertIn("::warning::", self.printed.getvalue())

    def test_unreadable_cache_answer_leaves_the_file_alone(self):
        def html_page(*_args, **_kwargs):
            raise ValueError("ei JSONia")

        for answer in (html_page, lambda *_a, **_k: [], lambda *_a, **_k: {"rows": 5}, lambda *_a, **_k: {"rows": [{"lat": 60.0}]}):
            lookup = Lookup()

            status = self.run_main(post=answer, lookup=lookup)

            self.assertEqual(0, status)
            self.assertEqual([], lookup.calls)
            self.assertEqual(self.original, self.read_text())

    def test_header_error_text_is_not_printed(self):
        def invalid_header(*_args, **_kwargs):
            raise ValueError("Invalid header value b'Bearer t\\n'")

        self.run_main(post=invalid_header)

        self.assertNotIn("Bearer", self.printed.getvalue())

    def test_unreadable_store_answer_keeps_filled_rows(self):
        def fake(base_url, token, path, body, **_):
            if path == "/admin/addr/list":
                return {"rows": []}
            raise ValueError("Invalid header value b'Bearer t\\n'")

        status = self.run_main(post=fake)

        self.assertEqual(0, status)
        self.assertEqual("Uusikatu 2, Vantaa", self.read()[0]["addr"])
        self.assertNotIn("Bearer", self.printed.getvalue())

    def test_failed_store_keeps_filled_rows(self):
        status = self.run_main(post=self.post(fail={"/admin/addr/put"}))

        self.assertEqual(0, status)
        self.assertEqual("Uusikatu 2, Vantaa", self.read()[0]["addr"])

    def test_failed_lookups_do_not_fail_the_run_but_warn(self):
        status = self.run_main(lookup=Lookup(LookupError("429")))

        self.assertEqual(0, status)
        self.assertIsNone(self.read()[0]["addr"])
        self.assertEqual("Oma 1, 00100 Helsinki", self.read()[1]["addr"])
        self.assertIn("::warning::", self.printed.getvalue())

    def test_many_lookups_without_a_single_address_warn(self):
        self.write([row(f"n{i}") for i in range(20)])

        self.run_main(lookup=Lookup(default=None))

        self.assertIn("::warning::", self.printed.getvalue())

    def test_few_lookups_without_an_address_do_not_warn(self):
        self.write([row(f"n{i}") for i in range(19)])

        self.run_main(lookup=Lookup(default=None))

        self.assertNotIn("::warning::", self.printed.getvalue())

    def test_bad_command_line_is_an_error(self):
        with mock.patch("sys.stderr", io.StringIO()), self.assertRaises(SystemExit) as caught:
            self.run_main(argv=[])

        self.assertEqual(2, caught.exception.code)


if __name__ == "__main__":
    unittest.main()
