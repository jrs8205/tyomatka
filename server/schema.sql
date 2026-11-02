-- Karttakohteet (OpenStreetMap, ODbL). Sama kohde voi kuulua kahteen luokkaan (huoltoasema ja kaasuasema),
-- joten avain on (id, cat). cell = 0,25°:n ruudukon avain (ks. src/query.ts).
CREATE TABLE IF NOT EXISTS poi (
  id TEXT NOT NULL,
  cat TEXT NOT NULL,
  country TEXT NOT NULL,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  cell INTEGER NOT NULL,
  name TEXT,
  brand TEXT,
  addr TEXT,
  hgv INTEGER NOT NULL DEFAULT 0,
  tags TEXT NOT NULL,
  updated TEXT NOT NULL,
  PRIMARY KEY (id, cat)
);
CREATE INDEX IF NOT EXISTS poi_cell_cat ON poi (cell, cat);
CREATE INDEX IF NOT EXISTS poi_country ON poi (country);

-- Välitaulu: putki kirjoittaa maan rivit tänne ja vaihtaa ne poi-tauluun yhdessä erässä.
CREATE TABLE IF NOT EXISTS poi_staging (
  id TEXT NOT NULL,
  cat TEXT NOT NULL,
  country TEXT NOT NULL,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  cell INTEGER NOT NULL,
  name TEXT,
  brand TEXT,
  addr TEXT,
  hgv INTEGER NOT NULL DEFAULT 0,
  tags TEXT NOT NULL,
  updated TEXT NOT NULL,
  PRIMARY KEY (id, cat)
);
CREATE INDEX IF NOT EXISTS poi_staging_country ON poi_staging (country);

CREATE TABLE IF NOT EXISTS meta (
  country TEXT PRIMARY KEY,
  extractDate TEXT NOT NULL,
  rows INTEGER NOT NULL,
  updated TEXT NOT NULL
);

-- Karttakohteiden osoitteiden täydennys (pipeline/fill_addr.py): Digitransitin käänteishaun tulos kohdetta kohti.
-- addr NULL = osoitetta ei löytynyt, jolloin samaa kohdetta ei haeta joka viikko uudelleen. checked = hakupäivä.
CREATE TABLE IF NOT EXISTS addr_fill (
  id TEXT PRIMARY KEY,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  addr TEXT,
  checked TEXT NOT NULL
);

-- Kuljettajien ilmoitukset (src/reports.ts). rid = sovelluksen ilmoitustunniste (uusinta ei tuota toista riviä),
-- device = sovelluksen satunnainen laitetunniste, created = tekohetki (järjestys), received = vastaanottohetki (raja ja
-- siivous), cell = kohteen solu ilmoitushetkellä. Osoitetta ei tallenneta.
CREATE TABLE IF NOT EXISTS report (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  rid TEXT UNIQUE NOT NULL,
  poi TEXT NOT NULL,
  field TEXT NOT NULL,
  value TEXT NOT NULL,
  device TEXT NOT NULL,
  created TEXT NOT NULL,
  received TEXT NOT NULL,
  cell INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS report_cell ON report (cell);
CREATE INDEX IF NOT EXISTS report_poi_field ON report (poi, field);
CREATE INDEX IF NOT EXISTS report_device_received ON report (device, received);
CREATE INDEX IF NOT EXISTS report_received ON report (received);

-- Poistetut laitetunnukset (src/reports.ts): kesken ollut lähetys ei tallennu omien ilmoitusten poiston jälkeen.
-- Vain tunnus ja poistohetki; rivi siivotaan enintään 7 vuorokauden kuluttua.
CREATE TABLE IF NOT EXISTS forgotten_device (
  device TEXT PRIMARY KEY,
  at TEXT NOT NULL
);

-- Kertatuonnin faktat 22.10.2026: linked = 1 täydentää samannimistä OSM-kohdetta,
-- linked = 0 on oma k-asema. facts = JSON-olio k:-avaimin. Koko lähteen poisto: POST /admin/facts/clear.
CREATE TABLE IF NOT EXISTS station_facts (
  id TEXT PRIMARY KEY,
  cat TEXT NOT NULL,
  lat REAL,
  lon REAL,
  cell INTEGER,
  name TEXT,
  brand TEXT,
  addr TEXT,
  linked INTEGER NOT NULL,
  facts TEXT NOT NULL,
  updated TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS station_facts_cell_cat ON station_facts (cell, cat);

-- Yrityshaku (Overture Places, CDLA Permissive 2.0 / Foursquare Apache 2.0; src/places.ts). gen = tuonnin sukupolvi:
-- haku lukee vain aktiivista (place_meta.active), tuonti kirjoittaa uutta sukupolvea ja prune siivoaa muut erissä, joten
-- vaihto on yksi rivipäivitys eikä satojentuhansien rivien kopio. nwords/lwords = nimen ja paikkakunnan sanat.
CREATE TABLE IF NOT EXISTS place (
  gen INTEGER NOT NULL,
  id TEXT NOT NULL,
  name TEXT NOT NULL,
  street TEXT,
  postcode TEXT,
  locality TEXT,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  conf REAL NOT NULL,
  nwords TEXT NOT NULL,
  lwords TEXT NOT NULL,
  PRIMARY KEY (gen, id)
);

-- Nimen ja paikkakunnan sanat hakemistona: sanan alku haetaan välillä [sana, sana + U+FFFF). Yksilöivä hakemisto
-- tekee erän uusinnasta harmittoman (INSERT OR IGNORE) ja kattaa ehdokasliitoksen ilman taulun lukua.
CREATE TABLE IF NOT EXISTS place_word (
  gen INTEGER NOT NULL,
  word TEXT NOT NULL,
  id TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS place_word_gen_word_id ON place_word (gen, word, id);

CREATE TABLE IF NOT EXISTS place_meta (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  active INTEGER,
  staging INTEGER,
  release TEXT,
  rows INTEGER NOT NULL DEFAULT 0,
  updated TEXT
);
