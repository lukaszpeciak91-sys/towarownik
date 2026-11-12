# TAKSULA — MODEL-FIRST CONTRACT V1 (Core V2, experimental)

Status: **isolated prototype / not accepted for production**. Base: main `818ae84bd894d07d29f695bd2fac23d00d0e9463`. PR #148 stays untouched. This document governs the new code in `proxy/src/core-v2.ts` only; it does not change the existing Advisor contract or route.

## Decision authority

1. The **OpenAI model** alone interprets the complete unchanged USER message, decides whether to answer from knowledge, use web search, ask for clarification or call the selected provider capability, and composes the final natural-language answer.
2. Deterministic Android/Worker logic does not parse Polish phrases or guess USER intent. It enforces externally granted provider and branch permissions, schemas, bounded resource use, response/call integrity and evidence provenance. A model argument is **never** a source of authorization.
3. Active provider comes from the trusted WorkingProfile, not model arguments. Independently approved branch IDs must be supplied by a trusted Android authorization decision. In this slice the bridge receives a canonical authorized branch ID; an unknown or unapproved ID cannot reach the provider.
4. Only the existing Android provider adapters may supply independently verified provider product/stock/price evidence. No web text or model statement can create a product card. Null stock is not zero. OBI and KWANT are isolated.
5. Every local outcome returns a structured function output: `success`, `not_found`, `unavailable`, `rejected`, `unknown` or `failure`. A recoverable failure is **not** an instruction to terminate the model turn.
6. The model's final technical prose stays model-authored. Citation URLs are extracted only from actual Responses `url_citation` annotations. Links and general technical explanations must not be misrepresented as provider verification.
7. Untrusted provider output is **data**, not system instructions. The bridge must sanitize product details before crossing into Responses; this prototype validates only the minimum typed identity and numeric fields and must not be treated as a final provider-proof format.
8. Limits are global to each USER turn: at most 5 Responses requests, at most 3 executed local provider calls, at most 5 products per local lookup, one function call per response. More elaborate multi-location requests are not yet accepted in this V2 slice. No automatic production retries.
9. The Worker remains unable to scrape or query providers. Actual provider execution must occur in Android via the existing `ProductProviderRegistry` / `FindProviderProductsTool` / `FindObiProductsTool` surface. The injected `V2Bridge` deliberately prevents an implicit backend rewrite.

## Isolated vertical slice

`runCoreV2(message, authority, bridge, apiKey, upstreamFetch)` uses the production model constant, production Responses endpoint and `tool_choice: auto`; it advertises only one provider-specific function plus native `web_search`, correlates local output with `previous_response_id` and `call_id`, bounds every turn and projects cards exclusively from validated bridge evidence.

The interface intentionally depends on a provider execution callback, not a new provider abstraction or a new NL parser. The Android live bridge and secure end-to-end transport are **not yet wired**. No `index.ts` route, Android UI/controller, auth, WorkingProfile, persistent conversation or CI release workflow imports this prototype. Thus it cannot silently affect production.

### Test matrix / evidence status

| Required scenario | Deterministic V2 coverage | Real-model proof |
| --- | --- | --- |
| Direct technical advice, no provider call | scripted tool-free answer | Not run |
| Manufacturer documentation via genuine web_search | native web_search tool offered; annotation extraction | Not run |
| Exact KWANT lookup | provider schema and isolation | Not run |
| Exact OBI lookup | synthetic verified product/card | Not run |
| Ordinary descriptions, location-like text | unchanged full USER message | Not run |
| Current and authorized alternate branches | approved branch allowlist and rejection | Not run |
| Manufacturer research then local lookup | bounded Responses continuation mechanism | Not run |
| Wrong-provider rejection then answer | scripted rejection and continuation | Not run |
| No result / unavailable | structured result support and unavailable-card test | Not run |
| No invented stock/price/cards | verified bridge-only card projection | Not run |
| Historical `w naszym OBI` | unchanged USER message | Not run |

These are **scripted tests**, not proof of model autonomy, provider connectivity, real web research or mobile acceptance. A malicious or defective trusted bridge remains a trust-boundary risk.

### Secure real-model testing blocker

