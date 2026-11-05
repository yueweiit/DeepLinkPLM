# New-product mold scope and complete approval list

New-product applications now use the existing scope editor to select the hole type, describe the structure, and choose the applicable phones. One selected phone remains one phone; multiple phones describe the requested shared scope. Existing-mold reuse keeps its original engineering workflow.

An engineering user can add a missing phone by choosing its brand and entering its model name without leaving the selector. Existing selections are retained, and the new phone's manufacturing code stays unassigned. The shared server naming rule supplies the Modelos preview and the persisted approval form, so saving and reopening no longer replace the factory abbreviation with a different name. Historical codes and source names are retained.

The new-product list displays every associated approval directly, including its applicable scope, approval number, status, and progress link. Product details, development progress, approvals, and actions align in a compact layout and wrap in narrower windows. The previous first-approval truncation and duplicate list popover were removed.

Validation: 35 backend executions, including seven isolated PostgreSQL cases; 321 frontend checks, with the final affected 170-check subgroup rerun without double-counting; type checking and production build. The final JAR also passed a normal Spring startup and validation of the existing 116 migrations. Layouts were checked at 1788, 1000, and 900 pixels.

No schema changes, business-data import, automatic manufacturing-code allocation, or real approval submission are included. Existing data and file references remain in place. The default phone-options response and the non-research engineering editor remain for their existing callers. Pro/Pro Max cost-group changes are outside this release; existing bundling and test-agent warnings remain unchanged.
