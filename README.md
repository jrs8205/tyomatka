<p align="right">🇫🇮 <a href="README.fi.md">Suomeksi</a> · 🇸🇪 <a href="README.sv.md">På svenska</a></p>

# Työmatka · Work Trip

[![Version](https://img.shields.io/github/v/release/jrs8205/tyomatka?label=version&color=1565C0)](https://github.com/jrs8205/tyomatka/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/jrs8205/tyomatka/total?label=downloads&color=00695C)](https://github.com/jrs8205/tyomatka/releases)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#requirements)

A route, navigation and work-log app for drivers on Android, built entirely
on open map services: no API keys, no usage-based billing, no account.

*Työmatka* is Finnish for a work trip. The app shows up as **Work Trip** on
English phones, **Körjournal** on Swedish ones and **Työmatka** on Finnish
ones.

**[Download the latest APK](https://github.com/jrs8205/tyomatka/releases/latest)**

## Who it is for

- **Truck drivers** with a list of deliveries and a deadline for the last one.
- **Delivery van drivers** doing 20–25 stops a day and wanting to know when
  the day ends.
- **Taxi drivers** who need their work hours and kilometres logged without
  paperwork.
- **Anyone** who wants a personal driving log: work days, odometer readings,
  fuel fill-ups and consumption, exported as a report you own.

## Why it exists

I drive a delivery van myself. The app grew out of my own need: plan the
day's stops, see when the day will end, get spoken turn-by-turn guidance
from stop to stop, and have the work hours, kilometres and fill-ups written
down without extra effort. Every feature is here because it is used on an
actual work day.

## What it does

Add the day's stops (a typical day has 20–25, but there is no limit) and the app

- shows them numbered on the map with the route line and lets you reorder
  them by dragging,
- navigates with spoken guidance from stop to stop, detects the arrival and
  continues to the next stop with one tap,
- predicts the arrival time at every stop and the finish time of the whole
  day, updating the forecast while you drive,
- warns when a "deliver by" deadline is no longer realistic,
- learns from actual driving and stop times a personal speed factor and
  the travel times between stop pairs,
- logs work days (start and end odometer readings, work time, vehicle) and
  fill-ups, and calculates consumption from one full tank to the next:
  diesel, petrol, gas, electricity and AdBlue in their own units, partial
  fill-ups within the interval, and amounts in euros,
- attaches photos to a fill-up as proof (receipt, pump display, a charging
  app's screenshot): taken with the camera, picked from the gallery or shared
  to the app from another app; the photos travel in the HTML report and the
  backup,
- asks for your vehicle on first start, remembers each vehicle's fuel and
  exports everything as an HTML report that also works as a backup.

## Open services

| Need | Service |
|---|---|
| Turn-by-turn navigation and spoken guidance | [Ferrostar](https://github.com/stadiamaps/ferrostar) + Android's own text-to-speech |
| Route for navigation | [FOSSGIS Valhalla](https://valhalla1.openstreetmap.de) |
| Whole-day route and leg durations | [FOSSGIS OSRM](https://routing.openstreetmap.de) |
| Map tiles | [OpenFreeMap](https://openfreemap.org) |
| Map rendering | [MapLibre](https://maplibre.org) |
| Map places (fuel stations, charging points, gas stations, rest areas and truck parks) | Own Cloudflare Worker + D1 database derived weekly from [OpenStreetMap](https://www.openstreetmap.org/copyright) (ODbL) with the pipeline in `server/` |
| Address search | [Digitransit](https://digitransit.fi) through the app's own server; as a fallback [Photon](https://photon.komoot.io) limited to Finland |
| Business names | Own Cloudflare Worker + D1 database with the Finnish places of [Overture Maps Foundation](https://overturemaps.org) (CDLA Permissive 2.0; Foursquare data Apache 2.0); the pipeline in `server/` checks weekly and imports each new Overture release (about monthly) |

The servers are run by volunteers and meant for fair use: the app sends an
identifiable User-Agent, throttles route requests to at most one per second
per server, searches addresses only after a short pause in typing and fetches
a route only when the stop list or the starting point actually changes. Server URLs can be pointed at your own Valhalla, OSRM or
Photon instance from the settings.

The app works in Finland only (Åland included): address search suggests Finnish
addresses only, even when the phone is abroad. Digitransit is searched first; if
it does not answer or finds nothing, the search moves on to Photon
limited to Finland, and results within about 30 km are listed first. The data
sources of the results are shown below the result list.

The app needs no keys: the Digitransit key is on the app's own server
(`DIGITRANSIT_KEY`, see `server/README.md`), which forwards searches without storing
them. Finnish addresses can also be searched by their Swedish names
(Mannerheimvägen 10 Helsingfors).

Business names (Askolan Auto- ja Konekorjaamo, Lidl Porvoo) are searched
alongside addresses from the Overture Maps places on the app's own server. A
name lists businesses first, a house number lists addresses first. A selected
business gets its official address and coordinates from the address search
(Digitransit) when the house number matches, otherwise the nearest address
within 300 m, and as a last resort Overture's own address and point.

Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors
(ODbL). Address data: Digitransit (CC BY 4.0) and OpenStreetMap contributors.

## Project layout

| Module | Contents |
|---|---|
| `core` | Pure Kotlin/JVM: ETA engine, history learning, address search (Digitransit/Pelias with Photon limited to Finland) with business search alongside it, and OSRM route client (`core/api`), request throttle, polyline decoding. Unit-tested, no Android dependencies. |
| `app` | Android app: Jetpack Compose (Material 3), Room, Hilt, foreground service, navigation and map layer. |
| `server` | Map places and business search backend: Cloudflare Worker (TypeScript) + D1 schema, and the GitHub Actions pipelines (`server/pipeline`): osmium-tool + Python filters the Geofabrik extract of Finland, and DuckDB + Python imports the Finnish Overture places. |

## Requirements

Android 8.0 (API 26) or newer, 64-bit device.

## Verifying the APK

Every release on GitHub is signed with the same key. The SHA-256 fingerprint
of the signing certificate is

```
54:67:D2:D8:1F:44:33:5A:5A:0C:ED:95:B9:49:C7:98:3F:80:58:99:AD:CE:10:26:A5:6F:F9:4B:C2:FD:89:5B
```

Check a downloaded file with `apksigner verify --print-certs tyomatka-<version>.apk`
(Android SDK build tools), which prints the same value in lower case without
colons (`5467d2d81f44335a5a0ced95b949c7983f805899adce1026a56ff94bc2fd895b`), or with an app such as AppVerifier. Each release also
lists the SHA-256 checksum of the APK file itself.

## Development

```
./gradlew.bat :core:test :app:testDebugUnitTest   # unit tests
./gradlew.bat :app:assembleDebug                  # debug APK
```

Release signing is read from `keystore.properties` in the project root
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); the file and the
keys are not under version control.

## License

GPL-3.0, see [LICENSE](LICENSE).

## Privacy and contact

Privacy policy: https://poi.jrs8205.com/privacy?lang=en. Contact: jrs8205@pm.me.
