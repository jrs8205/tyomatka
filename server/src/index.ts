import {
  ADDR_LIST_SQL,
  ADDR_PUT_SQL,
  FACTS_SQL,
  IMPORT_SQL,
  MAX_ADDR_ROWS,
  MAX_FACT_ROWS,
  MAX_IMPORT_ROWS,
  PREVIOUS_META_SQL,
  STAGING_COUNT_SQL,
  authorized,
  beginStatement,
  factsParam,
  importParam,
  swapDecision,
  swapStatements,
  validCountry,
  validateAddrRows,
  validateFacts,
  validateImport,
} from "./admin";
import type { Env } from "./env";
import { fetchUpstream, upstreamUrl } from "./geocode";
import { GLOBAL_KEY, guard, limiterKey } from "./guard";
import { privacyPage } from "./privacy";
import {
  MAX_PLACE_IMPORT_ROWS,
  PLACES_SOURCE,
  PlaceConflictError,
  parsePlacesQuery,
  placeItem,
  placesBegin,
  placesImport,
  placesPrune,
  placesStatus,
  placesSwap,
  searchPlaces,
  validatePlaceImport,
} from "./places";
import {
  type FactRowDb,
  type PoiQuery,
  type PoiRow,
  buildFactsSelect,
  buildSelect,
  limitRows,
  mergeFacts,
  orderItems,
  parseBatch,
  parseQuery,
  toItems,
} from "./query";
import {
  CELL_SQL,
  DAILY_LIMIT,
  EXPIRE_SQL,
  EXPIRED_DEVICE,
  expireBefore,
  FORGET_SQL,
  FORGOTTEN_PRUNE_SQL,
  FORGOTTEN_PUT_SQL,
  FORGOTTEN_SQL,
  forgottenBefore,
  DEVICE_COUNT_SQL,
  DEVICE_ID,
  INSERT_SQL,
  LATEST_SQL,
  RID_SQL,
  latestParams,
  validateReport,
} from "./reports";

const SOURCE =
  "© OpenStreetMap contributors (ODbL); Fintraffic / digitraffic.fi (CC BY 4.0); " +
  "Osoitteiden täydennys © Digitransit 2026 (CC BY 4.0; OpenStreetMap-pohjaiset osoitteet ODbL)";
const LICENSE_LINK = '<https://opendatacommons.org/licenses/odbl/1-0/>; rel="license"';
const GEOCODE_PREFIX = "/v1/geocode/";

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/privacy") return privacyPage(url, request);
    if (request.method === "GET" && url.pathname === "/v1/places") {
      // Haku kirjoitettaessa: oma osoiteraja, jottei kirjoittaminen kuluta karttakohteiden rajaa.
      const verdict = await guard(env, request.headers.get("CF-Connecting-IP") ?? "unknown", env.RATE_SEARCH);
      if (!verdict.allowed) {
        return json({ error: verdict.reason }, verdict.status, { "Retry-After": verdict.status === 503 ? "3600" : "60" });
      }
      return places(env, url);
    }
    if (request.method === "GET" && url.pathname.startsWith(GEOCODE_PREFIX)) return geocode(request, env, url);
    const isPublic =
      (request.method === "GET" && (url.pathname === "/v1/poi" || url.pathname === "/v1/meta" || url.pathname === "/v1/reports")) ||
      (request.method === "POST" && (url.pathname === "/v1/reports" || url.pathname === "/v1/reports/forget" || url.pathname === "/v1/poi/batch"));
    if (isPublic) {
      // Osoite vain rajoittimen avaimeksi; pyynnön sisältöä ei lokiteta. Yhteishaku on rajassa yksi haku.
      const verdict = await guard(env, request.headers.get("CF-Connecting-IP") ?? "unknown");
      if (!verdict.allowed) {
        return json({ error: verdict.reason }, verdict.status, { "Retry-After": verdict.status === 503 ? "3600" : "60" });
      }
      if (url.pathname === "/v1/poi/batch") return poiBatch(request, env);
      if (url.pathname === "/v1/reports/forget") return forgetReports(request, env);
      if (url.pathname === "/v1/reports") return request.method === "POST" ? sendReport(request, env) : reports(env, url);
      return url.pathname === "/v1/poi" ? poi(env, url) : meta(env);
    }
    if (request.method === "POST" && url.pathname.startsWith("/admin/")) return admin(request, env, url.pathname.slice("/admin/".length));
    return json({ error: "not found" }, 404);
  },

  async scheduled(controller: ScheduledController, env: Env): Promise<void> {
    const now = new Date(controller.scheduledTime);
    await env.DB.batch([
      env.DB.prepare(EXPIRE_SQL).bind(EXPIRED_DEVICE, expireBefore(now), EXPIRED_DEVICE),
      env.DB.prepare(FORGOTTEN_PRUNE_SQL).bind(forgottenBefore(now)),
    ]);
  },
};

