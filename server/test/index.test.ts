import { describe, expect, it } from "vitest";
import worker from "../src/index";
import type { Env } from "../src/env";
import { FACTS_SQL } from "../src/admin";
import { buildFactsSelect, buildSelect, parseQuery } from "../src/query";
import { CELL_SQL, LATEST_SQL, latestParams } from "../src/reports";
import { sqliteD1 } from "./d1";

const poiRows = [
  { id: "n1", cat: "fuel", lat: 60.3, lon: 24.6, name: "Neste Truck", brand: null, addr: null, hgv: 1, tags: "{}" },
];

type Executed = Array<{ sql: string; params: unknown[] }>;
/** Vastaus lauseen alun mukaan: arvo sellaisenaan tai funktio parametreista ja jo ajetuista lauseista. */
type Answers = Record<string, unknown>;

function answer(answers: Answers, sql: string, params: unknown[], executed: Executed): unknown {
  const key = Object.keys(answers).find((prefix) => sql.startsWith(prefix));
  if (key === undefined) return undefined;
  const value = answers[key];
  return typeof value === "function" ? (value as (p: unknown[], e: Executed) => unknown)(params, executed) : value;
}

/**
 * D1-fake: kirjaa lauseet ja parametrit. [first] vastaa yksirivisiin kyselyihin ja [all] monirivisiin lauseen alun
 * mukaan; muut monirivisen kyselyt saavat [rows].
 */
function fakeDb(rows: unknown[] = poiRows, first: Answers = {}, all: Answers = {}) {
  const executed: Executed = [];
  const batches: number[] = [];
  const batched: unknown[][] = [];
  const prepare = (sql: string) => {
    const statement = {
      sql,
      params: [] as unknown[],
      bind(...params: unknown[]) {
        statement.params = params;
        return statement;
      },
      async all() {
        const results = answer(all, sql, statement.params, executed) ?? rows;
        executed.push({ sql, params: statement.params });
        return { results };
      },
      async run() {
        executed.push({ sql, params: statement.params });
        return { success: true };
      },
      async first() {
        const result = answer(first, sql, statement.params, executed) ?? null;
        executed.push({ sql, params: statement.params });
        return result;
      },
    };
    return statement;
  };
  return {
    executed,
    batches,
    batched,
    prepare,
    /** Erän lauseet vastaavat kuten [all]: rivit lauseen alun mukaan, muuten [rows]. */
    async batch(statements: Array<{ sql: string; params: unknown[] }>) {
      batches.push(statements.length);
      batched.push(...statements.map((s) => s.params));
      const results = statements.map((s) => ({ success: true, results: answer(all, s.sql, s.params, executed) ?? rows }));
      executed.push(...statements.filter((s) => s.sql.startsWith("INSERT INTO report")).map((s) => ({ sql: s.sql, params: s.params })));
      return results;
    },
  };
}

function env(overrides: Partial<Record<keyof Env, unknown>> = {}) {
  return {
    DB: fakeDb(),
    KV: { get: async () => null },
    RATE_IP: { limit: async () => ({ success: true }) },
    RATE_GLOBAL: { limit: async () => ({ success: true }) },
    RATE_REPORT: { limit: async () => ({ success: true }) },
    RATE_SEARCH: { limit: async () => ({ success: true }) },
    ADMIN_TOKEN: "salainen",
    ...overrides,
  } as unknown as Env;
}

const get = (path: string, headers: Record<string, string> = {}) =>
  worker.fetch(new Request(`https://poi.example${path}`, { headers: { "CF-Connecting-IP": "203.0.113.7", ...headers } }), env());

describe("GET /v1/poi", () => {
  it("vastaa kohteilla, välimuistiotsakkeella ja lisenssilinkillä", async () => {
    const response = await get("/v1/poi?bbox=24.5,60.25,25.25,60.5&cats=fuel");
    expect(response.status).toBe(200);
    expect(response.headers.get("Cache-Control")).toBe("public, max-age=86400");
    expect(response.headers.get("Link")).toContain("opendatacommons.org/licenses/odbl");
    expect(response.headers.get("Content-Type")).toContain("application/json");
    const body = (await response.json()) as { source: string; generatedAt: string; items: Array<{ id: string; hgv: boolean }> };
    expect(body.source).toContain("OpenStreetMap");
    expect(body.generatedAt).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    expect(body.items).toEqual([{ id: "n1", cat: "fuel", lat: 60.3, lon: 24.6, name: "Neste Truck", hgv: true, tags: {} }]);
  });

  it("vastaa 400 ilman ikkunaa eikä anna välimuistittaa virhettä", async () => {
    const response = await get("/v1/poi");
    expect(response.status).toBe(400);
    expect(response.headers.get("Cache-Control")).toBe("no-store");
  });

  it("vastaa 429 ja Retry-After, kun osoitteen raja täyttyy", async () => {
    const response = await worker.fetch(
      new Request("https://poi.example/v1/poi?bbox=24.5,60.25,25.25,60.5", { headers: { "CF-Connecting-IP": "203.0.113.7" } }),
      env({ RATE_IP: { limit: async () => ({ success: false }) } }),
    );
    expect(response.status).toBe(429);
    expect(response.headers.get("Retry-After")).toBe("60");
  });

  it("vastaa 503 ja pitkän Retry-Afterin, kun palvelu on pysäytetty", async () => {
    const response = await worker.fetch(
      new Request("https://poi.example/v1/poi?bbox=24.5,60.25,25.25,60.5"),
      env({ KV: { get: async () => "1" } }),
    );
    expect(response.status).toBe(503);
    expect(response.headers.get("Retry-After")).toBe("3600");
  });
});

