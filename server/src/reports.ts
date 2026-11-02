import { LON_CELLS, type PoiQuery, cellRanges } from "./query";

/**
 * Kuljettajien ilmoitukset: yksi kenttä ja arvo kohdetta kohti, ei vapaata tekstiä. Ilmoittaja on sovelluksen
 * satunnainen laitetunniste (`X-Device`, UUID v4); osoitetta ei tallenneta. Asiakkaan `rid` tekee uusinnasta
 * kaksoiskappaleen eikä uutta riviä.
 */
export const DAILY_LIMIT = 50;
export const MAX_WINDOW_ITEMS = 3000;

const YES_NO = /^(yes|no)$/;
const DAY = "(Mo|Tu|We|Th|Fr|Sa|Su)";
const TIME = "(([01][0-9]|2[0-3]):[0-5][0-9]|24:00)";
const HOURS_ENTRY = `${DAY}(-${DAY})? ${TIME}-${TIME}`;
const HOURS = new RegExp(`^(24/7|${HOURS_ENTRY}(; ${HOURS_ENTRY})*)$`);
const FUEL_TYPES = ["diesel", "hvo", "petrol95", "petrol98", "e85", "mpo", "biogas", "lpg", "cng", "lng"];
const LIMIT = /^[0-9]{1,2}(\.[0-9]{1,2})?$/;
const PRICE = /^[0-9]{1,4}(\.[0-9]{1,3})? [A-Z]{3}$/;

const yesNo = (v: string) => YES_NO.test(v);
const hours = (v: string) => v.length <= 200 && HOURS.test(v);
const limit = (v: string) => LIMIT.test(v) && Number(v) >= 0.5 && Number(v) <= 99;
const price = (v: string) => PRICE.test(v) && Number(v.split(" ")[0]) > 0;

function fuels(v: string): boolean {
  if (v === "none") return true;
  const parts = v.split(";");
  return parts.every((p) => FUEL_TYPES.includes(p)) && new Set(parts).size === parts.length;
}

export const REPORT_FIELDS: Record<string, (value: string) => boolean> = {
  closed: yesNo,
  "hours:station": hours,
  "hours:restaurant": hours,
  "hours:lunch": hours,
  "hours:shop": hours,
  fuels,
  adblue: (v) => v === "pump" || v === "canister" || v === "no",
  charging: yesNo,
  shop: yesNo,
  restaurant: yesNo,
  lunch: yesNo,
  truck_parking: yesNo,
  shower: yesNo,
  toilets: yesNo,
  sauna: yesNo,
  overnight: yesNo,
  truck_length: (v) => v === "14" || v === "25.25" || v === "34.5",
  maxheight: limit,
  maxweight: limit,
  // Hintailmoitukset poistuivat sovelluksesta 31.10.2026; vanhojen versioiden jonot saavat vielä lähteä.
  "price:diesel": price,
  "price:petrol": price,
  "price:gas": price,
  "price:electric": price,
  "price:adblue": price,
};

/** OSM-kohde, AFIR-latauspiste (`c-` + maa + osapuoli + sijainti) tai kertatuonnin oma asema (`k-` + ketju + tunnus). */
export const REPORT_POI = /^([nwr][0-9]+|c-[A-Z]{2}[A-Z0-9]{3}-[A-Za-z0-9._-]{1,64}|k-[a-z0-9]{2,12}-[A-Za-z0-9]{1,32})$/;
export const DEVICE_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const RID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export interface ValidReport {
  poi: string;
  field: string;
  value: string;
  rid: string;
  /** Ilmoituksen tekohetki (offline-jonossa ollut ilmoitus tai laitteen vanha korjaus); puuttuu = palvelimen hetki. */
  at?: string;
}

const MAX_BACKDATE_MS = 365 * 24 * 3600 * 1000;
/** Puhelimen kello saa edistää tämän verran; hetki rajataan palvelimen nykyhetkeen. */
const CLOCK_SKEW_MS = 5 * 60 * 1000;
const INSTANT = /^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,3})?Z$/;

/** UTC-hetki ISO-muodossa, enintään vuoden vanha eikä kellon heittoa kauempana tulevaisuudessa; muuten null. */
export function reportInstant(value: unknown, now: Date): string | null {
  if (typeof value !== "string" || !INSTANT.test(value)) return null;
  const time = Date.parse(value);
  if (Number.isNaN(time) || new Date(time).toISOString().slice(0, 19) !== value.slice(0, 19)) return null;
  if (time > now.getTime() + CLOCK_SKEW_MS || time < now.getTime() - MAX_BACKDATE_MS) return null;
  return new Date(Math.min(time, now.getTime())).toISOString();
}

