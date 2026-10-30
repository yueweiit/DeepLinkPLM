# Public source release

This repository contains the public source for the deployed PLM program release `20261030-development-model-de132431fd79-r1` (30 October 2026, Asia/Shanghai).

- Private build input manifest: `7eaf9b448cb15cda8b909abbca9004a5b4a5370a5cedcfa7d42068f97727777e` (1,362 inputs).
- Deployed backend JAR SHA-256: `de132431fd791b64ff35027562a8b6e12cb9a0e35ee4bbdadb8d852198229a98`.
- Deployed frontend index SHA-256: `dc4cb56d08953ab9dd388fb59b9d1471047d2c67fb20a05ce155ad87db661f8f`.

This update includes Chinese/Spanish preferences, supplier catalog improvements, separate 3D source/render evidence, the existing approval and coding workflow, clearer research status and refusal information, same-page approval details, other-model draft continuation, and development-model terminology. Unreleased sample-location work is not included.

The latest terminology-only delta passed the affected 164 frontend and 46 backend unit tests, type checking, and normal builds. The preceding integrated research release passed 1,630 frontend and 226 backend tests; those earlier results are not presented as a new full run for this wording update. Production program hashes and health were checked. Real approval submissions or business data are not part of this source publication.

## Private resources

Production environment files, credentials, databases, business documents, original attachments, runtime files, local acceptance evidence, and internal deployment notes are excluded. Existing public configuration placeholders remain in place.

Three historical migrations contain private business or initial account data and remain excluded. See [private migration resources](private-migration-resources.md) and the existing restoration helper for their exact identities and checksums. Restore authorized original bytes privately when required; do not replace them with empty migrations or change their checksums.
