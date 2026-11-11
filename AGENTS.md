# Repository guidance

- This is a clean independent Oblivion project. Do not import application source, game compatibility modules, native libraries, extracted APK content, or artwork from sibling repositories.
- Follow `docs/ARCHITECTURE.md` and `docs/LAUNCH_ARCHITECTURE.md`; never state that a launcher-side preview or file copy modified Minecraft.
- The configured Minecraft compatibility fallback must remain an explicit separate-app action. Do not treat it as satisfying hosted gameplay.
- Server secrets belong only in Worker secrets. Android clients receive public configuration only.
- Keep English as the default UI language and preserve accessible touch targets, contrast, and landscape/portrait layouts.
- Add or update focused unit tests for pure policies and file validators. Do not disable validation to clear a failure.
- Do not commit, push, or alter repository settings without explicit user direction.
