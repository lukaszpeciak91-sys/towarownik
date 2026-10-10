# Progress

## PR #102 — 2026-10-10 focused location authorization hardening

Scope authorization hardened: focused explicit user inventory-intent gate before any location network access; unknown city without model hints rejected; store ID extraction requires explicit market context; partial 61-market headings state all-other scope and incomplete results; 45-second total bounded OBI scan with injectable fast deterministic timeout test and verified partial-result preservation. No WorkingProfile write, discovery regression or KWANT contract change.


## 2026-10-10 — PR 2 opt-in find_product_locations

The Android Advisor orchestration now supports one typed, provider-neutral location inventory call with current-conversation verified-product authorization and OBI current-user canonical location authorization. The Worker advertises it only when the new Android client sends `X-Taksula-Locations-Capability: 1`; legacy v1 and old v2–v5 clients continue with their original discovery tools, which enables Worker-first deployment. The location call shares the existing three-call budget and does not run automatically for ordinary product search. Recent persisted assistant product cards may authorize product identity but do not auto-generate new product cards or override live stock evidence. For explicit "where else" follow-ups, the Advisor derives the full canonical OBI directory scope (61 other markets excluding the selected market) and orchestrates at most four sequential service reads (20 IDs/read, 10 IDs/HTTP request, seven HTTP reads total). Explicit user cities/markets restrict the scope; Kraków expands to its four canonical stores, while Tarnów is not a canonical OBI market. Each missing market is returned with unknown/null stock, full checked IDs and partial coverage instead of a fabricated zero. This is still **one** logical Advisor local-tool call. The per-service PR #101 budgets remain intact and no ordinary search gains new location requests. KWANT returns `UNVERIFIED_REQUEST_CONTRACT` without transport until the independently observed `extended` query VALUE can be recovered; two-provider live readiness is not claimed. PR is unmerged until independently audited.


## 2026-10-10 — Product locations transport PR 1

Read-only Android internal `ProductLocationsService` now dispatches to isolated OBI and KWANT adapters, independently of current Advisor and manual search. OBI canonical requested subsets use max-10 market batches and max-20 logical budget, strict JSON trust checks and partial-coverage semantics. KWANT one-shot 21-branch parser and transport are implemented behind a **fail-closed missing-`extended` contract gate**: original live research redacted the query value, and production does **not** send unverified requests. Until an exact reproducible value is established, do not claim production-ready two-provider support. No existing normal-turn network cost changes. Dedicated offline regression tests cover quantities, product/store IDs, request budgets and malformed/partial responses; only final checks/CI can establish their pass status. Future PR 2 will connect the service to Advisor/Worker with trusted references.


## Current phase

Multi-attachment phase 2: Android composer, Room v12 ordered relations and 11→12 migration, multi-picker/camera, private-file ownership reconciliation, per-file remove/replace, 24 MiB total guard and failed-turn restoration. All attachment turns select v5; text-only behavior remains unchanged.


Multi-attachment transport phase 1: opt-in provider protocol v5 supports 1–3 JPEG/PNG/PDF parts for START/MESSAGE, with a 16 MiB per-file bound and 24 MiB aggregate raw-file ceiling (plus 16 KiB multipart overhead), stable Responses input order, Android transport list overloads, and unchanged JSON-only continuation. Existing single-file UI/v4, v2/v3, Room and ownership remain unchanged; UI multi-selection is deliberately deferred.


**Final Advisor contract validation + release readiness**

Observability follow-up: Worker-side advisor upstream failures now retain the same public 502/upstream_failure response while Cloudflare logs distinguish safe structural failure categories and optional upstream HTTP status without payloads, content, identifiers, filenames, or secrets.

Protocol-continuity blocker follow-up: tool-assisted advisor turns now retain the transport family selected by their initiating START/MESSAGE, so an OBI attachment turn cannot drop from provider-aware v4 `find_products` to legacy v2 during `/continue`. OBI local retrieval remains `FindObiProductsTool`; text-only OBI v2 and KWANT v3 remain unchanged.

