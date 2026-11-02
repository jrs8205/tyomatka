import { describe, expect, it } from "vitest";
import {
  FACTS_SQL,
  IMPORT_SQL,
  MAX_IMPORT_ROWS,
  authorized,
  factsParam,
  importParam,
  swapStatements,
  validateFacts,
  validateImport,
} from "../src/admin";
import { cellOf } from "../src/query";

const request = (auth?: string) =>
  new Request("https://poi.example/admin/import", { method: "POST", headers: auth ? { Authorization: auth } : {} });

const row = (overrides: Record<string, unknown> = {}) => ({
  id: "n123",
  cat: "fuel",
  lat: 60.3,
  lon: 24.6,
  name: "Neste Truck",
  hgv: 1,
  tags: { "fuel:hgv_diesel": "yes" },
  ...overrides,
});

describe("authorized", () => {
  it("hyväksyy oikean Bearer-tunnuksen", () => {
    expect(authorized(request("Bearer salainen"), "salainen")).toBe(true);
  });

  it("hylkää väärän, puuttuvan ja tyhjän tunnuksen", () => {
    expect(authorized(request("Bearer vaara"), "salainen")).toBe(false);
    expect(authorized(request(), "salainen")).toBe(false);
    expect(authorized(request("Bearer "), "")).toBe(false);
    expect(authorized(request("Basic salainen"), "salainen")).toBe(false);
  });
});

describe("validateImport", () => {
  it("hyväksyy kelvollisen rungon", () => {
    const body = validateImport({ country: "FI", rows: [row(), row({ id: "w4", cat: "gas", name: undefined })] });
    expect("error" in body).toBe(false);
    if (!("error" in body)) expect(body.rows).toHaveLength(2);
  });

  it("hyväksyy Fintrafficin latauspisteen tunnisteen mutta ei kertatuonnin omaa asemaa", () => {
    expect(validateImport({ country: "FI", rows: [row({ id: "c-FI911-70d5121e-0308-11f0-9e08-42010aa40043", cat: "charging" })] })).not.toHaveProperty("error");
    expect(validateImport({ country: "FI", rows: [row({ id: "c-FI-1", cat: "charging" })] })).toHaveProperty("error");
    expect(validateImport({ country: "FI", rows: [row({ id: "k-neste-2605" })] })).toHaveProperty("error");
  });

  it("hylkää virheelliset rivit ja maan", () => {
    expect(validateImport({ country: "FI", rows: [row({ id: "x1" })] })).toHaveProperty("error");
    expect(validateImport({ country: "FI", rows: [row({ lat: 95 })] })).toHaveProperty("error");
    expect(validateImport({ country: "FI", rows: [row({ cat: "foo" })] })).toHaveProperty("error");
    expect(validateImport({ country: "FI", rows: [row({ tags: "x" })] })).toHaveProperty("error");
    expect(validateImport({ country: "fin", rows: [row()] })).toHaveProperty("error");
    expect(validateImport({ country: "FI" })).toHaveProperty("error");
    expect(validateImport(null)).toHaveProperty("error");
  });

  it("hylkää yli 500 riviä", () => {
    const rows = Array.from({ length: MAX_IMPORT_ROWS + 1 }, (_, i) => row({ id: `n${i}` }));
    expect(validateImport({ country: "FI", rows })).toHaveProperty("error");
    expect(validateImport({ country: "FI", rows: rows.slice(0, MAX_IMPORT_ROWS) })).not.toHaveProperty("error");
  });
});

