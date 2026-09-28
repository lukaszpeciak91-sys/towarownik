import test from "node:test";
import assert from "node:assert/strict";

import {
  AGENT_INSTRUCTIONS,
  agentInstructionsForStore,
  FINAL_ANSWER_FORMAT,
  LOCAL_TOOL_NAME,
  CURRENT_MODEL_PRICING,
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
  const normalizedBody =
    body &&
    typeof body === "object" &&
    !Array.isArray(body) &&
    path.startsWith("/v1/agent/") &&
    options.injectStore !== false &&
    !Object.prototype.hasOwnProperty.call(body, "storeNumber")
      ? { ...body, storeNumber: "075" }
      : body;
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
    body: options.rawBody ?? JSON.stringify(normalizedBody),
  });
}

function answerPayload(text = "Synthetic answer", productRefs = []) {
  const normalizedRefs = productRefs.map((value) =>
    typeof value === "string"
      ? { storeNumber: "075", obik: value }
      : value,
  );
  const structured = JSON.stringify({ text, productRefs: normalizedRefs });
  return {
    id: "resp_test_answer",
    output_text: structured,
    output: [
      {
        type: "message",
        content: [{ type: "output_text", text: structured }],
      },
    ],
  };
}

function withUsage(payload, overrides = {}) {
  return {
    ...payload,
    model: OPENAI_MODEL,
    usage: {
      input_tokens: 1_000,
      input_tokens_details: {
        cached_tokens: 400,
        cache_write_tokens: 0,
      },
      output_tokens: 100,
      output_tokens_details: { reasoning_tokens: 50 },
      total_tokens: 1_100,
      ...overrides,
    },
  };
}

