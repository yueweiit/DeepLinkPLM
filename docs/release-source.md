# Persistent sessions and source-owned image adoption — 2026-10-31

Release `20261031-session-photo-ecd6e87d2571-r1` combines two fixes on the exact previously deployed source. It retains phone registration, supplier-model source identities, language support and the existing business flows.

- Replace the sole JVM-memory session implementation with the existing Redis service. Preserve the opaque random bearer format and fixed eight-hour expiry; reads do not extend lifetime. Store only the existing current-user payload under a SHA-256 token key. Login, logout, SSO and request contracts remain unchanged, and every protected request still reloads current account and object permissions.
- Preserve the current session during temporary network/502/503 profile failures. A small recovery view retries the original route through the existing profile and permission checks. Genuine 401 handling remains unchanged. The first move from old memory sessions may require one normal login; no existing token is read, copied or synthesized.
- Let the existing catalog editor choose original files owned by that source, even when no current row adopts them. Reuse attachment ACL, source/supplier checks, cost restrictions, existing row patches and audit. The thin read-only source-file endpoint paginates before returning metadata; no new write API, table or dependency is added.

The combined normal build passed 86 backend cases, including real isolated Redis and temporary PostgreSQL transactions, and 184 distinct frontend cases across eight files. Type checking and complete Maven/Vite builds passed. A real local Spring/Vue normal-login session survived termination of the first JVM, a temporary-offline recovery screen, a newly started JVM, retry and full page reload without another login or reading/injecting a token. Image acceptance separately exercised the unchanged real Vue editor with a local API-boundary fixture, two consecutive image swaps, refreshed rows, and 1788/1000/900-pixel windows; real backend file authorization and pagination were verified by temporary-PG tests. This boundary test is not represented as production authorization proof.

All 112 migration resources and 95 runtime dependencies are byte-identical to the previous program. Eighteen paths change: production +187/-87 lines and tests +356/-27. The old memory implementation and map-only test are removed; ordinary login APIs, real 401 behavior and other editor callers retain their existing active contracts. No duplicate write path or parallel session provider is introduced.

Source manifest: `251386bfcaf064ee318b69d3197084d4d2b2c0b76694e2e94176281c3276e327`.
Backend JAR: `ecd6e87d25719c6546334aeb247fd50b41f75b68a5756f1059781bf7ab83d595`.
Frontend index: `a3a081c71fbae8b4b40ac694d7a7ca3aa8fbef1a2f1c07861cf0a06ffc15299b`.

Existing accounts, permissions, configuration, business data, source files and links are retained. No new backup, migration, business import or production test fixture is created. Runtime configuration, credentials, source spreadsheets/images and private operational evidence are excluded from this public repository. Program recovery uses existing parent images and keeps all database/files; returning to the former memory session provider may require normal login.

## Explicit confirmation of a resolved phone model — 2026-10-31

Release `20261031-model-confirm-7ac53cc0bd7f-r1` updates the frontend only. A catalog row can display a dynamically resolved model while its stored explicit association is empty. Completing the existing model picker must count as an explicit edit even if the visible ID matches the computed initial value. The editor forwards the picker's existing confirm event and includes that field in the same incremental changes object used by save and unsaved-change guards. Viewing, picker cancellation and photo/price-only edits do not trigger adoption. Existing version, permission, transaction and audit checks are unchanged.

Two Vue files change (+5/-5 lines); two existing test files add 28 lines and five distinct regression cases. Three assertions first failed against the old behavior. Final verification passed 186 frontend cases, type checking, the complete Vite build and a normal local Spring/Vue UI save-and-reload, with cancellation verified not to write. No backend build was needed. The production backend process and Redis were not restarted, and the existing normal authenticated session survived the frontend refresh.

Source manifest: `ed366cdd8fdbbf1c685c619cb9e08421980ab53a13db319f8244420265f5ffab` (1374 inputs).
Unchanged backend JAR: `ecd6e87d25719c6546334aeb247fd50b41f75b68a5756f1059781bf7ab83d595`.
Frontend index: `7ac53cc0bd7f346fc2b640d8a4e5cb5426df0fd2d5853e37a9bf684d610f32bd`.

No schema, dependency, credentials, business import, backup or production test fixture is added. Existing static assets are retained for already-open pages. No duplicate write path or replacement picker is introduced. Only exact candidate source is published; unrelated shared-worktree edits are excluded. Private operational evidence, supplier documents and images are not part of this repository.
