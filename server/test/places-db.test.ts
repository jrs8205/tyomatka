import { describe, expect, it } from "vitest";
import {
  type PlaceImportRow,
  PRUNE_LIMIT,
  placesBegin,
  placesImport,
  placesPrune,
  placesStatus,
  placesSwap,
  SEARCH_LIMITS,
  searchPlaces,
  tokens,
} from "../src/places";
import { sqliteD1 } from "./d1";

const KONEKORJAAMO: PlaceImportRow = {
  id: "37f9b45c-9227-4fa9-ad8d-9fae83427804",
  name: "Askolan Auto- ja Konekorjaamo",
  street: "Teollisuustie 3",
  postcode: "07230",
  locality: "Askola",
  lat: 60.50148,
  lon: 25.56598,
  conf: 0.95,
};

const uuid = (i: number) => `00000000-0000-4000-8000-${String(i).padStart(12, "0")}`;
const row = (i: number, name: string, locality = "Porvoo"): PlaceImportRow => ({ id: uuid(i), name, locality, lat: 60.39, lon: 25.66, conf: 0.9 });

function database() {
  const d1 = sqliteD1();
  return { d1, db: d1 as unknown as D1Database };
}

/** Koko tuonti: begin → erät → swap. */
async function load(db: D1Database, release: string, batches: PlaceImportRow[][], force = false) {
  const { gen } = await placesBegin(db);
  for (const rows of batches) await placesImport(db, { gen, rows });
  return { gen, swap: await placesSwap(db, gen, release, force) };
}

const search = async (db: D1Database, q: string, near: [number, number] | null = [60.39, 25.66]) =>
  (await searchPlaces(db, tokens(q), near)).map((p) => p.name);

/** Laskee valmistellut lauseet alun mukaan: hakusanan laskenta- ja ehdokaskyselyt. */
function counting(d1: ReturnType<typeof sqliteD1>) {
  const seen: string[] = [];
  const prepare = d1.prepare;
  d1.prepare = (sql: string) => {
    seen.push(sql);
    return prepare(sql);
  };
  return {
    counts: () => seen.filter((q) => q.startsWith("SELECT count(*) AS n FROM (SELECT 1 FROM place_word")).length,
    candidates: () => seen.filter((q) => q.startsWith("SELECT id, name, street")).length,
    reset: () => seen.splice(0),
  };
}

const wordRows = (d1: ReturnType<typeof sqliteD1>) => Number(d1.raw.prepare("SELECT count(*) AS n FROM place_word").get()!.n);

