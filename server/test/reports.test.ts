import { describe, expect, it } from "vitest";
import { DAILY_LIMIT, LATEST_SQL, REPORT_FIELDS, latestParams, validateReport } from "../src/reports";
import { CELL_DEG, LON_CELLS, cellRanges } from "../src/query";

const DEVICE = "0f8fad5b-d9cb-469f-a165-70867728950e";
const RID = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
const report = (overrides: Record<string, unknown> = {}) => ({ poi: "n1", field: "closed", value: "yes", rid: RID, ...overrides });

describe("validateReport", () => {
  it("hyväksyy sulkemisilmoituksen OSM-kohteelle", () => {
    expect(validateReport(report(), DEVICE)).toEqual({ poi: "n1", field: "closed", value: "yes", rid: RID });
  });

  it("hyväksyy AFIR- ja kertatuontikohteen tunnisteet", () => {
    expect(validateReport(report({ poi: "c-FITSL-46e8271d-b83e" }), DEVICE)).not.toHaveProperty("error");
    expect(validateReport(report({ poi: "k-neste-2605" }), DEVICE)).not.toHaveProperty("error");
  });

  it("hylkää tuntemattoman kentän ja arvon", () => {
    expect(validateReport(report({ field: "phone", value: "0401234567" }), DEVICE)).toHaveProperty("error");
    expect(validateReport(report({ value: "maybe" }), DEVICE)).toHaveProperty("error");
  });

  it("hylkää vapaan tekstin ja väärät lukumuodot", () => {
    expect(validateReport(report({ field: "hours:station", value: "auki aina" }), DEVICE)).toHaveProperty("error");
    expect(validateReport(report({ field: "maxheight", value: "1e9" }), DEVICE)).toHaveProperty("error");
    expect(validateReport(report({ field: "price:diesel", value: "1,789 EUR" }), DEVICE)).toHaveProperty("error");
  });

  it("hylkää puuttuvan tai muun kuin UUID v4 -laitteen", () => {
    expect(validateReport(report(), null)).toHaveProperty("error");
    expect(validateReport(report(), "laite-1")).toHaveProperty("error");
    expect(validateReport(report(), "0f8fad5b-d9cb-169f-a165-70867728950e")).toHaveProperty("error");
  });

  it("hyväksyy ilmoitushetken (jonossa ollut tai laitteen vanha korjaus) enintään vuoden taakse", () => {
    const now = new Date("2026-10-23T12:00:00Z");
    expect(validateReport(report({ at: "2026-10-22T15:30:00Z" }), DEVICE, now)).toEqual({
      poi: "n1", field: "closed", value: "yes", rid: RID, at: "2026-10-22T15:30:00.000Z",
    });
    expect(validateReport(report({ at: "2025-10-24T00:00:00Z" }), DEVICE, now)).not.toHaveProperty("error");
    expect(validateReport(report({ at: "2025-10-22T00:00:00Z" }), DEVICE, now)).toHaveProperty("error");
    expect(validateReport(report({ at: "2026-10-22" }), DEVICE, now)).toHaveProperty("error");
    expect(validateReport(report({ at: "2026-02-30T10:00:00Z" }), DEVICE, now)).toHaveProperty("error");
    expect(validateReport(report({ at: 1792738800 }), DEVICE, now)).toHaveProperty("error");
  });

  it("puhelimen kello saa edistää vähän: tuleva hetki rajataan nykyhetkeen, kauempana tulevaisuudessa hylätään", () => {
    const now = new Date("2026-10-23T12:00:00Z");
    expect(validateReport(report({ at: "2026-10-23T12:04:00Z" }), DEVICE, now)).toHaveProperty("at", "2026-10-23T12:00:00.000Z");
    expect(validateReport(report({ at: "2026-10-23T12:06:00Z" }), DEVICE, now)).toHaveProperty("error");
  });

  it("hylkää tuntemattoman tunnisteen ja puuttuvan rid:n", () => {
    expect(validateReport(report({ poi: "x1" }), DEVICE)).toHaveProperty("error");
    expect(validateReport(report({ rid: undefined }), DEVICE)).toHaveProperty("error");
    expect(validateReport(report({ rid: "1" }), DEVICE)).toHaveProperty("error");
    expect(validateReport(null, DEVICE)).toHaveProperty("error");
  });
});

