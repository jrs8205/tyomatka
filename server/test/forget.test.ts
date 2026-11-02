import { describe, expect, it } from "vitest";
import worker from "../src/index";
import type { Env } from "../src/env";
import { EXPIRED_DEVICE, FORGET_SQL, INSERT_SQL } from "../src/reports";
import { sqliteD1 } from "./d1";

const DEVICE = "0f8fad5b-d9cb-469f-a165-70867728950e";
const OTHER = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
const NOW = new Date("2026-11-01T12:00:00.000Z");

function database(rows: Array<{ rid: string; device: string; received?: string }>) {
  const db = sqliteD1();
  for (const row of rows) {
    db.raw
      .prepare("INSERT INTO report (rid, poi, field, value, device, created, received, cell) VALUES (?, 'n1', 'closed', 'yes', ?, ?, ?, 1)")
      .run(row.rid, row.device, row.received ?? NOW.toISOString(), row.received ?? NOW.toISOString());
  }
  return db;
}

function env(db: ReturnType<typeof database>, overrides: Partial<Record<keyof Env, unknown>> = {}) {
  const open = { limit: async () => ({ success: true }) };
  return { DB: db, KV: { get: async () => null }, RATE_IP: open, RATE_GLOBAL: open, RATE_REPORT: open, ...overrides } as unknown as Env;
}

const forget = (e: Env, device: string | null = DEVICE) =>
  worker.fetch(
    new Request("https://poi.example/v1/reports/forget", {
      method: "POST",
      headers: { "CF-Connecting-IP": "203.0.113.7", ...(device === null ? {} : { "X-Device": device }) },
    }),
    e,
  );

const remaining = (db: ReturnType<typeof database>) =>
  (db.raw.prepare("SELECT rid, device FROM report ORDER BY rid").all() as Array<{ rid: string; device: string }>).map((r) => `${r.rid}:${r.device}`);

describe("POST /v1/reports/forget", () => {
  it("poistaa vain oman laitteen ilmoitukset ja kertoo määrän", async () => {
    const db = database([
      { rid: "a", device: DEVICE },
      { rid: "b", device: DEVICE },
      { rid: "c", device: OTHER },
    ]);
    const response = await forget(env(db));

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ deleted: 2 });
    expect(remaining(db)).toEqual([`c:${OTHER}`]);
    expect(response.headers.get("Cache-Control")).toBe("no-store");
  });

  it("isot kirjaimet ovat sama tunnus", async () => {
    const db = database([{ rid: "a", device: DEVICE }]);

    await forget(env(db), DEVICE.toUpperCase());

    expect(remaining(db)).toEqual([]);
  });

  it("ei ilmoituksia on onnistunut poisto", async () => {
    const response = await forget(env(database([])));

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ deleted: 0 });
  });

  it.each([null, "", "vanhentunut", "' OR 1=1 --", "0f8fad5b-d9cb-169f-a165-70867728950e"])("kelvoton tunnus %s hylätään", async (device) => {
    const db = database([{ rid: "a", device: EXPIRED_DEVICE }]);
    const response = await forget(env(db), device);

    expect(response.status).toBe(400);
    expect(remaining(db)).toHaveLength(1);
  });

  it("ilmoitusraja koskee poistoakin", async () => {
    const db = database([{ rid: "a", device: DEVICE }]);
    const response = await forget(env(db, { RATE_REPORT: { limit: async () => ({ success: false }) } }));

    expect(response.status).toBe(429);
    expect(remaining(db)).toHaveLength(1);
  });

  it("pysäytys ja yhteinen raja koskevat poistoa", async () => {
    const db = database([{ rid: "a", device: DEVICE }]);

    expect((await forget(env(db, { KV: { get: async () => "1" } }))).status).toBe(503);
    expect((await forget(env(db, { RATE_GLOBAL: { limit: async () => ({ success: false }) } }))).status).toBe(429);
    expect(remaining(db)).toHaveLength(1);
  });

  it("GET ei poista", async () => {
    const db = database([{ rid: "a", device: DEVICE }]);
    const response = await worker.fetch(new Request("https://poi.example/v1/reports/forget", { headers: { "X-Device": DEVICE } }), env(db));

    expect(response.status).toBe(404);
    expect(remaining(db)).toHaveLength(1);
  });
});