Current branch/location integration follow-up: prompt behavior now follows the shared Android BranchResolver authorization model, branch selector labels retain real IDs alongside names/addresses, explicit only-confirmed-local-stock intent requires selected-branch stock > 0, and unsupported internal citation/entity tokens are removed at the proxy normalization boundary while real URL citation annotations remain authoritative.

PR #17 established the production-shaped chat shell and bounded human manual OBI browsing. PR #18 added real local conversation state, multi-turn continuation, retention, and deletion. PR #19 added persistent app-owned verified product cards. Localization, Warm Modular Utility, Settings, PL/EN selection, diagnostics/reporting, the Taksula rename, provider-aware WorkingProfile, OBI + KWANT integration, usage/cost measurement, GPT-6 Luna, richer verified product facts, selective web search, branch routing, attachments, shared Advisor policy, behavioral evals, and B2 observability are complete. The current release gate is the final real-model behavioral run on both providers followed by final Play AAB validation.

Implemented direction:

- Room schema v1 for conversations/messages;
- real drawer history sorted newest-updated first;
- fully local phrase search across title plus USER/ASSISTANT text;
- first USER send creates the conversation and local bounded title;
- completed messages, timestamps, drafts, and final response ID survive database recreation;
- recent useful conversation restores on app restart where practical;
- composer re-enables after a completed answer for real follow-up turns;
- first turn uses `/v1/agent/start`;
- follow-up turns use authenticated `/v1/agent/message` with the stored final `lastResponseId`;
- tool responses still use `/v1/agent/continue`;
- only the final answer response ID replaces the persisted conversation context;
- local tool allowance is three calls per USER turn and resets on every new USER message;
- interrupted USER-only tails recover to editable draft without automatic resend;
- switching/new conversation cancels active work and stale callbacks are rejected;
- current provider facts continue to be refreshed through the active Android local provider tool;
- conversations are treated as individual customer cases, with “Nowa rozmowa” recommended for a new customer/problem;
- startup-only local retention removes conversations strictly older than 30 days by `updatedAt`;
- the exact 30-day cutoff remains retained and message rows cascade on deletion;
- each history row can be deleted manually after explicit confirmation;
- deleting the active conversation cancels its request, invalidates stale callbacks, and opens a fresh empty chat;
- final model answers use strict structured `{text, productRefs}` output with at most five selected verified references; OBI v2 uses `{storeNumber,obik}` while provider-aware v3/v4 uses `{providerId,branchId,productId}`;
- exact `LocalProduct` lookups retain local-only snapshots containing trusted URL and verification time while OpenAI still receives only OBIK/name/stock/price;
- product selection resolves only against verified snapshots from the current USER turn under provider-owned composite identity; OBI retains `(storeNumber, obik)` while provider-aware paths use provider/branch/product identity;
- Room schema v3 persists each conversation store and each message-product store; v2→v3 deterministically assigns historical rows to `075`;
- ASSISTANT text, selected snapshots, and final response ID commit atomically;
- historical cards reopen without network access and display their verification timestamp;
- the existing `VerifiedProductCard` boundary is shared by manual search and advisor history;
- current Android UI text is moved to the Polish/default `values/strings.xml` resource set;
- a complete matching English `values-en/strings.xml` resource set is provided;
- Compose resolves user-visible chrome through Android string resources and formatted placeholders;
- advisor/search errors use stable language-neutral enum state and are translated only in UI;
- saved manual-search errors no longer persist rendered language-dependent sentences;
- Android UI uses the complete PL/EN resource sets and Settings controls the supported per-app locale through AppCompat;
- production UI uses the approved warm dark palette and centralized semantic Compose colors;
- automatic Material light/dark switching and dynamic-color-style defaults are replaced by the approved dark-first scheme;
- top bars, conversation drawer, chat bubbles, persistent composer, progress/error states, verified product card, and manual OBI search share one visual hierarchy;
- strong amber is reserved for primary/verified/active emphasis rather than ordinary buttons and cards;
- character/emoji action controls are replaced by local vector drawable icons with accessible descriptions;
- no launcher PNG or other binary asset is created or modified by the visual-system work;
- Settings is pinned to the bottom of the conversation drawer independently of history length/search state;
- Settings exposes exactly Polski / English through AndroidX AppCompat per-app locales;
- AppCompat auto-stores the selected locale below API 33, while Android 13+ uses the platform per-app locale path with declared `pl` and `en` support;
- switching UI locale recreates the normal Android UI but does not send locale to the proxy/model or translate persisted conversation text;
- diagnostics is reached through Settings and the old hidden title long-press entry is removed;
- Diagnostics returns to Settings, while Settings returns to Advisor;
- Report problem and Privacy policy are visible disabled future rows with no fake destination/content;
- About uses `app_name` plus the current BuildConfig version, keeping later naming work cheap;
- each persisted ASSISTANT response has a quiet contextual Report/Zgłoś action backed by its persisted message ID;
- Settings Report problem is now active while Privacy policy remains future work;
- assistant/general report categories use stable technical enums with localized labels;
- transcript inclusion defaults OFF and is always user-controlled;
- assistant reports always include the exact persisted response and its persisted product snapshots, while optional context stops at that response;
- general reports require a description and may include the current persisted conversation only after opt-in;
- unsent drafts are excluded from report evidence;
- existing sanitized OBI diagnostics are offered only when already enabled and populated, through a separate checkbox that defaults OFF; reporting never starts diagnostics/probes/network work;
- reports are temporary UTF-8 TXT files under `cacheDir/reports/` and are not stored in Room;
- a non-exported FileProvider exposes only the report cache path and Android ACTION_SEND opens the system chooser with the centralized Nepahu Studio recipient;
- Taksula never sends the report automatically; chooser launch failure is handled with a bounded UI error and deletion of the fresh TXT; screenshots are added manually in the chosen mail/share client;
- report contents exclude OpenAI/tool IDs, response-chain IDs, secrets/auth headers, cookie values, raw model data, OBI HTML/Nuxt, account/network/location identifiers.
- public-facing product branding is **Taksula** in Android resources, diagnostics/report labels, TXT headings, and current product documentation;
- package/application ID `pl.lukaszpeciak.towarownik`, repository name, Worker/service/token identifiers, database/persisted contracts, internal theme symbols, and launcher asset filenames remain intentionally unchanged;
- launcher icon artwork and Warm Modular Utility styling are unchanged; no binary asset is modified.
- one persistent WorkingProfile selects the provider + branch for new conversations (default `obi-pl / 075`), while saved conversations keep their own profile;
- the advisor top bar exposes the provider/branch context and one-off cross-branch requests never mutate that persisted conversation profile;
- direct OBIK and selected EAN/text candidates exact-verify against the active selected store while search discovery remains store-independent;
- local Advisor tooling is protocol-shaped: text-only OBI uses v2 `find_obi_products(storeNumber, queries[])`; provider-aware text uses v3 `find_products(...)`; attachment turns use v5 `find_products(...)` (legacy v4 still accepted). Grouped calls remain bounded to at most five query groups and five requested products in total;
- customer-kit requests can verify several categories in one local call, with grouped `verified` / `not_found` / `unavailable` results and successful groups retained independently;
- the per-USER-turn ceiling is three local provider calls; a fourth requested batch performs zero provider work, returns `local_tool_limit_reached`, and forces the final continuation to proceed without another local function call;
- Android's shared `BranchResolver` authorizes one-off branch changes from only the CURRENT USER message against the active provider's real branch directory; exact IDs remain valid, natural metadata requires explicit branch/location intent, and ambiguous/unknown references fail closed;
- START/MESSAGE carry the conversation provider/branch context and the initiating transport family is preserved through CONTINUE; OBI text remains v2, provider-aware text v3, attachments v5, and unversioned Worker requests remain pinned to grouped v2 compatibility;
- unsupported/unauthorized branch or store requests fail closed before provider retrieval and never substitute a default branch;
- verified cards/history/reports preserve their own snapshot store and changing the conversation selector never rewrites historical facts;
- store `075` remains the deterministic regression/live-probe baseline.
- broad advisor searches now preserve OBI's reported search-result count locally; when that count exceeds a multi-result bounded advisor subset, the assistant message can persist a deduped “Zobacz więcej (N)” action without changing the five-card/productRef boundary;
- Room schema v5 adds `message_search_actions` for exact query/store/reported-count metadata, and the existing manual search can reopen that historical query/store without mutating the conversation store;
- manual search remains capped at 25 parsed candidates with the existing five-at-a-time enrichment/show-more behavior; no OBI pagination or Worker/protocol change is added.
- exact OBI product parsing now keeps one nullable trusted primary `bilder.obi.pl` image from structured product data; it stays Android-local, is persisted in Room v6 `message_products.imageUrl`, and is reused by advisor cards and manual exact-enrichment cards;
- Coil 3 Compose + OkHttp owns asynchronous cached thumbnail loading; image failure remains presentation-only and adds no product/advisor error or OBI retry.

