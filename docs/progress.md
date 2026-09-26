# Progress

## Current phase

**V0.1 — OBI browser-compatible transport fix**

The live hardware probe resolved the transport uncertainty:

- profiles A–E (default OkHttp, native Towarownik UA, Accept-only, language-only, and native combined HTML profile) received empty HTTP 404 responses from CloudFront;
- profile F, changing only the User-Agent to the fixed synthetic browser-like Android Chrome test UA, returned HTTP 200;
- profile G, using the same browser-like UA plus HTML Accept and Polish Accept-Language, also returned HTTP 200;
- `/search/dedra/` returned real OBI HTML with populated search results;
- product lookup using the browser-compatible profile successfully followed the existing `/api/disc/store/change` → bare `/p/{OBIK}` → canonical slug → product-page flow;
- a fresh session works, so no session bootstrap is required;
- the bare `/p/{OBIK}` redirect target is valid and no canonical slug needs to be discovered in advance.

Production OBI requests now use the proven browser-compatible HTML navigation profile centrally. URLs, redirect following, cookie handling, parsers, repositories, and search/product orchestration remain otherwise unchanged.

The live probe also proved that an empty CloudFront 404 can describe an infrastructure compatibility failure for an existing resource. A narrow safeguard now maps only the confirmed signature—HTTP 404 + empty body + `Server: CloudFront` + `x-cache` containing `Error from cloudfront`—to the existing server/network failure path. Ordinary 404 responses retain the existing business not-found behavior.

The diagnostic system and live probe remain available for verification. The diagnostic keyword heuristic for words such as "robot" or "captcha" is not treated as evidence of a real access challenge in this transport decision.

## Next implementation milestone

**Validate the fixed production transport on hardware**

Build the next Internal Testing AAB and verify normal OBIK, EAN, and text searches through the production UI while keeping diagnostics available for evidence if OBI changes again.

## Not started

- Camera barcode scanning
- Local and nearby-store fallback behavior
- External product-link behavior
- Persistence/history/favorites
- Product images
- AI
- Final Play release polish
