import test from "node:test";
import assert from "node:assert/strict";
import { hasEntitlement, hasPermission, mayAssignRole, mayModerateAccount, rolesFromDiscordMembership, ROLE } from "../src/access-control.js";

test("all requested roles exist and newcomer has no operational privileges", () => {
  assert.deepEqual(Object.keys(ROLE), ["OWNER", "ADMIN", "DEV", "AUTHOR", "PARTNER", "CREATOR", "STAFF", "TEAM_DESIGN", "TEAM_PROMOTING", "TEAM_EVENT", "MEMBER", "NEWCOMER", "SUPPORTER", "OBLIVION_VIP", "SERVER"]);
  assert.equal(hasPermission([ROLE.NEWCOMER], "user:moderate"), false);
  assert.equal(hasPermission([ROLE.SERVER], "admin:all"), false);
});

test("owner permission wildcard and configured owner identity are protected", () => {
  assert.equal(hasPermission([ROLE.OWNER], "anything:all"), true);
  assert.equal(mayAssignRole({ actorId: "owner", actorRoles: [ROLE.OWNER], targetId: "user", role: ROLE.SUPPORTER, configuredOwnerId: "owner" }), true);
  assert.equal(mayAssignRole({ actorId: "user", actorRoles: [ROLE.ADMIN], targetId: "owner", targetRoles: [ROLE.OWNER], role: ROLE.MEMBER, configuredOwnerId: "owner" }), false);
  assert.equal(mayAssignRole({ actorId: "user", actorRoles: [ROLE.ADMIN], targetId: "other", role: ROLE.OWNER, configuredOwnerId: "owner" }), false);
  assert.equal(mayAssignRole({ actorId: "owner", actorRoles: [ROLE.OWNER], targetId: "user", role: ROLE.MEMBER, configuredOwnerId: "owner" }), false);
  assert.equal(mayModerateAccount({ actorId: "admin", actorRoles: [ROLE.ADMIN], targetId: "owner", targetRoles: [ROLE.OWNER], configuredOwnerId: "owner" }), false);
});

test("admin can assign eligible badge roles only to lower-trust accounts", () => {
  const base = { actorId: "admin", actorRoles: [ROLE.ADMIN], targetId: "member", configuredOwnerId: "owner" };
  assert.equal(mayAssignRole({ ...base, role: ROLE.SUPPORTER }), true);
  assert.equal(mayAssignRole({ ...base, role: ROLE.OBLIVION_VIP }), true);
  assert.equal(mayAssignRole({ ...base, role: ROLE.DEV }), false);
  assert.equal(mayAssignRole({ ...base, targetRoles: [ROLE.STAFF], role: ROLE.MEMBER }), false);
  assert.equal(mayAssignRole({ ...base, targetRoles: [ROLE.ADMIN], role: ROLE.MEMBER }), false);
});

test("only configured owner can manage an admin account", () => {
  assert.equal(mayModerateAccount({ actorId: "admin", actorRoles: [ROLE.ADMIN], targetId: "staff-user", targetRoles: [ROLE.STAFF], configuredOwnerId: "owner" }), true);
  assert.equal(mayModerateAccount({ actorId: "admin", actorRoles: [ROLE.ADMIN], targetId: "another-admin", targetRoles: [ROLE.ADMIN], configuredOwnerId: "owner" }), false);
});

test("Discord OAuth without confirmed guild membership keeps Newcomer", () => {
  assert.deepEqual(rolesFromDiscordMembership({ isGuildMember: false, discordRoleIds: ["trusted"], roleMap: { trusted: ROLE.STAFF } }), [ROLE.NEWCOMER]);
});

test("Discord membership and explicitly mapped non-owner roles synchronize", () => {
  const roles = rolesFromDiscordMembership({
    isGuildMember: true,
    discordRoleIds: ["vip", "admin", "unknown"],
    roleMap: { vip: ROLE.OBLIVION_VIP, admin: ROLE.ADMIN }
  });
  assert.deepEqual(new Set(roles), new Set([ROLE.MEMBER, ROLE.OBLIVION_VIP]));
});

test("premium item requires server role or entitlement match", () => {
  assert.equal(hasEntitlement({ roles: [ROLE.NEWCOMER], entitlements: [], requiredRole: ROLE.OBLIVION_VIP, requiredEntitlement: "pack:vip" }), false);
  assert.equal(hasEntitlement({ roles: [ROLE.OBLIVION_VIP], entitlements: ["pack:vip"], requiredRole: ROLE.OBLIVION_VIP, requiredEntitlement: "pack:vip" }), true);
});
