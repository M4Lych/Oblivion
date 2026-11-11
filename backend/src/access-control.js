export const ROLE = Object.freeze({
  OWNER: "OWNER", ADMIN: "ADMIN", DEV: "DEV", AUTHOR: "AUTHOR", PARTNER: "PARTNER",
  CREATOR: "CREATOR", STAFF: "STAFF", TEAM_DESIGN: "TEAM_DESIGN", TEAM_PROMOTING: "TEAM_PROMOTING",
  TEAM_EVENT: "TEAM_EVENT", MEMBER: "MEMBER", NEWCOMER: "NEWCOMER", SUPPORTER: "SUPPORTER",
  OBLIVION_VIP: "OBLIVION_VIP", SERVER: "SERVER"
});

export const PERMISSIONS = Object.freeze({
  OWNER: ["*"],
  ADMIN: ["user:read", "user:moderate", "report:review", "content:moderate", "metrics:read", "role:assign:basic"],
  DEV: ["diagnostics:read", "system:health"],
  AUTHOR: ["content:own:manage"],
  PARTNER: ["badge:partner"],
  CREATOR: ["content:own:manage"],
  STAFF: ["user:support", "report:review"],
  TEAM_DESIGN: ["assets:review"],
  TEAM_PROMOTING: ["news:draft"],
  TEAM_EVENT: ["events:manage"],
  MEMBER: ["community:member"],
  NEWCOMER: [],
  SUPPORTER: ["badge:supporter"],
  OBLIVION_VIP: ["badge:vip", "content:vip:read"],
  SERVER: ["badge:server"]
});

const ADMIN_ASSIGNABLE = new Set([ROLE.NEWCOMER, ROLE.SUPPORTER, ROLE.OBLIVION_VIP]);
const NEVER_MANUALLY_ASSIGNABLE = new Set([ROLE.OWNER, ROLE.MEMBER]);

export function hasPermission(roles, permission) {
  return roles.some((role) => PERMISSIONS[role]?.includes("*") || PERMISSIONS[role]?.includes(permission));
}

export function mayAssignRole({ actorId, actorRoles, targetId, targetRoles = [], role, configuredOwnerId }) {
  if (!actorId || !targetId || !configuredOwnerId || !Object.hasOwn(ROLE, role)) return false;
  if (NEVER_MANUALLY_ASSIGNABLE.has(role) || targetId === configuredOwnerId || targetRoles.includes(ROLE.OWNER)) return false;
  if (actorId === configuredOwnerId) return actorRoles.includes(ROLE.OWNER);
  if (actorRoles.includes(ROLE.OWNER)) return false;
  if (!actorRoles.includes(ROLE.ADMIN) || !ADMIN_ASSIGNABLE.has(role)) return false;
  return !targetRoles.some((targetRole) => [ROLE.OWNER, ROLE.ADMIN, ROLE.DEV, ROLE.STAFF].includes(targetRole));
}

export function mayModerateAccount({ actorId, actorRoles, targetId, targetRoles = [], configuredOwnerId }) {
  if (!actorId || !targetId || targetId === configuredOwnerId || targetRoles.includes(ROLE.OWNER)) return false;
  if (actorRoles.includes(ROLE.OWNER)) return actorId === configuredOwnerId;
  if (!actorRoles.includes(ROLE.ADMIN)) return false;
  return !targetRoles.some((role) => [ROLE.OWNER, ROLE.ADMIN].includes(role));
}

export function hasEntitlement({ roles, entitlements, requiredRole, requiredEntitlement }) {
  const roleOk = !requiredRole || roles.includes(requiredRole);
  const entitlementOk = !requiredEntitlement || entitlements.includes(requiredEntitlement);
  return roleOk && entitlementOk;
}

export function rolesFromDiscordMembership({ isGuildMember, discordRoleIds, roleMap }) {
  const result = new Set([ROLE.NEWCOMER]);
  if (!isGuildMember) return [...result];
  result.add(ROLE.MEMBER);
  for (const id of discordRoleIds) {
    const role = roleMap[id];
    // Only explicitly configured roles are synchronized. OWNER and ADMIN are never Discord-derived.
    if (role && role !== ROLE.OWNER && role !== ROLE.ADMIN && Object.hasOwn(ROLE, role)) result.add(role);
  }
  result.delete(ROLE.NEWCOMER);
  return [...result];
}
