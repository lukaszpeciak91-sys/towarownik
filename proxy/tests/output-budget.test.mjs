import test from "node:test";
import assert from "node:assert/strict";
import { createWorker } from "../.test-dist/index.js";
import { startAgent, messageAgent } from "../.test-dist/openai.js";
import {
  OPENAI_MODEL, OPENAI_REASONING_EFFORT, OPENAI_MAX_OUTPUT_TOKENS,
  finalAnswerFormatForProtocol, localToolForProtocol,
} from "../.test-dist/config.js";

// Only synthetic fixtures and an injected transport; no live API/provider calls.
const env = { TOWAROWNIK_APP_TOKEN: "F4_APP_CANARY", OPENAI_API_KEY: "F4_KEY_CANARY" };
const trace = "00000000-1111-4111-8111-000000000004";
const profile = v => v <= 2 ? { storeNumber: "075" } : { providerId: "kwant-pl", branchId: "205" };
const toolName = v => v <= 2 ? "find_obi_products" : "find_products";
function product(v, i) {
  return {
    ...(v <= 2 ? { obik: String(1000000 + i) } : {
      productId: String(i), articleNumber: "F4_ARTICLE_CANARY",
      centralStock: null, priceScope: "online",
    }),
    name: "F4_PRODUCT_CANARY", brand: null, shortDescription: null,
    technicalFacts: [], stock: 7, stockUnit: null, price: 12,
  };
}
function result(v, counts = [1], status = "verified") {
  const products = n => Array.from({ length: n }, (_, i) => product(v, i + 1));
  if (v === 1) return { storeNumber: "075", query: "F4_QUERY_CANARY", products: products(counts[0]) };
  return { ...profile(v), results: counts.map((n, i) => ({
    query: `F4_QUERY_CANARY_${i}`, status: n === 0 && status === "verified" ? "not_found" : status, products: products(n),
  })) };
}
function rejection(v, reason) {
  if (v === 1) return { query: "synthetic", storeNumber: "075", rejection: "store_not_authorized" };
  return { ...profile(v), queries: [{ query: "synthetic", limit: 3 }, { query: "synthetic B", limit: 2 }], rejection: reason };
}
const fullUsage = {
  input_tokens: 1000, input_tokens_details: { cached_tokens: 400, cache_write_tokens: 0 },
  output_tokens: 384, output_tokens_details: { reasoning_tokens: 300 }, total_tokens: 1384,
};
function answer() {
  return { id: "resp_F4_NEW_ID_CANARY", model: OPENAI_MODEL, status: "completed", output: [{
    type: "message", content: [{ type: "output_text", text: JSON.stringify({ text: "Synthetic final", productRefs: [] }) }],
  }] };
}
function incomplete(usage = fullUsage, reason = "max_output_tokens") {
  return { ...answer(), status: "incomplete", incomplete_details: { reason }, usage,
    error: { message: "F4_RAW_ERROR_CANARY" },
    output: [{ type: "reasoning", text: "F4_REASONING_CANARY" }, { type: "message", content: [{
      type: "output_text", text: "F4_PARTIAL_TEXT_CANARY",
    }] }],
  };
}
async function invoke(v, stage = "continue", local = result(v), responsePayload = answer(), extra = {}) {
  const requestBody = { protocolVersion: v, ...profile(v), ...(stage === "continue" ? {
    responseId: "resp_F4_PREVIOUS_ID_CANARY", callId: "call_F4_ID_CANARY", tool: toolName(v), result: local,
  } : { message: "F4_MESSAGE_CANARY", ...(stage === "message" ? { previousResponseId: "resp_F4_PREVIOUS_ID_CANARY" } : {}) }), ...extra };
  const logs = [], sent = [];
  const saved = [console.info, console.warn];
  console.info = console.warn = value => logs.push(String(value));
  let response;
  try {
    response = await createWorker(async (_url, init) => {
      const body = JSON.parse(init.body); sent.push(body);
      const payload = typeof responsePayload === "function" ? responsePayload(body) : responsePayload;
      return new Response(JSON.stringify(payload), { status: 200 });
    }).fetch(new Request(`https://offline.invalid/v1/agent/${stage}`, {
      method: "POST", headers: { Authorization: `Bearer ${env.TOWAROWNIK_APP_TOKEN}`,
        "Content-Type": "application/json", "X-Taksula-Trace-Id": trace,
        ...(extra.tool === "find_product_locations" ? { "X-Taksula-Locations-Capability": "1" } : {}) },
      body: JSON.stringify(requestBody),
    }), env);
  } finally { [console.info, console.warn] = saved; }
  assert.equal(logs.length, 1);
  const log = JSON.parse(logs[0]);
  assert.equal(log.traceId, response.headers.get("X-Taksula-Trace-Id"));
  if (stage === "continue") assert.equal(log.traceId, trace);
  for (const canary of ["F4_APP_CANARY", "F4_KEY_CANARY", "F4_ARTICLE_CANARY", "F4_PRODUCT_CANARY",
    "F4_QUERY_CANARY", "F4_RAW_ERROR_CANARY", "F4_REASONING_CANARY", "F4_PARTIAL_TEXT_CANARY",
    "F4_MESSAGE_CANARY", "resp_F4_", "call_F4_"]) assert(!logs[0].includes(canary), canary);
  return { response, body: await response.json(), log, sent };
}
function assertFailure(r, category = "upstream_incomplete_output_budget") {
  assert.equal(r.response.status, 502);
  assert.deepEqual(r.body, { error: "upstream_failure" });
  assert.equal(r.log.upstreamFailureCategory, category);
  assert.equal(r.log.upstreamStatus, 200);
  assert.equal(r.log.outcome, "upstream_failure");
  assert.equal(r.log.responseEnvelopeType, null);
  assert.equal(r.sent.length, 1); // No retry, even after exhaustion.
}

