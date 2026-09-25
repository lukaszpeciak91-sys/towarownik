# Progress

## Current phase

**V0.1 — unified OBI product search**

Towarownik now uses one search field for three supported inputs: a seven-digit OBIK, plausible EAN/GTIN values, and product-name text. OBIK remains a direct lookup. EAN and text use OBI's public search route and produce at most five ordered candidates when selection is required.

Text results are always user-selected. Multiple EAN results are also user-selected; a single EAN candidate opens directly only after the selected product payload confirms the queried EAN. Candidate selection then reuses the existing store `075` lookup, preserving exact Nowy Sącz stock and local-price semantics.

Search parsing distinguishes explicit no-result states from data/parser failures. CI uses deterministic UTF-8 text fixtures and does not depend on live OBI.

## Next implementation milestone

**Device validation and next bounded V0.1 capability**

Validate unified OBIK/EAN/name search on a real Android device before adding another product capability.

## Not started

- Camera barcode scanning
- Local and nearby-store fallback behavior
- External product-link behavior
- Persistence/history/favorites
- Product images
- AI
- Final Play release polish
