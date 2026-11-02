/**
 * Kustannussuoja: KV-avain `paused` pysäyttää palvelun (503), yhteinen raja sijaintia kohti ja osoitekohtainen raja
 * vastaavat 429. Rajat ovat Rate Limiting -bindingeissä (`wrangler.jsonc`); KV:tä ei käytetä laskurina, koska se sallii
 * yhden kirjoituksen sekunnissa avainta kohti.
 */
export interface Limiter {
  limit(options: { key: string }): Promise<{ success: boolean }>;
}

export interface GuardEnv {
  RATE_IP: Limiter;
  RATE_GLOBAL: Limiter;
  KV: { get(key: string): Promise<string | null> };
}

export type GuardResult = { allowed: true } | { allowed: false; status: 429 | 503; reason: "ip" | "global" | "paused" };

export const PAUSE_KEY = "paused";
export const GLOBAL_KEY = "all";

/**
 * Osoiteraja ensin: yksi osoite ei saa kuluttaa yhteistä kiintiötä yli oman osuutensa. [perIp] on reitin oma osoiteraja
 * (yrityshaku kirjoitettaessa: `RATE_SEARCH`), oletuksena `RATE_IP`. [shared] on yhteinen raja; null jättää sen pois, kun
 * reitillä on oma (osoitehaku ei kuormita kantaa eikä saa viedä kiintiötä karttakohteilta).
 */
export async function guard(
  env: GuardEnv,
  clientIp: string,
  perIp: Limiter = env.RATE_IP,
  shared: Limiter | null = env.RATE_GLOBAL,
): Promise<GuardResult> {
  if ((await env.KV.get(PAUSE_KEY)) !== null) return { allowed: false, status: 503, reason: "paused" };
  if (!(await perIp.limit({ key: limiterKey(clientIp) })).success) return { allowed: false, status: 429, reason: "ip" };
  if (shared !== null && !(await shared.limit({ key: GLOBAL_KEY })).success) return { allowed: false, status: 429, reason: "global" };
  return { allowed: true };
}

/** IPv4 sellaisenaan; IPv6 /64-lohkona (neljä ensimmäistä ryhmää), koska yhdellä liittymällä on koko lohko. */
export function limiterKey(ip: string): string {
  if (!ip.includes(":")) return ip;
  const [head, tail = ""] = ip.split("::");
  const headGroups = head === "" ? [] : head.split(":");
  const tailGroups = tail === "" ? [] : tail.split(":");
  const zeros = Math.max(0, 8 - headGroups.length - tailGroups.length);
  const groups = [...headGroups, ...Array<string>(zeros).fill("0"), ...tailGroups];
  return groups.slice(0, 4).map((g) => g.toLowerCase().replace(/^0+(?=.)/, "")).join(":");
}
