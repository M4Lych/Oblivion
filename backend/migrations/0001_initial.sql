CREATE TABLE IF NOT EXISTS accounts (
  id TEXT PRIMARY KEY,
  username TEXT NOT NULL,
  avatar_url TEXT,
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','suspended','banned')),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS accounts_username_nocase ON accounts(username COLLATE NOCASE);

CREATE TABLE IF NOT EXISTS identities (
  provider TEXT NOT NULL,
  provider_user_id TEXT NOT NULL,
  user_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  provider_username TEXT,
  linked_at INTEGER NOT NULL,
  PRIMARY KEY(provider, provider_user_id),
  UNIQUE(provider, user_id)
);
CREATE INDEX IF NOT EXISTS identities_user_id ON identities(user_id);

CREATE TABLE IF NOT EXISTS account_roles (
  user_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  role TEXT NOT NULL,
  source TEXT NOT NULL DEFAULT 'manual',
  assigned_by TEXT,
  assigned_at INTEGER NOT NULL,
  expires_at INTEGER,
  PRIMARY KEY(user_id, role)
);
CREATE INDEX IF NOT EXISTS account_roles_expiry ON account_roles(expires_at);

CREATE TABLE IF NOT EXISTS role_history (
  id TEXT PRIMARY KEY,
  target_user_id TEXT NOT NULL REFERENCES accounts(id),
  actor_user_id TEXT REFERENCES accounts(id),
  role TEXT NOT NULL,
  action TEXT NOT NULL CHECK(action IN ('assigned','removed','expired')),
  reason TEXT,
  occurred_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS moderation_history (
  id TEXT PRIMARY KEY,
  target_user_id TEXT NOT NULL REFERENCES accounts(id),
  actor_user_id TEXT NOT NULL REFERENCES accounts(id),
  status TEXT NOT NULL,
  reason TEXT NOT NULL,
  expires_at INTEGER,
  occurred_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS entitlements (
  user_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  entitlement_id TEXT NOT NULL,
  source TEXT NOT NULL,
  granted_by TEXT REFERENCES accounts(id),
  created_at INTEGER NOT NULL,
  expires_at INTEGER,
  PRIMARY KEY(user_id, entitlement_id)
);

CREATE TABLE IF NOT EXISTS content_items (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  creator TEXT NOT NULL,
  version TEXT NOT NULL,
  content_type TEXT NOT NULL,
  source TEXT NOT NULL,
  license TEXT NOT NULL,
  supported_versions_json TEXT NOT NULL DEFAULT '[]',
  metadata_json TEXT NOT NULL DEFAULT '{}',
  required_role TEXT,
  required_entitlement TEXT,
  is_premium INTEGER NOT NULL DEFAULT 0,
  is_enabled INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS diagnostic_reports (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES accounts(id),
  object_key TEXT NOT NULL UNIQUE,
  category TEXT NOT NULL,
  app_version TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'new'
);

CREATE TABLE IF NOT EXISTS audit_events (
  id TEXT PRIMARY KEY,
  actor_user_id TEXT REFERENCES accounts(id),
  target_user_id TEXT REFERENCES accounts(id),
  action TEXT NOT NULL,
  metadata_json TEXT NOT NULL DEFAULT '{}',
  occurred_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS oauth_states (
  state_hash TEXT PRIMARY KEY,
  code_verifier TEXT NOT NULL,
  app_code_challenge TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS app_auth_codes (
  code_hash TEXT PRIMARY KEY,
  client_code_challenge TEXT NOT NULL,
  user_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS revoked_sessions (
  jti TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  revoked_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
