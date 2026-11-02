import { describe, expect, it } from "vitest";
import {
  CATS,
  type FactRowDb,
  MAX_BATCH_PARTS,
  MAX_ITEMS,
  buildFactsSelect,
  buildSelect,
  cellOf,
  cellRanges,
  inRequestedCats,
  mergeFacts,
  orderItems,
  parseBatch,
  parseQuery,
  toItems,
} from "../src/query";

const params = (s: string) => new URLSearchParams(s);

function query(s: string) {
  const parsed = parseQuery(params(s));
  if (!parsed.ok) throw new Error(parsed.error);
  return parsed.query;
}

describe("parseQuery", () => {
  it("käyttää kaikkia neljää luokkaa, kun cats puuttuu", () => {
    const q = query("bbox=24.5,60.25,25.25,60.5");
    expect(q.cats).toEqual([...CATS]);
    expect(q.hgvOnly).toBe(false);
    expect(q.bbox).toEqual([24.5, 60.25, 25.25, 60.5]);
  });

  it("lukee yhden luokan ja hgv-lipun", () => {
    const q = query("bbox=24.5,60.25,25.25,60.5&cats=fuel&hgv=1");
    expect(q.cats).toEqual(["fuel"]);
    expect(q.hgvOnly).toBe(true);
  });

  it("hylkää käänteisen ikkunan", () => {
    expect(parseQuery(params("bbox=25.25,60.25,24.5,60.5")).ok).toBe(false);
  });

  it("hylkää yli kahden asteen ikkunan", () => {
    expect(parseQuery(params("bbox=24,60,26.25,60.5")).ok).toBe(false);
    expect(parseQuery(params("bbox=24,60,24.5,62.25")).ok).toBe(false);
  });

  it("hylkää leveyspiirin rajojen ulkopuolella", () => {
    expect(parseQuery(params("bbox=24.5,89.5,25,91")).ok).toBe(false);
  });

  it("hylkää tuntemattoman luokan ja puuttuvan ikkunan", () => {
    expect(parseQuery(params("bbox=24.5,60.25,25.25,60.5&cats=foo")).ok).toBe(false);
    expect(parseQuery(params("cats=fuel")).ok).toBe(false);
    expect(parseQuery(params("bbox=24.5,60.25,25.25")).ok).toBe(false);
  });
});

describe("ruudukko", () => {
  it("laskee ruudun rivistä ja sarakkeesta", () => {
    expect(cellOf(-90, -180)).toBe(0);
    expect(cellOf(60.25, 24.5)).toBe(Math.floor(150.25 / 0.25) * 2048 + Math.floor(204.5 / 0.25));
  });

  it("antaa yhden välin leveyspiiririviä kohti, reunat mukaan lukien", () => {
    expect(cellRanges([24.5, 60.25, 25.25, 60.5])).toEqual([
      [cellOf(60.25, 24.5), cellOf(60.25, 25.25)],
      [cellOf(60.5, 24.5), cellOf(60.5, 25.25)],
    ]);
    expect(cellRanges([24.5, 60.25, 25.25, 60.75])).toHaveLength(3);
  });

  it("pysyy nousevana Nordkapin leveyspiireillä", () => {
    expect(cellOf(71.0, 25.0)).toBeLessThan(cellOf(71.25, 25.0));
    expect(cellOf(71.0, 25.0)).toBeLessThan(cellOf(71.0, 25.25));
    const ranges = cellRanges([25.0, 71.0, 26.0, 71.5]);
    expect(ranges).toHaveLength(3);
    for (const [from, to] of ranges) expect(from).toBeLessThanOrEqual(to);
  });
});

