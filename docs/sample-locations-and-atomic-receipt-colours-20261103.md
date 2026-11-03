# Sample locations and actual receipt colours

This release retains existing sample identities, receipts, balances and cancellation history. The receipt editor remains paginated for large batches and keeps input after validation errors.

- Reuse site and location maintenance with scoped warehouse access. Legacy free-text locations remain visible.
- Register a single actual colour per physical sample specification. Reuse supplier colours by their applicable style/configuration scope. Planned colours do not silently become receipt facts.
- Show actual received colours separately from selection plans. Preserve quantity correction and void history.
- Make cancelled selection re-selection explicit, with a compact, collapsible model picker. Preserve old snapshots and physical samples.
- Remove the redundant top-level receipt-batch tab while retaining original batch detail and transaction links.

Validation: 421 unique backend cases including 233 isolated PostgreSQL cases, 583 unique frontend cases, type check and production builds. Two legacy query-count assertions were corrected and the whole 11-case suite rerun; prior failures remain in private QA evidence.

One additive migration adds the sample-location record kind and its index; the prior 112 migrations remain byte-identical. This release does not import business data, create test receipts in production, or create new backups. Real stock corrections are separate, audited business operations.