No local transcript is replayed as a hidden fallback if an old OpenAI response chain cannot continue. No compaction/summarization is added. `previous_response_id` reduces application-level transcript replay but prior context tokens remain billable input.

Android test version: **0.1.10 (11)**.

- every successful OpenAI START/MESSAGE/CONTINUE response can carry bounded usage metadata without making telemetry a correctness dependency;
- GPT-5.6 Luna remains historical baseline data, while production now uses GPT-6 Luna with versioned pricing and the same usage accounting boundaries;
- local cumulative requests, USER turns, tool-assisted turns, tokens, model-grouped totals, known USD cost, and unpriced-request count are persisted without conversation content;
- Settings exposes AI Usage with adaptive sub-cent cost formatting;
- NBP USD/PLN is fetched only from the usage UI, cached for 24 hours, and fails soft to a dated stale rate or unavailable PLN;
- a user-configured local Taksula remaining budget uses the current cumulative cost as its baseline and is explicitly not OpenAI balance/credit data;
- the below-USD-1 budget warning is one-shot per threshold crossing and re-arms after budget reset/increase.

- exact verified OBI lookups now carry optional model-context brand, short description, and bounded technical facts from the same decoded product payload;
- live proof used OBIK 3496072 (adhesive), 6743009 (drill), and 7156243 (LED fitting), confirming productDescription/productOverview/technicalData shapes across categories;
- optional rich sections fail soft while OBIK/store identity, stock, and price rules remain unchanged;
- advisor tool payload remains bounded to brand 80, description 220, six facts, fact label 60, fact value 100;
- message-product snapshots/cards/reports remain unchanged; rich facts are transient model context only;
- raw OBI HTML/Nuxt, URLs, EAN, cookies, diagnostics, and verification timestamps remain outside OpenAI tool results.

