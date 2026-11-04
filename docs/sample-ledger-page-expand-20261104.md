# Sample ledger: atomic details and complete bulk actions

The sample ledger opens with the current page expanded. Its toolbar can collapse or expand all page styles together. Page details use one existing scoped batch read, preserve per-style 20-item pagination, and reuse cached reads. An outdated individual read cannot replace a newer batch result or bring back a cleared selection.

Style images remain visible above their details and open directly in the existing preview. Repeated images are removed from model/colour detail rows. The preview preserves the sample-scoped photo access path, including the Mexico role; general attachment downloads keep their existing permissions.

Filtered and cross-page sample selection uses real identity IDs. Bulk receipt corrections, loans, returns, location setting, shipments and labels keep their original transactional services and authorization. During a pending full-selection read, batch buttons wait until the complete selection is ready. Existing numbering, receipt history, positions, files and balances are preserved. This release creates no sample identities, receipts or products.

Validation:

- 208 frontend cases passed, including 8 new independent expansion/selection scenarios and 39 existing photo/attachment regression cases.
- 483 backend cases are reused from the identical frozen backend inputs and identical packaged JAR; they were not rerun for this frontend correction.
- Frontend types and production build passed.
- A local browser with synthetic readonly APIs checked 50 styles / 200 detail rows appearing together, one batch request, cached reopening, image preview and 1788/1000/900 px layouts. This is not a production receipt mutation test.
- Deployment uses no new DDL, business-data import or backup. Old static resources remain compatible with open pages.

Single-style details and later detail pages continue using the existing paginated read endpoint. They remain for individual expansion, retries and per-style paging, rather than a second write path.
