# Public source release

This repository contains the public application source for the deployed PLM program release `20261030-sales-detail-performance-e6e526f30a54-r1` (30 October 2026, Asia/Shanghai).

- Private build input manifest: `84998103748546a0ed417a187717c036d6ec02f6665c5bd6967cc674f6cb3122` (1,367 inputs).
- Deployed backend JAR SHA-256: `e6e526f30a54b1126ea2b2e0b130dacd4f2bf9c4e51ebc26e9ba97e9d234416d`.
- Deployed frontend index SHA-256: `cc1e568b890c3b17fdada60e3e1943ab586cf848607ee90852bf3de3716e15c5`.

This update bounds original-order detail attribution to the requested source scope and preserves exact source identity checks, permissions and pagination. An existing full-scale, read-only dataset reduced the affected detail call from about 29 seconds to 392 milliseconds. Detail failures are shown inside the drawer with retry, and responses from a closed drawer no longer replace active detail state. No database index, schema migration, longer timeout or second sales ledger was introduced.

Archived-product sales remain available in lists, matrices and original-order details. Current missing information and historical reference records remain separate. Original product names, codes and colors remain traceable; historical groups do not create physical molds. Reference-cost refresh retains its existing action and audit. Unknown expenses, physical set counts and return-on-cost inputs remain explicit.

Supplier business categories and tooling-supplier filters remain included, together with Chinese/Spanish preferences, catalog improvements, 3D source/render evidence, approval and coding workflows, same-page approval details, other-model draft continuation, and development-model terminology. Unreleased sample-location work is not included.

The affected backend suites executed 114 passing tests; six older matrix integration cases were skipped because their dedicated database was offline. Those skips are not counted as passes. Full-scale matrix, page and options behavior was separately verified against the existing isolated dataset. All 87 affected frontend tests passed, together with type checking, normal builds and isolated browser checks. The coordinator matched every backend JAR entry and frontend asset to the tested candidate.

The program was activated at 15:17:37 Asia/Shanghai. Production hashes, health and strict fresh-baseline data-preservation checks passed. Normal production browser checks for the preceding supplier/history release and two idempotent reference-cost refreshes had passed. Authenticated production browser rechecks of this detail performance fix are pending; they are not marked complete.

The production approval read-sync interval is configured as 24 hours through the persistent `APP_MOLD_APPLICATION_SYNC_INTERVAL_MS=86400000` override. Running environment, resolved Compose and the persistent override were checked. Manual synchronization remains available. The source fallback and one-time startup delay remain 15 minutes; other scheduled tasks and integration permissions are unchanged. A full 24-hour execution cycle has not yet been observed. This runtime-only configuration change does not change the source manifest. No new backups, business imports or external write tests were performed for this release.

## Private resources

Production environment files, credentials, databases, business documents, original attachments, runtime files, local acceptance evidence, and internal deployment notes are excluded. Existing public configuration placeholders remain in place. Production business reference files are not part of this source publication.

Four migrations contain private business, initial account or supplier data and remain excluded, together with the production-specific supplier integration fixture. See [private migration resources](private-migration-resources.md) and the existing restoration helper for exact migration identities and checksums. Restore authorized original bytes privately when required; do not replace them with empty migrations or change their checksums.
