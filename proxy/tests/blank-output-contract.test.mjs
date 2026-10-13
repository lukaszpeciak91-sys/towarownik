import test from "node:test";
import assert from "node:assert/strict";
import { createWorker } from "../.test-dist/index.js";
import { parseToolArguments, InvalidRequestError } from "../.test-dist/validation.js";

const env = { TOWAROWNIK_APP_TOKEN: "synthetic-app", OPENAI_API_KEY: "synthetic-key" };
const trace = "00000000-1111-4111-8111-000000000001";
const blanks = ["", " ", "\t\n\r", "\u00a0", "\u2003\u202f", "\u001c", "\u2028\u2029", "\u3000"];
function args(version, branch = null) {
  return { ...(version === 2 ? { storeNumber: "075" } : {
    providerId: "kwant-pl", branchId: "205", requestedBranch: branch,
  }), queries: [{ query: "synthetic A", limit: 3 }, { query: "synthetic B", limit: 3 }] };
}
function tool(version, options = {}) {
  return { id: options.responseId ?? "resp_synthetic", status: "completed", output: [{
    type: "function_call", name: options.name ?? (version === 2 ? "find_obi_products" : "find_products"),
    call_id: Object.hasOwn(options, "callId") ? options.callId : "call_synthetic",
    arguments: JSON.stringify(args(version, options.branch ?? null)),
  }] };
}
function answer(id) {
  return { id, status: "completed", output: [{ type: "message", content: [{
    type: "output_text", text: JSON.stringify({ text: "synthetic final", productRefs: [] }),
  }] }] };
}
function request(version, stage, resultOverride = null) {
  const context = version === 2 ? { storeNumber: "075" } : { providerId: "kwant-pl", branchId: "205" };
  const body = { protocolVersion: version, ...context, ...(stage === "continue" ? {
    responseId: " resp_previous ", callId: " call_previous ",
    tool: version === 2 ? "find_obi_products" : "find_products",
    result: resultOverride ?? { ...context, results: [{ query: "synthetic", status: "not_found", products: [] }] },
  } : { message: "PRIVATE_USER_SENTINEL", ...(stage === "message" ? { previousResponseId: " resp_previous " } : {}) }) };
  const headers = { Authorization: "Bearer synthetic-app", "Content-Type": "application/json", "X-Taksula-Trace-Id": trace };
  let data = JSON.stringify(body);
  if (version >= 4 && stage !== "continue") {
    const form = new FormData(); form.set("payload", data);
    form.set("attachment", new File([new Uint8Array([255, 216, 255, 224, 1])], "synthetic.jpg", { type: "image/jpeg" }));
    data = form; delete headers["Content-Type"]; headers["Content-Length"] = "1024";
    if (version === 5) headers["X-Taksula-Attachment-Protocol"] = "5";
  }
  return new Request(`https://offline.invalid/v1/agent/${stage}`, { method: "POST", headers, body: data });
}
async function invoke(version, payload, stage = "continue", resultOverride = null) {
  const logs = [], sent = [];
  const saved = [console.info, console.warn];
  console.info = console.warn = value => logs.push(JSON.parse(value));
  let response;
  try {
    response = await createWorker(async (_url, init) => {
      sent.push(JSON.parse(init.body)); return new Response(JSON.stringify(payload), { status: 200 });
    }).fetch(request(version, stage, resultOverride), env);
  } finally { [console.info, console.warn] = saved; }
  assert.equal(logs.length, 1);
  assert.equal(logs[0].traceId, response.headers.get("X-Taksula-Trace-Id"));
  if (stage === "continue") assert.equal(logs[0].traceId, trace);
  for (const sentinel of ["PRIVATE_USER_SENTINEL", "synthetic-app", "synthetic-key", "call_synthetic", "resp_synthetic", "synthetic A"]) {
    assert.equal(JSON.stringify(logs).includes(sentinel), false);
  }
  return { response, body: await response.json(), log: logs[0], sent };
}
async function rejected(version, payload, category) {
  const out = await invoke(version, payload);
  assert.equal(out.response.status, 502);
  assert.deepEqual(out.body, { error: "upstream_failure" });
  assert.equal(out.log.upstreamFailureCategory, category);
  assert.equal(out.log.upstreamStatus, 200);
}

