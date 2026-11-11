const encoder = new TextEncoder();

function toBase64Url(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

function fromBase64Url(value) {
  const base64 = value.replaceAll("-", "+").replaceAll("_", "/");
  const padded = base64 + "=".repeat((4 - base64.length % 4) % 4);
  const binary = atob(padded);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

export function randomToken(bytes = 32) {
  return toBase64Url(crypto.getRandomValues(new Uint8Array(bytes)));
}

export async function sha256(value) {
  return toBase64Url(new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(value))));
}

export async function createSessionToken(userId, env, nowSeconds = Math.floor(Date.now() / 1000)) {
  if (!env.SESSION_HMAC_SECRET || env.SESSION_HMAC_SECRET.length < 32) throw new Error("session_signing_not_configured");
  const header = toBase64Url(encoder.encode(JSON.stringify({ alg: "HS256", typ: "JWT" })));
  const claims = toBase64Url(encoder.encode(JSON.stringify({
    iss: env.API_ISSUER || "oblivion-api", aud: env.API_AUDIENCE || "oblivion-android",
    sub: userId, jti: crypto.randomUUID(), iat: nowSeconds, exp: nowSeconds + 7 * 24 * 60 * 60
  })));
  const signingInput = `${header}.${claims}`;
  const key = await crypto.subtle.importKey("raw", encoder.encode(env.SESSION_HMAC_SECRET), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const signature = await crypto.subtle.sign("HMAC", key, encoder.encode(signingInput));
  return `${signingInput}.${toBase64Url(new Uint8Array(signature))}`;
}

export async function verifySessionToken(token, env, nowSeconds = Math.floor(Date.now() / 1000)) {
  if (!env.SESSION_HMAC_SECRET || env.SESSION_HMAC_SECRET.length < 32) return null;
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  try {
    const header = JSON.parse(new TextDecoder().decode(fromBase64Url(parts[0])));
    const claims = JSON.parse(new TextDecoder().decode(fromBase64Url(parts[1])));
    if (header.alg !== "HS256" || claims.iss !== (env.API_ISSUER || "oblivion-api") || claims.aud !== (env.API_AUDIENCE || "oblivion-android")) return null;
    if (typeof claims.sub !== "string" || !claims.sub || typeof claims.jti !== "string" || !claims.jti || !Number.isInteger(claims.exp) || claims.exp <= nowSeconds) return null;
    const key = await crypto.subtle.importKey("raw", encoder.encode(env.SESSION_HMAC_SECRET), { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
    const valid = await crypto.subtle.verify("HMAC", key, fromBase64Url(parts[2]), encoder.encode(`${parts[0]}.${parts[1]}`));
    return valid ? claims : null;
  } catch {
    return null;
  }
}

const SECRET_PATTERNS = [
  /((?:authorization\s*[:=]\s*bearer\s+))[^\s,;]+/gi,
  /((?:access[_ -]?token|refresh[_ -]?token|client[_ -]?secret|password|bot[_ -]?token)(?:\s*[:=]\s*))[^\s,;]+/gi,
  /eyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g
];

export function redactSecrets(value) {
  let clean = String(value);
  clean = clean.replace(SECRET_PATTERNS[0], "$1[REDACTED]");
  clean = clean.replace(SECRET_PATTERNS[1], "$1[REDACTED]");
  clean = clean.replace(SECRET_PATTERNS[2], "[REDACTED_TOKEN]");
  return clean;
}

export function safeErrorMessage(error) {
  return redactSecrets(error instanceof Error ? error.message : "request_failed").slice(0, 240);
}
