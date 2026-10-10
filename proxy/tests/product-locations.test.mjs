import test from "node:test";
import assert from "node:assert/strict";
import { createWorker } from "../.test-dist/index.js";
import {
  LOCATIONS_LOCAL_TOOL_NAME,
  LOCATIONS_CAPABILITY_HEADER,
  LOCATIONS_TOOL,
} from "../.test-dist/config.js";

const env = {
  TOWAROWNIK_APP_TOKEN: "fixture-token",
  OPENAI_API_KEY: "fixture-openai-key",
};
const args = { providerId: "obi-pl", productId: "3496072", locations: ["075"] };
const locationCall = {
  id: "resp_locations",
  output: [{
    type: "function_call",
    name: LOCATIONS_LOCAL_TOOL_NAME,
    call_id: "call_locations",
    arguments: JSON.stringify(args),
  }],
};
const answer = {
  id: "resp_final",
  output: [{
    type: "message",
    content: [{
      type: "output_text",
      text: JSON.stringify({ text: "Sprawdziłam wskazany market OBI.", productRefs: [] }),
    }],
  }],
};
const result = {
  providerId: "obi-pl",
  productId: "3496072",
  status: "verified",
  reason: null,
  coverage: "requested_subset",
  checkedIds: ["075"],
  returnedIds: ["075"],
  missingIds: [],
  locations: [{ branchId: "075", name: "Nowy Sącz", stock: 0 }],
  verifiedAtMillis: 123456,
  centralStock: null,
};

function request(path, body, optIn = false) {
  return new Request("https://proxy.example" + path, {
    method: "POST",
    headers: {
      Authorization: "Bearer " + env.TOWAROWNIK_APP_TOKEN,
      "Content-Type": "application/json",
      ...(optIn ? { [LOCATIONS_CAPABILITY_HEADER]: "1" } : {}),
    },
    body: JSON.stringify(body),
  });
}