for (const v of [1, 2, 3, 4, 5]) {
  for (const [name, counts, budget] of [["simple", [1], 384], ["two products", [2], 1024], ["five products", [5], 1024]]) {
    test(`F4 v${v} ${name} CONTINUE budget and unchanged identity/configuration`, async () => {
      const local = result(v, counts);
      const r = await invoke(v, "continue", local);
      assert.equal(r.response.status, 200);
      assert.equal(r.sent[0].max_output_tokens, budget);
      assert.equal(r.sent[0].model, OPENAI_MODEL);
      assert.deepEqual(r.sent[0].reasoning, { effort: OPENAI_REASONING_EFFORT });
      assert.equal(r.sent[0].parallel_tool_calls, false);
      assert.deepEqual(r.sent[0].text.format, finalAnswerFormatForProtocol(v));
      assert.deepEqual(r.sent[0].tools[0], localToolForProtocol(v));
      assert.equal(r.sent[0].previous_response_id, "resp_F4_PREVIOUS_ID_CANARY");
      assert.equal(r.sent[0].input[0].call_id, "call_F4_ID_CANARY");
      assert.deepEqual(JSON.parse(r.sent[0].input[0].output), local);
    });
  }
  test(`F4 v${v} authorization rejection keeps base budget`, async () => {
    const r = await invoke(v, "continue", rejection(v, v <= 2 ? "store_not_authorized" : "branch_not_authorized"));
    assert.equal(r.response.status, 200);
    assert.equal(r.sent[0].max_output_tokens, 384);
  });
  if (v === 1) continue;
  for (const [name, counts, status] of [["production-shaped 3 queries/5 products", [2, 2, 1], "verified"],
    ["two groups/one product", [1, 0], "verified"], ["two unavailable groups", [0, 0], "unavailable"]]) {
    test(`F4 v${v} ${name} gets bounded complex budget`, async () => {
      const r = await invoke(v, "continue", result(v, counts, status));
      assert.equal(r.response.status, 200);
      assert.equal(r.sent[0].max_output_tokens, 1024);
    });
  }
  test(`F4 v${v} exhausted local-tool budget keeps 384 and tools disabled`, async () => {
    const r = await invoke(v, "continue", rejection(v, "local_tool_limit_reached"));
    assert.equal(r.response.status, 200);
    assert.equal(r.sent[0].max_output_tokens, 384);
    assert.deepEqual(r.sent[0].tools, [{ type: "web_search" }]);
  });
}

