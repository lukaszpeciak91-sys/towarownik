import test from "node:test";
import assert from "node:assert/strict";
import { createWorker } from "../.test-dist/index.js";
import {
  OPENAI_MODEL, OPENAI_REASONING_EFFORT, OPENAI_RESPONSES_URL,
  localToolForProtocol, finalAnswerFormatForProtocol, agentInstructionsForProfile,
} from "../.test-dist/config.js";

// Offline contract witnesses, NOT a tokenizer or real-model sufficiency trial.
// Fail any accidental unmocked request, including a real provider request.
globalThis.fetch = async () => { throw new Error("Unexpected live fetch"); };
const env = { TOWAROWNIK_APP_TOKEN: "R5_APP_CANARY", OPENAI_API_KEY: "R5_KEY_CANARY" };
const stages = ["start", "message", "continue"];
const profiles = [
  { v: 2, provider: "obi-pl", branch: "075", tool: "find_obi_products" },
  { v: 3, provider: "kwant-pl", branch: "205", tool: "find_products" },
];
const user = "klient potrzebuje poniższe:\n2 x włącznik pojedynczy\n3 x gniazdo pojedyncze\n" +
  "1 x włącznik krzyżowy\n2 x gniazdo podwójne\n3 x ramka pojedyncza okrągła\n" +
  "1 x ramka podwójna okrągła\n1 x ramka potrójna okrągła\n" +
  "życzy sobie serie Karlik Mini kolor Taupe, znajdź dla mnie w sieci kody producenta na te artykuły";
const profile = p => p.v === 2 ? { storeNumber: p.branch } : { providerId: p.provider, branchId: p.branch };
const rejected = p => ({ ...profile(p), queries: [{ query: "R5_QUERY_CANARY", limit: 1 }],
  rejection: p.v === 2 ? "store_not_authorized" : "branch_not_authorized" });
const notFound = p => ({ ...profile(p), results: [{ query: "R5_QUERY_CANARY", status: "not_found", products: [] }] });
const usage = (output = 302, reasoning = 73) => ({
  input_tokens: 16196, input_tokens_details: { cached_tokens: 0, cache_write_tokens: 0 },
  output_tokens: output, output_tokens_details: { reasoning_tokens: reasoning }, total_tokens: 16196 + output,
});
function answer(output = 302) {
  return { id: "resp_R5_NEW_CANARY", model: OPENAI_MODEL, status: "completed", usage: usage(output),
    output: [{ type: "message", content: [{ type: "output_text",
      text: JSON.stringify({ text: "Synthetic complete answer; no current inventory established.", productRefs: [] }),
    }] }] };
}
function incomplete(shape = "partial text", reason = "max_output_tokens") {
  const payload = { ...answer(), status: "incomplete", incomplete_details: { reason } };
  if (shape === "partial text") payload.output[0].content[0].text = '{"text":"R5_PARTIAL_CANARY';
  if (shape === "partial arguments") payload.output = [{ type: "function_call", name: "find_products",
    call_id: "call_R5_PARTIAL_CANARY", arguments: '{"providerId":"kwant-pl","queries":[' }];
  if (shape === "reasoning only") {
    payload.output = [{ type: "reasoning", summary: [], content: "R5_REASONING_CANARY" }];
    payload.usage = usage(302, 302);
  }
  return payload;
}
function tool(p) {
  return { ...answer(600), output: [{ type: "function_call", name: p.tool, call_id: "call_R5_NEXT_CANARY",
    arguments: JSON.stringify({ ...profile(p), ...(p.v >= 3 ? { requestedBranch: null } : {}),
      queries: [{ query: "R5_QUERY_CANARY", limit: 2 }] }),
  }] };
}
async function invoke(p, stage, upstream, local = notFound(p), extra = {}) {
  const requestBody = { protocolVersion: p.v, ...profile(p), ...(stage === "continue" ? {
    responseId: "resp_R5_PREVIOUS_CANARY", callId: "call_R5_PREVIOUS_CANARY", tool: p.tool, result: local,
  } : { message: user, ...(stage === "message" ? { previousResponseId: "resp_R5_PREVIOUS_CANARY" } : {}) }), ...extra };
  const sent = [], logs = [];
  const saved = [console.info, console.warn];
  console.info = console.warn = value => logs.push(JSON.parse(value));
  let response;
  try {
    response = await createWorker(async (url, init) => {
      assert.equal(url, OPENAI_RESPONSES_URL);
      const body = JSON.parse(init.body); sent.push(body);
      const payload = await upstream(body, sent.length);
      return payload instanceof Response ? payload : new Response(JSON.stringify(payload));
    }).fetch(new Request(`https://offline.invalid/v1/agent/${stage}`, {
      method: "POST", headers: { Authorization: `Bearer ${env.TOWAROWNIK_APP_TOKEN}`,
        "Content-Type": "application/json", "X-Taksula-Trace-Id": "11111111-1111-4111-8111-111111111115" },
      body: JSON.stringify(requestBody),
    }), env);
  } finally { [console.info, console.warn] = saved; }
  assert.equal(logs.length, 1);
  const log = logs[0];
  assert.equal(log.traceId, response.headers.get("X-Taksula-Trace-Id"));
  const serialized = JSON.stringify(log);
  for (const secret of ["R5_APP_CANARY", "R5_KEY_CANARY", "R5_QUERY_CANARY", "R5_PARTIAL_CANARY",
    "R5_REASONING_CANARY", "resp_R5_", "call_R5_", "Karlik"]) assert(!serialized.includes(secret), secret);
  return { response, body: await response.json(), log, sent, requestBody };
}
function assertFailure(r, category, status = 200) {
  assert.equal(r.response.status, 502);
  assert.deepEqual(r.body, { error: "upstream_failure" });
  assert.equal(r.log.upstreamFailureCategory, category);
  assert.equal(r.log.upstreamStatus, status);
  assert.equal(r.log.responseEnvelopeType, null);
  assert.equal(r.sent.length, 1, "one upstream attempt, never retry or continue incomplete output");
  assert.equal(r.log.localToolRequested, false);
}
function assertRequest(r, p, stage) {
  assert.equal(r.sent.length, 1);
  const b = r.sent[0];
  assert.equal(b.max_output_tokens, 1024, "uniform bounded headroom for every advisor stage");
  assert.equal(b.model, OPENAI_MODEL);
  assert.equal(b.model, "gpt-6-luna");
  assert.deepEqual(b.reasoning, { effort: OPENAI_REASONING_EFFORT });
  assert.equal(b.reasoning.effort, "low");
  assert.equal(b.instructions, agentInstructionsForProfile(p.provider, p.branch, p.v));
  assert.equal(b.parallel_tool_calls, false);
  assert.equal(b.tool_choice, "auto");
  assert.equal(b.max_tool_calls, 1);
  assert.equal(b.store, true);
  assert.deepEqual(b.text.format, finalAnswerFormatForProtocol(p.v));
  if (stage === "start") assert.equal(b.previous_response_id, undefined);
  else assert.equal(b.previous_response_id, "resp_R5_PREVIOUS_CANARY");
  if (stage === "continue") {
    assert.deepEqual(b.input, [{ type: "function_call_output", call_id: "call_R5_PREVIOUS_CANARY",
      output: JSON.stringify(r.requestBody.result) }]);
  } else assert.equal(b.input, user);
}