function fake(payload) {
  const captures = [];
  return {
    captures,
    fetch: async (_url, init) => {
      captures.push(JSON.parse(init.body));
      return new Response(JSON.stringify(payload), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    },
  };
}

test("locations function is a distinct bounded strict schema", () => {
  assert.equal(LOCATIONS_TOOL.name, "find_product_locations");
  assert.deepEqual(LOCATIONS_TOOL.parameters.required, [
    "providerId", "productId", "locations",
  ]);
  assert.equal(LOCATIONS_TOOL.parameters.additionalProperties, false);
  assert.equal(LOCATIONS_TOOL.parameters.properties.locations.maxItems, 20);
});

test("old v2 and v3 clients are not offered location tool", async () => {
  for (const v of [2, 3]) {
    const f = fake(answer);
    const body = v === 2
      ? { protocolVersion: v, message: "Zwykłe wyszukiwanie", storeNumber: "075" }
      : { protocolVersion: v, message: "Zwykłe wyszukiwanie", providerId: "kwant-pl", branchId: "205" };
    const reply = await createWorker(f.fetch).fetch(
      request("/v1/agent/start", body, false), env,
    );
    assert.equal(reply.status, 200);
    assert.equal(f.captures.length, 1);
    assert.equal(f.captures[0].tools.some(t => t.name === LOCATIONS_LOCAL_TOOL_NAME), false);
    assert.equal(f.captures[0].tools.some(t => t.name === (v === 2 ? "find_obi_products" : "find_products")), true);
  }
});

test("v2 opt-in requests one new function and preserves original discovery tool", async () => {
  const f = fake(locationCall);
  const reply = await createWorker(f.fetch).fetch(request("/v1/agent/message", {
    protocolVersion: 2,
    previousResponseId: "resp_old",
    message: "Sprawdź stan tego produktu w OBI 075",
    storeNumber: "075",
  }, true), env);
  assert.equal(reply.status, 200);
  const output = await reply.json();
  assert.equal(output.type, "tool_request");
  assert.equal(output.tool.name, LOCATIONS_LOCAL_TOOL_NAME);
  assert.deepEqual(output.tool.arguments, args);
  assert.equal(f.captures[0].previous_response_id, "resp_old");
  assert.deepEqual(f.captures[0].tools.filter(t => t.type === "function").map(t => t.name),
    ["find_obi_products", LOCATIONS_LOCAL_TOOL_NAME]);
  assert.match(f.captures[0].instructions, /only for explicit/i);
});

test("v2 opt-in continuation accepts typed zero and retains version", async () => {
  const f = fake(answer);
  const reply = await createWorker(f.fetch).fetch(request("/v1/agent/continue", {
    protocolVersion: 2,
    responseId: "resp_locations",
    callId: "call_locations",
    storeNumber: "075",
    tool: LOCATIONS_LOCAL_TOOL_NAME,
    result,
  }, true), env);
  assert.equal(reply.status, 200);
  const output = await reply.json();
  assert.equal(output.type, "answer");
  const input = f.captures[0].input[0];
  assert.equal(input.type, "function_call_output");
  assert.equal(JSON.parse(input.output).locations[0].stock, 0);
});

test("v3 opt-in location tool and continuation preserve provider context", async () => {
  const tool = fake(locationCall);
  const start = await createWorker(tool.fetch).fetch(request("/v1/agent/start", {
    protocolVersion: 3, message: "Sprawdź też inne markety",
    providerId: "obi-pl", branchId: "075",
  }, true), env);
  assert.equal(start.status, 200);
  assert.equal((await start.json()).tool.name, LOCATIONS_LOCAL_TOOL_NAME);
  assert.equal(tool.captures[0].tools.some(t => t.name === "find_products"), true);
  const f = fake(answer);
  const continued = await createWorker(f.fetch).fetch(request("/v1/agent/continue", {
    protocolVersion: 3,
    responseId: "resp_locations", callId: "call_locations",
    providerId: "obi-pl", branchId: "075",
    tool: LOCATIONS_LOCAL_TOOL_NAME, result,
  }, true), env);
  assert.equal(continued.status, 200);
});

test("missing opt-in rejects location continuations for old Android clients", async () => {
  const f = fake(answer);
  const reply = await createWorker(f.fetch).fetch(request("/v1/agent/continue", {
    protocolVersion: 2,
    responseId: "resp_locations",
    callId: "call_locations",
    storeNumber: "075",
    tool: LOCATIONS_LOCAL_TOOL_NAME,
    result,
  }, false), env);
  assert.equal(reply.status, 400);
  assert.equal(f.captures.length, 0);
});

test("malformed quantities and coverage never reach model", async () => {
  for (const candidate of [
    { ...result, locations: [{ ...result.locations[0], stock: -1 }] },
    { ...result, locations: [{ ...result.locations[0], stock: "0" }] },
    { ...result, locations: [{ ...result.locations[0], stock: 0.5 }] },
    { ...result, missingIds: ["075"] },
    { ...result, returnedIds: ["999"] },
    { ...result, centralStock: -3 },
    { ...result, extra: "invalid" },
  ]) {
    const f = fake(answer);
    const reply = await createWorker(f.fetch).fetch(request("/v1/agent/continue", {
      protocolVersion: 3, responseId: "resp_locations", callId: "call_locations",
      providerId: "obi-pl", branchId: "075", tool: LOCATIONS_LOCAL_TOOL_NAME,
      result: candidate,
    }, true), env);
    assert.equal(reply.status, 400);
    assert.equal(f.captures.length, 0);
  }
});

test("legacy tool response from model fails without opt-in", async () => {
  const f = fake(locationCall);
  const reply = await createWorker(f.fetch).fetch(request("/v1/agent/start", {
    protocolVersion: 2, message: "test", storeNumber: "075",
  }, false), env);
  assert.equal(reply.status, 502);
});
