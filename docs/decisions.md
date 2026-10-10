# Decisions

## 2026-10-10 — PR #102 named-locality grammar

Use an anchored, complete location phrase rather than classifying individual words after `sprawdź` or `w` as cities; avoid an ever-growing inventory-word blacklist. All named places in `i`, `oraz` and comma groups must resolve before stock HTTP. A single unsupported city rejects the complete restricted scope with typed `unknown_location`. Generic inventory requests remain all-other with the active market excluded, and the Worker/tool contracts are unchanged.


## PR #102 — 2026-10-10 focused location authorization hardening

An exact trusted product card is necessary but insufficient to authorize other-store stock HTTP. Android independently requires explicit location availability intent and interprets city/store tokens only in the current user text. The model's empty location list cannot convert `Sprawdź w Tarnowie` into an all-OBI scan. Isolated dimensions such as `100 cm` never become market IDs. The 45-second overall OBI deadline prevents seven sequential 15-second HTTP calls from yielding 105 seconds of cumulative delay; timeouts are reported as partial/unknown without fabricating zero. Nationwide partial presentation preserves the originally requested all-other scope. All other existing contracts, KWANT `extended` production disablement, and Worker capabilities remain unchanged.


## 2026-10-10 — PR 2 opt-in Advisor locations integration

- One explicit-capability model function `find_product_locations` is registered only if `X-Taksula-Locations-Capability: 1` is supplied. Existing v2/v3/v4/v5 families remain stable; old Android versions without the capability header receive unchanged tools, so deploy Worker before shipping the Android version.
- Android alone authorizes product identity from exact current-turn verification or recent assistant product cards from the **same persisted conversation**. The model's ID and location list remain hints. A single unambiguous historical product supports natural follow-ups; multiple cards require an exact reference/clarification. Historical snapshots never auto-promote to current-turn output product cards.
- An explicit user request for *other-market availability of one already verified product* authorizes all **other** canonical OBI markets by default; model `locations=[]` is correct and Android independently derives 61 IDs from the canonical 62-market directory, excluding the selected market. Explicit user-named cities include **all** canonical markets in each city; exact market requests restrict the scope. Contradictory/model-invented locations are rejected rather than used as authority; canonical unknown cities are never invented. Four sequential max-20 service calls issue at most seven 10-market HTTP requests for 61 markets; this is **one** logical Advisor tool invocation. Failures yield explicit missing `stock=null` rows, and full-other coverage is not all-public-market coverage. Render positive-first **above** transport. KWANT still returns typed production unavailability until `extended` is evidenced.
- Model requests are counted against the existing three local calls per user turn (independent of HTTP batch count), without modifying normal search, profile, Room or UI. Rejection and unavailable remain typed safe tool facts; Worker strict JSON validation and continuation budget remain in force.


## 2026-10-10 — Product locations transport PR 1 (isolated, no Advisor integration)

- Introduced a single Android internal `ProductLocationsService` dispatching the existing provider-owned `ProductRef` to separate OBI and KWANT readers, with typed invalid/unavailable/unsupported outcomes, location stock, exact zero, explicit requested/returned/missing coverage, and optional separately verified matching-product central stock. No products or locations are invented and no WorkingProfile is mutated.
- OBI PR #101 transport follows independently live-observed `GET /api/pdp/v1/stock/{OBIK}?storeIds=...`, strict storeId/availableQuantity rows, canonical IDs, max **10** IDs/HTTP request and max **20** requested IDs/**service read** (two batches). PR #102 composes multiple sequential bounded reads **above** transport to check all other markets. Partial batch failures do not masquerade as complete national inventory. A one-shot 62-store provider request remains unproven.
- KWANT browser observation independently confirmed one-shot 21/21 branch inventory from `GET /api/front/products/{id}/departments`, but the sanitized capture retained only the `extended` query parameter **name**, not its value. Production requests are **disabled** behind `UNVERIFIED_REQUEST_CONTRACT` until its exact value is evidenced; no speculative request omission or value is allowed. Strict parser/one-GET adapter are offline-testable with an explicitly labeled synthetic fixture query. `total_stock` stays uninterpreted, central stock stays separate.
- PR 1 intentionally adds **no model-facing tool, Worker/protocol/prompt change, Room change, UI change, search fanout or background refresh**. PR 2 must enforce exact product/trusted-reference authorization before calling this service and decide explicit location intent independently. Remaining blocker: authoritative reproducible `extended` query value for KWANT. Research evidence files remain historical and unchanged.


## 2026-10-09 — multi-attachment phase 2 Android UX and ownership

- Room v12 replaces the single attachment/message key with `(messageId,position)` and preserves every v11 row at position 0. No blob storage; private file IDs and metadata remain unchanged.
- Composer supports up to three ordered attachments via multi-select photo/document pickers, one-at-a-time camera, individual removal and replacement, with localized 3-file/24 MiB limits. All attachment turns now opt into v5; text-only protocol families remain unchanged.
- Pending/staged/retired ownership is journaled as durable sets before publishing/deleting files, startup checks Room ownership before cleanup, and failed turns reclaim the full list before Room recovery. Sent chat renders each attachment separately so one corrupted file fails soft.
- No proxy transport or provider-authority changes.


## 2026-10-09 — opt-in multi-attachment transport v5

- Preserve the deployed v4 single-attachment multipart contract and its early 16 MiB + 16 KiB Content-Length rejection; opt-in v5 requires an explicit `X-Taksula-Attachment-Protocol: 5` header matching `protocolVersion: 5` in the JSON payload.
- V5 accepts 1–3 ordered `attachment` parts on START/MESSAGE, bounded to 16 MiB per file, 24 MiB aggregate raw files, and a 24 MiB + 16 KiB multipart envelope, with existing JPEG/PNG/PDF MIME and signature validation. All parts must pass before one Responses USER input is constructed.
- Android adds a list-taking transport overload, while the existing composer, single-file methods, Room schema, and file ownership remain on v4. Continuation stays JSON-only under the initiating provider-aware contract; local stock/price authority and Advisor decision core are unchanged.


## 2026-10-08 — shared advisor instruction composition

- OBI and KWANT now share one exact provider-independent advisor decision core and one exact evidence/trust policy. Provider differences are expressed only through small OBI and KWANT appendices, while attachment behavior is a separate cross-cutting capability appendix.
- The shared decision core owns intent routing, the three-state product-selection model, harmless browseable-variant ambiguity, task/job handling, bounded-assortment wording, restrained complements, and concise advisor behavior. State 1 (unknown category) clarifies with no local lookup; State 2 (known category with an unresolved decision-critical variant) clarifies or gives an actionable resolution step while safely browsing plausible verified candidates in the same turn without claiming a correct match; State 3 (sufficiently specified) verifies immediately. The core deliberately contains no provider identifiers, local tool names, or protocol-version vocabulary.
- OBI remains a broad DIY/home-improvement retail advisor with OBIK and OBI market semantics. KWANT remains an electrical-wholesale/B2B technical-sales advisor with article/EAN/productId, central-vs-branch stock, online-price, and requestedBranch semantics.
- Wire contracts are unchanged: legacy OBI v1 remains single-query, OBI v2 remains grouped find_obi_products, provider v3/v4 remains grouped find_products, protocol versions and final-answer schemas are unchanged, and attachment capability is composed only for v4.
- Backward exported prompt constants remain as composed aliases for compatibility; the authoritative business-policy source is the shared core/evidence blocks plus provider/capability appendices.


## 2026-10-07 — shared provider branch/location resolver

- Branch/location authorization is centralized in one Android `BranchResolver` over the active provider's `ProviderBranch(branchId, name, address)` directory. OBI and KWANT do not keep separate natural-language routing logic.
- OBI's supported-store allowlist is now derived from one canonical metadata directory verified against OBI Poland's official customer-relations market list; the existing three-digit store number remains the authoritative branch ID.
- Matching is deterministic and conservative: exact branch IDs and bounded current-branch aliases remain independent; natural city/name/street/address matching is enabled only by an explicit branch/store location phrase in the current USER message and the metadata must be adjacent to that intent marker after small location connectors. Incidental product text such as `długą listwę`, `produkt do Krakowa`, or a bare `Wielicka` does not become branch intent. No geocoding, distance inference, or edit-distance fuzzy matching is added.
- The current user message authorizes branch identity. Model-produced `storeNumber`/`requestedBranch` is only a hint and cannot turn an incidental city/street token into authorization. Ambiguous aliases such as Kraków with explicit branch intent and explicit unknown locations fail closed.
- A uniquely resolved cross-branch lookup rewrites only the local tool argument for that turn. The persisted WorkingProfile and conversation branch remain unchanged. Provider directories remain isolated and there is no cross-provider fallback.