function toolPayload(argumentsJson = '{"query":"synthetic query","storeNumber":"075","limit":5}', name = LOCAL_TOOL_NAME) {
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
    storeNumber: "075",
    tool: LOCAL_TOOL_NAME,
    result: {
      query: "synthetic query",
      storeNumber: "075",
      products: [
        {
          obik: "1234567",
          name: "Synthetic product",
          brand: "Synthetic Brand",
          shortDescription: "Compact verified description.",
          technicalFacts: [
            { label: "Moc", value: "600 W" },
          ],
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
    productRefs: [],
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
  assert.equal(capture.body.instructions, agentInstructionsForStore("075"));
  assert.equal(capture.body.input, "find a product please");
  assert.deepEqual(capture.body.reasoning, { effort: OPENAI_REASONING_EFFORT });
  assert.equal(capture.body.reasoning.effort, "low");
  assert.equal(capture.body.max_output_tokens, OPENAI_MAX_OUTPUT_TOKENS);
  assert.equal(capture.body.parallel_tool_calls, false);
  assert.equal(capture.body.store, true);
  assert.deepEqual(capture.body.text, { format: FINAL_ANSWER_FORMAT });
  assert.equal(capture.body.text.format.type, "json_schema");
  assert.equal(capture.body.text.format.strict, true);
  assert.equal(capture.body.tools.length, 1);
  assert.equal(capture.body.tools[0].type, "function");
  assert.equal(capture.body.tools[0].name, LOCAL_TOOL_NAME);
  assert.equal(capture.body.tools[0].strict, true);
  assert.deepEqual(capture.body.tools[0].parameters.required, ["query", "storeNumber", "limit"]);
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

test("structured OpenAI answer is normalized and raw response is not forwarded", async () => {
  const structured = JSON.stringify({
    text: " concise answer ",
    productRefs: [
      { storeNumber: "075", obik: "1234567" },
      { storeNumber: "075", obik: "1234567" },
      { storeNumber: "075", obik: "7654321" },
    ],
  });
  const fake = fakeOpenAI({
    id: "resp_direct",
    output_text: structured,
    output: [
      {
        type: "message",
        content: [{ type: "output_text", text: structured }],
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
    productRefs: [
      { storeNumber: "075", obik: "1234567" },
      { storeNumber: "075", obik: "7654321" },
    ],
  });
  assert.equal(JSON.stringify(body).includes("usage"), false);
  assert.equal(JSON.stringify(body).includes("internal"), false);
});

test("valid OpenAI usage is normalized with cached and reasoning detail", async () => {
  const fake = fakeOpenAI(withUsage(answerPayload("Measured answer")));
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", { message: "hello" }),
    configuredEnv,
  );
  const body = await responseJson(response);

  assert.equal(response.status, 200);
  assert.deepEqual(body.usage, {
    model: OPENAI_MODEL,
    requestType: "START",
    inputTokens: 1_000,
    cachedInputTokens: 400,
    cacheWriteTokens: 0,
    outputTokens: 100,
    reasoningTokens: 50,
    totalTokens: 1_100,
    estimatedCostUsd: 0.000114,
    pricingVersion: CURRENT_MODEL_PRICING.pricingVersion,
  });
});

test("reasoning tokens are output detail and are not double charged", async () => {
  const fake = fakeOpenAI(withUsage(answerPayload()));
  const worker = createWorker(fake.fetch);

  const body = await responseJson(
    await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "hello" }),
      configuredEnv,
    ),
  );

  assert.equal(body.usage.outputTokens, 100);
  assert.equal(body.usage.reasoningTokens, 50);
  assert.equal(body.usage.estimatedCostUsd, 0.000114);
});

test("current gpt-6-luna pricing is explicit and versioned", () => {
  assert.equal(CURRENT_MODEL_PRICING.model, "gpt-6-luna");
  assert.deepEqual(CURRENT_MODEL_PRICING.usdPerMillionTokens, {
    uncachedInput: "0.10",
    cachedInput: "0.01",
    cacheWriteInput: "0.125",
    output: "0.50",
  });
  assert.equal(
    CURRENT_MODEL_PRICING.pricingVersion,
    "openai-gpt-6-luna-2026-09-27-v1",
  );
});

test("cache-write tokens are parsed and charged at USD 0.125 per million", async () => {
  const fake = fakeOpenAI(
    withUsage(answerPayload(), {
      input_tokens: 1_000,
      input_tokens_details: {
        cached_tokens: 400,
        cache_write_tokens: 100,
      },
      output_tokens: 100,
      output_tokens_details: { reasoning_tokens: 50 },
      total_tokens: 1_100,
    }),
  );
  const worker = createWorker(fake.fetch);

  const body = await responseJson(
    await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "hello" }),
      configuredEnv,
    ),
  );

  assert.equal(body.usage.cacheWriteTokens, 100);
  assert.equal(body.usage.estimatedCostUsd, 0.0001165);
});

test("GPT-6 ordinary input uses the USD 0.10 per million standard rate", async () => {
  const fake = fakeOpenAI(
    withUsage(answerPayload(), {
      input_tokens: 100_000,
      input_tokens_details: {
        cached_tokens: 0,
        cache_write_tokens: 0,
      },
      output_tokens: 0,
      output_tokens_details: { reasoning_tokens: 0 },
      total_tokens: 100_000,
    }),
  );
  const worker = createWorker(fake.fetch);

  const body = await responseJson(
    await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "hello" }),
      configuredEnv,
    ),
  );

  assert.equal(body.usage.estimatedCostUsd, 0.01);
});

test("GPT-6 cached input uses the USD 0.01 per million standard rate", async () => {
  const fake = fakeOpenAI(
    withUsage(answerPayload(), {
      input_tokens: 100_000,
      input_tokens_details: {
        cached_tokens: 100_000,
        cache_write_tokens: 0,
      },
      output_tokens: 0,
      output_tokens_details: { reasoning_tokens: 0 },
      total_tokens: 100_000,
    }),
  );
  const worker = createWorker(fake.fetch);

  const body = await responseJson(
    await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "hello" }),
      configuredEnv,
    ),
  );

  assert.equal(body.usage.estimatedCostUsd, 0.001);
});

