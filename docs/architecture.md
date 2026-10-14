# Architecture

## 2026-10-14 — INC-007 R2 independent identifiers and bounded recovery

Current standalone USER identifiers are classified as independent discovery before historical inventory admission. If the model sends such an identifier to `find_product_locations`, Controller executes the existing provider discovery tool with only that USER identifier, the saved conversation branch and the existing five-product bound. The mistaken request and actual discovery consume two of the existing three local calls. Exactly one matching, provider-verified identity is required; guessed model IDs, historical stock and unrelated cards do not enter the recovered result.

Recovery returns the fresh selected-branch lookup facts through the original location continuation, retaining its response/call IDs, transport family, trace and saved profile. It performs no departments/other-market inventory scan for a bare identifier. A null stock remains a coherent unknown result and a fresh card may retain its verified identity. Original productRefs filtering still selects only current-turn snapshots; final recovery prose comes from local evidence. If a later model discovery verifies that same exact USER query within the remaining budget, its fresh evidence replaces the earlier refusal without another local call. A new explicit stock request without identity can still reject -> discover -> retry within the existing three model calls; the current USER location remains authoritative throughout.

Bounded location clauses accept courtesy prefixes and natural `nie sprawdzisz mi w …?` retries only after an uninterrupted USER-owned product/inventory request. The complete location expression must resolve through the active canonical directory. Price, advice and unrelated USER turns still break inheritance; ASSISTANT suggestions never bind `tam`. A verified standalone product query can start its own new follow-up chain after an unrelated turn. Every explicit catalog identity, including codes before a command, is checked rather than allowing a newer unknown code to revive an older card. Embedded numeric dimensions such as `30/20G` are not catalog identity.

The shared BranchResolver's watt guard previously hid `w [city]` immediately after a numeric product ID. It now distinguishes the unit in `1000 W w Bochni` from the location preposition in `produkt 77556 w Tarnowie`; unknown and ambiguous cities reject before discovery rather than defaulting. R1 OBI bare identifiers, quantities and complete Kraków 003/019/059/072 scope remain covered.

No Worker, prompt, provider API, tool schema, call/product limit, persistence, UI, version or timeout change. [Executed evidence and exact histories](inc007-r2-recovery.md) distinguish synthetic provider controls from live stock, which remains unverified for 206/204.

## 2026-10-14 — INC-007 R1 OBI identity and city-wide market scope

An independent USER search is eligible on every turn; existing local-call counters reset in `runTurn`, not per conversation. Bare OBIK/EAN identifiers are product queries. Only a bare **three-digit** reference/comparison can select an OBI market; labelled market references still require canonical directory validation. Quantities, dimensions and product labels do not grant market authority. No location continues to use the saved conversation WorkingProfile.

The existing BranchResolver now distinguishes a directory-grounded plural OBI city scope from an ambiguous single-market city. “W krakowskich marketach” is a reviewed city-name alias, eligible only if Kraków exists in the directory; “w [wszystkich] marketach w Krakowie” uses the same canonical resolver. Market IDs are read from the directory, never from model hints or a hardcoded list. Singular Kraków without a distinguishing street/market remains ambiguous. Unknown/conflicting explicit places remain explicit failures and cannot become a profile fallback. This bounded repair does not add general adjective generation or change KWANT/follow-up grammar.

For new-product discovery in a plural scope, Controller deterministically uses the lowest canonical market ID **inside the USER-requested set** as an identity-verification seed. This is a single-market provider lookup, not a completed city-wide stock check. The original complete scope survives into the existing `find_product_locations` operation, which verifies that exact product and freshly reads every requested canonical market through the existing adapter/batching/coverage logic. Under today's repository directory, Kraków comprises 003, 019, 059 and 072; 075 is not a substitute. A premature location call with no trusted new identity is rejected, then discovery and a location retry can recover within the unchanged three-call turn budget. The model still determines which existing calls occur; the client does not synthesize calls, IDs or completion.

Identity-only completion renders an honest incomplete-scope statement, and only claims requested identity verification when current facts match every explicit numeric USER product reference. City-wide final text uses local verified location evidence/reasons rather than contradictory model stock prose. Provider-verified seed cards remain grounded in their actual branch and retain product links. Historical cards authorize identity only; missing/malformed/null inventory remains unknown, observed zero remains zero. Dedicated provider availability UI is unchanged.

Android/Worker wire schemas, saved profile, response/call identity, three-call/five-product caps, provider HTTP, model/prompts/budgets, persistence and UI are unchanged. Existing v2/v3 serializers accept the conversation profile 075 separately from verified seed-result branch 003; regression tests exercise those actual bytes and unchanged Worker validation. No additional transport contract is needed. Live model/provider completeness, the independent 502 and INC-007 KWANT R2 remain separate release gates.

## 2026-10-14 — INC-006 R2 bounded natural inventory follow-ups

Inventory clarifications use the same-conversation history already supplied from `MainActivity.persistedTurnConversation`: at most 12 preceding messages/four USER turns and bounded verified cards. Plain `w/we + canonical city`, optionally city + verified street, joins the existing `a w/we` and canonical-city forms. The current USER expression is resolved through the merged R1 BranchResolver and the active provider directory. A unique place is eligible only after an uninterrupted USER inventory anchor and trustworthy product identity. The bounded “czy jest/są w OBI/KWANT” or “czy jest/są u nas” form is also a USER availability anchor. A price/advice/installation turn breaks that chain; an ASSISTANT suggestion does not create it. Unknown/ambiguous short places produce bounded local reasons before inventory. An ambiguous single-city OBI request such as Kraków needs street/market clarification; explicit all-markets-in-city and complete multi-city inventory operations retain existing bounded scope/coverage behavior.

`u nas` resolves to the saved conversation branch, not the old card's branch or a model hint. `tam` refers only to the latest uniquely USER-selected canonical location since the inventory anchor. A missing, ambiguous or conflicting selection asks one location clarification; it never skips a newer ambiguous selection to reuse an older branch. Repeated `tam` can retain that earlier explicit USER referent, ignoring ASSISTANT suggestions. No persisted state or new routing subsystem is added.

Product identity remains verified/equality-bound. Fresh same-turn discovery snapshots join trusted identity candidates before a location retry; they cannot establish USER inventory intent or location scope. For inherited short follow-ups, only fresh identities matching the independently selected USER product are eligible; an unrelated discovery cannot replace it, and multiple inherited candidates remain ambiguous. Current explicit identity supersedes older selection/`obu`; unknown current or latest-anchor numeric/article identity cannot revive another old card. A verified article may be requested without its exact slash-delimited manufacturer suffix (`MBN116E` versus verified `MBN116E/HAG`); this is an exact canonical boundary, not fuzzy/prefix matching, and multiple verified matches remain ambiguous. Explicit product labels disambiguate short product IDs from market IDs and measurements. Multiple explicitly selected historical products are never reduced to the first match or one ASSISTANT card. The single-product location protocol cannot represent an `obu` operation; both providers return `multi_product_tool_contract` without partial inventory work.

Location-tool hints are structurally parsed as before but do not constrain USER-owned scope. Android resolves a unique USER city/street or uses the verified profile when no location is selected; existing complete broad/chain validators independently bound multi-location operations. Model hints cannot authorize additional branches, override a USER selection or veto that resolved scope. Wrong provider, missing/malformed/duplicate directory, unknown/conflicting USER places and invalid product identity still fail before inventory HTTP. Provider adapters, exact product-bound requests, batching/coverage/timeout/tool budgets and zero/null semantics are unchanged.

Historical cards authorize identity only. Fresh location results supply stock/time/unit evidence; they never create current-turn product cards. Current-turn discovery snapshots remain the only `productRefs`/card source. Deterministic local descriptions distinguish unknown/ambiguous location, missing/ambiguous trusted product, combined-product limitation, directory outage, missing inventory intent and tool rejection. Inventory-only, incomplete/unavailable/rejected location outcomes and discovery rejections replace contradictory model narration at final rendering; complete mixed discovery + location answers keep model explanation plus the existing deterministic location details. Sources/spans are cleared when model text is replaced; usage, response/call identity and trace remain unchanged. No raw errors/prompts/products are logged. A failed OpenAI continuation still follows existing transport-error behavior rather than accepting partial output.

Wire remains unchanged: Android serializes the existing 12-field location-result envelope; Worker `validateLocationToolResult` accepts the existing bounded `reason` string (<=64), exact coherent ID/row sets and zero/null semantics. No Worker, prompts/model/budgets/schemas, HTTP APIs, separate stock window or persistence change. R1 discovery authority remains intact. Full device/model behavior is a release verification gate, not inferred from offline Kotlin.


## 2026-10-14 — INC-006 R1 discovery location authority

Product discovery defaults to the saved conversation WorkingProfile branch. Ordinary provider context such as “czy jest w OBI” does not select a new location. A current USER place phrase (“w Bochni”, “KWANT Bochnia”, city + street, or an explicit market/branch number) is resolved only against the active provider's verified directory. Reviewed nominative/locative aliases bind to directory names, never hardcoded branch IDs; KWANT needs no special command or numeric department knowledge. An OBI market number may also stand alone. Bare KWANT product numbers, quantities, dimensions, article codes and unscoped numeric tokens are not branch authorization.

BranchResolver now parses scoped place descriptions instead of globally ranking every matching number/address token. City/ID/street pairs must agree. Supplied multiword street tokens must match one contiguous directory address, not a bag assembled across branches. Unknown explicit locations, unresolved “tam / innym markecie” references, incomplete/contradictory descriptions and multiple city matches reject before lookup; e.g. Kraków without street/market selection remains ambiguous. The existing bounded rejection continuation asks the advisor to clarify; this PR does not guarantee live model wording or tune prompts. An entirely explicit USER-named numeric comparison set retains per-call selection only within that set. Discovery does not fan out and does not alter the separate multi-location availability window.

Controller rejects wrong provider, missing selected branch, and unavailable/empty/oversized/duplicate-ID/blank-name directory before product execution. With a uniquely resolved USER location it overwrites only turn-local tool branch and clears requestedBranch; with no USER location it does the same for the profile branch. Contradictory model branch hints cannot authorize another branch or veto a valid default lookup. Provider adapters, product limits, verified-stock semantics, current-turn snapshot/card grounding, conversation profile and continuation identity remain unchanged.

This is current-turn discovery only. AdvisorLocationFollowUps, AdvisorLocationsTool, KWANT inventory-scope authorization, Worker/model/prompts/schemas/budgets, provider HTTP, persistence and UI are unchanged. R2 must separately repair a plain “w Bochni” clarification after a failed previous turn and location-tool hint/identity behavior.


## 2026-10-13 — INC-005 F4 bounded continuation output and incomplete usage

