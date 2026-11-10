## 2026-11-10 - Fixed N+1 Query in Profile Service
**Learning:** Found N+1 queries during entity promotion when looping over relationships (skills).
**Action:** Use batch operations (`findAllById`, `saveAll`) in Spring Data JPA instead of querying in loops.