describe("REPORT_FIELDS", () => {
  const ok = (field: string, value: string) => REPORT_FIELDS[field]!(value);

  it("kyllä/ei-kentät", () => {
    for (const field of ["closed", "charging", "shop", "restaurant", "lunch", "truck_parking", "shower", "toilets", "sauna", "overnight"]) {
      expect(ok(field, "yes")).toBe(true);
      expect(ok(field, "no")).toBe(true);
      expect(ok(field, "1")).toBe(false);
    }
  });

  it("aukiolo: 24/7 tai päiväryhmät yhdellä välillä", () => {
    expect(ok("hours:station", "24/7")).toBe(true);
    expect(ok("hours:restaurant", "Mo-Fr 06:30-19:00; Sa 08:00-16:00")).toBe(true);
    expect(ok("hours:lunch", "Mo-Fr 10:30-14:00")).toBe(true);
    expect(ok("hours:shop", "Mo-Su 00:00-24:00")).toBe(true);
    expect(ok("hours:shop", "Mo-Fr 06:00-22:00; PH off")).toBe(false);
    expect(ok("hours:shop", "Mo-Fr 25:00-26:00")).toBe(false);
    expect(ok("hours:shop", "")).toBe(false);
  });

  it("polttoaineet lajeina tai none, ei kaksoiskappaleita", () => {
    expect(ok("fuels", "diesel;hvo")).toBe(true);
    expect(ok("fuels", "none")).toBe(true);
    expect(ok("fuels", "diesel;diesel")).toBe(false);
    expect(ok("fuels", "kerosiini")).toBe(false);
    expect(ok("fuels", "")).toBe(false);
  });

  it("AdBlue, pituus, rajat ja hinnat", () => {
    expect(ok("adblue", "canister")).toBe(true);
    expect(ok("adblue", "yes")).toBe(false);
    expect(ok("truck_length", "25.25")).toBe(true);
    expect(ok("truck_length", "20")).toBe(false);
    expect(ok("maxheight", "4.15")).toBe(true);
    expect(ok("maxweight", "0.4")).toBe(false);
    expect(ok("maxweight", "99")).toBe(true);
    expect(ok("maxweight", "4.155")).toBe(false);
    expect(ok("price:diesel", "1.789 EUR")).toBe(true);
    expect(ok("price:electric", "0.45 SEK")).toBe(true);
    expect(ok("price:adblue", "1.7891 EUR")).toBe(false);
    expect(ok("price:gas", "1.789 eur")).toBe(false);
  });

  it("raja on 50 ilmoitusta vuorokaudessa", () => {
    expect(DAILY_LIMIT).toBe(50);
  });
});

describe("LATEST_SQL", () => {
  it("valitsee uusimman ilmoituksen kohdetta ja kenttää kohti ikkunan soluista", () => {
    expect(LATEST_SQL).toContain("PARTITION BY poi, field");
    expect(LATEST_SQL).toContain("ORDER BY created DESC");
    expect((LATEST_SQL.match(/\?/g) ?? []).length).toBe(4);
  });

  it("parametrit kattavat ikkunan solurivit ja -sarakkeet", () => {
    const bbox: [number, number, number, number] = [24.5, 60.25, 25.25, 60.5];
    const ranges = cellRanges(bbox);
    const [minCell, maxCell, firstCol, lastCol] = latestParams(bbox);
    expect(minCell).toBe(ranges[0]![0]);
    expect(maxCell).toBe(ranges[ranges.length - 1]![1]);
    expect(firstCol).toBe(Math.floor((24.5 + 180) / CELL_DEG));
    expect(lastCol).toBe(Math.floor((25.25 + 180) / CELL_DEG));
    expect(lastCol).toBeLessThan(LON_CELLS);
  });
});
