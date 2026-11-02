import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";

/** Oikea SQLite D1:n rajapinnan takana: palvelimen funktiot ajetaan testeissä samoilla lauseilla kuin tuotannossa. */
export function sqliteD1() {
  const db = new DatabaseSync(":memory:");
  db.exec(readFileSync(new URL("../schema.sql", import.meta.url), "utf-8"));
  const prepare = (sql: string) => {
    const statement = {
      sql,
      params: [] as unknown[],
      bind(...params: unknown[]) {
        statement.params = params;
        return statement;
      },
      async all<T>() {
        return { results: db.prepare(sql).all(...(statement.params as never[])) as T[] };
      },
      async first<T>() {
        return (db.prepare(sql).get(...(statement.params as never[])) ?? null) as T | null;
      },
      async run() {
        const result = db.prepare(sql).run(...(statement.params as never[])) as { changes: number | bigint };
        return { success: true, meta: { changes: Number(result.changes) } };
      },
    };
    return statement;
  };
  let inBatch = false;
  return {
    raw: db,
    prepare,
    /** Tosi erän aikana: oikeassa kannassa toinen pyyntö ei pääse tähän väliin. */
    inTransaction: () => inBatch,
    /** D1:n `batch` on yksi transaktio: virhe perii kaikki lauseet. */
    async batch(statements: Array<ReturnType<typeof prepare>>) {
      db.exec("BEGIN");
      inBatch = true;
      try {
        const results = [];
        // Kuten D1: kyselyn tulos riveinä, muun lauseen tulos muutosmääränä.
        for (const s of statements) results.push(/^\s*(SELECT|WITH)/i.test(s.sql) ? { success: true, ...(await s.all()) } : await s.run());
        db.exec("COMMIT");
        return results;
      } catch (error) {
        db.exec("ROLLBACK");
        throw error;
      } finally {
        inBatch = false;
      }
    },
  };
}
