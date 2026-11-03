# Style selection, actual receipts and parcel tracking

Supplier catalogue selection now adds styles directly. Actual receipt entry selects real models and colours; new specification rows default to two units in China, while new Mexico parcel rows default to one. Existing entered quantities and parcel drafts remain intact. Tracking numbers may be completed after dispatch or receipt without repeating stock movements.

Current supplier model ranges are projected for the exact source identity without replacing historical confirmation or receipt snapshots. Receipt transactions take existing source-head and source-row shared NOWAIT locks. A contended source reports a retryable conflict only after rollback. Successful original request replay remains first.

The integrated source also retains supplier-prefixed sample labels, original sample identities, old QR resolution, location maintenance, actual-colour summaries and large-receipt pagination.

Validation: 540 unique backend cases, including real dual-connection PostgreSQL scenarios; 394 unique frontend cases; type checking and normal production builds. No new dependencies or migration bytes.

Program publication imports no business data, creates no test receipts or backups, and does not perform the separately approved historical receipt cleanup. Cleanup requires a separate fresh preview, exact batches, current supplier scope, downstream checks and original audited actions.

Supplier lifecycle correction: catalogue scope availability follows the existing supplier rule, allowing draft and active profiles while rejecting inactive profiles. Six new real PostgreSQL receipt cases cover quote and selection sources across these three states. Only one production class changes; published frontend bytes, dependencies and migrations are identical to the parent. This correction does not change supplier records or perform historical stock cleanup.

Explicitly unprovided supplier models: only a persisted, genuine not-provided declaration permits manual selection of existing enabled phone members at actual receipt. Supplier scope remains empty and incomplete; this exception never means every system model is supplied. Known supplier scope, frozen model-colour pairs, source-version conflicts, successful request replay and transaction locks retain their original rules. The whole 16-file frontend qualification now passes 401 unique cases; 220 targeted backend cases include fresh PostgreSQL supplier lifecycle and source-concurrency scenarios. No new dependencies, schema or business-data import.