export function validateReport(body: unknown, device: string | null, now: Date = new Date()): ValidReport | { error: string } {
  if (device === null || !DEVICE_ID.test(device)) return { error: "X-Device: UUID v4" };
  if (!body || typeof body !== "object") return { error: "runko puuttuu" };
  const { poi, field, value, rid, at } = body as Record<string, unknown>;
  if (typeof poi !== "string" || !REPORT_POI.test(poi)) return { error: "poi" };
  if (typeof field !== "string" || !Object.hasOwn(REPORT_FIELDS, field)) return { error: "field" };
  if (typeof value !== "string" || value.length > 200 || !REPORT_FIELDS[field]!(value)) return { error: "value" };
  if (typeof rid !== "string" || !RID.test(rid)) return { error: "rid" };
  if (at === undefined) return { poi, field, value, rid: rid.toLowerCase() };
  const instant = reportInstant(at, now);
  return instant === null ? { error: "at" } : { poi, field, value, rid: rid.toLowerCase(), at: instant };
}

export const RID_SQL = "SELECT created FROM report WHERE rid = ?";
/** Kohde on OSM- tai AFIR-rivi (`poi`) tai kertatuonnin oma asema (`station_facts`). */
export const CELL_SQL = "SELECT cell FROM poi WHERE id = ?1 UNION ALL SELECT cell FROM station_facts WHERE id = ?1 LIMIT 1";
/** Raja lasketaan vastaanottohetkestä: aiemmaksi päivätty ilmoitus ei kierrä sitä. */
export const DEVICE_COUNT_SQL = "SELECT COUNT(*) AS n FROM report WHERE device = ? AND received > ?";
/** Oman laitteen ilmoitusten poisto: tunnus on satunnainen, joten vain laite tietää sen. */
export const FORGET_SQL = "DELETE FROM report WHERE device = ?";
/**
 * Poistettu tunnus ei enää kelpaa ilmoituksiin: ennen poistoa alkanut lähetys voi olla palvelimella yhä kesken, ja sen
 * jälkeen tallentunutta riviä ei tavoittaisi mikään, koska sovellus on jo vaihtanut tunnuksensa. Merkintä kirjoitetaan
 * samassa erässä poiston kanssa ja tarkistetaan samassa lauseessa lisäyksen kanssa ([INSERT_SQL]).
 */
export const FORGOTTEN_PUT_SQL = "INSERT INTO forgotten_device (device, at) VALUES (?, ?) ON CONFLICT(device) DO UPDATE SET at = excluded.at";
export const FORGOTTEN_SQL = "SELECT 1 AS n FROM forgotten_device WHERE device = ?";
export const FORGOTTEN_PRUNE_SQL = "DELETE FROM forgotten_device WHERE at < ?";
/**
 * Siivous ajetaan kerran vuorokaudessa, joten merkintä säilyy enintään 6 vuorokautta ja yhden väliin jääneen ajon jälkeen
 * enintään 7 (seloste). Merkinnän pitää kestää vain kesken olevan pyynnön yli, joten lyhyt aika riittää.
 */
export const FORGOTTEN_KEEP_DAYS = 5;

export function forgottenBefore(now: Date): string {
  return new Date(now.getTime() - FORGOTTEN_KEEP_DAYS * 24 * 3600 * 1000).toISOString();
}

/** Korvaa vanhan ilmoituksen laitetunnuksen; ei kelpaa `DEVICE_ID`:ksi, joten sillä ei voi lähettää eikä poistaa. */
export const EXPIRED_DEVICE = "vanhentunut";
export const EXPIRE_SQL = "UPDATE report SET device = ? WHERE received < ? AND device <> ?";
const DEVICE_KEEP_MS = 365 * 24 * 3600 * 1000;

/** Laitetunnus poistuu 12 kuukauden jälkeen, jolloin ilmoitusta ei enää voi yhdistää laitteeseen. */
export function expireBefore(now: Date): string {
  return new Date(now.getTime() - DEVICE_KEEP_MS).toISOString();
}
export const INSERT_SQL =
  "INSERT INTO report (rid, poi, field, value, device, created, received, cell) SELECT ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8 " +
  "WHERE NOT EXISTS (SELECT 1 FROM forgotten_device WHERE device = ?5) ON CONFLICT(rid) DO NOTHING";

/**
 * Uusin ilmoitus kohdetta ja kenttää kohti ikkunan soluista. Solut rajataan rivien välillä ja sarakkeen jakojäännöksellä,
 * jolloin lause on aina sama (ikkunan rivimäärä ei muuta parametrien määrää).
 */
export const LATEST_SQL =
  "SELECT poi, field, value, date, at FROM (" +
  "SELECT poi, field, value, substr(created, 1, 10) AS date, created AS at, " +
  "ROW_NUMBER() OVER (PARTITION BY poi, field ORDER BY created DESC, id DESC) AS n " +
  `FROM report WHERE cell BETWEEN ? AND ? AND (cell % ${LON_CELLS}) BETWEEN ? AND ?` +
  `) WHERE n = 1 LIMIT ${MAX_WINDOW_ITEMS}`;

export function latestParams(bbox: PoiQuery["bbox"]): [number, number, number, number] {
  const ranges = cellRanges(bbox);
  const first = ranges[0]!;
  const last = ranges[ranges.length - 1]!;
  return [first[0], last[1], first[0] % LON_CELLS, first[1] % LON_CELLS];
}
