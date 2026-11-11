import test from "node:test";
import assert from "node:assert/strict";
import worker, { discordAuthorizeUrl, getSystemHealth, verifyDiscordGuildMember } from "../src/index.js";
import { createSessionToken, redactSecrets, verifySessionToken } from "../src/security.js";

function request(path, method = "GET", body = undefined) {
  return new Request(`https://api.test${path}`, { method, headers: body ? { "content-type": "application/json" } : {}, body: body ? JSON.stringify(body) : undefined });
}

const minimalDb = {
  prepare() {
    return { bind() { return this; }, async run() { return { success: true }; }, async first() { return null; }, async all() { return { results: [] }; } };
  },
  async batch() { return [] }
};

test("health reports service status and configured feature flags only", async () => {
  const response = await worker.fetch(request("/api/health"), {});
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.status, "ok");
  assert.equal(body.configured.discord, false);
  assert.equal(body.configured.database, false);
});

test("admin health checks D1 connectivity and labels storage as binding configuration", async () => {
  const healthy = await getSystemHealth({
    DB: { prepare(sql) { assert.equal(sql, "SELECT 1 AS ok"); return { async first() { return { ok: 1 }; } }; } },
    REPORTS: { get() {}, put() {} }
  });
  assert.equal(healthy.status, 200);
  const body = await healthy.json();
  assert.equal(body.status, "ok");
  assert.equal(body.checks.database_query, true);
  assert.equal(body.checks.report_storage_binding, true);
  assert.equal(body.checks.curseforge_configured, false);

  const degraded = await getSystemHealth({ DB: { prepare() { return { async first() { throw new Error("database unavailable"); } }; } } });
  assert.equal((await degraded.json()).status, "degraded");
});

test("unknown API paths return 404 and unconfigured CurseForge is explicit", async () => {
  assert.equal((await worker.fetch(request("/api/not-a-route"), {})).status, 404);
  const disabled = await worker.fetch(request("/api/curseforge/search"), {});
  assert.equal(disabled.status, 503);
  assert.equal((await disabled.json()).error, "feature_not_configured");
});

test("OAuth start fails closed when configuration is missing", async () => {
  const response = await worker.fetch(request("/api/discord/oauth/start", "POST", {}), {});
  assert.equal(response.status, 503);
  const body = await response.json();
  assert.equal(body.feature, "discord_oauth");
  assert.ok(body.missing.includes("DISCORD_CLIENT_SECRET"));
});

test("OAuth start stores state and returns Discord PKCE URL with fixed redirect", async () => {
  let inserted = 0;
  const db = {
    prepare(sql) {
      return { bind(...values) { this.values = values; return this; }, async run() { inserted++; return { success: true }; }, async first() { return null; }, async all() { return { results: [] }; } };
    }
  };
  const env = {
    DB: db, DISCORD_CLIENT_ID: "client-id", DISCORD_CLIENT_SECRET: "secret-not-returned",
    DISCORD_REDIRECT_URI: "https://api.example.test/api/discord/oauth/callback",
    OBLIVION_CALLBACK_URI: "oblivion://auth/discord", SESSION_HMAC_SECRET: "x".repeat(40)
  };
  const response = await worker.fetch(request("/api/discord/oauth/start", "POST", { client_code_challenge: "c".repeat(43) }), env);
  assert.equal(response.status, 200);
  assert.equal(inserted, 1);
  const body = await response.json();
  const url = new URL(body.authorize_url);
  assert.equal(url.hostname, "discord.com");
  assert.equal(url.searchParams.get("redirect_uri"), env.DISCORD_REDIRECT_URI);
  assert.equal(url.searchParams.get("code_challenge_method"), "S256");
  assert.equal(url.searchParams.get("scope"), "identify");
  assert.ok(url.searchParams.get("state"));
  assert.ok(url.searchParams.get("code_challenge"));
  assert.equal(body.authorize_url.includes(env.DISCORD_CLIENT_SECRET), false);
});

