# Decisions

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
- The current cost-sensitive model is centralized as `gpt-5.6-luna` with low reasoning effort and a bounded output budget. This model choice may change after real assistant evaluations.
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
- `MAX_LOCAL_TOOL_CALLS_PER_TURN = 2`. Every USER message starts with a fresh allowance; the product limit remains 5. Manual human search remains independently bounded at 25 parsed candidates.
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
- The advisor has one generic tool: `find_obi_products(query, storeNumber, limit)`. There are no per-store tools or repositories. Existing limits stay at five products per call and two local tool calls per USER turn.
- Android captures an immutable turn-store snapshot and exact supported three-digit store tokens literally present in the current USER message. A tool store is authorized only when it equals the conversation store or is both allowlisted and literally present in that current message. Previous turns, city/region/store names, unsupported numbers, and digits embedded in longer numbers do not authorize it.
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
