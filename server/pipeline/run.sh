#!/usr/bin/env bash
# Aineistoputki maa kerrallaan: Geofabrikin ote → osmium-suodatus → GeoJSON-rivit → muunnos → Workerin tuonti.
# Suomen rivit täydennetään Fintrafficin latauspisteillä, rajataan Suomen rajaan ja niiden puuttuvat osoitteet haetaan
# Digitransitista ennen lähetystä.
# Käyttö: run.sh [--dry-run] [ISO,ISO,...]   (tyhjä lista = kaikki countries.txt:n kommentoimattomat maat)
# --dry-run lataa, suodattaa ja muuntaa, tulostaa rivimäärät luokittain eikä lähetä mitään.
# Ympäristö lähetykseen: POI_BASE_URL, POI_ADMIN_TOKEN; osoitteiden hakuun DIGITRANSIT_KEY (ilman sitä osoitteet vain välimuistista).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
dry_run=0
if [[ "${1:-}" == "--dry-run" ]]; then
  dry_run=1
  shift
fi
selected=",${1:-},"
countries="${POI_COUNTRIES_FILE:-$here/countries.txt}"
work="${POI_WORK_DIR:-$(mktemp -d)}"
mkdir -p "$work"
failed=()

while read -r iso path _; do
  [[ -z "$iso" || "$iso" == \#* ]] && continue
  if [[ "$selected" != ",," && "$selected" != *",$iso,"* ]]; then
    continue
  fi
  echo "== $iso ($path)"
  extract="$work/$iso.osm.pbf"
  filtered="$work/$iso-filtered.osm.pbf"
  features="$work/$iso.geojsonseq"
  rows="$work/$iso.jsonl"
  headers="$work/$iso.headers"
  # set -e ei vaikuta if- tai !-ehdon alikuoressa: alikuori ajetaan erikseen, jotta mikä tahansa epäonnistunut vaihe
  # pysäyttää maan ennen lähetystä.
  set +e
  (
    set -euo pipefail
    curl -fsSL --retry 3 --retry-delay 30 -D "$headers" -o "$extract" "https://download.geofabrik.de/${path}-latest.osm.pbf"
    # Puuttuva otsake ei saa kaataa maata pipefailin takia: päiväys on silloin tämä päivä.
    extract_date="$({ grep -i '^last-modified:' "$headers" || true; } | tail -1 | cut -d' ' -f2- | tr -d '\r')"
    extract_date="$(date -u -d "${extract_date:-now}" +%F)"
    osmium tags-filter "$extract" nwr/amenity=fuel,charging_station,parking nwr/highway=rest_area,services -o "$filtered" --overwrite
    osmium export "$filtered" -f geojsonseq --geometry-types=point,linestring,polygon --add-unique-id=type_id --attributes=timestamp -o "$features" --overwrite
    if [[ "$dry_run" == 1 ]]; then
      summary="$(python3 "$here/transform.py" --country "$iso" --summary "$features" "$rows")"
      echo "$summary"
      fuel="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["fuel"])' "$summary")"
      [[ "$fuel" -gt 0 ]] || { echo "$iso: ei yhtään huoltoasemaa, putki ei toimi" >&2; exit 1; }
    else
      python3 "$here/transform.py" --country "$iso" "$features" "$rows"
      # Suomen latauspisteet Fintrafficin avoimesta datasta (CC BY 4.0); haun virhe pysäyttää maan ennen lähetystä.
      # Fintrafficin aineistossa on virheellisiä koordinaatteja, joten afir.py rajaa omat rivinsä ennen OSM-rivien
      # korvaamista. Otteessa on myös rajan takaisia kohteita, joten lopuksi rajataan koko aineisto.
      if [[ "$iso" == "FI" ]]; then
        python3 "$here/afir.py" --merge "$rows"
        python3 "$here/border.py" --clip "$rows"
        # Osoitteet vain rajatuille riveille; skripti sietää Digitransitin ja välimuistin katkot itse.
        python3 "$here/fill_addr.py" "$rows"
      fi
      python3 "$here/upload.py" --country "$iso" --extract-date "$extract_date" "$rows"
    fi
  )
  status=$?
  set -e
  if (( status != 0 )); then
    failed+=("$iso")
    if [[ "${CI:-}" == "true" ]]; then
      # CI:n levytila on rajallinen: suuret välitiedostot pois, pienet jäävät lokin tueksi.
      rm -f "$extract" "$filtered" "$features"
      echo "!! $iso epäonnistui (suuret välitiedostot poistettu)" >&2
    else
      # Paikallisesti välitiedostot jäävät työkansioon vianetsintää varten.
      echo "!! $iso epäonnistui (välitiedostot kansiossa $work)" >&2
    fi
  else
    # Onnistunut maa siivotaan heti levytilan vuoksi.
    rm -f "$extract" "$filtered" "$features" "$rows" "$headers"
  fi
done < "$countries"

if (( ${#failed[@]} > 0 )); then
  echo "Epäonnistuneet maat: ${failed[*]}" >&2
  exit 1
fi
echo "Valmis."
