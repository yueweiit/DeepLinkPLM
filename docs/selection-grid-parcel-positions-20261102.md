# Selection grid, sample defaults and parcel positions

The selection paste dialog uses an editable grid with column headers and row numbers. It reuses the existing rectangular parser and incremental save, preserves zero values, and does not generate new products or sample batches. The retired string-to-array conversion had only test callers; the production path uses one array conversion.

New outbound parcel drafts default from the China sample room to the Mexico sample room. Receiver selection is labelled System users. Domestic authorized ledgers display actual current positions and quantities. Mexico scan shows only effective receipt-time locations belonging to the assigned parcel; undone receipts are excluded. It does not expose whole-stock positions or claim current shelves after mixed-batch moves.

Batch location moves default each original allocation quantity to one while retaining its true stock maximum. The explicit all-on-hand action remains available. Existing pending actions, loans, returns and same-location skips retain their behavior.

Validation: 154 affected frontend cases in ten files, type checking and ordinary Vite build passed. Reused the unchanged standard Maven backend package and its 26 executed cases: twelve isolated PostgreSQL cases, six ledger unit cases and eight authenticated route cases. The only changed backend class is SampleShipmentService; 112 migration resources and 95 dependencies remain byte-identical. The prior selected-phone summary and read-only generation view remain byte-identical.

Source/test manifest: 777830ed8bb3c162aaf2ae3935831ac5ea3d358d8091792c8de824552627014f (1331 inputs). JAR: f956eadd619aaca616c64a7f93f5f00af2d6138d9a7b0bad5a270f7a4245ebf0. Frontend index: 492648154256fb9ee7b99d835f7b3805bb78e61dbc659c2f9942e134a7bbabff.

No new backup, schema migration, dependency, production fixture or business-data import. Existing public sanitized seed/test templates remain unchanged. Private supplier files, credentials, operational evidence and local QA files are excluded.
