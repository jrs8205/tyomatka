import { CATS, type Cat, cellOf } from "./query";

/**
 * Aineistoputken tuontirajapinta: rivit kirjoitetaan välitauluun erissä ja maa vaihdetaan yhdellä `batch`-ajolla, joka
 * on D1:ssä atominen. Tunnus kulkee `Authorization: Bearer`-otsakkeessa ja vertailu on vakioaikainen.
 */
export const MAX_IMPORT_ROWS = 500;

export interface ImportRow {
  id: string;
  cat: Cat;
  lat: number;
  lon: number;
  name?: string;
  brand?: string;
  addr?: string;
  hgv: 0 | 1;
  tags: Record<string, string>;
}

export interface ImportBody {
  country: string;
  rows: ImportRow[];
}

export function authorized(request: Request, token: string): boolean {
  if (!token) return false;
  const header = request.headers.get("Authorization") ?? "";
  if (!header.startsWith("Bearer ")) return false;
  return constantTimeEquals(header.slice("Bearer ".length), token);
}

function constantTimeEquals(a: string, b: string): boolean {
  const x = new TextEncoder().encode(a);
  const y = new TextEncoder().encode(b);
  let diff = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) diff |= (x[i] ?? 0) ^ (y[i] ?? 0);
  return diff === 0;
}

const COUNTRY = /^[A-Z]{2}$/;
const OSM_ID = /^[nwr][0-9]+$/;
/** Tuotava kohde: OSM-kohde tai Fintrafficin latauspiste (`c-` + maa + osapuoli + `-` + sijainnin tunnus). */
const POI_ID = /^([nwr][0-9]+|c-[A-Z]{2}[A-Z0-9]{3}-[A-Za-z0-9._-]{1,64})$/;

export function validCountry(value: unknown): value is string {
  return typeof value === "string" && COUNTRY.test(value);
}

export function validateImport(body: unknown): ImportBody | { error: string } {
  if (!body || typeof body !== "object") return { error: "runko puuttuu" };
  const { country, rows } = body as { country?: unknown; rows?: unknown };
  if (!validCountry(country)) return { error: "country: kaksi isoa kirjainta" };
  if (!Array.isArray(rows)) return { error: "rows puuttuu" };
  if (rows.length > MAX_IMPORT_ROWS) return { error: `rows: enintään ${MAX_IMPORT_ROWS} riviä` };
  const out: ImportRow[] = [];
  for (const [i, raw] of rows.entries()) {
    const row = validateRow(raw);
    if (typeof row === "string") return { error: `rows[${i}]: ${row}` };
    out.push(row);
  }
  return { country, rows: out };
}

function validateRow(raw: unknown): ImportRow | string {
  if (!raw || typeof raw !== "object") return "ei olio";
  const r = raw as Record<string, unknown>;
  if (typeof r.id !== "string" || !POI_ID.test(r.id)) return "id";
  if (typeof r.cat !== "string" || !(CATS as readonly string[]).includes(r.cat)) return "cat";
  if (!isCoordinate(r.lat, 90) || !isCoordinate(r.lon, 180)) return "lat/lon";
  if (r.hgv !== 0 && r.hgv !== 1) return "hgv";
  if (!r.tags || typeof r.tags !== "object" || Array.isArray(r.tags)) return "tags";
  for (const field of ["name", "brand", "addr"] as const) {
    if (r[field] !== undefined && r[field] !== null && typeof r[field] !== "string") return field;
  }
  const tags: Record<string, string> = {};
  for (const [k, v] of Object.entries(r.tags as Record<string, unknown>)) if (typeof v === "string") tags[k] = v;
  return {
    id: r.id,
    cat: r.cat as Cat,
    lat: r.lat as number,
    lon: r.lon as number,
    name: optionalText(r.name),
    brand: optionalText(r.brand),
    addr: optionalText(r.addr),
    hgv: r.hgv,
    tags,
  };
}