START and MESSAGE retain `max_output_tokens=384`. CONTINUE selects **1024** only after the existing strict client-result validator accepts a product result with at least two query-result groups or at least two returned products (including legacy v1 multi-product output). A single group with zero/one product, authorization/tool-limit rejection, and location-inventory result keep **384**. Complexity is determined from typed counts, never prompt text, model hints or a client budget override. The three-local-call/five-product caps, provider authorization, `gpt-6-luna`, low reasoning, prompts, schemas, network deadlines, no-retry behavior and response/call identity are unchanged. This is a bounded reliability candidate, not a measured guarantee that 1024 suffices for every continuation.

The normalizer still requires `status=completed` before accepting any answer or tool call. Incomplete output remains `502 {"error":"upstream_failure"}`: `max_output_tokens` reason maps to `upstream_incomplete_output_budget`; other reasons map to `upstream_incomplete_other`. It now independently validates bounded model/token usage metadata on that incomplete path and carries only the normalized `AgentUsage` through `UpstreamFailureError`, including the wrapper that attaches OpenAI HTTP status, into existing `advisor_protocol` usage fields. It never parses/promotes partial answer/tool/hidden reasoning content. Invalid/missing usage is dropped without changing the original failure category; missing optional details remain null and observed zero remains zero. Incomplete cost/pricing stay null because complete web-search billing is unknown. Existing event shape/schema version, public error body and trace propagation are preserved; failure usage is log-only and is not added to Android Settings accounting.

The October 13 KWANT v3 CONTINUE exhaustion is CONFIRMED by the production-log evidence supplied for this repair. The October 12 pump failure remains UNKNOWN; neither this budget policy nor offline fixtures establish its cause. See decisions/progress for evidence provenance, costs and validation.

## 2026-10-13 — INC-005 F2/F3 Worker output blankness parity

Provider v3–v5 model `requestedBranch` accepts null or bounded nonblank text; blankness matches Android's control/whitespace normalization followed by Kotlin `isNotBlank` (Unicode separator categories plus all Cc controls). Worker only validates and preserves valid location text; Android still owns its normalization and user-authorized branch resolution. Model `call_id` and response `id` use Kotlin's distinct unnormalized blankness rule (Unicode separators, U+0009–U+000D and U+001C–U+001F), preserving every accepted opaque ID exactly. Do not apply JavaScript trim or location control normalization to IDs. Blank tool fields fail with existing `upstream_invalid_tool_call`; a blank response identity fails earlier with existing `upstream_invalid_envelope`. Public 502/trace behavior, strict client CONTINUE validators and F1 allocation remain unchanged. Native MockWebServer parser tests document Android acceptance, including NBSP and the FEFF/NEL distinction; no production Android changes.

## 2026-10-13 — INC-005 F1 model query-plan allocation

Only the OpenAI completed-response product-tool normalization path opts into aggregate-limit allocation for OBI v2 and provider v3–v5. Every query first passes existing exact-shape, count, query and individual-limit validation. For overflow alone, allocate in original order, reserving one result per remaining query; `[3,3]` becomes `[3,2]`, and five requested limits of five become five limits of one. No query is dropped/deduplicated and no limit is increased. Provider, branch and requestedBranch remain unchanged; Android retains its existing user-owned authorization boundary. Strict parser defaults and all CONTINUE validators still reject over-budget client query results. Model-visible schemas, prompts, hard limits and error/trace contracts are unchanged.

## 2026-10-12 — INC-003 bounded KWANT same-product location follow-up

KWANT's Android Advisor normalizes two explicitly bounded quantity follow-up forms (`A ile tego samego produktu jest w [location]?`, `A w [location] ile jest tego samego?`) only when the location resolves against the loaded canonical branch directory and the uninterrupted preceding USER inventory-selection chain contains a trusted product. The normalized intent is used solely for the local location tool; verified history still owns product identity, while the local adapter still performs a fresh product-bound inventory request. The abbreviation `Sącz/Sączu` derives only from a directory branch named `Nowy Sącz`, never an embedded branch ID. If an unrelated USER turn interrupts the chain, the contextual quantity tool is rejected as `untrusted_product` without a KWANT HTTP read. Existing OBI resolution and full explicit-product flows remain unchanged.


## 2026-10-11 — INC-002 verified stock-unit CONTINUE wire contract

Worker accepts an **optional** `stockUnit` only inside verified-product objects for OBI v2 and provider v3–v5 CONTINUE, where Android serializes it even when null. Values are null or bounded (1–32 characters) presentation labels; legacy clients may omit this field. Unknown product keys and malformed unit types continue to fail through strict shape validation. No public HTTP response, endpoint, trace, provider authorization, model/token limit, or tool budget change. This fixes a synthetically reproduced 400 `invalid_request` when Android sends a verified product with `stockUnit`.


## R2 — Responses completion gate (2026-10-11)

Worker normalizes OpenAI Responses only when the top-level `status` is exactly `completed`. Explicit `incomplete` responses are classified as output-budget exhaustion only for structured `incomplete_details.reason=max_output_tokens`, otherwise as other incompleteness. `failed`, `cancelled`, `queued`, and `in_progress` are rejected as noncompleted; missing, unknown, or malformed status is rejected independently. This gate precedes tool-call extraction or structured-final parsing; valid-looking output cannot authorize Android tool execution. The public 502/upstream_failure response and trace header remain unchanged. No retry, extra deadline, protocol change, tool-budget change, model-budget change, or attachment/provider behavior change.

## PR #112 final audit — canonical KWANT Polish locatives (2026-10-11)

The shared `advisorCityLocative` now recognizes deterministic locatives for all 21 canonical cities listed in KWANT research #111 (including Białymstoku, Bochni, Piasecznie, Mielcu, Włocławku, Suwałkach). Both KWANT explicit location authorization and bounded `AdvisorLocationFollowUps` resolve aliases ONLY against the current verified provider directory, deriving branch IDs from its `ProviderBranch` records rather than any static alias-to-ID lookup. The bounded follow-up syntax now supports `a we Włocławku?` alongside `a w Białymstoku?`; unrelated USER messages still sever permission. Public directory pages confirm Białystok 216 and Tarnów 204, correcting earlier incorrect synthetic fixtures. Tests cover every canonical locative, canonical numeric-ID routing, multi-city complete/fail-closed scope, and Controller → real adapter checks. No HTTP contract, Worker wire schema or provider search/persistence changes.


## PR #112 audit: Worker-valid unavailable evidence and complete KWANT location chains (2026-10-11)

Unavailable KWANT location outcomes now preserve a complete canonical `locations` row with `stock=null` for every `checkedIds`/ `missingIds` entry. If the canonical directory itself is unavailable, use empty checked/missing/location collections and `coverage=unknown`, never fabricated names or zero stock. The existing strict Worker `validateLocationToolResult` remains unchanged; real Android `continueTurn` JSON serialization and Worker validation have corresponding regressions. Error status retains its precise reason without verified timestamp or central stock.

KWANT location authorization consumes the full comma, `i` and `oraz` sequence of canonical city names, locatives and numeric department IDs. The whole request is rejected for an unknown extra location, duplicate/unknown identity, or conflicting paired city/ID rather than silently choosing the first branch. User-owned scope and model-hint containment still apply before inventory HTTP; one GET per product, three logical calls and normal KWANT `find_products` are unchanged.


## Production KWANT cross-branch stock — guarded extended=1 (2026-10-11)

The Worker location-tool guidance is updated only to remove obsolete unverified-extended wording and clarify provider-specific empty location-hint semantics. The location-result JSON schema, protocol versions and normal provider search prompt remain unchanged.

PR #112 enables the exact browser-verified single-product `GET https://services.kwant.net.pl/api/front/products/{numericProductId}/departments?extended=1`. Production reuses strict JSON-only, cookie-free, redirect-free bounded OkHttp transport (no retries), canonical KWANT branch IDs and one HTTP inventory GET per requested product. Android parses product-bound `list[].department_id/stock`, root `unit`, and never reinterprets `total_stock` as central. Branch ID unknown/duplicate, wrong product ID and malformed/negative/non-integer stocks fail closed. Zero remains zero; null/missing/nonreturned branch IDs remain unknown; no 21-branch assumption.

USER text controls KWANT scope. Canonical branch and deterministic Polish locative parsing plus explicit numeric branch references restrict `find_product_locations` to one location (default current) or only a user-authorized nationwide set. Model hints are constrained to that scope, never a source of authorization. Transport may retrieve all public branch rows in one product-bound call, but adapter, Controller evidence and deterministic final rendering include only requested branches when the user scoped a subset. Shared historical single-product identity remains authoritative over model arguments.

Android `ProductLocationsResult.Available.stockUnit` and `AdvisorLocationEvidence.stockUnit` are optional and local-only; the Worker CONTINUE JSON schema is unchanged (unit omitted) and Android deterministic location rendering consumes the exact product-bound unit, dropping unknown/conflicting labels. KWANT and OBI headings, zero/unknown handling and verified timestamps are provider-specific. `obu` remains a two-product selection, but the current single-product `find_product_locations` tool cannot reliably transmit two independent product results in one continuation; Controller returns typed `multi_product_tool_contract` without fetching or hiding a result for only one. Ordinary grouped `find_products` remains separate and unchanged.


## PR #110 latest explicit product selection supersedes inherited "obu" (2026-10-11)

The history resolver maintains the shared USER-origin bounded ID grammar but limits inherited multi-product scope: a new current USER explicit single-product ID or unknown explicit numeric identity ends any prior `obu` selection. Current `obu` remains authoritative and uninterrupted canonical location-only follow-ups continue the prior `obu` scope. The resolver narrows verified historical snapshots only for a current verified exact ID; unknown explicit IDs are passed to the normal locations authorization guard to reject before transport. Controller integration regressions cover verified 7027 replacing historical `obu`, wrong model hint, unverified 9999, fresh `obu`, and an ensuing Tarnów location-only question; the disabled KWANT extended adapter proves zero HTTP. The three-call budget and ordinary `find_products` routing are unchanged.


## PR #110 final integration identity consistency (2026-10-11)

Both `resolveAdvisorLocationFollowUp` and `AdvisorLocationsTool.resolveTrustedProduct` consume the shared `AdvisorProductIdentityReferences` USER-text grammar. It extracts short numeric identities only from bounded product/stock contexts, excludes measurement units (e.g. `1000 W`) and three-digit OBI store numbers, and preserves previous bounded long OBIK/EAN handling. The same policy applies to previous USER selections and the current turn before historical snapshots can be narrowed; model hints are equality-only and never grant product identity. Controller-to-history-to-real-location-tool offline regressions cover earlier 7035 selection versus a measured 1000 W, explicit product 1000, unknown IDs, conflicting model hints, OBI market numbers, and quantity dimensions. KWANT ordinary `find_products` and the three-local-call budget remain unchanged.


## PR #110 final short product-identity grammar (2026-10-11)

