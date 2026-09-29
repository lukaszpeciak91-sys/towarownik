# Taksula behavioral evaluations

## Purpose

The behavioral eval harness checks observable advisor behavior against regression scenarios using the real production advisor configuration. It is intended to answer questions such as:

- did Taksula clarify a materially ambiguous product request before selecting a concrete SKU;
- did browse intent request and surface multiple useful verified variants;
- did the model preserve stock/null/not-found/unavailable semantics;
- did a task-oriented request batch related OBI categories;
- did a general technical question avoid unnecessary local OBI or web tools.

The harness does **not** prove general model correctness. It is a small regression suite for known behaviors and failure modes.

## Contract tests versus behavioral evals

Existing proxy/unit tests are deterministic contract tests. They verify code paths, schemas, validation, prompt/configuration wiring, and normalized response handling with mocked upstream data. Prompt-contract assertions can prove that an instruction exists, but they cannot prove that a live model follows it.

Behavioral evals execute the production advisor path against the real configured OpenAI model. The advisor itself is created through the production `startAgent()` / `continueAgent()` functions, so it uses the current:

- model;
- reasoning effort;
- server-controlled advisor instructions;
- `find_obi_products` tool schema;
- built-in web-search declaration;
- structured final-answer schema;
- normal Responses continuation flow.

There is no copied eval-only advisor prompt.

The only eval-specific model prompt is the small semantic **grader** used when deterministic assertions cannot decide the behavior reliably. It sees only observable trace data and an explicit scenario rubric. It is instructed not to require or expose hidden reasoning.

## OBI isolation

Behavioral evals never call live `obi.pl`.

Every local `find_obi_products` request is answered from deterministic in-repository mock fixtures. The fixture result echoes the model's requested query and returns bounded verified/not-found/unavailable data for the scenario.

This allows the model/tool interaction to be exercised without coupling behavioral results to OBI website availability or parser changes.

## Initial scenarios

The suite currently contains:

- **A** — ambiguous black cable ties: clarify before selection;
- **B** — browse black cable ties: request multiple results and avoid exhaustive-assortment wording;
- **C** — specified 4.2 x 380 mm indoor cable ties: proceed to verification;
- **D** — ambiguous faucet aerator: clarify connection/thread information;
- **E** — three fitting verified variants: surface alternatives or justify one selection;
- **F** — direct current stock + price for one OBIK;
- **G_ZERO** — confirmed stock zero;
- **G_NULL** — unknown/null stock;
- **G_NOT_FOUND** — no verified matching result;
- **G_UNAVAILABLE** — retrieval could not establish the fact;
- **H** — small essentials-first washbasin-sealing kit with related queries batched where practical;
- **I** — general SDS+ versus SDS Max explanation without unnecessary tools.

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

Deterministic assertions are preferred for:

- tool choice and call count;
- call order;
- query limits and batching;
- direct OBIK preservation;
- unnecessary web usage;
- exact duplicate searches after normalization;
- current-turn `productRef` grounding;
- clarification-before-lookup cases.

Semantic grading is used only for behaviors that require meaning rather than exact wording, such as whether a clarification is relevant, whether alternatives were genuinely surfaced, or whether the four availability states were described distinctly. The semantic grader returns only `PASS/FAIL` plus one short diagnostic reason.

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

Default execution is deliberately small: one trial for every scenario.

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
scenario  trial  result  local_calls  web_searches  reason
...
passed: N / M
failed scenarios: ...
```

The JSON output contains the structured per-trial trace.

## Manual GitHub workflow

Use **Actions → Taksula behavioral evals → Run workflow**.

Inputs:

- `trials` — default 1, allowed 1–10;
- `scenarios` — `all` or comma-separated scenario IDs.

The workflow requires repository secret `OPENAI_API_KEY`. It does not require OBI credentials and never calls live OBI. The JSON trace is uploaded as the `taksula-behavioral-eval` artifact when produced.

The workflow is `workflow_dispatch` only. Real-model evals therefore do **not** run automatically for pull requests or pushes.

## API cost

A real trial can involve multiple Responses API calls:

1. advisor START;
2. zero or more advisor CONTINUE calls after mocked local-tool results;
3. one semantic grader call for scenarios that need semantic judgment.

If the advisor chooses built-in web search, the normal production web-search fee also applies. Increasing `--trials` multiplies these costs. Start with one trial and raise the count only when measuring consistency.

No real-model result should be fabricated when credentials are unavailable. Deterministic CI passing means the harness works; it is not evidence that the live model passed the behavioral scenarios.

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