test("GPT-6 cache-write input uses the USD 0.125 per million standard rate", async () => {
  const fake = fakeOpenAI(
    withUsage(answerPayload(), {
      input_tokens: 100_000,
      input_tokens_details: {
        cached_tokens: 0,
        cache_write_tokens: 100_000,
      },
      output_tokens: 0,
      output_tokens_details: { reasoning_tokens: 0 },
      total_tokens: 100_000,
    }),
  );
  const worker = createWorker(fake.fetch);

  const body = await responseJson(
    await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "hello" }),
      configuredEnv,
    ),
  );

  assert.equal(body.usage.estimatedCostUsd, 0.0125);
});

test("GPT-6 output pricing is USD 0.50 per million", async () => {
  const fake = fakeOpenAI(
    withUsage(answerPayload(), {
      input_tokens: 0,
      input_tokens_details: {
        cached_tokens: 0,
        cache_write_tokens: 0,
      },
      output_tokens: 1_000_000,
      output_tokens_details: { reasoning_tokens: 500_000 },
      total_tokens: 1_000_000,
    }),
  );
  const worker = createWorker(fake.fetch);

  const body = await responseJson(
    await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "hello" }),
      configuredEnv,
    ),
  );

  assert.equal(body.usage.estimatedCostUsd, 0.5);
  assert.equal(body.usage.reasoningTokens, 500_000);
});

test("GPT-6 combined short-context request cost is exact", async () => {
  const fake = fakeOpenAI(
    withUsage(answerPayload(), {
      input_tokens: 1_000,
      input_tokens_details: {
        cached_tokens: 400,
        cache_write_tokens: 100,
      },
      output_tokens: 100,
      output_tokens_details: { reasoning_tokens: 50 },
      total_tokens: 1_100,
    }),
  );
  const worker = createWorker(fake.fetch);

  const body = await responseJson(
    await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "hello" }),
      configuredEnv,
    ),
  );

  assert.equal(body.usage.estimatedCostUsd, 0.0001165);
});

test("cached plus cache-write tokens cannot exceed input tokens", async () => {
  const fake = fakeOpenAI(
    withUsage(answerPayload("Still usable"), {
      input_tokens: 100,
      input_tokens_details: {
        cached_tokens: 60,
        cache_write_tokens: 50,
      },
      output_tokens: 10,
      total_tokens: 110,
    }),
  );
  const worker = createWorker(fake.fetch);

  const body = await responseJson(
    await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "hello" }),
      configuredEnv,
    ),
  );

  assert.equal(body.text, "Still usable");
  assert.equal(Object.prototype.hasOwnProperty.call(body, "usage"), false);
});

test("272000 input tokens use short-context pricing", async () => {
  const fake = fakeOpenAI(
    withUsage(answerPayload(), {
      input_tokens: 272_000,
      input_tokens_details: {
        cached_tokens: 0,
        cache_write_tokens: 0,
      },
      output_tokens: 100,
      output_tokens_details: { reasoning_tokens: 50 },
      total_tokens: 272_100,
    }),
  );
  const worker = createWorker(fake.fetch);

  const body = await responseJson(
    await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "hello" }),
      configuredEnv,
    ),
  );

  assert.equal(body.usage.estimatedCostUsd, 0.02725);
});

test("272001 input tokens use long-context pricing for the full request", async () => {
  const fake = fakeOpenAI(
    withUsage(answerPayload(), {
      input_tokens: 272_001,
      input_tokens_details: {
        cached_tokens: 0,
        cache_write_tokens: 0,
      },
      output_tokens: 100,
      output_tokens_details: { reasoning_tokens: 50 },
      total_tokens: 272_101,
    }),
  );
  const worker = createWorker(fake.fetch);

  const body = await responseJson(
    await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "hello" }),
      configuredEnv,
    ),
  );

  assert.equal(body.usage.estimatedCostUsd, 0.0544752);
});