describe("poistettu tunnus", () => {
  const RID = "6fa459ea-ee8a-4ca4-894e-db77e160355e";

  /** Oikea kanta; [hook] ajetaan juuri ennen lausetta [sql], kuten toinen samaan aikaan käsiteltävä pyyntö. */
  function around(db: ReturnType<typeof database>, sql: string, hook: () => Promise<unknown>) {
    return {
      ...db,
      prepare(text: string) {
        const statement = db.prepare(text);
        if (text !== sql) return statement;
        const run = statement.run;
        statement.run = async () => {
          await hook();
          return run();
        };
        return statement;
      },
    };
  }

  /** Oikea kanta; [hook] ajetaan juuri ennen seuraavaa erää, kuten toinen samaan aikaan käsiteltävä pyyntö. */
  const beforeBatch = (db: ReturnType<typeof database>, hook: () => Promise<unknown>) => ({
    ...db,
    async batch(statements: Parameters<typeof db.batch>[0]) {
      await hook();
      return db.batch(statements);
    },
  });

  const withPoi = () => {
    const db = database([]);
    db.raw.prepare("INSERT INTO poi (id, cat, country, lat, lon, cell, hgv, tags, updated) VALUES ('n1', 'fuel', 'FI', 60, 24, 1, 0, '{}', 'x')").run();
    return db;
  };

  const send = (e: Env, device = DEVICE, rid = RID) =>
    worker.fetch(
      new Request("https://poi.example/v1/reports", {
        method: "POST",
        headers: { "CF-Connecting-IP": "203.0.113.7", "X-Device": device },
        body: JSON.stringify({ poi: "n1", field: "closed", value: "yes", rid }),
      }),
      e,
    );

  const tombstones = (db: ReturnType<typeof database>) =>
    (db.raw.prepare("SELECT device FROM forgotten_device ORDER BY device").all() as Array<{ device: string }>).map((r) => r.device);

  it("poiston aikana kesken ollut lähetys ei tallennu", async () => {
    const db = withPoi();
    const racing = beforeBatch(db, () => forget(env(db)));

    const response = await send(env(racing as never));

    expect(response.status).toBe(410);
    // Sovellus vaihtaa tunnuksensa vain tästä vastauksesta, ei kenen tahansa välittäjän 410:stä.
    expect(await response.json()).toEqual({ error: "tunnus poistettu" });
    expect(remaining(db)).toEqual([]);
  });

  it("poiston jälkeen perille tullut lähetys vanhalla tunnuksella hylätään", async () => {
    const db = withPoi();
    await forget(env(db));

    const response = await send(env(db));

    expect(response.status).toBe(410);
    expect(remaining(db)).toEqual([]);
  });

  it("isot kirjaimet eivät kierrä poistoa", async () => {
    const db = withPoi();
    await forget(env(db), DEVICE.toUpperCase());

    expect((await send(env(db))).status).toBe(410);
    expect((await send(env(db), DEVICE.toUpperCase())).status).toBe(410);
    expect(remaining(db)).toEqual([]);
  });

  it("toisen laitteen lähetys tallentuu", async () => {
    const db = withPoi();
    await forget(env(db));

    const response = await send(env(db), OTHER);

    expect(response.status).toBe(201);
    expect(remaining(db)).toEqual([`${RID}:${OTHER}`]);
  });

  it("saman tunnisteen rinnakkainen uusinta on edelleen onnistuminen", async () => {
    const db = withPoi();
    const racing = beforeBatch(db, async () => {
      db.raw
        .prepare("INSERT INTO report (rid, poi, field, value, device, created, received, cell) VALUES (?, 'n1', 'closed', 'yes', ?, 'x', 'x', 1)")
        .run(RID, DEVICE);
    });

    const response = await send(env(racing as never));

    expect(response.status).toBe(201);
    expect(remaining(db)).toEqual([`${RID}:${DEVICE}`]);
  });

  it("toistettu poisto onnistuu", async () => {
    const db = database([{ rid: "a", device: DEVICE }]);
    await forget(env(db));

    const again = await forget(env(db));

    expect(again.status).toBe(200);
    expect(await again.json()).toEqual({ deleted: 0 });
    expect(tombstones(db)).toEqual([DEVICE]);
  });

  it("epäonnistunut poisto ei mitätöi tunnusta", async () => {
    const db = database([{ rid: "a", device: DEVICE }]);
    const failing = around(db, FORGET_SQL, async () => {
      throw new Error("kanta");
    });

    await expect(forget(env(failing as never))).rejects.toThrow("kanta");

    expect(tombstones(db)).toEqual([]);
    expect(remaining(db)).toEqual([`a:${DEVICE}`]);
  });

  it("poisto ei tallenna tunnuksen lisäksi muuta", async () => {
    const db = database([{ rid: "a", device: DEVICE }]);
    await forget(env(db));

    const columns = (db.raw.prepare("PRAGMA table_info(forgotten_device)").all() as Array<{ name: string }>).map((c) => c.name);
    expect(columns).toEqual(["device", "at"]);
  });

  describe("säilytys", () => {
    const put = (db: ReturnType<typeof database>, device: string, at: string) => db.raw.prepare("INSERT INTO forgotten_device (device, at) VALUES (?, ?)").run(device, at);
    const run = (db: ReturnType<typeof database>, at = NOW.getTime()) => worker.scheduled({ scheduledTime: at, cron: "17 3 * * *" } as unknown as ScheduledController, env(db));
    const writtenAt = (db: ReturnType<typeof database>) => Date.parse((db.raw.prepare("SELECT at FROM forgotten_device").get() as { at: string }).at);
    const DAY = 24 * 3600 * 1000;

    it("merkintä poistuu 5 vuorokauden rajalla, joten yksi väliin jäänyt ajo mahtuu selosteen seitsemään vuorokauteen", async () => {
      const db = database([]);
      put(db, DEVICE, "2026-10-27T11:59:59.999Z");
      put(db, OTHER, "2026-10-27T12:00:00.000Z");

      await run(db);

      expect(tombstones(db)).toEqual([OTHER]);
    });

    it("poiston kirjoittama merkintä poistuu vasta rajalla", async () => {
      const db = database([]);
      await forget(env(db));
      const at = writtenAt(db);
      expect(Math.abs(at - Date.now())).toBeLessThan(60_000);

      await run(db, at + 5 * DAY);
      expect(tombstones(db)).toEqual([DEVICE]);

      await run(db, at + 5 * DAY + 1);
      expect(tombstones(db)).toEqual([]);
    });

    it("toistettu poisto aloittaa säilytyksen alusta", async () => {
      const db = database([]);
      put(db, DEVICE, "2020-01-01T00:00:00.000Z");

      await forget(env(db));

      expect(Math.abs(writtenAt(db) - Date.now())).toBeLessThan(60_000);
    });

    it("siivous ei voi muuttaa torjuttua lähetystä onnistuneeksi", async () => {
      const db = withPoi();
      put(db, DEVICE, "2026-01-01T00:00:00.000Z");
      // Siivous pääsee väliin heti lisäyksen jälkeen, ellei lisäys ole samassa transaktiossa tarkistuksen kanssa.
      const racing = {
        ...db,
        prepare(text: string) {
          const statement = db.prepare(text);
          if (text !== INSERT_SQL) return statement;
          const original = statement.run;
          statement.run = async () => {
            const result = await original();
            if (!db.inTransaction()) db.raw.prepare("DELETE FROM forgotten_device").run();
            return result;
          };
          return statement;
        },
      };

      const response = await send(env(racing as never));

      expect(response.status).toBe(410);
      expect(remaining(db)).toEqual([]);
    });

    it("erän puuttuva tulos ei näy onnistuneena lähetyksenä", async () => {
      const db = withPoi();
      const short = { ...db, batch: async (statements: Parameters<typeof db.batch>[0]) => (await db.batch(statements)).slice(0, 1) };

      await expect(send(env(short as never))).rejects.toThrow();
    });

    it("vanhentunut merkintä ei enää estä lähetystä", async () => {
      const db = withPoi();
      put(db, DEVICE, "2026-01-01T00:00:00.000Z");
      await run(db);

      expect((await send(env(db))).status).toBe(201);
    });

    it("sama ajo korvaa myös vanhat ilmoitusten tunnukset", async () => {
      const db = database([{ rid: "vanha", device: DEVICE, received: "2025-01-01T00:00:00.000Z" }]);
      put(db, OTHER, "2026-01-01T00:00:00.000Z");

      await run(db);

      expect(remaining(db)).toEqual([`vanha:${EXPIRED_DEVICE}`]);
      expect(tombstones(db)).toEqual([]);
    });
  });
});