describe("GET /v1/poi ja kertatuonnin faktat", () => {
  const facts = [
    { id: "n1", cat: "fuel", lat: 60.3, lon: 24.6, name: null, brand: null, addr: null, linked: 1, facts: '{"k:date":"2026-10-22","k:fuels":"diesel"}' },
    { id: "n999", cat: "fuel", lat: 60.31, lon: 24.61, name: null, brand: null, addr: null, linked: 1, facts: '{"k:date":"2026-10-22"}' },
    {
      id: "k-teboil-77", cat: "fuel", lat: 60.36, lon: 24.9, name: "Teboil Kylmäkoski", brand: "Teboil", addr: "Tie 2", linked: 0,
      facts: '{"k:date":"2026-10-22","k:status":"winding_down"}',
    },
  ];

  it("liittää faktat kohteisiin, lisää omat asemat ja ohittaa kadonneen kohteen faktat", async () => {
    const db = fakeDb(poiRows, {}, { "SELECT id, cat, lat, lon, name, brand, addr, linked, facts FROM station_facts": facts });
    const response = await worker.fetch(
      new Request("https://poi.example/v1/poi?bbox=24.5,60.25,25.25,60.5&cats=fuel", { headers: { "CF-Connecting-IP": "203.0.113.7" } }),
      env({ DB: db }),
    );
    expect(response.status).toBe(200);
    const body = (await response.json()) as { source: string; items: Array<{ id: string; tags: Record<string, string> }> };
    expect(body.source).toBe(
      "© OpenStreetMap contributors (ODbL); Fintraffic / digitraffic.fi (CC BY 4.0); " +
        "Osoitteiden täydennys © Digitransit 2026 (CC BY 4.0; OpenStreetMap-pohjaiset osoitteet ODbL)",
    );
    expect(body.items.map((i) => i.id)).toEqual(["n1", "k-teboil-77"]);
    expect(body.items[0]!.tags).toEqual({ "k:date": "2026-10-22", "k:fuels": "diesel" });
    expect(body.items[1]!.tags["k:status"]).toBe("winding_down");
  });

  it("katkaisu koskee yhdistettyä listaa", async () => {
    const rows = Array.from({ length: 300 }, (_, i) => ({ ...poiRows[0], id: `n${i}`, hgv: 0 }));
    const db = fakeDb(rows, {}, { "SELECT id, cat, lat, lon, name, brand, addr, linked, facts FROM station_facts": [facts[2]] });
    const response = await worker.fetch(
      new Request("https://poi.example/v1/poi?bbox=24.5,60.25,25.25,60.5&cats=fuel", { headers: { "CF-Connecting-IP": "203.0.113.7" } }),
      env({ DB: db }),
    );
    const body = (await response.json()) as { items: Array<{ id: string }>; truncated: boolean };
    expect(body.items).toHaveLength(300);
    expect(body.truncated).toBe(true);
  });

  it("ilmoituksen voi tehdä myös kertatuonnin omasta asemasta", async () => {
    expect(CELL_SQL).toContain("FROM station_facts");
  });
});

