"""run.sh valetyökaluilla: epäonnistunut vaihe pysäyttää maan ennen lähetystä, ja CI siivoaa suuret välitiedostot."""
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
RUN = HERE.parent / "run.sh"
# PATHin bash: Windowsissa CreateProcess löytäisi ensin System32:n WSL-bashin.
BASH = shutil.which("bash")

FAKE_CURL = """#!/usr/bin/env bash
while [[ $# -gt 0 ]]; do
  case "$1" in
    -D)
      if [[ -n "${FAKE_NO_LAST_MODIFIED:-}" ]]; then
        printf 'HTTP/1.1 200 OK\\r\\n' > "$2"
      else
        printf 'HTTP/1.1 200 OK\\r\\nLast-Modified: Tue, 20 Oct 2026 20:00:00 GMT\\r\\n' > "$2"
      fi
      shift 2 ;;
    -o) printf 'pbf' > "$2"; shift 2 ;;
    *) shift ;;
  esac
done
"""

FAKE_OSMIUM = """#!/usr/bin/env bash
echo "osmium $*" >> "$FAKE_LOG"
if [[ "$1" == "tags-filter" && -n "${FAKE_OSMIUM_FAIL:-}" ]]; then
  echo "osmium: virhe" >&2
  exit 1
fi
out=""
while [[ $# -gt 0 ]]; do
  if [[ "$1" == "-o" ]]; then out="$2"; shift 2; else shift; fi
done
printf 'x' > "$out"
"""

FAKE_PYTHON = """#!/usr/bin/env bash
# Kuiva-ajon huoltoasemien määrä: run.sh poimii sen summarysta python3 -c -kutsulla.
if [[ "$1" == "-c" ]]; then
  echo 1
  exit 0
fi
echo "python3 $(basename "$1") ${*:2}" >> "$FAKE_LOG"
if [[ "$(basename "$1")" == "transform.py" ]]; then
  for last; do :; done
  printf '{}\\n' > "$last"
fi
if [[ "$(basename "$1")" == "afir.py" && -n "${FAKE_AFIR_FAIL:-}" ]]; then
  echo "afir: verkkovirhe" >&2
  exit 1
fi
if [[ "$(basename "$1")" == "fill_addr.py" && -n "${FAKE_FILL_FAIL:-}" ]]; then
  echo "fill_addr: kaatui" >&2
  exit 1
fi
if [[ "$(basename "$1")" == "border.py" && -n "${FAKE_BORDER_FAIL:-}" ]]; then
  echo "border: rajatiedosto rikki" >&2
  exit 1
fi
"""


class CountriesFileTest(unittest.TestCase):
    def test_only_finland_is_updated(self):
        lines = (HERE.parent / "countries.txt").read_text(encoding="utf-8").splitlines()
        rows = [line.split() for line in lines if line.strip() and not line.startswith("#")]

        self.assertEqual([["FI", "europe/finland"]], rows)