/**
 * Käyttäjän oikeus poistaa omat ilmoituksensa: tunnus todistaa laitteen, ja rivit poistuvat kokonaan. Tunnus mitätöidään
 * samassa transaktiossa, joten kesken ollut lähetys joko poistuu tässä tai hylätään lisäyksessä.
 */
async function forgetReports(request: Request, env: Env): Promise<Response> {
  const device = request.headers.get("X-Device");
  if (device === null || !DEVICE_ID.test(device)) return json({ error: "X-Device: UUID v4" }, 400);
  const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
  if (!(await env.RATE_REPORT.limit({ key: limiterKey(ip) })).success) return json({ error: "ip" }, 429, { "Retry-After": "60" });
  const id = device.toLowerCase();
  const [, deleted] = await env.DB.batch([env.DB.prepare(FORGOTTEN_PUT_SQL).bind(id, new Date().toISOString()), env.DB.prepare(FORGET_SQL).bind(id)]);
  return json({ deleted: deleted?.meta?.changes ?? 0 });
}

/**
 * Haku kirjoitettaessa: yksi näppäily voi tehdä useamman haun, joten osoiteraja on oma. `RATE_GEOCODE` suojaa Digitransitia
 * sijaintia kohti eikä haku kuluta karttakohteiden yhteistä rajaa.
 */
