## 2026-11-07 - Fixed N+1 Query in Skill Persistence
**Learning:** Found a textbook N+1 query vulnerability in `LivingProfileService.java` where candidate skills were resolved one-by-one inside a loop during resume promotion.
**Action:** Always check loop structures interacting with repositories in domain logic. Prefer `findAllById` over repeated `findById` calls for entity resolution.
