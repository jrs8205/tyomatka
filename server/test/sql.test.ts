import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { describe, expect, it } from "vitest";
import { FACTS_SQL, factsParam } from "../src/admin";
import { buildSelect, cellOf, parseQuery } from "../src/query";
import { CELL_SQL, INSERT_SQL, LATEST_SQL, latestParams } from "../src/reports";

/** Palvelimen lauseet oikeaa SQLiteä vasten (D1 on SQLite): skeema tiedostosta, rivit käsin. */
function database() {
  const db = new DatabaseSync(":memory:");
  db.exec(readFileSync(new URL("../schema.sql", import.meta.url), "utf-8"));
  return db;
}

function addPoi(db: DatabaseSync, id: string, cat: string, lat: number, lon: number) {
  db.prepare("INSERT INTO poi (id, cat, country, lat, lon, cell, name, brand, addr, hgv, tags, updated) VALUES (?, ?, 'FI', ?, ?, ?, NULL, NULL, NULL, 0, '{}', 'x')")
    .run(id, cat, lat, lon, cellOf(lat, lon));
}

function addReport(db: DatabaseSync, rid: string, poi: string, field: string, value: string, created: string, cell: number) {
  db.prepare(INSERT_SQL).run(rid, poi, field, value, "0f8fad5b-d9cb-469f-a165-70867728950e", created, created, cell);
}

const query = (s: string) => {
  const parsed = parseQuery(new URLSearchParams(s));
  if (!parsed.ok) throw new Error(parsed.error);
  return parsed.query;
};

describe("SQL oikealla SQLitellä", () => {
  it("uusin ilmoitus tekohetken mukaan kohdetta ja kenttää kohti, vain ikkunan soluista", () => {
    const db = database();
    const cell = cellOf(60.3, 24.6);
    addReport(db, "r1", "n1", "closed", "yes", "2026-10-22T15:00:00.000Z", cell);
    addReport(db, "r2", "n1", "closed", "no", "2026-10-22T10:00:00.000Z", cell);
    addReport(db, "r3", "n1", "shop", "no", "2026-10-21T10:00:00.000Z", cell);
    addReport(db, "r4", "n9", "closed", "yes", "2026-10-22T10:00:00.000Z", cellOf(62.0, 27.0));
    const rows = db.prepare(LATEST_SQL).all(...latestParams([24.5, 60.25, 25.0, 60.5]));
    expect(rows.map((r) => `${r.poi}:${r.field}:${r.value}:${r.date}`).sort()).toEqual(["n1:closed:yes:2026-10-22", "n1:shop:no:2026-10-21"]);
  });

  it("sama rid toiseen kertaan ei lisää riviä", () => {
    const db = database();
    addReport(db, "r1", "n1", "closed", "yes", "2026-10-22T15:00:00.000Z", 1);
    addReport(db, "r1", "n1", "closed", "no", "2026-10-22T16:00:00.000Z", 1);
    expect(db.prepare("SELECT COUNT(*) AS n FROM report").get()).toEqual({ n: 1 });
  });

  it("kaasukysely ottaa ilmoitetun biokaasun huoltoaseman mukaan", () => {
    const db = database();
    addPoi(db, "n1", "fuel", 60.3, 24.6);
    addPoi(db, "n2", "fuel", 60.31, 24.61);
    addReport(db, "r1", "n1", "fuels", "diesel;biogas", "2026-10-22T15:00:00.000Z", cellOf(60.3, 24.6));
    addReport(db, "r2", "n2", "fuels", "biogas", "2026-10-21T15:00:00.000Z", cellOf(60.31, 24.61));
    addReport(db, "r3", "n2", "fuels", "diesel", "2026-10-22T15:00:00.000Z", cellOf(60.31, 24.61));
    const { sql, params } = buildSelect(query("bbox=24.5,60.25,25.0,60.5&cats=gas"));
    expect(db.prepare(sql).all(...params).map((r) => r.id)).toEqual(["n1"]);
  });

  it("saman hetken polttoaineilmoituksista luokkaan vaikuttaa sama uusin rivi kuin kortilla", () => {
    const db = database();
    addPoi(db, "n1", "fuel", 60.3, 24.6);
    const cell = cellOf(60.3, 24.6);
    addReport(db, "r1", "n1", "fuels", "diesel;biogas", "2026-10-22T15:00:00.000Z", cell);
    addReport(db, "r2", "n1", "fuels", "diesel", "2026-10-22T15:00:00.000Z", cell);
    const { sql, params } = buildSelect(query("bbox=24.5,60.25,25.0,60.5&cats=gas"));
    expect(db.prepare(sql).all(...params)).toEqual([]);
    const latest = db.prepare(LATEST_SQL).all(...latestParams([24.5, 60.25, 25.0, 60.5]));
    expect(latest.map((r) => r.value)).toEqual(["diesel"]);
  });

  it("kohteen solu löytyy OSM-riviltä ja kertatuonnin omalta asemalta samalla parametrilla", () => {
    const db = database();
    addPoi(db, "n1", "fuel", 60.3, 24.6);
    db.prepare(FACTS_SQL).run(
      factsParam({ rows: [{ id: "k-neste-2605", cat: "fuel", lat: 61.17, lon: 23.67, linked: 0, facts: { "k:date": "2026-10-22" } }] }, "x"),
    );
    expect(db.prepare(CELL_SQL).get("n1")).toEqual({ cell: cellOf(60.3, 24.6) });
    expect(db.prepare(CELL_SQL).get("k-neste-2605")).toEqual({ cell: cellOf(61.17, 23.67) });
    expect(db.prepare(CELL_SQL).get("n404")).toBeUndefined();
  });
});