test("long-context pricing doubles cache writes and other input-side rates", async () => {
  const fake = fakeOpenAI(
    withUsage(answerPayload(), {
      input_tokens: 272_001,
      input_tokens_details: {
        cached_tokens: 100_000,
        cache_write_tokens: 100_000,
      },
      output_tokens: 100,
      output_tokens_details: { reasoning_tokens: 50 },
      total_tokens: 272_101,
    }),
  );
  const worker = createWorker(fake.fetch);

  const body = await responseJson(
    await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "hello" }),
      configuredEnv,
    ),
  );

  assert.equal(body.usage.estimatedCostUsd, 0.0414752);
});

test("missing cache-write detail leaves cost unpriced instead of guessing", async () => {
  const fake = fakeOpenAI(
    withUsage(answerPayload(), {
      input_tokens_details: { cached_tokens: 400 },
    }),
  );
  const worker = createWorker(fake.fetch);

  const body = await responseJson(
    await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "hello" }),
      configuredEnv,
    ),
  );

  assert.equal(body.usage.cacheWriteTokens, null);
  assert.equal(body.usage.estimatedCostUsd, null);
  assert.equal(body.usage.pricingVersion, null);
});

test("unknown model usage is preserved but not priced", async () => {
  const fake = fakeOpenAI({
    ...withUsage(answerPayload()),
    model: "unknown-model",
  });
  const worker = createWorker(fake.fetch);

  const body = await responseJson(
    await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "hello" }),
      configuredEnv,
    ),
  );

  assert.equal(body.usage.model, "unknown-model");
  assert.equal(body.usage.estimatedCostUsd, null);
  assert.equal(body.usage.pricingVersion, null);
});

test("usage request type identifies MESSAGE and CONTINUE", async () => {
  const messageFake = fakeOpenAI(withUsage(answerPayload()));
  const messageWorker = createWorker(messageFake.fetch);
  const messageBody = await responseJson(
    await messageWorker.fetch(
      jsonRequest("/v1/agent/message", {
        previousResponseId: "resp_previous",
        message: "follow-up",
      }),
      configuredEnv,
    ),
  );
  assert.equal(messageBody.usage.requestType, "MESSAGE");

  const continueFake = fakeOpenAI(withUsage(answerPayload()));
  const continueWorker = createWorker(continueFake.fetch);
  const continueBody = await responseJson(
    await continueWorker.fetch(
      jsonRequest("/v1/agent/continue", validContinueBody()),
      configuredEnv,
    ),
  );
  assert.equal(continueBody.usage.requestType, "CONTINUE");
});

test("malformed usage fails soft and does not hide a valid answer", async () => {
  const payload = withUsage(answerPayload("Still usable"), {
    input_tokens: "not-a-number",
  });
  const fake = fakeOpenAI(payload);
  const worker = createWorker(fake.fetch);

  const body = await responseJson(
    await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "hello" }),
      configuredEnv,
    ),
  );

  assert.equal(body.text, "Still usable");
  assert.equal(Object.prototype.hasOwnProperty.call(body, "usage"), false);
});

test("normalized usage is bounded and exposes no raw OpenAI internals", async () => {
  const payload = withUsage(answerPayload("Safe"), {
    input_tokens_details: {
      cached_tokens: 400,
      raw_secret_detail: "must-not-leak",
    },
    output_tokens_details: {
      reasoning_tokens: 50,
      reasoning_text: "must-not-leak",
    },
  });
  payload.raw_internal = "must-not-leak";
  const fake = fakeOpenAI(payload);
  const worker = createWorker(fake.fetch);

  const body = await responseJson(
    await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "hello" }),
      configuredEnv,
    ),
  );
  assert.deepEqual(Object.keys(body.usage).sort(), [
    "cachedInputTokens",
    "cacheWriteTokens",
    "estimatedCostUsd",
    "inputTokens",
    "model",
    "outputTokens",
    "pricingVersion",
    "reasoningTokens",
    "requestType",
    "totalTokens",
  ].sort());
  assert.equal(JSON.stringify(body).includes("must-not-leak"), false);
});