for (const p of profiles) for (const stage of stages) {
  test(`R5 ${p.provider} ${stage} budget witness finishes without retry or partial acceptance`, async () => {
    const scripted = b => b.max_output_tokens < 700 ? incomplete() : answer(700);
    assert.equal(scripted({ max_output_tokens: 384 }).status, "incomplete");
    const r = await invoke(p, stage, scripted);
    assert.equal(r.response.status, 200, "a 700-token synthetic response must fit this stage's ceiling");
    assert.equal(r.body.type, "answer");
    assert.deepEqual(r.body.productRefs, []);
    assert.equal(r.body.usage.outputTokens, 700);
    assertRequest(r, p, stage);
  });
  test(`R5 ${p.provider} ${stage} completed tool plan preserves protocol and grants no execution`, async () => {
    const r = await invoke(p, stage, b => b.max_output_tokens < 700 ? incomplete("partial arguments") : tool(p));
    assert.equal(r.response.status, 200, "complete scripted tool plan fits uniform ceiling");
    assert.equal(r.body.type, "tool_request");
    assert.equal(r.body.tool.name, p.tool);
    assert.deepEqual(r.body.tool.arguments, JSON.parse(tool(p).output[0].arguments));
    assert.deepEqual(r.sent[0].tools[0], localToolForProtocol(p.v));
    assertRequest(r, p, stage);
  });
  test(`R5 ${p.provider} ${stage} small complete response and exact usage unchanged`, async () => {
    const r = await invoke(p, stage, () => answer());
    assert.equal(r.response.status, 200);
    assert.equal(r.body.text, "Synthetic complete answer; no current inventory established.");
    assert.deepEqual(r.body.productRefs, []);
    assert.equal(r.body.responseId, "resp_R5_NEW_CANARY");
    assert.deepEqual(r.body.usage, { model: OPENAI_MODEL, requestType: stage.toUpperCase(),
      inputTokens: 16196, cachedInputTokens: 0, cacheWriteTokens: 0,
      outputTokens: 302, reasoningTokens: 73, totalTokens: 16498,
      estimatedCostUsd: 0.0017706, pricingVersion: "openai-gpt-6-luna-2026-09-28-web-v1" });
    assert.equal(r.sent.length, 1);
  });
  for (const shape of ["partial text", "partial arguments", "reasoning only"]) {
    test(`R5 ${p.provider} ${stage} persistent exhaustion ${shape} fails closed once`, async () => {
      const r = await invoke(p, stage, () => incomplete(shape));
      assertFailure(r, "upstream_incomplete_output_budget");
      assert.equal(r.log.outputTokens, 302);
      assert.equal(r.log.reasoningTokens, shape === "reasoning only" ? 302 : 73);
      assert.equal(r.log.estimatedCostUsd, null);
      assert.equal(r.log.pricingVersion, null);
      assertRequest(r, p, stage);
    });
  }
  test(`R5 ${p.provider} ${stage} valid-looking incomplete answer still rejected`, async () => {
    const r = await invoke(p, stage, () => ({ ...answer(), status: "incomplete",
      incomplete_details: { reason: "max_output_tokens" } }));
    assertFailure(r, "upstream_incomplete_output_budget");
  });
}

