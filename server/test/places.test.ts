import { describe, expect, it } from "vitest";
import { MAX_PLACES, type PlaceCandidate, rankPlaces, tokens } from "../src/places";
import cases from "./tokens-cases.json";

/** Rivi kuten kannassa: sanat lasketaan samalla normalisoinnilla kuin tuonnissa. */
function place(id: string, name: string, locality: string | null, lat: number, lon: number, conf = 0.95, street: string | null = null): PlaceCandidate {
  return { id, name, street, postcode: null, locality, lat, lon, conf, nwords: tokens(name).join(" "), lwords: tokens(locality ?? "").join(" ") };
}

// Overture Places 2026-10-21.0, Askolan ja Porvoon rivit.
const KONEKORJAAMO = place("37f9b45c-9227-4fa9-ad8d-9fae83427804", "Askolan Auto- ja Konekorjaamo", "Askola", 60.50148, 25.56598, 0.95, "Teollisuustie 3");
const AUTOKOULU = place("bb0f539f-6cea-44d7-9226-e59557defbea", "Askolan Autokoulu Ky", "Askola", 60.4931, 25.54033, 0.95, "Meijeritie 18");
const KONE_OY = place("640464d2-30ed-405a-a7b9-4b9d5eaff1c1", "Askolan Kone Oy", "Askola", 60.51593, 25.48793, 0.73, "Luhdintie 413");
const KONEVARUSTE = place("76b78286-5ec1-4784-9326-b431d5221ca1", "Askolan Konevaruste", "ASKOLA", 60.50115, 25.56577, 0.95, "Teollisuustie 3");
const AUTOKIRI = place("7b946842-f7d3-4cb7-93dd-0f4e49ab208f", "Autokiri Oy", "PORVOO", 60.40347, 25.68018, 1.0, "Mestarintie 10");
const PORVOON = Array.from({ length: 20 }, (_, i) =>
  place(`00000000-0000-0000-0000-0000000000${String(i).padStart(2, "0")}`, `Porvoon Yritys ${i}`, "Porvoo", 60.39 + i * 0.001, 25.66, 0.9),
);
const ALL = [KONEKORJAAMO, AUTOKOULU, KONE_OY, KONEVARUSTE, AUTOKIRI, ...PORVOON];
const PORVOO_CENTRE: [number, number] = [60.3932, 25.6649];

const names = (query: string, near: [number, number] | null = PORVOO_CENTRE) => rankPlaces(tokens(query), ALL, near).map((p) => p.name);

describe("tokens", () => {
  it("yhteiset tapaukset putken kanssa (server/pipeline/test/test_places.py)", () => {
    for (const c of cases) expect(tokens(c.text), c.text).toEqual(c.words);
  });

  it("pienet kirjaimet, väliviiva välilyönniksi ja yhtiömuoto pois", () => {
    expect(tokens("Askolan Auto- ja Konekorjaamo Oy")).toEqual(["askolan", "auto", "ja", "konekorjaamo"]);
  });

  it("T:mi on yhtiömuoto", () => {
    expect(tokens("T:mi Automatikainen")).toEqual(["automatikainen"]);
  });

  it("pelkät yhtiömuodot ja välimerkit eivät jätä sanoja", () => {
    expect(tokens("Oy")).toEqual([]);
    expect(tokens("--")).toEqual([]);
    expect(tokens("T:mi")).toEqual([]);
  });

  it("hajotettu ääkkönen on sama kuin yhdistetty", () => {
    expect(tokens("Mäntsälän Autohuolto")).toEqual(["mäntsälän", "autohuolto"]);
  });

  it("heittomerkki ei katkaise sanaa", () => {
    expect(tokens("McDonald's Porvoo")).toEqual(["mcdonalds", "porvoo"]);
    expect(tokens("McDonald’s")).toEqual(["mcdonalds"]);
  });

  it("pienennyksen jälkeen jäävä yhdistävä merkki ei katkaise sanaa", () => {
    expect(tokens("İstanbul Kebab")).toEqual(["istanbul", "kebab"]);
  });
});

