import test from "node:test";
import assert from "node:assert/strict";

import {
  AGENT_INSTRUCTIONS,
  LOCAL_TOOL_NAME,
  OPENAI_MAX_OUTPUT_TOKENS,
  OPENAI_MODEL,
  OPENAI_REASONING_EFFORT,
  OPENAI_RESPONSES_URL,
} from "../.test-dist/config.js";
import { createWorker } from "../.test-dist/index.js";

const APP_TOKEN = "test-app-token-placeholder";
const OPENAI_KEY = "test-openai-key-placeholder";

const configuredEnv = {
  TOWAROWNIK_APP_TOKEN: APP_TOKEN,
  OPENAI_API_KEY: OPENAI_KEY,
};

function jsonRequest(path, body, options = {}) {
  const headers = {
    "Content-Type": "application/json",
    ...(options.authorization === false
      ? {}
      : { Authorization: options.authorization ?? `Bearer ${APP_TOKEN}` }),
    ...(options.headers ?? {}),
  };

  return new Request(`https://proxy.example${path}`, {
    method: options.method ?? "POST",
    headers,
    body: options.rawBody ?? JSON.stringify(body),
  });
}

function answerPayload(text = "Synthetic answer") {
  return {
    id: "resp_test_answer",
    output_text: text,
    output: [
      {
        type: "message",
        content: [{ type: "output_text", text }],
      },
    ],
  };
}

function toolPayload(argumentsJson = '{"query":"synthetic query","limit":5}', name = LOCAL_TOOL_NAME) {
  return {
    id: "resp_test_tool",
    output: [
      {
        type: "function_call",
        call_id: "call_test_tool",
        name,
        arguments: argumentsJson,
      },
    ],
  };
}

function fakeOpenAI(payload, options = {}) {
  const captures = options.captures ?? [];
  const status = options.status ?? 200;
  return {
    captures,
    fetch: async (input, init) => {
      captures.push({
        url: String(input),
        method: init?.method,
        headers: new Headers(init?.headers),
        body: init?.body ? JSON.parse(String(init.body)) : null,
      });

      if (options.throwNetwork) {
        throw new Error("synthetic network failure");
      }

      const responseBody =
        typeof payload === "string" ? payload : JSON.stringify(payload);
      return new Response(responseBody, {
        status,
        headers: { "Content-Type": "application/json" },
      });
    },
  };
}

async function responseJson(response) {
  return JSON.parse(await response.text());
}

function validContinueBody(overrides = {}) {
  return {
    responseId: "resp_previous",
    callId: "call_previous",
    tool: LOCAL_TOOL_NAME,
    result: {
      query: "synthetic query",
      products: [
        {
          obik: "1234567",
          name: "Synthetic product",
          stock: 3,
          price: 19.99,
        },
      ],
    },
    ...overrides,
  };
}

test("health remains public without any configured secrets", async () => {
  const worker = createWorker(async () => {
    throw new Error("upstream must not be called");
  });

  const response = await worker.fetch(
    new Request("https://proxy.example/health"),
    {},
  );

  assert.equal(response.status, 200);
  assert.deepEqual(await responseJson(response), {
    ok: true,
    service: "towarownik-proxy",
  });
});

test("start without Authorization returns 401", async () => {
  const worker = createWorker(async () => {
    throw new Error("upstream must not be called");
  });

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", { message: "hello" }, { authorization: false }),
    configuredEnv,
  );

  assert.equal(response.status, 401);
  assert.deepEqual(await responseJson(response), { error: "unauthorized" });
});

test("start with malformed auth scheme returns 401", async () => {
  const worker = createWorker(async () => {
    throw new Error("upstream must not be called");
  });

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", { message: "hello" }, { authorization: "Basic placeholder" }),
    configuredEnv,
  );

  assert.equal(response.status, 401);
  assert.deepEqual(await responseJson(response), { error: "unauthorized" });
});

test("start with wrong app token returns 401", async () => {
  const worker = createWorker(async () => {
    throw new Error("upstream must not be called");
  });

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", { message: "hello" }, { authorization: "Bearer wrong-placeholder" }),
    configuredEnv,
  );

  assert.equal(response.status, 401);
  assert.deepEqual(await responseJson(response), { error: "unauthorized" });
});