`find_product_locations` recognizes short KWANT product IDs/article numbers only in explicit identifier labels, direct `Sprawdź 7027` requests, or bounded natural Polish stock questions (e.g. `Sprawdź stan 7027`, `Sprawdź dostępność 7027`, `Jaki jest stan 921861`). Exact unverified short IDs fail closed, and model IDs remain equality checks. Verified 4–6 digit products are selected only through those bounded identity contexts; numeric quantities (including `1000 W`) and three-digit OBI market identifiers do not become product references. Existing 7–13 digit OBIK/EAN checks remain unchanged. Grouped two-query KWANT `find_products` continues independently for selected/explicit alternative branches, without invoking disabled `find_product_locations`.


## PR #110 final authorization audit hardening — canonical scope and KWANT IDs (2026-10-10)

Inventory-intent carry-over only accepts entire canonical city/branch-only clauses (including deterministic inflections), optionally with a contextual three-digit OBI market number. Price/advice suffixes break carry-over even after stock questions. The same shared canonical city inflections are used by follow-up matching and OBI authorized-scope parsing. OBI city-plus-market conflicts are rejected for both labeled `market w ... 054` and `a w ... 054`, without HTTP. Product identity validation rejects unknown explicitly labeled short KWANT IDs/article numbers (e.g. 7027/7035) before any location read; unlabeled quantities, dimensions and OBI market IDs do not create IDs. Ordinary KWANT `find_products` remains independent from unavailable multi-department `find_product_locations`, with Controller branch-route regression coverage.


## PR #110 audit hardening — bounded follow-up categories and current identity

A pending inventory request survives only explicit clarification forms (`obu`/`oba`, constrained location-preposition follow-ups, supported market clarification or broad relative-market follow-up) or a bare location that exactly names a branch in the active provider's canonical directory. Generic short messages, including `pokaż ceny`, interrupt authorization even if a previous stock request exists. Current-message exact identifiers are resolved solely against previously verified snapshots and take precedence over older user selections; multiple explicit products stay ambiguous. Model IDs remain equality-only. Only location/provider denial categories invariant under newly verified product evidence are memoized in a turn; `untrusted_product` and `ambiguous_product` must be re-evaluated after discovery. Every model tool request still consumes the existing three-call budget.


## Advisor inventory clarifications — bounded history (2026-10-10)

Android now supplies the location tool with a bounded prior-conversation evidence window: at most 12 preceding messages, four preceding USER turns, and up to three most recent assistant messages containing verified product cards in that 12-message window. A standalone city, "a w…", or "obu" is inventory-authorized only when chained to a previous **USER-requested** stock/availability operation with no unrelated intervening USER turn. Assistant suggestions and model location/product IDs do not authorize scope. Explicit earlier user identifiers and a single newest verified assistant card may disambiguate product identity; the model's ID remains an equality guard. An exact two-product "obu" clarification is retained across immediate related turns; because KWANT's extended transport is unverified, it produces a typed unavailable result before inventory HTTP rather than selecting one product.

OBI adds verified Miejsce Piastowe/Miejscu Piastowym alias, contextual city-vs-market ID conflict rejection (052 versus asserted 054, no silent substitution), and broad "markety w których…" relative clauses. For the specific rejected 054/052 mismatch, existing deterministic Advisor location-detail rendering now appends a canonical-directory-grounded confirmation request, explicitly stating that no stock HTTP occurred. The existing directory, 61-market coverage, four service reads / seven HTTP batches, 45-second budget, profile, Worker interface and trace semantics remain unchanged.


## Provider-verified stock unit in product cards

`ProviderProduct.stockUnit` is nullable presentation metadata, propagated through OBI `LocalProduct`, `VerifiedProductSnapshot`, Room `message_products.stockUnit` (v13), Compose saved state and `VerifiedProductUiModel`. KWANT only obtains explicit `unit` from a matching product page or product-ID-and-department-matched current-stock payload. If both trusted unit fields conflict, unit is unknown. OBI selected-store `articleData` still has no stock-unit field. For the same exact verified product, the PDP may however expose an explicit dedicated sales-unit fact such as `Sprzedaż: na metry` or `Jednostka sprzedaży: m²`; only these unit-labeled product facts are accepted for stock presentation. Titles, categories, dimensions such as `Długość [m]`, and price-per-unit text are never used to infer a unit. A narrow safe unit normalizer renders known labels (`szt.`, `m`, `m²`, `m³`, `kg`, `l`, `opak.`) or bounded trusted labels and suppresses missing/unsafe labels. Branch and central stocks share the same **product-level** verified unit; stock zero, null and central/branch separation are unchanged. Legacy Room v12 rows migrate to `stockUnit=NULL` without rewriting history.


## Advisor location model-hint equivalence (2026-10-10)

The Android location tool first computes the full authorized canonical OBI market set from the current user message, independently of the model. For a restricted scope, model `locations[]` hints are resolved using **the same canonical city alias table and deterministic Polish locatives** as user-scope parsing. Bare verified market IDs, exact trusted addresses and explicit market references such as `OBI 003` resolve to canonical branch IDs; unsupported/contradictory hints reject before any inventory HTTP. Equivalent hints do not narrow or enlarge the user-authorized subset. Explicit broad all-other queries keep their pre-existing 61-market semantics, with an empty hint list. No model-driven branch permission is introduced.


## Shared product-image preview (2026-10-10)

The existing VerifiedProductThumbnail provides click-to-preview for Advisor cards, restored conversation cards, exact manual-search results, and enriched search-result thumbnails, for OBI and KWANT. Only Coil-successful thumbnails are clickable; a null or failed primaryImageUrl leaves no interactive empty target. A full-screen in-app Compose Dialog opens the original trusted primaryImageUrl using a second AsyncImage constrained to the expanded display area and ContentScale.Fit, preserving Coil caching and memory-bounded decoding without enlarging a thumbnail bitmap. Tapping anywhere or Android Back dismisses the preview; loading/error states are presentation-only. No external navigation or changes to provider image URL validation, persisted data, or product actions.


## PR #102 — location-scope completeness and fail-closed lists (2026-10-10)

Android scope authorization uses one constrained parser for user-mentioned city lists and market-ID lists. It preserves commas; contextualized IDs after `market/sklep/oddział/OBI` may be joined by comma, `i` or `oraz`. City references may appear inside a longer sentence and can include the canonical directory's multi-word names and deterministic inflections such as `Nowym Sączu`. Parsing resolves **every** explicitly named location before any inventory HTTP; unknown/malformed mixed scopes are typed `unknown_location` with zero HTTP. Generic inventory phrases remain broad all-other, model hints are nonauthoritative, and product dimensions are not location IDs. The existing explicit-intent guard, 61-market scans, service/HTTP batch limits, cancellation, partial coverage and 45-second deadline remain unchanged.


## PR #102 — 2026-10-10 location-scope parser regression gate

Scope parsing only treats a **complete named-locality phrase** as a city request: a bare city chain after a location-check command, or a terminal `w/we/dla + city chain`. Conjunctions `i`, `oraz` and commas are validated together, and every locality must resolve against the canonical OBI directory before inventory HTTP. Generic stock, quantifier and store noun phrases (e.g. `Sprawdź stany w innych marketach`) are not interpreted as unknown cities. Model `locations=[]` cannot bypass user-scope authorization. Original batching, timeout and partial coverage are unchanged.


## PR #102 — 2026-10-10 focused location authorization hardening

The location-inventory tool has an **Android-side, operation-specific authorization guard** independent of model tool choices. Routine product advice, replacements and installation questions do not trigger `find_product_locations` inventory reads even if the model hallucinates a call. City/market restriction parsing is based on current **USER** wording and the canonical directory, not model arguments: unknown explicit cities (including `Sprawdź w Tarnowie` with `locations=[]`) fail typed `unknown_location` with zero HTTP. A free three-digit product dimension (e.g. `listwa 100 cm`) is not a store reference; market IDs only match contextualized `market/OBI/sklep/oddział <id>` mentions. Broad explicitly authorized other-market requests still check 61 stores in 4 sequential service reads and up to 7 requests. The total OBI scan has a **45-second wall-clock budget** (injectable in tests), in addition to PR #101's per-request 15-second timeout. On that deadline the coroutine cancels the in-flight HTTP request; already confirmed results remain trusted and unchecked markets remain unknown with partial coverage. Genuine user/job cancellation propagates. Chat presentation reconstructs `all other markets — incomplete` from canonical requested IDs even though evidence quality is `partial`.


## Advisor product locations PR 2 — opt-in model tool and trust gate

The Worker advertises **one** new function `find_product_locations(providerId, productId, locations[])` only for Android requests carrying `X-Taksula-Locations-Capability: 1` (START/MESSAGE/CONTINUE, including multipart). The existing v1–v5 protocol numbers and v2/v3/v4/v5 discovery function shapes remain unchanged. Older Android clients omit the header, so a **Worker-first deployment** does not expose an unfamiliar function to them. On explicit opt-in the Worker keeps the existing discovery function and web_search alongside the location function; all tool calls retain one strict continuation per step and the Android 3-logical-local-call budget. A location invocation costs one logical call, even when OBI performs two capped batches. Legacy v1 is intentionally not enabled. Existing attachment v4/v5 payload/signatures and trace/usage accounting stay unchanged.

Android reads recent (up to three) assistant messages' persisted verified product cards from **the current conversation only**, passed to AdvisorController as immutable per-turn evidence. The current turn's exact verified snapshots remain separate. Model-supplied productId must equal a uniquely user-resolvable trusted identity, not arbitrary text, an ID from another conversation, or another provider. When prior cards are ambiguous, return typed `ambiguous_product`; a new identifier must first be verified via existing product discovery. Historical evidence never creates a fresh `productRefs` card, because the final card filter still uses only current-turn verified snapshots.

For OBI, a separate tool authorization uses the current USER text plus the canonical 62-market `ObiProductProvider.branches()` directory, never relying on untrusted model-selected market IDs or the single-branch `BranchResolver`. **An explicit multi-location availability request without a narrower location restriction authorizes all other canonical OBI markets (61 when the selected market is one of 62)**; the currently selected market is excluded unless expressly requested. An explicitly named city expands to **every** canonical market in that city (including multiple Kraków markets), while an explicitly named exact market restricts the operation to that market. An unknown/unrepresented city is not fabricated (e.g. Tarnów is absent from the current directory), and contradictory untrusted hints are rejected. The model sends an empty `locations[]` for nationwide other-market checks and is not responsible for enumerating IDs.

