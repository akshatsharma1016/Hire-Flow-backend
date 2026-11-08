## 2023-11-08 - Tika Instance Caching
**Learning:** `org.apache.tika.Tika` instantiation is extremely slow (~2.5s for 100 instantiations in my benchmark). `ResumeGatekeeperService` instantiates a new `Tika` for every non-PDF file evaluated.
**Action:** Share a single thread-safe `Tika` instance via dependency injection or a class field.