## 2026-10-07 — conservative advisor fact relevance rescue

- The shared OBI/provider advisor shaping boundary keeps source order as the default and still applies the existing 220/6/60/100 transport bounds and blank-fact filtering.
- When more than six valid technical facts exist, the first six remain the baseline. At most one later fact may replace one selected fact, and only when deterministic lexical matching finds a clear query relevance signal that is stronger than the weakest selected fact.
- Clear signals are deliberately small: exact technical value+unit matches such as `16 A`, `400 V`, `IP65`, or `2,5 mm2`; a small universal technical alias set (voltage/current/power/phase/pole/IP/diameter/cross-section/thread/dimensions); or multiple meaningful shared tokens. There are no embeddings, AI calls, category classifiers, or product-specific ontologies.
- Replacement is minimal: among equally weak selected facts the latest one is displaced, all other selected facts keep their positions, and only one rescue is allowed per product. If no later fact has a clear stronger signal, output remains the first six useful facts exactly as in the previous shaping policy.
- `FindProviderProductsTool` and both OBI paths in `FindObiProductsTool` pass the current tool query into the same selector. Provider parsers, proxy limits, protocol versions, prompts, selection behavior, store/provider lookup, persistence, attachments, and web-search policy are unchanged.

## 2026-10-06 — advisor product transport shaping

- Provider parsers may preserve richer source metadata; the shared Android advisor boundary shapes both provider-neutral `ProviderProduct` and legacy OBI `LocalProduct` before either becomes an `AdvisorVerifiedProduct` for continuation transport.
- Both `FindProviderProductsTool` and both OBI paths in `FindObiProductsTool` (normal search verification and exact OBIK verification) use the same text/fact shaping policy.
- The shared policy normalizes whitespace, bounds short description to 220 characters, keeps at most six deterministic valid technical facts, and bounds fact labels/values to 60/100 characters. Blank normalized facts are discarded.
- This is transport conformance, not query-aware fact ranking; no AI selection is added.
- Existing proxy limits remain unchanged and authoritative. Provider parsing, OBI/provider product identity, stock/central-stock, price, WorkingProfile, Room, attachments, prompts, web-search policy, and protocol versions remain unchanged.

## 2026-10-06 — KWANT selected-branch stock source

- Live Android-like HTTP confirmed the server-rendered product page carries the selected branch identity but not the selected-branch quantity; the hydrated frontend obtains it from `GET https://services.kwant.net.pl/api/front/products/<productId>/current?depstock=<department_stock_id>`.
- The exact-product page remains authoritative for product identity, canonical URL, online price, and structured central `product.stock`. The current-product API is used only for selected-branch stock after exact identity succeeds.
- Selected-branch stock is accepted only when the current-product response root `product_id` matches the expected provider product and root `department_stock.department_id` matches the resolved branch. Missing, malformed, mismatched, or unavailable branch stock stays null; zero remains zero.
- Recommendation endpoints and nested recommendation stock are never consulted for the main product, and branch stock is never added to central stock.

## 2026-10-06 — KWANT live search and main-product stock parsing

- The live audit corrected the earlier Next-data assumption: KWANT search discovery uses `POST https://services.kwant.net.pl/api/front/search-engine/page` with the public frontend JSON contract `q/page/limit/tags`. Next.js buildId and `/_next/data/.../wyniki-wyszukiwania.json` are not search-result sources and are no longer used for Android search discovery.
- Search candidates are parsed from the frontend API `hits[]` using provider-owned numeric `id`, `slug`, `code`, and `name`. Article/catalog number, EAN, and plain-text queries remain search-then-exact-verification inputs.
- Every discovered candidate still goes through the existing exact KWANT product lookup before advisor facts are trusted; there is no OBI fallback or fabricated product ID.
- Exact KWANT central stock comes from the main structured `product.stock` field. Selected-branch stock is parsed only from the main product availability block anchored to the expected product ID, so recommended products cannot contaminate either quantity.
- Selected-branch stock and central stock remain separate and are never summed. Public price scope remains `ONLINE`; WorkingProfile, branch resolution, OBI transport, protocol split, and Room schema are unchanged.
- Local-tool progress copy reflects the active provider (OBI or KWANT) without changing execution routing.

## 2026-10-04 — KWANT logistics visibility

- Selected-branch stock and central stock are separate nullable quantities;
  totals are never synthesized.
- KWANT public gross price remains `ONLINE` and cards call it an indicative
  online price. OBI card labels and protocol v2 remain unchanged.
- Another KWANT branch is a one-off protocol-v3 request resolved by Android
  from the provider directory. It never mutates the conversation
  WorkingProfile and never authorizes cross-provider lookup.

Historical note: entries before the branding rename intentionally use **Towarownik** as the working product name used at that time.

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
- The selected-store Nuxt payload is authoritative for local availability and price. In the current live contract the product is identified by `skuId`, store binding is `product.store.information.storeId`, and local values come only from `product.store.articleData.stock` and `product.store.articleData.pricing.grossPrice`. Seller values and `fallbackPricing` are not substitutes.
- Confirmed numeric stock `0` means zero. Missing, negative, or malformed stock means unknown and must not be converted to zero.
- Nuxt `Ref` and `ShallowRef` wrappers are dereferenced as part of the confirmed flattened payload contract.
- Legacy `selectedStore` fixtures remain supported as a compatibility path, but current live store-bound product data takes precedence.
- Missing local price remains unknown. Retrieval and required product-identity failures produce an unavailable result rather than invented data.
- No generic retailer abstraction is introduced; transport and structured parsing are OBI-specific and remain outside Compose.

## Unified product search v0.1

- One input classifies exactly seven digits as OBIK, plausible 8/12/13/14-digit numeric values as EAN/GTIN, other non-blank input as text, and unsupported blank/numeric input as invalid.
- EAN and text queries use OBI's public search route; search candidates contain only identification data and preserve OBI's ordering.
- Search lists are limited to at most five candidates.
- Text results are never auto-selected. Ambiguous EAN results also require user selection.
- A single EAN candidate may proceed directly only when the fetched product payload confirms the queried EAN.
- Selecting any candidate uses the existing store `075` product lookup for exact local stock and local price.
- Product links alone are not sufficient evidence of search results because OBI may include recommendation/cross-sell product links on a true empty-search page. A positive `Wyniki dla … (N)` count is required before product links are accepted as search results. With a positive count, recognized product links are returned even if generic hidden empty-state wording is also embedded in the HTML. With no positive count, explicit empty-state wording produces `NotFound`; otherwise ambiguity is a data failure.
- Search parser uncertainty is a data failure, not a not-found guess.

## In-app OBI diagnostics v0.1

- Diagnostics are temporary engineering infrastructure and are OFF by default.
- The diagnostics screen is opened by long-pressing the Towarownik title; no permanent diagnostic action is added to the normal search UI.
- Diagnostic history is in-memory only and bounded to the last 10 OBI operations.
- The report may include sanitized request/redirect/canonical URLs, redirect statuses, safe response metadata, parser stages, and error mappings. Unknown query values are redacted; explicitly safe values such as `storeNumber=075` may remain visible.
- Cookie values may be inspected transiently only to determine whether recognizable store context matches `075`; only `true`/`false`/`unknown` evidence and cookie names may be retained. Cookie and Set-Cookie values, full response bodies, tokens, device identifiers, account data, IP addresses, and precise location must never be included.
- Final 4xx/5xx responses may use a bounded in-memory preview to derive the same safe body signatures. `decodedBodyUtf8Bytes` describes decoded diagnostic text re-encoded as UTF-8 and is not a raw HTTP byte count.
- Diagnostic instrumentation must not modify OBI request URLs, request headers, redirect following, cookie/session behavior, parser rules, or not-found semantics.
- Deterministic CI is not evidence that the live OBI contract still matches fixtures; live evidence must be reviewed before revising integration assumptions.

## OBI browser-compatible transport v0.1