The existing manual `.github/workflows/taksula-behavior-eval.yml` refers to `${{ secrets.OPENAI_API_KEY }}` and uses the production model constant. GitHub secrets cannot be read or inferred from the repository, and neither a key nor an authorized Actions dispatch facility was available in this execution context. **No actual OpenAI API calls were executed; cost $0 recorded for this PR session.** Presence of the reference does not prove that the secret is configured. Do not paste secrets into logs or commits.

An acceptable next phase is a manual, strictly budgeted V2-vs-main evaluation job that executes both drivers against the same scenario fixtures with synthetic Android provider output, real web_search where authorized, and records request IDs, input/output tokens, tool decisions, cost/latency and text outcomes. Do not run live OBI/KWANT providers. The current production harness `proxy/evals/taksula_behavior.ts` and manual workflow are available for comparison, but they do not run V2 yet. This PR makes no parity or win-rate claim.

### Targeted removal only after cutover

**Candidates, not deletions in this PR:** old language-phrase admission/projection branches in `BranchResolver.kt`, location intent helpers in `AdvisorLocationDetails.kt` / `AdvisorLocationFollowUps.kt`, old routing and output-repair branches in `AdvisorController.kt`, legacy prompt/tool protocol variants in `proxy/src/config.ts` and protocol parsing in `proxy/src/validation.ts`, plus obsolete provider-specific Advisor orchestration tests. Keep the actual product parsers, provider repositories, canonical directory, WorkingProfile, per-provider authorization, verified cards, persistence and diagnostics.

Each removal requires a usage/call-site check; some location logic is legitimate *permission validation* and must remain. Do not blindly delete by filename.

### Minimal migration

1. Run deterministic Worker typecheck/tests and new V2 tests; fix any failures inside the isolated slice.
2. Add a secure Android bridge using the already deployed tool executors and exact trusted authorization inputs; never pass unrestricted USER text into a new permission parser.
3. Add V2 real-model eval driver with synthetic provider outputs. Compare with main using the **same** manufacturer, OBI, KWANT, wrong-provider, history and negative evidence scenarios, with a modest spending cap.
4. Verify genuine citations, answer recovery, protected tool execution and card evidence invariants under adversarial tests.
5. Only after demonstrated parity, add an explicit non-default opt-in and physical Android acceptance. Production route switch is a **separate approved PR**.
6. After stable cutover, remove the proven-obsolete legacy branches and keep only enforceable permission checks. No new phrase exceptions.

## How to run isolated scripted checks

From `proxy/`, `npm ci && npm test`. The existing `npm test` compiles `src/*.ts` to `.test-dist` and runs `tests/*.test.mjs`. V2 is not wired to the Worker endpoint. Never treat a passing test suite as a passed real-model or real-device test.


## R2 isolated Android bridge — implementation and no-cutover boundary

Verified R2 starting PR #149 HEAD: `4d914c80f50258fcfc24d9cc3b2873e002d17def` (unchanged main `818ae84bd894d07d29f695bd2fac23d00d0e9463`). PR #148 remains separate. New `CoreV2AndroidToolBridge.kt` is intentionally **not called by the production Controller**. It accepts a typed request and a `Scope.currentOnly(WorkingProfile)`, pre-checks provider/branch/limits and delegates to existing `FindProviderProductsTool.execute` supplied by the caller. Its result is projected only when a same-provider, same-branch, same-query `AdvisorVerifiedToolResult` matches corresponding same-turn `VerifiedProductSnapshot` entries: product ID, stock, price, price scope and verification timestamp. Snapshot-backed facts rather than model output are card evidence. The bridge does not directly handle web search or invent a second authorization/NL grammar.

The real provider IDs are `obi-pl` and `kwant-pl`. V2 Worker now uses those exact IDs in authority, tool results and local product identity. No authority is derived from USER-text phrase matching. Current-only scope is deliberately the only public constructor: **alternate branch access is not yet authorized in this isolated adapter**; it must be granted by a trusted, independently established application permission with canonical directory validation, not constructed by model parameters or an unvalidated JSON allowlist. Real multi-branch acceptance remains a blocked feature.

