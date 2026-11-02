# Flexible unit-cost entry

The existing unit-cost sheet uses a compact editable grid. Rows can be added, copied, selected for batch editing, pasted from an Excel rectangle and restored with the local undo action. Long item names wrap, and the current calculation mode keeps source amounts, usage or allocation quantities and calculated per-unit results distinct. Editing triggers the existing preview calculation; saving still follows the existing draft and version workflow.

The sheet can also contain named supplementary text or numeric columns, while optional tax, loss, description and remark fields can be shown in the grid when useful. Supplementary columns and their cell values are stored with the sheet and retained by normal draft save, reopen and version copying. They do not enter the cost algorithm. Additional chargeable expenses continue to use normal cost rows and the established expense categories; a new column name does not create a new formula or silently change totals.

Rebuilding a draft from its selected BOM preserves the supplementary column definitions and optional-column choices. Row values continue to follow the existing BOM rebuild semantics: old supplementary cell values are not guessed onto different rows by position. Original BOM references, source evidence, unknown values and explicit zeroes keep their existing meaning.

The existing calculator, business save interface, access rules, conflict handling and confirmed-version history remain in use. The update adds no schema migration or dependency and does not change accounts or business records as part of program deployment. Public source contains no original business files, live prices, private deployment evidence, credentials or QA preview service.