- Live Android probing confirmed that OBI/CloudFront returns synthetic empty HTTP 404 responses for the default OkHttp and other non-browser User-Agent profiles.
- Changing only to the fixed synthetic browser-like Android Chrome User-Agent restored normal OBI HTML; HTML Accept and Polish Accept-Language alone did not.
- Production OBI navigation uses the proven browser-compatible profile: the fixed synthetic browser-like User-Agent plus HTML Accept and `Accept-Language: pl-PL,pl;q=0.9`. This compatibility UA is not claimed to be the user's installed Chrome version.
- The existing OBI URLs remain authoritative: search stays on `/search/{query}/`; product lookup still starts with `/api/disc/store/change?storeNumber=075&redirectUrl=/p/{OBIK}`.
- Session bootstrap is unnecessary. Product lookup does not pre-request `/` or a product page.
- The bare `/p/{OBIK}` redirect target is valid; OBI may redirect it to the canonical slug itself, so no slug prelookup is added.
- Only the confirmed empty CloudFront edge 404 signature (empty body + `Server: CloudFront` + `x-cache: Error from cloudfront`) is classified as infrastructure/server failure rather than business not-found. Other HTTP 404 behavior remains unchanged.

## OBI live contract tooling v0.1

- Live Android verification remains authoritative for end-to-end app behavior, but parser-contract discovery does not require a new AAB for every iteration.
- The GitHub Actions live probe is manual-only (`workflow_dispatch`) and is never part of pull-request checks. Normal CI must remain deterministic and must not depend on live OBI.
- The manual probe may request the same public OBI product flow with the proven browser-compatible headers and store selection.
- Inputs are restricted to a seven-digit OBIK and a three-digit store number before any request is made.
- The cookie jar is deleted before artifact upload and captured live payloads are never committed.
- A safe summary artifact contains only sanitized transport metadata and bounded structural evidence. Raw HTML and extracted `__NUXT_DATA__` may be uploaded only after HTTP 200 from the expected `www.obi.pl` host and are treated as short-lived sensitive diagnostic artifacts.
- The inspector must distinguish flattened Nuxt reference indices from resolved values. Raw scalar values are exposed only for an exact allowlist of parser-contract identifiers and values (for example `skuId`, `storeId`, `stock`, `grossPrice`, EAN/GTIN). Other keyword hits expose only reference indices plus type/length/shape metadata, never arbitrary live scalar strings. Relevant allowlisted fields are reported as explicit chains such as `stock -> ref top[793] -> 25`, not as the misleading pseudo-value `stock=793`.
- Deterministic tests cover Nuxt extraction, malformed/missing payloads, reverse-reference traversal, bounded collectors, wrapper dereferencing, value-vs-reference semantics, and identifier validation.
- Parser fixes must still be converted into deterministic sanitized fixtures and tests before merge; the live workflow is evidence gathering, not a replacement for CI fixtures.


## AI assistant / proxy foundation v0.1

- The original V0.1 decisions “Use no backend” and “Do not include AI in V0.1” describe the initial product-lookup milestone and remain part of the project history.
- The assistant phase intentionally introduces one minimal Cloudflare Worker under `proxy/` solely as the future server-side boundary for protecting API credentials and communicating with OpenAI.
- This foundation does not call OpenAI, select a model, implement prompts, expose agent start/continue endpoints, implement tool calling, or consume API credits.
- Future Worker secrets are reserved as `OPENAI_API_KEY` and `TOWAROWNIK_APP_TOKEN`; neither value belongs in the repository or Android application.
- The Worker does not scrape OBI and must not duplicate or move Android OBI transport/parser/repository logic.
- Existing Android OBI search and exact store-`075` lookup remain authoritative for product identity, local stock, and local price.
- A future model may request a high-level local tool such as `find_available_obi_075(query, limit)`, but Android executes that tool and returns only compact structured results.
- OBI HTML and Nuxt payloads are not sent to OpenAI.
- The initial Worker surface is only `GET /health`; unknown routes return bounded JSON 404 and unsupported health methods return 405.
- No paid Cloudflare services, storage products, schedules, custom domains, or automatic deployments are introduced by this foundation.


## Authenticated OpenAI proxy v0.1

- `GET /health` remains public and independent of Worker secrets.
- `POST /v1/agent/start` and `POST /v1/agent/continue` require `Authorization: Bearer <TOWAROWNIK_APP_TOKEN>`. Missing server token configuration fails closed. This shared token is initial Internal-Testing abuse prevention, not strong device identity.
- `OPENAI_API_KEY` exists only in the Worker environment and is never forwarded to Android. The Android Authorization header is never forwarded to OpenAI.
- The Worker uses native `fetch` with `POST https://api.openai.com/v1/responses`; no OpenAI SDK runtime dependency is introduced.
- The current cost-sensitive model is centralized as `gpt-6-luna` with low reasoning effort and a bounded output budget.
- OpenAI request parameters are server-controlled. Android cannot choose the model, instructions, tools, reasoning effort, output budget, or upstream URL.
- No OpenAI built-in tool is enabled. The sole function tool is strict `find_available_obi_075(query, limit)`, with `limit <= 5`.
- The Worker never executes that OBI tool. It validates the model request and returns it to Android; Android remains authoritative for OBI discovery, OBIK, store `075`, stock, and local price.
- Continuation uses `previous_response_id` plus one matching `function_call_output`. Stable instructions and the single tool definition are resent. No Cloudflare persistence is added.
- Android tool results are strict, compact, and bounded: at most five products; seven-digit OBIK; bounded names; stock is non-negative integer or null; price is finite non-negative number or null. Arbitrary extra structures such as OBI HTML/Nuxt are rejected.
- Raw OpenAI responses/errors are not forwarded. Client errors map to bounded 400/413, app auth to 401, missing server configuration to 503, and upstream/protocol failures to 502.
- No application-level OpenAI retry, streaming, web search, file search, computer use, hosted shell, image generation, MCP, analytics, or server-side OBI implementation is introduced.


## Android advisor integration v0.1

- The app exposes two separate top-level modes without a navigation framework: **WYSZUKIWARKA** is the default and preserves the existing OBIK/EAN/text flow; **DORADCA** is the technical assistant integration surface.
- Ordinary search never calls the Cloudflare proxy or OpenAI and remains usable when the advisor build token is absent.
- Android owns execution of the one known tool, `find_available_obi_075(query, limit)`, using the existing `ProductSearchRepository` followed by bounded sequential exact `ProductLookupRepository` calls. No OBI transport/parser code is duplicated.
- A search `NotFound` becomes a verified empty tool result. Search failure stops the advisor flow. Failed exact lookups never create fake zero-stock products; verified successes may be returned alongside skipped failures, but zero verified products plus any exact failure stops the flow.
- Compact tool output contains only OBIK, exact product name, stock, and local gross price. Confirmed stock `0` remains `0`; unknown stock/price remain `null`. HTML, Nuxt, cookies, URLs, diagnostics, and parser reasons never enter the proxy payload.
- `AdvisorController` owns one customer case, stores no persistent history, and enforces `MAX_LOCAL_TOOL_CALLS_PER_CASE = 2`. A third requested tool is not executed and does not trigger another continue request.
- Android proxy transport has no application retry, disables OkHttp connection retry, uses bounded timeouts, and cancels the active Call when its coroutine is cancelled.
- `TOWAROWNIK_APP_TOKEN` is injected through BuildConfig from the build environment. Missing configuration fails DORADCA locally before networking and does not weaken WYSZUKIWARKA.
- The shared static app token is only an Internal Testing abuse barrier and can be extracted from a distributed APK/AAB; it is not treated as strong device/user authentication.
- The manual signed AAB workflow requires the GitHub Actions secret `TOWAROWNIK_APP_TOKEN` and passes it to Gradle only for the release build.
- The final advisor personality/prompt, persistent chat, general chat, additional tools, and cost/policy tuning remain later milestones.


## Chat-style shell and manual OBI search v0.2