describe("rankPlaces", () => {
  it("koko nimi yhtiömuotoineen löytää Konekorjaamon ensimmäisenä", () => {
    expect(names("Askolan auto ja kone oy")[0]).toBe("Askolan Auto- ja Konekorjaamo");
  });

  it("kokonainen sana ennen sanan alkua: Konekorjaamo ennen Autokoulua", () => {
    expect(names("Askolan auto")).toEqual(["Askolan Auto- ja Konekorjaamo", "Askolan Autokoulu Ky"]);
  });

  it("askolan kone: Kone Oy ensin, sitten Konevaruste ja Konekorjaamo", () => {
    expect(names("askolan kone")).toEqual(["Askolan Kone Oy", "Askolan Konevaruste", "Askolan Auto- ja Konekorjaamo"]);
  });

  it("sana osuu vain sanan alkuun, ei keskelle", () => {
    expect(names("korjaamo")).toEqual([]);
  });

  it("varahaku alkaa kolmesta sanasta, eikä yhtiömuoto ole sana", () => {
    expect(names("askolan auto zzz")).toEqual(["Askolan Auto- ja Konekorjaamo", "Askolan Autokoulu Ky"]);
    expect(names("askolan zzz oy")).toEqual([]);
  });

  it("varahakua ei käytetä, kun täysi osuma on", () => {
    expect(names("askolan auto ja")).toEqual(["Askolan Auto- ja Konekorjaamo"]);
  });

  it("nimi ja paikkakunta", () => {
    expect(names("Autokiri Porvoo")).toEqual(["Autokiri Oy"]);
  });

  it("sanajärjestyksellä ei ole väliä", () => {
    expect(names("konekorjaamo askola")).toEqual(["Askolan Auto- ja Konekorjaamo"]);
  });

  it("vähintään kolmen sanan haku kelpaa yhtä sanaa vaille", () => {
    expect(names("Askolan auto ja kone korjaamo")).toEqual(["Askolan Auto- ja Konekorjaamo"]);
  });

  it("vähintään yksi sana osuu nimeen: pelkkä paikkakunta ei riitä", () => {
    // Sijainti Autokirin kohdalla: ilman sääntöä se olisi lähin ja ensimmäinen.
    const result = names("porvoo", [AUTOKIRI.lat, AUTOKIRI.lon]);
    expect(result).toHaveLength(MAX_PLACES);
    expect(result.every((n) => n.startsWith("Porvoon "))).toBe(true);
  });

  it("kaksisanainen haku ilman täyttä osumaa ei käytä varahakua", () => {
    expect(names("askolan zzz")).toEqual([]);
  });

  it("sijainti järjestää muuten tasavertaiset rivit lähin ensin", () => {
    const askola = place("a", "Lidl", "Askola", 60.53, 25.6);
    const porvoo = place("b", "Lidl", "Porvoo", 60.39, 25.66);
    expect(rankPlaces(["lidl"], [porvoo, askola], [60.53, 25.6]).map((p) => p.id)).toEqual(["a", "b"]);
    expect(rankPlaces(["lidl"], [askola, porvoo], PORVOO_CENTRE).map((p) => p.id)).toEqual(["b", "a"]);
  });

  it("ilman sijaintia luotettavampi ensin", () => {
    const weak = place("a", "Lidl", "Askola", 60.53, 25.6, 0.5);
    const strong = place("b", "Lidl", "Porvoo", 60.39, 25.66, 0.9);
    expect(rankPlaces(["lidl"], [weak, strong], null).map((p) => p.id)).toEqual(["b", "a"]);
  });

  it("etäisyys ratkaisee ennen luotettavuutta", () => {
    const nearWeak = place("a", "Lidl", "Porvoo", 60.39, 25.66, 0.5);
    const farStrong = place("b", "Lidl", "Askola", 60.53, 25.6, 0.9);
    expect(rankPlaces(["lidl"], [farStrong, nearWeak], PORVOO_CENTRE).map((p) => p.id)).toEqual(["a", "b"]);
  });

  it("tasapelissä nimi suomen aakkosjärjestyksessä (Z ennen Ä:tä)", () => {
    const a = place("a", "Lidl Äänekoski", null, 60, 25);
    const z = place("z", "Lidl Zeta", null, 60, 25);
    expect(rankPlaces(["lidl"], [a, z], null).map((p) => p.id)).toEqual(["z", "a"]);
  });

  it("sama tunniste kahdesti näkyy kerran", () => {
    expect(rankPlaces(["autokiri"], [AUTOKIRI, AUTOKIRI], null)).toHaveLength(1);
  });

  it("tyhjä haku ei palauta mitään", () => {
    expect(rankPlaces(tokens("Oy"), ALL, null)).toEqual([]);
  });
});