describe("/admin/facts", () => {
  const post = (path: string, payload: unknown, e: Env) =>
    worker.fetch(
      new Request(`https://poi.example${path}`, {
        method: "POST",
        headers: { Authorization: "Bearer salainen", "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      }),
      e,
    );

  it("tallentaa faktat yhdellä lauseella", async () => {
    const db = fakeDb();
    const rows = [{ id: "k-neste-2605", cat: "fuel", lat: 60.9, lon: 23.1, linked: 0, facts: { "k:date": "2026-10-22" } }];
    const response = await post("/admin/facts", { rows }, env({ DB: db }));
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ inserted: 1 });
    expect(db.executed.map((s) => s.sql)).toEqual([FACTS_SQL]);
  });

  it("hylkää kielletyn avaimen ja tyhjentää faktat vain erillisellä pyynnöllä", async () => {
    const db = fakeDb();
    const rows = [{ id: "n1", cat: "fuel", lat: 60.9, lon: 23.1, linked: 1, facts: { "k:phone": "0401234567" } }];
    expect((await post("/admin/facts", { rows }, env({ DB: db }))).status).toBe(400);
    expect(db.executed).toHaveLength(0);
    expect((await post("/admin/facts/clear", {}, env({ DB: db }))).status).toBe(200);
    expect(db.executed.map((s) => s.sql)).toEqual(["DELETE FROM station_facts"]);
  });
});

describe("/admin/addr", () => {
  const post = (path: string, payload: unknown, e: Env, token = "salainen") =>
    worker.fetch(
      new Request(`https://poi.example${path}`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      }),
      e,
    );
  const row = { id: "n1", lat: 60.3, lon: 24.6, addr: "Mikaelintie 2, 01620 Vantaa", checked: "2026-10-31" };
  const list = async (e: Env) => ((await (await post("/admin/addr/list", {}, e)).json()) as { rows: unknown[] }).rows;

  it("vaatii tunnuksen", async () => {
    const db = sqliteD1();
    expect((await post("/admin/addr/list", {}, env({ DB: db }), "väärä")).status).toBe(401);
    expect((await post("/admin/addr/put", { rows: [row] }, env({ DB: db }), "väärä")).status).toBe(401);
    expect(db.raw.prepare("SELECT COUNT(*) AS n FROM addr_fill").get()).toEqual({ n: 0 });
  });

  it("tallentaa rivit ja listaa ne tunnisteen mukaan", async () => {
    const e = env({ DB: sqliteD1() });
    const latauspiste = { id: "c-FI001-311500", lat: 61.1, lon: 25.1, addr: "Peuralahdentie 52, 52550 Hirvensalmi", checked: "2026-10-30" };
    const response = await post("/admin/addr/put", { rows: [row, latauspiste] }, e);
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ inserted: 2 });
    expect(await list(e)).toEqual([latauspiste, row]);
  });

  it("uudelleen tallennettu kohde korvaa vanhan, ja tyhjä osoite säilyy nullina", async () => {
    const e = env({ DB: sqliteD1() });
    await post("/admin/addr/put", { rows: [row] }, e);
    const none = { ...row, lat: 60.4, addr: null, checked: "2026-11-01" };
    await post("/admin/addr/put", { rows: [none] }, e);
    expect(await list(e)).toEqual([none]);
  });

  it("hylkää yli 500 rivin erän", async () => {
    const db = sqliteD1();
    const rows = Array.from({ length: 501 }, (_, i) => ({ ...row, id: `n${i}` }));
    expect((await post("/admin/addr/put", { rows }, env({ DB: db }))).status).toBe(413);
    expect(db.raw.prepare("SELECT COUNT(*) AS n FROM addr_fill").get()).toEqual({ n: 0 });
  });

  it("500 rivin erä kelpaa", async () => {
    const e = env({ DB: sqliteD1() });
    const rows = Array.from({ length: 500 }, (_, i) => ({ ...row, id: `n${i}` }));
    expect((await post("/admin/addr/put", { rows }, e)).status).toBe(200);
    expect(await list(e)).toHaveLength(500);
  });

  it.each([
    ["id", { ...row, id: "x1" }],
    ["id puuttuu", { ...row, id: undefined }],
    ["lat", { ...row, lat: 91 }],
    ["lat tekstinä", { ...row, lat: "60.3" }],
    ["lon", { ...row, lon: 181 }],
    ["osoite numerona", { ...row, addr: 5 }],
    ["osoite tyhjänä", { ...row, addr: "" }],
    ["osoite liian pitkä", { ...row, addr: "x".repeat(201) }],
    ["osoite puuttuu", { ...row, addr: undefined }],
    ["päiväys tekstinä", { ...row, checked: "eilen" }],
    ["päiväys olematon", { ...row, checked: "2026-13-40" }],
    ["päiväys kellonajalla", { ...row, checked: "2026-10-31T10:00:00.000Z" }],
    ["päiväys vajaa", { ...row, checked: "2026-10" }],
    ["päiväys helmikuun 30.", { ...row, checked: "2026-02-30" }],
    ["ei olio", "n1"],
  ])("hylkää väärän rivin: %s", async (_name, bad) => {
    const db = sqliteD1();
    expect((await post("/admin/addr/put", { rows: [row, bad] }, env({ DB: db }))).status).toBe(400);
    expect(db.raw.prepare("SELECT COUNT(*) AS n FROM addr_fill").get()).toEqual({ n: 0 });
  });

  it("hylkää rungon ilman rivejä", async () => {
    expect((await post("/admin/addr/put", {}, env({ DB: sqliteD1() }))).status).toBe(400);
    expect((await post("/admin/addr/put", { rows: "n1" }, env({ DB: sqliteD1() }))).status).toBe(400);
  });

  it("200 merkin osoite kelpaa", async () => {
    const e = env({ DB: sqliteD1() });
    expect((await post("/admin/addr/put", { rows: [{ ...row, addr: "x".repeat(200) }] }, e)).status).toBe(200);
  });
});

describe("GET /v1/meta", () => {
  it("palauttaa meta-rivit tunnin välimuistilla", async () => {
    const e = env({ DB: fakeDb([{ country: "FI", extractDate: "2026-10-20", rows: 1234, updated: "2026-10-22T02:00:00Z" }]) });
    const response = await worker.fetch(new Request("https://poi.example/v1/meta"), e);
    expect(response.status).toBe(200);
    expect(response.headers.get("Cache-Control")).toBe("public, max-age=3600");
    expect(await response.json()).toEqual([{ country: "FI", extractDate: "2026-10-20", rows: 1234, updated: "2026-10-22T02:00:00Z" }]);
  });

  it("on saman suojan takana kuin kohdekysely", async () => {
    const response = await worker.fetch(new Request("https://poi.example/v1/meta"), env({ KV: { get: async () => "1" } }));
    expect(response.status).toBe(503);
    const limited = await worker.fetch(new Request("https://poi.example/v1/meta"), env({ RATE_IP: { limit: async () => ({ success: false }) } }));
    expect(limited.status).toBe(429);
  });
});