- final Taksula instructions define a practical provider-aware retail/wholesale advisor rather than a literal product-answer bot;
- ordinary technical/sales advice uses normal model knowledge when sufficient and does not automatically invoke the provider merely because a product category can be inferred;
- product selection uses the shared three-state model: unknown category clarifies without lookup; known category with a decision-critical variant unresolved clarifies/resolves while safely browsing plausible candidates in the same turn; sufficiently specified intent verifies immediately. Provider browsing is never used to infer the missing parameter;
- understood job/project requests receive practical essentials-first advice without automatic OBI lookup, while explicit concrete-product or complete verified selected-market requests may use efficient multi-query verification;
- complements are restrained and are proactively mentioned only when materially helpful for correctness, compatibility, safety, or avoiding obvious failure; direct current price/stock questions remain direct and avoid unnecessary cross-sell;
- model-facing instructions do not expose the numerical local-tool guard; Android independently enforces three local calls per USER turn and graceful `local_tool_limit_reached`;
- stock `0`, null stock, grouped `not_found`, and grouped `unavailable` remain distinct evidence states and must produce distinct wording;
- zero-stock/not-found requested items may lead to a verified current-store substitute and an offer to check another market, but no other-market number or availability is invented;
- ordinary general technical knowledge remains allowed, specific OBI SKU/current-store claims remain under the verified tool trust hierarchy, and missing SKU-specific facts are not invented;
- GPT-6 Luna, low reasoning, multi-query/schema/productRefs boundaries, hard tool/product limits, multi-store authorization, web search, pricing, UI, persistence, and parser remain unchanged.

