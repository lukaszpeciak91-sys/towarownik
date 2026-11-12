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