- DORADCA becomes the default application surface and is presented as the first real Towarownik chat shell rather than the previous validation form.
- The shell uses a modal left drawer with “Nowa rozmowa”, a local placeholder conversation-search field, and an empty-history area. Persistent conversation history, fake history rows, date grouping, and a conversation index remain out of scope.
- The top bar provides hamburger, centered Towarownik title, explicit new-case action, and direct OBI-search access. Navigation Compose is still unnecessary; state-based surfaces remain sufficient.
- Advisor behavior remains one-shot. UI messages have `createdAt` timestamps and are saveable across configuration changes where practical. Simple Markdown markers are removed rather than introducing a Markdown framework.
- Starting a new advisor case cancels active work, clears rendered case state, and ignores stale callbacks from the previous generation. No response/call IDs are reused across cases.
- Direct Wyszukiwarka OBI is a full-screen local surface independent from proxy/OpenAI configuration. OBIK, EAN verification, text selection, diagnostics, store `075`, and exact lookup semantics remain unchanged.
- Human manual text search uses a separate bounded parser/repository view: up to **25** recognized candidates from the current HTML response, displayed incrementally in chunks of five. The bound is intentionally five UI pages: enough to browse beyond the old first five without unbounded parsing/memory or inventing an OBI pagination contract.
- OBI's reported total result count is preserved separately from the parsed candidate list. “Pokaż więcej” is shown only while additional parsed candidates actually exist.
- The advisor/local `find_available_obi_075` tool remains capped at **5** products and does not use the 25-candidate human browsing capacity.
- Candidate selection still performs the existing exact store-`075` product lookup before exact facts or external links are shown.
- Verified product presentation uses the exact `LocalProduct.productUrl`; Android does not reconstruct or guess product URLs.
- Stock `0` is displayed explicitly as zero/unavailable; unknown stock and price remain “brak danych”.


## Persistent advisor conversations + multi-turn v0.3

- Room 2.8.5 is introduced for the concrete conversation-history requirement; no generic persistence abstraction is added.
- Schema v1 has `conversations(id,title,createdAt,updatedAt,lastResponseId,draft)` and `messages(id,conversationId,role,text,createdAt)`; message rows use a CASCADE foreign key.
- Empty untouched chats do not create database rows. The first send creates the conversation and a title derived locally from the normalized first USER message, bounded to 50 characters; no OpenAI title request is made.
- Drawer history is ordered by `updatedAt DESC`. Phrase search is local SQL substring matching against title and USER/ASSISTANT text, with no embeddings/network/AI.
- Only a final ASSISTANT answer response ID is persisted as `lastResponseId`. Tool response IDs/call IDs are transient. New conversations start with null context.
- The Worker adds authenticated `POST /v1/agent/message`; it accepts only `previousResponseId` and the new message, while model/instructions/tools/reasoning/output budget remain server-controlled.
- `previous_response_id` is used for normal follow-up turns instead of replaying the entire local transcript. This does not make prior context free; previous chain input tokens remain billable.
- `MAX_LOCAL_TOOL_CALLS_PER_TURN = 3`. Every USER message starts with a fresh allowance; the product limit remains 5. Manual human search remains independently bounded at 25 parsed candidates.
- Current OBI stock/price/availability questions must refresh through `find_available_obi_075`; historical conversation values are not current truth.
- Interrupted trailing USER messages are transactionally recovered into editable draft text without advancing `lastResponseId` and without automatic network retry.
- Completing a turn commits the ASSISTANT message and replacement final `lastResponseId` in one Room transaction.
- Local history remains readable even if the corresponding OpenAI chain later cannot continue. This milestone does not silently replay the transcript or start a replacement context chain.
- A conversation represents one customer case. The intended workflow is new customer/new problem -> “Nowa rozmowa”; follow-ups for the same case stay in the same conversation. No hard maximum conversation length is introduced.
- Local conversations are retained for 30 days based on `updatedAt`. Startup cleanup deletes only rows with `updatedAt < now - 30 days`; the exact cutoff is retained. Messages are removed by the existing CASCADE foreign key.
- Retention cleanup is startup-only and local-only: no WorkManager, scheduler, proxy, OpenAI, or OBI call is introduced.
- History supports confirmed per-conversation deletion. Deleting the active conversation cancels active work, invalidates stale callbacks, removes the local row/messages, and opens a fresh empty advisor chat.


## Advisor verified product cards v0.4

- The model may select products for presentation only by seven-digit OBIK. It is never authoritative for card name, OBIK facts, stock, gross price, product URL, or verification time.
- The final Responses answer is strict structured output with exactly a bounded non-empty `text` and `productObiks` array of at most five seven-digit strings. Duplicate selections are deterministically removed.
- A selected OBIK is rendered only when it matches a locally retained exact store-`075` `LocalProduct` snapshot from the current USER turn. Unknown model selections are ignored and never trigger an extra lookup.
- The existing OpenAI tool payload remains `{obik,name,stock,price}`. `productUrl`, `verifiedAt`, OBI HTML/Nuxt, cookies, diagnostics, and parser internals remain Android-local.
- Successful exact lookups create local-only verified snapshots. Across two tool calls in one USER turn, snapshots are combined by OBIK and the latest exact lookup wins. Snapshot state is discarded between USER turns.
- Room schema v2 adds `message_products` with a CASCADE foreign key to `messages`; v1 upgrades through an explicit migration. Gross price is persisted as decimal text, not floating point.
- Completing an ASSISTANT turn atomically stores its text, selected snapshots, and final `lastResponseId`. Product snapshots belong only to that ASSISTANT message and cascade through existing manual/retention deletion.
- Persisted cards are historical snapshots. Reopening history never refreshes them; current stock/price/availability questions must use the local tool again.
- Advisor product cards reuse the existing verified product-card UI boundary and open only the trusted persisted exact `productUrl`; URLs are never reconstructed from OBIK or model output.
- This iteration does not change OBI parser, transport, exact store-`075` lookup, manual search limit 25, advisor product limit 5, tool count limit 2, history phrase-search semantics, or retry behavior.


## Android localization foundation PL/EN v0.1

- Current Android UI chrome is sourced from Android string resources instead of Kotlin/Compose literals.
- Polish is the default resource set under `values/`; English under `values-en/` must define the same UI string keys.
- Formatting with runtime values uses Android resource placeholders while existing stock/price semantics stay unchanged.
- Controller error state is locale-independent: advisor/search controllers expose stable enums, and Compose maps them to localized strings.
- Saved/manual-search error state stores the stable error enum, not a rendered sentence, so persisted/saved state does not become language-dependent.
- Technical and persisted contracts are not localization targets: USER/ASSISTANT roles, JSON/API/tool identifiers, OBIK/store 075, HTTP details, database schema/values, URLs, OpenAI schema, and diagnostic internal traces remain stable.
- This iteration relies only on Android resource locale selection. In-app Polish/English selection and a Settings UI are explicitly deferred.
- No localization framework or new dependency is introduced.


## Towarownik visual system v0.1 — Warm Modular Utility

- Production UI uses a dark-first warm palette: Background #17110F, Surface #211815, SurfaceRaised #2B201C, SurfaceHighlight #352720, Outline #49362E, TextPrimary #F3E8DE, TextSecondary #BDAA9E, Accent #E58A3F, AccentLight #F2AC68, Success #91A77F, Error #DE7468.
- Amber is semantic rather than decorative: active, selected, verified, and important actions may use it strongly; ordinary surfaces and secondary actions stay visually quiet.
- The Material 3 theme is centralized in Compose and no longer follows automatic light/dark switching or dynamic color. The approved production scope is dark only.
- Surface hierarchy, soft modular rounding, system typography, restrained metadata, and vector action icons define the visual language.
- Verified product cards use a raised warm surface plus subtle amber extraction rail; assistant messages are not amber.
- A dedicated light theme is intentionally deferred instead of being inferred from the dark palette.


## Settings + per-app language selection + diagnostics relocation v0.1

- The conversation drawer has one Settings action pinned below the scrollable/empty history area; opening it preserves the active conversation, draft, and advisor request state.
- Settings is a real top-level state-based Compose surface. Navigation Compose is still unnecessary for the current Advisor / manual search / Settings / Diagnostics graph.
- UI language selection offers exactly `pl` and `en` and uses `AppCompatDelegate.setApplicationLocales(LocaleListCompat...)`; no custom Context wrapper or SharedPreferences language store is introduced.
- `MainActivity` uses `AppCompatActivity`, and the XML host theme becomes the smallest AppCompat-compatible dark theme. The Compose Warm Modular Utility theme remains authoritative for app visuals.
- AppCompat `autoStoreLocales` supplies supported persistence on API 32 and lower. The application manifest declares `android:localeConfig` with only `pl` and `en` for Android 13+ platform per-app language support.
- UI locale is presentation-only. It is not forwarded to the Worker/OpenAI, does not alter advisor request bodies/instructions/context, and never translates or rewrites persisted conversation messages.
- OBI diagnostics moves from the hidden Towarownik-title long press to Settings. Diagnostics behavior, recorder bounds, probe, copy/share, and privacy rules remain unchanged.
- Report problem and Privacy policy are visible but disabled future rows; this iteration deliberately adds no fake report action, privacy URL, or legal content.
- About renders `app_name` and build version data rather than freezing a future public product name.
- No OBI, Room schema, verified-product trust boundary, or proxy behavior changes are part of this settings iteration.