test("OAuth configuration only accepts the registered HTTPS redirect and exact app callback", async () => {
  const env = {
    DB: minimalDb,
    DISCORD_CLIENT_ID: "client-id",
    DISCORD_CLIENT_SECRET: "test-only-discord-client-secret",
    DISCORD_REDIRECT_URI: "https://user:password@example.test/callback",
    OBLIVION_CALLBACK_URI: "oblivion://auth/other",
    SESSION_HMAC_SECRET: "test-only-session-hmac-secret-".repeat(3)
  };
  const response = await worker.fetch(request("/api/discord/oauth/start", "POST", { client_code_challenge: "c".repeat(43) }), env);
  assert.equal(response.status, 503);
  const body = await response.json();
  assert.ok(body.missing.includes("HTTPS DISCORD_REDIRECT_URI"));
  assert.ok(body.missing.includes("OBLIVION_CALLBACK_URI=oblivion://auth/discord"));
});

test("Discord OAuth callback rejects missing/expired state before token exchange", async () => {
  const env = { DB: { prepare() { return { bind() { return this; }, async first() { return null; } }; } } };
  let fetched = false;
  const response = await worker.fetch(request("/api/discord/oauth/callback?code=abc&state=bad"), env, async () => { fetched = true; throw new Error("must not fetch"); });
  assert.equal(response.status, 400);
  assert.equal(fetched, false);
});

test("Discord OAuth denial returns to Oblivion after consuming valid state", async () => {
  let consumed = false;
  const env = {
    OBLIVION_CALLBACK_URI: "oblivion://auth/discord",
    DB: { prepare() { return { bind() { return this; }, async first() { consumed = true; return { code_verifier: "v", app_code_challenge: "c" }; } }; } }
  };
  const response = await worker.fetch(request("/api/discord/oauth/callback?error=access_denied&state=valid"), env);
  assert.equal(response.status, 302);
  assert.equal(consumed, true);
  assert.equal(new URL(response.headers.get("location")).searchParams.get("error"), "discord_authorization_cancelled");
});

test("Discord guild verification requires the configured bot lookup and matching member identity", async () => {
  assert.deepEqual(await verifyDiscordGuildMember("discord-user", {}, async () => { throw new Error("must not fetch"); }), { verified: false, roles: [] });
  const env = { DISCORD_GUILD_ID: "guild-id", DISCORD_BOT_TOKEN: "server-only-token" };
  const verified = await verifyDiscordGuildMember("discord-user", env, async (url, options) => {
    assert.match(url, /\/guilds\/guild-id\/members\/discord-user$/);
    assert.equal(options.headers.authorization, "Bot server-only-token");
    return Response.json({ user: { id: "discord-user" }, roles: ["role-a"] });
  });
  assert.deepEqual(verified, { verified: true, roles: ["role-a"] });
  const mismatched = await verifyDiscordGuildMember("discord-user", env, async () =>
    Response.json({ user: { id: "different-user" }, roles: ["role-a"] }));
  assert.deepEqual(mismatched, { verified: false, roles: [] });
  const absent = await verifyDiscordGuildMember("discord-user", env, async () => new Response(null, { status: 404 }));
  assert.deepEqual(absent, { verified: false, roles: [] });
});

test("sessions are signed, audience-bound and expire", async () => {
  const env = { SESSION_HMAC_SECRET: "s".repeat(40), API_ISSUER: "oblivion-api", API_AUDIENCE: "android" };
  const token = await createSessionToken("user-1", env, 1000);
  assert.equal((await verifySessionToken(token, env, 1001)).sub, "user-1");
  assert.equal(await verifySessionToken(token, env, 999999), null);
  assert.equal(await verifySessionToken(token, { ...env, API_AUDIENCE: "other" }, 1001), null);
});