async function geocode(request: Request, env: Env, url: URL): Promise<Response> {
  const verdict = await guard(env, request.headers.get("CF-Connecting-IP") ?? "unknown", env.RATE_GEOCODE_IP, null);
  if (!verdict.allowed) return json({ error: verdict.reason }, verdict.status);
  const target = upstreamUrl(url.pathname.slice(GEOCODE_PREFIX.length), url.searchParams);
  if (target === null) return json({ error: "virheellinen haku" }, 400);
  if (!env.DIGITRANSIT_KEY) return json({ error: "ei käytössä" }, 503);
  if (!(await env.RATE_GEOCODE.limit({ key: GLOBAL_KEY })).success) return json({ error: "global" }, 429);
  const body = await fetchUpstream(target, env.DIGITRANSIT_KEY);
  if (body === null) return json({ error: "osoitehaku ei vastaa" }, 503);
  return new Response(body, { headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" } });
}

async function poi(env: Env, url: URL): Promise<Response> {
  const parsed = parseQuery(url.searchParams);
  if (!parsed.ok) return json({ error: parsed.error }, 400);
  const q = parsed.query;
  const [select, factSelect] = poiStatements(env, q);
  const { results } = await select.all<PoiRow>();
  const facts = await factSelect.all<FactRowDb>();
  return json(poiPart(q, results ?? [], facts.results ?? [], new Date().toISOString()), 200, {
    "Cache-Control": "public, max-age=86400",
    Link: LICENSE_LINK,
  });
}

/**
 * Monta ikkunaa yhdellä pyynnöllä (`{parts: [{bbox, cats}]}`): jokainen osa on sama kuin vastaava `/v1/poi`, ja kaikki
 * kyselyt lähtevät kannalle yhtenä eränä. POST, joten ei jaettua välimuistia.
 */
async function poiBatch(request: Request, env: Env): Promise<Response> {
  const text = await readCapped(request, MAX_BATCH_BODY);
  if (text === null) return json({ error: `runko enintään ${MAX_BATCH_BODY} tavua` }, 413);
  let body: unknown;
  try {
    body = JSON.parse(text);
  } catch {
    return json({ error: "runko ei ole JSONia" }, 400);
  }
  const parsed = parseBatch(body);
  if (!parsed.ok) return json({ error: parsed.error }, 400);
  const statements = parsed.queries.flatMap((q) => poiStatements(env, q));
  const results = await env.DB.batch(statements);
  // Puuttuva tulos ei saa näkyä tyhjänä osana, jonka sovellus tallentaisi vuorokaudeksi.
  if (results.length !== statements.length) throw new Error(`erän tuloksia ${results.length}, lauseita ${statements.length}`);
  const generatedAt = new Date().toISOString();
  const parts = parsed.queries.map((q, i) =>
    poiPart(q, (results[2 * i]?.results ?? []) as PoiRow[], (results[2 * i + 1]?.results ?? []) as FactRowDb[], generatedAt),
  );
  return json({ parts }, 200, { Link: LICENSE_LINK });
}

/** 50 palaa vie noin 3,5 kt; katto estää suuren rungon jäsennyksen ennen tarkistusta. */
const MAX_BATCH_BODY = 16_384;

/** Runko tekstinä, tai null, kun se on yli [limit] tavua (luku lopetetaan heti rajan ylityttyä). */
async function readCapped(request: Request, limit: number): Promise<string | null> {
  const declared = Number(request.headers.get("Content-Length") ?? "0");
  if (declared > limit) return null;
  if (!request.body) return "";
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > limit) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(bytes);
}

/** Ikkunan kaksi kyselyä: kohteet ja kertatuonnin faktat. */
function poiStatements(env: Env, q: PoiQuery): [D1PreparedStatement, D1PreparedStatement] {
  const select = buildSelect(q);
  const factSelect = buildFactsSelect(q);
  return [env.DB.prepare(select.sql).bind(...select.params), env.DB.prepare(factSelect.sql).bind(...factSelect.params)];
}

/** Ikkunan vastaus. Katkaisu koskee yhdistettyä listaa: kohdekysely palauttaa enintään yhden ylimääräisen rivin, omat asemat lisätään. */
function poiPart(q: PoiQuery, rows: PoiRow[], facts: FactRowDb[], generatedAt: string) {
  const merged = orderItems(mergeFacts(toItems(rows), facts, q), q.bbox);
  const { rows: items, truncated } = limitRows(merged);
  return { generatedAt, source: SOURCE, items, truncated };
}

/** Ilmoitukset eivät kulje välimuistin kautta: toinen kuljettaja näkee ne seuraavalla haulla. */
async function reports(env: Env, url: URL): Promise<Response> {
  const parsed = parseQuery(url.searchParams);
  if (!parsed.ok) return json({ error: parsed.error }, 400);
  const { results } = await env.DB.prepare(LATEST_SQL).bind(...latestParams(parsed.query.bbox)).all();
  return json({ items: results ?? [] });
}

async function sendReport(request: Request, env: Env): Promise<Response> {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return json({ error: "runko ei ole JSONia" }, 400);
  }
  const report = validateReport(body, request.headers.get("X-Device"));
  if ("error" in report) return json({ error: report.error }, 400);
  // Laitetunnisteen voi vaihtaa, joten ilmoituksia rajoitetaan myös osoitetta kohti (osoitetta ei tallenneta).
  const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
  if (!(await env.RATE_REPORT.limit({ key: limiterKey(ip) })).success) return json({ error: "ip" }, 429, { "Retry-After": "60" });
  const device = request.headers.get("X-Device")!.toLowerCase();
  // Uusinta (esim. aikakatkaisun jälkeen) kuitataan ennen rajaa, jotta jo tallennettu ilmoitus ei saa 429:ää.
  const previous = await env.DB.prepare(RID_SQL).bind(report.rid).first<{ created: string }>();
  if (previous) return json({ date: previous.created.slice(0, 10), at: previous.created, duplicate: true });
  const target = await env.DB.prepare(CELL_SQL).bind(report.poi).first<{ cell: number }>();
  if (!target) return json({ error: "tuntematon kohde" }, 404);
  const now = new Date();
  const received = now.toISOString();
  const since = new Date(now.getTime() - 24 * 3600 * 1000).toISOString();
  const count = await env.DB.prepare(DEVICE_COUNT_SQL).bind(device, since).first<{ n: number }>();
  if ((count?.n ?? 0) >= DAILY_LIMIT) return json({ error: "vuorokauden raja" }, 429, { "Retry-After": "3600" });
  // Tekohetki ratkaisee järjestyksen (uusin voittaa), vastaanottohetki rajan.
  const created = report.at ?? received;
  // Samassa transaktiossa: siivous ei voi poistaa merkintää lisäyksen ja tarkistuksen välissä. Merkintä tarkoittaa, että lisäys
  // torjuttiin, ja sovellus vaihtaa tunnuksensa. Muuten rivi on tallessa, myös kun sama tunniste ehti toisesta pyynnöstä.
  const [, forgotten] = await env.DB.batch([
    env.DB.prepare(INSERT_SQL).bind(report.rid, report.poi, report.field, report.value, device, created, received, target.cell),
    env.DB.prepare(FORGOTTEN_SQL).bind(device),
  ]);
  if (forgotten!.results.length > 0) return json({ error: "tunnus poistettu" }, 410);
  return json({ date: created.slice(0, 10), at: created }, 201);
}

