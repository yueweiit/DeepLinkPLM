# Selected phone display and sample batch operations

Selected phones use the existing phone identities. Lists show family summaries and total selected counts; a separate read-only dialog groups each actual selected model by brand and generation, supports search, and pages whole groups. Combined historic names and four-digit codes remain unchanged. The original model text in the editor is collapsed by default.

Sample operations reuse the existing receipt and balance transactions. Receipt can apply actual models to multiple styles, defaults the custodian to the signed-in person, and accepts a real system user. Existing samples support batch location moves. Receipts, loans, returns and moves keep their original access checks, audit and idempotency; a cancelled form writes nothing.

This source also includes the previously deployed source-snapshot completion, phone-display and query-performance fixes. Runtime images use a fixed, byte-verified slim base rather than accumulating earlier application layers.

Validation: 180 backend cases (155 isolated PostgreSQL, 17 unit, 8 authenticated route/interceptor cases), 326 distinct final frontend cases in 14 files, type checking and full Vite build. Local rendered component checks and subsequent normal authenticated production UI checks are recorded separately; component rendering is not claimed as full Spring integration.

Application source/test manifest: 806ce12671786c0237f9f4113ebfc0a8390a5f4323452cac4f0957eac9970719 (1329 inputs). JAR: f8d23e393212f61ca7d799f1894e3e0623232f5bde6de855254143aa3b2ef310. Frontend index: fbc40684d51a6e9ca616aa266501c265d15dd461567023f75052c0f5147d46a1.

No schema migration, dependency change, new backup or test data import. Public history's five previously sanitized seed/test resources are retained as public templates; original production seed bytes and business records are not published. Business source files, attachments, credentials, deployment evidence and local QA artifacts are excluded.
