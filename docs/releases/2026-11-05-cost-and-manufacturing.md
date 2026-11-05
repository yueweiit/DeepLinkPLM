# 2026-11-05 Project cost and manufacturing identity release

Project costs now use one read-only/edit/save workspace. Estimated investment and actual expenses are separate, sources stay collapsed until needed, and pending corrections retain the previous effective actual amount until confirmed. Existing finance permissions, source vouchers, audit history and frozen cost versions remain authoritative. ERP source amounts are read-only. Flexible unit-cost rows and columns remain available.

Manufacturing relationships explicitly distinguish the customer phone from an existing manufacturing product, SKU range and physical mold set. Shared molds do not automatically imply shared finished goods. Historical identifiers and relationships are preserved; no name-based global substitution or automatic SKU/inventory creation is introduced.

Release inputs:

- Source manifest: `02854c7a48752ce1cdcfc3f68124a0c20620190ca4054bcaa9e14f65a16018f7` (1,388 inputs).
- Backend JAR: `33e37350dbcd7af3b0d325239f049573205df2c61e1ee03891203907686c81a7`.
- Frontend index: `6db9a096b70d448f7f7a2f9ebef88844f3d7f2107b550343851652d77480e66f`.
- Three additive Flyway migrations; all 113 original migration resources and 95 dependency JARs retained byte for byte.

Validation: 199 backend tests passed, with one existing conditional test skipped because its real source workbook was unavailable; 361 frontend tests passed, plus type checking, full builds and seven Python connector contract tests. Current and retained backend packages both completed normal isolated startup on schema 116. Migration, preservation and publication checks were separately qualified. Authenticated production UI checks covered the three existing development projects, read-only/edit/cancel behavior, narrow layouts, existing phone identities and cancellation of an unsaved manufacturing form. No real expense, approval or manufacturing relationship was submitted for smoke testing.

The 64 application-source paths update 40 existing public files and add 24, retaining unrelated repository content. Net changes: application code +1,667/-361 lines, tests +946/-99 and the manufacturing contract +74 lines. Executed test counts include existing regressions; they are not counts of newly added cases.

Deployment retained existing business data, files, users, permissions, safety switches and old static assets. No new backup or business-data import was created. Normal authorized background synchronization was retained and reviewed rather than rolled back. A Python 3.10 timestamp parsing incompatibility in the private publication verifier was corrected without changing the application package or weakening its preservation comparisons.

Compatibility: existing finance ledger/import interfaces and historical cost snapshots remain in use by the finance views and their callers. The new workspace uses those same underlying services. A later caller migration is required before retiring those interfaces. New external approval-template mappings and outbound order dispatch remain unconfigured or disabled until their separate integration conditions are met. No external approval writes or central-code switch were enabled by this release.