## Problem reporting + assistant response reports v0.1

- Reporting has exactly two initial entry points: one persisted ASSISTANT response or a general Settings report.
- Report categories are stable enums; localized PL/EN labels are presentation only.
- Contextual message identity is the persisted Room message ID carried through the UI/saveable state. Before generation the report resolver re-reads Room, verifies conversation ownership and `ASSISTANT` role, and fails closed if the target disappeared.
- The reported response and its persisted verified-product snapshots are always included. Broader transcript inclusion is explicit opt-in and defaults OFF; assistant context stops at the reported response and never includes later messages or the unsent draft.
- General reports require a short description. Current conversation inclusion is offered only when a persisted active conversation exists and also defaults OFF.
- Product evidence is historical persisted snapshot data only; reporting never refreshes OBI or asks the model for product facts.
- Existing sanitized OBI diagnostic output is optional evidence only if diagnostics were already enabled and populated. Even then it is exposed behind a separate checkbox that defaults OFF and is included only after explicit user opt-in. Reporting never auto-enables diagnostics or runs the live probe.
- Reports are deterministic UTF-8 plain text created on demand under `cacheDir/reports/`; no Room report table/history exists.
- Sharing uses a non-exported, read-granting FileProvider restricted to the report cache subdirectory and the Android system `ACTION_SEND` chooser. If the chooser cannot be launched (`ActivityNotFoundException`), the newly created TXT is deleted and the UI receives a controlled share-unavailable error. Towarownik never sends email automatically.
- The Nepahu Studio recipient is centralized in one reporting constant. Screenshots remain a manual mail/share-client action.
- Sensitive/internal exclusions include OpenAI response/tool IDs, `lastResponseId`, API/app tokens, Authorization headers, cookie values, raw model output/reasoning, OBI HTML/Nuxt, account IDs, IP address, and precise location.
- There is no report backend. Proxy, OBI parser/transport, Room schema, advisor context behavior, and verified-product trust boundary remain unchanged.


## Brand rename: Towarownik → Taksula v0.1

- The public-facing product name is **Taksula**.
- The working brand interpretation is **“ally of the customer advisor” / “sojusznik doradcy”**.
- Brand owner/developer is **Nepahu Studio**.
- Android `applicationId`, namespace, Kotlin/Java package declarations, repository name, database/schema identifiers, persisted technical keys, API/tool identifiers, Worker/service identifiers, signing configuration, and other upgrade-sensitive technical identity remain unchanged intentionally.
- Existing internal symbols such as `TowarownikTheme`, `TowarownikColorTokens`, and `TowarownikSemanticColors` remain unchanged to avoid churn-only refactoring.
- Existing launcher icon PNG assets and their technical filenames remain unchanged; no binary asset is modified.
- The approved **Warm Modular Utility** visual system remains unchanged.
- Google Play public title should be changed manually to **Taksula**; developer remains **Nepahu Studio**. The optional marketing line is **“Sojusznik doradcy”**.


## OBI multi-store v0.1

- Android owns one canonical static allowlist of confirmed OBI Poland three-digit market numbers. Default conversation store remains `075`; arbitrary syntactically valid numbers are rejected before OBI HTTP.
- One conversation has one explicit selected/default store. The UI selector is the only action that mutates this persisted default; chat text does not mutate it, and alternate tool queries never change it.
- A new unsaved conversation may hold a transient store choice without creating an empty Room row; the selected store is persisted with the first USER message.
- Room schema advances v2→v3 by adding `conversations.storeNumber` and `message_products.storeNumber`, both defaulting historical rows to `075`. No destructive migration is allowed.
- Verified snapshot identity is `(storeNumber, obik)`, not OBIK alone. Historical snapshots retain their original store even if the conversation selector changes later.
- The advisor has one generic tool: `find_obi_products(storeNumber, queries[])`. There are no per-store tools or repositories. One batch has one shared store, at most five query groups, and `sum(limit) <= 5`, preserving the maximum of five exact lookups per local call. Android still executes at most three local batches per USER turn; a fourth requested batch does zero OBI work and resolves through `local_tool_limit_reached` before a final continuation without the local tool.
- This historical OBI-only rule was superseded on 2026-10-07 by shared provider-neutral branch/location routing. Android still keeps the immutable conversation branch, but current-turn authorization may now use a uniquely resolved real branch name/address or an explicit supported branch ID; ambiguous/unknown references and model-only branch hints remain unauthorized.
- Rejected store tool calls fail closed before OBI and return bounded `store_not_authorized` continuation data; no silent fallback or fabricated empty result exists.
- The proxy receives the selected conversation store on START/MESSAGE/CONTINUE, validates only exact three-digit syntax, and adds the current store as small dynamic instruction context without receiving the full allowlist.
- Final structured output is `{text, productRefs:[{storeNumber,obik}]}`. Android resolves references only against current-turn verified snapshots; unknown references never trigger a lookup.
- Product cards, reports, and persisted message products include the snapshot store. Current price/stock questions still require fresh verification.
- Store `075` remains the deterministic regression baseline, including the manual live-contract probe. Search discovery remains store-independent; exact verification is store-aware.
- Richer product facts, descriptions/specifications, OpenAI web research, final advisor persona/domain policy, context compaction, other retailers, and location-based store selection remain separate future iterations. AI token/cost telemetry is implemented first as the measured baseline.


## AI usage metrics + budget visibility v0.1

- Keep advisor behavior frozen while measuring it: model remains `gpt-5.6-luna`, reasoning remains low, current instructions/tools/context chain are unchanged.
- Treat OpenAI usage telemetry as optional evidence attached to each successful response, never as a prerequisite for a usable answer.
- Version corrected current pricing as `openai-gpt-5.6-luna-2026-09-27-v2`: USD 0.20/M ordinary input, USD 0.02/M cached input, USD 0.25/M cache-write input, USD 1.20/M output. Above 272,000 input tokens apply 2× to every input-side class and 1.5× to output for the full request. Do not add reasoning tokens separately to output cost, and do not price unknown models or incomplete usage by inference.
- Keep cumulative telemetry local to the installation and grouped by model; do not introduce backend analytics or persist conversation content in usage state.
- Use official NBP USD/PLN only on demand from the usage UI with a 24-hour cache. FX/network failure cannot affect advisor execution.
- A configured “remaining AI budget” is a Taksula-local estimate with a spend baseline, not OpenAI credit/balance. Unknown/unpriced spend makes the remaining estimate unavailable rather than guessed.
- Warn once when the known estimate crosses below USD 1; reset/increasing the budget to at least USD 1 re-arms the warning.
- Measured future sequence: baseline → model comparison/swap PR → richer OBI facts → final advisor instructions/persona → optional OpenAI web_search.


## Advisor model swap to GPT-6 Luna v0.1

- Production advisor model changes from `gpt-5.6-luna` to `gpt-6-luna` only; reasoning remains low and the existing Responses API, instructions, structured output, tool contract, trust boundary, tool-call limit, and `previous_response_id` strategy are unchanged.
- Current GPT-6 Luna pricing version is `openai-gpt-6-luna-2026-09-27-v1`: USD 0.10/M ordinary input, USD 0.01/M cached input, USD 0.125/M cache-write input, and USD 0.50/M output. Above 272,000 input tokens, all input-side rates are doubled and output is multiplied by 1.5 for the full request.
- Existing GPT-5.6 Luna usage remains historical per-model baseline data. Global totals and configured local Taksula budget continue without reset; the latest measured model changes only when measured GPT-6 usage arrives.
- No fallback to GPT-5.6 is introduced. Unknown model pricing remains unpriced rather than inferred.
- Next measured product work remains richer OBI facts → final advisor instructions/persona → optional OpenAI `web_search`.


## Richer verified OBI product facts v0.1

