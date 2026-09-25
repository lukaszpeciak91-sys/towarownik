# Decisions

The following decisions are approved for V0.1:

- Build a native Android application using Kotlin and Jetpack Compose.
- Use package and application ID `pl.lukaszpeciak.towarownik`.
- Use no backend.
- Provide one unified search field.
- Automatically detect a 7-digit OBIK product code, EAN, or product name.
- Return at most five results for text searches.
- Use store number `075` in Nowy Sącz as the default store.
- Focus a result on product name, exact local stock, local store price, and an external product link.
- When local stock is zero, later check a bounded number of nearby stores.
- Do not include product images.
- Do not include user accounts.
- Do not include analytics.
- Do not include AI in V0.1.
- Support portrait and landscape orientations.
- Use neutral Towarownik branding with no retailer branding.
- Validate through local APK testing first, followed by Google Play Internal Testing.

These decisions describe the broader intended product behavior. The currently implemented subset is recorded below.

## OBI product lookup core v0.1

- A seven-digit OBIK remains a direct product lookup and does not pass through a candidate list.
- Store `075` is selected through OBI's store-change endpoint, using one cookie-preserving OkHttp session through its redirect to `/p/{OBIK}`.
- The selected-store Nuxt payload is authoritative for `stock` and `pricing.grossPrice`. Online price and shipping cost are not substitutes for local gross price.
- Confirmed numeric stock `0` means zero. Missing, negative, or malformed stock means unknown and must not be converted to zero.
- Missing local price remains unknown. Retrieval and required product-identity failures produce an unavailable result rather than invented data.
- No generic retailer abstraction is introduced; transport and structured parsing are OBI-specific and remain outside Compose.

## Unified product search v0.1

- One input classifies exactly seven digits as OBIK, plausible 8/12/13/14-digit numeric values as EAN/GTIN, other non-blank input as text, and unsupported blank/numeric input as invalid.
- EAN and text queries use OBI's public search route; search candidates contain only identification data and preserve OBI's ordering.
- Search lists are limited to at most five candidates.
- Text results are never auto-selected. Ambiguous EAN results also require user selection.
- A single EAN candidate may proceed directly only when the fetched product payload confirms the queried EAN.
- Selecting any candidate uses the existing store `075` product lookup for exact local stock and local price.
- Search parser uncertainty is a data failure, not a not-found guess.