describe("buildSelect", () => {
  it("rajaa luokat, koordinaatit ja ruudut ja järjestää raskaan kaluston ensin, sitten lähimmät ikkunan keskeltä", () => {
    const q = query("bbox=24.5,60.25,25.25,60.5&cats=fuel,rest");
    const { sql, params: p } = buildSelect(q);
    expect(sql).toContain("cat IN (?,?)");
    expect(sql).toContain("lat BETWEEN ? AND ?");
    expect(sql).toContain("(cell BETWEEN ? AND ?) OR (cell BETWEEN ? AND ?)");
    expect(sql).toContain("ORDER BY hgv DESC, ((lat - ?) * (lat - ?) + (lon - ?) * (lon - ?) * ?) ASC, id ASC");
    // Yksi ylimääräinen rivi kertoo katkaisusta.
    expect(sql).toContain(`LIMIT ${MAX_ITEMS + 1}`);
    expect(sql).not.toContain("hgv = 1");
    const cos = Math.cos((60.375 * Math.PI) / 180);
    expect(p).toEqual([
      "fuel", "rest", 60.25, 60.5, 24.5, 25.25,
      cellOf(60.25, 24.5), cellOf(60.25, 25.25), cellOf(60.5, 24.5), cellOf(60.5, 25.25),
      60.375, 60.375, 24.875, 24.875, cos * cos,
    ]);
  });

  it("lisää hgv-ehdon ja pysyy alle sadassa parametrissa suurimmalla ikkunalla", () => {
    const q = query("bbox=24,60,26,62&hgv=1");
    const { sql, params: p } = buildSelect(q);
    expect(sql).toContain("AND hgv = 1");
    expect(p.length).toBeLessThanOrEqual(31);
  });
});

describe("toItems", () => {
  it("jäsentää tagit ja jättää tyhjät kentät pois", () => {
    const items = toItems([
      { id: "n1", cat: "fuel", lat: 60.3, lon: 24.6, name: "Neste Truck", brand: null, addr: null, hgv: 1, tags: '{"fuel:hgv_diesel":"yes"}' },
      { id: "w2", cat: "rest", lat: 60.4, lon: 24.7, name: null, brand: "ABC", addr: "Tie 1", hgv: 0, tags: "ei json" },
    ]);
    expect(items[0]).toEqual({ id: "n1", cat: "fuel", lat: 60.3, lon: 24.6, name: "Neste Truck", hgv: true, tags: { "fuel:hgv_diesel": "yes" } });
    expect(items[1]).toEqual({ id: "w2", cat: "rest", lat: 60.4, lon: 24.7, brand: "ABC", addr: "Tie 1", hgv: false, tags: {} });
  });
});

describe("luokkavaikutus ilmoituksista", () => {
  it("kaasukysely ottaa mukaan huoltoasemat, joiden uusin polttoaineilmoitus sisältää kaasun", () => {
    const { sql, params: p } = buildSelect(query("bbox=24,60,26,62&cats=gas"));
    expect(sql).toContain("cat = 'fuel' AND id IN (SELECT r.poi FROM report r WHERE r.field = 'fuels'");
    expect(sql).toContain("LIKE '%;biogas;%'");
    expect(sql).not.toContain("LIKE '%;diesel;%'");
    expect(p.length).toBeLessThanOrEqual(100);
  });

  it("huoltoasemakysely ottaa mukaan kaasuasemat, joilla on ilmoitettu nestemäinen polttoaine", () => {
    const { sql } = buildSelect(query("bbox=24.5,60.25,25.25,60.5&cats=fuel"));
    expect(sql).toContain("cat = 'gas' AND id IN (SELECT r.poi FROM report r WHERE r.field = 'fuels'");
    expect(sql).toContain("LIKE '%;diesel;%'");
  });

  it("ei lisäehtoa, kun molemmat tai kumpikaan luokista on pyydetty", () => {
    expect(buildSelect(query("bbox=24.5,60.25,25.25,60.5&cats=fuel,gas")).sql).not.toContain("FROM report");
    expect(buildSelect(query("bbox=24.5,60.25,25.25,60.5&cats=rest")).sql).not.toContain("FROM report");
  });

  it("toisen luokan kohde näytetään pyydetyssä luokassa", () => {
    const items = [
      { id: "n1", cat: "fuel" as const, lat: 60, lon: 24, hgv: false, tags: {} },
      { id: "n2", cat: "gas" as const, lat: 60, lon: 24, hgv: false, tags: {} },
    ];
    expect(inRequestedCats(items, ["gas"]).map((i) => i.cat)).toEqual(["gas", "gas"]);
    expect(inRequestedCats(items, ["fuel", "gas"]).map((i) => i.cat)).toEqual(["fuel", "gas"]);
  });
});

