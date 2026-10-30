# Supplier model-table source identities — 2026-10-31

Release `20261031-source-index-da3261345ee5-r2` retains the previous phone registration, single-model preference and original-download fixes.

- Preserve the supplier's original number, brand and model together as the source identity. Repeated numbers and explicitly unnumbered source positions stay distinct. Index-only legacy callers are accepted only when the source is unambiguous.
- Keep the existing bigint field and numeric compatibility. One additive Flyway migration adds a separate source-label field and index; original source snapshots, IDs and evidence digests are retained.
- Reuse the existing catalog model-sheet editor and pending-phone intake flow. Source review does not automatically create products, samples or phone codes.
- Fold long provenance into the existing review details. Model search uses business fields and excludes provenance checksums, while the full original notes remain saved.

Validation: 69 affected backend cases; 134 frontend cases across six files; type check and complete builds. Normal classpath migration replay includes 108 historical SQL and two Java migrations, preserving the 111-row history prefix and adding one migration. The actual prior whole application boots against the additive schema with read-only QA database access; this does not authorize old writers after new-format records have been saved.

Real local-browser acceptance covered independent repeated-number selections, a 53-entry source, a 193-entry source with unnumbered positions, cancel without collecting pending rows, original-image upload and retained states. The actual window and CSS 1788×870, 1000×870 and 900×870 were checked with the sidebar expanded and temporary sizes restored.

Twenty source files change: production +110/-45 lines across 11 files, tests +230/-19 across nine files. No new dependency or parallel write path is introduced. Legacy numeric handling remains for previously stored source records and unique index-only callers; it can be removed only after those records and callers have been deliberately upgraded.

Source manifest: `4f9a28b2c91ef8b0f9b6a8e83dd140594442bc51ee3ecd7eef76f681f5e985b9`.
Backend JAR: `da3261345ee579aa90bbcf56049844380a9f17b2673d6c80c47d6c1d36bda422`.
Frontend index: `3853b519defda67a3c0d32bb789e3a5a75e48938b56c8dfe08361491d0b233d6`.

Existing production accounts, permissions, settings and business relationships are retained. Release preservation separately checks the existing source-refresh clocks and protects their source contents and manual mappings. No new backup is created. Supplier files, images, credentials, runtime state and actual QA fixtures are excluded from this repository.
