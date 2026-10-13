import test from "node:test";
import assert from "node:assert/strict";
import { createWorker } from "../.test-dist/index.js";
import { parseToolArguments, InvalidRequestError } from "../.test-dist/validation.js";
import { MAX_TOOL_PRODUCTS, OBI_TOOL, PROVIDER_TOOL } from "../.test-dist/config.js";

const env = { TOWAROWNIK_APP_TOKEN: "synthetic-app", OPENAI_API_KEY: "synthetic-key" };
const traceId = "00000000-1111-4111-8111-000000000001";
const profiles = [
  { version: 2, provider: "obi-pl", branch: "075" },
  ...[3, 4, 5].flatMap(version => [
    { version, provider: "obi-pl", branch: "075" },
    { version, provider: "kwant-pl", branch: "205" },
  ]),
];
function argumentsFor(profile, limits) {
  return {
    ...(profile.version === 2 ? { storeNumber: profile.branch } : {
      providerId: profile.provider, branchId: profile.branch, requestedBranch: null,
    }),
    queries: limits.map((limit, i) => ({ query: `synthetic-${i}`, limit })),
  };
}
function toolPayload(profile, args, override = {}) {
  return { id: "resp_synthetic", status: "completed", output: [{
    type: "function_call", name: profile.version === 2 ? "find_obi_products" : "find_products",
    call_id: "call_synthetic", arguments: JSON.stringify(args), ...override,
  }] };
}
async function invoke(profile, args, { stage = "start", override = {}, continuation = null } = {}) {
  const provider = profile.version === 2 ? { storeNumber: profile.branch } : {
    providerId: profile.provider, branchId: profile.branch,
  };
  const body = { protocolVersion: profile.version, ...provider, ...(stage === "continue" ? {
    responseId: "resp_previous", callId: "call_previous",
    tool: profile.version === 2 ? "find_obi_products" : "find_products", result: continuation,
  } : { message: "PRIVATE_USER_SENTINEL", ...(stage === "message" ? { previousResponseId: "resp_previous" } : {}) }) };
  let requestBody = JSON.stringify(body);
  const headers = { Authorization: "Bearer synthetic-app", "Content-Type": "application/json", "X-Taksula-Trace-Id": traceId };
  if (profile.version >= 4 && stage !== "continue") {
    const form = new FormData(); form.set("payload", requestBody);
    form.set("attachment", new File([new Uint8Array([255, 216, 255, 224, 1])], "synthetic.jpg", { type: "image/jpeg" }));
    requestBody = form; delete headers["Content-Type"]; headers["Content-Length"] = "1024";
    if (profile.version === 5) headers["X-Taksula-Attachment-Protocol"] = "5";
  }
  const logs = [], captures = [];
  const saved = [console.info, console.warn];
  console.info = console.warn = value => logs.push(JSON.parse(value));
  let response;
  try {
    response = await createWorker(async (_url, init) => {
      captures.push(JSON.parse(init.body));
      return new Response(JSON.stringify(toolPayload(profile, args, override)), { status: 200 });
    }).fetch(new Request(`https://offline.invalid/v1/agent/${stage}`, {
      method: "POST", headers, body: requestBody,
    }), env);
  } finally { [console.info, console.warn] = saved; }
  assert.equal(logs.length, 1);
  const log = logs[0];
  assert.equal(log.httpStatus, response.status);
  assert.equal(response.headers.get("X-Taksula-Trace-Id"), log.traceId);
  assert.match(log.traceId, /^[0-9a-f-]{36}$/);
  if (stage === "continue") assert.equal(log.traceId, traceId);
  for (const sentinel of ["PRIVATE_USER_SENTINEL", "synthetic-app", "synthetic-key", "synthetic-0", "call_synthetic", "resp_synthetic"]) {
    assert.equal(JSON.stringify(log).includes(sentinel), false);
  }
  return { response, body: await response.json(), log, captures };
}

