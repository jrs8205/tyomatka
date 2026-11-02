/**
 * Yrityshaku Overture Places -aineistosta. Sama normalisointi
 * laskee tuonnissa kannan sanat ja haussa hakusanan sanat, joten "Auto-" ja "auto" ovat sama sana.
 */
export const MAX_PLACES = 8;

/** Yhtiömuodot eivät erota yrityksiä: "Askolan auto ja kone oy" ei saa vaatia nimeen sanaa "oy". */
export const LEGAL_FORMS: ReadonlySet<string> = new Set(["oy", "oyj", "ab", "abp", "ky", "ay", "tmi", "ry", "osk", "ltd", "as"]);

/** Vähintään näin monen sanan haku kelpaa yhtä sanaa vaille (kirjoitusvirhe tai ylimääräinen sana). */
const FALLBACK_MIN_WORDS = 3;

const TMI = /(^|[^\p{L}\p{N}])t:mi(?=$|[^\p{L}\p{N}])/gu;
/** Heittomerkki on osa sanaa: "McDonald's" löytyy myös hakusanalla "mcdonalds". */
const APOSTROPHE = /['’ʼ`´]/gu;
/** NFC:n jälkeen jäävät yhdistävät merkit ("İ" pienenä on "i" + U+0307) eivät saa katkaista sanaa. */
const MARK = /\p{M}+/gu;
const SEPARATOR = /[^\p{L}\p{N}]+/u;

/**
 * Normalisoidut sanat järjestyksessä: NFC, pienet kirjaimet, heittomerkit ja yhdistävät merkit pois, muut välimerkit
 * erottimina, yhtiömuodot pois.
 */
export function tokens(text: string): string[] {
  return text
    .normalize("NFC")
    .toLowerCase()
    .replace(MARK, "")
    .replace(APOSTROPHE, "")
    .replace(TMI, "$1tmi")
    .split(SEPARATOR)
    .filter((word) => word !== "" && !LEGAL_FORMS.has(word));
}

/** Kannan rivi: `nwords` ja `lwords` ovat nimen ja paikkakunnan sanat välilyönnein. */
export interface PlaceCandidate {
  id: string;
  name: string;
  street: string | null;
  postcode: string | null;
  locality: string | null;
  lat: number;
  lon: number;
  conf: number;
  nwords: string;
  lwords: string;
}

interface Scored {
  place: PlaceCandidate;
  matched: number;
  whole: number;
  distanceKm: number;
}

/**
 * Hakusanan sana osuu, kun se on jonkin nimen tai paikkakunnan sanan alku; vähintään yhden sanan on osuttava nimeen.
 * Järjestys: täsmäävät sanat, kokonaisina täsmäävät sanat, etäisyys, luotettavuus ja nimi.
 */
export function rankPlaces(query: string[], candidates: PlaceCandidate[], near: [number, number] | null): PlaceCandidate[] {
  return rank(query, candidates, near).places;
}

/** [rankPlaces] ja tieto siitä, löytyikö täysi osuma (muuten tulos on varahausta tai tyhjä). */
function rank(query: string[], candidates: PlaceCandidate[], near: [number, number] | null): { places: PlaceCandidate[]; full: boolean } {
  if (query.length === 0) return { places: [], full: false };
  const seen = new Set<string>();
  const scored: Scored[] = [];
  for (const place of candidates) {
    if (seen.has(place.id)) continue;
    seen.add(place.id);
    const s = score(query, place, near);
    if (s) scored.push(s);
  }
  let hits = scored.filter((s) => s.matched === query.length);
  const full = hits.length > 0;
  if (!full && query.length >= FALLBACK_MIN_WORDS) hits = scored.filter((s) => s.matched >= query.length - 1);
  hits.sort(
    (a, b) =>
      b.matched - a.matched ||
      b.whole - a.whole ||
      a.distanceKm - b.distanceKm ||
      b.place.conf - a.place.conf ||
      a.place.name.localeCompare(b.place.name, "fi"),
  );
  return { places: hits.slice(0, MAX_PLACES).map((s) => s.place), full };
}

function score(query: string[], place: PlaceCandidate, near: [number, number] | null): Scored | null {
  const nameWords = words(place.nwords);
  const localityWords = words(place.lwords);
  let matched = 0;
  let whole = 0;
  let nameHit = false;
  for (const token of query) {
    const inName = nameWords.some((w) => w.startsWith(token));
    if (!inName && !localityWords.some((w) => w.startsWith(token))) continue;
    matched++;
    if (inName) nameHit = true;
    if (nameWords.includes(token) || localityWords.includes(token)) whole++;
  }
  if (!nameHit) return null;
  return { place, matched, whole, distanceKm: near ? haversineKm(near[0], near[1], place.lat, place.lon) : 0 };
}

function words(text: string): string[] {
  return text === "" ? [] : text.split(" ");
}

function haversineKm(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const rad = Math.PI / 180;
  const dLat = (lat2 - lat1) * rad;
  const dLon = (lon2 - lon1) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * 6371 * Math.asin(Math.sqrt(h));
}

/** Rajapinnan lähdemaininta (lisenssit `server/README.md`). */
export const PLACES_SOURCE = "Overture Maps Foundation (CDLA Permissive 2.0; Foursquare Apache 2.0)";
export const MAX_QUERY_LENGTH = 100;
export const MAX_PLACE_IMPORT_ROWS = 500;
/** Rivejä yhtä siivouskutsua kohti: D1:n yksittäinen lause pysyy lyhyenä, ja putki kutsuu, kunnes mitään ei jää. */
export const PRUNE_LIMIT = 20_000;
/**
 * Lasketaan vain näin monta pisintä eri sanaa: rivien luku pysyy rajattuna (sata merkkiä yksikirjaimisia sanoja ei saa
 * lukea satojatuhansia rivejä), ja pitkä sana on yleensä harvinainen.
 */
const MAX_COUNTED_WORDS = 4;
/**
 * Haun rajat (testeissä pienemmät). [count]: sanan rivejä lasketaan enintään tähän asti, ja [recount]iin asti, jos
 * useampi sana osuu rajaan. [driving]: ehdokasjoukon
 * pohjana olevan sanan rivejä luetaan enintään, jottei hyvin yleinen sana ("a") lue koko aineistoa; raja on
 * kymmenkertainen ehdokasrajaan nähden, joten suodatus ja järjestys ehtivät valita parhaat. [candidates]: ehdokkaita
 * enintään, kun kaikki sanat ja etäisyys on jo otettu huomioon kyselyssä.
 */
export interface SearchLimits {
  count: number;
  recount: number;
  driving: number;
  candidates: number;
}

export const SEARCH_LIMITS: SearchLimits = { count: 2001, recount: 20_001, driving: 20_000, candidates: 2000 };
/** Hakuehtoon enintään näin monta pisintä eri sanaa: SQL-parametrien määrä pysyy rajattuna. */
const MAX_FILTER_WORDS = 8;
/** Sanan alku -haun yläraja: kaikki hakusanalla alkavat sanat ovat välillä [sana, sana + U+FFFF). */
const PREFIX_END = "￿";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const NEAR = /^(-?\d{1,2}(?:\.\d+)?),(-?\d{1,3}(?:\.\d+)?)$/;
/** Vaihto vaatii pakotuksen, jos uudessa sukupolvessa on alle tämän osuuden edellisestä (kuten karttakohteissa). */
const SWAP_MIN_SHARE = 0.5;
/**
 * Tuonti, jonka aloituksesta on kulunut tätä kauemmin, on keskeytynyt (työnkulun aikaraja on kaksi tuntia): siivous perii
 * sen, jottei saman julkaisun keskeytyneen uusintatuonnin rivejä jää kantaan seuraavaan julkaisuun asti.
 */
const STALE_IMPORT_MS = 6 * 3600_000;

export type PlacesQuery = { ok: true; query: string[]; near: [number, number] | null } | { ok: false; error: string };

export function parsePlacesQuery(params: URLSearchParams): PlacesQuery {
  const q = params.get("q")?.trim() ?? "";
  if (q === "") return { ok: false, error: "q puuttuu" };
  if (q.length > MAX_QUERY_LENGTH) return { ok: false, error: `q: enintään ${MAX_QUERY_LENGTH} merkkiä` };
  const nearText = params.get("near");
  if (nearText === null) return { ok: true, query: tokens(q), near: null };
  const match = NEAR.exec(nearText);
  const lat = Number(match?.[1]);
  const lon = Number(match?.[2]);
  if (!match || Math.abs(lat) > 90 || Math.abs(lon) > 180) return { ok: false, error: "near: lat,lon" };
  return { ok: true, query: tokens(q), near: [lat, lon] };
}

/** Rajapinnan rivi: puuttuvat valinnaiset kentät jätetään pois. */
export function placeItem(p: PlaceCandidate): Record<string, unknown> {
  const item: Record<string, unknown> = { id: p.id, name: p.name };
  if (p.street) item.street = p.street;
  if (p.postcode) item.postcode = p.postcode;
  if (p.locality) item.locality = p.locality;
  item.lat = p.lat;
  item.lon = p.lon;
  return item;
}

/**
 * Aktiivinen sukupolvi luetaan jokaisessa lauseessa: vaihto ja siivous voivat osua haun lauseiden väliin, ja erikseen
 * luettu sukupolvi olisi silloin jo poistettu.
 */
const ACTIVE_GEN = "(SELECT active FROM place_meta WHERE id = 1)";
const COUNT_SQL = `SELECT count(*) AS n FROM (SELECT 1 FROM place_word WHERE gen = ${ACTIVE_GEN} AND word >= ?1 AND word < ?2 LIMIT ?3)`;

/**
 * Ehdokkaat harvinaisimman sanan kautta: täysi osuma sisältää jokaisen sanan, joten myös harvinaisimman, eikä yleinen
 * sana ("ja", "auto") katkaise ehdokkaita. Varahaussa (≥ 3 sanaa) mukaan toiseksi harvinaisin, koska yhtä sanaa vaille
 * osuva rivi osuu ainakin toiseen kahdesta. Muiden sanojen ehto ja järjestys (osuneet sanat, kokonaiset sanat,
 * etäisyys, luotettavuus) lasketaan kyselyssä ennen ehdokasrajaa, joten yleinen sana ei katkaise oikeaa tai lähintä
 * yritystä pois; [rank] tekee lopullisen järjestyksen.
 */
export async function searchPlaces(
  db: D1Database,
  query: string[],
  near: [number, number] | null,
  limits: SearchLimits = SEARCH_LIMITS,
): Promise<PlaceCandidate[]> {
  if (query.length === 0) return [];
  const distinct = [...new Set(query)].sort((a, b) => b.length - a.length);
  const counted = distinct.slice(0, MAX_COUNTED_WORDS);
  const counts = await countWords(db, counted, limits.count);
  // Rajaan osuneet sanat näyttävät yhtä yleisiltä, ja tasapeli valitsisi pidemmän, vaikka se olisi paikkakunta
  // kymmenillätuhansilla riveillä ("parturi helsinki"): ne lasketaan uudelleen ehdokasjoukon pohjan rajaan asti.
  const capped = counted.filter((_, i) => (counts[i] ?? 0) >= limits.count);
  if (capped.length >= 2) {
    const again = await countWords(db, capped, limits.recount);
    capped.forEach((token, j) => (counts[counted.indexOf(token)] = again[j] ?? 0));
  }
  const rarest = counted
    .map((token, i) => ({ token, n: counts[i] ?? 0 }))
    .sort((a, b) => a.n - b.n || b.token.length - a.token.length)
    .map((r) => r.token);
  const fallback = query.length >= FALLBACK_MIN_WORDS && rarest.length >= 2;
  const filter = distinct.slice(0, MAX_FILTER_WORDS);
  // Toistuva sana painaa kuten [rank]issa, joka laskee jokaisen esiintymän ("auto auto kone").
  const weights = filter.map((t) => query.filter((q) => q === t).length);
  const total = weights.reduce((a, b) => a + b, 0);
  const { sql, params } = candidatesQuery(filter, weights, fallback ? rarest.slice(0, 2) : rarest.slice(0, 1), {
    // Osajoukolla (yli 8 sanaa) ehto on väljempi kuin [rank]in: rivi, jolta puuttuu enintään yksi esiintymä, osuu
    // osajoukosta vähintään yhtä vaille.
    minMatched: fallback ? total - 1 : total,
    nameHit: filter.length === distinct.length,
    near,
    limits,
  });
  const candidates = (await db.prepare(sql).bind(...params).all<PlaceCandidate>()).results ?? [];
  return rankPlaces(query, candidates, near);
}

/**
 * Ehdokaskysely: [filter]in sanat parametreina ?1…?k ja niiden välien loput ?k+1…?2k. Sana osuu, kun se on nimen tai
 * paikkakunnan sanan alku (`instr` välilyönnin kanssa), ja on kokonainen, kun sitä seuraa välilyönti; [weights] =
 * sanan esiintymät hakusanassa.
 */
function candidatesQuery(
  filter: string[],
  weights: number[],
  driving: string[],
  options: { minMatched: number; nameHit: boolean; near: [number, number] | null; limits: SearchLimits },
): { sql: string; params: unknown[] } {
  const params: unknown[] = [...filter, ...filter.map((t) => t + PREFIX_END)];
  const k = filter.length;
  const token = (t: string) => filter.indexOf(t) + 1;
  const words = "(' ' || p.nwords || ' ' || p.lwords || ' ')";
  // Painot ovat laskettuja kokonaislukuja, eivät käyttäjän tekstiä.
  const matched = filter.map((_, i) => `(instr(${words}, ' ' || ?${i + 1}) > 0) * ${weights[i]}`).join(" + ");
  const whole = filter.map((_, i) => `(instr(${words}, ' ' || ?${i + 1} || ' ') > 0) * ${weights[i]}`).join(" + ");
  const inName = filter.map((_, i) => `instr(' ' || p.nwords, ' ' || ?${i + 1}) > 0`).join(" OR ");
  const sources = driving.map(
    (t) => `SELECT id FROM (SELECT id FROM place_word WHERE gen = ${ACTIVE_GEN} AND word >= ?${token(t)} AND word < ?${token(t) + k} LIMIT ${options.limits.driving})`,
  );
  let distance = "0";
  if (options.near) {
    // Tasokulma riittää järjestykseen: pituusaste kerrotaan leveyspiirin kosinin neliöllä.
    const [lat, lon] = options.near;
    const cos = Math.cos((lat * Math.PI) / 180);
    params.push(lat, lon, cos * cos);
    const [a, b, c] = [params.length - 2, params.length - 1, params.length];
    distance = `(p.lat - ?${a}) * (p.lat - ?${a}) + (p.lon - ?${b}) * (p.lon - ?${b}) * ?${c}`;
  }
  params.push(options.minMatched);
  const min = params.length;
  const sql =
    "SELECT id, name, street, postcode, locality, lat, lon, conf, nwords, lwords FROM (" +
    "SELECT p.id, p.name, p.street, p.postcode, p.locality, p.lat, p.lon, p.conf, p.nwords, p.lwords, " +
    `${matched} AS matched, ${whole} AS whole, ${distance} AS dist ` +
    `FROM place p WHERE p.gen = ${ACTIVE_GEN} AND p.id IN (${sources.join(" UNION ")})` +
    (options.nameHit ? ` AND (${inName})` : "") +
    `) WHERE matched >= ?${min} ORDER BY matched DESC, whole DESC, dist, conf DESC LIMIT ${options.limits.candidates}`;
  return { sql, params };
}

/** Sanojen rivimäärät enintään [cap]iin asti yhdellä `batch`-kutsulla. */
async function countWords(db: D1Database, words: string[], cap: number): Promise<number[]> {
  const results = await db.batch<{ n: number }>(words.map((t) => db.prepare(COUNT_SQL).bind(t, t + PREFIX_END, cap)));
  return results.map((r) => r?.results?.[0]?.n ?? 0);
}

export interface PlaceImportRow {
  id: string;
  name: string;
  street?: string;
  postcode?: string;
  locality?: string;
  lat: number;
  lon: number;
  conf: number;
}

/** Sukupolvi ei ole tuonnissa oleva (vanhentunut tai toisen ajon): 409. */
export class PlaceConflictError extends Error {}

export interface PlaceStatus {
  active: number | null;
  staging: number | null;
  release: string | null;
  rows: number;
}

export async function placesStatus(db: D1Database): Promise<PlaceStatus> {
  const row = await db.prepare("SELECT active, staging, release, rows FROM place_meta WHERE id = 1").first<PlaceStatus>();
  return row ?? { active: null, staging: null, release: null, rows: 0 };
}

/**
 * Uusi sukupolvi on suurempi kuin mikään kannassa oleva, joten keskeneräisen ajon jäänteet eivät sekoitu siihen. Se on
 * myös vähintään aloitushetki sekunteina: perutun ja jo siivotun tuonnin tunnus ei palaa käyttöön, joten sen myöhästynyt
 * erä ei kelpaa uuteen tuontiin (siivous perii tuonnin vasta tuntien päästä, ja kello on sen jälkeen ohittanut tunnuksen).
 */
export async function placesBegin(db: D1Database, now = Date.now()): Promise<{ gen: number }> {
  await db.prepare("INSERT OR IGNORE INTO place_meta (id, rows) VALUES (1, 0)").run();
  const status = await placesStatus(db);
  const stored = (await db.prepare("SELECT max(gen) AS g FROM place").first<{ g: number | null }>())?.g ?? 0;
  const gen = Math.max(stored, status.active ?? 0, status.staging ?? 0, Math.floor(now / 1000) - 1) + 1;
  await db.prepare("UPDATE place_meta SET staging = ?1, updated = ?2 WHERE id = 1").bind(gen, new Date(now).toISOString()).run();
  return { gen };
}

export function validatePlaceImport(body: unknown): { gen: number; rows: PlaceImportRow[] } | { error: string } {
  const { gen, rows } = (body ?? {}) as { gen?: unknown; rows?: unknown };
  if (typeof gen !== "number" || !Number.isSafeInteger(gen) || gen < 1) return { error: "gen" };
  if (!Array.isArray(rows)) return { error: "rows puuttuu" };
  if (rows.length > MAX_PLACE_IMPORT_ROWS) return { error: `rows: enintään ${MAX_PLACE_IMPORT_ROWS} riviä` };
  const out: PlaceImportRow[] = [];
  for (const [i, raw] of rows.entries()) {
    const row = validatePlaceRow(raw);
    if (typeof row === "string") return { error: `rows[${i}]: ${row}` };
    out.push(row);
  }
  return { gen, rows: out };
}

function validatePlaceRow(raw: unknown): PlaceImportRow | string {
  if (!raw || typeof raw !== "object") return "ei olio";
  const r = raw as Record<string, unknown>;
  if (typeof r.id !== "string" || !UUID.test(r.id)) return "id";
  if (typeof r.name !== "string" || r.name.trim() === "" || r.name.length > 200) return "name";
  if (!isCoordinate(r.lat, 90) || !isCoordinate(r.lon, 180)) return "lat/lon";
  if (typeof r.conf !== "number" || !(r.conf >= 0 && r.conf <= 1)) return "conf";
  const row: PlaceImportRow = { id: r.id, name: r.name.trim(), lat: r.lat, lon: r.lon, conf: r.conf };
  for (const field of ["street", "postcode", "locality"] as const) {
    const value = r[field];
    if (value === undefined || value === null || value === "") continue;
    if (typeof value !== "string" || value.length > 200) return field;
    row[field] = value.trim();
  }
  return row;
}

function isCoordinate(value: unknown, limit: number): value is number {
  return typeof value === "number" && Number.isFinite(value) && Math.abs(value) <= limit;
}

const PLACE_COLUMNS = ["id", "name", "street", "postcode", "locality", "lat", "lon", "conf", "nwords", "lwords"] as const;
const IMPORT_PLACE_SQL =
  `INSERT OR REPLACE INTO place (gen, ${PLACE_COLUMNS.join(", ")}) SELECT ?1, ` +
  PLACE_COLUMNS.map((c) => `json_extract(value, '$.${c}')`).join(", ") +
  " FROM json_each(?2)";
const IMPORT_WORD_SQL = "INSERT OR IGNORE INTO place_word (gen, word, id) SELECT ?1, json_extract(value, '$.w'), json_extract(value, '$.id') FROM json_each(?2)";

/** Erä uuteen sukupolveen: rivit ja sanat yhdellä `batch`-kutsulla (D1:ssä atominen); sanat lasketaan [tokens]illa. */
export async function placesImport(db: D1Database, body: { gen: number; rows: PlaceImportRow[] }): Promise<{ inserted: number }> {
  const status = await placesStatus(db);
  if (status.staging !== body.gen) throw new PlaceConflictError(`väärä sukupolvi ${body.gen} (tuonnissa ${status.staging ?? "ei mitään"})`);
  const byId = new Map(body.rows.map((r) => [r.id, r]));
  const places = [...byId.values()].map((r) => ({
    ...r,
    street: r.street ?? null,
    postcode: r.postcode ?? null,
    locality: r.locality ?? null,
    nwords: [...new Set(tokens(r.name))].join(" "),
    lwords: [...new Set(tokens(r.locality ?? ""))].join(" "),
  }));
  const words = places.flatMap((p) =>
    [...new Set(`${p.nwords} ${p.lwords}`.split(" "))].filter((w) => w !== "").map((w) => ({ w, id: p.id })),
  );
  await db.batch([
    db.prepare(IMPORT_PLACE_SQL).bind(body.gen, JSON.stringify(places)),
    db.prepare(IMPORT_WORD_SQL).bind(body.gen, JSON.stringify(words)),
  ]);
  return { inserted: places.length };
}

export type PlaceSwap = { kind: "swap"; rows: number } | { kind: "already" } | { kind: "conflict"; error: string };

/** Vaihto on yksi rivipäivitys: haku siirtyy uuteen sukupolveen kerralla, ja vanha jää prunen siivottavaksi. */
export async function placesSwap(db: D1Database, gen: number, release: string, force: boolean): Promise<PlaceSwap> {
  const status = await placesStatus(db);
  // Uusinta, kun vaihto ehti tallentua mutta vastaus katosi: kuitataan, jotta putki jatkaa siivoukseen.
  if (status.active === gen && status.staging === null && status.release === release) return { kind: "already" };
  if (status.staging !== gen) return { kind: "conflict", error: `väärä sukupolvi ${gen}` };
  const staged = (await db.prepare("SELECT count(*) AS n FROM place WHERE gen = ?1").bind(gen).first<{ n: number }>())?.n ?? 0;
  if (staged === 0) return { kind: "conflict", error: "sukupolvi on tyhjä: hakua ei vaihdeta" };
  if (!force && status.active !== null && staged < status.rows * SWAP_MIN_SHARE) {
    return { kind: "conflict", error: `sukupolvessa ${staged} riviä, edellisessä ${status.rows}: vaihto vaatii pakotuksen` };
  }
  await db
    .prepare("UPDATE place_meta SET active = ?1, staging = NULL, release = ?2, rows = ?3, updated = ?4 WHERE id = 1")
    .bind(gen, release, staged, new Date().toISOString())
    .run();
  return { kind: "swap", rows: staged };
}

/**
 * Poistaa enintään [limit] riviä sukupolvista, jotka eivät ole aktiivinen eikä tuonnissa oleva (ensin sanat, sitten
 * paikat). Ehto on kirjoitettu väleinä, jotta hakemisto (gen, …) rajaa luettavat rivit. Vanhentunut tuonti
 * ([STALE_IMPORT_MS]) perutaan ensin: sen sukupolvi ei enää kelpaa tuontiin eikä vaihtoon.
 */
export async function placesPrune(db: D1Database, limit = PRUNE_LIMIT, now = Date.now()): Promise<{ deleted: number }> {
  let status = await placesStatus(db);
  if (status.staging !== null) {
    const started = (await db.prepare("SELECT updated FROM place_meta WHERE id = 1").first<{ updated: string | null }>())?.updated;
    const startedMs = started ? Date.parse(started) : Number.NaN;
    if (!Number.isFinite(startedMs) || now - startedMs > STALE_IMPORT_MS) {
      await db.prepare("UPDATE place_meta SET staging = NULL WHERE id = 1 AND staging = ?1").bind(status.staging).run();
      status = await placesStatus(db);
    }
  }
  const kept = [status.active, status.staging].filter((g): g is number => g !== null);
  const low = kept.length ? Math.min(...kept) : -1;
  const high = kept.length ? Math.max(...kept) : -1;
  const stale = "(gen < ?1 OR (gen > ?1 AND gen < ?2) OR gen > ?2)";
  const words = await db
    .prepare(`DELETE FROM place_word WHERE rowid IN (SELECT rowid FROM place_word WHERE ${stale} LIMIT ?3)`)
    .bind(low, high, limit)
    .run();
  const wordsDeleted = words.meta?.changes ?? 0;
  if (wordsDeleted >= limit) return { deleted: wordsDeleted };
  const places = await db
    .prepare(`DELETE FROM place WHERE rowid IN (SELECT rowid FROM place WHERE ${stale} LIMIT ?3)`)
    .bind(low, high, limit - wordsDeleted)
    .run();
  return { deleted: wordsDeleted + (places.meta?.changes ?? 0) };
}
