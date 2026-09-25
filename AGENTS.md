# Working Rules

These rules apply to the entire repository.

1. Read `README.md`, `docs/architecture.md`, `docs/decisions.md`, and `docs/progress.md` before modifying code.
2. Keep changes incremental. Each pull request must contain one coherent feature or fix.
3. Do not merge pull requests.
4. Avoid speculative abstractions and unnecessary dependencies. Add a dependency only when a current requirement needs it.
5. Preserve clear boundaries between Compose UI, domain/repository logic, HTTP transport, and OBI-specific parsing.
6. Never invent product, price, or stock data when retrieval or parsing fails. Represent failure or unavailable data honestly.
7. Update project documentation whenever architectural or product decisions change.
8. Run all checks that are technically available before reporting completion.
9. Codex must not create, modify, replace, or delete binary files.
10. Generated APK, AAB, JAR, and build artifacts must not be committed.
11. If a required binary file is missing, report it instead of fabricating it.
