# Progress

## Current phase

**V0.1 — live OBI diagnostic instrumentation**

Unified OBIK/EAN/name search remains unchanged. A temporary in-app diagnostic mode now captures bounded, sanitized evidence for OBI transport, redirects, cookie names, response signatures, parser decisions, and final error classification so live failures can be investigated from the phone without Android Studio or Logcat.

Diagnostics are OFF by default and session-only. The report never stores cookie values or complete response bodies. The normal request URLs, redirect behavior, headers, parsing rules, and not-found semantics are intentionally unchanged in this milestone.

Deterministic CI validates the diagnostic recorder against mocked redirects, HTTP failures, cookies, product pages, and search parser decisions. It does not prove compatibility with live OBI.

## Next implementation milestone

**Live OBI contract validation**

Run the diagnostic build on a real phone with OBIK `3496072`, text `dedra`, and control text `qbrick system`. Use the resulting report to locate the first live transport/session/parser mismatch before implementing any integration fix.

## Not started

- Live OBI integration repair based on diagnostic evidence
- Camera barcode scanning
- Local and nearby-store fallback behavior
- External product-link behavior
- Persistence/history/favorites
- Product images
- AI
- Final Play release polish
