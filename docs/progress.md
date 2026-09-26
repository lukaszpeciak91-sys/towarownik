# Progress

## Current phase

**V0.1 — OBI live payload/parser contract repair**

Hardware verification of app `0.1.2 (3)` confirmed that the browser-compatible production transport is working end to end:

- the store-change request for `075` returns HTTP 302 to the bare product path;
- OBI redirects the bare product path to the canonical slug;
- the final product response is HTTP 200 and contains real OBI HTML;
- the production request uses the proven browser-compatible User-Agent, HTML Accept, Polish Accept-Language, redirect handling, and cookie session;
- the full response contains valid `__NUXT_DATA__`, the requested OBIK, store `075`, and the canonical product URL.

The remaining failure is parser-side. `ObiPayloadParser` successfully parses the Nuxt JSON but its historical object-shape assumption no longer finds the expected product/store context, producing `PRODUCT_ID_MATCH_FAILED`, `STORE_075_MATCH_FAILED`, and a DATA error.

A developer-only GitHub Actions live contract probe is being added so current public OBI HTML and Nuxt structure can be inspected without rebuilding the Android app for every diagnostic iteration. The probe keeps its cookie jar out of artifacts and emits bounded structural summaries suitable for updating deterministic sanitized fixtures.

## Next implementation milestone

**Map the current live Nuxt structure and repair `ObiPayloadParser`**

Use the live contract artifact to identify the current product, store, local stock, and local gross-price relationships. Update the parser only from confirmed evidence, add sanitized fixtures/tests for the new structure, then perform one final Android hardware verification.

## Not started

- Camera barcode scanning
- Local and nearby-store fallback behavior
- External product-link behavior
- Persistence/history/favorites
- Product images
- AI
- Final Play release polish