function isCoordinate(value: unknown, limit: number): value is number {
  return typeof value === "number" && Number.isFinite(value) && Math.abs(value) <= limit;
}

function optionalText(value: unknown): string | undefined {
  return typeof value === "string" && value !== "" ? value : undefined;
}

const COLUMNS = ["id", "cat", "country", "lat", "lon", "cell", "name", "brand", "addr", "hgv", "tags", "updated"] as const;

/** Yksi lause erää kohti: rivit kulkevat JSON-taulukkona (yksi parametri), D1 sallii enintään sata parametria. */
export const IMPORT_SQL =
  `INSERT OR REPLACE INTO poi_staging (${COLUMNS.join(", ")}) SELECT ` +
  COLUMNS.map((c) => `json_extract(value, '$.${c}')`).join(", ") +
  " FROM json_each(?1)";

export function importParam(body: ImportBody, updated: string): string {
  return JSON.stringify(
    body.rows.map((r) => ({
      id: r.id,
      cat: r.cat,
      country: body.country,
      lat: r.lat,
      lon: r.lon,
      cell: cellOf(r.lat, r.lon),
      name: r.name ?? null,
      brand: r.brand ?? null,
      addr: r.addr ?? null,
      hgv: r.hgv,
      tags: JSON.stringify(r.tags),
      updated,
    })),
  );
}

/**
 * Osoitteiden täydennyksen välimuisti (`pipeline/fill_addr.py`): rivit kulkevat JSON-taulukkona kuten [IMPORT_SQL].
 * `addr` null on tallennettu "ei osoitetta"; `checked` on hakupäivä muodossa VVVV-KK-PP.
 */
export const MAX_ADDR_ROWS = 500;
const MAX_ADDR_LENGTH = 200;
const ADDR_DATE = /^\d{4}-\d{2}-\d{2}$/;

export interface AddrRow {
  id: string;
  lat: number;
  lon: number;
  addr: string | null;
  checked: string;
}

export const ADDR_LIST_SQL = "SELECT id, lat, lon, addr, checked FROM addr_fill ORDER BY id";
export const ADDR_PUT_SQL =
  "INSERT OR REPLACE INTO addr_fill (id, lat, lon, addr, checked) SELECT " +
  ["id", "lat", "lon", "addr", "checked"].map((c) => `json_extract(value, '$.${c}')`).join(", ") +
  " FROM json_each(?1)";

export function validateAddrRows(body: unknown): { rows: AddrRow[] } | { error: string } {
  const rows = (body as { rows?: unknown } | null)?.rows;
  if (!Array.isArray(rows)) return { error: "rows puuttuu" };
  const out: AddrRow[] = [];
  for (const [i, raw] of rows.entries()) {
    const row = validateAddrRow(raw);
    if (typeof row === "string") return { error: `rows[${i}]: ${row}` };
    out.push(row);
  }
  return { rows: out };
}

function validateAddrRow(raw: unknown): AddrRow | string {
  if (!raw || typeof raw !== "object") return "ei olio";
  const r = raw as Record<string, unknown>;
  if (typeof r.id !== "string" || !POI_ID.test(r.id)) return "id";
  if (!isCoordinate(r.lat, 90) || !isCoordinate(r.lon, 180)) return "lat/lon";
  if (r.addr !== null && (typeof r.addr !== "string" || r.addr === "" || r.addr.length > MAX_ADDR_LENGTH)) return "addr";
  if (!isAddrDate(r.checked)) return "checked";
  return { id: r.id, lat: r.lat, lon: r.lon, addr: r.addr as string | null, checked: r.checked };
}

function isAddrDate(value: unknown): value is string {
  if (typeof value !== "string" || !ADDR_DATE.test(value)) return false;
  const time = Date.parse(value);
  return !Number.isNaN(time) && new Date(time).toISOString().startsWith(value);
}

export function beginStatement(country: string): { sql: string; params: unknown[] } {
  return { sql: "DELETE FROM poi_staging WHERE country = ?", params: [country] };
}