- current Responses `web_search` is available selectively alongside the protocol-appropriate local provider tool, with `tool_choice=auto` and `max_tool_calls=1` for built-ins;
- Android local provider verification remains authoritative for current selected-branch stock/price/availability and only current-turn verified snapshots may enter productRefs/cards;
- final answers preserve at most six normalized HTTPS sources from actual OpenAI url_citation annotations; real annotation offsets are mapped only when exact, safely mapped citations render as clickable inline markers, and unmappable sources remain in the compact fallback source list;
- Room schema v11 retains message sources/search actions/product images and additionally persists provider/branch ownership, provider-owned product identity, central stock, message attachments, and nullable Advisor trace correlation through explicit non-destructive migrations;
- searched pages are explicitly untrusted reference data and cannot alter role/tool/trust/privacy rules;
- AI Usage counts completed web searches independently from optional token usage; current pricing adds USD 0.01 per search action when cost is priceable;
- web availability alone does not count as a call, while missing/malformed usage still preserves the actual search count and keeps existing unpriced/known-minimum budget semantics;
- no deep research/background/streaming/file search/image search/RAG/MCP/general autonomous agent behavior is added.

## Next implementation milestone

The Advisor architecture/policy/observability stage is complete. The immediate release gate is one final real-model behavioral suite across OBI and KWANT, followed by final contract review of any true regressions and a signed Play AAB. Context compaction, privacy-policy content, broader attachment formats, and other feature work remain separate future milestones.

## Not started

- Context summarization/compaction
- Explicit continue-as-new-context fallback
- Strong per-device/user identity
- General chat
- Additional agent tools
- Streaming
- Server-side OBI implementation
- Camera barcode scanning
- Nearby-store fallback behavior
- Final Play release polish


## 2026-10-03 — KWANT provider integration

- Added `kwant-pl` behind the neutral `ProductProvider` boundary using the researched public KWANT frontend contract.
- Added provider-owned KWANT branch metadata/`departmentCookie` construction and selected-branch stock parsing.
- Added explicit neutral price scope so KWANT public prices are `ONLINE` while current OBI prices remain `BRANCH`.
- Production provider registry now resolves both OBI and KWANT; UI/advisor/persistence remain unchanged.
- Fresh KWANT lookup route is live-proven: `/produkt/580` -> HTTP 308 -> canonical `...-580` product URL -> HTTP 200, with final public product ID `580`; the reusable live probe records only safe status/path/URL/product-ID evidence.


## 2026-10-03 — persistent WorkingProfile

- Added persistent global provider/branch selection with default `obi-pl / 075`.
- Added neutral branch discovery to ProductProvider; OBI uses its existing supported-store allowlist and KWANT uses the public branch directory with `department_stock_id` identity.
- Added Room schema v7 conversation ownership of provider + branch; v6 historical conversations migrate to OBI with their existing store number preserved as branch.
- New conversations capture the current global working profile and saved conversations retain it.
- Manual search now routes through the provider selected by the global working profile and keeps provider-owned ProductRef identity.
- Provider-specific manual product labels distinguish OBIK from KWANT article numbers.
- Existing advisor tooling remains OBI-only and does not automatically switch providers.


## 2026-10-04 — provider-aware advisor / KWANT

- Added advisor protocol v3 using provider + branch identity and neutral `find_products`.
- Added Android local advisor tool execution through `ProductProviderRegistry`.
- KWANT conversations now enter the advisor using their saved WorkingProfile (e.g. `kwant-pl / 205`) instead of returning unsupported-provider.
- Kept existing OBI advisor transport on protocol v2 for rollout compatibility.
- Added Room v8 provider-owned advisor product persistence. The already-shipped v6→v7 migration remains unchanged; v7→v8 adds providerId/productId/branchId/articleNumber and maps historical OBI products safely. Price scope is restored from provider identity rather than adding another database column.
- Added provider isolation: a KWANT conversation cannot execute an OBI tool request and vice versa.
- Added Worker and Android coverage for protocol v3, KWANT 205, no branch fallback, provider-owned product refs, and KWANT online price scope.


## 2026-10-04 — advisor product intent and exact lookup