- Add model context only from structured fields proven in the current live decoded OBI product payload; do not infer unsupported fields or scrape rendered page text when structured data exists.
- Implement nullable brand from `brand.name`, bounded normalized description from `productDescription`, and bounded technical facts from `productOverview[]` plus `technicalData.productDetails[]` / `dimensionsAndWeight[]`.
- Do not create a separate `applications` field in this iteration: live proof did not show one stable structured applications section. Uses and explicit limitations stated by OBI remain available through the bounded product description.
- Preserve current stock/price authority and multi-store rules. Rich product facts do not authorize stores, replace selected-store stock/price, or alter composite verified identity.
- Keep rich facts transient and model-facing. Do not migrate Room or expand visible product cards/reports merely to persist advisor-only context.
- Preserve the existing continuation transport budget with bounds: brand 80, description 220, at most six facts, label 60, value 100.
- Minimal instruction change only: OBI-supplied rich facts are verified product-page facts; absent properties remain unknown, not false.
- Next stages remain final Taksula advisor instructions/persona, then optional OpenAI `web_search`.


## Final Taksula advisor behavior v0.1

- Replace the temporary generic retail assistant instructions with the final Taksula role: a concise practical product/technical advisor for home-improvement retail staff.
- Do not over-block ordinary technical knowledge. Model knowledge may answer general installation, product-type, material-compatibility, tool-selection, and troubleshooting questions without a verified OBI source.
- Apply strict verification only to specific SKU/current-store claims. `find_obi_products` facts are authoritative for OBIK, name, store, stock, price, and supplied product-page facts; missing SKU-specific dimensions/materials/compatibility/certifications/applications/parameters/limitations must not be invented.
- Fresh current stock/price verification remains mandatory when relevant, and historical conversation values remain non-authoritative for current store facts.
- Use richer OBI facts selectively for the question; do not dump technicalFacts or repeat marketing copy. Unknown means unknown.
- Clearly unrelated general chat gets a brief redirect toward product/technical retail support rather than a verbose refusal. Borderline practical home-improvement topics remain in scope.
- Reply naturally in the user's conversation language where practical; no Android locale or persisted-history translation architecture changes.
- Keep `gpt-6-luna`, low reasoning, one `find_obi_products` tool, structured final output, productRefs trust boundary, tool/product limits, multi-store rules, persistence, UI, parser, and pricing unchanged.
- Do not enable `web_search` or any OpenAI built-in tool here. Next milestone: selective OpenAI `web_search`.


## Retail-advisor mindset + task-oriented OBI tool use v0.2

- Keep the existing multi-query `find_obi_products(storeNumber, queries[])` contract and Android hard guard `MAX_LOCAL_TOOL_CALLS_PER_TURN = 3`; the guard remains infrastructure safety, not the model's retail-planning budget.
- Remove explicit "you have at most 2 calls" / "use the second call" reasoning from normal advisor instructions. The model should use verified OBI lookup whenever current assortment, stock, price, store availability, or concrete selection is useful, batch related categories aggressively, prefer one well-planned batch, and avoid skipping necessary verification merely to conserve a call.
- For job/goal and explicit complete-kit intent, identify a small practical category set, separate essentials from optional convenience items, proactively verify the important categories, and explain briefly what selected items are for. Do not require one user confirmation per category and do not generate exhaustive shopping lists.
- For a single product/category request, answer/select that item first. Useful complements may be offered briefly, but do not search them until the user asks unless the original request already asks for a complete kit. Accepted complements should be batched.
- Before selecting a concrete SKU, treat a missing materially relevant parameter as ambiguity: ask one targeted clarification rather than guessing. Treat assortment/browse wording separately by returning multiple verified variants where useful. When several verified products materially fit, do not silently collapse them to one; show/compare alternatives or briefly justify the chosen one.
- The advisor tool result is bounded to at most five verified products per batch. For "what variants/sizes exist" browse questions, never present that bounded subset as exhaustive unless completeness is actually verified; prefer example/among-others wording when completeness is unknown.
- Direct current price/stock questions stay direct and do not trigger routine cross-sell.
- Preserve distinct evidence semantics: stock `0` = confirmed unavailable in that verified store; null stock = unknown; `not_found` = no verified match; `unavailable` = retrieval could not establish the fact. Never collapse unknown/failure into "brak".
- When a requested verified item has stock zero or an exact requested item is not found, Taksula may verify a reasonable substitute in the current store and may offer another-market checking. Historical exact-number-only authorization was superseded on 2026-10-07 by the shared deterministic branch/location resolver; Android still never invents a branch or availability.
- Keep `local_tool_limit_reached` graceful if the Android hard guard is reached. Preserve grouped results, partial group failures, continuation byte budget, web search, current-turn `productRefs` trust, and composite `(storeNumber, obik)` identity.
- Do not add cross-market scanning, distance logic, a new availability tool, parser/manual-search/Room changes, RAG, or other retailer integrations in this iteration.


## Advisor Product Contract v1 alignment

- This section records the original Product Contract v1 alignment milestone; its old clarification-before-lookup wording is superseded by the 2026-10-08 shared-advisor decision above and the current `docs/advisor-product-contract-v1.md`.
- Current production remains advisor-first and uses normal technical/sales knowledge when provider evidence is unnecessary.
- Product selection now uses the shared three-state model: unknown category clarifies without lookup; known category with a decision-critical variant unresolved performs safe same-turn candidate browse while clarification/resolution remains outstanding; sufficiently specified product intent verifies immediately.
- Safe State-2 browsing must never be used to infer the missing parameter and must never promote a candidate to a confirmed compatible/correct recommendation before that parameter is resolved.
- Job/project/"what do I need?" requests are understood first and receive practical essentials-first advice without automatic provider lookup once sufficiently clear.
- Explicit assortment/browse, direct current provider facts/identifiers, sufficiently specified concrete product intent, and sufficiently specified verified kits use the active provider immediately. Provider semantics remain in their appendices rather than being flattened into OBI-only wording.
- Preserve the existing model, reasoning effort, multi-query protocols, Android hard guard, current-turn grounding, branch authorization, bounded-result wording, availability semantics, selective web search, pricing, persistence, parsers, and UI boundaries.


## Selective web search for Taksula v0.1

- Enable only the current Responses built-in `web_search`, never `web_search_preview`. Use automatic tool choice and one built-in call per Responses request; do not force browsing on ordinary technical questions.
- Preserve the protocol-appropriate application-function contract and Android three-local-call USER-turn safety limit independently of the built-in-tool limit.
- Search may fill an important missing SKU-specific technical fact, satisfy an explicit relevant online/current-information request, or verify inherently current non-OBI information. Broader optional research should not browse reflexively.
- Local Android provider verification remains absolute authority for current selected-branch stock, price/price scope, availability, and card/productRef eligibility. Web evidence cannot create trusted product cards; OBI and KWANT retain their provider-specific semantics.
- Prefer manufacturer product pages/manuals/datasheets for SKU specifications, then authoritative industry/specialist sources; retailer evidence is secondary and community content is experience/opinion. Do not silently hide meaningful source conflicts.
- Treat searched pages as untrusted data. Their instructions never override Taksula's role, tool rules, trust hierarchy, or privacy boundaries.
- Preserve citations only from actual Responses `url_citation` annotations. Normalize max six unique HTTPS sources (title <=200, URL <=2048), never model-authored plain-text URLs, raw web results, queries, or search metadata.
- Preserve annotation spans only when the real OpenAI start/end offsets can be mapped exactly through the structured JSON `text` string and Android display normalization. Never infer a position. Persist nullable spans with dedicated Room v4 `message_sources` rows that cascade with messages; no generic source/knowledge framework is introduced.
- Render safely mapped citations as small clickable inline numbered markers and keep the compact clickable source list as fallback for unmappable sources and navigation.
- Pricing version becomes `openai-gpt-6-luna-2026-09-28-web-v1`; add USD 0.01 per completed search action to existing token cost. Completed search actions are counted directly from Responses output independently of optional token usage. Availability alone is zero search calls; missing/malformed or otherwise unpriceable usage preserves the search count while cost remains a lower bound.
- This completes the currently planned AI capability stage. No deep research, streaming, background mode, fallback routing, RAG, MCP, file/image search, another retailer, or autonomous agent framework is introduced.

## Advisor transport protocol versioning v0.1

- Android explicitly sends `protocolVersion: 2` on advisor START, MESSAGE, and CONTINUE requests. Protocol v2 is the current grouped `find_obi_products(storeNumber, queries[])` transport.
- The Worker keeps an explicit protocol v1 branch for the old single-query `{query,storeNumber,limit}` tool/continuation contract. That branch is selected only by an explicit `protocolVersion: 1`.
- Missing `protocolVersion` is intentionally treated as v2, not v1. A deployed grouped Android build already existed before protocol markers were introduced, so both the old single-query generation and the grouped generation can be unversioned and cannot be distinguished safely from the request shape before a tool response is emitted.
- Therefore the Worker must never guess v1 from an absent marker. Unversioned grouped clients remain operational during rollout; pre-versioned single-query clients must update.
- Unsupported future protocol versions fail explicitly before OpenAI work. Protocol diagnostics may record only protocol version, endpoint stage, response-envelope type, and bounded validation category; no user/tool/product content, secrets, raw request bodies, or upstream payloads.
- Future incompatible transport changes require a new explicit protocol version and a rollout plan that preserves the frozen unversioned-v2 compatibility alias instead of changing its meaning.



