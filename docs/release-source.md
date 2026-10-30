# Public source release

This repository contains the public application source for the deployed PLM program release `20261030-history-restoration-f76dec6d0a78-r1` (30 October 2026, Asia/Shanghai).

- Private build input manifest: `a245f59d4e50fa6bc2e919b38c94d1fb83bfc448bfde2d7c04b275e592e0a749` (1,367 inputs).
- Deployed backend JAR SHA-256: `f76dec6d0a78b130da027c218846adbb29e831ebfb12b82d36c274ef8a57d032`.
- Deployed frontend index SHA-256: `d301461730961f674089ba02e4f8066fdf7b3ffb8ca79deb6b6461ddc5a2ed55`.

This update restores archived-product historical sales to lists, matrices and original-order details, separates current missing information from historical reference records, and attributes historical product families by their original product names while preserving source codes and colors. Source-only historical groups do not create physical mold records. Reference-cost refresh uses the existing statistics action, preserves original orders, records the cost change once and remains idempotent. Unknown expenses, physical set counts and return-on-cost inputs remain explicit.

Supplier business categories and tooling-supplier filters remain included, together with Chinese/Spanish preferences, catalog improvements, 3D source/render evidence, approval and coding workflows, same-page approval details, other-model draft continuation, and development-model terminology. Unreleased sample-location work is not included.

The affected source passed 113 backend and 84 frontend tests plus isolated database/browser acceptance. The coordinator verified the exact source and reports, performed fresh normal backend and frontend builds, and matched every JAR entry and frontend file to the tested candidate. Earlier integrated-suite totals are inherited and are not presented as a new full run. Production program hashes, health and declared data-preservation hashes were checked. Final authenticated production browser checks and the normal reference-cost refresh are still pending the existing login request; they are not marked complete.

## Private resources

Production environment files, credentials, databases, business documents, original attachments, runtime files, local acceptance evidence, and internal deployment notes are excluded. Existing public configuration placeholders remain in place. Production business reference files are not part of this source publication.

Four migrations contain private business, initial account or supplier data and remain excluded, together with the production-specific supplier integration fixture. See [private migration resources](private-migration-resources.md) and the existing restoration helper for exact migration identities and checksums. Restore authorized original bytes privately when required; do not replace them with empty migrations or change their checksums.
