import { hasEntitlement, hasPermission, mayAssignRole, mayModerateAccount, rolesFromDiscordMembership, ROLE, PERMISSIONS } from "./access-control.js";
import { createSessionToken, randomToken, redactSecrets, safeErrorMessage, sha256, verifySessionToken } from "./security.js";

const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
  "x-content-type-options": "nosniff",
  "referrer-policy": "no-referrer",
  "x-frame-options": "DENY",
  "content-security-policy": "default-src 'none'; frame-ancestors 'none'"
};
const MAX_BODY_BYTES = 96 * 1024;
const REPORT_LOG_MAX_CHARS = 64 * 1024;

function json(data, status = 200, headers = {}) {
  return new Response(JSON.stringify(data), { status, headers: { ...JSON_HEADERS, ...headers } });
}

function methodNotAllowed(allowed) {
  return json({ error: "method_not_allowed", allowed }, 405, { allow: allowed.join(", ") });
}

function serviceUnavailable(feature, missing = []) {
  return json({ error: "feature_not_configured", feature, missing }, 503);
}

function isD1Configured(env) {
  return Boolean(env.DB && typeof env.DB.prepare === "function");
}

function safeOblivionCallbackUri(value) {
  try {
    const uri = new URL(value);
    return uri.protocol === "oblivion:" && uri.hostname === "auth" && uri.pathname === "/discord" &&
      !uri.username && !uri.password && !uri.port && !uri.search && !uri.hash;
  } catch {
    return false;
  }
}

function safeDiscordRedirectUri(value) {
  try {
    const uri = new URL(value);
    return uri.protocol === "https:" && Boolean(uri.host) && !uri.username && !uri.password && !uri.hash;
  }
  catch { return false; }
}

export function discordAuthorizeUrl(env, state, challenge) {
  const url = new URL("https://discord.com/oauth2/authorize");
  url.searchParams.set("client_id", env.DISCORD_CLIENT_ID);
  url.searchParams.set("redirect_uri", env.DISCORD_REDIRECT_URI);
  url.searchParams.set("response_type", "code");
  url.searchParams.set("scope", "identify");
  url.searchParams.set("state", state);
  url.searchParams.set("code_challenge", challenge);
  url.searchParams.set("code_challenge_method", "S256");
  return url.toString();
}

async function rateLimit(request, env, suffix = "api") {
  if (!env.API_LIMITER?.limit) return true;
  const ip = request.headers.get("CF-Connecting-IP") || "unknown";
  const result = await env.API_LIMITER.limit({ key: `${suffix}:${ip}` });
  return result.success;
}

function corsHeaders(request, env) {
  const origin = request.headers.get("Origin");
  if (!origin) return {};
  const origins = (env.ALLOWED_ORIGINS || "").split(",").map((item) => item.trim()).filter(Boolean);
  return origins.includes(origin) ? { "access-control-allow-origin": origin, vary: "Origin" } : null;
}