@unittest.skipUnless(BASH, "bash puuttuu")
class RunScriptTest(unittest.TestCase):
    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        for name, body in (("curl", FAKE_CURL), ("osmium", FAKE_OSMIUM), ("python3", FAKE_PYTHON)):
            path = self.bin / name
            path.write_bytes(body.encode())
            path.chmod(0o755)
        self.work = self.tmp / "work"
        self.log = self.tmp / "calls.log"
        self.countries = self.tmp / "countries.txt"
        self.countries.write_bytes(b"FI europe/finland\n")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_pipeline(self, *args, **extra_env):
        env = dict(os.environ)
        env.pop("CI", None)
        env.update(
            PATH=f"{self.bin.as_posix()}{os.pathsep}{env['PATH']}",
            POI_COUNTRIES_FILE=self.countries.as_posix(),
            POI_WORK_DIR=self.work.as_posix(),
            FAKE_LOG=self.log.as_posix(),
            POI_BASE_URL="https://example.invalid",
            POI_ADMIN_TOKEN="t",
        )
        env.update(extra_env)
        result = subprocess.run([BASH, RUN.as_posix(), *args], env=env, capture_output=True, encoding="utf-8", timeout=60)
        calls = self.log.read_text(encoding="utf-8").splitlines() if self.log.exists() else []
        return result, calls

    def test_successful_country_is_transformed_uploaded_and_cleaned(self):
        result, calls = self.run_pipeline()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(any(c.startswith("python3 upload.py") for c in calls), calls)
        self.assertEqual(sorted(p.name for p in self.work.iterdir()), [])

    def test_extract_date_comes_from_last_modified(self):
        result, calls = self.run_pipeline()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(any(c.startswith("python3 upload.py --country FI --extract-date 2026-10-20") for c in calls), calls)

    def test_missing_last_modified_falls_back_to_today(self):
        result, calls = self.run_pipeline(FAKE_NO_LAST_MODIFIED="1")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(any(c.startswith("python3 upload.py") for c in calls), calls)

    def test_export_keeps_osm_timestamps(self):
        result, calls = self.run_pipeline()

        self.assertEqual(result.returncode, 0, result.stderr)
        export = next(c for c in calls if c.startswith("osmium export"))
        self.assertIn("--attributes=timestamp", export)

    def test_finland_merges_fintraffic_charging_points_before_upload(self):
        result, calls = self.run_pipeline()

        self.assertEqual(result.returncode, 0, result.stderr)
        names = [c.split()[1] for c in calls if c.startswith("python3")]
        self.assertEqual(["transform.py", "afir.py", "border.py", "fill_addr.py", "upload.py"], names)
        self.assertTrue(any(c.startswith("python3 afir.py --merge ") and c.endswith("FI.jsonl") for c in calls), calls)

    def test_finland_is_clipped_to_the_border_after_merging_and_before_upload(self):
        result, calls = self.run_pipeline()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(any(c.startswith("python3 border.py --clip ") and c.endswith("FI.jsonl") for c in calls), calls)

    def test_dry_run_does_not_merge_clip_or_upload(self):
        result, calls = self.run_pipeline("--dry-run")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(["transform.py"], [c.split()[1] for c in calls if c.startswith("python3")])

    def test_finland_fills_addresses_after_clipping_and_before_upload(self):
        result, calls = self.run_pipeline()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(any(c.startswith("python3 fill_addr.py ") and c.endswith("FI.jsonl") for c in calls), calls)

    def test_crashed_address_fill_stops_country_before_upload(self):
        result, calls = self.run_pipeline(FAKE_FILL_FAIL="1")

        self.assertEqual(result.returncode, 1)
        self.assertFalse(any(c.startswith("python3 upload.py") for c in calls), calls)

    def test_failed_border_clip_stops_country_before_upload(self):
        result, calls = self.run_pipeline(FAKE_BORDER_FAIL="1")

        self.assertEqual(result.returncode, 1)
        self.assertFalse(any(c.startswith("python3 upload.py") for c in calls), calls)

    def test_other_countries_do_not_fetch_fintraffic_data_or_use_the_finnish_border(self):
        self.countries.write_bytes(b"SE europe/sweden\n")
        result, calls = self.run_pipeline()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(any(c.startswith("python3 afir.py") for c in calls), calls)
        self.assertFalse(any(c.startswith("python3 border.py") for c in calls), calls)
        self.assertFalse(any(c.startswith("python3 fill_addr.py") for c in calls), calls)
        self.assertTrue(any(c.startswith("python3 upload.py --country SE") for c in calls), calls)

    def test_failed_fintraffic_fetch_stops_country_before_upload(self):
        result, calls = self.run_pipeline(FAKE_AFIR_FAIL="1")

        self.assertEqual(result.returncode, 1)
        self.assertFalse(any(c.startswith("python3 upload.py") for c in calls), calls)

    def test_failed_filter_stops_country_before_upload(self):
        result, calls = self.run_pipeline(FAKE_OSMIUM_FAIL="1")

        self.assertEqual(result.returncode, 1)
        self.assertIn("FI epäonnistui", result.stderr)
        self.assertFalse(any(c.startswith("osmium export") for c in calls), calls)
        self.assertFalse(any(c.startswith("python3") for c in calls), calls)

    def test_failed_country_keeps_files_locally_but_ci_removes_large_ones(self):
        self.run_pipeline(FAKE_OSMIUM_FAIL="1")
        self.assertTrue((self.work / "FI.osm.pbf").exists())

        shutil.rmtree(self.work)
        result, _ = self.run_pipeline(FAKE_OSMIUM_FAIL="1", CI="true")
        self.assertEqual(result.returncode, 1)
        self.assertFalse((self.work / "FI.osm.pbf").exists())
        self.assertFalse((self.work / "FI-filtered.osm.pbf").exists())


if __name__ == "__main__":
    unittest.main()