The Advisor orchestrator performs **sequential `ProductLocationsService.read` calls in canonical directory order, with at most 20 markets per service call**. Within each read the unchanged PR #101 OBI adapter performs at most two HTTPS requests of at most ten market IDs each. Thus checking 61 other stores requires **four service calls and seven provider HTTP requests**, but consumes **one of the Advisor's three local function calls**. No automatic inventory lookup is added to ordinary product discovery. Per-batch failures are retained as `partial` / `unknown` with full requested and missing canonical IDs; each missing market appears with `stock=null`, never fabricated zero. On full success coverage is `all_other_locations` (not `all_public_locations` when the selected market was excluded). Presentation ordering puts positive quantities ahead of confirmed zero and unknown, without altering transport order. One conservative timestamp (`verifiedAtMillis`) represents the earliest successful batch; unavailable outcomes omit a verification timestamp. Results contain bounded canonical IDs/names and no raw provider response and are validated against the 16 KiB continuation cap. The model's brief answer is followed by an Android-rendered, deterministic text list of **every checked market** (ID, canonical name/address, positive/zero/unknown state) from the trusted typed result. This is needed because the model's short final-answer budget cannot itself list 61 locations. No new Compose screen or HTTP call is introduced by that rendering. KWANT remains fail-closed on unverified `extended` value.

KWANT is wired through `ProductLocationsService` but production returns `UNVERIFIED_REQUEST_CONTRACT` without any departments request: repository research retained `extended` query NAME only, not value. The Worker must relay the typed unavailable result honestly. Do not advertise working KWANT live multi-branch availability, infer `total_stock` semantics or use synthetic `extended` values. This is a documented acceptance limitation pending independently evidenced contract reproduction.

No Room migrations, automatic inventory reads, provider selection/WorkingProfile mutations, UI redesign, changes to regular `find_products` discovery or changes to PR #101 adapters. Strict 16 KiB continuation budget, sanitized canonical identity and location fields, bounded response sizes and read-only network handling apply.


## Read-only product locations foundation (PR 1 of 2; not Advisor-exposed)

`ProductLocationsService` is an Android-only provider-neutral dispatcher keyed by existing `ProductRef.providerId`. It returns `ProductLocationsResult` with canonical `ProviderBranch` identities, nullable integer stock, verification time, explicit `LocationCoverage` (requested, returned, missing, attempts/failures), and an optional independently verified `centralStock`. Zero is a known physical-location quantity; null and missing canonical IDs remain unknown. No missing location rows or aggregate totals are synthesized. Unsupported/invalid/unavailable are typed failures.

The two dedicated adapters do **not** change `ProductProvider.search/lookup`, `find_products`, Advisor, Worker, UI, Room, WorkingProfile or existing selected-store state. Neither adapter is called during regular discovery or during Advisor turns in this PR. The future PR 2 owns trusted prior-product authorization and model tool routing; an arbitrary model-chosen product ID is not authorized by the mere existence of these transport helpers.

- **OBI:** exact seven-digit OBIK; only IDs from the current `ObiProductProvider` / canonical `OBI_STORES` directory. One HTTPS `GET https://www.obi.pl/api/pdp/v1/stock/{OBIK}?storeIds=...` for up to ten requested stores, comma-delimited. **Each `ProductLocationsService.read` call** accepts at most **20** unique store IDs, issuing at most **two** ten-store batches; this per-call budget is an **application cost limit**, NOT live evidence of OBI's provider maximum. The new PR 2 orchestrator may perform four bounded, sequential reads for 61 other stores without changing this adapter contract. The live research proves the 10-ID B subset request shape, **not** a one-shot 62-store request. JSON list entries must have exact `storeId` and nonnegative integer `availableQuantity`. Duplicates, foreign IDs, bad quantities and untrusted paths reject their batch. A failed second batch leaves the first trusted subset with `PARTIAL` coverage; omitted IDs remain unknown.
- **KWANT:** `GET https://services.kwant.net.pl/api/front/products/{id}/departments` was observed with an `extended` query **name**, but the sanitized research evidence contains **no value**. The production constructor deliberately has no value and returns `UNVERIFIED_REQUEST_CONTRACT` **before any HTTP or directory lookup**. It must **not** guess `true`, `1`, an empty value or omission. A non-default, explicitly supplied verified query value enables the isolated one-request/one-product adapter and its deterministic fixture tests, but must not be configured for production until the exact request is independently evidenced. It accepts only canonical numeric `department_id` and nonnegative integer `stock` in root `list`, rejects duplicates/conflicts, reports partial directory coverage, and never treats `total_stock` as branch or central stock. `centralStock` may only come from a separately verified matching `ProviderProduct`.
- Both adapters use dedicated JSON-only read-only OkHttp GETs, exact HTTPS-origin/path allowlists, redirects disabled, no cookie/session-switch operation, bounded 64 KiB response bodies, 15-second call budget and coroutine-aware cancellation. No raw payload or secret logging.

**Production status:** OBI adapter implemented with deterministic transport/parser tests. KWANT parsing, one-shot logic and safety boundary are implemented and fixture-tested, but **live-reproducible production transport remains blocked by the missing verified `extended` value**. Thus full two-provider acceptance is **not** claimed. No re-probing was done in PR 1.


## Provider branch resolution and one-off lookup

`ProviderProduct` carries nullable `centralStock` independently from the
existing selected-branch `stock`. KWANT central and branch stock remain
separate facts; OBI leaves `centralStock` null.

Android owns one provider-neutral `BranchResolver` over
`ProviderBranch(branchId, name, address)`. It normalizes case, whitespace,
Polish diacritics and basic street forms. Exact branch IDs and bounded
current-branch aliases are always eligible. Natural city/name/street/address
metadata participates only when the USER text contains an explicit branch or
store location phrase such as `OBI ...`, `Kwant ...`, `w markecie ...`,
`w hurtowni ...`, or `na magazynie ...`; the matching metadata must occur
adjacent to that intent marker after only small location connectors such as
`w`, `na`, `ul.`, or `al.`. Incidental product text therefore cannot
authorize a branch merely because it contains a city or street token. No
edit-distance guessing is used. OBI branches come from one canonical static
directory verified against OBI Poland's official customer-relations market
list; KWANT continues to expose its live public branch directory.

The current user message, not the model's `requestedBranch`, authorizes a
turn-scoped branch. A unique explicitly scoped natural reference may rewrite
only the local tool branch ID for that call. Current-branch aliases such as
`u nas` and the bounded Nowy Sącz/Sączu variants resolve back to the
conversation branch independently of the natural-location gate. Ambiguous and
explicit unknown locations fail closed. Multiple separately stated exact IDs
may still be queried one at a time using the tool hint only to choose among
those already user-authorized IDs. The conversation WorkingProfile and
provider/branch passed to subsequent continuation remain unchanged.

## Goal

Taksula should remain a small native Android application. Its intended data flow is:

```text
UI / Compose
    ↓
Repository / domain layer
    ↓
HTTP/session transport
    ↓
OBI-specific payload parser
```

The product lookup core implements repository, HTTP/session transport, and OBI-specific parsing. The unified search controller runs blocking repository work on `Dispatchers.IO` and exposes only application UI state to Compose.

## Boundaries

- **UI / Compose** renders state and reports user intent. It must not issue HTTP requests or understand OBI payload details.
- **Repository / domain layer** coordinates search rules and exposes application-oriented results without leaking transport or page-format details into the UI.
- **HTTP/session transport** will retrieve public data and report transport outcomes. It must not interpret OBI payloads.
- **OBI-specific payload parser** will translate retrieved payloads into validated data. Parsing failures must be explicit; missing or malformed values must never be invented.

Transport and parsing must remain isolated from the UI. An OBI website change should require changes in its transport/parser boundary and tests, not a UI rewrite.

## UI localization boundary

Android presentation text uses normal platform resources: Polish is the default `app/src/main/res/values/strings.xml` set and English lives in `app/src/main/res/values-en/strings.xml`. Compose resolves current UI chrome through `stringResource(...)` and formatted resource placeholders.

Application/controller state remains locale-independent. Advisor and product-search failures are represented by stable error enums and are translated only at the Compose boundary; the manual-search saver stores the enum value rather than rendered localized text. Persisted conversations, USER/ASSISTANT role constants, product facts, OBIK/store identifiers, URLs, API paths, JSON fields, tool names, OpenAI schema, database schema, and diagnostic internal traces remain technical contract data and are not localized.

Locale selection uses AndroidX AppCompat per-app locales. `MainActivity` is an `AppCompatActivity`; selecting Polski or English calls `AppCompatDelegate.setApplicationLocales(LocaleListCompat...)`. AppCompat's `autoStoreLocales` metadata provides supported persistence below Android 13, while Android 13+ uses the platform per-app locale service and the manifest `localeConfig` advertises only `pl` and `en`. No custom locale Context wrapper or SharedPreferences language store exists.

The UI locale remains independent from advisor/model language. No locale field is added to proxy requests or persisted conversations, and existing USER/ASSISTANT text is never translated as a side effect of changing Settings.

## Problem reporting boundary

Problem reporting is local and user-controlled. There are two entry points: an exact persisted ASSISTANT-message report from the advisor transcript and a general report from Settings. The UI carries a nullable persisted message ID only so the report flow can re-resolve the authoritative Room message before generation. Web-source persistence is independent of reporting; normalized sources are not automatically copied into problem-report evidence.

A contextual report resolves the requested conversation/message from `ConversationRepository`, confirms that the message belongs to that conversation and has role `ASSISTANT`, and reads its persisted verified-product snapshots. With conversation inclusion OFF (the default), no unrelated transcript is included. With inclusion ON, context is sliced from the beginning through the reported response only. General reports include the current persisted conversation only after explicit opt-in. Draft text is not part of report evidence.

Report generation never calls the proxy, OpenAI, OBI repositories, or the live diagnostic probe. If the existing OBI recorder is already enabled and has sanitized captured operations, the form may expose those diagnostics as a separate opt-in checkbox. The checkbox defaults OFF, and the existing public `report()` output is appended only when the user explicitly selects it. Otherwise the report records that diagnostics were not included.

The report formatter receives only bounded technical metadata, persisted message/product evidence, the stable report category, and the user's description. It deliberately has no access to `lastResponseId`, OpenAI/tool IDs, secrets, raw responses, cookies, HTML/Nuxt, accounts, network address, or location.

TXT files are UTF-8 and temporary under `cacheDir/reports/`. Before creating a new file, previous files in that dedicated directory are removed. A non-exported `FileProvider` exposes only `reports/`; sharing uses `ACTION_SEND` + `EXTRA_STREAM` + temporary read permission and the Android chooser. `ActivityNotFoundException` while opening the chooser is handled locally: the fresh file is deleted and a bounded UI error is returned. No report is auto-sent, persisted in Room, or uploaded to a report backend.

## AI assistant boundary

The Android app now exposes two separate top-level paths. WYSZUKIWARKA continues to use the existing local OBI flow directly and never invokes the proxy. DORADCA uses this assistant path:

```text
User / Compose
    ↓
AI interaction controller
    ↓
Cloudflare proxy
    ↓
OpenAI
```

