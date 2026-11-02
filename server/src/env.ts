/** Workerin sidokset (`wrangler.jsonc`) ja salaisuudet `ADMIN_TOKEN` ja `DIGITRANSIT_KEY` (`wrangler secret put`). */
export interface Env {
  DB: D1Database;
  KV: KVNamespace;
  RATE_IP: RateLimit;
  RATE_GLOBAL: RateLimit;
  RATE_REPORT: RateLimit;
  RATE_SEARCH: RateLimit;
  RATE_GEOCODE_IP: RateLimit;
  RATE_GEOCODE: RateLimit;
  ADMIN_TOKEN: string;
  /** Puuttuessa osoitehaku vastaa 503, ja sovellus siirtyy varahakuun. */
  DIGITRANSIT_KEY?: string;
}