test("missing server-side app token fails closed with 503", async () => {
  const worker = createWorker(async () => {
    throw new Error("upstream must not be called");
  });

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", { message: "hello" }),
    { OPENAI_API_KEY: OPENAI_KEY },
  );

  assert.equal(response.status, 503);
  assert.deepEqual(await responseJson(response), { error: "server_not_configured" });
});

test("missing OpenAI key returns bounded 503 after valid app auth", async () => {
  const worker = createWorker(async () => {
    throw new Error("upstream must not be called");
  });

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", { message: "hello" }),
    { TOWAROWNIK_APP_TOKEN: APP_TOKEN },
  );

  assert.equal(response.status, 503);
  assert.deepEqual(await responseJson(response), { error: "server_not_configured" });
});

test("auth and server secret values never appear in errors", async () => {
  const worker = createWorker(async () => {
    throw new Error("upstream must not be called");
  });

  const wrong = await worker.fetch(
    jsonRequest("/v1/agent/start", { message: "hello" }, { authorization: "Bearer wrong-placeholder" }),
    configuredEnv,
  );
  const missingApi = await worker.fetch(
    jsonRequest("/v1/agent/start", { message: "hello" }),
    { TOWAROWNIK_APP_TOKEN: APP_TOKEN },
  );

  const combined = (await wrong.text()) + (await missingApi.text());
  assert.equal(combined.includes(APP_TOKEN), false);
  assert.equal(combined.includes(OPENAI_KEY), false);
});

test("valid start sends only server-controlled OpenAI configuration", async () => {
  const fake = fakeOpenAI(answerPayload("Use a verified local lookup."));
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", { message: "  find   a product\nplease  " }),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  assert.deepEqual(await responseJson(response), {
    type: "answer",
    responseId: "resp_test_answer",
    text: "Use a verified local lookup.",
  });
  assert.equal(fake.captures.length, 1);

  const capture = fake.captures[0];
  assert.equal(capture.url, OPENAI_RESPONSES_URL);
  assert.equal(capture.method, "POST");
  assert.equal(capture.headers.get("Authorization"), `Bearer ${OPENAI_KEY}`);
  assert.notEqual(capture.headers.get("Authorization"), `Bearer ${APP_TOKEN}`);
  assert.equal(capture.headers.get("Content-Type"), "application/json");

  assert.equal(capture.body.model, OPENAI_MODEL);
  assert.equal(capture.body.model, "gpt-6-luna");
  assert.equal(capture.body.instructions, AGENT_INSTRUCTIONS);
  assert.equal(capture.body.input, "find a product please");
  assert.deepEqual(capture.body.reasoning, { effort: OPENAI_REASONING_EFFORT });
  assert.equal(capture.body.reasoning.effort, "low");
  assert.equal(capture.body.max_output_tokens, OPENAI_MAX_OUTPUT_TOKENS);
  assert.equal(capture.body.parallel_tool_calls, false);
  assert.equal(capture.body.store, true);
  assert.equal(capture.body.tools.length, 1);
  assert.equal(capture.body.tools[0].type, "function");
  assert.equal(capture.body.tools[0].name, LOCAL_TOOL_NAME);
  assert.equal(capture.body.tools[0].strict, true);
  assert.deepEqual(capture.body.tools[0].parameters.required, ["query", "limit"]);
  assert.equal(capture.body.tools[0].parameters.additionalProperties, false);
  assert.equal(capture.body.tools[0].parameters.properties.limit.maximum, 5);
});

test("blank start message is rejected without upstream call", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", { message: " \r\n\t " }),
    configuredEnv,
  );

  assert.equal(response.status, 400);
  assert.deepEqual(await responseJson(response), { error: "invalid_request" });
  assert.equal(fake.captures.length, 0);
});

test("malformed JSON start request is rejected", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", null, { rawBody: "{not-json" }),
    configuredEnv,
  );

  assert.equal(response.status, 400);
  assert.equal(fake.captures.length, 0);
});

