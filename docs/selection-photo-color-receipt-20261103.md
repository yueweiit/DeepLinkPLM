# Direct photo previews and flexible sample entry

Selection-row images open directly in a large preview using the clicked attachment identity. The gallery contains only that row's referenced images. Original attachment metadata, versions and download access remain available separately; non-image documents retain the existing metadata-based preview or download path.

The shared viewer keeps its authenticated metadata checks and Blob lifecycle. Switching, closing or leaving the current record releases pending requests and temporary URLs. Looking at images does not save selection changes or change adopted attachment references. The existing upload and document-library entry points remain available for their original purpose.

Supplier color choices and selected sample colors are explicit fields. The selection workflow preserves the chosen values, and the receipt editor supports repeated entry and application of actual model, color and quantity values while retaining per-row exceptions. Planned quantities remain separate from actual receipt facts. Existing product, sample and inventory identities are not created merely by browsing or selecting a picture.

The implementation reuses existing catalog, selection, sample, attachment and receipt components and business write paths. It introduces no schema migration or dependency. Accounts, permissions, original images and business records are retained by program deployment. Private operational evidence, live business information, credentials and QA fixtures are excluded from this public source update.