describe("tuonti ja vaihto", () => {
  it("tuontilause kirjoittaa välitauluun json_each-kutsulla", () => {
    expect(IMPORT_SQL).toContain("INSERT OR REPLACE INTO poi_staging");
    expect(IMPORT_SQL).toContain("FROM json_each(?1)");
  });

  it("tuontiparametri lisää ruudun, maan ja päivityshetken ja sarjallistaa tagit", () => {
    const body = validateImport({ country: "FI", rows: [row()] });
    if ("error" in body) throw new Error(body.error);
    const rows = JSON.parse(importParam(body, "2026-10-22T02:00:00Z"));
    expect(rows[0].cell).toBe(cellOf(60.3, 24.6));
    expect(rows[0].country).toBe("FI");
    expect(rows[0].updated).toBe("2026-10-22T02:00:00Z");
    expect(rows[0].brand).toBeNull();
    expect(rows[0].tags).toBe('{"fuel:hgv_diesel":"yes"}');
  });

  it("vaihto korvaa maan rivit yhdessä erässä ja säilyttää rajan kohteet INSERT OR REPLACE -lauseella", () => {
    const statements = swapStatements("FI", "2026-10-20", 1234, "2026-10-22T02:00:00Z");
    expect(statements).toHaveLength(4);
    expect(statements[0].sql).toContain("DELETE FROM poi WHERE country = ?");
    expect(statements[1].sql).toContain("INSERT OR REPLACE INTO poi");
    expect(statements[1].sql).toContain("FROM poi_staging WHERE country = ?");
    expect(statements[2].sql).toContain("DELETE FROM poi_staging WHERE country = ?");
    expect(statements[3].sql).toContain("INSERT OR REPLACE INTO meta");
    expect(statements[3].params).toEqual(["FI", "2026-10-20", 1234, "2026-10-22T02:00:00Z"]);
  });
});

describe("validateFacts", () => {
  const fact = (overrides: Record<string, unknown> = {}) => ({
    id: "n123",
    cat: "fuel",
    lat: 60.3,
    lon: 24.6,
    linked: 1,
    facts: { "k:date": "2026-10-22", "k:fuels": "diesel=Neste Futura Diesel;hvo=Neste MY", "k:hgv_lane": "yes" },
    ...overrides,
  });

  it("hyväksyy liitetyn ja oman aseman sallituilla avaimilla", () => {
    const own = fact({ id: "k-neste-2605", linked: 0, name: "Neste Kylmäkoski", brand: "Neste", addr: "Tie 1" });
    expect(validateFacts({ rows: [fact(), own] })).toEqual({ rows: [fact(), own] });
  });

  it("hylkää henkilö- ja ruokalistatiedot sekä tuntemattomat avaimet", () => {
    for (const key of ["k:phone", "k:email", "k:menu", "phone", "k:price"]) {
      expect(validateFacts({ rows: [fact({ facts: { "k:date": "2026-10-22", [key]: "x" } })] })).toHaveProperty("error");
    }
  });

  it("hylkää yli 200 merkin arvon, väärän tilan ja väärän tunnisteen", () => {
    expect(validateFacts({ rows: [fact({ facts: { "k:restaurant": "x".repeat(201) } })] })).toHaveProperty("error");
    expect(validateFacts({ rows: [fact({ facts: { "k:status": "open" } })] })).toHaveProperty("error");
    expect(validateFacts({ rows: [fact({ facts: { "k:hgv_lane": "no" } })] })).toHaveProperty("error");
    expect(validateFacts({ rows: [fact({ id: "k-neste-2605" })] })).toHaveProperty("error");
    expect(validateFacts({ rows: [fact({ id: "n1", linked: 0 })] })).toHaveProperty("error");
    expect(validateFacts({ rows: [fact({ cat: "rest" })] })).toHaveProperty("error");
  });

  it("hylkää yli 500 rivin erän", () => {
    expect(validateFacts({ rows: Array.from({ length: 501 }, (_, i) => fact({ id: `n${i}` })) })).toHaveProperty("error");
  });

  it("kirjoittaa faktat soluineen yhdellä parametrilla", () => {
    const valid = validateFacts({ rows: [fact()] });
    if ("error" in valid) throw new Error(valid.error);
    const [row] = JSON.parse(factsParam(valid, "2026-10-23T05:00:00Z")) as Array<Record<string, unknown>>;
    expect(row).toMatchObject({ id: "n123", cat: "fuel", cell: cellOf(60.3, 24.6), linked: 1, updated: "2026-10-23T05:00:00Z" });
    expect(JSON.parse(row!.facts as string)).toEqual(fact().facts);
    expect(FACTS_SQL.match(/\?/g)).toHaveLength(1);
  });
});