/** Yrityshaku (`src/places.ts`): sama hakusana antaa saman vastauksen, joten lyhyt välimuisti sallitaan. */
async function places(env: Env, url: URL): Promise<Response> {
  const parsed = parsePlacesQuery(url.searchParams);
  if (!parsed.ok) return json({ error: parsed.error }, 400);
  const found = await searchPlaces(env.DB, parsed.query, parsed.near);
  return json({ source: PLACES_SOURCE, items: found.map(placeItem) }, 200, { "Cache-Control": "public, max-age=3600" });
}

async function meta(env: Env): Promise<Response> {
  const { results } = await env.DB.prepare("SELECT country, extractDate, rows, updated FROM meta ORDER BY country").all();
  return json(results ?? [], 200, { "Cache-Control": "public, max-age=3600" });
}

async function admin(request: Request, env: Env, action: string): Promise<Response> {
  if (!authorized(request, env.ADMIN_TOKEN)) return json({ error: "unauthorized" }, 401);
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return json({ error: "runko ei ole JSONia" }, 400);
  }
  const now = new Date().toISOString();
  switch (action) {
    case "facts": {
      const rows = (body as { rows?: unknown } | null)?.rows;
      if (Array.isArray(rows) && rows.length > MAX_FACT_ROWS) return json({ error: `enintään ${MAX_FACT_ROWS} riviä` }, 413);
      const valid = validateFacts(body);
      if ("error" in valid) return json({ error: valid.error }, 400);
      await env.DB.prepare(FACTS_SQL).bind(factsParam(valid, now)).run();
      return json({ inserted: valid.rows.length });
    }
    case "facts/clear": {
      // Koko kertatuonnin poisto yhdellä pyynnöllä (määrittely: lähteen voi poistaa, jos sitä pyydetään).
      await env.DB.prepare("DELETE FROM station_facts").run();
      return json({ ok: true });
    }
    case "addr/list": {
      const { results } = await env.DB.prepare(ADDR_LIST_SQL).all();
      return json({ rows: results ?? [] });
    }
    case "addr/put": {
      const rows = (body as { rows?: unknown } | null)?.rows;
      if (Array.isArray(rows) && rows.length > MAX_ADDR_ROWS) return json({ error: `enintään ${MAX_ADDR_ROWS} riviä` }, 413);
      const valid = validateAddrRows(body);
      if ("error" in valid) return json({ error: valid.error }, 400);
      await env.DB.prepare(ADDR_PUT_SQL).bind(JSON.stringify(valid.rows)).run();
      return json({ inserted: valid.rows.length });
    }
    case "reports/delete": {
      const b = body as { id?: unknown; device?: unknown } | null;
      if (typeof b?.id === "number" && Number.isSafeInteger(b.id) && b.id > 0) {
        const result = await env.DB.prepare("DELETE FROM report WHERE id = ?").bind(b.id).run();
        return json({ deleted: result.meta?.changes ?? 0 });
      }
      if (typeof b?.device === "string" && DEVICE_ID.test(b.device)) {
        const result = await env.DB.prepare("DELETE FROM report WHERE device = ?").bind(b.device.toLowerCase()).run();
        return json({ deleted: result.meta?.changes ?? 0 });
      }
      const range = body as { since?: unknown; until?: unknown } | null;
      const since = typeof range?.since === "string" ? Date.parse(range.since) : Number.NaN;
      const until = typeof range?.until === "string" ? Date.parse(range.until) : Number.NaN;
      if (!Number.isNaN(since) && !Number.isNaN(until) && since < until) {
        const result = await env.DB.prepare("DELETE FROM report WHERE received >= ? AND received < ?")
          .bind(new Date(since).toISOString(), new Date(until).toISOString())
          .run();
        return json({ deleted: result.meta?.changes ?? 0 });
      }
      return json({ error: "id, device tai since ja until" }, 400);
    }
    case "places/status":
      return json(await placesStatus(env.DB));
    case "places/begin":
      return json(await placesBegin(env.DB));
    case "places/import": {
      const rows = (body as { rows?: unknown } | null)?.rows;
      if (Array.isArray(rows) && rows.length > MAX_PLACE_IMPORT_ROWS) return json({ error: `enintään ${MAX_PLACE_IMPORT_ROWS} riviä` }, 413);
      const valid = validatePlaceImport(body);
      if ("error" in valid) return json({ error: valid.error }, 400);
      try {
        return json(await placesImport(env.DB, valid));
      } catch (error) {
        if (error instanceof PlaceConflictError) return json({ error: error.message }, 409);
        throw error;
      }
    }
    case "places/swap": {
      const b = body as { gen?: unknown; release?: unknown; force?: unknown } | null;
      if (!b || typeof b.gen !== "number" || typeof b.release !== "string" || b.release === "") {
        return json({ error: "gen ja release vaaditaan" }, 400);
      }
      const swap = await placesSwap(env.DB, b.gen, b.release, b.force === true);
      if (swap.kind === "conflict") return json({ error: swap.error }, 409);
      return json(swap.kind === "already" ? { ok: true, alreadySwapped: true } : { ok: true, rows: swap.rows });
    }
    case "places/prune":
      return json(await placesPrune(env.DB));
    case "begin": {
      const country = (body as { country?: unknown } | null)?.country;
      if (!validCountry(country)) return json({ error: "country" }, 400);
      const s = beginStatement(country);
      await env.DB.prepare(s.sql).bind(...s.params).run();
      return json({ ok: true });
    }
    case "import": {
      const rows = (body as { rows?: unknown } | null)?.rows;
      if (Array.isArray(rows) && rows.length > MAX_IMPORT_ROWS) return json({ error: `enintään ${MAX_IMPORT_ROWS} riviä` }, 413);
      const valid = validateImport(body);
      if ("error" in valid) return json({ error: valid.error }, 400);
      await env.DB.prepare(IMPORT_SQL).bind(importParam(valid, now)).run();
      return json({ inserted: valid.rows.length });
    }
    case "swap": {
      const b = body as { country?: unknown; extractDate?: unknown; force?: unknown } | null;
      if (!b || !validCountry(b.country) || typeof b.extractDate !== "string") {
        return json({ error: "country ja extractDate vaaditaan" }, 400);
      }
      const staged = await env.DB.prepare(STAGING_COUNT_SQL).bind(b.country).first<{ n: number }>();
      const previous = await env.DB.prepare(PREVIOUS_META_SQL).bind(b.country).first<{ extractDate: string; rows: number }>();
      const decision = swapDecision(staged?.n ?? 0, previous ?? null, b.extractDate, b.force === true);
      if (decision.kind === "conflict") return json({ error: decision.error }, 409);
      if (decision.kind === "already") return json({ ok: true, alreadySwapped: true });
      const statements = swapStatements(b.country, b.extractDate, decision.rows, now);
      await env.DB.batch(statements.map((s) => env.DB.prepare(s.sql).bind(...s.params)));
      return json({ ok: true });
    }
    default:
      return json({ error: "not found" }, 404);
  }
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": status === 200 ? (headers["Cache-Control"] ?? "no-store") : "no-store",
      ...headers,
    },
  });
}
