# Storage and imports

Oblivion requests user-selected documents through Android's Storage Access Framework. Imports are copied into app-private files; no broad file permission is requested and Minecraft's private data directory is never read.

The `.mcworld` importer caps source archive size, entry count, and expanded bytes; rejects absolute paths, dot segments, backslashes that normalize to traversal, NUL, colon, malformed ZIPs, and archives without root `level.dat`. The original archive is preserved rather than extracted. The duplicate key is a content hash. Remove deletes only a file directly in Oblivion's own library directory.

Imported files are not game-installed. User can share an archive through Android so an authorized receiving app can handle it. The skin importer accepts 64x64 or 64x32 PNG only and stores a private copy; its selected color sample only affects Oblivion's placeholder. Resource/behavior packs and game settings need a supported import/export contract before implementation.
