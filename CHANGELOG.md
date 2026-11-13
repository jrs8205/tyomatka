# Changelog

## 1.1.1 (2026-11-13)

Fixes and finishing touches after the new look.

- Large font sizes: all three tabs of the add-stop window fit on the screen,
  and the bottom bar labels stay off the screen edge.
- Screen readers read the names of the add-stop and reorder buttons and
  announce the notices.
- Search fields have a button that clears the text.
- The map places menu opens at full height, so the heavy vehicle and
  along-the-route switches are visible at once.
- Adding a place from the Map page during a drive asks first.
- The station card lists each nearby charging point with its connectors and,
  when the open data has it, its price.
- A letter after the house number no longer makes a separate stop: 5, 5 A
  and 5 B are the same building.
- Imported address lists are checked by Finnish address rules only. .xls and
  .ods files can be picked and get a notice to save them as xlsx or csv.
- Money, amounts and kilometres use the same thousands grouping as whole
  numbers.
- The day view's add button follows the car: fill-up, charge or refill.
- Notices and map places give the same tap feedback as other buttons.
- End drive and the other outlined buttons share one look.

## 1.1.0 (2026-11-12)

A new look for the whole app.

- One shared look on every page, form and dialog, with AAA contrast in the
  light and dark themes and text up to 150 %.
- Work Trip: one week, month or year at a time, browsed with arrows or a
  swipe, with the kilometres, the fuel and its cost at the top and the
  other periods listed below. A chosen day shows all its drives and
  fill-ups.
- The Today card shows the odometer readings from start to end and says
  "in progress" while the work day continues.
- Plan: the day's stops as a numbered timeline with the arrival time next to
  each stop.
- The app opens on Work Trip; the bottom bar is Work Trip, Plan, Map, Drive.
- Fastest or Shortest is chosen on the Map page, and Shortest stays for the
  day it was chosen.
- Days with a note or a photo are marked in the list.
- Drive: the speed and the speed limit stay visible above the closed bottom
  panel.
- Large font sizes: units stay on the same line as their numbers, the route
  fits below the route cards on the map, and nearby results stay visible
  above the keyboard.

## 1.0.0 (2026-11-03)

First public release.

- The day's stops on the map, numbered with the route line and reordered by
  dragging; the arrival time at every stop and the finish time of the day,
  updated while driving.
- Spoken turn-by-turn navigation from stop to stop (Ferrostar and FOSSGIS
  Valhalla), with the Fastest or the Shortest route.
- Work days with odometer readings, work time and vehicle; fill-ups for
  diesel, petrol, gas, electricity and AdBlue with consumption from one full
  tank to the next and photos as proof.
- Nearby fuel stations, charging points, gas stations, rest areas and truck
  parks, with corrections sent by drivers.
- Address and business search: Digitransit and Overture Maps places through
  the app's own server, Photon as a fallback.
- Work-day reminders at a chosen time or when the phone connects to the car
  over Bluetooth.
- An HTML report for a chosen period and a full backup that can be restored.
- Finnish, Swedish and English. Works in Finland, Åland included.
- No account, no API keys and no Google Play services.