The Cloudflare Worker is the active credential and OpenAI mediation boundary for advisor requests. The OpenAI API key exists only on the proxy side. The Android app must never embed it.

When the model requests the single high-level local OBI tool, Android owns execution:

```text
AI requests local OBI tool
    ↓
existing Android OBI repository/search/lookup
    ↓
requested-store verified structured result
    ↓
compact result returned to AI
```

`find_obi_products(storeNumber, queries[])` is executed by Android using the existing `ProductSearchRepository` and sequential exact `ProductLookupRepository` lookups. One invocation targets one store, accepts 1–5 query groups, and enforces `sum(query.limit) <= 5`, so a batch can cover several customer-kit categories without increasing the existing maximum of five exact product lookups per local call. Results remain grouped by requested query with bounded `verified`, `not_found`, or `unavailable` status. A failed group never discards successful verified snapshots from other groups; no failure is converted into a fabricated product. Only one store context plus grouped verified bounded records `{obik,name,brand?,shortDescription?,technicalFacts[],stock,price}` are sent back to the proxy. The proxy does not scrape OBI, does not contain an OBI parser, and does not become authoritative for OBI data. Android owns the canonical store allowlist and validates membership before any OBI HTTP request.

OpenAI must receive only compact structured results produced by the app. OBI HTML, Nuxt payloads, cookies, and parser internals must not be forwarded to OpenAI or moved into the proxy.

The proxy now exposes a public `GET /health` plus authenticated `POST /v1/agent/start`, `POST /v1/agent/message`, and `POST /v1/agent/continue`. The AI endpoints require the shared Internal-Testing `TOWAROWNIK_APP_TOKEN`; the OpenAI credential remains Worker-only as `OPENAI_API_KEY`.

The Worker calls the OpenAI Responses API with a centralized `gpt-6-luna` configuration, low reasoning effort, the final server-controlled Taksula advisor instructions, a bounded output budget, the strict application function `find_obi_products(storeNumber, queries[])`, and the Responses built-in `{type:"web_search"}`. Tool choice is automatic and `max_tool_calls=1` bounds built-in use per Responses request; Android independently retains a three-local-function-calls-per-USER-turn infrastructure safety guard.

When the model returns that function call, the Worker validates the tool name and arguments and returns a normalized `tool_request` envelope to Android. Android executes the existing OBI search/exact store-`075` lookup and later sends only the compact verified result to `/v1/agent/continue`. The Worker continues with `previous_response_id` and a matching `function_call_output`, resending the stable server-controlled instructions/tool declaration. It does not store conversation state in Cloudflare storage.

The proxy normalizes OpenAI output to either `tool_request` or a structured final `answer`. Final answers are constrained by a strict JSON schema with exactly `text` and `productRefs` (maximum five `{storeNumber,obik}` references). The model supplies no card price, stock, name, URL, or verification timestamp. Raw Responses payloads, reasoning content/items, internal instructions, and upstream error bodies do not cross into Android. The only usage information crossing this boundary is a separately validated bounded metadata object containing model/request type, token counts, estimated USD cost, and pricing version; malformed telemetry is dropped without dropping the core answer/tool request.

On Android, `AdvisorProxyClient` is the isolated authenticated transport boundary. `AdvisorController` owns one USER turn and may execute at most three local OBI tool calls during that turn. A fourth requested batch performs zero OBI work, returns `local_tool_limit_reached`, removes `find_obi_products` from the final continuation, and finishes from already verified current-turn snapshots plus general guidance. The allowance resets for every new USER message. It never automatically retries a completed proxy request. `FindObiProductsTool` is the single Android OBI tool adapter; there is no generic agent/plugin framework or per-store tool class.

Conversation continuity is local-first:

```text
Room conversation.lastResponseId
        ↓
POST /v1/agent/message
        ↓
Responses API previous_response_id
        ↓
tool loop if requested
        ↓
FINAL answer responseId
        ↓
transaction: ASSISTANT message + replace lastResponseId
```

The first USER turn has no previous response and uses `/v1/agent/start`. Later turns use the last successfully completed final answer response ID. Tool request response IDs are used only transiently for `/continue`; call IDs are never persisted. A new conversation starts with no response ID.

Room schema v12 contains `conversations`, `messages`, and normalized child relations for products, sources, search actions, and attachments. The v9→v10 migration introduced `message_attachments` with one row per USER message; v11→v12 preserves each historical row at `position=0` and changes the primary key to `(messageId, position)`. Each USER message now stores 0–3 ordered attachment metadata rows with unique opaque `localId` values and cascading deletion. Room stores only IMAGE/PDF category, display name, MIME type, private-file identifier, byte size, optional image dimensions, and creation time—never attachment bytes or an absolute path. Product/source/search-action semantics remain unchanged.

Persisted advisor attachment bytes live only under the app's private files directory behind `AttachmentStorage`. Validated content is copied under an opaque generated identifier; resolution and deletion cannot escape that directory. Android rejects any individual source above 16 MiB before copying and enforces a 24 MiB aggregate limit for attachment turns separately from the Worker's multipart envelope check. Conversation deletion and the existing 30-day retention cleanup collect all related attachment IDs from Room, delete the conversation transactionally, and then best-effort delete the private files. Missing or undeletable files do not block Room cleanup or history loading. File content is not added to diagnostics or problem reports, and no background cleanup worker is introduced.

During a USER turn, every successful exact `LocalProduct` result yields two deliberately separate views: a compact tool result with one `storeNumber` plus `{obik,name,brand?,shortDescription?,technicalFacts[],stock,price}` products sent to OpenAI, and local-only verified snapshots `{storeNumber,obik,name,stock,grossPrice,productUrl,verifiedAt}`. `productUrl` and `verifiedAt` are never sent upstream. `AdvisorController` accumulates snapshots only inside the current `runTurn`, keyed by `(storeNumber, obik)`, and resolves final model-selected `productRefs` only against that set. Unknown references are ignored; they never cause a lookup. Requested ordering is retained and duplicate composite references cannot create duplicate cards. Broad advisor searches also have a separate Android-only metadata path: when a query uses `limit > 1` and OBI's `reportedTotalCount` exceeds the bounded candidates requested/returned, `FindObiProductsTool` emits an `AdvisorSearchAction`. `AdvisorController` deduplicates these actions per USER turn by store + normalized query. They are never added to the Worker continuation or model context and remain independent of verified snapshots/productRefs. Exact product parsing may additionally attach one nullable `primaryImageUrl` to the local snapshot path. The parser reads only the first structured Product JSON-LD image from the already-downloaded product page and accepts it only when it is HTTPS on `bilder.obi.pl`. This URL is never copied into `AdvisorVerifiedProduct` or the Worker protocol.

A trailing USER without a committed ASSISTANT means the turn was interrupted. The repository collects all attachment `localId` values belonging to that trailing USER before Room recovery removes the message. Ordinary interrupted-turn cleanup best-effort deletes those private files after the recovery transaction; failed-send recovery instead durably reclaims the ordered attachments for the composer before removing the USER row. Recovery restores the USER text to `draft` and leaves the prior final `lastResponseId` unchanged. Files belonging to other messages are not scanned or deleted, and no proxy/OpenAI request runs during recovery. Completed assistant text, selected verified snapshots, and the final response ID are committed in one Room transaction.

Conversation history search is local SQL substring matching over title and message text. It has no AI/network dependency. Conversations are ordered by `updatedAt` descending. A conversation represents one customer case: a new customer or new problem should normally start with “Nowa rozmowa”, while follow-up questions for the same case continue in place; this is not enforced as a hard conversation-length limit.

On normal app startup, Room deletes conversations whose `updatedAt` is strictly older than `now - 30 days`; rows exactly at the cutoff are retained and message/attachment-metadata rows disappear through CASCADE foreign keys. Related private attachment files are then deleted best-effort from the dedicated attachment directory. Cleanup is local-only and has no WorkManager, proxy, OpenAI, OBI, or other network dependency. If a remembered active conversation was removed by retention, the app opens a fresh empty chat instead of restoring stale saved UI state.

Each history entry can also be deleted manually after confirmation. Deleting the active conversation first invalidates the advisor generation token and cancels/joins its active request, then removes the Room row and returns to a fresh empty chat. Final callback handling checks the generation/conversation guard before database mutation, and the DAO completion transaction requires the conversation row to still exist, so a stale completion cannot recreate a deleted conversation.

Persisted product cards are historical point-in-time snapshots, not current OBI evidence. They render below the ASSISTANT message with their local verification timestamp and trusted persisted URL, with no proxy/OBI request merely to reopen history, restart the app, or open the drawer. A later USER question about current availability, stock, price, or currently suitable products must run `find_obi_products` again and receives independent current-turn snapshots.

On cold start the most recently updated useful retained conversation is restored when practical. Switching/new conversation cancels active work and generation-guards stale UI callbacks.



## Multi-store authorization boundary

The conversation captures the active WorkingProfile provider/branch at creation; the global fallback is OBI 075 only when no saved profile exists. Historical product snapshots retain their original branch. Current USER text may authorize one-off discovery in another uniquely resolved canonical location without mutating the conversation profile.

The current policy is the INC-006 R1 boundary described above: default to the saved profile without a USER place selection; otherwise resolve city/street or explicit branch references from the active provider directory and reject unknown/ambiguous/conflicting scope. Arbitrary three-digit tokens inside products or measurements do not authorize inventory. Authorization precedes every local provider lookup. A model hint may select only within an entirely explicit USER-named numeric comparison set, never outside it.

Room v2→v3 adds `conversations.storeNumber` and `message_products.storeNumber` with deterministic historical default `075`. Verified product identity is therefore `(storeNumber, obik)`, both in current-turn dedupe and persisted history.


## OBIK lookup flow

`ProductLookupRepository` accepts a seven-digit OBIK plus a supported `storeNumber` (default `075`) and validates the store against the canonical allowlist before HTTP. `ObiHttpClient` calls `/api/disc/store/change?storeNumber={storeNumber}&redirectUrl=/p/{OBIK}` with an in-memory cookie jar. OkHttp follows the normal redirect to the product route on that same client, so the response page was produced in the selected-store session. Live Android probing confirmed that this existing URL/redirect/session flow is valid, but OBI/CloudFront rejects the default native/non-browser User-Agent with synthetic empty 404 responses. Production OBI requests therefore apply one centralized browser-compatible HTML navigation profile: a fixed synthetic Android Chrome-style User-Agent, HTML Accept, and Polish Accept-Language. The UA is a compatibility string and does not represent the user's installed Chrome. No bootstrap request or canonical-product prelookup is performed. Non-2xx, empty, and transport responses become explicit unavailable results; the client does not retry.

`ObiPayloadParser` extracts the `__NUXT_DATA__` script as JSON and resolves Nuxt's flattened references. It selects only an object whose product identifier matches the requested OBIK. Product identity may be supplemented from Product JSON-LD; canonical-link markup is a URL fallback. It does not scrape visible price or availability text.