describe("GET /v1/poi katkaisu", () => {
  const rows = (n: number) => Array.from({ length: n }, (_, i) => ({ ...poiRows[0], id: `n${i}` }));
  const fetchWith = (n: number) =>
    worker.fetch(
      new Request("https://poi.example/v1/poi?bbox=24.9,60.15,24.95,60.2&cats=charging", { headers: { "CF-Connecting-IP": "203.0.113.7" } }),
      env({ DB: fakeDb(rows(n)) }),
    );

  it("kertoo katkaisusta ja palauttaa enintään 300 kohdetta", async () => {
    const body = (await (await fetchWith(301)).json()) as { items: unknown[]; truncated: boolean };
    expect(body.items).toHaveLength(300);
    expect(body.truncated).toBe(true);
  });

  it("ei katkaisua, kun kohteita on enintään 300", async () => {
    const body = (await (await fetchWith(300)).json()) as { items: unknown[]; truncated: boolean };
    expect(body.items).toHaveLength(300);
    expect(body.truncated).toBe(false);
  });
});

describe("/admin", () => {
  const post = (path: string, body: unknown, e: Env, auth = "Bearer salainen") =>
    worker.fetch(
      new Request(`https://poi.example${path}`, { method: "POST", headers: { Authorization: auth, "Content-Type": "application/json" }, body: JSON.stringify(body) }),
      e,
    );

  it("vastaa 401 ilman tunnusta", async () => {
    const response = await post("/admin/import", { country: "FI", rows: [] }, env(), "Bearer vaara");
    expect(response.status).toBe(401);
  });

  it("tuo rivit välitauluun ja vaihtaa maan yhdessä erässä", async () => {
    const db = fakeDb([], { "SELECT COUNT(*)": { n: 1 }, "SELECT extractDate": { extractDate: "2026-10-13", rows: 1 } });
    const e = env({ DB: db });
    const begin = await post("/admin/begin", { country: "FI" }, e);
    expect(begin.status).toBe(200);
    const imported = await post("/admin/import", { country: "FI", rows: [{ id: "n1", cat: "fuel", lat: 60.3, lon: 24.6, hgv: 0, tags: {} }] }, e);
    expect(imported.status).toBe(200);
    expect(await imported.json()).toEqual({ inserted: 1 });
    const swapped = await post("/admin/swap", { country: "FI", extractDate: "2026-10-20", rows: 1 }, e);
    expect(swapped.status).toBe(200);
    expect(db.executed.map((s) => s.sql.split(" ").slice(0, 3).join(" "))).toEqual([
      "DELETE FROM poi_staging",
      "INSERT OR REPLACE",
      "SELECT COUNT(*) AS",
      "SELECT extractDate, rows",
    ]);
    expect(db.batches).toEqual([4]);
  });

  const swap = (db: ReturnType<typeof fakeDb>, body: Record<string, unknown> = {}) =>
    post("/admin/swap", { country: "FI", extractDate: "2026-10-20", rows: 4377, ...body }, env({ DB: db }));

  it("tyhjä välitaulu ei tyhjennä maata", async () => {
    const db = fakeDb([], { "SELECT COUNT(*)": { n: 0 }, "SELECT extractDate": { extractDate: "2026-10-13", rows: 4300 } });
    const response = await swap(db);
    expect(response.status).toBe(409);
    expect(((await response.json()) as { error: string }).error).toContain("tyhjä");
    expect(db.batches).toEqual([]);
  });

  it("saman otteen uusinta on jo vaihdettu: kuitataan tekemättä mitään", async () => {
    const db = fakeDb([], { "SELECT COUNT(*)": { n: 0 }, "SELECT extractDate": { extractDate: "2026-10-20", rows: 4377 } });
    const response = await swap(db);
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ok: true, alreadySwapped: true });
    expect(db.batches).toEqual([]);
  });

  it("alle puolet edellisestä rivimäärästä vaatii pakotuksen", async () => {
    const shrunk = { "SELECT COUNT(*)": { n: 2000 }, "SELECT extractDate": { extractDate: "2026-10-13", rows: 4377 } };
    const db = fakeDb([], shrunk);
    const response = await swap(db);
    expect(response.status).toBe(409);
    expect(((await response.json()) as { error: string }).error).toContain("4377");
    expect(db.batches).toEqual([]);

    const forced = fakeDb([], shrunk);
    expect((await swap(forced, { force: true })).status).toBe(200);
    expect(forced.batches).toEqual([4]);
  });

  it("metaan kirjataan välitaulun todellinen rivimäärä ja ensimmäinen maa vaihtuu ilman aiempaa metaa", async () => {
    const db = fakeDb([], { "SELECT COUNT(*)": { n: 4370 } });
    expect((await swap(db)).status).toBe(200);
    expect(db.batches).toEqual([4]);
    expect(db.batched[3]).toEqual(["FI", "2026-10-20", 4370, expect.any(String)]);
  });

  it("vastaa 400 virheellisestä rungosta ja 413 liian suuresta erästä", async () => {
    const e = env();
    expect((await post("/admin/import", { country: "FI", rows: [{ id: "x" }] }, e)).status).toBe(400);
    const rows = Array.from({ length: 501 }, (_, i) => ({ id: `n${i}`, cat: "fuel", lat: 60, lon: 24, hgv: 0, tags: {} }));
    expect((await post("/admin/import", { country: "FI", rows }, e)).status).toBe(413);
  });
});

