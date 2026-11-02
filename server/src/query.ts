/**
 * Ikkunakyselyn jäsennys ja SQL. Kohteet on indeksoitu 0,25°:n ruudukkoon (`cell`): ikkuna muuttuu leveyspiiririveiksi,
 * joista jokainen on yksi `BETWEEN`-väli. Sovellus pyöristää ikkunan samaan ruudukkoon (lähennettynä 0,05°:n
 * ruudukkoon), joten reunavälimuisti osuu.
 */
export const CATS = ["fuel", "charging", "gas", "rest"] as const;
export type Cat = (typeof CATS)[number];

export const CELL_DEG = 0.25;
/** Sarakkeita riviä kohti: 360 / 0,25 = 1 440 mahtuu alle. */
export const LON_CELLS = 2048;
export const MAX_SPAN_DEG = 2;
export const MAX_ITEMS = 300;

export interface PoiQuery {
  bbox: [number, number, number, number];
  cats: Cat[];
  hgvOnly: boolean;
}

export type ParseResult = { ok: true; query: PoiQuery } | { ok: false; error: string };

export function parseQuery(params: URLSearchParams): ParseResult {
  const raw = params.get("bbox");
  if (!raw) return { ok: false, error: "bbox puuttuu" };
  const parts = raw.split(",").map((s) => Number(s.trim()));
  if (parts.length !== 4 || parts.some((n) => !Number.isFinite(n))) return { ok: false, error: "bbox: neljä lukua minLon,minLat,maxLon,maxLat" };
  const [minLon, minLat, maxLon, maxLat] = parts as [number, number, number, number];
  if (minLon < -180 || maxLon > 180 || minLat < -90 || maxLat > 90) return { ok: false, error: "bbox: koordinaatit rajojen ulkopuolella" };
  if (!(minLon < maxLon && minLat < maxLat)) return { ok: false, error: "bbox: min pitää olla pienempi kuin max" };
  if (maxLon - minLon > MAX_SPAN_DEG || maxLat - minLat > MAX_SPAN_DEG) return { ok: false, error: `bbox: enintään ${MAX_SPAN_DEG}°×${MAX_SPAN_DEG}°` };

  const catsRaw = params.get("cats");
  const names = catsRaw && catsRaw.trim() !== "" ? catsRaw.split(",").map((s) => s.trim()).filter((s) => s !== "") : [...CATS];
  const cats: Cat[] = [];
  for (const name of names) {
    if (!(CATS as readonly string[]).includes(name)) return { ok: false, error: `cats: tuntematon luokka ${name}` };
    if (!cats.includes(name as Cat)) cats.push(name as Cat);
  }
  return { ok: true, query: { bbox: [minLon, minLat, maxLon, maxLat], cats, hgvOnly: params.get("hgv") === "1" } };
}

/**
 * Yhteishaun palojen enimmäismäärä. Tiheällä alueella jokainen pala voi olla katkaistu 300 kohteeseen, joten 50 palaa
 * pitää vastauksen ja Workerin muistin kohtuullisena (100 palaa latauspisteineen vei mitattuna noin 90 Mt).
 */
export const MAX_BATCH_PARTS = 50;
/** Pala on enintään yksi ruudukon solu: yhteishaku on rajassa yksi haku, joten 2°:n palat moninkertaistaisivat kuorman. */
export const MAX_PART_DEG = CELL_DEG;
const PART_KEYS = ["bbox", "cats"];

export type BatchResult = { ok: true; queries: PoiQuery[] } | { ok: false; error: string };

/**
 * Yhteishaun runko `{parts: [{bbox, cats}]}`: jokainen pala jäsennetään kuten ikkunakysely, ja palassa on täsmälleen
 * yksi laji. Yksikin virheellinen pala hylkää koko pyynnön (virheessä palan indeksi), joten vastaus ei ole osittainen.
 */