The Android adapter currently exposes a Kotlin typed boundary, **not an authenticated network bridge**. `runCoreV2` is an isolated in-process Worker runner taking an injected `V2Bridge`, and neither a Worker endpoint, Android dispatcher, protected tool-request/response exchange, nor lifecycle/persistence callback has been connected. Connecting these two halves securely requires a new authenticated, correlation-checked transport for tool requests/responses or a carefully isolated extension to the existing production transport; a new production route was prohibited for this iteration. **There is no live Android ↔ Worker V2 end-to-end execution**. Future wiring must bind trusted profile, authorized scope, Responses response ID/call ID, request limits and provider snapshots to the same authenticated conversation without accepting the model's authority claims.

### Evidence trust tiers

- **Provider proof:** existing Kotlin executor yields current-turn `VerifiedProductSnapshot` with provider, effective branch, effective product ID, timestamp, price scope, zero/null stock. The R2 bridge checks an exact per-query match before allowing structured product data out.
- **Transport integrity (still missing):** an Android-to-Worker authenticated and correlation-checked exchange must prove that a claimed `V2Evidence` came from this Kotlin executor. The Worker currently validates the result envelope, but a syntactically valid result from a forged injected callback can still produce a card. Structural validation is not a cryptographic proof.
- **OpenAI final text:** model-authored advice is never product proof. OpenAI citation annotations produce optional external source links; they do not prove store inventory.
- **Unknown/partial coverage:** the V2 Worker includes explicit `coverage` and `query`, rejects mismatched result scopes, preserves `stock: null` vs `stock: 0`, and only turns `success` products into cards. A `not_found` report describes only the attempted scoped lookup, never exhaustion of the provider assortment.

### Live-model execution and comparison boundary

Verified existing manually dispatched workflow `.github/workflows/taksula-behavior-eval.yml` references `secrets.OPENAI_API_KEY`, uses `contents: read` and the existing production driver. Its existence does **not** establish that the secret exists or is usable. The connected GitHub capabilities in this session support GitHub source and CI reads/writes and job reruns, **not dispatch of workflow_dispatch**; secret-admin endpoints are unavailable and no local OpenAI key was exposed. No paid Responses call or genuine web_search was executed for R2. Consequently there are no actual selected-tools traces, measured costs, latencies or V2-versus-main success rates for the 14 scenarios. Report these as `BLOCKED_NOT_EXECUTED`, not PASS. Measured OpenAI spend from this iteration: **USD 0**, not a validated experiment budget or proof of model behavior.

The current V2 scripted controls cover direct answer, simulated provider call, wrong-provider continuation, wrong branch, no cards from unavailable evidence, typed card projection, OBI ordinary text preservation, duplicate query caching, malformed arguments, interrupted/incomplete output, injection-as-data, same-turn limits, and other synthetic negatives. Android tests exercise the original `FindProviderProductsTool`-compatible payload/snapshot relationship under scripted execution. None tests physical Android HTTP. Current main has production policy/authorization paths including BranchResolver and the production Worker loop, while V2 intentionally does not call any of them; the difference is **architectural**, not statistically measured performance. Historical `na 5 bolców`, `w naszym OBI` and `we wnęce` still need real-model behavioral trials, including model-selected tool arguments, before recommending a cutover.

### R2 acceptance decision and outstanding gates

**NO-GO for controlled opt-in / production cutover until:**

1. P0: Add an authenticated, conversation/call-ID-bound tool transport, verified Android profile authorization and protected provider-fact provenance; keep current-only default until an independent alternate-branch grant mechanism exists.
2. P0: Run a real OpenAI evaluation under a **strict USD 1 ceiling** with a secure workflow dispatch, validated credentials/pricing and synthetic providers only. Stop before spending if cost cannot be bounded; record actual tool decisions, token/call usage, traces and errors. Never run the original unbounded production manual workflow merely to claim V2 acceptance.
3. P1: Run same-scenario V2/main comparison with identical synthetic outputs, including genuine manufacturer web_search, wrong-provider recovery and historical OBI/KWANT text. Record unsupported comparisons as not comparable.
4. P1: Complete Android physical-device opt-in acceptance in a separate authorized phase; inspect all exact-head CI checks before moving the PR out of Draft.

The R2 adapter and evidence checks constitute a safe isolated piece, **not** a complete secure Android↔Worker vertical slice. No production routes, provider implementations, models, versions, keys, old PRs or deploys are changed.
