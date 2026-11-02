## 2026-11-02 - N+1 Query Issue in LivingProfileService
**Learning:** Found an N+1 query issue combined with non-batched save statements in the `LivingProfileService#storeSkills` method. The loop was executing an individual `findById` and a `save` for each existing candidate skill, causing significant overhead in JPA.
**Action:** Use `findAllById` passing a list of IDs to resolve the read N+1 problem, and accumulate instances into a list to use `saveAll` for batch inserts. This pattern should be consistently applied across the codebase whenever operating on collections.
