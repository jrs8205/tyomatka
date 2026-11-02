# Karttakohteiden palvelin

Cloudflare Worker ja D1-kanta, joista sovellus hakee huoltoasemat, latauspisteet, kaasuasemat ja lepopaikat
kartan ikkunaan. Aineisto on OpenStreetMapista (© OpenStreetMap-tekijät, ODbL); viikoittainen GitHub Actions
-työnkulku (`.github/workflows/poi-update.yml`) suodattaa Geofabrikin Suomen otteen (Ahvenanmaa mukaan lukien) ja
kirjoittaa sen kantaan (`pipeline/`). Kanta on OSM:stä johdettu tietokanta, ja sen tekotapa on tämä kansio (ODbL 4.6).

Suomen ajo lisää Fintrafficin latauspisteet (Digitraffic AFIR, CC BY 4.0, `pipeline/afir.py`), jotka korvaavat
OSM-latauspisteet 100 metrin säteellä, ja rajaa rivit Suomen rajaan (`pipeline/border.py`), koska otteessa on myös rajan
takaisia kohteita. Rajan ulkopuolinen Fintrafficin piste ei korvaa OSM-pistettä. Raja on OSM-relaatio 54224 (ODbL,
https://opendatacommons.org/licenses/odbl/1-0/) tiedostona
`pipeline/data/finland-border.geojson`, ja sen tekotapa on `pipeline/border.py`:n alussa. Kohteiden puuttuvat katuosoitteet
täydennetään Digitransitin käänteishaulla (`pipeline/fill_addr.py`; © Digitransit 2026, CC BY 4.0;
OpenStreetMap-pohjaiset osoitteet ODbL; GitHub-salaisuus `DIGITRANSIT_KEY`), ja kohteen oma katuosoite voittaa aina.
Tulokset säilyvät taulussa `addr_fill`. Ilman avainta tai Digitransitin katkon aikana kohteet lähetetään silti
välimuistin osoitteilla, ja työnkulun lokiin tulee varoitus. Ehdot tarkistettu 31.10.2026: käyttöehdot
(https://digitransit.fi/en/developers/apis/7-terms-of-use/) ovat maksuton käyttö ilman kieltoa tallennukselle tai
eräajolle, ja rekisteröintisivu (https://digitransit.fi/en/developers/api-registration/) suosittelee eräajoon 0,5–1 s
pyyntöjen väliä.

Kuljettajien ilmoitukset tallentuvat tauluun `report`.

Yrityshaku käyttää Overture Maps Foundationin Places-aineistoa
(Suomi ja Ahvenanmaa). Lisenssit: Meta, Microsoft, BrightQuery ja muut lähteet CDLA Permissive 2.0
(https://cdla.dev/permissive-2-0/), Foursquare Apache 2.0 (Copyright 2024 Foursquare Labs, Inc., NOTICE:
https://docs.overturemaps.org/attribution/). Rajapinnan `source`-kenttä kertoo lähteen jokaisessa vastauksessa.

## Rajapinta

- `GET /v1/poi?bbox=minLon,minLat,maxLon,maxLat&cats=fuel,charging,gas,rest&hgv=1`
  Ikkuna enintään 2°×2°; `cats` valinnainen (oletus kaikki), `hgv=1` rajaa raskaan kaluston palveluihin.
  Vastaus `{ generatedAt, source, items[] }`, `items[]`: `id` (`n123`/`w456`/`r789`), `cat`, `lat`, `lon`, `name?`,
  `brand?`, `addr?`, `hgv`, `tags`. Enintään 300 kohdetta: raskaan kaluston palvelut ensin, sitten lähimmät.
  Kertatuonnin faktat (22.10.2026) tulevat tageihin `k:`-avaimin, ja liittämättömät asemat ovat omia kohteitaan
  (`id` `k-…`). Kun pyydetään vain huoltoasemat tai vain kaasuasemat, mukaan tulevat myös toisen luokan kohteet,
  joiden uusin polttoaineilmoitus kuuluu pyydettyyn luokkaan (esim. ilmoitettu biokaasu).
  Otsakkeet `Cache-Control: public, max-age=86400` ja `Link: <…/odbl/1-0/>; rel="license"`.
- `POST /v1/poi/batch` runko `{parts: [{bbox: "minLon,minLat,maxLon,maxLat", cats: "fuel"}, …]}`: yhteishaku sovelluksen
  Lähellä-listalle. Paloja 1–50, jokainen enintään 0,25°×0,25° (yksi ruudukon solu tai sen alisolu), ja jokaisessa on
  täsmälleen yksi luokka; muita kenttiä ei hyväksytä. Yksikin virheellinen pala antaa 400, jonka virheessä on palan
  indeksi (`parts[3]: …`); osittaista vastausta ei tule. Runko enintään 16 kt (muuten 413). Vastaus `{parts:
  [{generatedAt, source, items, truncated}]}` palojen järjestyksessä, ja jokainen osa on sama kuin vastaavan
  `GET /v1/poi` -haun vastaus (oma 300 kohteen raja ja `truncated`). Kaikki kyselyt (2 palaa kohti) lähtevät kannalle
  yhtenä eränä. Rajoissa yhteishaku on yksi haku; palan kokoraja pitää kuorman enintään noin viisinkertaisena 2°:n
  ikkunahakuun nähden, ja 50 palan raja pitää vastauksen ja Workerin muistin kohtuullisena tiheälläkin alueella.
  Otsakkeet `Cache-Control: no-store` ja sama `Link`.
- `GET /v1/meta`: maat, otteen päiväys ja rivimäärä.
- `GET /v1/places?q=<teksti>&near=<lat>,<lon>`: yrityshaku nimellä (`src/places.ts`). `q` 1–100 merkkiä, `near`
  valinnainen ja vain järjestää tulokset. Sana osuu nimen tai paikkakunnan sanan alkuun, vähintään yksi sana nimeen,
  yhtiömuodot (Oy, Ab, Ky, Tmi …) eivät ole sanoja, ja vähintään kolmen sanan haku kelpaa yhtä sanaa vaille. Vastaus
  `{source, items[]}`, `items[]` `{id, name, street?, postcode?, locality?, lat, lon}`, enintään 8.
  `Cache-Control: public, max-age=3600`.
- `GET /v1/geocode/autocomplete|search|reverse`: Digitransitin osoitehaku palvelimen avaimella (`src/geocode.ts`). Parametrit `text`, `size`
  (1–10), `layers`, `lang`, `boundary.rect.*`, `focus.point.*`, `point.lat`, `point.lon`, enintään 200 merkkiä; muut pudotetaan. Vastaus sellaisenaan ja
  `Cache-Control: no-store`. `400` virheellisestä hausta, `429` yli rajan, `503`, jos salaisuus puuttuu tai Digitransit ei vastaa 200:lla. Hakuja ei tallenneta.
- `POST /v1/reports/forget` (otsake `X-Device: <UUID v4>`): poistaa laitteen omat ilmoitukset `{deleted}`. Sama raja kuin ilmoituksilla. Tunnus mitätöidään samassa transaktiossa: sillä lähetetty ilmoitus saa vastauksen 410 `{"error":"tunnus poistettu"}` eikä tallennu, ja sovellus vaihtaa tunnuksensa. Merkintä säilyy enintään 7 vuorokautta. Taulu `forgotten_device` on luotava kantaan ennen tämän version julkaisua.
- `GET /privacy?lang=fi|sv|en`: tietosuojaseloste (`src/privacy.ts`); ilman parametria kieli otsakkeesta, muuten englanti.
- `POST /v1/reports` (otsake `X-Device: <UUID v4>`, runko `{poi, field, value, rid, at?}`): kuljettajan ilmoitus
  kohteesta. `at` (UTC-hetki ISO-muodossa, enintään vuoden takaa, enintään 5 min tulevaisuudessa ja silloin rajattuna
  nykyhetkeen) on offline-jonossa olleen ilmoituksen tai laitteen vanhan korjauksen tekohetki; ilman sitä palvelimen
  hetki. Järjestys tekohetken mukaan, vuorokausiraja vastaanottohetken mukaan.
  Kentät ja arvot ovat kiinteät (`src/reports.ts`, ei vapaata tekstiä); `poi` on OSM-, AFIR- (`c-…`) tai kertatuonnin
  (`k-…`) tunniste. Vastaus 201 `{date}`; saman `rid`:n uusinta 200 `{date, duplicate: true}` ilman uutta riviä;
  tuntematon kohde 404; yli 50 ilmoitusta laitteelta 24 tunnissa tai yli 10 osoitteesta minuutissa 429. Laitetunniste on sovelluksen satunnainen tunniste;
  palvelin ei tallenna osoitetta.
- `GET /v1/reports?bbox=…`: ikkunan uusin ilmoitus kohdetta ja kenttää kohti `{items: [{poi, field, value, date}]}`,
  `Cache-Control: no-store`, jotta ilmoitus näkyy muille heti. Kohteissa myös `at` (tekohetki).
- `POST /admin/facts` `{rows: [{id, cat, lat, lon, name?, brand?, addr?, linked, facts}]}` (Bearer, enintään 500 riviä):
  kertatuonnin faktat, vain sallitut `k:`-avaimet (`src/admin.ts`). `POST /admin/facts/clear` poistaa koko lähteen
  (vain pyynnöstä).
- `POST /admin/addr/list` `{}` ja `POST /admin/addr/put` `{rows: [{id, lat, lon, addr, checked}]}` (Bearer, enintään 500
  riviä, `413` yli): osoitteiden täydennyksen välimuisti (`addr_fill`). `addr` on enintään 200 merkkiä tai `null` ("ei
  osoitetta"), `checked` on hakupäivä `VVVV-KK-PP`. Digitransitin käänteishaun tulokset (© Digitransit 2026, CC BY 4.0;
  OpenStreetMap-pohjaiset osoitteet ODbL).
- `POST /admin/reports/delete` `{id}`, `{device}` tai `{since, until}` (Bearer): ilmoituksen, laitteen kaikkien
  ilmoitusten tai aikavälillä vastaanotettujen ilmoitusten poisto (tulvan siivous ilman osoitetietoa).
- `POST /admin/places/status|begin|import|swap|prune` (Bearer): yrityshaun tuonti sukupolvittain. `begin` antaa uuden
  sukupolven (`{gen}`), `import` `{gen, rows[≤500]}` kirjoittaa siihen rivit ja sanat, `swap` `{gen, release, force?}`
  vaihtaa haun siihen yhdellä rivipäivityksellä (tyhjä sukupolvi tai alle puolet edellisestä ilman forcea → 409; jo
  vaihdetun sukupolven uusinta → `{ok: true, alreadySwapped: true}`),
  ja `prune` poistaa enintään 20 000 riviä muista sukupolvista (kutsutaan, kunnes `deleted` on 0). Keskeneräinen
  tuonti ei näy haussa. Putki: `pipeline/places.py` (työnkulku `.github/workflows/places-update.yml`): Overturen uusin
  julkaisu S3:sta DuckDB:llä, maat FI ja AX, pois nimettömät, muut kuin avoinna olevat, luotettavuus alle 0,3 ja
  luontokohteet; kaksoiskappaleet yhdistetään ja yli 200 merkin kentät jätetään pois. Kuiva-ajo `python
  pipeline/places.py --dry-run` (vaatii `pip install duckdb==2.0.0`): julkaisu 2026-10-21.0 → 279 014 riviä, 0 hylättyä.
- `POST /admin/begin|import|swap` (`Authorization: Bearer <ADMIN_TOKEN>`): aineistoputken käyttöön. Vaihto ei koskaan
  tyhjennä maata: tyhjä välitaulu antaa 409 (saman otteen uusinta kuitataan jo vaihdetuksi), ja yli puolen pudotus
  edellisestä rivimäärästä vaatii pakotuksen (`upload.py --force`, `POI_FORCE=1 run.sh FI` tai työnkulun force-valinta).

Suojat: 60 kyselyä (myös ilmoitukset) minuutissa osoitetta kohti ja 1 200 minuutissa yhteensä sijaintia kohti (Rate Limiting
-bindingit); yrityshaulla oma osoiteraja 120 minuutissa (`RATE_SEARCH`), koska haku lähtee kirjoitettaessa; osoitehaulla (`/v1/geocode/*`) oma osoiteraja 120 minuutissa (`RATE_GEOCODE_IP`) ja oma 600 minuutissa sijaintia kohti (`RATE_GEOCODE`), ei yhteistä rajaa; KV-avain `paused` pysäyttää palvelun (503). Palvelin ei lokita kyselyn sisältöä.

Kustannukset: Workers Paid -tilaus sisältää D1:ssä 25 miljardia luettua ja 50 miljoonaa kirjoitettua riviä sekä 5 Gt
tallennustilaa kuukaudessa (Cloudflaren hinnasto 28.10.2026). Yrityshaun kuukausituonti kirjoittaa ja siivoaa noin
6 miljoonaa riviä (paikat, sanat ja hakemistot), ja yksi haku lukee enintään noin 12 000 riviä (neljän pisimmän sanan
laskenta enintään 2 001 riviä kukin ja enintään kaksi 2 000 rivin ehdokasjoukkoa).

## Kehitys

```
npm install
npm test                                   # vitest, kerta-ajo
npx wrangler d1 execute tyomatka-poi --local --file schema.sql
npx wrangler dev --local                   # .dev.vars: ADMIN_TOKEN=paikallinen
```

## Käyttöönotto

1. `npx wrangler whoami`
2. `npx wrangler d1 create tyomatka-poi` → `database_id` tiedostoon `wrangler.jsonc`
3. `npx wrangler kv namespace create KV` → `id` tiedostoon `wrangler.jsonc`
4. `npx wrangler d1 execute tyomatka-poi --remote --file schema.sql` (vain `CREATE … IF NOT EXISTS`; ajetaan myös
   uusien taulujen jälkeen: `report` ja `station_facts` 23.10.2026, `place`, `place_word` ja `place_meta` 28.10.2026,
   `addr_fill` 31.10.2026)
5. `npx wrangler secret put ADMIN_TOKEN` (sama arvo GitHub-salaisuuteen `POI_ADMIN_TOKEN`; muuttuja `POI_BASE_URL`;
   Digitransitin avain GitHub-salaisuuteen `DIGITRANSIT_KEY`). `npx wrangler secret put DIGITRANSIT_KEY` osoitehakua varten
6. `npx wrangler deploy` (luo Workerin ja Custom Domainin `poi.jrs8205.com`; ei muita muutoksia tiliin)
7. `gh workflow run poi-update.yml -f countries=FI`
8. `gh workflow run places-update.yml` (yrityshaku; ajastettuna keskiviikkoisin, tuo vain uuden Overture-julkaisun)

Palvelun pysäytys: `npx wrangler kv key put --remote --namespace-id <id> paused 1`; tarkistus: `npx wrangler kv key get --remote --namespace-id <id> paused`
(tulostaa `1`); jatko: `npx wrangler kv key delete --remote --namespace-id <id> paused`. Ilman `--remote`-valintaa Wrangler 4
kirjoittaa vain paikalliseen kehitystallennukseen, eikä tuotanto pysähdy.
