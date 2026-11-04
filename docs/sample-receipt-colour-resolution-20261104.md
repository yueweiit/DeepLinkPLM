# Actual sample receipt: scoped supplier colour names

Actual receipt now prefers an existing colour belonging to the current supplier style/configuration over a supplier-wide colour of the same name. Unnumbered duplicate names within the same scope reuse an existing identity; original rows and explicit historical IDs remain intact. Distinct real supplier colour codes in the same scope still require an explicit choice.

Within the original receipt transaction and name lock, persistence checks colours created by earlier rows before inserting another colour. A multi-model receipt can reuse one newly recorded colour. Existing receipt quantities, audit, retry/idempotency and supplier permissions continue using the same services; no schema or company colour code is added.

Validation: 488 backend cases actually passed with zero failures/errors/skips, including 3 new focused resolution cases and 2 real PostgreSQL transaction cases. PostgreSQL fixtures stayed in disposable loopback databases. Two initially skipped environment-gated suites were rerun on their allowed local database endpoint; the final suite aggregation retains the other executed cases without double counting. All packaged main classes match the test compilation.

The frontend inputs and built resources are byte-identical to the previous release; its 208 frontend cases, types and build are reused rather than described as rerun. Deployment updates the backend only, preserving the current web container and resources. No new backup, business-data import or production sample test is performed.
