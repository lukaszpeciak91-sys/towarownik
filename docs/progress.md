# Progress

## Current phase

**Android advisor integration v0.1**

PR #13 completed the OBI text-search false-empty repair. PR #14 established the isolated Cloudflare Worker boundary. PR #15 deployed the authenticated OpenAI proxy contract while keeping Android authoritative for OBI search, exact store-`075` lookup, local stock, and local price.

The Android app now has two intentionally separate test paths:

- **WYSZUKIWARKA** remains the default and preserves the existing OBIK/EAN/text search without any proxy/OpenAI call.
- **DORADCA** sends one customer need to the authenticated Worker, handles normalized `answer` / `tool_request` responses, executes `find_available_obi_075` locally through the existing OBI repositories, sends back only compact verified product records, and displays the final normalized answer.

The advisor flow is deliberately bounded: no automatic request retry, at most two local tool calls per customer case, no persistent conversation history, no reused response/call IDs between new cases, and cancellation when leaving an active advisor flow where practical.

The Android app token is build-time injected through `BuildConfig.TOWAROWNIK_APP_TOKEN`. Builds without the variable still compile and run WYSZUKIWARKA normally; DORADCA reports a local not-configured state before network access. The manual signed Play AAB workflow now requires the matching GitHub Actions secret. This static token is only an Internal Testing abuse barrier and is not strong device authentication.

Version prepared for the next Internal Testing AAB: **0.1.6 (7)**.

## Next implementation milestone

**Hardware validation of search and advisor integration**

On the next signed AAB, verify ordinary WYSZUKIWARKA first (including text queries such as Dedra, Pufas, and clean) without using assistant tokens, then separately validate DORADCA end-to-end against the already deployed Worker.

After technical validation, tune the final advisor personality/prompt and cost/policy behavior as a separate iteration.

## Not started

- Final "Justyna" advisor persona/prompt
- Persistent conversation history
- Strong per-device/user identity
- General chat
- Additional agent tools
- OpenAI built-in tools
- Streaming
- Cloudflare KV/D1/Durable Objects
- Server-side OBI implementation
- Camera barcode scanning
- Local and nearby-store fallback behavior
- External product-link behavior
- Persistence/history/favorites
- Product images
- Final Play release polish
