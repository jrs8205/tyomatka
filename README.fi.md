<p align="right">🇬🇧 <a href="README.md">In English</a> · 🇸🇪 <a href="README.sv.md">På svenska</a></p>

# Työmatka

[![Versio](https://img.shields.io/github/v/release/jrs8205/tyomatka?label=versio&color=1565C0)](https://github.com/jrs8205/tyomatka/releases/latest)
[![Lataukset](https://img.shields.io/github/downloads/jrs8205/tyomatka/total?label=lataukset&color=00695C)](https://github.com/jrs8205/tyomatka/releases)
[![Lisenssi: GPL-3.0](https://img.shields.io/badge/lisenssi-GPL--3.0-blue.svg)](LICENSE)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#vaatimukset)

Kuljettajan reitti-, navigointi- ja työmatkasovellus Androidille. Sovellus
toimii kokonaan avoimilla karttapalveluilla: ei API-avaimia, ei
käyttöpohjaista laskutusta, ei tiliä.

Sovelluksen nimi on suomenkielisessä puhelimessa **Työmatka**,
englanninkielisessä **Work Trip** ja ruotsinkielisessä **Körjournal**.

**[Lataa uusin APK](https://github.com/jrs8205/tyomatka/releases/latest)**

## Kenelle

- **Rekkakuskille**, jolla on lista purkupaikkoja ja viimeiselle aikaraja.
- **Jakoauton kuljettajalle**, joka ajaa 20–25 stoppia päivässä ja haluaa
  tietää, milloin päivä on valmis.
- **Taksikuskille**, joka tarvitsee työajat ja kilometrit kirjattuina ilman
  paperitöitä.
- **Kenelle tahansa** omaksi ajopäiväkirjaksi: työpäivät, mittarilukemat,
  tankkaukset ja kulutus raporttina, joka on omissa käsissä.

## Miksi

Ajan itse jakoautoa, ja sovellus syntyi omaan tarpeeseen: päivän stopit
suunnitelmaksi, tieto siitä milloin päivä loppuu, puheohjattu navigointi
stopilta seuraavalle sekä työajat, kilometrit ja tankkaukset kirjattuina
ilman ylimääräistä vaivaa. Jokainen ominaisuus on mukana, koska sitä
käytetään oikeana työpäivänä.

## Mitä se tekee

Sovellukseen lisätään päivän stopit (tyypillisesti 20–25, mutta määrää ei ole rajattu), ja se

- näyttää stopit numeroituina kartalla reittiviivan kanssa ja antaa järjestää
  ne raahaamalla,
- navigoi puheohjeineen stopilta seuraavalle ja siirtyy saapumisen jälkeen
  automaattisesti eteenpäin,
- laskee jokaiselle stopille arvioidun saapumisajan ja koko päivän
  valmistumisajan ja päivittää ennustetta ajon aikana,
- varoittaa, jos "perillä viimeistään" -aikaraja ei ole realistinen,
- oppii toteutuneista ajoajoista ja stoppikestoista henkilökohtaisen
  nopeuskertoimen ja stoppiparien väliajat,
- kirjaa työpäivät (aloitus- ja lopetuslukemat, työaika, auto) ja tankkaukset
  ja laskee kulutuksen täydestä tankista seuraavaan täyteen: diesel,
  bensiini, kaasu, sähkö ja AdBlue omilla yksiköillään, vajaat tankkaukset
  välin sisällä ja summat euroina,
- liittää tankkaukseen kuvia todisteeksi (kuitti, automaatin näyttö,
  latausapin kuvakaappaus): kamerasta, kuvavalitsimesta tai toisesta
  sovelluksesta jakamalla; kuvat kulkevat mukana HTML-raportissa ja
  varmuuskopiossa,
- kysyy auton ensimmäisellä käynnistyksellä, muistaa autojen polttoaineen ja
  vie kaiken HTML-raporttina, joka toimii myös varmuuskopiona.

## Avoimet palvelut

| Tarve | Palvelu |
|---|---|
| Käännös käännökseltä -navigointi ja ääniopastus | [Ferrostar](https://github.com/stadiamaps/ferrostar) + Androidin oma puhesynteesi |
| Reitti navigointiin | [FOSSGIS Valhalla](https://valhalla1.openstreetmap.de) |
| Koko päivän reitti ja legien kestot | [FOSSGIS OSRM](https://routing.openstreetmap.de) |
| Karttalaatat | [OpenFreeMap](https://openfreemap.org) |
| Karttarenderöinti | [MapLibre](https://maplibre.org) |
| Karttakohteet (huoltoasemat, latauspisteet, kaasuasemat, lepopaikat ja rekkaparkit) | Oma Cloudflare Worker + D1-kanta, joka johdetaan viikoittain [OpenStreetMapista](https://www.openstreetmap.org/copyright) (ODbL) kansion `server/` putkella |
| Osoitehaku | [Digitransit](https://digitransit.fi) sovelluksen oman palvelimen kautta; varalla Suomeen rajattu [Photon](https://photon.komoot.io) |
| Yritysnimet | Oma Cloudflare-Worker ja D1-tietokanta, jossa [Overture Maps Foundationin](https://overturemaps.org) Suomen paikat (CDLA Permissive 2.0; Foursquaren aineisto Apache 2.0); `server/`-kansion putki tarkistaa viikoittain ja tuo jokaisen uuden Overture-julkaisun (noin kuukausittain) |

Palvelimet ovat vapaaehtoisvoimin ylläpidettyjä ja tarkoitettu kohtuulliseen
käyttöön: sovellus lähettää tunnistettavan User-Agentin, kuristaa reittipyynnöt
enintään yhteen sekunnissa per palvelin, hakee osoitteita vasta lyhyen
kirjoitustauon jälkeen ja hakee reitin vain kun stoppilista tai lähtöpaikka
oikeasti muuttuu. Palvelimien osoitteet voi vaihtaa
asetuksista omaan Valhalla-, OSRM- tai Photon-palvelimeen.

Sovellus toimii vain Suomessa (Ahvenanmaa mukaan lukien): osoitehaku ehdottaa
vain Suomen osoitteita, myös kun puhelin on ulkomailla. Ensisijainen haku on
Digitransit; jos se ei vastaa tai ei löydä mitään, haku siirtyy Suomeen
rajattuun Photoniin, ja noin 30 km:n säteellä olevat osumat
näytetään ensin. Tulosten lähteet näkyvät tuloslistan alla.

Sovellus ei tarvitse avaimia: Digitransit-avain on sovelluksen omalla palvelimella
(`DIGITRANSIT_KEY`, katso `server/README.md`), joka välittää haut tallentamatta niitä.
Suomen osoitteita voi hakea myös ruotsinkielisillä nimillä (Mannerheimvägen 10
Helsingfors).

Yritysnimet (Askolan Auto- ja Konekorjaamo, Lidl Porvoo) haetaan osoitteiden
rinnalla sovelluksen omalta palvelimelta Overture Mapsin paikoista. Nimellä
haettaessa yritykset tulevat ensin, talonumerolla osoitteet. Valittu yritys saa
virallisen osoitteen ja koordinaatit osoitehausta (Digitransit), kun talonumero
täsmää, muuten lähimmän osoitteen 300 m:n säteeltä ja viimeisenä Overturen oman
osoitteen ja pisteen.

Karttadata © [OpenStreetMap](https://www.openstreetmap.org/copyright) -yhteisö
(ODbL). Osoitedata: Digitransit (CC BY 4.0) ja OpenStreetMap-yhteisö.

## Rakenne

| Moduuli | Sisältö |
|---|---|
| `core` | Puhdas Kotlin/JVM: ETA-moottori, historiaoppiminen, osoitehaku (Digitransit/Pelias ja Suomeen rajattu Photon) ja sen rinnalla yrityshaku sekä OSRM-reittiasiakas (`core/api`), pyyntökuristin, polyline-purku. Yksikkötestattu, ei Android-riippuvuuksia. |
| `app` | Android-sovellus: Jetpack Compose (Material 3), Room, Hilt, foreground service, navigointi- ja karttakerros. |
| `server` | Karttakohteiden ja yrityshaun palvelin: Cloudflare Worker (TypeScript) + D1-skeema sekä GitHub Actions -putket (`server/pipeline`): osmium-tool + Python suodattaa Geofabrikin Suomen otteen, ja DuckDB + Python tuo Overturen Suomen paikat. |

## Vaatimukset

Android 8.0 (API 26) tai uudempi, 64-bittinen laite.

## Kehitys

```
./gradlew.bat :core:test :app:testDebugUnitTest   # yksikkötestit
./gradlew.bat :app:assembleDebug                  # debug-APK
```

Release-allekirjoitus luetaan juuren `keystore.properties`-tiedostosta
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); tiedosto ja
avaimet eivät kuulu versionhallintaan.

## Lisenssi

GPL-3.0, ks. [LICENSE](LICENSE).

## Tietosuoja ja yhteystiedot

Tietosuojaseloste: https://poi.jrs8205.com/privacy?lang=fi. Yhteystieto: jrs8205@pm.me.
