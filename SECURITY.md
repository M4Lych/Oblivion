# Security policy

Report security issues privately to the project maintainers; the official contact address is not configured yet, so do not include secrets or personal data in public issues.

## Current controls

- Android requests only Internet access and package visibility for the official Minecraft package; no broad storage permission is declared.
- User-selected files are handled through Android's Storage Access Framework and copied into Oblivion's private directory with archive size/path validation.
- Diagnostics stay local unless a future protected backend is configured. Logs redact common bearer/token/password fields and rotate at a bounded size.
- The Worker must enforce permissions server-side. A client role or UI visibility state is never authoritative.
- Secrets and local signing keys are ignored by Git.

## Limitations

Remote identity, account actions, diagnostic submission, and admin endpoints are not active until a reviewed backend implementation is deployed. Do not use the current local health service to store personal information.
