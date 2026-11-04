# Sample ledger: expand all visible styles

The sample ledger now offers **Expand all on this page** and **Collapse all on this page** beside Refresh. Existing model, colour and quantity details use the same read-only child lists and pagination. Reopening details preserves selections and uses the existing cache. Chinese and Spanish labels are included.

Only the ledger component and Spanish translations changed (11 lines added, 2 removed); no API, persistence, dependencies or migrations were added. The existing 38 related tests, type checking and production build passed. Local browser checks covered expanded and collapsed states, retained selection, cached reopening and narrow windows. Backend code and authentication are unchanged.
