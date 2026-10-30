# Public source release

This repository contains the public application source for the deployed PLM program release `20261030-supplier-business-7f0cf0bc4a77-r1` (30 October 2026, Asia/Shanghai).

- Private build input manifest: `3ac7cddc3e87058a3db2a40f9bbc571852984fdf6f01d59326b6f44542109f3c` (1,365 inputs).
- Deployed backend JAR SHA-256: `7f0cf0bc4a773134fd62d7a6f43190249f0de2633e78cb6c4527c1069f386b36`.
- Deployed frontend index SHA-256: `ab972228dec2b6ee5f188618dede0aa27801a2bce62b7d703d236a621321763b`.

This update adds independent multi-select supplier business categories and limits new tooling-supplier assignments to classified tooling suppliers. Existing supplier relationships remain readable. It retains Chinese/Spanish preferences, catalog improvements, 3D source/render evidence, approval and coding workflows, same-page approval details, other-model draft continuation, and development-model terminology. Unreleased sample-location and historical-sales restoration work are not included.

The merged supplier update passed 201 affected frontend tests, 79 backend tests including seven isolated PostgreSQL cases, type checking, and normal builds. A separate real Flyway rehearsal verified the new data migration and repeat idempotency. Earlier integrated-suite results are inherited and are not presented as a new full run. Production program hashes and health were checked. Real approval submissions and business records are not part of this source publication.

## Private resources

Production environment files, credentials, databases, business documents, original attachments, runtime files, local acceptance evidence, and internal deployment notes are excluded. Existing public configuration placeholders remain in place.

Four migrations contain private business, initial account or supplier data and remain excluded, together with the production-specific supplier integration fixture. See [private migration resources](private-migration-resources.md) and the existing restoration helper for exact migration identities and checksums. Restore authorized original bytes privately when required; do not replace them with empty migrations or change their checksums.