- Clear concrete-product, recommendation, assortment, price/stock, and identifier intent now proactively uses the current conversation provider; general technical advice remains lookup-free by default and decision-critical ambiguity still clarifies first.
- Exact seven-digit OBIK tool queries bypass OBI text search and use the existing exact store lookup directly.
- KWANT advisor input exposes no model-controlled direct product-ID path; article number, EAN, product name, manufacturer text, and other user-supplied identifiers remain search-then-exact-verification inputs.
- KWANT branch metadata is resolved once per provider-tool execution and reused for all verified candidates without adding a broad cache.


## 2026-10-04 — provider-aware behavioral parity harness

- Extended the existing A–I behavioral evaluation families across OBI protocol v2 and KWANT protocol v3, with provider-shaped direct-product fixtures and the same provider-neutral quality bar.
- Preserved the OBI fixtures and OBIK baseline; KWANT direct-product variants use article-number search text, provider-owned references, branch `205`, and online-price fixtures rather than OBIK semantics.
- The manual behavioral workflow runs both paths by default and can select either path for focused audits.
- No OBI or provider-v3 production advisor instruction change was needed; deterministic parity coverage exercises decision-critical clarification, proactive lookup, availability distinctions, advice-first jobs and technical questions, bounded assortment wording, and restrained complements.
- After the first real-model parity run, scenario A was calibrated so a broad low-risk commodity request such as black cable ties may browse several verified variants immediately; decision-critical compatibility or safety ambiguity still clarifies first.
- The concrete-product recommendation fixture no longer exposes `mock` or eval-only wording to the model.

## 2026-10-04 — KWANT logistics visibility

- KWANT parsing now exposes selected-branch and central stock separately; a
  missing central value remains null.
- KWANT cards label selected-branch stock, optional central stock, and the
  indicative online price. Existing OBI presentation remains unchanged.
- Explicit other-location requests can perform one uniquely resolved KWANT
  branch lookup while the conversation WorkingProfile remains unchanged.
- Room v9 persists nullable central stock; migration 8→9 only adds that column.

## 2026-10-04 — advisor attachment foundation

- Added provider-neutral IMAGE/PDF metadata with exactly one attachment per USER message and no binary content in domain or Room models.
- Added app-private attachment import/read/delete storage using opaque identifiers; no picker, composer, upload, Worker, or OpenAI request behavior changed.
- Room v10 adds the normalized `message_attachments` relation. Historical messages migrate with zero attachments and metadata cascades with message/conversation deletion.
- Explicit deletion, 30-day retention cleanup, and interrupted-turn recovery now perform best-effort cleanup of only the related private attachment files; recovery captures the trailing USER attachment id before Room deletion and preserves draft recovery even if filesystem deletion fails.
- Attachment import now enforces a temporary 16 MiB per-file Android private-storage ceiling before copying while retaining exact copied-byte validation; no Worker/OpenAI transport limit is introduced.

## 2026-10-04 — Android attachment acquisition and composer UX

- Added the approved in-composer attachment menu, private image/PDF acquisition, compact private-file preview/removal, and existing WorkingProfile selector hook.
- Added Android Photo Picker, system camera temporary capture, constrained system document selection, bounded EXIF-aware image normalization, localized acquisition errors, and single-pending-file lifecycle cleanup. A one-localId ownership marker now closes the cold-start orphan gap while preserving restored pending state and persisted message attachments.
- Preserved text-only advisor behavior and explicitly gated every attachment send until multimodal protocol/Worker transport arrives in PR #3.

## 2026-10-05 — multimodal transport / protocol v4

- Enabled one persisted JPEG, PNG, or PDF to stream from Android private storage through Worker multipart validation to GPT-6 Luna.
- Added image `input_image`/high-detail and PDF `input_file` data-URL request shaping while preserving response chaining and the local-tool continuation loop.
- Kept protocol v2/v3 and text-only paths compatible, with attachment bytes restricted to the initial `/start` or `/message` request.

## 2026-10-05 — attachment turn lifecycle

