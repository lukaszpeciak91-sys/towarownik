# Taksula behavioral evaluations

## Purpose

The behavioral eval harness checks observable advisor behavior against regression scenarios using the real production advisor configuration. The same A–I families run against both the preserved OBI protocol-v2 path and the provider-aware KWANT protocol-v3 path. It is intended to answer questions such as:

- did Taksula clarify a materially ambiguous product request before selecting a concrete SKU;
- did browse intent request and surface multiple useful verified variants;
- did the model preserve stock/null/not-found/unavailable semantics;
- did an understood task/job request give useful essentials-first advice without automatically turning into an OBI shopping kit;
- did a general technical question avoid unnecessary local OBI or web tools.

The harness does **not** prove general model correctness. It is a small regression suite for known behaviors and failure modes.

## Contract tests versus behavioral evals

Existing proxy/unit tests are deterministic contract tests. They verify code paths, schemas, validation, prompt/configuration wiring, and normalized response handling with mocked upstream data. Prompt-contract assertions can prove that an instruction exists, but they cannot prove that a live model follows it.

Behavioral evals execute the production advisor path against the real configured OpenAI model. The advisor itself is created through the production `startAgent()` / `continueAgent()` functions, so it uses the current:

- model;
- reasoning effort;
- server-controlled advisor instructions;
- the selected path's production `find_obi_products` (OBI v2) or `find_products` (KWANT v3) tool schema;
- built-in web-search declaration;
- structured final-answer schema;
- normal Responses continuation flow.

There is no copied eval-only advisor prompt.

The only eval-specific model prompt is the small semantic **grader** used when deterministic assertions cannot decide the behavior reliably. It sees only observable trace data and an explicit scenario rubric. It is instructed not to require or expose hidden reasoning.

## Provider isolation and fixtures

Behavioral evals never call live `obi.pl` or `kwant.net.pl`.

Every local product request is answered from deterministic in-repository provider-shaped mock fixtures. OBI scenarios retain their OBIK inputs and v2 result shape. KWANT direct-product scenarios instead use article-number search text and v3 results with `providerId`, `branchId`, `productId`, `articleNumber`, and online price scope; they never pretend that KWANT supports OBIK or model-controlled exact product IDs. Fixtures are query-sensitive: only a query whose observable meaning matches the scenario receives the intended verified/unavailable fixture; unrelated queries deterministically receive `not_found`. This prevents a poor tool query from being rewarded with the product the scenario expected.

This allows both model/tool interactions to be exercised without coupling behavioral results to either provider website or parser changes.

## Initial scenarios

The A–I behavioral scenario set contains:

- **A** — broad black cable-tie product intent: search the current provider and surface several verified variants without pretending one unspecified size is definitively correct;
- **B** — browse black cable ties: request multiple results and avoid exhaustive-assortment wording;
- **C** — specified 4.2 x 380 mm indoor cable ties: proceed to verification; the search phrase may omit literal indoor wording when the returned verified product itself confirms indoor suitability;
- **D** — ambiguous faucet aerator: clarify connection/thread information;
- **E** — three fitting verified variants: surface alternatives or justify one selection;
- **F** — direct current stock + price for one provider-specific identifier (OBIK on OBI, article number on KWANT);
- **G_ZERO** — confirmed stock zero;
- **G_NULL** — unknown/null stock;
- **G_NOT_FOUND** — no verified matching result;
- **G_UNAVAILABLE** — retrieval could not establish the fact;
- **H** — understood silicone washbasin-to-wall sealing job: give small practical essentials-first advice, distinguish essentials from optional convenience items, and use zero automatic local product calls / zero `productRefs` unless explicit provider-product intent exists;
- **H_AMBIGUOUS** — original underspecified washbasin-sealing request: clarify wall/countertop joint versus drain/siphon-type work before any concrete OBI lookup;
- **I** — general SDS+ versus SDS Max explanation without unnecessary tools.

One additional provider-neutral regression runs beside that baseline:

- **PRODUCT_INTENT** — a sufficiently specified concrete product/recommendation request must use the current provider immediately, without requiring an extra “check the market/branch” phrase. The user intent is identical on OBI v2 and KWANT v3; only the mocked tool/result shape differs by provider.

## Trace and grading

Each trial records only observable data:

- scenario and trial number;
- user message;
- normalized model outputs;
- the clarification candidate or final answer text;
- `find_obi_products` requests in order;
- tool arguments;
- deterministic mocked tool results;
- completed web-search count;
- final `productRefs`.

No chain-of-thought, hidden reasoning item, raw OpenAI response, API key, bearer token, cookie, or live OBI payload is stored by the harness.

Each scenario also declares an explicit web-use policy: `forbidden`, `allowed`, or `required`. The initial A–I suite uses `forbidden` because it targets clarification, local OBI behavior, stable availability semantics, batching, and stable general technical knowledge rather than external research. The production `web_search` tool remains available to the advisor exactly as in production; if the model nevertheless uses it in a forbidden scenario, deterministic grading fails the trial.

