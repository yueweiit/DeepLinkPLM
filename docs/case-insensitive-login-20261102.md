# Case-insensitive login names

Look up the existing username with a parameterized lower-case comparison. Password verification, active/deleted-account gates, persistent sessions and mandatory login audit remain unchanged. A lookup with multiple matching accounts fails rather than choosing the first record. No account or permission is created by this code change.

Ten new cases run against an isolated PostgreSQL database; together with nine existing authentication, grant and audit cases, 19 executions passed without failures or skips. The ordinary packaged backend matches the tested authentication class. Only that application class and the generated classpath ordering differ from the preceding backend; all 112 migration entries and 95 dependency entries are byte-identical. Frontend source and the deployed static assets remain unchanged.

This code-only release uses the existing backend runtime. It does not introduce schema changes, credentials, business records, backups or external-system writes. Private deployment evidence and actual account data are excluded from this repository.
