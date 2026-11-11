#!/usr/bin/env node
const [command, ...args] = process.argv.slice(2);
const base = (process.env.OBLIVION_API_BASE_URL || "").replace(/\/$/, "");
const token = process.env.OBLIVION_ADMIN_TOKEN || "";

const routes = {
  health: "/api/admin/health",
  stats: "/api/admin/stats",
  users: "/api/admin/users",
  roles: "/api/admin/roles",
  reports: "/api/reports",
  version: "/api/version"
};

if (!routes[command]) {
  process.stderr.write("Usage: oblivion <health|stats|users [query]|roles|reports|version>\n");
  process.exit(2);
}
if (!base) {
  process.stderr.write("Set OBLIVION_API_BASE_URL to the deployed HTTPS API base URL.\n");
  process.exit(2);
}
if (!base.startsWith("https://")) {
  process.stderr.write("The Oblivion API URL must use HTTPS.\n");
  process.exit(2);
}
if (command !== "version" && !token) {
  process.stderr.write("Set OBLIVION_ADMIN_TOKEN in the current Termux session. The token is not saved or printed by this CLI.\n");
  process.exit(2);
}

const url = new URL(routes[command], base);
if (command === "users" && args.length) url.searchParams.set("q", args.join(" ").slice(0, 64));
if (command === "stats" && args.length >= 2) {
  url.searchParams.set("from", args[0]);
  url.searchParams.set("to", args[1]);
}

try {
  const response = await fetch(url, {
    headers: token ? { authorization: `Bearer ${token}`, accept: "application/json" } : { accept: "application/json" },
    signal: AbortSignal.timeout(15_000)
  });
  const text = await response.text();
  let body;
  try { body = JSON.parse(text); } catch { body = { error: "non_json_response" }; }
  if (!response.ok) {
    process.stderr.write(`Oblivion API returned HTTP ${response.status}: ${String(body.error || "request_failed").slice(0, 80)}\n`);
    process.exit(1);
  }
  process.stdout.write(`${JSON.stringify(body, null, 2)}\n`);
} catch {
  process.stderr.write("Could not reach the Oblivion API. Check the URL and network connection.\n");
  process.exit(1);
}