for (const version of [2, 3, 4, 5]) {
  for (const [index, value] of blanks.entries()) {
    test(`INC005 F3 v${version} blank call_id case ${index} rejects before Android`, () =>
      rejected(version, tool(version, { callId: value }), "upstream_invalid_tool_call"));
    test(`INC005 responseId v${version} blank case ${index} rejects before Android`, () =>
      rejected(version, answer(value), "upstream_invalid_envelope"));
  }
  if (version >= 3) for (const [index, value] of [...blanks, "\u0000\u0085"].entries()) {
    test(`INC005 F2 v${version} normalized-blank location case ${index} rejects`, () =>
      rejected(version, tool(version, { branch: value }), "upstream_invalid_tool_call"));
  }
  for (const stage of ["start", "message", "continue"]) test(`INC005 v${version} ${stage} valid output preserves IDs and F1`, async () => {
    const id = " \u00a0resp_valid\u00a0 "; const call = " \u00a0call_valid\u00a0 ";
    const out = await invoke(version, tool(version, { responseId: id, callId: call }), stage);
    assert.equal(out.response.status, 200);
    assert.equal(out.body.responseId, id); assert.equal(out.body.tool.callId, call);
    assert.deepEqual(out.body.tool.arguments.queries.map(q => q.limit), [3, 2]);
    if (version >= 3) assert.equal(out.body.tool.arguments.requestedBranch, null);
    if (stage !== "start") assert.equal(out.sent[0].previous_response_id, " resp_previous ");
    if (stage === "continue") assert.equal(out.sent[0].input[0].call_id, " call_previous ");
    assert.equal(out.log.outcome, "success");
  });
  test(`INC005 v${version} nonblank opaque IDs are not JS-trimmed or control-normalized`, async () => {
    // Kotlin does not call FEFF or NEL whitespace; these are acceptance parity probes, not claims about generated OpenAI IDs.
    for (const value of ["\ufeff", "\u0085", "\u200b", "\u0000"]) {
      const out = await invoke(version, tool(version, { callId: value, responseId: value }));
      assert.equal(out.response.status, 200);
      assert.equal(out.body.tool.callId, value); assert.equal(out.body.responseId, value);
    }
  });
  test(`INC005 v${version} malformed IDs and wrong tool names stay strict`, async () => {
    for (const value of [null, 123, {}, "x".repeat(257)]) {
      await rejected(version, tool(version, { callId: value }), "upstream_invalid_tool_call");
      await rejected(version, answer(value), "upstream_invalid_envelope");
    }
    await rejected(version, tool(version, { name: "unknown_tool" }), "upstream_invalid_tool_call");
  });
  test(`INC005 v${version} invalid client CONTINUE results fail before upstream`, async () => {
    const out = await invoke(version, answer("resp_ok"), "continue", { extra: true });
    assert.equal(out.response.status, 400); assert.equal(out.sent.length, 0);
    assert.equal(out.log.outcome, "validation_failure");
  });
}
for (const version of [3, 4, 5]) for (const branch of ["Nowy Sącz", "w Białymstoku", "Zamość", " \u00a0Nowy Sącz\u00a0 ", "Nowy\tSącz", "\ufeff", "\u200b"]) {
  test(`INC005 F2 v${version} valid branch ${JSON.stringify(branch)} remains unchanged`, async () => {
    const out = await invoke(version, tool(version, { branch }));
    assert.equal(out.response.status, 200); assert.equal(out.body.tool.arguments.requestedBranch, branch);
  });
}
test("INC005 blank location validation rejects rather than inventing null or an authorized branch", () => {
  for (const branch of [" ", "\u00a0", "\u0000\u0085"]) {
    assert.throws(() => parseToolArguments(JSON.stringify(args(3, branch)), 3, undefined, true), InvalidRequestError);
  }
  const input = { ...args(3, "Zamość"), providerId: "obi-pl", branchId: "128" };
  const out = parseToolArguments(JSON.stringify(input), 3, undefined, true);
  assert.equal(out.providerId, input.providerId); assert.equal(out.branchId, input.branchId);
  assert.equal(out.requestedBranch, input.requestedBranch);
});
test("INC005 blank responseId also rejects a tool envelope", () =>
  rejected(3, tool(3, { responseId: "\u00a0" }), "upstream_invalid_envelope"));