/** Maan vaihto: vanhat rivit pois, välitaulun rivit tilalle (rajan kohde voi olla jo toisen maan rivinä), meta päivittyy. */
export function swapStatements(country: string, extractDate: string, rows: number, updated: string): Array<{ sql: string; params: unknown[] }> {
  return [
    { sql: "DELETE FROM poi WHERE country = ?", params: [country] },
    { sql: `INSERT OR REPLACE INTO poi (${COLUMNS.join(", ")}) SELECT ${COLUMNS.join(", ")} FROM poi_staging WHERE country = ?`, params: [country] },
    { sql: "DELETE FROM poi_staging WHERE country = ?", params: [country] },
    { sql: "INSERT OR REPLACE INTO meta (country, extractDate, rows, updated) VALUES (?, ?, ?, ?)", params: [country, extractDate, rows, updated] },
  ];
}

/** Vaihto vaatii pakotuksen, jos välitaulussa on alle tämän osuuden edellisestä rivimäärästä. */
export const SWAP_MIN_SHARE = 0.5;
export const STAGING_COUNT_SQL = "SELECT COUNT(*) AS n FROM poi_staging WHERE country = ?";
export const PREVIOUS_META_SQL = "SELECT extractDate, rows FROM meta WHERE country = ?";

export type SwapDecision = { kind: "swap"; rows: number } | { kind: "already" } | { kind: "conflict"; error: string };

/**
 * Suoja maan tyhjenemistä vastaan: tyhjä välitaulu ei koskaan vaihda maata (saman otteen uusinta on jo vaihdettu, jos
 * metassa on sama ote), ja jyrkkä pudotus vaatii pakotuksen. Metaan kirjataan välitaulun todellinen rivimäärä.
 */
export function swapDecision(
  staged: number,
  previous: { extractDate: string; rows: number } | null,
  extractDate: string,
  force: boolean,
): SwapDecision {
  if (staged === 0) {
    if (previous?.extractDate === extractDate) return { kind: "already" };
    return { kind: "conflict", error: "välitaulu on tyhjä: maata ei vaihdeta" };
  }
  if (!force && previous && staged < previous.rows * SWAP_MIN_SHARE) {
    return { kind: "conflict", error: `välitaulussa ${staged} riviä, edellisellä kerralla ${previous.rows}: vaihto vaatii pakotuksen` };
  }
  return { kind: "swap", rows: staged };
}

/**
 * Kertatuonnin faktat: vain kuljettajan päätöksiin tarvittavat avaimet, ei
 * yhteystietoja, ruokalistoja, tekstejä eikä raakatietoja. Liitetty rivi (`linked` 1) täydentää OSM-kohdetta samalla
 * tunnisteella; liittämätön (`linked` 0) on oma `k-`-asema. `/admin/facts/clear` poistaa koko lähteen.
 */
export const MAX_FACT_ROWS = 500;
export const OWN_STATION_ID = /^k-[a-z0-9]{2,12}-[A-Za-z0-9]{1,32}$/;

const FACT_FUELS = ["diesel", "hvo", "petrol95", "petrol98", "e85", "mpo", "biogas", "lpg", "cng", "lng"];
const isYes = (v: string) => v === "yes";
const factHours = (v: string) => /^[A-Za-z0-9:;,/ -]+$/.test(v);
const FACT_KEYS: Record<string, (v: string) => boolean> = {
  "k:date": (v) => /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(v),
  "k:status": (v) => v === "winding_down" || v === "closed",
  "k:hours:station": factHours,
  "k:hours:restaurant": factHours,
  "k:hours:lunch": factHours,
  "k:hours:breakfast": factHours,
  "k:hours:shop": factHours,
  "k:fuels": (v) => v.split(";").every((part) => FACT_FUELS.includes(part.split("=")[0]!)),
  "k:adblue": (v) => v === "pump" || v === "canister" || v === "yes",
  "k:hgv_lane": isYes,
  "k:truck_parking": isYes,
  "k:shower": isYes,
  "k:sauna": isYes,
  "k:rest_room": isYes,
  "k:toilets": isYes,
  "k:carwash": isYes,
  "k:shop": isYes,
  "k:charging": isYes,
  "k:cafe": isYes,
  "k:truck_length": (v) => v.split(";").every((part) => part === "14" || part === "25.25" || part === "34.5"),
  "k:restaurant": (v) => v !== "",
  "k:cards": (v) => v !== "",
  "k:url": (v) => v.startsWith("https://"),
};

