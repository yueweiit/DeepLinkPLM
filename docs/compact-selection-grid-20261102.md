# Compact selection grid for long original fields

Real selection records can contain dozens of model names and long source notes. Remove the 560px content-height cap to use the available viewport and keep grid rows at a compact fixed height; the active cell has a persistent full-text editor above the grid. Selecting another cell changes the editor without losing the full value. Rectangular paste, line breaks, zero quantities, readonly mode, review and the existing incremental save keep the same path.

The CSS-only height line reuses the 155 previously verified functional cases in ten files; no new cases or repeated runs are counted. Fresh type checking, full Vite build and active-long-field browser checks passed. Actual browser checks use a 39-model / 1392-character field and 945-character notes at 1788, 1000 and 900 by 870: rows remain 67px with at least five complete visible rows, and editing preserves full source values in the original incremental request. Earlier excessive row growth with real production text was a failed density check and is not counted as passed.

This is frontend only. All backend inputs and the existing f956 backend JAR remain unchanged. The release retains the existing backend container, configuration and sessions; no backend rebuild/restart, new schema, dependency, backup or production fixture is required.

Source manifest: 319eaafc3012e3c699f7eabdbf1c19fb2f8c8f54b683f2f6012b79a95a955046 (1331 inputs). Frontend index: c14d16cf3fcc291ba21fa5f0e48b8558f235f998c3f347c63d984f1d885c65d8. Aggregate versus the deployed grid baseline: three files. The final height follow-up itself is one CSS line (+1/-1), with no JavaScript, template or backend change. With the long-text editor expanded, 870px-high windows show at least five complete rows; a 720px-high window shows three with the footer accessible.

The previously delivered selected-phone brand/generation view, sample move default one, parcel location rules and account access boundaries are retained. Original business files, credentials and private deployment evidence are excluded from this public source update.