for (const stage of stages) {
  for (const [name, upstream, category, status] of [
    ["filtered", () => incomplete("partial text", "content_filter"), "upstream_incomplete_other", 200],
    ["other incomplete", () => incomplete("partial arguments", "max_messages"), "upstream_incomplete_other", 200],
    ["invalid JSON", () => new Response("{R5_PRIVATE_CANARY"), "upstream_invalid_json", 200],
    ["invalid envelope", () => [], "upstream_invalid_envelope", 200],
    ["invalid final", () => { const p = answer(); p.output[0].content[0].text = "{"; return p; }, "upstream_invalid_final_answer", 200],
    ["failed", () => ({ ...answer(), status: "failed" }), "upstream_response_not_completed", 200],
    ["HTTP 400", () => new Response("R5_PRIVATE_CANARY", { status: 400 }), "upstream_http_4xx", 400],
    ["HTTP 429", () => new Response("R5_PRIVATE_CANARY", { status: 429 }), "upstream_rate_limit", 429],
    ["HTTP 503", () => new Response("R5_PRIVATE_CANARY", { status: 503 }), "upstream_http_5xx", 503],
    ["network", () => { throw new TypeError("R5_PRIVATE_CANARY"); }, "upstream_network", null],
    ["timeout abort", () => { throw new DOMException("R5_PRIVATE_CANARY", "AbortError"); }, "upstream_network", null],
  ]) test(`R5 ${stage} ${name} retains classification without retry`, async () => {
    const r = await invoke(profiles[1], stage, upstream);
    assertFailure(r, category, status);
    assert(!JSON.stringify(r.log).includes("R5_PRIVATE_CANARY"));
  });
}

for (const p of profiles) {
  test(`R5 ${p.provider} rejected local result is submitted once, cannot reenable exhausted tool`, async () => {
    const r = await invoke(p, "continue", () => tool(p), { ...rejected(p), rejection: "local_tool_limit_reached" });
    assertFailure(r, "upstream_invalid_tool_call");
    assert.deepEqual(r.sent[0].tools, [{ type: "web_search" }]);
  });
  test(`R5 ${p.provider} authorization denial remains denial in one continuation`, async () => {
    const r = await invoke(p, "continue", () => answer(), rejected(p));
    assert.equal(r.response.status, 200);
    assertRequest(r, p, "continue");
    assert.deepEqual(JSON.parse(r.sent[0].input[0].output), rejected(p));
  });
  test(`R5 ${p.provider} complete web search remains separately priced without retry`, async () => {
    const payload = answer();
    payload.output.unshift({ type: "web_search_call", status: "completed", action: { type: "search", query: "synthetic" } });
    const r = await invoke(p, "start", () => payload);
    assert.equal(r.response.status, 200);
    assert.equal(r.body.webSearchCalls, 1);
    assert.equal(r.body.usage.estimatedCostUsd, 0.0117706);
    assert.equal(r.sent.length, 1);
  });
  test(`R5 ${p.provider} a client cannot inflate the output budget`, async () => {
    const r = await invoke(p, "start", () => answer(), notFound(p), { max_output_tokens: 99999 });
    assert.equal(r.response.status, 400);
    assert.equal(r.sent.length, 0);
  });
}

test("R5 repeated budget exhaustion across endpoints terminates, no hidden recovery chain", async () => {
  let attempts = 0;
  for (const stage of stages) {
    const r = await invoke(profiles[1], stage, () => { attempts++; return incomplete(); });
    assertFailure(r, "upstream_incomplete_output_budget");
  }
  assert.equal(attempts, 3);
});
