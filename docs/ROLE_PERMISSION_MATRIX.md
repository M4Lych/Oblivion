# Role and permission matrix

Roles are badges/permissions assigned by a server-authoritative backend. The client currently has no account session and does not assign roles.

| Role | Intended scope |
|---|---|
| OWNER | Full administration, owner identity protected by configured ID |
| ADMIN | Moderate below Admin, content moderation, permitted aggregate metrics |
| DEV | Technical diagnostics and service health |
| AUTHOR | Manage own approved content |
| PARTNER | Partner badge/eligible features |
| CREATOR | Manage own approved content and creator metadata |
| STAFF | Support and scoped moderation/report review |
| TEAM_DESIGN | Approved visual/cosmetic review |
| TEAM_PROMOTING | Announcement drafts |
| TEAM_EVENT | Event metadata |
| MEMBER | Verified community features |
| NEWCOMER | Default basic account |
| SUPPORTER | Supporter badge/approved entitlement |
| OBLIVION_VIP | VIP badge and eligible content |
| SERVER | Server status badge only; no admin permissions |

Operational permissions and cosmetic entitlements are separate. Discord OAuth alone never assigns Member; the backend must verify guild membership. Owners cannot be modified by Admins. Every sensitive operation must be authorized and audited by its backend endpoint. No such privileged production endpoints are active yet.
