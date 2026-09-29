import test from "node:test";
import assert from "node:assert/strict";

import { LOCAL_TOOL_NAME } from "../.test-dist/config.js";
import { createWorker } from "../.test-dist/index.js";

const APP_TOKEN = "protocol-test-app-token";
const OPENAI_KEY = "protocol-test-openai-key";

const configuredEnv = {
  TOWAROWNIK_APP_TOKEN: APP_TOKEN,
  OPENAI_API_KEY: OPENAI_KEY,
};

function request(path, body) {
  return new Request(`https://proxy.example${path}`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${APP_TOKEN}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });
}

function answerPayload(text = "Synthetic answer") {
  const structured = JSON.stringify({
    text,
    productRefs: [],
  });
  return {
    id: "resp_answer",
    output: [
      {
        type: "message",
        content: [
          {
            type: "output_text",
            text: structured,
          },
        ],
      },
    ],
  };
}

function toolPayload(argumentsValue) {
  return {
    id: "resp_tool",
    output: [
      {
        type: "function_call",
        call_id: "call_tool",
        name: LOCAL_TOOL_NAME,
        arguments: JSON.stringify(argumentsValue),
      },
    ],
  };
}

function fakeOpenAI(payload) {
  const captures = [];
  return {
    captures,
    fetch: async (input, init) => {
      captures.push({
        url: String(input),
        body: JSON.parse(String(init?.body)),
      });
      return new Response(JSON.stringify(payload), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    },
  };
}

async function responseJson(response) {
  return JSON.parse(await response.text());
}

function parseLegacyToolRequest(value) {
  assert.equal(value.type, "tool_request");
  assert.deepEqual(
    Object.keys(value.tool.arguments).sort(),
    ["limit", "query", "storeNumber"],
  );
  assert.equal(
    Object.prototype.hasOwnProperty.call(
      value.tool.arguments,
      "queries",
    ),
    false,
  );
  return value;
}

test("unversioned grouped Android client remains v2-compatible during rolling deployment", async () => {
  const fake = fakeOpenAI(
    toolPayload({
      storeNumber: "075",
      queries: [
        { query: "klej", limit: 2 },
      ],
    }),
  );
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    request("/v1/agent/start", {
      message: "synthetic unversioned grouped request",
      storeNumber: "075",
    }),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  const body = await responseJson(response);
  assert.equal(body.type, "tool_request");
  assert.deepEqual(body.tool.arguments, {
    storeNumber: "075",
    queries: [
      { query: "klej", limit: 2 },
    ],
  });

  const upstreamTool =
    fake.captures[0].body.tools.find(
      (tool) => tool.type === "function",
    );
  assert.deepEqual(
    upstreamTool.parameters.required,
    ["storeNumber", "queries"],
  );
});

test("protocol v2 Android request receives grouped queries tool request", async () => {
  const fake = fakeOpenAI(
    toolPayload({
      storeNumber: "075",
      queries: [
        { query: "silikon", limit: 2 },
        { query: "pistolet", limit: 1 },
      ],
    }),
  );
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    request("/v1/agent/start", {
      protocolVersion: 2,
      message: "synthetic v2 request",
      storeNumber: "075",
    }),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  const body = await responseJson(response);
  assert.equal(body.type, "tool_request");
  assert.deepEqual(body.tool.arguments, {
    storeNumber: "075",
    queries: [
      { query: "silikon", limit: 2 },
      { query: "pistolet", limit: 1 },
    ],
  });
});

test("legacy CONTINUE shape is accepted and can produce a final answer", async () => {
  const fake = fakeOpenAI(answerPayload("Legacy final"));
  const worker = createWorker(fake.fetch);
  const legacyResult = {
    query: "klej",
    storeNumber: "075",
    products: [],
  };

  const response = await worker.fetch(
    request("/v1/agent/continue", {
      protocolVersion: 1,
      responseId: "resp_previous",
      callId: "call_previous",
      storeNumber: "075",
      tool: LOCAL_TOOL_NAME,
      result: legacyResult,
    }),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  const body = await responseJson(response);
  assert.equal(body.type, "answer");
  assert.equal(body.text, "Legacy final");
  assert.deepEqual(
    JSON.parse(fake.captures[0].body.input[0].output),
    legacyResult,
  );
});

test("protocol v2 CONTINUE shape is accepted and can produce a final answer", async () => {
  const fake = fakeOpenAI(answerPayload("V2 final"));
  const worker = createWorker(fake.fetch);
  const v2Result = {
    storeNumber: "075",
    results: [
      {
        query: "klej",
        status: "not_found",
        products: [],
      },
    ],
  };

  const response = await worker.fetch(
    request("/v1/agent/continue", {
      protocolVersion: 2,
      responseId: "resp_previous",
      callId: "call_previous",
      storeNumber: "075",
      tool: LOCAL_TOOL_NAME,
      result: v2Result,
    }),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  const body = await responseJson(response);
  assert.equal(body.type, "answer");
  assert.equal(body.text, "V2 final");
  assert.deepEqual(
    JSON.parse(fake.captures[0].body.input[0].output),
    v2Result,
  );
});

test("explicit protocol v1 receives the legacy single-query tool contract", async () => {
  const fake = fakeOpenAI(
    toolPayload({
      query: "klej",
      storeNumber: "075",
      limit: 2,
    }),
  );
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    request("/v1/agent/start", {
      protocolVersion: 1,
      message: "synthetic explicit legacy request",
      storeNumber: "075",
    }),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  const body = parseLegacyToolRequest(
    await responseJson(response),
  );
  assert.deepEqual(body.tool.arguments, {
    query: "klej",
    storeNumber: "075",
    limit: 2,
  });

  const upstreamTool =
    fake.captures[0].body.tools.find(
      (tool) => tool.type === "function",
    );
  assert.deepEqual(
    upstreamTool.parameters.required,
    ["query", "storeNumber", "limit"],
  );
});

test("unversioned grouped CONTINUE remains accepted as v2", async () => {
  const fake = fakeOpenAI(answerPayload("Unversioned v2 final"));
  const worker = createWorker(fake.fetch);
  const v2Result = {
    storeNumber: "075",
    results: [
      {
        query: "klej",
        status: "not_found",
        products: [],
      },
    ],
  };

  const response = await worker.fetch(
    request("/v1/agent/continue", {
      responseId: "resp_previous",
      callId: "call_previous",
      storeNumber: "075",
      tool: LOCAL_TOOL_NAME,
      result: v2Result,
    }),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  const body = await responseJson(response);
  assert.equal(body.type, "answer");
  assert.equal(body.text, "Unversioned v2 final");
  assert.deepEqual(
    JSON.parse(fake.captures[0].body.input[0].output),
    v2Result,
  );
});

test("unsupported future protocol version fails explicitly before upstream work", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    request("/v1/agent/start", {
      protocolVersion: 3,
      message: "future client",
      storeNumber: "075",
    }),
    configuredEnv,
  );

  assert.equal(response.status, 400);
  assert.deepEqual(
    await responseJson(response),
    { error: "unsupported_protocol_version" },
  );
  assert.equal(fake.captures.length, 0);
});