describe("kertatuonnin faktat", () => {
  const item = (id: string, hgv = false, lat = 60.3, lon = 24.6) => ({ id, cat: "fuel" as const, lat, lon, hgv, tags: { amenity: "fuel" } });
  const factRow = (overrides: Partial<FactRowDb> = {}): FactRowDb => ({
    id: "n1",
    cat: "fuel",
    lat: 60.3,
    lon: 24.6,
    name: null,
    brand: null,
    addr: null,
    linked: 1,
    facts: '{"k:date":"2026-10-22","k:fuels":"diesel=Neste Futura Diesel"}',
    ...overrides,
  });

  it("liitetty asema saa k-tagit OSM-tagien rinnalle", () => {
    const [n1] = mergeFacts([item("n1")], [factRow()], { cats: ["fuel"], hgvOnly: false });
    expect(n1!.tags).toEqual({ amenity: "fuel", "k:date": "2026-10-22", "k:fuels": "diesel=Neste Futura Diesel" });
  });

  it("oma asema tulee kohteeksi ja saa raskaan kaluston rekkapisteestä", () => {
    const own = factRow({
      id: "k-neste-2605", linked: 0, name: "Neste Kylmäkoski", brand: "Neste", addr: "Tie 1", facts: '{"k:date":"2026-10-22","k:hgv_lane":"yes"}',
    });
    const items = mergeFacts([item("n1")], [own], { cats: ["fuel"], hgvOnly: false });
    expect(items[1]).toEqual({
      id: "k-neste-2605", cat: "fuel", lat: 60.3, lon: 24.6, name: "Neste Kylmäkoski", brand: "Neste", addr: "Tie 1", hgv: true,
      tags: { "k:date": "2026-10-22", "k:hgv_lane": "yes" },
    });
  });

  it("kadonneen kohteen faktat ja rikkinäinen rivi ohitetaan, hgv-kysely ohittaa oman aseman ilman rekkapistettä", () => {
    const items = mergeFacts(
      [item("n1")],
      [factRow({ id: "n999" }), factRow({ facts: "ei json" }), factRow({ id: "k-abc-1", linked: 0, facts: '{"k:date":"2026-10-22"}' })],
      { cats: ["fuel"], hgvOnly: true },
    );
    expect(items).toEqual([item("n1")]);
  });

  it("oma asema toisessa luokassa näytetään pyydetyssä luokassa (ilmoitus toi sen kyselyyn)", () => {
    const gasOnly = factRow({ id: "k-st1-1", cat: "gas", linked: 0 });
    expect(mergeFacts([], [gasOnly], { cats: ["fuel"], hgvOnly: false }).map((i) => i.cat)).toEqual(["fuel"]);
  });

  it("faktakysely rajaa ikkunan ja pysyy alle sadan parametrin", () => {
    const { sql, params: p } = buildFactsSelect(query("bbox=24,60,26,62&cats=gas&hgv=1"));
    expect(sql).toContain("FROM station_facts");
    expect(sql).toContain("linked = 1 OR cat IN (?)");
    expect(sql).toContain("cat = 'fuel' AND id IN (SELECT r.poi FROM report r");
    expect(p.length).toBeLessThanOrEqual(100);
  });

  it("yhdistetty lista järjestetään raskas kalusto ensin, sitten etäisyys keskeltä", () => {
    const far = item("n1", false, 60.49, 24.6);
    const near = item("k-neste-1", false, 60.375, 24.875);
    const hgv = item("n2", true, 60.26, 24.51);
    expect(orderItems([far, near, hgv], [24.5, 60.25, 25.25, 60.5]).map((i) => i.id)).toEqual(["n2", "k-neste-1", "n1"]);
  });
});