describe("laitetunnuksen vanheneminen", () => {
  const run = (db: ReturnType<typeof database>, at = NOW) =>
    worker.scheduled({ scheduledTime: at.getTime(), cron: "17 3 * * *" } as unknown as ScheduledController, env(db));

  it("yli 12 kuukauden ilmoitus menettää tunnuksen ja rivi säilyy", async () => {
    const db = database([
      { rid: "vanha", device: DEVICE, received: "2025-10-31T12:00:00.000Z" },
      { rid: "uusi", device: DEVICE, received: "2025-11-02T12:00:00.000Z" },
    ]);

    await run(db);

    expect(remaining(db)).toEqual([`uusi:${DEVICE}`, `vanha:${EXPIRED_DEVICE}`]);
  });

  it("raja on tasan 365 vuorokautta", async () => {
    const db = database([
      { rid: "raja", device: DEVICE, received: "2025-11-01T12:00:00.000Z" },
      { rid: "yli", device: DEVICE, received: "2025-11-01T11:59:59.999Z" },
    ]);

    await run(db);

    expect(remaining(db)).toEqual([`raja:${DEVICE}`, `yli:${EXPIRED_DEVICE}`]);
  });

  it("ajo on toistettavissa", async () => {
    const db = database([{ rid: "vanha", device: DEVICE, received: "2020-01-01T00:00:00.000Z" }]);

    await run(db);
    await run(db);

    expect(remaining(db)).toEqual([`vanha:${EXPIRED_DEVICE}`]);
  });

  it("vanhentunut tunnus ei ole kelvollinen ilmoittajan tunnus", async () => {
    const db = database([{ rid: "vanha", device: EXPIRED_DEVICE }]);

    expect((await forget(env(db), EXPIRED_DEVICE)).status).toBe(400);
    expect(remaining(db)).toHaveLength(1);
  });
});