## Advisor broad-search “Zobacz więcej” v0.1

- Preserve OBI `reportedTotalCount` on the Android advisor search path while keeping the advisor candidate/result-card limit at five.
- For a successful advisor query with `limit > 1`, create Android-only `AdvisorSearchAction(query,storeNumber,reportedTotalCount)` metadata only when the reported count exceeds the bounded candidates requested/returned. This metadata is separate from verified product snapshots and is never sent through the Worker continuation protocol.
- Deduplicate eligible actions within the current USER turn by store + normalized query. Persist final actions with the ASSISTANT message in Room v5 `message_search_actions`; historical messages migrate with zero actions.
- Render the count only as an OBI-reported search-result count. UI wording must not imply store stock, availability, completeness, or that all reported results were loaded.
- “Zobacz więcej (N)” opens the existing manual OBI search with the exact persisted query and store and automatically starts the existing `ManualSearchController` path. A separate manual-search store context prevents a historical action from mutating the conversation's selected store; normal top-bar manual search still starts from the conversation store.
- Manual search remains capped at 25 parsed candidates and existing five-item reveal/enrichment. No OBI pagination, advisor protocol/version change, Worker validation change, prompt/orchestration change, productRef change, or manual-screen redesign is introduced.


## Trusted OBI primary thumbnails v0.1

- Keep product imagery strictly Android-local presentation metadata. Do not add image URLs to `AdvisorVerifiedProduct`, Worker continuations, productRefs, or prompts/model context.
- Use the already-fetched product page only. Primary image extraction reads the first structured Product JSON-LD `image` value and fails soft when absent or malformed; do not add an image-specific OBI request or parse search-result thumbnails.
- Trust only HTTPS URLs on the confirmed product-image host `bilder.obi.pl`. Keep only one primary image, not the gallery. Unrelated page images such as energy labels, banners, recommendations, and heyOBI assets are out of scope.
- Persist the local image URL with verified assistant product snapshots. Room v5→v6 adds nullable `message_products.imageUrl`; historical rows remain valid with NULL.
- Reuse the same exact lookup/enrichment for manual-search thumbnails. Preserve the 25-candidate cap, five-at-a-time reveal, search-more action behavior, advisor five-product limit, orchestration, protocol, and OBI search/parser boundaries.
- Render thumbnails with standard Coil 3 Compose + OkHttp networking, bounded `ContentScale.Fit`, and fail-soft presentation-only error handling.

## Neutral ProductProvider foundation v0.1

- Introduce provider-owned identity with `ProviderId`, opaque `BranchId`, `WorkingProfile(providerId, branchId)`, and `ProductRef(providerId, productId)`.
- Define the existing OBI Poland provider ID as `obi-pl`; the current default working profile remains `obi-pl` + branch `075`.
- Keep `ProductProvider` deliberately small: only the existing bounded search and exact branch lookup operations are part of the neutral contract.
- Implement `ObiProductProvider` as an adapter over the existing OBI search/lookup repositories. Preserve OBIK/EAN/text search, exact OBIK lookup, store propagation, stock, price, trusted URL/image, and existing failure behavior without rewriting OBI parsers or HTTP code.
- Resolve providers through a small `ProviderId -> ProductProvider` registry. Unknown provider IDs fail explicitly; there is no implicit OBI fallback.
- Do not add KWANT, capability matrices, branch directories, UI/settings changes, advisor-tool changes, Worker/prompt changes, persistence migrations, or a wholesale rename of current OBI-specific models in this foundation PR.
- Existing OBI-specific call sites remain intentionally coupled until the next provider integration can migrate them incrementally against a real second implementation.



## KWANT ProductProvider v0.1

- Add the second production provider ID `kwant-pl`; the production registry now contains `obi-pl` and `kwant-pl`.
- Use KWANT's public numeric product ID as `ProductRef.productId`; public URL slugs remain provider transport data rather than neutral identity.
- Use the public `department_stock_id` as KWANT `BranchId`. Resolve branch metadata from the public branch directory and construct `departmentCookie` deterministically from the four proven fields: `department_stock_id`, `department_stock_name`, `department_stock_postcode`, and `department_stock_street`.
- Keep KWANT production transport HTTP-only. No browser automation or Playwright is part of Android production code.
- Preserve selected-branch stock exactly: an observed zero is `0`; missing/unparseable branch stock is unknown (`null`).
- Extend neutral price semantics only with proven scope: `BRANCH` and `ONLINE`. OBI keeps its current branch-price meaning; KWANT's public storefront price is `ONLINE` and must not be described as a counter or customer-specific branch price.
- Invalid KWANT branch IDs fail explicitly and never substitute another branch.
- Exact KWANT lookup must work on a fresh provider instance. The stable numeric product ID is sent through the public dynamic `/produkt/<id>` route; a previous search/cache is optional only. Numeric-ID search is not used because research only confirmed article/catalog, EAN, and text search. This numeric route is live-confirmed by `tools/kwant_live_contract_probe.py`: on 2026-10-03, `GET /produkt/580` returned 308 to `/produkt/wylacznik-nadpradowy-b16-a-1p-6ka-mbn116e-hager-580`, then 200, and the final public `__NEXT_DATA__` product ID was `580`.
- KWANT search leaves the neutral reported total unknown unless an authoritative frontend total is evidenced; the number of parsed candidates is not promoted to a total.
- Validate KWANT request/response URLs using parsed scheme/host/path rules, not string-prefix checks.
- No UI, conversation/profile persistence, advisor tools, Worker, automatic provider switching, natural-language branch resolution, or database migration is included.


## Persistent WorkingProfile selection

- Persist one global `WorkingProfile(providerId, branchId)` using app preferences; existing/default state is `obi-pl / 075`.
- Expose explicit user selection for OBI or KWANT and one branch owned by that provider. Technical provider IDs are not shown in UI.
- Treat a saved conversation's working profile as conversation-owned state. A new conversation captures the current global profile at first USER message; later global profile changes do not rewrite it.
- Room v7 migrates historical conversations to provider `obi-pl` and preserves their previous OBI store number as `branchId`; no destructive migration is allowed.
- Manual search follows the global working profile and resolves the exact `ProductProvider`; unknown providers or branches fail explicitly and never fall back to another provider/branch.
- KWANT branch choices come from its public directory and use public `department_stock_id`; no single-branch hardcode or natural-language resolver is introduced.
- Keep the existing advisor integration OBI-specific for this PR. The model does not choose or switch providers automatically.


## Provider-aware advisor contract

- Keep OBI advisor transport on protocol v2 until a separate migration is justified.
- Introduce protocol v3 only for provider-aware conversations that need it.
- Protocol v3 uses `find_products(providerId, branchId, queries)`; do not add a parallel provider-specific tool such as `find_kwant_products`.
- Android validates the requested provider/branch against the conversation WorkingProfile before executing the local tool.
- Do not automatically switch providers or branches from model output.
- Persist provider-owned product identity for advisor cards so reopening a conversation does not reinterpret KWANT products as OBI.
- Do not alter shipped Room migration 6→7. Provider-owned message-product identity belongs to schema v8 and explicit migration 7→8; historical rows map to `obi-pl + obik + storeNumber`.
- Keep OBI authorization and prompt/tool contract on their proven v2 path. Protocol v3 has explicit provider-aware instructions and must not be synthesized by string-replacing OBI instructions.

## Advisor product intent and exact identifier routing

- Concrete-product intent is sufficient permission to use the conversation's current provider: a clear request for a product, recommendation, assortment option, current stock/price, or concrete identifier must not require a second “check in the market” turn.
- Preserve advice-first behavior for general technical questions and one concise clarification when a genuinely decision-critical parameter would change selection, compatibility, or safety.
- In the OBI v2 path, an exact seven-digit OBIK bypasses text search and goes directly to the existing exact store lookup. Other OBI text/identifier inputs keep search followed by exact verification.
- In the KWANT advisor path, article/catalog number, EAN, product name, manufacturer text, and other user-facing identifiers always continue through search followed by exact product verification. The advisor contract does not expose KWANT's internal exact `productId` capability, so the model cannot invent an ID and bypass discovery.
- Resolve and validate KWANT branch metadata once per local tool execution and reuse its department cookie for all exact candidate verification in that batch. This execution-scoped lookup context is not a cross-turn or broad metadata cache.

