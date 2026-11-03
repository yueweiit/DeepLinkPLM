# Style selection, actual receipts and parcel tracking

Supplier catalogue selection now adds styles directly. Actual receipt entry selects real models and colours; new specification rows default to two units in China, while new Mexico parcel rows default to one. Existing entered quantities and parcel drafts remain intact. Tracking numbers may be completed after dispatch or receipt without repeating stock movements.

Current supplier model ranges are projected for the exact source identity without replacing historical confirmation or receipt snapshots. Receipt transactions take existing source-head and source-row shared NOWAIT locks. A contended source reports a retryable conflict only after rollback. Successful original request replay remains first.

The integrated source also retains supplier-prefixed sample labels, original sample identities, old QR resolution, location maintenance, actual-colour summaries and large-receipt pagination.

Validation: 540 unique backend cases, including real dual-connection PostgreSQL scenarios; 394 unique frontend cases; type checking and normal production builds. No new dependencies or migration bytes.

Program publication imports no business data, creates no test receipts or backups, and does not perform the separately approved historical receipt cleanup. Cleanup requires a separate fresh preview, exact batches, current supplier scope, downstream checks and original audited actions.

Supplier lifecycle correction: catalogue scope availability follows the existing supplier rule, allowing draft and active profiles while rejecting inactive profiles. Six new real PostgreSQL receipt cases cover quote and selection sources across these three states. Only one production class changes; published frontend bytes, dependencies and migrations are identical to the parent. This correction does not change supplier records or perform historical stock cleanup.
