# Attachment preview and original download — 2026-10-30

Frontend release `20261030-office-preview-3c282bcce52b-r1`.

- Use the existing attachment metadata API before requesting preview bytes. Unsupported Office files show a clear original-download action; image, PDF and text preview routes remain available.
- The supplier-original download explicitly uses the existing download endpoint. A failed download remains retryable; permission errors do not bypass authorization.
- Reuse the authorized blob queue, attachment ownership and download service. No new renderer, dependency or attachment store is introduced.

Validation: 52 affected frontend cases, type check and complete frontend build. Local real-browser checks covered both original-download entries, byte equality, image/gallery switching and a 900px viewport with sidebar expanded. PDF/text component route tests pass; native PDF/text rendering is not claimed by the headless suite.

Seven source files change: implementation +34/-6 lines, tests +80/-6 lines. Ten new test definitions execute eleven cases; 41 prior cases remain. The generic `fileBlob` preview default remains for existing preview callers. An operations-page Office fallback identified only by source review remains outside this verified fix.

Frozen source: `4531a124da72a5ebf584a59daaaab3b2d9c04f3c728455d250295aaac874db52` (1,371 inputs).
Frontend index SHA-256: `3c282bcce52bce97fd789646aa1ec5b1bbdd4d49ded5dab01d6e516bba371e35`.
Backend remains `2a6ab7be2557870a5003b4f21ed22a273ec711fda48f81e8e15932bde44f0608`; all 111 migration resources and 95 dependencies remain unchanged. The release changes only the existing web image, retains old static resources, imports no business data and creates no backups. Credentials, supplier originals, media and local QA records are excluded from public source.

---

# Phone-code workflow release — 2026-10-30

Release `20261030-phone-one-save-2a6ab7be2557-r1`.

- Register a phone code, brand and new/existing member in one transaction. Reuse existing member IDs, required audit and code rules; keep leading zeros and explicit factory names.
- Prefer a unique single-model identity among exact supplier-model candidates. Preserve explicit historical IDs and unresolved ambiguity.
- Show current, authorized supplier-catalog style names before frozen selection names; reads do not rewrite history.
- Reuse the phone selector; show business conflict messages inline and fold technical source notes.

Validation: 147 affected backend cases (62 real local PostgreSQL cases), 75 frontend cases, type check and complete builds. Production browser acceptance is documented separately after normal user login; no production test records are created.

Private source manifest: `ab227419088bde9274363e4113f7e22ffee5ebf87ee8921d57a31de22162529a` (1,371 build inputs).
Backend SHA-256: `2a6ab7be2557870a5003b4f21ed22a273ec711fda48f81e8e15932bde44f0608`.
Frontend index SHA-256: `d9eb6301079124a473e39ad8812433b6c5c678c8263e605d0d269e8760385858`.

All 111 migration resources and 95 runtime dependencies remain byte-identical to the prior deployed program. This release imports no business data, adds no schema changes, changes no runtime settings and creates no backups. Existing files, identities and relationships are preserved. The public repository retains sanitized configuration and existing private-resource restoration documentation; credentials, supplier originals, media, database exports and production-only fixtures are excluded.

---

# Public source release

This repository contains the application source for release `20261030-sales-model-age-628cfb315589-r1` (30 October 2026, Asia/Shanghai). The program was activated at 18:05:01 Asia/Shanghai and passed normal authenticated production browser checks.

- Private build input manifest: `2e8fe38d173cd23c54f86552d0c69459b86e2072915cc5051b52c2151646ed98` (1,367 inputs).
- Backend JAR SHA-256: `628cfb315589aeb606e5bb8eb373ded1776f04f015085ca4979be5a567132dc2`.
- Frontend index SHA-256: `78e773bccc85492206c0c671034cbb7e2e5487e671f3ab8b640f29f68dc4d87f`.

Cumulative production and mold sales default to descending four-digit phone-model code, with leading zeroes preserved and unassigned codes last. Sorting is performed before pagination and can be changed to opening date or quantity. Shared mold groups use the greatest valid member code without changing original codes, membership or quantities.

A single opening-date field uses the visible recorded mold date when available. Otherwise it displays the earliest effective order business date for the same attribution, with the fallback identified in details. This derived date is not written back into mold or order records. Model overview follows the same rule for the selected products. Chinese and Spanish labels and user-specific view preferences remain supported.

The change updates ten production files (+93/-38 lines) and eight related test files (+86/-12 lines), adding six backend and three frontend behavior scenarios. The affected backend suites have 120 current executed passing cases with no skips; the full shared run first exposed an obsolete unchanged baseline archive assertion, then its nine real SQL tests were rerun after that admitted baseline test was synchronized. The other 111 current reports passed in the initial run. The existing six fixed-database matrix cases are outside these 120 and are not claimed as newly passed. All 81 affected frontend cases, type checking and normal builds passed. Local real-browser checks passed with the sidebar expanded at 1788, 1000 and 900 pixels. No new migration, business ledger, automatic data rewrite or external write test was introduced.

This release inherits the completed original-order detail performance fix, historical attribution, cost maintenance, supplier catalog and selector changes, and existing Chinese/Spanish account preferences from the deployed parent. All 111 migration resources and 95 dependency JARs are byte-identical to that parent. Maven reordered the generated classpath index; its dependency set is unchanged, and the built application was started and verified locally. Existing Java overloads still serve actual callers and tests through the same implementation; they are retained until those callers migrate, without a second business rule pipeline.

The persistent approval read-sync interval remains 24 hours (`APP_MOLD_APPLICATION_SYNC_INTERVAL_MS=86400000`); this deployment changes no runtime settings. Manual sync and the existing 15-minute source fallback / one-time startup delay are unchanged. A full 24-hour cycle has not yet been observed. No new backups, business imports, test-data uploads, real approval submissions or central coding changes are included.

Production checks confirmed default code order in product detail and cumulative production, stable consecutive pages without overlap, quantity and opening-date sorting, recorded-date priority and the explicitly marked first-effective-order fallback. The model overview uses the same descending code order. The sidebar remained expanded at actual 1788, 1000 and 900 pixel viewports; temporary sizing was restored to the original 1436 by 809 viewport, with product detail, the original four selected products and default code order restored. No business records or statistics were saved or refreshed during these checks.

Fresh-baseline protection checks passed during the program switch. Public HTTPS returned 200 for the application and health endpoint and 401 for an anonymous profile request. Running JAR, frontend index and all 5,328 retained/new static files matched the release; the other 19 containers and all runtime settings were unchanged. Existing account permissions, records, documents and reference costs were preserved. Rollback uses existing prior program images and does not restore production data.

## Private resources

Production environment files, credentials, databases, business documents, original attachments, runtime files, local acceptance evidence and internal deployment notes are excluded. Existing public configuration placeholders remain in place.

Four migrations contain private business, initial account or supplier data and remain excluded, together with the production-specific supplier integration fixture. See [private migration resources](private-migration-resources.md) and the existing restoration helper for their identities and checksums. Restore authorized original bytes privately when required; do not replace them with empty migrations or change their checksums.
