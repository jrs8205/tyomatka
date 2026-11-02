<p align="right">🇬🇧 <a href="README.md">In English</a> · 🇫🇮 <a href="README.fi.md">Suomeksi</a></p>

# Työmatka · Körjournal

[![Version](https://img.shields.io/github/v/release/jrs8205/tyomatka?label=version&color=1565C0)](https://github.com/jrs8205/tyomatka/releases/latest)
[![Nedladdningar](https://img.shields.io/github/downloads/jrs8205/tyomatka/total?label=nedladdningar&color=00695C)](https://github.com/jrs8205/tyomatka/releases)
[![Licens: GPL-3.0](https://img.shields.io/badge/licens-GPL--3.0-blue.svg)](LICENSE)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#krav)

En rutt-, navigations- och körjournalsapp för förare på Android, byggd helt
på öppna karttjänster: inga API-nycklar, ingen användningsbaserad
fakturering, inget konto.

*Työmatka* är finska för arbetsresa. Appen heter **Körjournal** på svenska
telefoner, **Work Trip** på engelska och **Työmatka** på finska.

**[Ladda ner senaste APK](https://github.com/jrs8205/tyomatka/releases/latest)**

## För vem

- **Lastbilsförare** med en lista lossningsställen och en deadline för det
  sista.
- **Förare av distributionsbilar** som kör 20–25 stopp om dagen och vill
  veta när dagen är slut.
- **Taxiförare** som behöver arbetstid och kilometer loggade utan
  pappersarbete.
- **Vem som helst** som vill ha en personlig körjournal: arbetsdagar,
  mätarställningar, tankningar och förbrukning i en rapport man själv äger.

## Varför

Jag kör själv distributionsbil, och appen växte fram ur mitt eget behov:
planera dagens stopp, se när dagen tar slut, få röststyrd navigation från
stopp till stopp och få arbetstid, kilometer och tankningar nedskrivna utan
extra besvär. Varje funktion finns med för att den används en riktig
arbetsdag.

## Vad den gör

Lägg till dagens stopp (en vanlig dag har 20–25, men antalet är inte begränsat) så

- visar appen dem numrerade på kartan med ruttlinje och låter dig ordna om
  dem genom att dra,
- navigerar med röstinstruktioner från stopp till stopp och går vidare
  automatiskt efter varje ankomst,
- beräknar ankomsttid för varje stopp och sluttid för hela dagen och
  uppdaterar prognosen medan du kör,
- varnar när en "framme senast"-deadline inte längre är realistisk,
- lär sig av verkliga körtider och stopptider en personlig hastighetsfaktor
  och restiderna mellan stopp,
- loggar arbetsdagar (mätarställning vid start och slut, arbetstid, fordon)
  och tankningar och beräknar förbrukningen från full tank till nästa full
  tank: diesel, bensin, gas, el och AdBlue i egna enheter, deltankningar
  inom intervallet och belopp i euro,
- bifogar foton till en tankning som bevis (kvitto, pumpens display,
  skärmbild från en laddningsapp): tagna med kameran, valda i bildväljaren
  eller delade till appen från en annan app; fotona följer med i
  HTML-rapporten och säkerhetskopian,
- frågar efter ditt fordon vid första starten, kommer ihåg fordonens bränsle
  och exporterar allt som en HTML-rapport som också fungerar som
  säkerhetskopia.

## Öppna tjänster

| Behov | Tjänst |
|---|---|
| Sväng-för-sväng-navigation och röstinstruktioner | [Ferrostar](https://github.com/stadiamaps/ferrostar) + Androids egen talsyntes |
| Rutt för navigation | [FOSSGIS Valhalla](https://valhalla1.openstreetmap.de) |
| Hela dagens rutt och delsträckornas tider | [FOSSGIS OSRM](https://routing.openstreetmap.de) |
| Kartrutor | [OpenFreeMap](https://openfreemap.org) |
| Kartrendering | [MapLibre](https://maplibre.org) |
| Kartplatser (bensinstationer, laddpunkter, gasstationer, rastplatser och lastbilsparkeringar) | Egen Cloudflare Worker + D1-databas som varje vecka härleds ur [OpenStreetMap](https://www.openstreetmap.org/copyright) (ODbL) med pipelinen i `server/` |
| Adressökning | [Digitransit](https://digitransit.fi) via appens egen server; i reserv [Photon](https://photon.komoot.io) begränsad till Finland |
| Företagsnamn | Egen Cloudflare Worker och D1-databas med [Overture Maps Foundations](https://overturemaps.org) platser i Finland (CDLA Permissive 2.0; Foursquares data Apache 2.0); pipelinen i `server/` kontrollerar varje vecka och hämtar varje ny Overture-utgåva (ungefär en gång i månaden) |

Servrarna drivs av frivilliga och är avsedda för skälig användning: appen
skickar en identifierbar User-Agent, begränsar ruttanropen till högst ett per
sekund per server, söker adresser först efter en kort paus i skrivandet och
hämtar en rutt bara när stopplistan eller startpunkten faktiskt ändras. Serveradresserna kan pekas om till en egen Valhalla-, OSRM-
eller Photon-server i inställningarna.

Appen fungerar bara i Finland (Åland inräknat): adressökningen föreslår bara
finländska adresser, även när telefonen är utomlands. Digitransit tillfrågas
först; om den inte svarar eller inte hittar något går sökningen vidare till
Photon begränsad till Finland, och träffar inom cirka 30 km
visas först. Källorna för träffarna visas under resultatlistan.

Appen behöver inga nycklar: Digitransit-nyckeln finns på appens egen server
(`DIGITRANSIT_KEY`, se `server/README.md`), som vidarebefordrar sökningar utan att
spara dem. Finländska adresser kan också sökas med sina svenska namn
(Mannerheimvägen 10 Helsingfors).

Företagsnamn (Askolan Auto- ja Konekorjaamo, Lidl Porvoo) söks parallellt med
adresser från Overture Maps platser på appens egen server. Med ett namn kommer
företagen först, med ett husnummer adresserna. Ett valt företag får sin
officiella adress och koordinater från adressökningen (Digitransit) när
husnumret stämmer, annars närmaste adress inom 300 m och sist Overtures egen
adress och punkt.

Kartdata © [OpenStreetMap](https://www.openstreetmap.org/copyright)
-bidragsgivare (ODbL). Adressdata: Digitransit (CC BY 4.0) och
OpenStreetMap-bidragsgivare.

## Projektstruktur

| Modul | Innehåll |
|---|---|
| `core` | Ren Kotlin/JVM: ETA-motor, inlärning från historik, adressökning (Digitransit/Pelias och Photon begränsad till Finland) med företagssökning vid sidan om, och OSRM-ruttklient (`core/api`), anropsbegränsare, polyline-avkodning. Enhetstestad, inga Android-beroenden. |
| `app` | Android-app: Jetpack Compose (Material 3), Room, Hilt, förgrundstjänst, navigations- och kartlager. |
| `server` | Backend för kartplatser och företagssökning: Cloudflare Worker (TypeScript) + D1-schema samt GitHub Actions-pipelinerna (`server/pipeline`): osmium-tool + Python filtrerar Geofabriks utdrag för Finland, och DuckDB + Python hämtar Overtures platser i Finland. |

## Krav

Android 8.0 (API 26) eller nyare, 64-bitars enhet.

## Utveckling

```
./gradlew.bat :core:test :app:testDebugUnitTest   # enhetstester
./gradlew.bat :app:assembleDebug                  # debug-APK
```

Release-signeringen läses från `keystore.properties` i projektroten
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); filen och
nycklarna ligger inte under versionshantering.

## Licens

GPL-3.0, se [LICENSE](LICENSE).

## Integritet och kontakt

Integritetspolicy: https://poi.jrs8205.com/privacy?lang=sv. Kontakt: jrs8205@pm.me.