test("non-JSON content type is rejected", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);

  const request = new Request("https://proxy.example/v1/agent/start", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${APP_TOKEN}`,
      "Content-Type": "text/plain",
    },
    body: '{"message":"hello"}',
  });

  const response = await worker.fetch(request, configuredEnv);
  assert.equal(response.status, 400);
  assert.equal(fake.captures.length, 0);
});

test("oversized start message returns 413", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", { message: "x".repeat(2_001) }),
    configuredEnv,
  );

  assert.equal(response.status, 413);
  assert.deepEqual(await responseJson(response), { error: "request_too_large" });
  assert.equal(fake.captures.length, 0);
});

test("client cannot inject model tools instructions or reasoning", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", {
      message: "hello",
      model: "client-model",
      tools: [],
      instructions: "client instructions",
      reasoning: { effort: "max" },
    }),
    configuredEnv,
  );

  assert.equal(response.status, 400);
  assert.equal(fake.captures.length, 0);
});

test("direct OpenAI answer is normalized and raw response is not forwarded", async () => {
  const fake = fakeOpenAI({
    id: "resp_direct",
    output_text: " concise answer ",
    output: [
      {
        type: "message",
        content: [{ type: "output_text", text: " concise answer " }],
      },
      { type: "reasoning", summary: [{ text: "internal" }] },
    ],
    usage: { total_tokens: 999 },
    instructions: "internal",
  });
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", { message: "hello" }),
    configuredEnv,
  );
  const body = await responseJson(response);

  assert.equal(response.status, 200);
  assert.deepEqual(body, {
    type: "answer",
    responseId: "resp_direct",
    text: "concise answer",
  });
  assert.equal(JSON.stringify(body).includes("usage"), false);
  assert.equal(JSON.stringify(body).includes("internal"), false);
});

test("valid known function call becomes normalized tool_request", async () => {
  const fake = fakeOpenAI(toolPayload('{"query":"  moisture   absorber ","limit":5}'));
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", { message: "find something" }),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  assert.deepEqual(await responseJson(response), {
    type: "tool_request",
    responseId: "resp_test_tool",
    tool: {
      name: LOCAL_TOOL_NAME,
      callId: "call_test_tool",
      arguments: {
        query: "moisture absorber",
        limit: 5,
      },
    },
  });
});

test("unknown model function call is a bounded upstream failure", async () => {
  const fake = fakeOpenAI(toolPayload('{"query":"x","limit":1}', "unknown_tool"));
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", { message: "hello" }),
    configuredEnv,
  );

  assert.equal(response.status, 502);
  assert.deepEqual(await responseJson(response), { error: "upstream_failure" });
});

test("malformed model function arguments are a bounded upstream failure", async () => {
  const fake = fakeOpenAI(toolPayload('{"query":"x","limit":99}'));
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", { message: "hello" }),
    configuredEnv,
  );

  assert.equal(response.status, 502);
  assert.deepEqual(await responseJson(response), { error: "upstream_failure" });
});

test("valid continue sends previous_response_id and function_call_output", async () => {
  const fake = fakeOpenAI(answerPayload("Final synthetic answer"));
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/continue", validContinueBody()),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  assert.deepEqual(await responseJson(response), {
    type: "answer",
    responseId: "resp_test_answer",
    text: "Final synthetic answer",
  });

  const capture = fake.captures[0];
  assert.equal(capture.body.model, OPENAI_MODEL);
  assert.equal(capture.body.instructions, AGENT_INSTRUCTIONS);
  assert.equal(capture.body.previous_response_id, "resp_previous");
  assert.deepEqual(capture.body.reasoning, { effort: "low" });
  assert.equal(capture.body.tools.length, 1);
  assert.equal(capture.body.tools[0].name, LOCAL_TOOL_NAME);
  assert.equal(capture.body.input.length, 1);
  assert.equal(capture.body.input[0].type, "function_call_output");
  assert.equal(capture.body.input[0].call_id, "call_previous");
  assert.deepEqual(JSON.parse(capture.body.input[0].output), validContinueBody().result);
});

test("continue can return another normalized local tool request", async () => {
  const fake = fakeOpenAI(toolPayload('{"query":"second synthetic query","limit":2}'));
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/continue", validContinueBody()),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  const body = await responseJson(response);
  assert.equal(body.type, "tool_request");
  assert.equal(body.tool.name, LOCAL_TOOL_NAME);
  assert.deepEqual(body.tool.arguments, {
    query: "second synthetic query",
    limit: 2,
  });
});

test("continue requires responseId and callId", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);
  const body = validContinueBody();
  delete body.responseId;

  const response = await worker.fetch(
    jsonRequest("/v1/agent/continue", body),
    configuredEnv,
  );

  assert.equal(response.status, 400);
  assert.equal(fake.captures.length, 0);
});

test("continue rejects unknown tool name", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/continue", validContinueBody({ tool: "other_tool" })),
    configuredEnv,
  );

  assert.equal(response.status, 400);
  assert.equal(fake.captures.length, 0);
});

test("continue rejects more than five products", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);
  const body = validContinueBody();
  body.result.products = Array.from({ length: 6 }, (_, index) => ({
    obik: String(1_000_000 + index),
    name: `Synthetic product ${index}`,
    stock: 1,
    price: 1,
  }));

  const response = await worker.fetch(
    jsonRequest("/v1/agent/continue", body),
    configuredEnv,
  );

  assert.equal(response.status, 400);
  assert.equal(fake.captures.length, 0);
});

test("continue rejects malformed OBIK", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);
  const body = validContinueBody();
  body.result.products[0].obik = "123";

  const response = await worker.fetch(
    jsonRequest("/v1/agent/continue", body),
    configuredEnv,
  );

  assert.equal(response.status, 400);
  assert.equal(fake.captures.length, 0);
});

test("continue rejects negative stock", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);
  const body = validContinueBody();
  body.result.products[0].stock = -1;

  const response = await worker.fetch(
    jsonRequest("/v1/agent/continue", body),
    configuredEnv,
  );

  assert.equal(response.status, 400);
  assert.equal(fake.captures.length, 0);
});

test("continue accepts null stock", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);
  const body = validContinueBody();
  body.result.products[0].stock = null;

  const response = await worker.fetch(
    jsonRequest("/v1/agent/continue", body),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  assert.equal(fake.captures.length, 1);
});

test("continue accepts null price", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);
  const body = validContinueBody();
  body.result.products[0].price = null;

  const response = await worker.fetch(
    jsonRequest("/v1/agent/continue", body),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  assert.equal(fake.captures.length, 1);
});

test("continue rejects malformed negative price", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);
  const body = validContinueBody();
  body.result.products[0].price = -0.01;

  const response = await worker.fetch(
    jsonRequest("/v1/agent/continue", body),
    configuredEnv,
  );

  assert.equal(response.status, 400);
  assert.equal(fake.captures.length, 0);
});

test("continue rejects arbitrary HTML-like result structures", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);
  const body = validContinueBody();
  body.result.html = "<html>synthetic raw payload</html>";

  const response = await worker.fetch(
    jsonRequest("/v1/agent/continue", body),
    configuredEnv,
  );

  assert.equal(response.status, 400);
  assert.equal(fake.captures.length, 0);
});

for (const status of [401, 429, 500]) {
  test(`OpenAI ${status} body is not leaked`, async () => {
    const upstreamSecret = `synthetic-upstream-secret-${status}`;
    const fake = fakeOpenAI(
      { error: { message: upstreamSecret, internal: "details" } },
      { status },
    );
    const worker = createWorker(fake.fetch);

    const response = await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "hello" }),
      configuredEnv,
    );
    const text = await response.text();

    assert.equal(response.status, 502);
    assert.equal(text.includes(upstreamSecret), false);
    assert.deepEqual(JSON.parse(text), { error: "upstream_failure" });
  });
}

test("malformed OpenAI JSON becomes bounded upstream failure", async () => {
  const fake = fakeOpenAI("{not-json");
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", { message: "hello" }),
    configuredEnv,
  );

  assert.equal(response.status, 502);
  assert.deepEqual(await responseJson(response), { error: "upstream_failure" });
});

test("OpenAI transport exception becomes bounded upstream failure without retry", async () => {
  const fake = fakeOpenAI(answerPayload(), { throwNetwork: true });
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", { message: "hello" }),
    configuredEnv,
  );

  assert.equal(response.status, 502);
  assert.deepEqual(await responseJson(response), { error: "upstream_failure" });
  assert.equal(fake.captures.length, 1);
});

test("protected agent endpoint rejects unsupported method after authentication", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    new Request("https://proxy.example/v1/agent/start", {
      method: "GET",
      headers: { Authorization: `Bearer ${APP_TOKEN}` },
    }),
    configuredEnv,
  );

  assert.equal(response.status, 405);
  assert.equal(response.headers.get("Allow"), "POST");
  assert.equal(fake.captures.length, 0);
});
