# Progress

## Current phase

**V0.1 — OBIK lookup device POC**

The first end-to-end Android lookup flow is implemented. The Compose screen accepts exactly seven numeric OBIK digits, validates input locally, runs the existing store `075` lookup off the main thread, and presents loading, success, and human-readable error states. A successful result shows only the product name, exact Nowy Sącz stock when known, and local Nowy Sącz price when known. Unknown stock or price remains explicitly unavailable rather than becoming zero.

The flow is ready for manual device validation with OBIK `7313810`. CI remains deterministic and does not depend on live OBI.

## Next implementation milestone

**EAN and product-name search**

Extend the unified search input beyond the current OBIK-only POC while preserving the repository/transport/parser boundaries. Text search should remain bounded to at most five results.

## Not started

- EAN/name detection and search
- Multiple text-search results
- Local and nearby-store fallback behavior
- External product-link behavior
- Barcode scanning
- Final responsive visual polish
