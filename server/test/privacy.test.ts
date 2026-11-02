import { describe, expect, it } from "vitest";
import worker from "../src/index";
import type { Env } from "../src/env";

const env = {} as unknown as Env;
const get = (path: string, headers: Record<string, string> = {}, method = "GET") =>
  worker.fetch(new Request(`https://poi.example${path}`, { headers, method }), env);

describe("GET /privacy", () => {
  it.each([
    ["fi", "tietosuojaseloste"],
    ["sv", "integritetspolicy"],
    ["en", "privacy policy"],
  ])("kieli %s", async (lang, title) => {
    const response = await get(`/privacy?lang=${lang}`);
    const html = await response.text();

    expect(response.status).toBe(200);
    expect(response.headers.get("Content-Type")).toContain("text/html");
    expect(response.headers.get("Content-Language")).toBe(lang);
    expect(html).toContain(`<html lang="${lang}">`);
    expect(html).toContain(title);
    expect(html).toContain("jrs8205");
    expect(html).toContain("mailto:jrs8205@pm.me");
  });

  it.each(["fi", "sv", "en"])("kieli %s kertoo samat palvelut ja ehdot", async (lang) => {
    const html = await (await get(`/privacy?lang=${lang}`)).text();

    for (const expected of [
      "valhalla1.openstreetmap.de",
      "routing.openstreetmap.de",
      "tiles.openfreemap.org",
      "poi.jrs8205.com",
      "Digitransit",
      "photon.komoot.io",
      "Cloudflare",
      "Data Privacy Framework",
      "https://www.cloudflare.com/dpa/",
      "2026-11-01",
    ]) {
      expect(html, expected).toContain(expected);
    }
    expect(html).toMatch(/\b12\b/);
    expect(html.toLowerCase()).not.toContain("geoapify");
  });

  it.each([
    ["fi", ["pysyvä ilmoitus", "noin kilometrin", "kohdekortin", "osoitemuisti", "lisätiedot", "ilman yhteyttä laitteeseen", "poista ilmoitukset sitä ennen"]],
    ["sv", ["permanent avisering", "ungefär en kilometer", "körtiden för ett objekt", "adressminnet", "tilläggsuppgifter", "utan koppling till enheten", "radera rapporterna innan dess"]],
    ["en", ["ongoing notification", "about a kilometre", "driving time to a place", "saved addresses", "additional notes", "no link to the device", "delete your reports first"]],
  ])("kieli %s kertoo sijainnin käytön ja poiston rajat", async (lang, phrases) => {
    const html = await (await get(`/privacy?lang=${lang}`)).text();

    for (const phrase of phrases as string[]) expect(html, phrase).toContain(phrase);
  });

  it.each([
    ["fi", "enintään 7 vuorokautta"],
    ["sv", "högst 7 dygn"],
    ["en", "at most 7 days"],
  ])("kieli %s kertoo poistetun tunnuksen säilytyksen", async (lang, phrase) => {
    const html = await (await get(`/privacy?lang=${lang}`)).text();

    expect(html).toContain(phrase);
  });

  it("seloste ei väitä, ettei sijaintia käytetä taustalla", async () => {
    const fi = await (await get("/privacy?lang=fi")).text();
    const sv = await (await get("/privacy?lang=sv")).text();
    const en = await (await get("/privacy?lang=en")).text();

    expect(fi).not.toContain("eikä sijaintia seurata taustalla");
    expect(sv).not.toContain("platsen spåras inte i bakgrunden");
    expect(en).not.toContain("does not track location in the background");
    expect(fi).not.toContain("kaikki ilmoituksesi");
    expect(sv).not.toContain("alla dina rapporter");
    expect(en).not.toContain("all your reports");
  });

  it("sivulla on linkit muihin kieliin", async () => {
    const html = await (await get("/privacy?lang=fi")).text();

    expect(html).toContain('href="/privacy?lang=sv"');
    expect(html).toContain('href="/privacy?lang=en"');
  });

  it.each([
    ["sv-FI,sv;q=0.9,en;q=0.5", "sv"],
    ["fi", "fi"],
    ["de", "en"],
    ["fil-PH,fil;q=0.9", "en"],
    ["svenska", "en"],
  ])("kieli otsakkeesta %s on %s", async (header, lang) => {
    const response = await get("/privacy", { "Accept-Language": header });

    expect(response.headers.get("Content-Language")).toBe(lang);
    expect(response.headers.get("Vary")).toContain("Accept-Language");
  });

  it("ilman kieltä ja tuntematon kieli ovat englantia", async () => {
    expect((await get("/privacy")).headers.get("Content-Language")).toBe("en");
    expect((await get("/privacy?lang=<script>")).headers.get("Content-Language")).toBe("en");
  });

  it("parametri voittaa otsakkeen", async () => {
    const response = await get("/privacy?lang=fi", { "Accept-Language": "sv" });

    expect(response.headers.get("Content-Language")).toBe("fi");
  });

  it("ei skriptejä, evästeitä eikä ulkoisia resursseja", async () => {
    const response = await get("/privacy?lang=fi");
    const html = await response.text();

    expect(html).not.toMatch(/<script|<img|<link|<iframe|\ssrc=/i);
    expect(response.headers.get("Set-Cookie")).toBeNull();
    expect(response.headers.get("Content-Security-Policy")).toContain("default-src 'none'");
    expect(response.headers.get("Cache-Control")).toBe("public, max-age=3600");
  });

  it("toimii ilman kantaa ja rajoittimia", async () => {
    expect((await get("/privacy")).status).toBe(200);
  });

  it("muu metodi ja polku eivät avaa sivua", async () => {
    const other = { ...env, KV: { get: async () => null } } as unknown as Env;
    const post = await worker.fetch(new Request("https://poi.example/privacy", { method: "POST" }), other);
    const sub = await worker.fetch(new Request("https://poi.example/privacy/x"), other);

    expect(post.status).toBe(404);
    expect(sub.status).toBe(404);
  });
});