export function parseBatch(body: unknown): BatchResult {
  const parts = (body as { parts?: unknown } | null)?.parts;
  if (!Array.isArray(parts)) return { ok: false, error: "parts: lista puuttuu" };
  if (parts.length === 0 || parts.length > MAX_BATCH_PARTS) return { ok: false, error: `parts: 1–${MAX_BATCH_PARTS} palaa` };
  const queries: PoiQuery[] = [];
  for (const [index, part] of parts.entries()) {
    if (!part || typeof part !== "object" || Array.isArray(part)) return { ok: false, error: `parts[${index}]: pala ei ole olio` };
    const unknown = Object.keys(part).find((key) => !PART_KEYS.includes(key));
    if (unknown !== undefined) return { ok: false, error: `parts[${index}]: tuntematon kenttä ${unknown}` };
    const { bbox, cats } = part as { bbox?: unknown; cats?: unknown };
    if (typeof cats !== "string" || !(CATS as readonly string[]).includes(cats.trim())) {
      return { ok: false, error: `parts[${index}]: cats on yksi luokista ${CATS.join(", ")}` };
    }
    if (bbox !== undefined && typeof bbox !== "string") return { ok: false, error: `parts[${index}]: bbox on merkkijono minLon,minLat,maxLon,maxLat` };
    const params = new URLSearchParams({ cats });
    if (bbox !== undefined) params.set("bbox", bbox);
    const parsed = parseQuery(params);
    if (!parsed.ok) return { ok: false, error: `parts[${index}]: ${parsed.error}` };
    const [minLon, minLat, maxLon, maxLat] = parsed.query.bbox;
    // Pieni toleranssi: "24.75" ja "25.00" liukulukuina voivat erota 0,25:stä viimeisessä numerossa.
    if (maxLon - minLon > MAX_PART_DEG + 1e-9 || maxLat - minLat > MAX_PART_DEG + 1e-9) {
      return { ok: false, error: `parts[${index}]: bbox: pala enintään ${String(MAX_PART_DEG).replace(".", ",")}°×${String(MAX_PART_DEG).replace(".", ",")}°` };
    }
    queries.push(parsed.query);
  }
  return { ok: true, queries };
}

export function cellOf(lat: number, lon: number): number {
  const row = Math.floor((lat + 90) / CELL_DEG);
  const col = Math.floor((lon + 180) / CELL_DEG);
  return row * LON_CELLS + col;
}

/** Yksi [alku, loppu] jokaista ikkunan koskettamaa leveyspiiririviä kohti; reunarivit ja -sarakkeet ovat mukana. */
export function cellRanges(bbox: PoiQuery["bbox"]): Array<[number, number]> {
  const [minLon, minLat, maxLon, maxLat] = bbox;
  const firstRow = Math.floor((minLat + 90) / CELL_DEG);
  const lastRow = Math.floor((maxLat + 90) / CELL_DEG);
  const firstCol = Math.floor((minLon + 180) / CELL_DEG);
  const lastCol = Math.floor((maxLon + 180) / CELL_DEG);
  const ranges: Array<[number, number]> = [];
  for (let row = firstRow; row <= lastRow; row++) ranges.push([row * LON_CELLS + firstCol, row * LON_CELLS + lastCol]);
  return ranges;
}

export function buildSelect(q: PoiQuery): { sql: string; params: Array<string | number> } {
  const [minLon, minLat, maxLon, maxLat] = q.bbox;
  const ranges = cellRanges(q.bbox);
  const sql =
    "SELECT id, cat, lat, lon, name, brand, addr, hgv, tags FROM poi" +
    ` WHERE (cat IN (${q.cats.map(() => "?").join(",")})${crossCatClause(q.cats)})` +
    " AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?" +
    ` AND (${ranges.map(() => "(cell BETWEEN ? AND ?)").join(" OR ")})` +
    (q.hgvOnly ? " AND hgv = 1" : "") +
    // Raskas kalusto ensin, sitten lähimmät ikkunan keskeltä: tiheässä kaupungissa katkaisu jättää reunat pois eikä
    // sattumanvaraisia kohteita. Pituusasteen ero painotetaan cos²(leveys):llä (D1:ssä ei ole trigonometriaa).
    " ORDER BY hgv DESC, ((lat - ?) * (lat - ?) + (lon - ?) * (lon - ?) * ?) ASC, id ASC" +
    // Yksi ylimääräinen rivi kertoo, että ikkunassa on enemmän kuin MAX_ITEMS kohdetta.
    ` LIMIT ${MAX_ITEMS + 1}`;
  const centerLat = (minLat + maxLat) / 2;
  const centerLon = (minLon + maxLon) / 2;
  const cos = Math.cos((centerLat * Math.PI) / 180);
  return {
    sql,
    params: [...q.cats, minLat, maxLat, minLon, maxLon, ...ranges.flat(), centerLat, centerLat, centerLon, centerLon, cos * cos],
  };
}

