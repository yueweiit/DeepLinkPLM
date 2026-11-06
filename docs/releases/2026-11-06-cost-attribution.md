# Assigning saved cost drafts

The existing project cost page now has a direct attribution action. Users explicitly choose the target specification; the saved server-side draft moves without copying confirmed versions, overwriting an occupied draft or changing physical product identities. Budget and expense attribution uses the existing source editor, record IDs, permissions, original-value checks and audit transaction.

Attribution alone keeps unit prices and actual expense states unchanged. Both save entrances preserve an explicitly disabled investment link, and saving no longer confirms unedited actual drafts or pending corrections. Intentional edits to actual amounts retain the existing financial confirmation rules. The project retains one current source-outlay reference; historical versions and attachments remain readable. New labels use the existing Spanish dictionary.

Validation: 435 project frontend cases and 3 existing language cases passed. Backend regression discovered 337 cases: 304 passed, 33 existing conditional cases skipped. Type checks, builds and normal Spring startup on isolated PostgreSQL schema 117 passed. Ordinary local browser verification covered attribution, reopening, both save paths, unchanged actual records and three window widths with the sidebar expanded.

No migration, endpoint or business table was added. Existing ungrouped draft source fields remain supported until each project is explicitly attributed; historical snapshots keep their original source references. Deployment does not choose a real cost group or submit an approval.