async function parseJson(request, maxBytes = MAX_BODY_BYTES) {
  const length = Number(request.headers.get("Content-Length") || "0");
  if (length > maxBytes) throw new RangeError("request_too_large");
  if (!request.body) throw new SyntaxError("invalid_json");

  const reader = request.body.getReader();
  const chunks = [];
  let totalBytes = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      totalBytes += value.byteLength;
      if (totalBytes > maxBytes) {
        await reader.cancel("request_too_large").catch(() => {});
        throw new RangeError("request_too_large");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const bytes = new Uint8Array(totalBytes);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  let text;
  try { text = new TextDecoder("utf-8", { fatal: true }).decode(bytes); }
  catch { throw new SyntaxError("invalid_json"); }
  try { return JSON.parse(text); } catch { throw new SyntaxError("invalid_json"); }
}

async function authenticate(request, env) {
  const authorization = request.headers.get("Authorization") || "";
  const token = /^Bearer ([A-Za-z0-9_.-]+)$/.exec(authorization)?.[1];
  if (!token) return { response: json({ error: "unauthorized" }, 401) };
  const claims = await verifySessionToken(token, env);
  if (!claims) return { response: json({ error: "invalid_or_expired_session" }, 401) };
  if (!isD1Configured(env)) return { response: serviceUnavailable("account_api", ["D1 database"]) };
  const revoked = await env.DB.prepare("SELECT jti FROM revoked_sessions WHERE jti = ? AND expires_at > ?")
    .bind(claims.jti, Math.floor(Date.now() / 1000)).first();
  if (revoked) return { response: json({ error: "session_revoked" }, 401) };
  const account = await env.DB.prepare("SELECT id, username, avatar_url, status, created_at FROM accounts WHERE id = ?")
    .bind(claims.sub).first();
  if (!account) return { response: json({ error: "account_not_found" }, 401) };
  let status = account.status;
  if (status !== "active") {
    const latestAction = await env.DB.prepare("SELECT status, expires_at FROM moderation_history WHERE target_user_id = ? ORDER BY occurred_at DESC LIMIT 1")
      .bind(account.id).first();
    const now = Math.floor(Date.now() / 1000);
    if (latestAction?.status === status && latestAction.expires_at && latestAction.expires_at <= now) {
      await env.DB.batch([
        env.DB.prepare("UPDATE accounts SET status = 'active', updated_at = ? WHERE id = ? AND status = ?").bind(now, account.id, status),
        env.DB.prepare("INSERT INTO audit_events (id, target_user_id, action, metadata_json, occurred_at) VALUES (?, ?, 'account.suspension.expired', ?, ?)")
          .bind(crypto.randomUUID(), account.id, JSON.stringify({ previous_status: status }), now)
      ]);
      status = "active";
    }
  }
  if (status !== "active") return { response: json({ error: "account_inactive", status }, 403) };
  const now = Math.floor(Date.now() / 1000);
  const expired = await env.DB.prepare("DELETE FROM account_roles WHERE user_id = ? AND expires_at IS NOT NULL AND expires_at <= ? RETURNING role")
    .bind(account.id, now).all();
  if ((expired.results || []).length) {
    await env.DB.batch(expired.results.map((row) => env.DB.prepare("INSERT INTO role_history (id, target_user_id, role, action, reason, occurred_at) VALUES (?, ?, ?, 'expired', 'Role assignment expired', ?)")
      .bind(crypto.randomUUID(), account.id, row.role, now)));
  }
  const rows = await env.DB.prepare("SELECT role FROM account_roles WHERE user_id = ? AND (expires_at IS NULL OR expires_at > ?)")
    .bind(account.id, now).all();
  const ownerId = await configuredOwnerId(env, env.DB);
  const roles = (rows.results || []).map((row) => row.role).filter((role) => role !== ROLE.OWNER || account.id === ownerId);
  return { account, roles, claims };
}

async function configuredOwnerId(env, db) {
  if (env.OWNER_USER_ID) return env.OWNER_USER_ID;
  if (!env.OWNER_DISCORD_USER_ID) return null;
  const identity = await db.prepare("SELECT user_id FROM identities WHERE provider = 'discord' AND provider_user_id = ?")
    .bind(env.OWNER_DISCORD_USER_ID).first();
  return identity?.user_id || null;
}

async function appendAudit(db, actorId, targetId, action, metadata = {}) {
  await db.prepare("INSERT INTO audit_events (id, actor_user_id, target_user_id, action, metadata_json, occurred_at) VALUES (?, ?, ?, ?, ?, ?)")
    .bind(crypto.randomUUID(), actorId || null, targetId || null, action, JSON.stringify(metadata), Math.floor(Date.now() / 1000)).run();
}

async function handleDiscordStart(request, env) {
  const missing = [];
  if (!env.DISCORD_CLIENT_ID) missing.push("DISCORD_CLIENT_ID");
  if (!env.DISCORD_CLIENT_SECRET) missing.push("DISCORD_CLIENT_SECRET");
  if (!env.DISCORD_REDIRECT_URI || !safeDiscordRedirectUri(env.DISCORD_REDIRECT_URI)) missing.push("HTTPS DISCORD_REDIRECT_URI");
  if (!env.OBLIVION_CALLBACK_URI || !safeOblivionCallbackUri(env.OBLIVION_CALLBACK_URI)) missing.push("OBLIVION_CALLBACK_URI=oblivion://auth/discord");
  if (!env.SESSION_HMAC_SECRET || env.SESSION_HMAC_SECRET.length < 32) missing.push("SESSION_HMAC_SECRET (32+ characters)");
  if (!isD1Configured(env)) missing.push("D1 database and applied migrations");
  if (missing.length) return serviceUnavailable("discord_oauth", missing);

  const body = await parseJson(request, 4096);
  const clientCodeChallenge = body.client_code_challenge;
  if (typeof clientCodeChallenge !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(clientCodeChallenge)) {
    return json({ error: "valid_client_pkce_challenge_required" }, 400);
  }

  const state = randomToken(32);
  const verifier = randomToken(48);
  const challenge = await sha256(verifier);
  const now = Math.floor(Date.now() / 1000);
  await env.DB.prepare("INSERT INTO oauth_states (state_hash, code_verifier, app_code_challenge, created_at, expires_at) VALUES (?, ?, ?, ?, ?)")
    .bind(await sha256(state), verifier, clientCodeChallenge, now, now + 600).run();
  return json({ authorize_url: discordAuthorizeUrl(env, state, challenge), expires_in: 600, provider: "discord" });
}

async function consumeOAuthState(db, state) {
  if (!state || state.length > 256) return null;
  return db.prepare("DELETE FROM oauth_states WHERE state_hash = ? AND expires_at > ? RETURNING code_verifier, app_code_challenge")
    .bind(await sha256(state), Math.floor(Date.now() / 1000)).first();
}

async function exchangeDiscordCode(code, verifier, env, fetchImpl) {
  const body = new URLSearchParams({
    client_id: env.DISCORD_CLIENT_ID,
    client_secret: env.DISCORD_CLIENT_SECRET,
    grant_type: "authorization_code",
    code,
    redirect_uri: env.DISCORD_REDIRECT_URI,
    code_verifier: verifier
  });
  const response = await fetchImpl("https://discord.com/api/v10/oauth2/token", {
    method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body
  });
  if (!response.ok) throw new Error("discord_token_exchange_failed");
  const result = await response.json();
  if (typeof result.access_token !== "string" || result.token_type !== "Bearer") throw new Error("discord_token_response_invalid");
  return result.access_token;
}

async function findOrCreateDiscordAccount(db, discordUser, env) {
  const linked = await db.prepare("SELECT user_id FROM identities WHERE provider = 'discord' AND provider_user_id = ?")
    .bind(discordUser.id).first();
  if (linked) return linked.user_id;
  const userId = crypto.randomUUID();
  const createdAt = Math.floor(Date.now() / 1000);
  const requestedName = String(discordUser.global_name || discordUser.username || "wanderer")
    .replace(/[^A-Za-z0-9 _-]/g, "").trim().slice(0, 24) || "Wanderer";
  const username = `${requestedName.slice(0, 18)}-${randomToken(3).slice(0, 4)}`;
  const avatarUrl = discordUser.avatar
    ? `https://cdn.discordapp.com/avatars/${encodeURIComponent(discordUser.id)}/${encodeURIComponent(discordUser.avatar)}.png?size=128`
    : null;
  await db.batch([
    db.prepare("INSERT INTO accounts (id, username, avatar_url, status, created_at, updated_at) VALUES (?, ?, ?, 'active', ?, ?)")
      .bind(userId, username, avatarUrl, createdAt, createdAt),
    db.prepare("INSERT INTO identities (provider, provider_user_id, user_id, provider_username, linked_at) VALUES ('discord', ?, ?, ?, ?)")
      .bind(discordUser.id, userId, String(discordUser.username || "").slice(0, 64), createdAt),
    db.prepare("INSERT INTO account_roles (user_id, role, source, assigned_at) VALUES (?, 'NEWCOMER', 'system', ?)")
      .bind(userId, createdAt),
    db.prepare("INSERT INTO role_history (id, target_user_id, role, action, reason, occurred_at) VALUES (?, ?, 'NEWCOMER', 'assigned', 'Default role', ?)")
      .bind(crypto.randomUUID(), userId, createdAt),
    db.prepare("INSERT INTO audit_events (id, target_user_id, action, metadata_json, occurred_at) VALUES (?, ?, 'account.created', '{\"provider\":\"discord\"}', ?)")
      .bind(crypto.randomUUID(), userId, createdAt)
  ]);
  return userId;
}

async function provisionConfiguredOwner(db, userId, discordUserId, env) {
  if (!env.OWNER_DISCORD_USER_ID || env.OWNER_DISCORD_USER_ID !== discordUserId) return;
  const existing = await db.prepare("SELECT role FROM account_roles WHERE user_id = ? AND role = 'OWNER'").bind(userId).first();
  if (existing) return;
  const now = Math.floor(Date.now() / 1000);
  await db.batch([
    db.prepare("INSERT OR IGNORE INTO account_roles (user_id, role, source, assigned_at) VALUES (?, 'OWNER', 'owner_config', ?)").bind(userId, now),
    db.prepare("DELETE FROM account_roles WHERE user_id = ? AND role = 'NEWCOMER'").bind(userId),
    db.prepare("INSERT INTO role_history (id, target_user_id, role, action, reason, occurred_at) VALUES (?, ?, 'OWNER', 'assigned', 'Configured owner identity', ?)").bind(crypto.randomUUID(), userId, now),
    db.prepare("INSERT INTO audit_events (id, target_user_id, action, metadata_json, occurred_at) VALUES (?, ?, 'owner.provisioned', '{\"method\":\"configured_discord_identity\"}', ?)").bind(crypto.randomUUID(), userId, now)
  ]);
}

export async function verifyDiscordGuildMember(discordUserId, env, fetchImpl = fetch) {
  if (!env.DISCORD_GUILD_ID || !env.DISCORD_BOT_TOKEN) return { verified: false, roles: [] };
  const endpoint = `https://discord.com/api/v10/guilds/${encodeURIComponent(env.DISCORD_GUILD_ID)}/members/${encodeURIComponent(discordUserId)}`;
  try {
    const response = await fetchImpl(endpoint, { headers: { authorization: `Bot ${env.DISCORD_BOT_TOKEN}` } });
    if (response.status === 404 || response.status === 403) return { verified: false, roles: [] };
    if (!response.ok) return { verified: false, roles: [] };
    const member = await response.json();
    if (member.user?.id !== discordUserId || !Array.isArray(member.roles)) return { verified: false, roles: [] };
    return { verified: true, roles: member.roles.filter((role) => typeof role === "string") };
  } catch {
    return { verified: false, roles: [] };
  }
}

async function syncDiscordRoles(db, userId, membership, env) {
  let roleMap = {};
  try { roleMap = JSON.parse(env.DISCORD_ROLE_MAP_JSON || "{}"); } catch { roleMap = {}; }
  const configured = await configuredOwnerId(env, db);
  const account = await db.prepare("SELECT id FROM accounts WHERE id = ?").bind(userId).first();
  const current = await db.prepare("SELECT role, source FROM account_roles WHERE user_id = ?").bind(userId).all();
  const desired = rolesFromDiscordMembership({ isGuildMember: membership.verified, discordRoleIds: membership.roles, roleMap });
  const now = Math.floor(Date.now() / 1000);
  const statements = [];
  for (const row of current.results || []) {
    if (row.source === "discord" && !desired.includes(row.role)) {
      statements.push(db.prepare("DELETE FROM account_roles WHERE user_id = ? AND role = ? AND source = 'discord'").bind(userId, row.role));
      statements.push(db.prepare("INSERT INTO role_history (id, target_user_id, role, action, reason, occurred_at) VALUES (?, ?, ?, 'removed', 'Discord membership sync', ?)").bind(crypto.randomUUID(), userId, row.role, now));
    }
  }
  const existingRoles = new Set((current.results || []).map((row) => row.role));
  for (const role of desired) {
    if (role === ROLE.OWNER || role === ROLE.ADMIN || (role === ROLE.NEWCOMER && configured === userId) || existingRoles.has(role)) continue;
    statements.push(db.prepare("INSERT OR IGNORE INTO account_roles (user_id, role, source, assigned_at) VALUES (?, ?, 'discord', ?)").bind(userId, role, now));
    statements.push(db.prepare("INSERT INTO role_history (id, target_user_id, role, action, reason, occurred_at) VALUES (?, ?, ?, 'assigned', 'Discord membership sync', ?)").bind(crypto.randomUUID(), userId, role, now));
  }
  if (configured !== userId && !desired.includes(ROLE.NEWCOMER)) {
    statements.push(db.prepare("DELETE FROM account_roles WHERE user_id = ? AND role = 'NEWCOMER' AND source = 'system'").bind(userId));
  }
  if (statements.length) await db.batch(statements);
  if (!account) throw new Error("account_not_found");
}

async function handleDiscordCallback(request, env, fetchImpl = fetch) {
  const url = new URL(request.url);
  const oauthError = url.searchParams.get("error");
  const state = url.searchParams.get("state");
  const code = url.searchParams.get("code");
  if (!isD1Configured(env)) return serviceUnavailable("discord_oauth", ["D1 database"]);
  const pending = await consumeOAuthState(env.DB, state);
  if (!pending) return json({ error: "oauth_state_invalid_or_expired" }, 400);
  if (oauthError || !code || code.length > 2048) {
    const callback = new URL(env.OBLIVION_CALLBACK_URI);
    callback.searchParams.set("error", "discord_authorization_cancelled");
    return new Response(null, { status: 302, headers: { location: callback.toString(), "cache-control": "no-store", "referrer-policy": "no-referrer" } });
  }
  try {
    const accessToken = await exchangeDiscordCode(code, pending.code_verifier, env, fetchImpl);
    const userResponse = await fetchImpl("https://discord.com/api/v10/users/@me", { headers: { authorization: `Bearer ${accessToken}` } });
    if (!userResponse.ok) throw new Error("discord_identity_request_failed");
    const discordUser = await userResponse.json();
    if (typeof discordUser.id !== "string" || typeof discordUser.username !== "string") throw new Error("discord_identity_invalid");
    const userId = await findOrCreateDiscordAccount(env.DB, discordUser, env);
    await provisionConfiguredOwner(env.DB, userId, discordUser.id, env);
    const membership = await verifyDiscordGuildMember(discordUser.id, env, fetchImpl);
    await syncDiscordRoles(env.DB, userId, membership, env);
    const appCode = randomToken(32);
    const now = Math.floor(Date.now() / 1000);
    await env.DB.prepare("INSERT INTO app_auth_codes (code_hash, client_code_challenge, user_id, created_at, expires_at) VALUES (?, ?, ?, ?, ?)")
      .bind(await sha256(appCode), pending.app_code_challenge, userId, now, now + 90).run();
    const callback = new URL(env.OBLIVION_CALLBACK_URI);
    callback.searchParams.set("code", appCode);
    return new Response(null, { status: 302, headers: { location: callback.toString(), "cache-control": "no-store", "referrer-policy": "no-referrer" } });
  } catch (error) {
    return json({ error: "discord_authentication_failed", message: safeErrorMessage(error) }, 502);
  }
}

async function exchangeAppCode(request, env) {
  const missing = [];
  if (!env.SESSION_HMAC_SECRET || env.SESSION_HMAC_SECRET.length < 32) missing.push("SESSION_HMAC_SECRET (32+ characters)");
  if (!isD1Configured(env)) missing.push("D1 database and migrations");
  if (missing.length) return serviceUnavailable("session_exchange", missing);
  const body = await parseJson(request, 4096);
  if (typeof body.code !== "string" || body.code.length < 24 || body.code.length > 256 || typeof body.client_verifier !== "string" || !/^[A-Za-z0-9_-]{43,128}$/.test(body.client_verifier)) return json({ error: "invalid_code_or_verifier" }, 400);
  const consumed = await env.DB.prepare("DELETE FROM app_auth_codes WHERE code_hash = ? AND client_code_challenge = ? AND expires_at > ? RETURNING user_id")
    .bind(await sha256(body.code), await sha256(body.client_verifier), Math.floor(Date.now() / 1000)).first();
  if (!consumed) return json({ error: "invalid_or_expired_code" }, 401);
  const token = await createSessionToken(consumed.user_id, env);
  return json({ access_token: token, token_type: "Bearer", expires_in: 604800 });
}

async function submitDiagnostic(request, env, actor) {
  if (!env.REPORTS?.put || !isD1Configured(env)) return serviceUnavailable("diagnostic_reports", ["private R2 bucket", "D1 database"]);
  const body = await parseJson(request, MAX_BODY_BYTES);
  if (body.consent !== true) return json({ error: "explicit_consent_required" }, 400);
  if (typeof body.logs !== "string" || body.logs.length > REPORT_LOG_MAX_CHARS) return json({ error: "invalid_or_oversized_logs" }, 400);
  const appVersion = typeof body.app_version === "string" && /^[A-Za-z0-9.+_-]{1,40}$/.test(body.app_version) ? body.app_version : "unknown";
  const category = typeof body.category === "string" && /^[a-z0-9_-]{1,48}$/i.test(body.category) ? body.category : "general";
  const clean = redactSecrets(body.logs).replace(/\r\n/g, "\n");
  const id = crypto.randomUUID();
  const objectKey = `reports/${id}.txt`;
  const createdAt = Math.floor(Date.now() / 1000);
  await env.REPORTS.put(objectKey, clean, { httpMetadata: { contentType: "text/plain; charset=utf-8", cacheControl: "private, no-store" }, customMetadata: { report_id: id } });
  try {
    await env.DB.batch([
      env.DB.prepare("INSERT INTO diagnostic_reports (id, user_id, object_key, category, app_version, created_at) VALUES (?, ?, ?, ?, ?, ?)")
        .bind(id, actor.account.id, objectKey, category, appVersion, createdAt),
      env.DB.prepare("INSERT INTO audit_events (id, actor_user_id, target_user_id, action, metadata_json, occurred_at) VALUES (?, ?, ?, 'diagnostic_report.submitted', ?, ?)")
        .bind(crypto.randomUUID(), actor.account.id, actor.account.id, JSON.stringify({ report_id: id, category, app_version: appVersion }), createdAt)
    ]);
  } catch (error) {
    await env.REPORTS.delete(objectKey).catch(() => {});
    throw error;
  }
  return json({ report_id: id, status: "received", created_at: createdAt }, 202);
}

async function listReports(env, actor) {
  const result = await env.DB.prepare("SELECT id, user_id, category, app_version, created_at, status FROM diagnostic_reports ORDER BY created_at DESC LIMIT 100").all();
  await appendAudit(env.DB, actor.account.id, null, "diagnostic_reports.listed", { count: (result.results || []).length });
  return json({ reports: result.results || [] });
}

async function getReport(env, id, actor) {
  const row = await env.DB.prepare("SELECT id, object_key, category, app_version, created_at FROM diagnostic_reports WHERE id = ?").bind(id).first();
  if (!row) return json({ error: "not_found" }, 404);
  await appendAudit(env.DB, actor.account.id, null, "diagnostic_report.viewed", { report_id: id });
  const object = await env.REPORTS.get(row.object_key);
  if (!object) return json({ error: "report_blob_missing" }, 410);
  return new Response(object.body, { status: 200, headers: { ...JSON_HEADERS, "content-type": "text/plain; charset=utf-8", "content-disposition": `attachment; filename="oblivion-${row.id}.txt"` } });
}

async function updateRole(request, env, actor, targetId) {
  const body = await parseJson(request, 4096);
  const role = body.role;
  const action = body.action;
  if (typeof role !== "string" || !Object.hasOwn(ROLE, role) || !["assign", "remove"].includes(action)) return json({ error: "invalid_role_request" }, 400);
  const target = await env.DB.prepare("SELECT id FROM accounts WHERE id = ?").bind(targetId).first();
  if (!target) return json({ error: "user_not_found" }, 404);
  const ownerId = await configuredOwnerId(env, env.DB);
  const targetRows = await env.DB.prepare("SELECT role FROM account_roles WHERE user_id = ? AND (expires_at IS NULL OR expires_at > ?)")
    .bind(targetId, Math.floor(Date.now() / 1000)).all();
  const targetRoles = (targetRows.results || []).map((row) => row.role);
  if (!mayAssignRole({ actorId: actor.account.id, actorRoles: actor.roles, targetId, targetRoles, role, configuredOwnerId: ownerId })) {
    return json({ error: "forbidden_role_change" }, 403);
  }
  const expiresAt = body.expires_at == null ? null : Number(body.expires_at);
  if (expiresAt != null && (!Number.isSafeInteger(expiresAt) || expiresAt <= Math.floor(Date.now() / 1000))) return json({ error: "invalid_expiration" }, 400);
  if (action === "remove" && expiresAt != null) return json({ error: "expiration_not_valid_for_removal" }, 400);
  const reason = typeof body.reason === "string" ? body.reason.trim().slice(0, 300) : "";
  const now = Math.floor(Date.now() / 1000);
  const statements = [];
  if (action === "assign") {
    statements.push(env.DB.prepare("INSERT OR REPLACE INTO account_roles (user_id, role, source, assigned_by, assigned_at, expires_at) VALUES (?, ?, 'manual', ?, ?, ?)")
      .bind(targetId, role, actor.account.id, now, expiresAt));
  } else {
    const assignment = await env.DB.prepare("SELECT source FROM account_roles WHERE user_id = ? AND role = ?").bind(targetId, role).first();
    if (!assignment) return json({ error: "role_not_assigned" }, 404);
    if (assignment.source !== "manual") return json({ error: "role_managed_by_identity_provider" }, 409);
    statements.push(env.DB.prepare("DELETE FROM account_roles WHERE user_id = ? AND role = ? AND source = 'manual'").bind(targetId, role));
  }
  statements.push(
    env.DB.prepare("INSERT INTO role_history (id, target_user_id, actor_user_id, role, action, reason, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?)")
      .bind(crypto.randomUUID(), targetId, actor.account.id, role, action === "assign" ? "assigned" : "removed", reason || null, now),
    env.DB.prepare("INSERT INTO audit_events (id, actor_user_id, target_user_id, action, metadata_json, occurred_at) VALUES (?, ?, ?, ?, ?, ?)")
      .bind(crypto.randomUUID(), actor.account.id, targetId, `role.${action}`, JSON.stringify({ role, expires_at: expiresAt }), now)
  );
  await env.DB.batch(statements);
  return json({ user_id: targetId, role, action, expires_at: expiresAt });
}

async function moderateAccount(request, env, actor, targetId) {
  const body = await parseJson(request, 4096);
  const status = body.status;
  const reason = typeof body.reason === "string" ? body.reason.trim() : "";
  if (!["active", "suspended", "banned"].includes(status) || reason.length < 5 || reason.length > 500) return json({ error: "invalid_moderation_request" }, 400);
  const target = await env.DB.prepare("SELECT id, status FROM accounts WHERE id = ?").bind(targetId).first();
  if (!target) return json({ error: "user_not_found" }, 404);
  const targetRows = await env.DB.prepare("SELECT role FROM account_roles WHERE user_id = ?").bind(targetId).all();
  const targetRoles = (targetRows.results || []).map((row) => row.role);
  const ownerId = await configuredOwnerId(env, env.DB);
  if (!mayModerateAccount({ actorId: actor.account.id, actorRoles: actor.roles, targetId, targetRoles, configuredOwnerId: ownerId })) return json({ error: "forbidden_moderation" }, 403);
  const expiresAt = body.expires_at == null ? null : Number(body.expires_at);
  if (expiresAt != null && (!Number.isSafeInteger(expiresAt) || expiresAt <= Math.floor(Date.now() / 1000))) return json({ error: "invalid_expiration" }, 400);
  if (status === "active" && expiresAt != null) return json({ error: "expiration_not_valid_for_active_status" }, 400);
  const now = Math.floor(Date.now() / 1000);
  await env.DB.batch([
    env.DB.prepare("UPDATE accounts SET status = ?, updated_at = ? WHERE id = ?").bind(status, now, targetId),
    env.DB.prepare("INSERT INTO moderation_history (id, target_user_id, actor_user_id, status, reason, expires_at, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?)")
      .bind(crypto.randomUUID(), targetId, actor.account.id, status, reason, expiresAt, now),
    env.DB.prepare("INSERT INTO audit_events (id, actor_user_id, target_user_id, action, metadata_json, occurred_at) VALUES (?, ?, ?, 'account.moderated', ?, ?)")
      .bind(crypto.randomUUID(), actor.account.id, targetId, JSON.stringify({ status, expires_at: expiresAt }), now)
  ]);
  return json({ user_id: targetId, status, expires_at: expiresAt });
}

async function listUsers(url, env) {
  const query = (url.searchParams.get("q") || "").trim().slice(0, 64);
  const like = `%${query.replace(/[\\%_]/g, "\\$&")}%`;
  const result = await env.DB.prepare("SELECT id, username, status, created_at FROM accounts WHERE (? = '' OR username LIKE ? ESCAPE '\\' OR id = ?) ORDER BY created_at DESC LIMIT 50")
    .bind(query, like, query).all();
  const users = [];
  for (const account of result.results || []) {
    const roles = await env.DB.prepare("SELECT role FROM account_roles WHERE user_id = ? AND (expires_at IS NULL OR expires_at > ?)")
      .bind(account.id, Math.floor(Date.now() / 1000)).all();
    users.push({ ...account, roles: (roles.results || []).map((row) => row.role) });
  }
  return json({ users });
}

async function getAdminStats(url, env) {
  const now = Math.floor(Date.now() / 1000);
  const from = Number(url.searchParams.get("from") || now - 30 * 24 * 60 * 60);
  const to = Number(url.searchParams.get("to") || now);
  if (!Number.isSafeInteger(from) || !Number.isSafeInteger(to) || from > to || to - from > 366 * 24 * 60 * 60 || to > now + 60) return json({ error: "invalid_date_range" }, 400);
  const [accounts, statuses, reports, roles] = await Promise.all([
    env.DB.prepare("SELECT COUNT(*) AS total FROM accounts WHERE created_at <= ?").bind(to).first(),
    env.DB.prepare("SELECT status, COUNT(*) AS count FROM accounts GROUP BY status").all(),
    env.DB.prepare("SELECT COUNT(*) AS total FROM diagnostic_reports WHERE created_at BETWEEN ? AND ?").bind(from, to).first(),
    env.DB.prepare("SELECT role, COUNT(*) AS count FROM account_roles WHERE expires_at IS NULL OR expires_at > ? GROUP BY role").bind(now).all()
  ]);
  return json({ range: { from, to }, total_accounts: accounts?.total || 0, accounts_by_status: statuses.results || [], reports_in_range: reports?.total || 0, active_role_assignments: roles.results || [] });
}

export async function getSystemHealth(env) {
  let databaseQuery = false;
  if (isD1Configured(env)) {
    try {
      const result = await env.DB.prepare("SELECT 1 AS ok").first();
      databaseQuery = result?.ok === 1;
    } catch {
      databaseQuery = false;
    }
  }
  const checks = {
    database_query: databaseQuery,
    report_storage_binding: Boolean(env.REPORTS?.get && env.REPORTS?.put),
    discord_oauth_configured: Boolean(env.DISCORD_CLIENT_ID && env.DISCORD_CLIENT_SECRET),
    curseforge_configured: false
  };
  return json({ service: "oblivion-api", status: databaseQuery ? "ok" : "degraded", checks, checked_at: Math.floor(Date.now() / 1000) });
}

async function handleApi(request, env, fetchImpl = fetch) {
  const url = new URL(request.url);
  const path = url.pathname;
  if (path === "/api/health") {
    if (request.method !== "GET") return methodNotAllowed(["GET"]);
    return json({ service: "oblivion-api", status: "ok", version: "0.1.0", configured: { database: isD1Configured(env), discord: Boolean(env.DISCORD_CLIENT_ID && env.DISCORD_CLIENT_SECRET), reports: Boolean(env.REPORTS?.put), curseforge: Boolean(env.CURSEFORGE_ENABLED === "true" && env.CURSEFORGE_API_KEY) } });
  }
  if (path === "/api/config") {
    if (request.method !== "GET") return methodNotAllowed(["GET"]);
    return json({ features: { discord_oauth: Boolean(env.DISCORD_CLIENT_ID && env.DISCORD_CLIENT_SECRET && env.DISCORD_REDIRECT_URI && env.SESSION_HMAC_SECRET && isD1Configured(env)), curseforge: false, diagnostic_upload: Boolean(env.REPORTS?.put && isD1Configured(env)), account_admin: Boolean(env.SESSION_HMAC_SECRET && isD1Configured(env)), microsoft_login: false, minecraft_runtime_host: false } });
  }
  if (path === "/api/version") {
    if (request.method !== "GET") return methodNotAllowed(["GET"]);
    return json({ version: "0.1.0", api_version: 1 });
  }
  if (path === "/api/discord/oauth/start") {
    if (request.method !== "POST") return methodNotAllowed(["POST"]);
    return handleDiscordStart(request, env);
  }
  if (path === "/api/discord/oauth/callback") {
    if (request.method !== "GET") return methodNotAllowed(["GET"]);
    return handleDiscordCallback(request, env, fetchImpl);
  }
  if (path === "/api/discord/oauth/exchange") {
    if (request.method !== "POST") return methodNotAllowed(["POST"]);
    return exchangeAppCode(request, env);
  }
  if (path === "/api/me") {
    if (request.method !== "GET") return methodNotAllowed(["GET"]);
    const auth = await authenticate(request, env);
    if (auth.response) return auth.response;
    const [identities, membership] = await Promise.all([
      env.DB.prepare("SELECT provider, provider_user_id, provider_username, linked_at FROM identities WHERE user_id = ?")
        .bind(auth.account.id).all(),
      env.DB.prepare("SELECT role FROM account_roles WHERE user_id = ? AND role = 'MEMBER' AND source = 'discord' AND (expires_at IS NULL OR expires_at > ?)")
        .bind(auth.account.id, Math.floor(Date.now() / 1000)).first()
    ]);
    return json({ account: auth.account, roles: auth.roles, identities: identities.results || [], microsoft: { connected: false, xbox_verified: false, minecraft_entitlement_verified: false }, discord_guild_membership_verified: Boolean(membership) });
  }
  if (path === "/api/auth/logout") {
    if (request.method !== "POST") return methodNotAllowed(["POST"]);
    const auth = await authenticate(request, env);
    if (auth.response) return auth.response;
    const now = Math.floor(Date.now() / 1000);
    await env.DB.batch([
      env.DB.prepare("INSERT OR IGNORE INTO revoked_sessions (jti, user_id, revoked_at, expires_at) VALUES (?, ?, ?, ?)")
        .bind(auth.claims.jti, auth.account.id, now, auth.claims.exp),
      env.DB.prepare("INSERT INTO audit_events (id, actor_user_id, action, metadata_json, occurred_at) VALUES (?, ?, 'session.revoked', '{}', ?)")
        .bind(crypto.randomUUID(), auth.account.id, now)
    ]);
    return json({ status: "signed_out" });
  }
  if (path === "/api/catalog") {
    if (request.method !== "GET") return methodNotAllowed(["GET"]);
    if (!isD1Configured(env)) return serviceUnavailable("marketplace", ["D1 database and catalog entries"]);
    const auth = request.headers.has("Authorization") ? await authenticate(request, env) : null;
    if (auth?.response) return auth.response;
    const rows = await env.DB.prepare("SELECT id, title, description, creator, version, content_type, source, license, supported_versions_json, metadata_json, required_role, required_entitlement, is_premium FROM content_items WHERE is_enabled = 1 ORDER BY created_at DESC LIMIT 50").all();
    const entitlements = auth ? await env.DB.prepare("SELECT entitlement_id FROM entitlements WHERE user_id = ? AND (expires_at IS NULL OR expires_at > ?)").bind(auth.account.id, Math.floor(Date.now() / 1000)).all() : { results: [] };
    const owned = (entitlements.results || []).map((row) => row.entitlement_id);
    const roles = auth?.roles || [ROLE.NEWCOMER];
    return json({ items: (rows.results || []).map((row) => ({ ...row, supported_versions: JSON.parse(row.supported_versions_json || "[]"), metadata: JSON.parse(row.metadata_json || "{}"), entitled: hasEntitlement({ roles, entitlements: owned, requiredRole: row.required_role, requiredEntitlement: row.required_entitlement }) })).map(({ supported_versions_json, metadata_json, ...item }) => item) });
  }
  if (path === "/api/curseforge/search") return serviceUnavailable("curseforge", ["approved CurseForge API access", "server API key", "reviewed Bedrock-compatible catalog policy"]);
  if (path === "/api/reports") {
    if (request.method !== "POST" && request.method !== "GET") return methodNotAllowed(["GET", "POST"]);
    const auth = await authenticate(request, env);
    if (auth.response) return auth.response;
    if (request.method === "POST") return submitDiagnostic(request, env, auth);
    if (!hasPermission(auth.roles, "report:review")) return json({ error: "forbidden" }, 403);
    return listReports(env, auth);
  }
  const reportMatch = /^\/api\/reports\/([0-9a-f-]{36})$/.exec(path);
  if (reportMatch) {
    if (request.method !== "GET") return methodNotAllowed(["GET"]);
    const auth = await authenticate(request, env);
    if (auth.response) return auth.response;
    if (!hasPermission(auth.roles, "report:review")) return json({ error: "forbidden" }, 403);
    if (!env.REPORTS?.get) return serviceUnavailable("diagnostic_reports", ["private R2 bucket"]);
    return getReport(env, reportMatch[1], auth);
  }
  const roleMatch = /^\/api\/admin\/users\/([A-Za-z0-9-]{1,64})\/roles$/.exec(path);
  if (roleMatch) {
    if (request.method !== "POST") return methodNotAllowed(["POST"]);
    const auth = await authenticate(request, env);
    if (auth.response) return auth.response;
    if (!hasPermission(auth.roles, "role:assign:basic")) return json({ error: "forbidden" }, 403);
    return updateRole(request, env, auth, roleMatch[1]);
  }
  if (path === "/api/admin/roles") {
    if (request.method !== "GET") return methodNotAllowed(["GET"]);
    const auth = await authenticate(request, env);
    if (auth.response) return auth.response;
    if (!hasPermission(auth.roles, "user:read")) return json({ error: "forbidden" }, 403);
    return json({ roles: Object.entries(ROLE).map(([key, value]) => ({ key, value, permissions: PERMISSIONS[value] || [] })) });
  }
  if (path === "/api/admin/users") {
    if (request.method !== "GET") return methodNotAllowed(["GET"]);
    const auth = await authenticate(request, env);
    if (auth.response) return auth.response;
    if (!hasPermission(auth.roles, "user:read")) return json({ error: "forbidden" }, 403);
    await appendAudit(env.DB, auth.account.id, null, "admin.users.searched", {});
    return listUsers(url, env);
  }
  if (path === "/api/admin/stats") {
    if (request.method !== "GET") return methodNotAllowed(["GET"]);
    const auth = await authenticate(request, env);
    if (auth.response) return auth.response;
    if (!hasPermission(auth.roles, "metrics:read")) return json({ error: "forbidden" }, 403);
    await appendAudit(env.DB, auth.account.id, null, "admin.metrics.viewed", {});
    return getAdminStats(url, env);
  }
  if (path === "/api/admin/health") {
    if (request.method !== "GET") return methodNotAllowed(["GET"]);
    const auth = await authenticate(request, env);
    if (auth.response) return auth.response;
    if (!hasPermission(auth.roles, "system:health")) return json({ error: "forbidden" }, 403);
    return getSystemHealth(env);
  }
  if (path === "/api/admin/audit") {
    if (request.method !== "GET") return methodNotAllowed(["GET"]);
    const auth = await authenticate(request, env);
    if (auth.response) return auth.response;
    if (!hasPermission(auth.roles, "audit:read")) return json({ error: "forbidden" }, 403);
    const result = await env.DB.prepare("SELECT id, actor_user_id, target_user_id, action, metadata_json, occurred_at FROM audit_events ORDER BY occurred_at DESC LIMIT 100").all();
    return json({ events: result.results || [] });
  }
  const moderationMatch = /^\/api\/admin\/users\/([A-Za-z0-9-]{1,64})\/status$/.exec(path);
  if (moderationMatch) {
    if (request.method !== "POST") return methodNotAllowed(["POST"]);
    const auth = await authenticate(request, env);
    if (auth.response) return auth.response;
    if (!hasPermission(auth.roles, "user:moderate")) return json({ error: "forbidden" }, 403);
    return moderateAccount(request, env, auth, moderationMatch[1]);
  }
  if (path.startsWith("/api/admin/")) return serviceUnavailable("admin_api", ["this administration operation is not implemented"]);
  return json({ error: "not_found" }, 404);
}

export default {
  async fetch(request, env) {
    const cors = corsHeaders(request, env);
    if (cors === null) return json({ error: "origin_not_allowed" }, 403);
    if (request.method === "OPTIONS") {
      if (!request.headers.has("Origin")) return new Response(null, { status: 204 });
      return new Response(null, { status: 204, headers: { ...cors, "access-control-allow-methods": "GET, POST, OPTIONS", "access-control-allow-headers": "Authorization, Content-Type", "access-control-max-age": "600", "vary": "Origin" } });
    }
    try {
      const contentLength = Number(request.headers.get("Content-Length") || "0");
      if (Number.isFinite(contentLength) && contentLength > MAX_BODY_BYTES) return json({ error: "request_too_large" }, 413);
      if (!await rateLimit(request, env, new URL(request.url).pathname)) return json({ error: "rate_limited" }, 429, { "retry-after": "60" });
      const response = await handleApi(request, env);
      if (cors) for (const [name, value] of Object.entries(cors)) response.headers.set(name, value);
      return response;
    } catch (error) {
      if (error instanceof RangeError) return json({ error: "request_too_large" }, 413);
      if (error instanceof SyntaxError) return json({ error: "invalid_json" }, 400);
      return json({ error: "internal_error", message: safeErrorMessage(error) }, 500);
    }
  }
};
