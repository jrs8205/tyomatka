import { describe, expect, it } from "vitest";
import { guard } from "../src/guard";

function limiter(success: boolean) {
  const calls: string[] = [];
  return {
    calls,
    limit: async ({ key }: { key: string }) => {
      calls.push(key);
      return { success };
    },
  };
}

const kv = (value: string | null) => ({ get: async () => value });

describe("guard", () => {
  it("päästää läpi, kun rajat eivät täyty eikä palvelua ole pysäytetty", async () => {
    const ip = limiter(true);
    const result = await guard({ RATE_IP: ip, RATE_GLOBAL: limiter(true), KV: kv(null) }, "203.0.113.7");
    expect(result).toEqual({ allowed: true });
    expect(ip.calls).toEqual(["203.0.113.7"]);
  });

  it("pysäytyskytkin vastaa 503 ennen rajoja", async () => {
    const ip = limiter(true);
    const global = limiter(true);
    const result = await guard({ RATE_IP: ip, RATE_GLOBAL: global, KV: kv("1") }, "203.0.113.7");
    expect(result).toEqual({ allowed: false, status: 503, reason: "paused" });
    expect(ip.calls).toEqual([]);
    expect(global.calls).toEqual([]);
  });

  it("osoitekohtainen raja vastaa 429 ennen yhteistä, jottei yksi osoite kuluta yhteistä kiintiötä", async () => {
    const global = limiter(true);
    const result = await guard({ RATE_IP: limiter(false), RATE_GLOBAL: global, KV: kv(null) }, "203.0.113.7");
    expect(result).toEqual({ allowed: false, status: 429, reason: "ip" });
    expect(global.calls).toEqual([]);
  });

  it("yhteinen raja vastaa 429", async () => {
    const skipped = limiter(false);
    const own = await guard({ RATE_IP: limiter(true), RATE_GLOBAL: skipped, KV: kv(null) }, "203.0.113.7", limiter(true), null);
    expect(own).toEqual({ allowed: true });
    expect(skipped.calls).toEqual([]);
    const result = await guard({ RATE_IP: limiter(true), RATE_GLOBAL: limiter(false), KV: kv(null) }, "203.0.113.7");
    expect(result).toEqual({ allowed: false, status: 429, reason: "global" });
  });

  it("IPv6-osoite rajataan /64-lohkona, ettei osoitteen vaihto lohkon sisällä kierrä rajaa", async () => {
    const ip = limiter(true);
    await guard({ RATE_IP: ip, RATE_GLOBAL: limiter(true), KV: kv(null) }, "2001:db8:85a3:8d3:1319:8a2e:370:7348");
    await guard({ RATE_IP: ip, RATE_GLOBAL: limiter(true), KV: kv(null) }, "2001:db8:85a3:8d3::1");
    await guard({ RATE_IP: ip, RATE_GLOBAL: limiter(true), KV: kv(null) }, "2001:db8::1");
    expect(ip.calls).toEqual(["2001:db8:85a3:8d3", "2001:db8:85a3:8d3", "2001:db8:0:0"]);
  });

  it("annettu osoiteraja korvaa RATE_IP:n", async () => {
    const ip = limiter(true);
    const search = limiter(false);
    const result = await guard({ RATE_IP: ip, RATE_GLOBAL: limiter(true), KV: kv(null) }, "203.0.113.7", search);
    expect(result).toEqual({ allowed: false, status: 429, reason: "ip" });
    expect(search.calls).toEqual(["203.0.113.7"]);
    expect(ip.calls).toEqual([]);
  });
});