/** Enintään [MAX_ITEMS] riviä ja tieto siitä, jäikö ikkunasta kohteita pois. */
export function limitRows<T>(rows: T[]): { rows: T[]; truncated: boolean } {
  return rows.length > MAX_ITEMS ? { rows: rows.slice(0, MAX_ITEMS), truncated: true } : { rows, truncated: false };
}

export interface PoiRow {
  id: string;
  cat: Cat;
  lat: number;
  lon: number;
  name: string | null;
  brand: string | null;
  addr: string | null;
  hgv: number;
  tags: string;
}

export interface PoiItemOut {
  id: string;
  cat: Cat;
  lat: number;
  lon: number;
  name?: string;
  brand?: string;
  addr?: string;
  hgv: boolean;
  tags: Record<string, string>;
}

export function toItems(rows: PoiRow[]): PoiItemOut[] {
  return rows.map((r) => {
    const item: PoiItemOut = { id: r.id, cat: r.cat, lat: r.lat, lon: r.lon, hgv: r.hgv === 1, tags: parseTags(r.tags) };
    if (r.name) item.name = r.name;
    if (r.brand) item.brand = r.brand;
    if (r.addr) item.addr = r.addr;
    return item;
  });
}

function parseTags(text: string): Record<string, string> {
  try {
    const value: unknown = JSON.parse(text);
    if (value && typeof value === "object" && !Array.isArray(value)) {
      const out: Record<string, string> = {};
      for (const [k, v] of Object.entries(value as Record<string, unknown>)) if (typeof v === "string") out[k] = v;
      return out;
    }
  } catch {
    // rikkinäinen rivi: kohde näytetään ilman tageja
  }
  return {};
}

const GAS_FUELS = ["biogas", "lpg", "cng", "lng"];
const LIQUID_FUELS = ["diesel", "hvo", "petrol95", "petrol98", "e85", "mpo"];

/** Kohteet, joiden uusin polttoaineilmoitus sisältää jonkin lajeista (arvo on `;`-eroteltu lista). */
function reportedFuels(types: string[]): string {
  // Sama yksittäinen uusin rivi kuin LATEST_SQL:ssä (created, sitten id), jotta luokka ja kortti eivät ole ristiriidassa.
  const latest =
    "r.id = (SELECT r2.id FROM report r2 WHERE r2.poi = r.poi AND r2.field = 'fuels' ORDER BY r2.created DESC, r2.id DESC LIMIT 1)";
  const any = types.map((t) => `';' || r.value || ';' LIKE '%;${t};%'`).join(" OR ");
  return `SELECT r.poi FROM report r WHERE r.field = 'fuels' AND ${latest} AND (${any})`;
}

/**
 * Ilmoitukset vaikuttavat luokkiin kuten vastaava OSM-tieto: ilmoitettu biokaasu tuo huoltoaseman kaasuasemiin ja
 * ilmoitettu diesel kaasuaseman huoltoasemiin. Lisäehto vain, kun pyydetään toinen luokista; ei parametreja.
 */
export function crossCatClause(cats: readonly Cat[]): string {
  const fuel = cats.includes("fuel");
  const gas = cats.includes("gas");
  if (gas && !fuel) return ` OR (cat = 'fuel' AND id IN (${reportedFuels(GAS_FUELS)}))`;
  if (fuel && !gas) return ` OR (cat = 'gas' AND id IN (${reportedFuels(LIQUID_FUELS)}))`;
  return "";
}

