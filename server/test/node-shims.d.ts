// Vain testien tarvitsemat Noden osat (sql.test.ts): Workerin tyypit eivät sisällä Nodea, eikä @types/node sovi niiden kanssa.
declare module "node:sqlite" {
  export class DatabaseSync {
    constructor(path: string);
    exec(sql: string): void;
    prepare(sql: string): {
      run(...params: unknown[]): unknown;
      all(...params: unknown[]): Array<Record<string, unknown>>;
      get(...params: unknown[]): Record<string, unknown> | undefined;
    };
  }
}

declare module "node:fs" {
  export function readFileSync(path: URL | string, encoding: "utf-8"): string;
}

interface ImportMeta {
  readonly url: string;
}