Current live OBI Nuxt payloads wrap product references in `Ref`/`ShallowRef` entries. The decoder unwraps only these confirmed wrapper types while retaining the existing flattened-reference rules. Product identity accepts the current `skuId` field as well as legacy identifiers.

For the current contract, the matched product's selected store is `product.store.information.storeId` (or `storeNumber` if present). Local stock comes only from `product.store.articleData.stock` and local price only from `product.store.articleData.pricing.grossPrice`. Seller stock/pricing and `fallbackPricing` are never substitutes. The current `articleEanEcms` field may decode to a one-element array containing the EAN; the parser accepts either a direct scalar or exactly one non-blank scalar value, and does not guess when multiple values are present. The historical sibling `selectedStore` + product-local `stock`/`pricing` shape remains a compatibility fallback for deterministic legacy fixtures. A parsed integer stock of `0` is confirmed zero. An absent, negative, or unparseable stock is `null` (unknown), and absent/unparseable local price is also `null`.

## Unified search flow

Input classification is explicit: exactly seven digits are an OBIK; numeric GTIN/EAN lengths 8, 12, 13, or 14 are EAN input; other non-blank input is text; blank or unsupported all-numeric lengths are invalid.

There are now two intentionally separate search-result capacities over the same OBI transport/parser rules. The existing `ProductSearchRepository.search()` path remains capped at five candidates and is the only search path used by the advisor/local AI tool. The human-only `searchManual()` path asks the parser for at most 25 recognized product links from the same already downloaded HTML and also preserves OBI's reported total result count. The UI reveals those parsed candidates in five-item chunks. The advisor parser now preserves the same OBI-reported total alongside its bounded five-candidate subset. That count is trusted only as a search-result count, never as store stock or verified availability. When eligible, the persisted advisor action opens the existing manual search with the exact historical query and store; manual search still loads at most 25 candidates and reveals them in five-item chunks. The reported total may be larger than the parsed candidate list; the UI never invents additional candidates and no OBI pagination HTTP contract is assumed.

OBIK continues to use direct exact product lookup without a candidate list, now against the active selected store. EAN and text queries use OBI's public `/search/{query}/` route. `ObiSearchParser` reads product links structurally, preserves their page order, deduplicates by OBIK, and returns at most five candidates. A canonical product URL is also accepted as a single search candidate when OBI redirects a search directly to a product page.

Text search always requires user selection before product lookup. Multiple EAN candidates also require selection. A single EAN candidate is opened automatically only after the existing product payload confirms that its EAN equals the user's query; otherwise the candidate remains selectable instead of being guessed.

An explicit empty-search state or ordinary HTTP 404 maps to not found. A narrow transport safeguard excludes the confirmed infrastructure signature—HTTP 404 with an empty body, `Server: CloudFront`, and `x-cache` containing `Error from cloudfront`—from business not-found classification; that case follows the existing server/network failure path. Unrecognized or changed search structure maps to a data failure, never to not found. Selecting a candidate runs exact lookup for the active selected store, so local stock and local gross price keep the same data rules.

## Temporary OBI diagnostics

A temporary in-app engineering diagnostic mode observes the existing OBI integration without changing its URLs, headers, redirect policy, cookie behavior, or parsing decisions. It is OFF by default and is opened from **Drawer → Settings → Diagnostics**. The former hidden long-press on the app title has been removed. State and history are process-session only; no persistence dependency is used.

`ObiDiagnosticRecorder` is bounded to the last 10 operations. An OkHttp network interceptor observes actual request headers, redirect hops, safe response metadata, and cookie names. All recorded URLs are sanitized: scheme/host/path are retained, only explicitly safe query values such as the requested `storeNumber` remain visible, and other query values are replaced with `REDACTED`. Cookie values are inspected only transiently to classify per-hop store evidence as `true`, `false`, or `unknown`, then discarded; they are never stored or printed. Response bodies are never persisted. Successful responses are reduced immediately to safe signatures, while final 4xx/5xx responses use a bounded diagnostic preview for the same signatures. The reported body-size field is `decodedBodyUtf8Bytes`, meaning UTF-8 bytes of the decoded diagnostic text, not raw HTTP payload bytes.

Product and search parsers append diagnostic stages while keeping their existing decisions unchanged. Repositories append error-classification traces and then finalize each diagnostic operation. Diagnostic mode is infrastructure for contract discovery, not product behavior.

Deterministic fixtures and CI prove code behavior against known inputs; they do not prove compatibility with live OBI. Live diagnostic reports must be reviewed before changing transport, session, redirect, parser, or not-found assumptions.

## UI and configuration

The application uses one AppCompat-backed Compose activity and state-based top-level surfaces; Navigation Compose is intentionally not introduced. The surfaces are Advisor, manual OBI search, Settings, and Diagnostics. The default surface is the advisor chat shell. Its top bar has a modal drawer action, centered Taksula title, explicit new-case action, and quick access to the independent full-screen Wyszukiwarka OBI. The drawer contains “Nowa rozmowa”, local persisted history/search, per-conversation confirmed deletion, and a visually separated Settings action pinned at the bottom regardless of history/search state. Opening Settings changes only the surface and closes the drawer; it does not reset the active conversation/draft or initiate proxy/OBI work.

Settings returns directly to Advisor. Diagnostics is entered only from Settings and returns to Settings. Settings contains General/Language, Help/Diagnostics plus disabled future Report problem, and About with resource-based `app_name`, current versionName/versionCode, plus a disabled future Privacy policy row. The placeholders are deliberately non-functional and contain no invented endpoint, URL, or legal text.

The advisor is now genuinely multi-turn within one local customer conversation. After a completed ASSISTANT answer the composer is enabled again; the next USER message continues from that conversation's stored final response ID. Submitted/completed messages persist with createdAt timestamps and keep the existing local HH:mm presentation. Simple Markdown markers are still normalized before storage/display. Starting or opening another conversation cancels active work, recovers any interrupted trailing USER to the old conversation's draft, and protects the new selection from stale callbacks.

The Wyszukiwarka OBI has its own Back surface and keeps advisor UI state intact. Visible candidates still use the existing exact-verification enrichment path; if that already-fetched exact product carries a trusted primary image URL, the enriched result card renders the same local thumbnail without any image-specific OBI request. It now has an explicit manual-search store context separate from the conversation-selected store: normal top-bar entry initializes it from the current conversation, while a persisted advisor “Zobacz więcej” action opens with its historical query/store and automatically starts the same `ManualSearchController` flow without mutating the conversation store. Completed manual-search state, typed search query, advisor draft/messages, and selected top-level surface use Compose saved state where practical so rotation does not unnecessarily erase the current screen. A reusable verified-product UI model/card receives only trusted Android-side exact lookup data and opens the existing `LocalProduct.productUrl` through the normal external browser intent.

`TOWAROWNIK_APP_TOKEN` is injected at Android build time through `BuildConfig`. Missing token configuration keeps compilation and ordinary search working; DORADCA fails locally before any network call. The signed Play workflow receives the token only from the matching GitHub Actions secret. The static token is only an Internal Testing abuse barrier and is extractable from an APK/AAB; it is not strong device authentication.

The application uses the normal Android resource system. No orientation is locked, so the UI must continue to adapt cleanly to portrait and landscape sizes. Navigation, dependency injection, persistence, and other frameworks should be added only if a concrete feature requires them.


## Multi-turn proxy continuation

The Worker exposes authenticated turn endpoints carrying the conversation-store snapshot: `/start` receives `{message,storeNumber}`, `/message` receives `{previousResponseId,message,storeNumber}`, and `/continue` retains the same `storeNumber` alongside tool output. Android cannot override model, instructions, tools, reasoning effort, token budget, or OpenAI URL. The Worker resends the stable server-controlled instructions/tool declaration and passes `previous_response_id` plus only the new user message to the Responses API.

Using `previous_response_id` avoids manually sending the complete local transcript on each turn, but previous context tokens in that chain are still billed as input tokens. No summarization or compaction is implemented yet.

Advisor transport is explicitly versioned at the Android/Worker boundary. Text-only OBI conversations use grouped v2 with `find_obi_products(storeNumber, queries[])`; text-only provider-aware conversations such as KWANT use v3 with `find_products(providerId, branchId, requestedBranch, queries[])`. Current Android attachment turns use provider-aware v5 with 1–3 files and `find_products`, including turns with only one file; legacy v4 remains accepted for older single-file clients. The initiating START/MESSAGE contract is retained through every CONTINUE in that tool-assisted turn. The Worker also retains explicit legacy v1 single-query compatibility, while an absent protocol marker remains pinned to grouped v2 compatibility.

Server-side instructions compose one provider-independent shared decision core, one shared evidence policy, the active provider appendix, the protocol contract suffix, and the attachment capability appendix for attachment-capable v4/v5 turns. Normal model knowledge answers ordinary technical/sales questions when provider evidence is unnecessary. Product selection uses the three-state model: unknown category clarifies without local lookup; known category with an unresolved decision-critical variant clarifies/resolves while safely browsing plausible verified candidates in the same turn without claiming a match; sufficiently specified product intent verifies immediately. Job/project requests still clarify materially different interpretations before concrete selection and do not automatically become shopping lists. Android independently retains `MAX_LOCAL_TOOL_CALLS_PER_TURN = 3` as a safety guard and resolves exhaustion through `local_tool_limit_reached`. Availability semantics remain strict: selected-branch stock `0`, null stock, `not_found`, and `unavailable` are distinct; KWANT central stock does not prove branch availability and online price scope is not branch/counter price. One-off cross-branch lookup is authorized only by Android's shared BranchResolver from the CURRENT USER turn and fails closed on ambiguous/unknown locations. Current-turn locally verified snapshots remain the only source of trusted product cards.


## AI usage observability v0.1

- The Worker reads Responses API `usage` independently from answer/tool normalization. START, MESSAGE, and CONTINUE are explicit request types. Usage failure is non-critical.
- Current pricing is centralized server-side under version `openai-gpt-6-luna-2026-09-27-v1`: USD 0.10/M ordinary input, USD 0.01/M cached input, USD 0.125/M cache-write input, USD 0.50/M output. Ordinary input is `input - cached - cacheWrite`; above 272,000 input tokens the full request uses 2× input-side rates and 1.5× output. Reasoning tokens are output detail only and are not double-counted. Historical GPT-5.6 Luna totals remain in the same per-model local usage state.
- Android records every successful proxy/OpenAI response immediately, plus USER-turn and first-tool-request counters. Cached and cache-write token totals remain separately auditable. Cumulative money is persisted as decimal text/BigDecimal rather than binary floating-point. Totals remain grouped by model; the UI model label comes from measured usage, not a duplicated Android model constant. Unpriced responses remain counted and cause the displayed known cost to be marked as a lower bound.
- AI usage state contains operational numeric metadata only; it stores no conversation text, prompts, tool queries/results, response/call IDs, raw reasoning, or secrets.
- NBP USD/PLN lookup is an AI Usage UI concern only. A successful rate stores rate, effective date, and refresh time; a 24-hour cache prevents per-turn calls. Failure returns stale dated cache when present and otherwise leaves PLN unavailable.
- User-configured Taksula budget stores a starting remaining USD amount plus cumulative-cost/unpriced-request baselines. It is explicitly not OpenAI account balance data. A below-USD-1 warning is edge-triggered and re-armed only by a budget reset/increase above the threshold.


