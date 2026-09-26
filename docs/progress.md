# Progress

## Current phase

**V0.1 — OBI live contract probe v0.2**

Hardware diagnostics confirmed that the current native OBI requests fail before parsing:

- search `GET https://www.obi.pl/search/dedra/` returns HTTP 404 from CloudFront with `x-cache=Error from cloudfront`, an empty body, no redirect, and no cookies;
- product lookup starts with `/api/disc/store/change?storeNumber=075&redirectUrl=...` and receives the same class of CloudFront 404 with an empty body, no redirect, and no cookies;
- the product/search parsers are therefore not reached in these failing live cases.

The next diagnostic build does not assume a replacement OBI contract. It adds a user-triggered hidden live probe that compares baseline endpoint paths, request-profile A/B/C/D/E variants plus diagnostic-only synthetic browser-like F/G profiles. Session-bootstrap S0/S1/S2 now runs unconditionally for baseline A, native HTML E, and browser-like HTML G so search and store-selection behavior are tested independently. If any of those store flows reaches a non-404 response, the probe also compares bare versus canonical-slug redirect targets. Probe output remains sanitized and separate from the normal bounded operation history. Live-probe body evidence is explicitly a 65,536-byte preview with `previewUtf8Bytes`, `previewLimitBytes`, and `previewTruncated=true|false|unknown`; it is not reported as a complete response-body size.

The search field was also hardened after hardware testing exposed retained multiline/control input. Submission now snapshots a normalized immutable query, clears the visible field immediately, and lets the submitted lookup continue independently. CR/LF and other control/whitespace runs are normalized to a single space before classification or URL construction, so `3496072\n` becomes OBIK `3496072` and `qbrick\nsystem` becomes text `qbrick system`.

The signed Google Play AAB workflow remains available so this probe and input hardening can be exercised in one next Internal Testing build.

## Next implementation milestone

**Run the live probe on the Android device**

From the hidden diagnostics screen, enable diagnostics and run **Uruchom test OBI**. Compare endpoint behavior, profiles A–G, the complete A/E/G session-bootstrap matrix, bounded preview evidence, and the same-device browser controls before changing any production OBI URL, header, redirect, parser, or not-found assumption.

## Not started

- Production OBI integration repair based on live probe evidence
- Camera barcode scanning
- Local and nearby-store fallback behavior
- External product-link behavior
- Persistence/history/favorites
- Product images
- AI
- Final Play release polish