describe("/v1/reports", () => {
  const DEVICE = "0f8fad5b-d9cb-469f-a165-70867728950e";
  const RID = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
  const body = { poi: "n1", field: "closed", value: "yes", rid: RID };
  const send = (e: Env, payload: unknown = body, device: string | null = DEVICE) =>
    worker.fetch(
      new Request("https://poi.example/v1/reports", {
        method: "POST",
        headers: { "CF-Connecting-IP": "203.0.113.7", "Content-Type": "application/json", ...(device ? { "X-Device": device } : {}) },
        body: JSON.stringify(payload),
      }),
      e,
    );
  const inserts = (db: ReturnType<typeof fakeDb>) => db.executed.filter((s) => s.sql.startsWith("INSERT INTO report"));
  const known = { "SELECT cell FROM poi": { cell: 1234 }, "SELECT COUNT(*) AS n FROM report": { n: 0 } };

  it("tallentaa ilmoituksen kohteen soluun ja palauttaa päivän", async () => {
    const db = fakeDb([], known);
    const response = await send(env({ DB: db }));
    expect(response.status).toBe(201);
    const { date, at } = (await response.json()) as { date: string; at: string };
    expect(date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(at.slice(0, 10)).toBe(date);
    expect(inserts(db)).toHaveLength(1);
    const params = inserts(db)[0]!.params;
    expect(params.slice(0, 5)).toEqual([RID, "n1", "closed", "yes", DEVICE]);
    expect(params[5]).toBe(at);
    expect(params[6]).toBe(at);
    expect(params[7]).toBe(1234);
  });

  it("jonossa ollut ilmoitus tallentuu tekohetkellään, mutta raja lasketaan vastaanottohetkestä", async () => {
    const db = fakeDb([], known);
    const made = new Date(Date.now() - 30 * 3600 * 1000).toISOString();
    const response = await send(env({ DB: db }), { ...body, at: made });
    expect(response.status).toBe(201);
    expect(await response.json()).toEqual({ date: made.slice(0, 10), at: made });
    const params = inserts(db)[0]!.params;
    expect(params[5]).toBe(made);
    expect(Date.parse(params[6] as string)).toBeGreaterThan(Date.now() - 60_000);
    const count = db.executed.find((s) => s.sql.startsWith("SELECT COUNT(*) AS n FROM report"))!;
    expect(count.sql).toContain("received > ?");
  });

  it("vastaa 404 tuntemattomalle kohteelle", async () => {
    const db = fakeDb([], { "SELECT COUNT(*) AS n FROM report": { n: 0 } });
    expect((await send(env({ DB: db }))).status).toBe(404);
    expect(inserts(db)).toHaveLength(0);
  });

  it("vastaa 400 virheelliseen ilmoitukseen ja puuttuvaan laitteeseen", async () => {
    const db = fakeDb([], known);
    expect((await send(env({ DB: db }), { ...body, value: "maybe" })).status).toBe(400);
    expect((await send(env({ DB: db }), body, null)).status).toBe(400);
    expect(inserts(db)).toHaveLength(0);
  });

  it("vastaa 429 ilman lisäystä, kun laite on ilmoittanut vuorokaudessa rajan verran", async () => {
    const db = fakeDb([], { ...known, "SELECT COUNT(*) AS n FROM report": { n: 50 } });
    const response = await send(env({ DB: db }));
    expect(response.status).toBe(429);
    expect(inserts(db)).toHaveLength(0);
    const count = db.executed.find((s) => s.sql.startsWith("SELECT COUNT(*) AS n FROM report"))!;
    expect(count.params[0]).toBe(DEVICE);
  });

  it("sama rid kahdesti: toinen kuitataan kaksoiskappaleena eikä uutta riviä tule", async () => {
    const db = fakeDb([], {
      ...known,
      "SELECT created FROM report WHERE rid": (params: unknown[], executed: Executed) =>
        executed.some((s) => s.sql.startsWith("INSERT INTO report") && s.params[0] === params[0]) ? { created: "2026-10-23T05:00:00.000Z" } : null,
    });
    const e = env({ DB: db });
    expect((await send(e)).status).toBe(201);
    const again = await send(e);
    expect(again.status).toBe(200);
    expect(await again.json()).toEqual({ date: "2026-10-23", at: "2026-10-23T05:00:00.000Z", duplicate: true });
    expect(inserts(db)).toHaveLength(1);
  });

  it("rajoittaa ilmoituksia myös osoitetta kohti, koska laitetunnisteen voi vaihtaa", async () => {
    const keys: string[] = [];
    const db = fakeDb([], known);
    const response = await send(
      env({ DB: db, RATE_REPORT: { limit: async ({ key }: { key: string }) => (keys.push(key), { success: false }) } }),
    );
    expect(response.status).toBe(429);
    expect(keys).toEqual(["203.0.113.7"]);
    expect(inserts(db)).toHaveLength(0);
  });

  it("on saman suojan takana kuin kohdekysely", async () => {
    const response = await send(env({ RATE_IP: { limit: async () => ({ success: false }) } }));
    expect(response.status).toBe(429);
    expect(response.headers.get("Retry-After")).toBe("60");
  });

  it("GET palauttaa ikkunan uusimmat ilmoitukset ilman välimuistia", async () => {
    const items = [{ poi: "n1", field: "closed", value: "yes", date: "2026-10-23", at: "2026-10-23T05:00:00.000Z" }];
    const db = fakeDb([], {}, { [LATEST_SQL.slice(0, 20)]: items });
    const response = await worker.fetch(
      new Request("https://poi.example/v1/reports?bbox=24.5,60.25,25.25,60.5", { headers: { "CF-Connecting-IP": "203.0.113.7" } }),
      env({ DB: db }),
    );
    expect(response.status).toBe(200);
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    expect(await response.json()).toEqual({ items });
    expect(db.executed).toEqual([{ sql: LATEST_SQL, params: latestParams([24.5, 60.25, 25.25, 60.5]) }]);
  });

  it("GET vastaa 400 ilman ikkunaa", async () => {
    expect((await get("/v1/reports")).status).toBe(400);
  });
});

describe("/admin/reports/delete", () => {
  const remove = (payload: unknown, e: Env, auth = "Bearer salainen") =>
    worker.fetch(
      new Request("https://poi.example/admin/reports/delete", {
        method: "POST",
        headers: { Authorization: auth, "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      }),
      e,
    );

  it("vastaa 401 ilman tunnusta", async () => {
    const db = fakeDb();
    expect((await remove({ id: 1 }, env({ DB: db }), "Bearer vaara")).status).toBe(401);
    expect(db.executed).toHaveLength(0);
  });

  it("poistaa ilmoituksen tunnisteella tai kaikki laitteen ilmoitukset", async () => {
    const db = fakeDb();
    expect((await remove({ id: 7 }, env({ DB: db }))).status).toBe(200);
    expect((await remove({ device: "0f8fad5b-d9cb-469f-a165-70867728950e" }, env({ DB: db }))).status).toBe(200);
    expect(db.executed).toEqual([
      { sql: "DELETE FROM report WHERE id = ?", params: [7] },
      { sql: "DELETE FROM report WHERE device = ?", params: ["0f8fad5b-d9cb-469f-a165-70867728950e"] },
    ]);
  });

  it("poistaa aikavälillä vastaanotetut ilmoitukset (tulvan siivous ilman osoitetietoa)", async () => {
    const db = fakeDb();
    const range = { since: "2026-10-23T05:00:00Z", until: "2026-10-23T06:00:00Z" };
    expect((await remove(range, env({ DB: db }))).status).toBe(200);
    expect(db.executed).toEqual([
      { sql: "DELETE FROM report WHERE received >= ? AND received < ?", params: ["2026-10-23T05:00:00.000Z", "2026-10-23T06:00:00.000Z"] },
    ]);
    expect((await remove({ since: "eilen", until: range.until }, env({ DB: fakeDb() }))).status).toBe(400);
  });

  it("vastaa 400 ilman kelvollista tunnistetta", async () => {
    const db = fakeDb();
    expect((await remove({}, env({ DB: db }))).status).toBe(400);
    expect((await remove({ id: "1 OR 1=1" }, env({ DB: db }))).status).toBe(400);
    expect(db.executed).toHaveLength(0);
  });
});

describe("POST /v1/poi/batch", () => {
  const POI_SQL = "SELECT id, cat, lat, lon, name, brand, addr, hgv, tags FROM poi";
  const FACTS_SELECT = "SELECT id, cat, lat, lon, name, brand, addr, linked, facts FROM station_facts";
  const parts = [
    { bbox: "24.50,60.25,24.75,60.50", cats: "fuel" },
    { bbox: "24.75,60.25,25.00,60.50", cats: "gas" },
  ];
  const fuelRow = { id: "n1", cat: "fuel", lat: 60.3, lon: 24.6, name: "Neste", brand: "Neste", addr: "Tie 1", hgv: 1, tags: '{"opening_hours":"24/7"}' };
  const gasRow = { id: "n2", cat: "gas", lat: 60.4, lon: 24.9, name: "Gasum", brand: null, addr: null, hgv: 0, tags: "{}" };
  const fact = { id: "n1", cat: "fuel", lat: 60.3, lon: 24.6, name: null, brand: null, addr: null, linked: 1, facts: '{"k:fuels":"diesel"}' };
  /** Kohdekyselyn ensimmäinen parametri on laji; faktat vain huoltoasemaikkunaan. */
  const answers = (fuelRows: unknown[] = [fuelRow]) => ({
    [POI_SQL]: (params: unknown[]) => (params[0] === "fuel" ? fuelRows : [gasRow]),
    [FACTS_SELECT]: (params: unknown[]) => (params[0] === "fuel" ? [fact] : []),
  });
  const post = (body: string, e: Env) =>
    worker.fetch(
      new Request("https://poi.example/v1/poi/batch", {
        method: "POST",
        headers: { "CF-Connecting-IP": "203.0.113.7", "Content-Type": "application/json" },
        body,
      }),
      e,
    );
  type Part = { generatedAt: string; source: string; items: Array<{ id: string }>; truncated: boolean };

  it("palauttaa osat palojen järjestyksessä, ja jokainen osa on sama kuin vastaava GET /v1/poi", async () => {
    const db = fakeDb([], {}, answers());
    const response = await post(JSON.stringify({ parts }), env({ DB: db }));
    expect(response.status).toBe(200);
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    expect(response.headers.get("Link")).toContain("opendatacommons.org/licenses/odbl");
    const body = (await response.json()) as { parts: Part[] };
    expect(body.parts.map((p) => p.items.map((i) => i.id))).toEqual([["n1"], ["n2"]]);
    for (const [index, part] of parts.entries()) {
      const single = await worker.fetch(
        new Request(`https://poi.example/v1/poi?bbox=${part.bbox}&cats=${part.cats}`, { headers: { "CF-Connecting-IP": "203.0.113.7" } }),
        env({ DB: fakeDb([], {}, answers()) }),
      );
      const { generatedAt: _single, ...expected } = (await single.json()) as Part;
      const { generatedAt, ...actual } = body.parts[index]!;
      expect(generatedAt).toMatch(/^\d{4}-\d{2}-\d{2}T/);
      expect(actual).toEqual(expected);
    }
    expect(body.parts[0]!.items[0]).toMatchObject({ tags: { opening_hours: "24/7", "k:fuels": "diesel" } });
  });

  it("lähettää kaikki kyselyt yhdellä erällä, kaksi lausetta palaa kohti, kukin oman palansa ikkunasta", async () => {
    const db = fakeDb([], {}, answers());
    await post(JSON.stringify({ parts }), env({ DB: db }));
    expect(db.batches).toEqual([4]);
    expect(db.executed).toHaveLength(0);
    const queries = parts.map((p) => {
      const parsed = parseQuery(new URLSearchParams(p));
      if (!parsed.ok) throw new Error(parsed.error);
      return parsed.query;
    });
    expect(db.batched).toEqual(queries.flatMap((q) => [buildSelect(q).params, buildFactsSelect(q).params]));
  });

  it("osan kohteet ovat lähimmät ensin palan keskeltä", async () => {
    const far = { ...fuelRow, id: "n5", lat: 60.26, lon: 24.51, hgv: 0 };
    const near = { ...fuelRow, id: "n6", lat: 60.37, lon: 24.62, hgv: 0 };
    const db = fakeDb([], {}, { ...answers([far, near]), [FACTS_SELECT]: [] });
    const body = (await (await post(JSON.stringify({ parts: [parts[0]] }), env({ DB: db }))).json()) as { parts: Part[] };
    expect(body.parts[0]!.items.map((i) => i.id)).toEqual(["n6", "n5"]);
  });

  it("50 palaa kelpaa ja jokainen saa osan", async () => {
    const many = Array.from({ length: 50 }, (_, i) => ({ bbox: `${(96 + i) / 4},60.25,${(97 + i) / 4},60.50`, cats: "fuel" }));
    const db = fakeDb([], {}, answers());
    const response = await post(JSON.stringify({ parts: many }), env({ DB: db }));
    expect(response.status).toBe(200);
    expect(((await response.json()) as { parts: Part[] }).parts).toHaveLength(50);
    expect(db.batches).toEqual([100]);
  });

  it("liian suuri runko vastaa 413 ilman kantakyselyitä", async () => {
    const db = fakeDb([], {}, answers());
    const response = await post(JSON.stringify({ parts, pad: "x".repeat(20_000) }), env({ DB: db }));
    expect(response.status).toBe(413);
    expect(db.batches).toEqual([]);
  });

  it("erän tulosten määrän virhe ei tuota tyhjiä osia", async () => {
    const db = fakeDb([], {}, answers());
    const short = { ...db, batch: async (statements: unknown[]) => (await db.batch(statements as never)).slice(1) };
    await expect(post(JSON.stringify({ parts }), env({ DB: short }))).rejects.toThrow();
  });

  it("katkaisu on palakohtainen", async () => {
    const many = Array.from({ length: 301 }, (_, i) => ({ ...fuelRow, id: `n${i + 10}`, hgv: 0 }));
    const response = await post(JSON.stringify({ parts }), env({ DB: fakeDb([], {}, answers(many)) }));
    const body = (await response.json()) as { parts: Part[] };
    expect(body.parts[0]!.items).toHaveLength(300);
    expect(body.parts[0]!.truncated).toBe(true);
    expect(body.parts[1]!.items).toHaveLength(1);
    expect(body.parts[1]!.truncated).toBe(false);
  });

  it("lasketaan osoiterajaan yhtenä hakuna", async () => {
    let calls = 0;
    const counting = { limit: async () => ((calls += 1), { success: true }) };
    const response = await post(JSON.stringify({ parts }), env({ DB: fakeDb([], {}, answers()), RATE_IP: counting }));
    expect(response.status).toBe(200);
    expect(calls).toBe(1);
  });

  it("täysi osoiteraja vastaa 429 ennen kantaa", async () => {
    const db = fakeDb([], {}, answers());
    const response = await post(JSON.stringify({ parts }), env({ DB: db, RATE_IP: { limit: async () => ({ success: false }) } }));
    expect(response.status).toBe(429);
    expect(response.headers.get("Retry-After")).toBe("60");
    expect(db.batches).toEqual([]);
    expect(db.executed).toHaveLength(0);
  });

  it("vastaa 400 ilman kantakyselyitä, kun runko ei ole JSONia, paloja on liikaa tai pala on virheellinen", async () => {
    const tooMany = Array.from({ length: 51 }, () => parts[0]);
    const broken = [parts[0], { bbox: "24.50,60.25", cats: "fuel" }];
    for (const body of ["{", JSON.stringify({ parts: tooMany }), JSON.stringify({ parts: broken })]) {
      const db = fakeDb([], {}, answers());
      const response = await post(body, env({ DB: db }));
      expect(response.status).toBe(400);
      expect(response.headers.get("Cache-Control")).toBe("no-store");
      expect(db.batches).toEqual([]);
    }
    const response = await post(JSON.stringify({ parts: broken }), env());
    expect(((await response.json()) as { error: string }).error).toMatch(/^parts\[1\]: bbox/);
  });

  it("GET ei ole yhteishaku", async () => {
    expect((await get("/v1/poi/batch")).status).toBe(404);
  });
});

describe("muut polut", () => {
  it("vastaa 404", async () => {
    expect((await get("/")).status).toBe(404);
    expect((await get("/v1/muu")).status).toBe(404);
  });
});

describe("/v1/places ja /admin/places", () => {
  const post = (path: string, payload: unknown, e: Env, auth = "Bearer salainen") =>
    worker.fetch(
      new Request(`https://poi.example${path}`, {
        method: "POST",
        headers: { Authorization: auth, "Content-Type": "application/json", "CF-Connecting-IP": "203.0.113.7" },
        body: JSON.stringify(payload),
      }),
      e,
    );
  const search = (query: string, e: Env) =>
    worker.fetch(new Request(`https://poi.example/v1/places${query}`, { headers: { "CF-Connecting-IP": "203.0.113.7" } }), e);
  const konekorjaamo = {
    id: "37f9b45c-9227-4fa9-ad8d-9fae83427804",
    name: "Askolan Auto- ja Konekorjaamo",
    street: "Teollisuustie 3",
    postcode: "07230",
    locality: "Askola",
    lat: 60.50148,
    lon: 25.56598,
    conf: 0.95,
  };

  it("tuonti rajapinnan kautta ja haku koko nimellä", async () => {
    const e = env({ DB: sqliteD1() });
    const begin = (await (await post("/admin/places/begin", { release: "2026-10-21.0" }, e)).json()) as { gen: number };
    expect((await post("/admin/places/import", { gen: begin.gen, rows: [konekorjaamo] }, e)).status).toBe(200);
    expect((await post("/admin/places/swap", { gen: begin.gen, release: "2026-10-21.0" }, e)).status).toBe(200);
    const again = await post("/admin/places/swap", { gen: begin.gen, release: "2026-10-21.0" }, e);
    expect(again.status).toBe(200);
    expect(await again.json()).toEqual({ ok: true, alreadySwapped: true });
    const status = await (await post("/admin/places/status", {}, e)).json();
    expect(status).toMatchObject({ active: begin.gen, release: "2026-10-21.0", rows: 1 });
    const response = await search("?q=Askolan%20auto%20ja%20kone%20oy&near=60.39,25.66", e);
    expect(response.status).toBe(200);
    expect(response.headers.get("Cache-Control")).toBe("public, max-age=3600");
    expect(await response.json()).toEqual({
      source: "Overture Maps Foundation (CDLA Permissive 2.0; Foursquare Apache 2.0)",
      items: [{ id: konekorjaamo.id, name: konekorjaamo.name, street: "Teollisuustie 3", postcode: "07230", locality: "Askola", lat: 60.50148, lon: 25.56598 }],
    });
  });

  it("vastaa 400 ilman hakusanaa, liian pitkällä hakusanalla tai virheellisellä sijainnilla", async () => {
    const e = env({ DB: sqliteD1() });
    expect((await search("", e)).status).toBe(400);
    expect((await search(`?q=${"a".repeat(101)}`, e)).status).toBe(400);
    expect((await search("?q=lidl&near=60.39", e)).status).toBe(400);
    expect((await search("?q=lidl&near=91,25", e)).status).toBe(400);
    expect((await search("?q=lidl&near=abc,def", e)).status).toBe(400);
  });

  it("pelkkä yhtiömuoto antaa tyhjän listan", async () => {
    const response = await search("?q=Oy", env({ DB: sqliteD1() }));
    expect(response.status).toBe(200);
    expect(await response.json()).toMatchObject({ items: [] });
  });

  it("oma hakuraja: täysi RATE_SEARCH vastaa 429 ennen kantaa, eikä RATE_IP kulu", async () => {
    const db = fakeDb();
    let ipCalls = 0;
    const e = env({
      DB: db,
      RATE_SEARCH: { limit: async () => ({ success: false }) },
      RATE_IP: { limit: async () => ({ success: (ipCalls++, true) }) },
    });
    expect((await search("?q=lidl", e)).status).toBe(429);
    expect(db.executed).toHaveLength(0);
    expect(ipCalls).toBe(0);
  });

  it("tuontirajapinta vaatii tunnuksen ja kelvolliset rivit", async () => {
    const db = fakeDb();
    expect((await post("/admin/places/begin", { release: "r1" }, env({ DB: db }), "Bearer vaara")).status).toBe(401);
    expect(db.executed).toHaveLength(0);
    const e = env({ DB: sqliteD1() });
    const { gen } = (await (await post("/admin/places/begin", { release: "r1" }, e)).json()) as { gen: number };
    const many = Array.from({ length: 501 }, () => konekorjaamo);
    expect((await post("/admin/places/import", { gen, rows: many }, e)).status).toBe(413);
    expect((await post("/admin/places/import", { gen, rows: [{ ...konekorjaamo, id: "x" }] }, e)).status).toBe(400);
    expect((await post("/admin/places/import", { gen, rows: [{ ...konekorjaamo, name: "" }] }, e)).status).toBe(400);
    expect((await post("/admin/places/import", { gen: gen + 5, rows: [konekorjaamo] }, e)).status).toBe(409);
    expect((await post("/admin/places/swap", { gen, release: "r1" }, e)).status).toBe(409);
    expect((await post("/admin/places/prune", {}, e)).status).toBe(200);
  });
});