test("malformed structured final output fails closed", async () => {
  const fake = fakeOpenAI({
    id: "resp_bad_structured",
    output_text: JSON.stringify({ text: "missing product selection" }),
    output: [],
  });
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", { message: "hello" }),
    configuredEnv,
  );

  assert.equal(response.status, 502);
  assert.deepEqual(await responseJson(response), { error: "upstream_failure" });
});

test("structured final output rejects more than five selected product refs", async () => {
  const fake = fakeOpenAI(
    answerPayload(
      "Too many",
      ["1000001", "1000002", "1000003", "1000004", "1000005", "1000006"],
    ),
  );
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", { message: "hello" }),
    configuredEnv,
  );

  assert.equal(response.status, 502);
  assert.deepEqual(await responseJson(response), { error: "upstream_failure" });
});

test("valid known function call becomes normalized tool_request", async () => {
  const fake = fakeOpenAI(toolPayload('{"query":"  moisture   absorber ","storeNumber":"075","limit":5}'));
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
        storeNumber: "075",
        limit: 5,
      },
    },
  });
});

test("unknown model function call is a bounded upstream failure", async () => {
  const fake = fakeOpenAI(toolPayload('{"query":"x","storeNumber":"075","limit":1}', "unknown_tool"));
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", { message: "hello" }),
    configuredEnv,
  );

  assert.equal(response.status, 502);
  assert.deepEqual(await responseJson(response), { error: "upstream_failure" });
});

test("malformed model function arguments are a bounded upstream failure", async () => {
  const fake = fakeOpenAI(toolPayload('{"query":"x","storeNumber":"075","limit":99}'));
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", { message: "hello" }),
    configuredEnv,
  );

  assert.equal(response.status, 502);
  assert.deepEqual(await responseJson(response), { error: "upstream_failure" });
});

test("message without Authorization returns 401", async () => {
  const worker = createWorker(async () => {
    throw new Error("upstream must not be called");
  });

  const response = await worker.fetch(
    jsonRequest(
      "/v1/agent/message",
      { previousResponseId: "resp_previous", message: "hello" },
      { authorization: false },
    ),
    configuredEnv,
  );

  assert.equal(response.status, 401);
  assert.deepEqual(await responseJson(response), { error: "unauthorized" });
});

test("valid message chains previous response with server-controlled configuration", async () => {
  const fake = fakeOpenAI(answerPayload("Follow-up answer"));
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/message", {
      previousResponseId: "resp_previous",
      message: "A coś tańszego?",
    }),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  const capture = fake.captures[0];
  assert.equal(capture.body.previous_response_id, "resp_previous");
  assert.equal(capture.body.input, "A coś tańszego?");
  assert.equal(capture.body.model, OPENAI_MODEL);
  assert.equal(capture.body.instructions, agentInstructionsForStore("075"));
  assert.equal(capture.body.reasoning.effort, "low");
  assert.equal(capture.body.tools.length, 1);
  assert.equal(capture.body.tools[0].name, LOCAL_TOOL_NAME);
  assert.deepEqual(capture.body.text, { format: FINAL_ANSWER_FORMAT });
});

test("message rejects extra fields and oversized inputs", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);

  const extra = await worker.fetch(
    jsonRequest("/v1/agent/message", {
      previousResponseId: "resp_previous",
      message: "hello",
      model: "client-model",
    }),
    configuredEnv,
  );
  assert.equal(extra.status, 400);

  const longId = await worker.fetch(
    jsonRequest("/v1/agent/message", {
      previousResponseId: "r".repeat(257),
      message: "hello",
    }),
    configuredEnv,
  );
  assert.equal(longId.status, 400);

  const longMessage = await worker.fetch(
    jsonRequest("/v1/agent/message", {
      previousResponseId: "resp_previous",
      message: "x".repeat(2_001),
    }),
    configuredEnv,
  );
  assert.equal(longMessage.status, 413);
  assert.equal(fake.captures.length, 0);
});

