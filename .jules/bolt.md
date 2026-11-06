## 2026-11-06 - N+1 Query in Pagination Maps
**Learning:** Found a common anti-pattern where a Spring Data `Page.map` operation contains a repository `findById` call inside the lambda, triggering an N+1 query problem per page load.
**Action:** Always inspect `Page.map` operations for database calls. Optimize by extracting IDs first, bulk-fetching via `findByIdIn`, and using an in-memory Map for O(1) lookups during the DTO mapping phase.