export interface FactRow {
  id: string;
  cat: "fuel" | "gas";
  lat: number;
  lon: number;
  name?: string;
  brand?: string;
  addr?: string;
  linked: 0 | 1;
  facts: Record<string, string>;
}

export function validateFacts(body: unknown): { rows: FactRow[] } | { error: string } {
  const rows = (body as { rows?: unknown } | null)?.rows;
  if (!Array.isArray(rows)) return { error: "rows puuttuu" };
  if (rows.length > MAX_FACT_ROWS) return { error: `rows: enintään ${MAX_FACT_ROWS} riviä` };
  const out: FactRow[] = [];
  for (const [i, raw] of rows.entries()) {
    const row = validateFactRow(raw);
    if (typeof row === "string") return { error: `rows[${i}]: ${row}` };
    out.push(row);
  }
  return { rows: out };
}

function validateFactRow(raw: unknown): FactRow | string {
  if (!raw || typeof raw !== "object") return "ei olio";
  const r = raw as Record<string, unknown>;
  if (r.linked !== 0 && r.linked !== 1) return "linked";
  if (typeof r.id !== "string" || !(r.linked === 1 ? OSM_ID : OWN_STATION_ID).test(r.id)) return "id";
  if (r.cat !== "fuel" && r.cat !== "gas") return "cat";
  if (!isCoordinate(r.lat, 90) || !isCoordinate(r.lon, 180)) return "lat/lon";
  for (const field of ["name", "brand", "addr"] as const) {
    const value = r[field];
    if (value !== undefined && value !== null && (typeof value !== "string" || value.length > 200)) return field;
  }
  if (!r.facts || typeof r.facts !== "object" || Array.isArray(r.facts)) return "facts";
  const facts: Record<string, string> = {};
  for (const [key, value] of Object.entries(r.facts as Record<string, unknown>)) {
    const check = Object.hasOwn(FACT_KEYS, key) ? FACT_KEYS[key] : undefined;
    if (!check) return `facts: avain ${key} ei ole sallittu`;
    if (typeof value !== "string" || value.length > 200 || !check(value)) return `facts: ${key}`;
    facts[key] = value;
  }
  const row: FactRow = { id: r.id, cat: r.cat, lat: r.lat as number, lon: r.lon as number, linked: r.linked, facts };
  for (const field of ["name", "brand", "addr"] as const) {
    const value = optionalText(r[field]);
    if (value !== undefined) row[field] = value;
  }
  return row;
}

const FACT_COLUMNS = ["id", "cat", "lat", "lon", "cell", "name", "brand", "addr", "linked", "facts", "updated"] as const;

export const FACTS_SQL =
  `INSERT OR REPLACE INTO station_facts (${FACT_COLUMNS.join(", ")}) SELECT ` +
  FACT_COLUMNS.map((c) => `json_extract(value, '$.${c}')`).join(", ") +
  " FROM json_each(?1)";

export function factsParam(body: { rows: FactRow[] }, updated: string): string {
  return JSON.stringify(
    body.rows.map((r) => ({
      id: r.id,
      cat: r.cat,
      lat: r.lat,
      lon: r.lon,
      cell: cellOf(r.lat, r.lon),
      name: r.name ?? null,
      brand: r.brand ?? null,
      addr: r.addr ?? null,
      linked: r.linked,
      facts: JSON.stringify(r.facts),
      updated,
    })),
  );
}