test("message upstream failure is bounded and not retried", async () => {
  const fake = fakeOpenAI(
    { error: { message: "synthetic upstream detail" } },
    { status: 500 },
  );
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/message", {
      previousResponseId: "resp_previous",
      message: "hello",
    }),
    configuredEnv,
  );

  assert.equal(response.status, 502);
  assert.deepEqual(await responseJson(response), { error: "upstream_failure" });
  assert.equal(fake.captures.length, 1);
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
    productRefs: [],
  });

  const capture = fake.captures[0];
  assert.equal(capture.body.model, OPENAI_MODEL);
  assert.equal(capture.body.instructions, agentInstructionsForStore("075"));
  assert.equal(capture.body.previous_response_id, "resp_previous");
  assert.deepEqual(capture.body.reasoning, { effort: "low" });
  assert.equal(capture.body.tools.length, 1);
  assert.equal(capture.body.tools[0].name, LOCAL_TOOL_NAME);
  assert.deepEqual(capture.body.text, { format: FINAL_ANSWER_FORMAT });
  assert.equal(capture.body.input.length, 1);
  assert.equal(capture.body.input[0].type, "function_call_output");
  assert.equal(capture.body.input[0].call_id, "call_previous");
  assert.deepEqual(JSON.parse(capture.body.input[0].output), validContinueBody().result);
});

test("tool assisted structured answer exposes selected product refs only", async () => {
  const fake = fakeOpenAI(
    answerPayload("Use first and second.", ["1234567", "7654321"]),
  );
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/continue", validContinueBody()),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  assert.deepEqual(await responseJson(response), {
    type: "answer",
    responseId: "resp_test_answer",
    text: "Use first and second.",
    productRefs: [
      { storeNumber: "075", obik: "1234567" },
      { storeNumber: "075", obik: "7654321" },
    ],
  });
});

test("continue can return another normalized local tool request", async () => {
  const fake = fakeOpenAI(toolPayload('{"query":"second synthetic query","storeNumber":"074","limit":2}'));
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
    storeNumber: "074",
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

test("continue accepts enriched verified product facts and forwards them unchanged", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);
  const body = validContinueBody();

  const response = await worker.fetch(
    jsonRequest("/v1/agent/continue", body),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  const output = JSON.parse(
    fake.captures[0].body.input[0].output,
  );
  assert.deepEqual(output.products[0], body.result.products[0]);
});

test("continue accepts null and empty optional rich product facts", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);
  const body = validContinueBody();
  body.result.products[0].brand = null;
  body.result.products[0].shortDescription = null;
  body.result.products[0].technicalFacts = [];

  const response = await worker.fetch(
    jsonRequest("/v1/agent/continue", body),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  assert.equal(fake.captures.length, 1);
});

test("continue rejects unexpected raw product payload structures", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);
  const body = validContinueBody();
  body.result.products[0].rawNuxt = { secret: "raw" };

  const response = await worker.fetch(
    jsonRequest("/v1/agent/continue", body),
    configuredEnv,
  );

  assert.equal(response.status, 400);
  assert.equal(fake.captures.length, 0);
});

test("continue enforces compact rich product bounds", async () => {
  const cases = [
    (product) => { product.brand = "b".repeat(81); },
    (product) => {
      product.shortDescription = "d".repeat(221);
    },
    (product) => {
      product.technicalFacts = Array.from(
        { length: 7 },
        (_, index) => ({
          label: `Fact ${index}`,
          value: "value",
        }),
      );
    },
    (product) => {
      product.technicalFacts = [{
        label: "l".repeat(61),
        value: "value",
      }];
    },
    (product) => {
      product.technicalFacts = [{
        label: "Fact",
        value: "v".repeat(101),
      }];
    },
  ];

  for (const mutate of cases) {
    const fake = fakeOpenAI(answerPayload());
    const worker = createWorker(fake.fetch);
    const body = validContinueBody();
    mutate(body.result.products[0]);

    const response = await worker.fetch(
      jsonRequest("/v1/agent/continue", body),
      configuredEnv,
    );

    assert.equal(response.status, 400);
    assert.equal(fake.captures.length, 0);
  }
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


test("start and message require exact 3-digit store context", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);

  for (const storeNumber of ["74", "0074", "abc", ""]) {
    const response = await worker.fetch(
      jsonRequest(
        "/v1/agent/start",
        { message: "hello", storeNumber },
      ),
      configuredEnv,
    );
    assert.equal(response.status, 400);
  }

  const missing = await worker.fetch(
    jsonRequest(
      "/v1/agent/start",
      { message: "hello" },
      { injectStore: false },
    ),
    configuredEnv,
  );
  assert.equal(missing.status, 400);
  assert.equal(fake.captures.length, 0);
});