## Richer verified OBI product facts v0.1

- Enrichment remains inside the existing exact product-page lookup. The same browser-compatible OBI response is decoded once; there is no second product fetch and no server-side OBI parser.
- Live contract evidence across OBIK 3496072, 6743009, and 7156243 confirmed product-level paths `brand.name`, `productDescription`, `productOverview[]`, `technicalData.productDetails[] {key,value}`, and `technicalData.dimensionsAndWeight[] {key,value}` on the selected-store product object.
- `LocalProduct` carries optional brand, optional normalized short description, and bounded `TechnicalFact(label,value)` items for the active exact lookup. Room snapshots remain unchanged and continue to persist store, OBIK, name, stock, price, trusted URL, and verification time only.
- Model-context bounds are brand 80 chars, description 220 chars, six facts, 60-char labels, and 100-char values. These tighter limits preserve the existing 16 KiB continuation request budget at the five-product tool maximum.
- Optional enrichment is fail-soft. Malformed or absent descriptive subsections are ignored independently; OBIK identity and selected-store structure remain required exactly as before.
- Store authority is unchanged: stock and gross price come only from `product.store.articleData`. Descriptive and technical facts are product-level data unless OBI itself explicitly states otherwise.
- Android serializes only bounded product context into `function_call_output`. Product URL, verifiedAt, EAN, raw HTML/Nuxt, cookies, diagnostics, and arbitrary JSON never cross to OpenAI. Final `productRefs` and the `(storeNumber, obik)` trust boundary are unchanged.


## Final Taksula advisor behavior v0.1

- Scope: product selection, building/finishing materials, tools, electrical/lighting, garden/home-improvement products, applications, installation guidance, compatibility, troubleshooting, alternatives, and helping retail staff answer customer questions.
- General technical knowledge is explicitly allowed. The advisor should answer first, remain practical/concise, ask at most one clarification when genuinely necessary, and continue helping even when a specific SKU detail is unconfirmed.
- Concrete product selection follows the shared three-state model. State 1 (unknown product category) asks one targeted clarification and performs no local lookup. State 2 (known category, decision-critical variant unresolved) keeps the parameter unresolved while asking one concise clarification or giving an actionable resolution step AND safely browsing plausible verified candidates in the same turn; those candidates must not be claimed compatible/correct until the parameter is resolved. State 3 (sufficiently specified) verifies immediately without unnecessary clarification. Provider browsing is never used to infer the missing decision-critical parameter.
- Assortment/browse intent is distinct from selection intent. Requests such as what is available, which variants/sizes exist, or to show options should use multiple verified results where useful rather than arbitrarily narrowing to the first result. If several verified products materially fit, Taksula must present/compare the useful alternatives or briefly explain why one was chosen; it must not silently imply that only one exists.
- The protocol-appropriate local tool (`find_obi_products` on OBI v2 or `find_products` on provider-aware v3/v4) exposes only a bounded subset, with at most five requested products per grouped call. Browse/assortment answers may state the bounded number actually surfaced but must not describe it as the complete assortment unless completeness is independently verified.
- Specific-product trust hierarchy: verified local-provider facts are authoritative for provider-owned product identity, selected branch/store stock, price scope/value, and supplied product facts; model knowledge may explain general principles around them but may not fabricate absent SKU-specific properties. KWANT central stock never substitutes for selected-branch stock.
- Rich OBI facts are selected for relevance rather than dumped or rewritten as marketing copy. Missing rich data means unknown, not negative.
- Clearly unrelated general-chat requests receive a short role redirect; borderline practical home-improvement topics remain in scope.
- Response language follows the user's current conversation language where practical. Android locale/persisted-history behavior remains unchanged.
- Structured output, productRefs, Android current-turn snapshot resolution, tool/product limits, multi-store authorization, GPT-6 Luna/low reasoning, and usage/cost accounting remain unchanged.
- Selective Responses `web_search` is now the only enabled OpenAI built-in tool; deep research, file search, image search, MCP, streaming, and background mode remain absent.


## Selective web search v0.1

- Search is supplemental, not a replacement for model knowledge or the Android OBI tool. General technical questions should normally use model knowledge; focused search is available for explicit relevant web requests, inherently current external information, or a missing important SKU-specific fact after OBI verification.
- Current OBI stock, price, store availability, and product-card eligibility remain Android-local authority. Search-visible OBI/manufacturer/retailer pages cannot override those facts, and web-discovered products cannot enter `productRefs`.
- Built-in configuration is `{type:"web_search"}`, `tool_choice:"auto"`, `max_tool_calls:1` per Responses request. Application function continuations still use `previous_response_id` and Android retains its three-local-function-calls-per-USER-turn infrastructure safety guard.
- Output normalization tolerates reasoning items, `web_search_call`, message items, and one application `function_call`. Only application function calls are returned as Android tool requests; raw web-search objects/actions/query metadata never cross the proxy boundary.
- Final web evidence is sourced exclusively from actual OpenAI `url_citation` annotations. Normalization accepts HTTPS only, deduplicates URL order, and caps six sources, 200-char titles, and 2048-char URLs. Android validates those bounds again.
- `message_sources` was introduced in Room v4; current Room v5 additionally adds only the local `message_search_actions(messageId,position,query,storeNumber,reportedTotalCount)` relation for advisor “Zobacz więcej”, with message FK cascade. Assistant text, verified product snapshots, sources, and final response ID commit in one DAO transaction. Historical v3 messages migrate with zero sources and history reopens citations without network access.
- Source UI is a compact clickable list below the assistant answer. Inline annotation offsets are intentionally not fabricated or remapped through the structured JSON text boundary.
- Web pages are untrusted external reference data. Server instructions reject page attempts to change role/tool/trust rules, disclose secrets, or exfiltrate unrelated conversation data.
- Usage telemetry adds `webSearchCalls`. The current pricing version adds USD 0.01 per completed search action to the existing token cost. Tool availability alone does not count as a call; unknown/unpriceable model usage remains unpriced.
- Existing advisor call timeout remains unchanged at 30 seconds because built-in search is limited to one action and no background/deep-research flow is introduced.

## Neutral product-provider foundation

The product layer now defines a minimal provider-owned identity boundary for future multi-provider work:

- `ProviderId` identifies a product source; the existing OBI Poland provider is `obi-pl`.
- `BranchId` is an opaque provider-owned branch identifier. The provider-neutral layer does not assume three digits or OBI store semantics.
- `WorkingProfile(providerId, branchId)` pairs the active provider and branch. The current default profile is `obi-pl` + the existing default OBI store `075`.
- `ProductRef(providerId, productId)` owns a product identifier explicitly, so the neutral boundary does not assume every provider uses OBIK.

`ProductProvider` intentionally exposes only the two operations already proven by the application: bounded product search and exact product lookup in a branch. `ObiProductProvider` is a thin adapter over the existing `ProductSearchRepository` / `ProductLookupRepository`; it translates between neutral provider types and the unchanged OBI-specific repositories, parsers, HTTP transport, error semantics, stock/price/image/URL fields, and current OBI search capacities. No OBI parser or network behavior is duplicated.

`ProductProviderRegistry` resolves providers by `ProviderId`. Production registers `obi-pl` and `kwant-pl`; unknown provider IDs fail explicitly and never fall back to another provider. KWANT `BranchId` is the public `department_stock_id`. The provider resolves the public branch directory metadata and builds the proven compact `departmentCookie` JSON object from `department_stock_id`, `department_stock_name`, `department_stock_postcode`, and `department_stock_street` before exact lookup.

Neutral product results distinguish price scope with `BRANCH` and `ONLINE`. Existing OBI exact prices map to `BRANCH`; KWANT's public storefront price maps to `ONLINE` and is not represented as a branch/counter/customer-specific price. Provider search totals are nullable: OBI supplies its existing reported total, while KWANT leaves the total unknown when the public payload does not expose an evidenced authoritative count.

KWANT exact lookup does not depend on a previous search. On a cold provider instance it requests the public dynamic product route using the stable numeric product ID (`/produkt/<id>`), lets the frontend canonicalize the route, then verifies the returned product payload still owns that ID. Search-populated canonical URLs are retained only as an optimization. KWANT URLs are accepted only when parsed as HTTPS on the exact `kwant.net.pl` host, with the expected `/produkt/<slug>` path for product requests.

This is a foundation boundary only. Existing OBI-specific UI, conversation persistence, advisor tool contracts, `LocalProduct`, OBIK fields, and store-number flows remain intentionally unchanged in this PR. They should move behind the neutral boundary incrementally when the second provider is introduced, rather than through a broad rename/rewrite now.



## Persistent working profile and conversation ownership

The app now persists one global neutral `WorkingProfile(providerId, branchId)` in app settings. The default remains `obi-pl / 075`. Provider and branch selection is explicit and manual; there is no model-driven or automatic provider switching.

Each newly created conversation captures the then-current working profile in Room and keeps that provider/branch identity for its lifetime. Room schema v7 adds `providerId` and `branchId` to conversations; the v6→v7 migration assigns every historical conversation to `obi-pl` and copies its existing OBI `storeNumber` into `branchId`, preserving history. The legacy conversation `storeNumber` field remains temporarily for the still-OBI-specific advisor transport.

`ProductProvider` now also exposes provider-owned branch discovery. OBI returns the existing supported store allowlist. KWANT resolves its public branch directory and exposes `department_stock_id` as `BranchId`, with branch name and public address metadata.

Manual product search resolves the provider from the current global working profile and performs search/exact enrichment through that provider. Product candidates and exact products keep provider-owned `ProductRef(providerId, productId)`. OBI presentation may label its identifier as OBIK; KWANT presentation uses article-number terminology. The advisor tool remains OBI-only in this iteration.


## Provider-aware advisor

Advisor execution is now owned by the conversation WorkingProfile.