- Enabled attachment-only USER submission and attachment-derived conversation titles without placeholder message text.
- Completed pending-to-Room ownership handoff: pending ownership remains until USER metadata commits, then marker release is attempted synchronously without deleting the persisted private file; release failure is harmless and reconciled later.
- Kept failed-turn retry behavior intact, including restored text/attachment state and preserved private bytes, while current conversation reloads retain USER attachment metadata.

## 2026-10-05 — sent attachment rendering + final attachment UX polish

- Added persisted USER image/PDF rendering in current chat and restored history using the existing private attachment file; PDFs show sanitized filename and readable size, images render above optional user text, and timestamps retain the existing bubble layout.
- Added graceful missing/corrupt-file presentation with no private path/content disclosure.
- Finalized composer import UX with a compact loading/preview row, always-available remove action, send disabled during preprocessing, and generation-guarded serialized imports so stale results cannot cross conversations or replace newer selections.
- Tightened large-image decode sampling while preserving the existing normalization pipeline and 4096 px output cap.
- Added focused coverage for persisted image/PDF render state, database recreation, missing/truncated files, loading send gating, stale selections/conversation invalidation, stale-file cleanup, removal, and text-only behavior.
- Kept provider behavior, advisor protocol v4, local-tool authority, response chaining, and PR #76 ownership/recovery semantics unchanged; no proxy runtime change is required.


## 2026-10-06 — live KWANT search and stock repair

- Follow-up live Android-vs-browser auditing showed that Next-data returns only the search page shell; actual results come from `POST https://services.kwant.net.pl/api/front/search-engine/page`.
- Android search now calls that frontend API directly with `q/page/limit/tags` and parses candidates from `hits[]`; buildId and Next-data are no longer part of KWANT search discovery.
- MBN116E, EAN 3250614312762, and the plain-text Hager query are covered by realistic frontend-API fixtures and preserve candidate product ID `580` followed by the existing exact lookup.
- Central stock still comes from structured main-product `product.stock`; selected branch stock remains bounded to the main product availability block so recommendation cards cannot leak their stock into the verified product.
- Live-probe evidence now records the trusted `services.kwant.net.pl` search API POST and uses its body `q` as query-specific evidence instead of treating a Next-data request as search proof.
- OBI paths, proxy runtime, prompts, provider routing, protocol versions, WorkingProfile behavior, branch lookup, and Room schema remain unchanged.


## 2026-10-06 — KWANT selected-branch stock repair

- Live probe of product 580 / branch 205 showed raw Android-like HTML has the selected branch name but no branch quantity, while the hydrated frontend calls `/api/front/products/580/current?depstock=205` and receives root `department_stock` for the main product.
- Exact KWANT lookup now keeps page parsing for identity, online price, canonical URL, and central stock, then performs one fail-soft current-product request for selected-branch stock only.
- Parser validation requires exact `product_id` and `department_id`; zero is preserved, missing/malformed data remains null, and recommendation stock cannot contaminate the result.
- KWANT search API from #79, OBI, proxy, prompts, WorkingProfile, advisor routing, and Room remain unchanged.


## 2026-10-06 — advisor product facts bounded before continuation

- Added one shared Android text/fact shaping policy used by both `ProviderProduct -> AdvisorVerifiedProduct` and OBI `LocalProduct -> AdvisorVerifiedProduct`.
- `FindProviderProductsTool` plus normal-search and exact-OBIK paths in `FindObiProductsTool` now all pass through that same policy before continuation serialization.
- Oversized descriptions/facts are normalized and bounded to the existing Worker contract (220-char description, six facts, 60-char labels, 100-char values); blank facts are discarded in stable source order.
- Added oversized provider, serialized v3 `/continue`, and oversized OBI search/exact regressions while preserving OBIK/provider identity, price, branch stock, central stock, and already-bounded content.
- Proxy limits were not loosened; provider parsers and all routing/persistence/prompt behavior remain unchanged.


## 2026-10-07 — conservative advisor fact relevance rescue