test("selected conversation store is carried into dynamic instructions", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", {
      message: "hello",
      storeNumber: "074",
    }),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  assert.equal(
    fake.captures[0].body.instructions,
    agentInstructionsForStore("074"),
  );
  assert.equal(
    fake.captures[0].body.instructions.includes("074"),
    true,
  );
  assert.equal(
    fake.captures[0].body.instructions.includes(
      "001,002,003",
    ),
    false,
  );
});

test("generic tool carries one explicit store number", async () => {
  const fake = fakeOpenAI(
    toolPayload(
      '{"query":"klej","storeNumber":"074","limit":3}',
    ),
  );
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", {
      message: "Sprawdź w 074",
      storeNumber: "075",
    }),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  assert.deepEqual(await responseJson(response), {
    type: "tool_request",
    responseId: "resp_test_tool",
    tool: {
      name: "find_obi_products",
      callId: "call_test_tool",
      arguments: {
        query: "klej",
        storeNumber: "074",
        limit: 3,
      },
    },
  });
});

test("continue retains conversation store and compact result store context", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);
  const body = validContinueBody();
  body.storeNumber = "075";
  body.result.storeNumber = "074";

  const response = await worker.fetch(
    jsonRequest("/v1/agent/continue", body),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  const capture = fake.captures[0];
  assert.equal(
    capture.body.instructions,
    agentInstructionsForStore("075"),
  );
  const output = JSON.parse(capture.body.input[0].output);
  assert.equal(output.storeNumber, "074");
  assert.equal(
    Object.prototype.hasOwnProperty.call(
      output.products[0],
      "storeNumber",
    ),
    false,
  );
});

test("continue accepts bounded store rejection without products", async () => {
  const fake = fakeOpenAI(answerPayload("Need store number."));
  const worker = createWorker(fake.fetch);
  const body = validContinueBody();
  body.result = {
    query: "klej",
    storeNumber: "999",
    rejection: "store_not_authorized",
  };

  const response = await worker.fetch(
    jsonRequest("/v1/agent/continue", body),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  const output = JSON.parse(
    fake.captures[0].body.input[0].output,
  );
  assert.deepEqual(output, body.result);
});

test("final productRefs preserve same OBIK in two stores", async () => {
  const refs = [
    { storeNumber: "074", obik: "3496072" },
    { storeNumber: "075", obik: "3496072" },
  ];
  const fake = fakeOpenAI(answerPayload("Compare.", refs));
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", {
      message: "Porównaj 074 i 075",
      storeNumber: "075",
    }),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  assert.deepEqual(
    (await responseJson(response)).productRefs,
    refs,
  );
});

test("tool schema is generic and final schema is store-aware", () => {
  assert.equal(LOCAL_TOOL_NAME, "find_obi_products");
  assert.deepEqual(
    FINAL_ANSWER_FORMAT.schema.required,
    ["text", "productRefs"],
  );
  assert.deepEqual(
    FINAL_ANSWER_FORMAT.schema.properties.productRefs.items.required,
    ["storeNumber", "obik"],
  );
  assert.equal(
    AGENT_INSTRUCTIONS.includes("find_available_obi_075"),
    false,
  );
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
