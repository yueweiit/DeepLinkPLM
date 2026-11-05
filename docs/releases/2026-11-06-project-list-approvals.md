# New product list: complete approvals and aligned layout

The project list shows every linked mold approval and an explicit count. Each record keeps its model range, original approval number, status and its own progress action. The list retains the existing summary endpoint and detail drawer; opening a record does not create or submit an application.

Project, progress, approvals and actions use aligned columns. Approval records have consistent spacing and readable text; the main project action is prominent. Narrow containers wrap into a complete layout without horizontal scrolling or changing the user's sidebar preference.

Validation: 170 existing view tests passed, including the expanded zero/one/two/three-approval case and opening the second record. Type checking and the frontend build passed. The actual Vue page was checked with synthetic local fixtures at 1788, 1000 and 900 × 870 pixels. Twelve existing web-only publisher lifecycle tests passed.

This release changes two frontend source/test files (+46/-15 lines), modifies one existing test definition and adds no test definitions, business storage or duplicate write path. Existing routes, permissions, source records and the approval drawer remain in use. No compatibility shim or schema migration is added. The backend package and login sessions remain running. Deployment creates no backup and imports no business or QA data. Further cost-group changes are outside this frontend release.
