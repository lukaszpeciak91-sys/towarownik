# Progress

## Current phase

**V0.1 — OBI product lookup core**

The native Android bootstrap is established. The first data capability now validates a seven-digit OBIK, selects OBI Nowy Sącz store `075` in a cookie-preserving HTTP session, follows the product redirect, and structurally parses matching product identity, exact local stock, local gross price, canonical URL, and EAN when available. Deterministic text fixtures cover positive, zero, missing, misleading-price, malformed, and identity cases. Product UI is not implemented.

## Next implementation milestone

**Product lookup presentation**

Connect repository results to a small responsive Compose flow in a future focused change, without exposing OBI page structure to UI.

## Not started

- Search input and lookup presentation
- EAN/name detection and search
- Product and text-search result presentation
- Local and nearby-store availability behavior
- External product-link behavior
- Final responsive portrait and landscape UI