test("F4 explicit 384 witness fails; higher selected budget completes only the scripted fixture", async () => {
  const scripted = body => body.max_output_tokens < 700 ? incomplete() : answer();
  assert.equal(scripted({ max_output_tokens: 384 }).status, "incomplete");
  const r = await invoke(3, "continue", result(3, [2, 2, 1]), scripted);
  assert.equal(r.response.status, 200);
  assert.equal(r.sent[0].max_output_tokens, 1024);
  // 700 is a synthetic transport threshold, NOT a tokenizer or live model measurement.
});

for (const v of [2, 3, 4, 5]) {
  test(`F4 v${v} higher budget never promotes an incomplete final/tool to success`, async () => {
    for (const output of [answer().output, [{ type: "function_call", name: toolName(v),
      call_id: "call_partial", arguments: JSON.stringify({ queries: [] }) }]]) {
      const r = await invoke(v, "continue", result(v, [2, 2, 1]), { ...incomplete(), output });
      assertFailure(r);
      assert.equal(r.log.outputTokens, 384);
      assert.equal(r.log.reasoningTokens, 300);
      assert.equal(r.log.verifiedProductCount, 5);
      assert.equal(r.log.resultQueryCount, 3);
      assert.equal(r.log.requestType, "CONTINUE");
      assert.equal(r.log.estimatedCostUsd, null); // Incomplete web usage is unknown.
    }
  });
}

for (const v of [2, 3]) for (const stage of ["start", "message", "continue"]) {
  test(`F4 v${v} ${stage} incomplete safe usage survives HTTP attribution`, async () => {
    const r = await invoke(v, stage, result(v, [2, 2, 1]), incomplete());
    assertFailure(r);
    assert.equal(r.log.endpointStage, stage.toUpperCase());
    assert.equal(r.log.model, OPENAI_MODEL);
    assert.equal(r.log.requestType, stage.toUpperCase());
    assert.equal(r.log.inputTokens, 1000);
    assert.equal(r.log.cachedInputTokens, 400);
    assert.equal(r.log.outputTokens, 384);
    assert.equal(r.log.reasoningTokens, 300);
    assert.equal(r.log.totalTokens, 1384);
    assert.equal(r.log.estimatedCostUsd, null);
    assert.equal(r.log.pricingVersion, null);
  });
}

for (const [name, usage, expected] of [
  ["absent", undefined, null], ["null", null, null],
  ["details omitted", { input_tokens: 0, output_tokens: 0, total_tokens: 0 }, 0],
  ["explicit zero", { input_tokens: 0, input_tokens_details: { cached_tokens: 0, cache_write_tokens: 0 },
    output_tokens: 0, output_tokens_details: { reasoning_tokens: 0 }, total_tokens: 0 }, 0],
  ["negative output", { ...fullUsage, output_tokens: -1 }, null],
  ["string output", { ...fullUsage, output_tokens: "384" }, null],
  ["oversized output", { ...fullUsage, output_tokens: 10000000001 }, null],
  ["fraction output", { ...fullUsage, output_tokens: 1.5 }, null],
  ["reasoning exceeds output", { ...fullUsage, output_tokens_details: { reasoning_tokens: 385 } }, null],
  ["cache exceeds input", { ...fullUsage, input_tokens_details: { cached_tokens: 1001 } }, null],
  ["malformed details", { ...fullUsage, output_tokens_details: "F4_PRIVATE_DETAIL_CANARY" }, null],
]) test(`F4 incomplete ${name} usage does not change failure or invent tokens`, async () => {
  const payload = incomplete(usage);
  if (name === "absent") delete payload.usage;
  const r = await invoke(3, "continue", result(3), payload);
  assertFailure(r);
  assert.equal(r.log.outputTokens, expected);
  assert.equal(r.log.estimatedCostUsd, null);
  assert.equal(r.log.reasoningTokens, name === "explicit zero" ? 0 : null);
});