## Advisor attachment foundation v1

- A USER message may eventually contain text, one attachment, or both, but the current text-only send validation and transport remain unchanged in this foundation.
- Support exactly one provider-neutral `IMAGE` or `PDF` attachment per USER message. The Room v10 `message_attachments.messageId` primary key enforces the one-per-message cardinality; unsupported or malformed metadata is rejected rather than reinterpreted.
- Persist only bounded metadata in Room and keep bytes in a dedicated app-private files subdirectory under an opaque identifier. Domain and Room models contain no BLOB, Base64, provider/OpenAI fields, or user-facing absolute path.
- Attachment-file lifetime follows explicit conversation deletion, retention cleanup, and interrupted-turn recovery. Interrupted recovery captures only the trailing USER message's attachment identifier before Room removes the message, then best-effort deletes exactly that file after the transaction succeeds; filesystem failure cannot block or corrupt draft recovery and cleanup never scans unrelated storage.
- `AttachmentStorage` has a temporary v1 local private-storage ceiling of 16 MiB per attachment. Oversized declared metadata is rejected before opening/copying the source; copied-byte equality remains mandatory. This is an Android storage guard, not a Worker/OpenAI API or transport limit.
- Camera, photo/file picker UI, compression, EXIF processing, PDF preprocessing, upload, Worker/OpenAI protocol changes, and attachment-only sending remain follow-up work.

## 2026-10-04 — Android attachment acquisition and pending composer

- Approved a compact `+` inside the advisor composer with Camera, Photos, File, then an existing WorkingProfile selector shortcut.
- Chose Android Photo Picker through `PickVisualMedia`, system camera through `TakePicture` and a dedicated temporary FileProvider cache path, and system Documents UI through `OpenDocument` limited to PDF/images; no storage or camera runtime permission is declared.
- Chose one pending private attachment and a 4096 px image dimension bound. Images are orientation-normalized and re-encoded at OCR-friendly JPEG quality 92 (or PNG for meaningful alpha); PDFs are not transformed. The existing 16 MiB local ceiling remains authoritative.
- Enter is newline-only and the external send button remains the sole submit action.
- Attachment submission is deliberately disabled and guarded from the current text-only controller until PR #3 supplies end-to-end multimodal transport.

## 2026-10-05 — protocol v4 multimodal transport

- Use multipart only when one private IMAGE/PDF attachment accompanies `/start` or `/message`; preserve JSON for text-only traffic and `/continue`.
- Carry attachment bytes inline to Responses as a validated data URL rather than introducing Files API or persistent proxy storage.
- Treat attachment-derived text as user content and retain local provider verification for current commercial facts; generic technical photos do not independently trigger provider search.

## 2026-10-05 — attachment turn ownership completion

- Accept a USER turn when it contains non-blank text, one attachment, or both; reject only a turn containing neither. Attachment-only conversations derive their title from the attachment display name and persist blank message text without synthetic `[IMAGE]` or `[PDF]` content.
- Keep the pending-owner marker through the atomic Room USER-message insert. Attempt synchronous marker release only after the insert succeeds, never delete the private file during the handoff, and treat release failure as non-fatal because Room already owns the attachment.
- Preserve overlapping pending/Room ownership across the narrow interruption window rather than risking an unowned file. Startup reconciliation recognizes Room ownership and clears stale pending state without deleting the persisted file.

## 2026-10-05 — sent attachment rendering and import isolation

- Render persisted USER images and PDFs from their existing app-private attachment files; keep image media above optional user text and show PDF filename plus human-readable size without synthetic `[IMAGE]`/`[PDF]` text.
- Treat a missing, size-mismatched, or undecodable persisted attachment as unavailable presentation state. Never expose private paths, local IDs, URIs, or attachment contents in that fallback or problem-report diagnostics.
- Serialize attachment imports and guard their UI publication with a monotonically changing generation. New selection, remove, new conversation, and conversation switch invalidate older generations; stale successful imports are deleted rather than attached to the active chat.
- Disable send and show explicit composer loading only while the current attachment generation is importing/preprocessing. Keep one attachment maximum, multiline Enter behavior, dedicated-send-only submission, and the existing pending ownership/storage/protocol v4 contracts.
- Keep the 4096 px image output policy but sample source bitmap decode directly toward that bound to reduce obvious peak-memory risk without redesigning preprocessing.

## 2026-10-07 — advisor tool continuation protocol continuity

- Preserve one explicit transport contract for the full active tool-assisted turn rather than choosing `/continue` from provider identity alone.
- Text-only OBI remains protocol v2 + `find_obi_products`; text-only provider/KWANT remains v3 + `find_products`; attachment START/MESSAGE and every resulting continuation remain provider-aware v4 + `find_products`.
- OBI attachment turns still execute the existing Android `FindObiProductsTool`; only the continuation serialization is provider-aware v4. BranchResolver, WorkingProfile, product retrieval, persistence, prompts, and protocol version numbers are unchanged.


## 2026-10-07 — safe advisor upstream failure diagnostics

- Keep every advisor upstream failure externally collapsed to HTTP 502 with `{"error":"upstream_failure"}`.
- Classify failures only where their cause is known: fetch/network, upstream HTTP class, JSON parsing, Responses envelope, function-call contract, or structured final-answer contract.
- Reuse the existing `advisor_protocol` Cloudflare log and add only the internal upstream category plus upstream HTTP status. Do not log user/model/tool/product/attachment content, identifiers, credentials, tokens, cookies, or raw upstream bodies.
- Diagnostics are observational only: no retry, persistence, analytics provider, Android/UI change, protocol/version change, or advisor behavior change.


## 2026-10-08 — Advisor observability B2.1

- Keep Cloudflare structured logs as the only storage/inspection surface for Advisor observability in B2.1; do not add Analytics Engine, KV, D1, R2, a database, or a dashboard.
- Keep one structured `advisor_protocol` event per protected Advisor HTTP request. The event describes only observable transport/runtime behavior: protocol/provider/branch/stage, answer vs local-tool request, bounded query/result/product counts, web-search/source counts, normalized usage/cost when present, total Worker latency, and safe validation/upstream failure categories.
- Start a new opaque `X-Taksula-Trace-Id` for every START/MESSAGE user turn. CONTINUE reuses a syntactically valid inbound trace header and otherwise creates a new one without rejecting the request. A separate internal requestId distinguishes individual HTTP requests inside one turn trace.
- Do not place traceId in Advisor JSON bodies and do not change any existing request/response, tool, productRef, attachment, prompt, model, reasoning, or protocol contract.
- Logs must never contain chain-of-thought, user/model text, local or web query text, product content or identifiers, stock/price values, attachment filenames/content, response/call chain IDs, raw upstream bodies, authorization material, or secrets. ProviderId and branch/store ID are allowed operational metadata.
- CONTINUE logs summarize the already-validated local result only through safe aggregate counts and rejection category; individual products are never logged.
- Android trace propagation/reporting is intentionally deferred to B2.2.


## 2026-10-09 — Android Advisor trace correlation B2.2

- Android treats `X-Taksula-Trace-Id` as optional opaque operational metadata outside the Advisor JSON protocol. Only canonical UUID-form values are accepted; malformed or missing trace headers fail soft to null.
- START and MESSAGE never send a prior turn trace. The first valid Worker response establishes the current USER-turn trace; every subsequent CONTINUE sends it, and a later valid Worker trace becomes authoritative without allowing a missing header to erase an already-established trace.
- The final successful turn trace is persisted atomically with the ASSISTANT message in nullable `messages.advisorTraceId`. Room moves from schema 10 to 11 through a non-destructive 10→11 migration; historical USER/ASSISTANT rows remain valid with null trace.
- Existing assistant-response problem reports include the persisted trace correlation even when full conversation context is excluded. Advisor failure diagnostics retain a valid Worker trace or, for a later network failure, the best already-established turn trace where available.
- No Worker requestId, raw headers, auth material, OpenAI response/call IDs, new telemetry store, dashboard, diagnostics screen, or user-visible chat trace is introduced.
