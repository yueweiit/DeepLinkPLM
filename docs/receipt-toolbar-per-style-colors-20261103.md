# Compact sample receipt entry and per-style colours

The actual-receipt editor groups its two per-style shortcuts together, with model, colour, quantity and apply controls aligned beneath. It retains the existing responsive table and pagination.

Select-all colours prefers explicit intended sample colours. Otherwise, the existing receipt preview exposes explicit colour names from the frozen per-style catalogue baseline. Supplier-wide matching suggestions are not treated as every style's available colours. Missing or unspecified source descriptions remain empty. Historical model-colour pairs retain their exact per-model scope.

Both shortcuts update the unsaved form only. Existing row identities, quantities, colour exceptions and sample identities remain intact; repeating an action adds no duplicate row. New rows have no actual quantity until the operator supplies it. Colours may be populated before models without using one style's colours for another. The original single save, reconciliation, permissions, optimistic checks and audit remain authoritative.

This change adds no schema migration, dependency, secondary colour ledger or alternative save endpoint. Source provenance, original files and business records are retained. Private runtime evidence and production data are excluded from this public note.

Large-receipt updates use grouped indexes and append missing rows in one pass. Model expansion and single model edits reuse already loaded per-style metadata for at-receipt colour mode; historical model-specific scopes still refresh their authoritative options. Each backend preview reads a selected source row and its frozen snapshot once per request, while checking every row version and actual pair. This cache does not survive a request or user context.

Missing required receipt fields are reported locally before a full validation request. Placeholder descriptions such as colours shown in a picture remain original source text and are not actual colour names. Formal writes retain the original atomic save and request replay rules. A longer timeout is scoped to the actual-receipt write only; ordinary requests retain their default timeout, and unknown write results keep their original reconciliation identity.

Pending-receipt storage shares identical at-receipt source metadata by style instead of repeating the full model list for every expanded line. All original input rows and the request identity are retained; restoration also accepts the previous per-row metadata format.

Legacy receipt colour defaults are read only when the optional selected-colours field is absent. Explicitly empty selections remain empty; editing another field does not erase that distinction, and frozen phone/colour pairs retain their original rules.

Validation: 140 backend cases, including isolated PostgreSQL receipt checks, and 129 frontend cases passed. Type checking and the production build passed. No schema migration or historical data rewrite is included.
