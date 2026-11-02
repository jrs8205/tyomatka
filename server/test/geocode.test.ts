import { afterEach, describe, expect, it, vi } from "vitest";
import worker from "../src/index";
import type { Env } from "../src/env";

const KEY = "salainen-avain";
const BODY = '{"features":[{"id":"oa:1"}]}';

type Upstream = (url: string, init: RequestInit) => Promise<Response> | Response;

const calls: Array<{ url: URL; init: RequestInit }> = [];

function stubUpstream(answer: Upstream = () => new Response(BODY, { status: 200 })) {
  calls.length = 0;
  vi.stubGlobal("fetch", async (url: string, init: RequestInit) => {
    calls.push({ url: new URL(url), init });
    return answer(url, init);
  });
}

afterEach(() => vi.unstubAllGlobals());

function env(overrides: Partial<Record<keyof Env, unknown>> = {}) {
  const open = { limit: async () => ({ success: true }) };
  return {
    KV: { get: async () => null },
    RATE_IP: open,
    RATE_GLOBAL: open,
    RATE_REPORT: open,
    RATE_SEARCH: open,
    RATE_GEOCODE_IP: open,
    RATE_GEOCODE: open,
    DIGITRANSIT_KEY: KEY,
    ...overrides,
  } as unknown as Env;
}

const get = (path: string, e = env(), init: RequestInit = {}) =>
  worker.fetch(
    new Request(`https://poi.example/v1/geocode/${path}`, {
      ...init,
      headers: { "CF-Connecting-IP": "203.0.113.7", "User-Agent": "kayttajan-laite", Cookie: "a=b", ...init.headers },
    }),
    e,
  );