describe("yrityshaun kanta oikealla SQLitellä", () => {
  it("tuonti kahdessa erässä ja haku koko nimellä", async () => {
    const { db } = database();
    const { swap } = await load(db, "2026-10-21.0", [[row(1, "Askolan Kone Oy", "Askola")], [KONEKORJAAMO]]);
    expect(swap).toEqual({ kind: "swap", rows: 2 });
    expect((await search(db, "Askolan auto ja kone oy"))[0]).toBe("Askolan Auto- ja Konekorjaamo");
    expect(await placesStatus(db)).toMatchObject({ release: "2026-10-21.0", rows: 2, staging: null });
  });

  it("harvinaisin sana valitsee ehdokkaat: yleinen ensimmäinen sana ei pudota oikeaa yritystä", async () => {
    const { db } = database();
    const common = Array.from({ length: 2500 }, (_, i) => row(i + 10, `Auto ${i}`));
    const batches = [];
    for (let i = 0; i < common.length; i += 500) batches.push(common.slice(i, i + 500));
    batches.push([KONEKORJAAMO]); // viimeisenä: indeksin järjestyksessä yleisen sanan 2 000 ensimmäistä eivät sisällä sitä
    await load(db, "r1", batches);
    expect(await search(db, "auto askolan")).toEqual(["Askolan Auto- ja Konekorjaamo"]);
  });

  it("varahaku löytää yhtä sanaa vaille myös, kun puuttuva sana on harvinaisin", async () => {
    const { db } = database();
    await load(db, "r1", [[KONEKORJAAMO, row(2, "Askolan Kone Oy", "Askola")]]);
    expect(await search(db, "Askolan auto ja kone qqqq")).toEqual(["Askolan Auto- ja Konekorjaamo"]);
  });

  it("keskeneräinen tuonti ei muuta hakua, ja seuraava ajo siivoaa jäänteet", async () => {
    const { d1, db } = database();
    const first = await load(db, "r1", [[KONEKORJAAMO]]);
    const broken = await placesBegin(db);
    await placesImport(db, { gen: broken.gen, rows: [row(3, "Keskeneräinen Oy")] });
    expect(await search(db, "keskeneräinen")).toEqual([]);
    expect((await search(db, "konekorjaamo"))[0]).toBe("Askolan Auto- ja Konekorjaamo");
    const next = await placesBegin(db);
    expect(next.gen).toBeGreaterThan(broken.gen);
    expect(next.gen).toBeGreaterThan(first.gen);
    await placesImport(db, { gen: next.gen, rows: [KONEKORJAAMO, row(4, "Uusi Oy")] });
    expect((await placesSwap(db, next.gen, "r2", false)).kind).toBe("swap");
    let deleted = (await placesPrune(db)).deleted;
    expect(deleted).toBeGreaterThan(0);
    while (deleted > 0) deleted = (await placesPrune(db)).deleted;
    const gens = d1.raw.prepare("SELECT DISTINCT gen FROM place UNION SELECT DISTINCT gen FROM place_word").all().map((r) => r.gen);
    expect(gens).toEqual([next.gen]);
    expect(await search(db, "uusi")).toEqual(["Uusi Oy"]);
  });

  it("prune poistaa enintään rajan verran kerralla eikä koske aktiiviseen", async () => {
    const { db } = database();
    expect(PRUNE_LIMIT).toBe(20_000);
    await load(db, "r1", [[row(1, "Vanha Yksi"), row(2, "Vanha Kaksi")]]);
    await load(db, "r2", [[KONEKORJAAMO]]);
    // Vanhassa sukupolvessa 2 paikkaa ja 6 sanariviä (vanha, yksi/kaksi, porvoo).
    expect((await placesPrune(db, 5)).deleted).toBe(5);
    expect((await placesPrune(db, 5)).deleted).toBe(3);
    expect((await placesPrune(db, 5)).deleted).toBe(0);
    expect((await search(db, "konekorjaamo"))[0]).toBe("Askolan Auto- ja Konekorjaamo");
  });

  it("tyhjä sukupolvi ei vaihdu", async () => {
    const { db } = database();
    await load(db, "r1", [[KONEKORJAAMO]]);
    const { gen } = await placesBegin(db);
    expect((await placesSwap(db, gen, "r2", false)).kind).toBe("conflict");
    expect((await search(db, "konekorjaamo"))[0]).toBe("Askolan Auto- ja Konekorjaamo");
  });

  it("alle puolet edellisestä vaatii pakotuksen", async () => {
    const { db } = database();
    await load(db, "r1", [[KONEKORJAAMO, row(2, "A Oy"), row(3, "B Oy")]]);
    const { gen } = await placesBegin(db);
    await placesImport(db, { gen, rows: [row(4, "C Oy")] });
    expect((await placesSwap(db, gen, "r2", false)).kind).toBe("conflict");
    expect(await placesSwap(db, gen, "r2", true)).toEqual({ kind: "swap", rows: 1 });
  });

  it("vanhentunut sukupolvi ei kelpaa tuontiin eikä vaihtoon", async () => {
    const { db } = database();
    const old = await placesBegin(db);
    await placesBegin(db);
    await expect(placesImport(db, { gen: old.gen, rows: [KONEKORJAAMO] })).rejects.toThrow(/sukupolvi/);
    expect((await placesSwap(db, old.gen, "r1", true)).kind).toBe("conflict");
  });

  it("ilman aktiivista sukupolvea haku on tyhjä", async () => {
    const { db } = database();
    expect(await search(db, "askolan")).toEqual([]);
  });

  it("vaihdon uusinta onnistuneen vaihdon jälkeen kuitataan, ja siivous jatkuu", async () => {
    const { db } = database();
    await load(db, "r1", [[row(1, "Vanha Oy")]]);
    const { gen, swap } = await load(db, "r2", [[KONEKORJAAMO]]);
    expect(swap.kind).toBe("swap");
    expect(await placesSwap(db, gen, "r2", false)).toEqual({ kind: "already" });
    expect((await placesPrune(db)).deleted).toBeGreaterThan(0);
  });

  it("siivous säilyttää tuonnissa olevan sukupolven ja poistaa välissä hylätyn", async () => {
    const { d1, db } = database();
    const first = await load(db, "r1", [[row(1, "Eka Oy")]]);
    const abandoned = await placesBegin(db);
    await placesImport(db, { gen: abandoned.gen, rows: [row(2, "Hylätty Oy")] });
    const staging = await placesBegin(db);
    await placesImport(db, { gen: staging.gen, rows: [KONEKORJAAMO, row(3, "Kolmas Oy")] });
    while ((await placesPrune(db)).deleted > 0);
    const gens = d1.raw.prepare("SELECT DISTINCT gen FROM place ORDER BY gen").all().map((r) => r.gen);
    expect(gens).toEqual([first.gen, staging.gen]);
    expect(await placesSwap(db, staging.gen, "r2", false)).toEqual({ kind: "swap", rows: 2 });
  });

  it("yli kuusi tuntia vanha keskeneräinen tuonti perutaan siivouksessa, tuore säilyy", async () => {
    const { d1, db } = database();
    const first = await load(db, "r1", [[KONEKORJAAMO]]);
    const broken = await placesBegin(db);
    await placesImport(db, { gen: broken.gen, rows: [row(3, "Keskeneräinen Oy")] });
    while ((await placesPrune(db)).deleted > 0);
    expect((await placesStatus(db)).staging).toBe(broken.gen);
    const later = Date.now() + 7 * 3600_000;
    while ((await placesPrune(db, PRUNE_LIMIT, later)).deleted > 0);
    expect((await placesStatus(db)).staging).toBeNull();
    const gens = d1.raw.prepare("SELECT DISTINCT gen FROM place UNION SELECT DISTINCT gen FROM place_word").all().map((r) => r.gen);
    expect(gens).toEqual([first.gen]);
    // Peruttuun tuontiin ei voi enää lisätä eikä sitä voi vaihtaa.
    await expect(placesImport(db, { gen: broken.gen, rows: [row(4, "Myöhässä Oy")] })).rejects.toThrow(/sukupolvi/);
    expect((await placesSwap(db, broken.gen, "r1", true)).kind).toBe("conflict");
    expect((await search(db, "konekorjaamo"))[0]).toBe("Askolan Auto- ja Konekorjaamo");
  });

  it("saman erän uusinta ei kahdenna sanarivejä", async () => {
    const { d1, db } = database();
    const { gen } = await placesBegin(db);
    await placesImport(db, { gen, rows: [KONEKORJAAMO] });
    const once = wordRows(d1);
    await placesImport(db, { gen, rows: [KONEKORJAAMO] });
    expect(wordRows(d1)).toBe(once);
  });

  it("monisanainen haku laskee enintään neljä sanaa ja hakee toisen ehdokasjoukon vain tarvittaessa", async () => {
    const d1 = sqliteD1();
    const db = d1 as unknown as D1Database;
    await load(db, "r1", [[KONEKORJAAMO, row(2, "Askolan Kone Oy", "Askola")]]);
    const spy = counting(d1);
    await searchPlaces(db, tokens("a b c d e f g h i j k l"), null);
    expect(spy.counts()).toBeLessThanOrEqual(4);
    spy.reset();
    expect((await searchPlaces(db, tokens("Askolan auto ja kone"), null)).map((p) => p.name)).toEqual(["Askolan Auto- ja Konekorjaamo"]);
    expect(spy.candidates()).toBe(1);
    spy.reset();
    expect((await searchPlaces(db, tokens("Askolan auto ja kone qqqq"), null)).map((p) => p.name)).toEqual(["Askolan Auto- ja Konekorjaamo"]);
    // Varahaku samassa kyselyssä: ehdokkaat kahden harvinaisimman sanan kautta.
    expect(spy.candidates()).toBe(1);
  });

  it("yli 2 000 ehdokasta: muut sanat rajaavat ennen katkaisua", async () => {
    const { db } = database();
    const autos = Array.from({ length: 2500 }, (_, i) => row(i + 10, `Auto ${i}`));
    const kones = Array.from({ length: 2500 }, (_, i) => row(i + 3000, `Kone ${i}`));
    const both = [...autos, ...kones, row(9000, "Auto Kone")];
    const batches = [];
    for (let i = 0; i < both.length; i += 500) batches.push(both.slice(i, i + 500));
    await load(db, "r1", batches);
    expect(await search(db, "auto kone")).toEqual(["Auto Kone"]);
  });

  it("yli 2 000 ehdokasta: lähin yritys ennen katkaisua", async () => {
    const { db } = database();
    const far = Array.from({ length: 2500 }, (_, i) => ({ ...row(i + 10, `Auto ${i}`), lat: 65.0, lon: 25.5 }));
    const batches = [];
    for (let i = 0; i < far.length; i += 500) batches.push(far.slice(i, i + 500));
    batches.push([row(9000, "Auto Lähellä")]); // viimeisenä: suurin tunniste, hakemiston järjestyksessä viimeinen
    await load(db, "r1", batches);
    expect((await search(db, "auto", [60.39, 25.66]))[0]).toBe("Auto Lähellä");
  });

  it("yli 2 000 paikkakunnalla osuvaa: nimellä osuva ei putoa ennen katkaisua", async () => {
    const { db } = database();
    const town = Array.from({ length: 2500 }, (_, i) => row(i + 10, `Yritys ${i}`, "Porvoo"));
    const batches = [];
    for (let i = 0; i < town.length; i += 500) batches.push(town.slice(i, i + 500));
    batches.push([{ ...row(9000, "Porvoon Kahvila", "Helsinki"), lat: 60.17, lon: 24.94 }]);
    await load(db, "r1", batches);
    expect(await search(db, "porvoo", [60.39, 25.66])).toEqual(["Porvoon Kahvila"]);
  });

  it("rajaan osuneet sanat lasketaan uudelleen: yleisempi pitkä sana ei valitse ehdokkaita", async () => {
    const { db } = database();
    // Pienet rajat: laskenta 5, uudelleenlaskenta 50, ehdokasjoukon pohja 20 riviä.
    const limits = { ...SEARCH_LIMITS, count: 5, recount: 50, driving: 20 };
    const town = Array.from({ length: 40 }, (_, i) => row(i + 10, `Yritys ${i}`, "Helsinki"));
    const barbers = Array.from({ length: 8 }, (_, i) => row(i + 100, `Parturi ${i}`, "Espoo"));
    await load(db, "r1", [[...town, ...barbers, row(900, "Parturi Kallio", "Helsinki")]]);
    const found = await searchPlaces(db, tokens("parturi helsinki"), [60.39, 25.66], limits);
    expect(found.map((p) => p.name)).toEqual(["Parturi Kallio"]);
  });

  it("toistuva hakusana painaa rajauksessa kuten lopullisessa järjestyksessä", async () => {
    const { db } = database();
    const limits = { ...SEARCH_LIMITS, candidates: 20 };
    const autos = Array.from({ length: 10 }, (_, i) => ({ ...row(i + 10, `Auto ${i}`), lat: 65.0, lon: 25.5 }));
    const kones = Array.from({ length: 30 }, (_, i) => row(i + 100, `Kone ${i}`));
    await load(db, "r1", [[...autos, ...kones]]);
    const found = await searchPlaces(db, tokens("auto auto kone"), [60.39, 25.66], limits);
    expect(found.length).toBe(8);
    expect(found.every((p) => p.name.startsWith("Auto"))).toBe(true);
  });

  it("perutun tuonnin sukupolvea ei anneta uudelleen", async () => {
    const { db } = database();
    await load(db, "r1", [[KONEKORJAAMO]]);
    const broken = await placesBegin(db);
    await placesImport(db, { gen: broken.gen, rows: [row(3, "Keskeneräinen Oy")] });
    const later = Date.now() + 7 * 3600_000;
    while ((await placesPrune(db, PRUNE_LIMIT, later)).deleted > 0);
    const next = await placesBegin(db, later);
    expect(next.gen).toBeGreaterThan(broken.gen);
    // Vanhan ajon myöhästynyt erä ei kelpaa uuteen tuontiin.
    await expect(placesImport(db, { gen: broken.gen, rows: [row(4, "Myöhässä Oy")] })).rejects.toThrow(/sukupolvi/);
  });

  it("sukupolven vaihto haun aikana ei tyhjennä tulosta", async () => {
    const d1 = sqliteD1();
    const db = d1 as unknown as D1Database;
    await load(db, "r1", [[KONEKORJAAMO]]);
    // Vaihto ja siivous osuvat sanojen laskennan ja ehdokashaun väliin.
    const batch = d1.batch;
    let fired = false;
    d1.batch = async (statements) => {
      const results = await batch(statements);
      if (!fired) {
        fired = true;
        await load(db, "r2", [[KONEKORJAAMO, row(2, "Toinen Oy")]]);
        while ((await placesPrune(db)).deleted > 0);
      }
      return results;
    };
    expect(await search(db, "konekorjaamo")).toEqual(["Askolan Auto- ja Konekorjaamo"]);
    expect(fired).toBe(true);
  });
});