test("/api/me reports guild verification only for a Discord-sourced Member assignment", async () => {
  const envBase = { SESSION_HMAC_SECRET: "m".repeat(40) };
  const token = await createSessionToken("user-1", envBase);
  for (const [roleSource, expected] of [["manual", false], ["discord", true]]) {
    const db = {
      prepare(sql) {
        let values = [];
        return {
          bind(...args) { values = args; return this; },
          async first() {
            if (sql.includes("FROM revoked_sessions")) return null;
            if (sql.includes("FROM accounts")) return { id: "user-1", username: "Wanderer", avatar_url: null, status: "active", created_at: 1 };
            if (sql.includes("source = 'discord'")) return roleSource === "discord" ? { role: "MEMBER" } : null;
            return null;
          },
          async all() {
            if (sql.includes("SELECT role FROM account_roles WHERE user_id = ? AND (expires_at")) return { results: [{ role: "MEMBER" }] };
            return { results: [] };
          },
          async run() { return { success: true }; }
        };
      },
      async batch() { return []; }
    };
    assert.ok(db);
    const response = await worker.fetch(new Request("https://api.test/api/me", { headers: { authorization: `Bearer ${token}` } }), { ...envBase, DB: db });
    assert.equal(response.status, 200);
    assert.equal((await response.json()).discord_guild_membership_verified, expected);
  }
});

test("redaction removes bearer, named secrets and JWT-looking values", () => {
  const value = redactSecrets("Authorization: Bearer bearer-secret password=hunter2 eyJabcdefghijklmnop.payload.signature");
  assert.equal(value.includes("bearer-secret"), false);
  assert.equal(value.includes("hunter2"), false);
  assert.equal(value.includes("eyJabcdefghijklmnop"), false);
  assert.ok(value.includes("[REDACTED_TOKEN]"));
});

test("reports require session; admin endpoints never trust a client role field", async () => {
  const env = { DB: minimalDb, SESSION_HMAC_SECRET: "a".repeat(40) };
  const report = await worker.fetch(request("/api/reports", "POST", { consent: true, logs: "log" }), env);
  assert.equal(report.status, 401);
  const roleChange = await worker.fetch(request("/api/admin/users/target/roles", "POST", { role: "OWNER", action: "assign", actor_role: "OWNER" }), env);
  assert.equal(roleChange.status, 401);
});

test("CORS only reflects explicitly configured origins", async () => {
  const denied = await worker.fetch(new Request("https://api.test/api/health", { headers: { Origin: "https://evil.example" } }), { ALLOWED_ORIGINS: "https://app.example" });
  assert.equal(denied.status, 403);
  const allowed = await worker.fetch(new Request("https://api.test/api/health", { headers: { Origin: "https://app.example" } }), { ALLOWED_ORIGINS: "https://app.example" });
  assert.equal(allowed.headers.get("access-control-allow-origin"), "https://app.example");
});

test("body length and malformed JSON are rejected", async () => {
  const oversized = new Request("https://api.test/api/discord/oauth/exchange", { method: "POST", headers: { "content-length": "999999", "content-type": "application/json" }, body: "{}" });
  assert.equal((await worker.fetch(oversized, {})).status, 413);
  const malformed = new Request("https://api.test/api/discord/oauth/exchange", { method: "POST", headers: { "content-type": "application/json" }, body: "{" });
  assert.equal((await worker.fetch(malformed, { DB: minimalDb, SESSION_HMAC_SECRET: "s".repeat(40) })).status, 400);
});

test("streaming request bodies without Content-Length are still bounded", async () => {
  const bodyBytes = new Uint8Array(100 * 1024);
  bodyBytes.fill(0x61);
  const oversized = new Request("https://api.test/api/discord/oauth/start", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: new ReadableStream({
      start(controller) {
        controller.enqueue(bodyBytes);
        controller.close();
      }
    }),
    duplex: "half"
  });
  const response = await worker.fetch(oversized, {
    DB: minimalDb,
    DISCORD_CLIENT_ID: "client-id",
    DISCORD_CLIENT_SECRET: "test-only-discord-client-secret",
    DISCORD_REDIRECT_URI: "https://api.example.test/api/discord/oauth/callback",
    OBLIVION_CALLBACK_URI: "oblivion://auth/discord",
    SESSION_HMAC_SECRET: "test-only-session-hmac-secret-".repeat(3)
  });
  assert.equal(response.status, 413);
});

test("authorization URL only uses configured callback destination", () => {
  const url = new URL(discordAuthorizeUrl({ DISCORD_CLIENT_ID: "id", DISCORD_REDIRECT_URI: "https://api.example/cb" }, "state", "challenge"));
  assert.equal(url.searchParams.get("redirect_uri"), "https://api.example/cb");
  assert.equal(url.searchParams.get("code_challenge"), "challenge");
});