test("F4 incomplete other retains its category and validated usage without parsing partial output", async () => {
  const r = await invoke(3, "continue", result(3), { ...incomplete(fullUsage, "content_filter"), output: null });
  assertFailure(r, "upstream_incomplete_other");
  assert.equal(r.log.outputTokens, 384);
});

for (const v of [1, 2, 3, 4, 5]) for (const stage of ["start", "message"]) {
  test(`F4 v${v} ${stage} remains 384 including attachment entrypoints`, async () => {
    const sent = [];
    const fake = async (_url, init) => { sent.push(JSON.parse(init.body)); return new Response(JSON.stringify(answer())); };
    const provider = v <= 2 ? "obi-pl" : "kwant-pl";
    const branch = v <= 2 ? "075" : "205";
    const attachments = v >= 4 ? [{ mimeType: "image/jpeg", filename: "synthetic.jpg", bytes: new Uint8Array([255, 216, 255]) }] : undefined;
    if (stage === "start") await startAgent("synthetic", provider, branch, "synthetic", fake, v, attachments);
    else await messageAgent("resp_previous", "synthetic", provider, branch, "synthetic", fake, v, attachments);
    assert.equal(sent[0].max_output_tokens, 384);
    assert.equal(sent[0].max_output_tokens, OPENAI_MAX_OUTPUT_TOKENS);
  });
}

test("F4 client budget override and malformed complex result fail before upstream", async () => {
  for (const [local, extra] of [[result(3, [2, 2, 1]), { max_output_tokens: 99999 }],
    [{ ...result(3, [2, 2, 1]), extra: true }, {}], [result(3, [3, 3]), {}]]) {
    const r = await invoke(3, "continue", local, answer(), extra);
    assert.equal(r.response.status, 400); assert.equal(r.sent.length, 0);
  }
});

test("F4 multi-location inventory result retains the base budget", async () => {
  const local = {
    providerId: "kwant-pl", productId: "1", status: "verified", reason: null,
    coverage: "requested_subset", checkedIds: ["205", "128"], returnedIds: ["205", "128"],
    missingIds: [], locations: [
      { branchId: "205", name: "Synthetic location", stock: 1 },
      { branchId: "128", name: "Synthetic location B", stock: 2 },
    ], verifiedAtMillis: 1000, centralStock: null,
  };
  const r = await invoke(3, "continue", local, answer(), { tool: "find_product_locations" });
  assert.equal(r.response.status, 200);
  assert.equal(r.sent[0].max_output_tokens, 384);
});

for (const [name, mutate, category] of [
  ["invalid final JSON", p => { p.output[0].content[0].text = "{"; }, "upstream_invalid_final_answer"],
  ["blank response ID", p => { p.id = " "; }, "upstream_invalid_envelope"],
  ["wrong tool", p => { p.output = [{ type: "function_call", name: "unknown", call_id: "call_ok", arguments: "{}" }]; }, "upstream_invalid_tool_call"],
  ["blank call ID", p => { p.output = [{ type: "function_call", name: "find_products", call_id: "\u00a0", arguments: "{}" }]; }, "upstream_invalid_tool_call"],
  ["blank requestedBranch", p => { p.output = [{ type: "function_call", name: "find_products", call_id: "call_ok", arguments: JSON.stringify({ providerId: "kwant-pl", branchId: "205", requestedBranch: " ", queries: [{ query: "synthetic", limit: 1 }] }) }]; }, "upstream_invalid_tool_call"],
]) test(`F4 complex CONTINUE still rejects ${name}`, async () => {
  const payload = answer(); mutate(payload);
  const r = await invoke(3, "continue", result(3, [2, 2, 1]), payload);
  assertFailure(r, category);
});