const plans = [
  [[3, 3], [3, 2]], [[5], [5]], [[1, 1, 1, 1, 1], [1, 1, 1, 1, 1]],
  [[5, 5, 5, 5, 5], [1, 1, 1, 1, 1]], [[2, 1, 2], [2, 1, 2]],
  [[5, 1, 1], [3, 1, 1]], [[1, 5], [1, 4]],
];
for (const profile of profiles) {
  const label = `v${profile.version}/${profile.provider}`;
  for (const [requested, expected] of plans) test(`F1 ${label} ${requested} -> ${expected}`, async () => {
    const args = argumentsFor(profile, requested);
    const result = await invoke(profile, args);
    assert.equal(result.response.status, 200);
    assert.deepEqual(result.body.tool.arguments, { ...args, queries: args.queries.map((q, i) => ({ ...q, limit: expected[i] })) });
    assert.equal(result.log.outcome, "success");
    assert.equal(result.log.localToolRequested, true);
    assert.equal(result.log.localQueryCount, requested.length);
    assert.equal(result.log.requestedProductLimitTotal, expected.reduce((a, b) => a + b, 0));
    assert.equal(result.log.upstreamFailureCategory, null);
  });
  for (const mutation of [
    args => { args.queries[1] = null; },
    args => { args.queries[1].extra = true; },
    args => { args.extra = true; },
    args => { args.queries[1].query = " "; },
    args => { args.queries[1].limit = 6; },
    args => { args.queries[1].limit = 0; },
    args => { args.queries[1].limit = 1.5; },
    args => { args.queries[1].limit = "3"; },
    args => { delete args.queries[1].limit; },
    args => { args.queries = Array.from({ length: 6 }, () => ({ query: "synthetic", limit: 1 })); },
  ].entries()) test(`F1 ${label} invalid shape/limit ${mutation[0]} is not normalized`, async () => {
    const args = argumentsFor(profile, [3, 3]); mutation[1](args);
    const result = await invoke(profile, args);
    assert.equal(result.response.status, 502);
    assert.equal(result.log.upstreamFailureCategory, "upstream_invalid_tool_call");
  });
  test(`F1 ${label} CONTINUE rejected query limits remain strict`, async () => {
    const args = argumentsFor(profile, [3, 3]);
    const context = profile.version === 2 ? { storeNumber: profile.branch } : { providerId: profile.provider, branchId: profile.branch };
    const result = await invoke(profile, args, { stage: "continue", continuation: {
      ...context, queries: args.queries, rejection: "local_tool_limit_reached",
    } });
    assert.equal(result.response.status, 400);
    assert.equal(result.captures.length, 0);
    assert.equal(result.log.outcome, "validation_failure");
  });
  test(`F1 ${label} unknown tool and empty call ID still rejected`, async () => {
    for (const override of [{ name: "unknown_tool" }, { call_id: "" }]) {
      const result = await invoke(profile, argumentsFor(profile, [3, 3]), { override });
      assert.equal(result.response.status, 502);
      assert.equal(result.log.upstreamFailureCategory, "upstream_invalid_tool_call");
    }
  });
}

test("F1 schema permits overflow while strict parser retains baseline rejection evidence", () => {
  for (const profile of profiles) {
    const schema = (profile.version === 2 ? OBI_TOOL : PROVIDER_TOOL).parameters.properties.queries;
    assert.equal(schema.maxItems, 5);
    assert.equal(schema.items.properties.limit.maximum, 5);
    const raw = JSON.stringify(argumentsFor(profile, [3, 3]));
    assert.throws(() => parseToolArguments(raw, profile.version), InvalidRequestError);
    const normalized = parseToolArguments(raw, profile.version, undefined, true);
    assert.deepEqual(normalized.queries.map(q => q.limit), [3, 2]);
  }
});

for (const version of [2, 3, 4, 5]) test(`F1 v${version} exhaustive valid plans preserve order and never increase limits`, () => {
  const profile = { version, provider: version === 2 ? "obi-pl" : "kwant-pl", branch: version === 2 ? "075" : "205" };
  let count = 0;
  function check(limits) {
    if (limits.length) {
      const args = argumentsFor(profile, limits);
      const output = parseToolArguments(JSON.stringify(args), version, undefined, true);
      assert.deepEqual(output.queries.map(q => q.query), args.queries.map(q => q.query));
      assert.equal(output.queries.length, args.queries.length);
      assert(output.queries.every((q, i) => q.limit >= 1 && q.limit <= limits[i]));
      assert(output.queries.reduce((sum, q) => sum + q.limit, 0) <= MAX_TOOL_PRODUCTS);
      if (limits.reduce((a, b) => a + b, 0) <= MAX_TOOL_PRODUCTS) assert.deepEqual(output, args);
      count++;
    }
    if (limits.length < 5) for (let limit = 1; limit <= 5; limit++) check([...limits, limit]);
  }
  check([]); assert.equal(count, 3905);
});

test("F1 normalized model hints retain wrong provider/branch for existing Android rejection", async () => {
  const profile = { version: 3, provider: "kwant-pl", branch: "205" };
  for (const changed of [{ providerId: "obi-pl", branchId: "075" }, { branchId: "128", requestedBranch: "Zamość" }]) {
    const args = { ...argumentsFor(profile, [3, 3]), ...changed };
    const result = await invoke(profile, args);
    assert.equal(result.response.status, 200);
    assert.deepEqual(result.body.tool.arguments, { ...args, queries: args.queries.map((q, i) => ({ ...q, limit: i === 0 ? 3 : 2 })) });
    // Authorization is Android-owned; normalization must never rewrite an unauthorized hint to the current profile.
    assert(result.body.tool.arguments.providerId !== profile.provider || result.body.tool.arguments.branchId !== profile.branch);
  }
});

test("F1 duplicate queries and explicit branch hint retain order and identity", () => {
  const args = { providerId: "kwant-pl", branchId: "205", requestedBranch: "Zamość", queries: [{ query: "same", limit: 3 }, { query: "same", limit: 3 }] };
  assert.deepEqual(parseToolArguments(JSON.stringify(args), 3, undefined, true), { ...args, queries: [{ query: "same", limit: 3 }, { query: "same", limit: 2 }] });
});

test("F1 MESSAGE and CONTINUE model outputs use the same normalized plan", async () => {
  const profile = { version: 3, provider: "kwant-pl", branch: "205" };
  const args = argumentsFor(profile, [3, 3]);
  for (const stage of ["message", "continue"]) {
    const result = await invoke(profile, args, { stage, continuation: {
      providerId: "kwant-pl", branchId: "205", results: [{ query: "synthetic", status: "not_found", products: [] }],
    } });
    assert.equal(result.response.status, 200);
    assert.deepEqual(result.body.tool.arguments.queries.map(q => q.limit), [3, 2]);
    assert.equal(result.captures[0].previous_response_id, "resp_previous");
  }
});