/** Toisen luokan kohde (ilmoituksen tuoma) näytetään pyydetyssä luokassa. */
export function inRequestedCats(items: PoiItemOut[], cats: readonly Cat[]): PoiItemOut[] {
  return items.map((item) => {
    if (cats.includes(item.cat)) return item;
    const other: Cat = item.cat === "fuel" ? "gas" : "fuel";
    return cats.includes(other) ? { ...item, cat: other } : item;
  });
}

export interface FactRowDb {
  id: string;
  cat: Cat;
  lat: number;
  lon: number;
  name: string | null;
  brand: string | null;
  addr: string | null;
  linked: number;
  facts: string;
}

/** Faktat samasta ikkunasta: liitetyt luokasta riippumatta, omat asemat pyydetyissä luokissa. */
export function buildFactsSelect(q: PoiQuery): { sql: string; params: Array<string | number> } {
  const [minLon, minLat, maxLon, maxLat] = q.bbox;
  const ranges = cellRanges(q.bbox);
  const sql =
    "SELECT id, cat, lat, lon, name, brand, addr, linked, facts FROM station_facts" +
    ` WHERE (linked = 1 OR cat IN (${q.cats.map(() => "?").join(",")})${crossCatClause(q.cats)})` +
    " AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?" +
    ` AND (${ranges.map(() => "(cell BETWEEN ? AND ?)").join(" OR ")})`;
  return { sql, params: [...q.cats, minLat, maxLat, minLon, maxLon, ...ranges.flat()] };
}

/**
 * Liitetyt faktat kohteen tageihin (`k:`-avaimet), omat asemat uusiksi kohteiksi. Kadonneen kohteen faktat ja
 * rikkinäiset rivit ohitetaan. Oman aseman raskas kalusto tulee rekkapisteestä tai rekkapysäköinnistä.
 */
export function mergeFacts(items: PoiItemOut[], facts: FactRowDb[], q: Pick<PoiQuery, "cats" | "hgvOnly">): PoiItemOut[] {
  const linked = new Map<string, Record<string, string>>();
  const own: PoiItemOut[] = [];
  for (const row of facts) {
    const tags = parseFacts(row.facts);
    if (!tags) continue;
    if (row.linked === 1) {
      linked.set(row.id, tags);
    } else if (row.linked === 0) {
      const hgv = tags["k:hgv_lane"] === "yes" || tags["k:truck_parking"] === "yes";
      if (q.hgvOnly && !hgv) continue;
      const item: PoiItemOut = { id: row.id, cat: row.cat, lat: row.lat, lon: row.lon, hgv, tags };
      if (row.name) item.name = row.name;
      if (row.brand) item.brand = row.brand;
      if (row.addr) item.addr = row.addr;
      own.push(item);
    }
  }
  const merged = items.map((item) => {
    const tags = linked.get(item.id);
    return tags ? { ...item, tags: { ...item.tags, ...tags } } : item;
  });
  return inRequestedCats([...merged, ...own], q.cats);
}

function parseFacts(text: unknown): Record<string, string> | null {
  if (typeof text !== "string") return null;
  try {
    const value: unknown = JSON.parse(text);
    if (!value || typeof value !== "object" || Array.isArray(value)) return null;
    const out: Record<string, string> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) if (k.startsWith("k:") && typeof v === "string") out[k] = v;
    return out;
  } catch {
    return null;
  }
}

/** Sama järjestys kuin SQL:ssä: raskas kalusto ensin, sitten etäisyys ikkunan keskeltä, sitten tunniste. */
export function orderItems(items: PoiItemOut[], bbox: PoiQuery["bbox"]): PoiItemOut[] {
  const [minLon, minLat, maxLon, maxLat] = bbox;
  const centerLat = (minLat + maxLat) / 2;
  const centerLon = (minLon + maxLon) / 2;
  const cos = Math.cos((centerLat * Math.PI) / 180);
  const distance = (i: PoiItemOut) => (i.lat - centerLat) ** 2 + (i.lon - centerLon) ** 2 * cos * cos;
  return [...items].sort((a, b) => Number(b.hgv) - Number(a.hgv) || distance(a) - distance(b) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
}
