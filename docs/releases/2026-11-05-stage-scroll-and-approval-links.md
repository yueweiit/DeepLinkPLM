# Development navigation and approval visibility

The development workspace uses one page scroll across all eight stages, with the stage navigation kept visible. Existing approvals can be explicitly linked to an existing project while preserving their original model ranges and approval history.

The project header now provides clear back and project-cost buttons. A compact approval panel shows each model range, current status and its own action together. Approval numbers remain readable at narrow widths. The duplicate first-approval action was removed; creating an application, continuing an editable draft and viewing each original approval retain their existing permissions and behavior.

Current product-name corrections preserve existing codes and historical snapshots. Cost editing retains the unified Edit, Save and Cancel controls, flexible rows and supplementary columns, and existing finance permissions. Linking an approval does not create molds, products or inventory, or grant approval.

Validation includes 220 passing frontend tests, type checking and a production build. Real-component read-only previews with synthetic fixtures were checked at 1788, 1000 and 900 pixels with the sidebar expanded. The backend retains the previously validated package and all 116 migration resources; the final header update builds and switches only the web image.

No backups, schema migrations or business imports are introduced by this update. Existing media, accounts, permissions and static resources are retained. Remote approval submission and group-level progress or cost management are separate business operations.

Authenticated production checks confirmed approval-specific details, project-cost editing and cancellation, and return navigation. The final web-only switch retained the exact backend process and existing business data.