Deterministic assertions are preferred for:

- tool choice and call count;
- call order;
- query limits and batching;
- direct OBIK preservation;
- unnecessary web usage;
- exact duplicate searches after normalization;
- current-turn `productRef` grounding;
- clarification-before-lookup cases.

Semantic grading is used only for behaviors that require meaning rather than exact wording, such as whether a clarification is relevant, whether alternatives were genuinely surfaced, or whether the four availability states were described distinctly. The semantic grader still returns only a short structured `{ pass, reason }` judgment.

Trial status is separate from that grader shape:

- **PASS** — observable behavior passed deterministic checks and, when applicable, a valid semantic judgment;
- **FAIL** — an actual behavioral regression was observed, either deterministically or through a valid semantic judgment with `pass: false`;
- **ERROR** — the eval infrastructure could not produce a trustworthy verdict, for example because advisor transport failed or the semantic grader still could not return a consumable grade after its bounded retry.

Malformed, missing, structurally invalid, or explicitly output-limit-incomplete semantic-grader output is retried at most once. A valid semantic rejection is never retried. If the retry also cannot produce a valid grade, the trial is reported as **ERROR**, not **FAIL**.

## Deterministic CI tests

Normal PR CI never calls OpenAI and never calls live OBI.

From `proxy/`:

```bash
npm ci
npm run typecheck
npm test
```

`npm test` includes deterministic behavioral-harness tests with a scripted advisor driver and mocked semantic grader. These tests validate the harness mechanics, trace structure, production configuration reuse, repeated-trial handling, grounding checks, and failure propagation.

## Run real-model evals locally

Real-model execution requires an OpenAI API key and consumes API tokens.

From `proxy/`:

```bash
export OPENAI_API_KEY="..."
npm ci
npm run eval:behavior
```

Default execution is deliberately small: one trial for every A–I baseline scenario plus the provider-neutral PRODUCT_INTENT regression on both provider paths.

Run only one provider path:

```bash
npm run eval:behavior -- --provider obi-v2
npm run eval:behavior -- --provider kwant-v3
```

Run repeated trials:

```bash
npm run eval:behavior -- --trials 3
```

Run selected scenarios:

```bash
npm run eval:behavior -- --scenarios A,B,F,I
```

Choose an output file:

```bash
npm run eval:behavior -- \
  --trials 2 \
  --scenarios B,E,H \
  --output .eval-results/taksula-behavior.json
```

Trials are capped at 10 per scenario to make accidental cost multiplication harder.

The command prints a compact summary:

```text
provider  scenario  trial  result  local_calls  web_searches  reason
...
passed: N / M
failed scenarios: ...
errored scenarios: ...
```

The JSON output contains the structured per-trial trace. The CLI/workflow exits non-zero when at least one trial is either **FAIL** or **ERROR**. This keeps infrastructure problems visible and red without misreporting them as behavioral regressions.

## Manual GitHub workflow

Use **Actions → Taksula behavioral evals → Run workflow**.

Inputs:

- `trials` — default 1, allowed 1–10;
- `scenarios` — `all` or comma-separated scenario IDs.
- `provider` — `both` (default), `obi-v2`, or `kwant-v3`.

The workflow requires repository secret `OPENAI_API_KEY`. It does not require OBI credentials and never calls live OBI. The JSON trace is uploaded as the `taksula-behavioral-eval` artifact when produced.

The workflow is `workflow_dispatch` only. Real-model evals therefore do **not** run automatically for pull requests or pushes.

## API cost

A real trial can involve multiple Responses API calls:

1. advisor START;
2. zero or more advisor CONTINUE calls after mocked local-tool results;
3. normally one semantic grader call for scenarios that need semantic judgment, with at most one additional grader attempt only when the first grader output is malformed, missing, structurally invalid, or observably incomplete because of the output limit.

If the advisor chooses built-in web search, the normal production web-search fee also applies. Increasing `--trials` multiplies these costs. Start with one trial and raise the count only when measuring consistency.

No real-model result should be fabricated when credentials are unavailable. Deterministic CI passing means the harness works for both provider shapes; it is not evidence that the live model passed the behavioral scenarios.

## Adding a regression scenario

When a real advisor failure is discovered:

1. reduce it to the smallest user message that still reproduces the behavior;
2. add deterministic mocked OBI data representing only the evidence needed for that failure;
3. add deterministic assertions for tool choice/order/arguments/counts wherever possible;
4. add a short semantic rubric only for the part that cannot be decided mechanically;
5. run the deterministic harness tests;
6. run the new scenario manually against the real model;
7. keep the scenario after the behavior is fixed so the failure remains a regression test.

Do not paste production conversations, secrets, raw OBI payloads, or hidden model reasoning into eval fixtures.