describe("parseBatch", () => {
  const part = (i: number) => ({ bbox: "24.50,60.25,24.75,60.50", cats: i % 2 === 0 ? "fuel" : "gas" });
  const parts = (n: number) => Array.from({ length: n }, (_, i) => part(i));

  it("hyväksyy yhden palan samassa muodossa kuin ikkunakysely", () => {
    expect(parseBatch({ parts: [part(0)] })).toEqual({
      ok: true,
      queries: [{ bbox: [24.5, 60.25, 24.75, 60.5], cats: ["fuel"], hgvOnly: false }],
    });
  });

  it("hyväksyy 50 palaa järjestyksessä", () => {
    expect(MAX_BATCH_PARTS).toBe(50);
    const parsed = parseBatch({ parts: parts(50) });
    if (!parsed.ok) throw new Error(parsed.error);
    expect(parsed.queries).toHaveLength(50);
    expect(parsed.queries.map((q) => q.cats[0])).toEqual(parts(50).map((p) => p.cats));
  });

  it("hylkää tyhjän listan, yli 50 palaa ja rungon ilman parts-listaa", () => {
    for (const body of [{ parts: [] }, { parts: parts(MAX_BATCH_PARTS + 1) }, {}, null, [], { parts: "fuel" }]) {
      expect(parseBatch(body).ok).toBe(false);
    }
  });

  it("palassa on täsmälleen yksi laji", () => {
    for (const cats of ["fuel,gas", undefined, "", "bar"]) {
      const parsed = parseBatch({ parts: [{ bbox: "24.50,60.25,24.75,60.50", cats }] });
      expect(parsed).toEqual({ ok: false, error: expect.stringMatching(/^parts\[0\]: cats/) });
    }
  });

  it("virheessä on virheellisen palan indeksi eikä osittaista tulosta", () => {
    const body = { parts: [part(0), part(1), part(2), { bbox: "24.75,60.25,24.50,60.50", cats: "fuel" }] };
    expect(parseBatch(body)).toEqual({ ok: false, error: expect.stringMatching(/^parts\[3\]: bbox/) });
    expect(parseBatch({ parts: [part(0), "x"] })).toEqual({ ok: false, error: expect.stringMatching(/^parts\[1\]:/) });
    expect(parseBatch({ parts: [part(0), { cats: "fuel" }] })).toEqual({ ok: false, error: "parts[1]: bbox puuttuu" });
  });

  it("pala on enintään yksi 0,25 asteen solu, koska yhteishaku on rajassa yksi haku", () => {
    const one = (bbox: string) => parseBatch({ parts: [{ bbox, cats: "charging" }] });
    expect(one("24.75,60.25,25.00,60.50").ok).toBe(true);
    expect(one("24.95,60.45,25.00,60.50").ok).toBe(true);
    for (const bbox of ["24.50,60.25,25.00,60.50", "24.75,60.25,25.00,60.75", "24.00,60.00,26.00,62.00"]) {
      expect(one(bbox)).toEqual({ ok: false, error: expect.stringMatching(/^parts\[0\]: bbox: pala enintään 0,25°/) });
    }
  });

  it("palassa on vain bbox ja cats merkkijonoina", () => {
    expect(parseBatch({ parts: [{ ...part(0), hgv: "1" }] })).toEqual({ ok: false, error: "parts[0]: tuntematon kenttä hgv" });
    expect(parseBatch({ parts: [{ bbox: [24.5, 60.25, 24.75, 60.5], cats: "fuel" }] })).toEqual({
      ok: false,
      error: expect.stringMatching(/^parts\[0\]: bbox on merkkijono/),
    });
  });
});
