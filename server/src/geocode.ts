/**
 * Digitransitin osoitehaku sovelluksen puolesta: avain pysyy palvelimella,
 * joten sovellukseen ei tarvita avaimia. Hakutekstiä tai pistettä ei tallenneta, ja Digitransit näkee vain palvelimen.
 */
const UPSTREAM = "https://api.digitransit.fi/geocoding/v1";
const USER_AGENT = "Tyomatka-palvelin/1.0 (+https://poi.jrs8205.com)";
const TIMEOUT_MS = 5_000;
const MAX_VALUE_LENGTH = 200;
const MAX_SIZE = 10;

const ENDPOINTS: ReadonlySet<string> = new Set(["autocomplete", "search", "reverse"]);
const ALLOWED = [
  "text",
  "size",
  "layers",
  "lang",
  "boundary.rect.min_lat",
  "boundary.rect.max_lat",
  "boundary.rect.min_lon",
  "boundary.rect.max_lon",
  "focus.point.lat",
  "focus.point.lon",
  "point.lat",
  "point.lon",
];

/** Digitransitin osoite vain tunnetuilla parametreilla; null, jos pyyntö ei kelpaa. */
export function upstreamUrl(endpoint: string, query: URLSearchParams): string | null {
  if (!ENDPOINTS.has(endpoint)) return null;
  const forwarded = new URLSearchParams();
  for (const name of ALLOWED) {
    const value = query.get(name);
    if (value === null) continue;
    if (value.length > MAX_VALUE_LENGTH) return null;
    forwarded.set(name, value);
  }
  const size = forwarded.get("size");
  if (size !== null && !isValidSize(size)) return null;
  const complete =
    endpoint === "reverse"
      ? forwarded.has("point.lat") && forwarded.has("point.lon")
      : (forwarded.get("text") ?? "").trim() !== "";
  return complete ? `${UPSTREAM}/${endpoint}?${forwarded}` : null;
}

function isValidSize(size: string): boolean {
  return /^\d+$/.test(size) && Number(size) >= 1 && Number(size) <= MAX_SIZE;
}

/** Digitransitin vastaus sellaisenaan; kaikki muu kuin 200 on null, ja sovellus siirtyy varahakuun. Avain ei kulje virheissä. */
export async function fetchUpstream(url: string, key: string): Promise<string | null> {
  try {
    const response = await fetch(url, {
      headers: { "digitransit-subscription-key": key, "User-Agent": USER_AGENT, Accept: "application/json" },
      // Uudelleenohjaus veisi avainotsakkeen toiselle palvelimelle, ja se on 503 kuten muutkin muut vastaukset kuin 200.
      redirect: "manual",
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
    return response.status === 200 ? await response.text() : null;
  } catch {
    return null;
  }
}