- OBI conversations keep the established advisor wire contract on protocol v2.
- KWANT conversations use provider-aware protocol v3 with `providerId + branchId`.
- Protocol v3 exposes the neutral local tool `find_products`; Android resolves the exact `ProductProvider` through `ProductProviderRegistry`.
- The model may not choose or switch provider. The provider and branch are fixed by the saved conversation WorkingProfile.
- Provider-owned product references use `providerId + branchId + productId`.
- Room schema v8 adds provider-owned identity fields to persisted message products. The shipped v6→v7 migration remains unchanged; v7→v8 maps historical OBI rows from legacy `obik + storeNumber`. Price scope is not a new Room column; restored cards derive it from the persisted provider identity.
- KWANT branch stock remains branch-specific. KWANT public price keeps `ONLINE` scope and must not be described as a branch/counter/customer-specific price.
- Worker protocols v1/v2 remain accepted for existing OBI clients.

Concrete product intent now invokes the existing provider tool without waiting for a separate market-check phrase, while general technical questions remain advice-first and material selection ambiguity still triggers one focused clarification. OBI protocol v2 recognizes an exact seven-digit OBIK locally and routes it straight to `ProductLookupRepository`; search remains the discovery path for other input.

Protocol v3 query groups expose search text only. For KWANT, article number, EAN, name, manufacturer text, and other user-supplied identifiers remain search terms and every candidate is exact-verified; the provider's internal exact `productId` lookup is deliberately not model-controlled. The Android provider tool opens one execution-scoped branch lookup context before processing the group batch. KWANT resolves the selected branch directory entry once, then reuses that metadata/cookie for candidate lookups; no cross-execution cache or fallback branch is introduced.

## Android advisor attachment acquisition

The advisor composer owns an ordered list of at most three unsent `AdvisorAttachment` objects. Its compact in-composer `+` menu launches Android contracts rather than custom browsers: `PickMultipleVisualMedia(ImageOnly)` for multiple photos where supported, `OpenMultipleDocuments` constrained to PDF and images for files, and `TakePicture` into the dedicated `cache/advisor_camera_capture/` FileProvider path for one photo at a time. Individual replacement uses a single-document picker without discarding the other selected items. The store shortcut opens the existing WorkingProfile selector; it does not create or mutate a second store model.

Selected content crosses an Android-local `AttachmentImporter` boundary before joining the ordered pending list. PDFs are signature/MIME checked and copied with the existing 16 MiB per-file ceiling. Images are bounds-inspected, EXIF-oriented, proportionally reduced only above a centralized 4096 px maximum dimension, and re-encoded as metadata-free PNG when alpha matters or quality-92 JPEG otherwise. Previews read app-private files, never external URIs. A durable multi-file pending/staged/retired ownership journal records imports before publication and records replaced, removed, or abandoned files before cleanup. Replacing or removing one attachment preserves the others. New-conversation and conversation-switch actions invalidate outstanding import generations and release unsent ownership; startup reconciliation checks each marked localId against Room before attempting cleanup, without scanning unrelated files.

The composer uses multiline IME behavior: Enter inserts a newline and only the dedicated send button submits. Text-only transport is unchanged. Any ordered selection of 1–3 attachments, with or without accompanying text, uses protocol v5 and is checked against the 24 MiB aggregate cap before sending. Pending ownership remains durable until the USER message and all attachment metadata have committed in Room; Android then attempts to hand off all file IDs without deleting the private files. A failed ownership-marker release after a Room commit is non-fatal because startup reconciliation checks Room ownership. A failed send can reclaim all submitted files for the composer; a failed Room begin leaves its pending selection available.

## Advisor upstream failure diagnostics

Advisor-facing upstream failures retain the public `502 {"error":"upstream_failure"}` contract. Internally, the Worker classifies the failure at its source as network, HTTP 4xx/rate-limit/5xx, invalid upstream JSON, invalid Responses envelope, invalid local-tool call, or invalid structured final answer. The existing structured Cloudflare protocol log records only stage, protocol version, internal failure category, and upstream HTTP status when available. It never logs request/response payloads, messages, prompts, tool queries/results, product data, attachment filenames/bytes, IDs, tokens, keys, or cookies. No retry behavior is coupled to these diagnostics.

## Multimodal advisor transport (legacy protocol v4 and current v5)

- Legacy v4 extends provider-aware v3 with exactly one JPEG/PNG/PDF attachment. Current Android attachment turns use v5 for 1–3 ordered files; v2/v3 JSON text compatibility is unchanged.
- Android streams the app-private attachment file(s) through OkHttp multipart on the initial `/start` or `/message` request. `/continue` is always JSON and never contains attachment bytes.
- A tool-assisted turn keeps the transport contract selected by its initiating request: OBI text-only stays v2 + `find_obi_products`; provider text-only stays v3 + `find_products`; current Android attachment turns stay v5 + `find_products` (older v4 single-file turns keep v4). Provider identity independently selects the existing local retrieval implementation, so OBI attachment turns still execute `FindObiProductsTool`.
- Legacy v4 accepts one image/PDF file with its original 16 MiB and multipart limits. V5 accepts at most three JPEG/PNG/PDF files, each up to 16 MiB and together up to 24 MiB, and verifies MIME, file signatures, count, and declared multipart bounds before upstream forwarding.
- Images become Responses `input_image` data URLs with `detail=high`; PDFs become `input_file.file_data` data URLs. The Files API and Cloudflare R2/KV/D1 are not involved.
- Attachment content is untrusted user input. It may ground identity, codes, and specifications, but current provider stock, price, and availability retain the existing local verification boundary.

## Opt-in multi-attachment transport (protocol v5)

- Version 5 is distinct from v4. The current Android UI sends every 1–3-file turn through `attachments: List<AdvisorAttachment>` and v5. Legacy v4 single-file clients remain accepted; Phase 2 adds Room v12, the multi-file ownership journal, and multi-select UX without changing local product retrieval.
- V5 START/MESSAGE sends `X-Taksula-Attachment-Protocol: 5` and a JSON `payload` declaring `protocolVersion: 5`, followed by 1–3 repeated `attachment` file parts in stable order. The header chooses the larger pre-formData size guard; mismatched marker/payload versions reject. Legacy v4 retains its original single part and 16 MiB + 16 KiB declared-length guard; v5 permits at most 24 MiB of aggregate attachment bytes plus 16 KiB of multipart overhead, while every file retains the existing 16 MiB bound and MIME/magic validation.
- The Worker validates all parts before forwarding them together in one Responses USER message. Optional text comes first, then ordered high-detail `input_image` or named PDF `input_file`. One-file v4, v2/v3 JSON text, JSON-only `/continue` (including v5), provider-aware `find_products`, local verification authority, observability schema, and response chaining remain intact.

## Phase A1 — lightweight text attachment contract

Provider-aware v5 supports ten additional **text-only** extensions: `.txt`, `.md`, `.csv`, `.json`, `.xml`, `.yaml`, `.yml`, `.log`, `.ini`, `.conf`. Worker checks both the sanitized final filename extension and strict extension-specific MIME allowlist. A fatal UTF-8 decode rejects malformed sequences; C0/C1 control characters (apart from tab/CR/LF), NUL, and common binary-container signatures reject. Text parts have a conservative 1 MiB per-file cap and a separate 1 MiB aggregate TEXT cap per v5 turn; image/PDF bytes do not consume that text budget. Existing 16 MiB image/PDF per-file, maximum three attachments and 24 MiB aggregate multipart limits are unchanged.

Validated text is represented as **untrusted USER `input_text`** that quotes the sanitized filename and includes content as data, in the original position relative to USER message text, images, and PDFs. It never uses `input_file`/image handling; no file content is authoritative for provider facts, system instructions, or stock/price. Phase A2 enables TEXT selection through the existing Android document picker, reusing Room v12 generic ordered metadata and the durable multi-file pending/staged/retired ownership journal. Android maps only narrowly recognized document-provider MIME aliases (including generic octet-stream when a permitted extension and UTF-8 content validate) to exact Phase A1 wire MIME pairs. It prevalidates UTF-8, control/binary content and the 1 MiB per-file/aggregate text bounds before pending publication. Image/PDF picker behavior, Room tables, the product tool and JSON-only `/continue` are unchanged. Office/spreadsheet/archive binaries remain unsupported.

## Multi-attachment Android lifecycle (Room v12)

- Room 11→12 migrates the single `message_attachments` row into `(messageId,position=0)` without rewriting private file IDs; composite `(messageId,position)` keys persist up to 3 ordered rows, with unique localId and CASCADE cleanup.
- Android photo and document pickers support multi-selection where available; camera and per-item replacement import one item. Compact attachments remain above multiline text; Enter inserts a newline, and only the send icon submits.
- Composer saveable state, durable pending/staged/retired ownership sets, import generation guards and Room-first handoff prevent a candidate from crossing conversations. Failed turns reclaim all submitted files before deleting the interrupted USER row. Startup reconciliation never deletes files still owned by Room.
- USER history renders ordered image/PDF/TEXT attachments independently; a missing/corrupt item does not suppress other items. Android selects v5 for any 1–3 attachments (24 MiB total), while text-only v2/v3 stays JSON; v4 remains available for existing legacy clients.

## Sent attachment presentation and import isolation

Persisted USER attachment metadata remains the pointer to the existing app-private attachment file. Chat presentation resolves only that private file: images render as compact USER-bubble media above optional text, while PDFs and TEXT render as distinct compact document cards with the persisted sanitized display name, type and human-readable size. TEXT file contents are never rendered in the composer or history; a corrupted/invalid UTF-8 private file is unavailable rather than displayed. Missing files, size-mismatched files, or image decode failures degrade to a bounded unavailable-attachment message without exposing a path, URI, or content. Text-only history is unchanged.

Attachment acquisition is an ordered bounded (1–3-file) pipeline. Composer imports are serialized, and each selection receives a UI generation token. Explicit removal, starting a new conversation, or switching conversations invalidates older generations; a late successful import is discarded with its staged private file instead of overwriting the current selection. While import/preprocessing is active, the composer shows a compact loading row and disables the send action. Multi-file pending-to-Room ownership handoff is used for submitted attachments; Android sends attachment turns through v5 while preserving legacy v4 compatibility.

Image preprocessing keeps the existing 4096 px output bound and now chooses decode sampling against that bound directly, reducing peak bitmap memory for very large source images before the existing EXIF/orientation/scale/re-encode steps.


## Android Advisor trace correlation

The Worker-provided `X-Taksula-Trace-Id` is optional transport metadata and is not part of Advisor JSON. Android accepts only UUID-form values. START/MESSAGE begin a fresh USER turn and send no previous trace; once a valid trace is returned, the controller propagates it on CONTINUE requests until the turn finishes. A later valid continuation trace replaces the current value, while a missing/invalid one does not erase existing correlation.

The final successful trace is stored only on the persisted ASSISTANT message as nullable `advisorTraceId`; Room schema v11 adds this column with a non-destructive 10→11 migration. Historical rows therefore remain null. The existing problem-report path uses that persisted value for old assistant responses after restart, and proxy failure diagnostics may carry the best known turn trace. The trace is not rendered in chat and no separate telemetry storage or diagnostics UI is introduced.