describe("GET /v1/geocode", () => {
  it.each(["autocomplete?text=Kaivokatu", "search?text=Kaivokatu", "reverse?point.lat=60.1&point.lon=24.9"])(
    "välittää haun %s Digitransitille ja palauttaa vastauksen",
    async (path) => {
      stubUpstream();
      const response = await get(path);

      expect(response.status).toBe(200);
      expect(await response.text()).toBe(BODY);
      expect(calls).toHaveLength(1);
      expect(calls[0].url.origin + calls[0].url.pathname).toBe(`https://api.digitransit.fi/geocoding/v1/${path.split("?")[0]}`);
    },
  );

  it("avain kulkee vain Digitransitille otsakkeessa, ei vastauksessa", async () => {
    stubUpstream();
    const response = await get("autocomplete?text=Kaivokatu");

    const headers = new Headers(calls[0].init.headers);
    expect(headers.get("digitransit-subscription-key")).toBe(KEY);
    expect(calls[0].url.search).not.toContain(KEY);
    expect(await response.text()).not.toContain(KEY);
    expect([...response.headers.values()].join()).not.toContain(KEY);
  });

  it("käyttäjän osoite ja otsakkeet eivät välity", async () => {
    stubUpstream();
    await get("autocomplete?text=Kaivokatu");

    const headers = new Headers(calls[0].init.headers);
    expect(headers.get("CF-Connecting-IP")).toBeNull();
    expect(headers.get("X-Forwarded-For")).toBeNull();
    expect(headers.get("Cookie")).toBeNull();
    expect(headers.get("User-Agent")).toContain("Tyomatka");
  });

  it("vain tunnetut parametrit välitetään", async () => {
    stubUpstream();
    const params = new URLSearchParams({
      text: "Kaivokatu 1",
      size: "8",
      layers: "address,street,venue",
      lang: "fi",
      "boundary.rect.min_lat": "60",
      "boundary.rect.max_lat": "61",
      "boundary.rect.min_lon": "24",
      "boundary.rect.max_lon": "25",
      "focus.point.lat": "60.1",
      "focus.point.lon": "24.9",
      "digitransit-subscription-key": "vieras",
      api_key: "vieras",
      "sources": "osm",
    });
    await get(`search?${params}`);

    const forwarded = Object.fromEntries(calls[0].url.searchParams);
    expect(forwarded).toEqual({
      text: "Kaivokatu 1",
      size: "8",
      layers: "address,street,venue",
      lang: "fi",
      "boundary.rect.min_lat": "60",
      "boundary.rect.max_lat": "61",
      "boundary.rect.min_lon": "24",
      "boundary.rect.max_lon": "25",
      "focus.point.lat": "60.1",
      "focus.point.lon": "24.9",
    });
  });

  it("toistettu parametri ei monistu", async () => {
    stubUpstream();
    await get("search?text=eka&text=toka");

    expect(calls[0].url.searchParams.getAll("text")).toEqual(["eka"]);
  });

  it("vastaus on JSONia eikä jää välimuistiin", async () => {
    stubUpstream(() => new Response(BODY, { status: 200, headers: { "Set-Cookie": "x=y", "Content-Type": "text/html" } }));
    const response = await get("search?text=Kaivokatu");

    expect(response.headers.get("Content-Type")).toContain("application/json");
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    expect(response.headers.get("Set-Cookie")).toBeNull();
  });

  it.each([
    ["tuntematon reitti", "place?text=a"],
    ["alipolku", "foo/search?text=a"],
    ["puolikas piste", "reverse?point.lat=60.1"],
    ["murtoluku kokona", "search?text=a&size=1.5"],
    ["hakusanaton haku", "search?size=8"],
    ["tyhjä hakusana", "autocomplete?text=%20"],
    ["koordinaatiton käänteishaku", "reverse?size=1"],
    ["liian pitkä hakusana", `search?text=${"a".repeat(201)}`],
    ["liian pitkä muu parametri", `search?text=a&layers=${"a".repeat(201)}`],
    ["liian suuri koko", "search?text=a&size=11"],
    ["nollakoko", "search?text=a&size=0"],
    ["koko ei ole luku", "search?text=a&size=x"],
  ])("%s vastaa 400 ilman pyyntöä Digitransitille", async (_name, path) => {
    stubUpstream();
    const response = await get(path);

    expect(response.status).toBe(400);
    expect(calls).toHaveLength(0);
  });

  it("200 merkin hakusana ja koko 10 kelpaavat", async () => {
    stubUpstream();
    const response = await get(`search?text=${"a".repeat(200)}&size=10`);

    expect(response.status).toBe(200);
  });

  it("muu metodi ei välity", async () => {
    stubUpstream();
    const response = await get("search?text=a", env(), { method: "POST" });

    expect(response.status).toBe(404);
    expect(calls).toHaveLength(0);
  });

  it.each([429, 500, 503, 401, 404])("Digitransitin %i on 503 ilman sen vastausta", async (status) => {
    stubUpstream(() => new Response(`virhe ${KEY}`, { status }));
    const response = await get("search?text=a");

    expect(response.status).toBe(503);
    expect(await response.text()).not.toContain(KEY);
  });

  it.each([301, 302, 307, 308])("uudelleenohjausta %i ei seurata, joten avain ei lähde muualle", async (status) => {
    stubUpstream((_url, init) =>
      init.redirect === "manual"
        ? new Response(null, { status, headers: { Location: "https://muu.example/" } })
        : new Response(BODY, { status: 200 }),
    );
    const response = await get("search?text=a");

    expect(response.status).toBe(503);
    expect(calls).toHaveLength(1);
    expect(calls[0].url.hostname).toBe("api.digitransit.fi");
  });

  it("verkkovirhe ja aikakatkaisu ovat 503", async () => {
    stubUpstream(() => {
      throw new DOMException("aika loppui", "TimeoutError");
    });
    expect((await get("search?text=a")).status).toBe(503);

    stubUpstream(() => {
      throw new TypeError("verkko");
    });
    expect((await get("search?text=a")).status).toBe(503);
  });

  it("pyynnöllä on viiden sekunnin aikaraja", async () => {
    const spy = vi.spyOn(AbortSignal, "timeout");
    stubUpstream();
    await get("search?text=a");

    expect(spy).toHaveBeenCalledWith(5_000);
    expect(calls[0].init.signal).toBeInstanceOf(AbortSignal);
    spy.mockRestore();
  });

  it("puuttuva salaisuus on 503 ilman pyyntöä", async () => {
    stubUpstream();
    const response = await get("search?text=a", env({ DIGITRANSIT_KEY: undefined }));

    expect(response.status).toBe(503);
    expect(calls).toHaveLength(0);
  });

  it("osoiteraja käyttää omaa sidosta eikä kuluta muita", async () => {
    stubUpstream();
    let searchCalls = 0;
    let ipCalls = 0;
    const response = await get(
      "search?text=a",
      env({
        RATE_GEOCODE_IP: { limit: async () => ({ success: false }) },
        RATE_SEARCH: { limit: async () => ({ success: (searchCalls++, true) }) },
        RATE_IP: { limit: async () => ({ success: (ipCalls++, true) }) },
      }),
    );

    expect(response.status).toBe(429);
    expect(calls).toHaveLength(0);
    expect(searchCalls + ipCalls).toBe(0);
  });

  it("koko palvelun raja vastaa 429 ilman pyyntöä", async () => {
    stubUpstream();
    const response = await get("search?text=a", env({ RATE_GEOCODE: { limit: async () => ({ success: false }) } }));

    expect(response.status).toBe(429);
    expect(calls).toHaveLength(0);
  });

  it("pysäytys koskee myös hakua", async () => {
    stubUpstream();
    expect((await get("search?text=a", env({ KV: { get: async () => "1" } }))).status).toBe(503);
    expect(calls).toHaveLength(0);
  });

  it("haku ei kuluta karttakohteiden yhteistä rajaa", async () => {
    stubUpstream();
    let globalCalls = 0;
    const response = await get("search?text=a", env({ RATE_GLOBAL: { limit: async () => ({ success: (globalCalls++, false) }) } }));

    expect(response.status).toBe(200);
    expect(globalCalls).toBe(0);
  });

  it("virheellinen pyyntö ei kuluta Digitransitin rajaa", async () => {
    stubUpstream();
    let geocodeCalls = 0;
    await get("search?size=8", env({ RATE_GEOCODE: { limit: async () => ({ success: (geocodeCalls++, true) }) } }));

    expect(geocodeCalls).toBe(0);
  });
});