- Extended the shared OBI/provider advisor shaper with source-order baseline + one optional query-relevant rescue when more than six valid technical facts exist.
- Normal/no-match behavior remains the first six valid normalized/bounded facts in source order. A later fact is admitted only for a clear deterministic lexical/value signal and only if it is stronger than the weakest selected fact.
- Added exact-value coverage for 16 A, 400 V, IP65, 2,5 mm2 and 3 phases, plus no-match/no-aggressive-reorder, minimal displacement, OBI/provider parity, and tool-level OBI/provider query propagation.
- Existing continuation bounds, identity/price/stock fields, parsers, proxy contract, protocol versions, prompts, WorkingProfile, Room, attachments, and web-search policy remain unchanged.


## 2026-10-07 — shared OBI + KWANT branch/location routing

- Added one provider-neutral branch resolver using real `ProviderBranch` IDs, names and addresses.
- Replaced OBI's number-only branch metadata with a canonical 62-market directory verified against OBI Poland's official customer-relations list; Kraków Wielicka is market 003 and Nowy Sącz remains 075.
- Advisor authorization resolves the current USER message against only the active provider directory. Natural metadata now requires an explicit branch/store location phrase and adjacent location metadata; incidental city/street words stay `NotMentioned`. Unique authorized references rewrite only the turn-local tool branch, ambiguous/unknown explicit references reject instead of falling back, and raw model `requestedBranch` cannot authorize a switch.
- KWANT routing uses the same resolver over its existing live branch metadata; verified examples include Nowy Sącz 205 and Zamość 128.
- Current-branch aliases (`u nas`, bounded Nowy Sącz/Sączu forms) remain on the conversation branch. Cross-branch lookup does not mutate WorkingProfile, protocol versions, product search/lookup contracts, stock endpoints, Room, attachments, prompts, or advisor fact shaping.


## 2026-10-08 — Advisor observability B2.1

- Added one privacy-safe structured `advisor_protocol` Cloudflare log event per protected Advisor Worker request, including protocol/provider/branch/stage, observable response/tool counts, normalized usage/cost when present, total request latency, and safe failure categories.
- Added turn correlation through `X-Taksula-Trace-Id`: START/MESSAGE always create a new trace, while CONTINUE reuses a valid propagated trace or fails soft to a new trace when correlation is unavailable. Each HTTP request also receives an internal requestId.
- CONTINUE logging records only deterministic aggregate local-result counts and safe rejection category; no user/model/query/product/attachment content, product identifiers, stock/price values, response/call IDs, upstream bodies, credentials, or chain-of-thought enter structured logs.
- JSON protocol bodies, prompts, provider behavior, tool schemas, retrieval, attachments, usage pricing, Android, and protocol versions are unchanged. Android propagation/reporting remains B2.2.


## 2026-10-09 — Android Advisor observability B2.2

- Android now captures the Worker's optional `X-Taksula-Trace-Id`, validates it as UUID-form metadata, and propagates one trace across every CONTINUE in the same USER turn without sending an old trace on START/MESSAGE.
- A valid trace returned by a continuation becomes authoritative for later continuations; a missing/invalid later trace does not erase an already-established turn trace.
- Successful ASSISTANT responses persist the final trace in Room schema v11 via migration 10→11. Historical pre-B2.2 messages keep null trace and conversation/history reload restores new traces normally.
- Existing problem-report TXT output now carries the persisted assistant trace, while failure diagnostics include the known Worker/current-turn trace when available. Unrelated general reports do not receive a random previous Advisor trace.
- No Advisor behavior, Worker implementation, JSON schema, tool contract, provider/retrieval behavior, attachment lifecycle, pricing, chat layout, telemetry database, or dashboard changed.


## 2026-10-09 — behavioral eval fidelity cleanup

- Scenario B now distinguishes a bounded factual surfaced-count statement from an actual completeness/exclusivity claim; wording such as three verified variants is not a failure unless the answer semantically claims those are the whole assortment.
- Scenario D now evaluates the approved State-2 behavior: keep the decision-critical parameter unresolved, safely browse and surface both plausible candidates in the same turn, and prohibit premature compatibility/correctness claims. A literal question mark is not required when the answer gives a concise actionable way to establish the missing parameter.
- Deterministic protections for local browse, both ambiguity branches, surfaced candidate refs, grounding, web policy, and premature-match rejection remain intact. No production Advisor prompt or runtime behavior changed in the eval cleanup.
