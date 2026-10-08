import test from "node:test";
import assert from "node:assert/strict";

import {
  AGENT_INSTRUCTIONS,
  KWANT_V3_APPENDIX,
  PROVIDER_V3_INSTRUCTIONS,
  agentInstructionsForStore,
  agentInstructionsForProfile,
  FINAL_ANSWER_FORMAT,
  LOCAL_TOOL_NAME,
  CURRENT_MODEL_PRICING,
  CURRENT_ADVISOR_PROTOCOL_VERSION,
  OPENAI_MAX_OUTPUT_TOKENS,
  OPENAI_MODEL,
  OPENAI_REASONING_EFFORT,
  OPENAI_RESPONSES_URL,
  MAX_WEB_SEARCH_CALLS_PER_RESPONSE,
  MAX_TOOL_PRODUCTS,
  WEB_SEARCH_TOOL,
} from "../.test-dist/config.js";
import { createWorker } from "../.test-dist/index.js";

const APP_TOKEN = "test-app-token-placeholder";
const OPENAI_KEY = "test-openai-key-placeholder";

const configuredEnv = {
  TOWAROWNIK_APP_TOKEN: APP_TOKEN,
  OPENAI_API_KEY: OPENAI_KEY,
};

function jsonRequest(path, body, options = {}) {
  let normalizedBody =
    body &&
    typeof body === "object" &&
    !Array.isArray(body) &&
    path.startsWith("/v1/agent/") &&
    options.injectStore !== false &&
    !Object.prototype.hasOwnProperty.call(body, "storeNumber")
      ? { ...body, storeNumber: "075" }
      : body;

  if (
    normalizedBody &&
    typeof normalizedBody === "object" &&
    !Array.isArray(normalizedBody) &&
    path.startsWith("/v1/agent/") &&
    options.injectProtocol !== false &&
    !Object.prototype.hasOwnProperty.call(
      normalizedBody,
      "protocolVersion",
    )
  ) {
    normalizedBody = {
      ...normalizedBody,
      protocolVersion: 2,
    };
  }
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

function toolPayload(argumentsJson = '{"storeNumber":"075","queries":[{"query":"synthetic query","limit":5}]}', name = LOCAL_TOOL_NAME) {
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

async function captureWarnLogs(run) {
  const originalWarn = console.warn;
  const lines = [];
  console.warn = (value) => {
    lines.push(String(value));
  };
  try {
    const result = await run();
    return { result, lines };
  } finally {
    console.warn = originalWarn;
  }
}

function parsedWarn(lines) {
  assert.equal(lines.length, 1);
  return JSON.parse(lines[0]);
}

function validContinueBody(overrides = {}) {
  return {
    responseId: "resp_previous",
    callId: "call_previous",
    storeNumber: "075",
    tool: LOCAL_TOOL_NAME,
    result: {
      storeNumber: "075",
      results: [
        {
          query: "synthetic query",
          status: "verified",
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
    webSearchCalls: 0,
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
  assert.equal(capture.body.tools.length, 2);
  assert.equal(capture.body.tools[0].type, "function");
  assert.equal(capture.body.tools[0].name, LOCAL_TOOL_NAME);
  assert.deepEqual(capture.body.tools[1], WEB_SEARCH_TOOL);
  assert.equal(capture.body.tool_choice, "auto");
  assert.equal(
    capture.body.max_tool_calls,
    MAX_WEB_SEARCH_CALLS_PER_RESPONSE,
  );
  assert.equal(capture.body.tools[0].strict, true);
  assert.deepEqual(
    capture.body.tools[0].parameters.required,
    ["storeNumber", "queries"],
  );
  assert.equal(capture.body.tools[0].parameters.additionalProperties, false);
  assert.equal(capture.body.tools[0].parameters.properties.queries.maxItems, 5);
  assert.equal(
    capture.body.tools[0].parameters.properties.queries.items.properties.limit.maximum,
    5,
  );
});

test("final advisor behavior does not change model reasoning tools or structured output", async () => {
  const fake = fakeOpenAI(answerPayload("runtime contract"));
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", {
      message: "Sprawdź konkretny produkt",
      storeNumber: "075",
      protocolVersion: 2,
    }),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  assert.equal(fake.captures.length, 1);

  const body = fake.captures[0].body;
  assert.equal(body.model, OPENAI_MODEL);
  assert.deepEqual(body.reasoning, {
    effort: OPENAI_REASONING_EFFORT,
  });
  assert.equal(body.tool_choice, "auto");
  assert.equal(
    body.max_tool_calls,
    MAX_WEB_SEARCH_CALLS_PER_RESPONSE,
  );
  assert.equal(body.tools.length, 2);
  assert.equal(body.tools[0].type, "function");
  assert.equal(body.tools[0].name, LOCAL_TOOL_NAME);
  assert.deepEqual(body.tools[1], WEB_SEARCH_TOOL);
  assert.deepEqual(body.text, {
    format: FINAL_ANSWER_FORMAT,
  });
  assert.equal(body.text.format.type, "json_schema");
  assert.equal(body.text.format.strict, true);
  assert.deepEqual(
    body.text.format.schema.required,
    ["text", "productRefs"],
  );
  assert.deepEqual(
    body.text.format.schema.properties.productRefs.items.required,
    ["storeNumber", "obik"],
  );
});

test("web_search is available selectively with automatic tool choice and one built-in call", async () => {
  const fake = fakeOpenAI(answerPayload("No search needed."));
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", { message: "SDS Plus vs SDS Max?" }),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  const capture = fake.captures[0].body;
  assert.equal(capture.model, "gpt-6-luna");
  assert.deepEqual(capture.reasoning, { effort: "low" });
  assert.equal(capture.tool_choice, "auto");
  assert.equal(capture.max_tool_calls, 1);
  assert.equal(
    capture.tools.some((tool) => tool.type === "web_search"),
    true,
  );
  assert.equal(
    capture.tools.some((tool) => tool.type === "web_search_preview"),
    false,
  );
  assert.equal(
    capture.tools.some(
      (tool) =>
        tool.type === "function" &&
        tool.name === LOCAL_TOOL_NAME,
    ),
    true,
  );
  assert.deepEqual(capture.text, { format: FINAL_ANSWER_FORMAT });
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
    webSearchCalls: 0,
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

  assert.equal(body.webSearchCalls, 0);
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
    "openai-gpt-6-luna-2026-09-28-web-v1",
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
  const fake = fakeOpenAI(toolPayload('{"storeNumber":"075","queries":[{"query":"  moisture   absorber ","limit":5}]}'));
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
        storeNumber: "075",
        queries: [
          { query: "moisture absorber", limit: 5 },
        ],
      },
    },
    webSearchCalls: 0,
  });
});

test("five query groups with limit one are accepted", async () => {
  const fake = fakeOpenAI(
    toolPayload(JSON.stringify({
      storeNumber: "075",
      queries: Array.from(
        { length: 5 },
        (_, index) => ({ query: `category ${index + 1}`, limit: 1 }),
      ),
    })),
  );
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", { message: "build a kit" }),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  const body = await responseJson(response);
  assert.equal(body.type, "tool_request");
  assert.equal(body.tool.arguments.queries.length, 5);
  assert.equal(
    body.tool.arguments.queries.reduce(
      (sum, query) => sum + query.limit,
      0,
    ),
    5,
  );
});

test("tool rejects total requested limits above five", async () => {
  const fake = fakeOpenAI(
    toolPayload(
      '{"storeNumber":"075","queries":[{"query":"one","limit":3},{"query":"two","limit":3}]}',
    ),
  );
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", { message: "build a kit" }),
    configuredEnv,
  );

  assert.equal(response.status, 502);
});

test("tool rejects more than five query groups", async () => {
  const fake = fakeOpenAI(
    toolPayload(JSON.stringify({
      storeNumber: "075",
      queries: Array.from(
        { length: 6 },
        (_, index) => ({ query: `category ${index + 1}`, limit: 1 }),
      ),
    })),
  );
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", { message: "build a kit" }),
    configuredEnv,
  );

  assert.equal(response.status, 502);
});

test("tool rejects blank and oversized queries", async () => {
  for (const query of ["   ", "x".repeat(201)]) {
    const fake = fakeOpenAI(
      toolPayload(JSON.stringify({
        storeNumber: "075",
        queries: [{ query, limit: 1 }],
      })),
    );
    const worker = createWorker(fake.fetch);

    const response = await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "build a kit" }),
      configuredEnv,
    );

    assert.equal(response.status, 502);
  }
});

test("web_search_call plus final message preserves bounded citation metadata", async () => {
  const structured = JSON.stringify({
    text: "Verified technical detail.",
    productRefs: [],
  });
  const citationStart = structured.indexOf("Verified technical detail.");
  const citationEnd =
    citationStart + "Verified technical detail.".length;
  const fake = fakeOpenAI(
    withUsage({
      id: "resp_web_answer",
      output_text: structured,
      output: [
        {
          type: "web_search_call",
          id: "ws_1",
          status: "completed",
          action: {
            type: "search",
            query: "private query must not be forwarded",
          },
        },
        {
          type: "reasoning",
          summary: [{ text: "internal reasoning" }],
        },
        {
          type: "message",
          content: [
            {
              type: "output_text",
              text: structured,
              annotations: [
                {
                  type: "url_citation",
                  start_index: citationStart,
                  end_index: citationEnd,
                  title: " Manufacturer  Manual ",
                  url: "https://manufacturer.example/manual",
                },
              ],
            },
          ],
        },
      ],
    }),
  );
  const worker = createWorker(fake.fetch);

  const body = await responseJson(
    await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "verify" }),
      configuredEnv,
    ),
  );

  assert.equal(body.type, "answer");
  assert.deepEqual(body.sources, [
    {
      title: "Manufacturer Manual",
      url: "https://manufacturer.example/manual",
      startIndex: 0,
      endIndex: "Verified technical detail.".length,
    },
  ]);
  assert.equal(body.webSearchCalls, 1);
  assert.equal(body.usage.estimatedCostUsd, 0.010114);
  const serialized = JSON.stringify(body);
  assert.equal(serialized.includes("private query"), false);
  assert.equal(serialized.includes("internal reasoning"), false);
  assert.equal(serialized.includes("web_search_call"), false);
});

test("web_search_call plus application function call remains a local tool request", async () => {
  const fake = fakeOpenAI(
    withUsage({
      id: "resp_web_then_function",
      output: [
        {
          type: "web_search_call",
          id: "ws_2",
          status: "completed",
          action: { type: "search", query: "technical fact" },
        },
        { type: "reasoning", summary: [] },
        {
          type: "function_call",
          call_id: "call_after_web",
          name: LOCAL_TOOL_NAME,
          arguments:
            '{"storeNumber":"075","queries":[{"query":"klej","limit":2}]}',
        },
      ],
    }),
  );
  const worker = createWorker(fake.fetch);

  const body = await responseJson(
    await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "verify then stock" }),
      configuredEnv,
    ),
  );

  assert.equal(body.type, "tool_request");
  assert.equal(body.tool.callId, "call_after_web");
  assert.equal(body.webSearchCalls, 1);
  assert.equal(body.usage.estimatedCostUsd, 0.010114);
});

test("citation normalization deduplicates limits and filters unsafe URLs", async () => {
  const structured = JSON.stringify({
    text: "Cited answer.",
    productRefs: [],
  });
  const annotations = [
    {
      type: "url_citation",
      title: "One",
      url: "https://one.example/a",
    },
    {
      type: "url_citation",
      title: "Duplicate",
      url: "https://one.example/a",
    },
    {
      type: "url_citation",
      title: "Unsafe",
      url: "http://unsafe.example/",
    },
    {
      type: "url_citation",
      title: "Malformed",
      url: "not-a-url",
    },
    {
      type: "url_citation",
      title: "Overlong URL",
      url: "https://too-long.example/" + "x".repeat(2050),
    },
    {
      type: "url_citation",
      title: 123,
      url: "https://malformed.example/",
    },
    ...Array.from({ length: 8 }, (_, index) => ({
      type: "url_citation",
      title: "T".repeat(250) + index,
      url: `https://source-${index}.example/path`,
    })),
  ];
  const fake = fakeOpenAI({
    id: "resp_citations",
    output_text: structured,
    output: [
      {
        type: "message",
        content: [
          {
            type: "output_text",
            text: structured,
            annotations,
          },
        ],
      },
    ],
  });
  const worker = createWorker(fake.fetch);

  const body = await responseJson(
    await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "sources" }),
      configuredEnv,
    ),
  );

  assert.equal(body.sources.length, 6);
  assert.equal(body.sources[0].url, "https://one.example/a");
  assert.equal(body.sources[0].startIndex, null);
  assert.equal(body.sources[0].endIndex, null);
  assert.equal(
    body.sources.filter(
      (source) => source.url === "https://one.example/a",
    ).length,
    1,
  );
  assert.equal(
    body.sources.every(
      (source) =>
        source.url.startsWith("https://") &&
        source.title.length <= 200,
    ),
    true,
  );
});

test("answer text containing a URL without url_citation metadata creates no source", async () => {
  const fake = fakeOpenAI(
    answerPayload(
      "Manufacturer says https://invented.example/spec but this is plain model text.",
    ),
  );
  const worker = createWorker(fake.fetch);

  const body = await responseJson(
    await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "source?" }),
      configuredEnv,
    ),
  );

  assert.equal(Object.hasOwn(body, "sources"), false);
});

test("unsupported internal citation and entity tokens are removed without breaking real url citations", async () => {
  const internalCitation = "\uE200cite\uE202turn0search0\uE201";
  const internalEntity = "\uE200entity\uE202internal-product\uE201";
  const answerText =
    `Przed ${internalCitation} potwierdzony parametr ${internalEntity} dalej.`;
  const structured = JSON.stringify({
    text: answerText,
    productRefs: [],
  });
  const citedFragment = "potwierdzony parametr";
  const rawStart = structured.indexOf(citedFragment);
  const rawEnd = rawStart + citedFragment.length;
  const fake = fakeOpenAI({
    id: "resp_internal_markup",
    output_text: structured,
    output: [
      {
        type: "message",
        content: [
          {
            type: "output_text",
            text: structured,
            annotations: [
              {
                type: "url_citation",
                start_index: rawStart,
                end_index: rawEnd,
                title: "Manufacturer manual",
                url: "https://manufacturer.example/manual",
              },
            ],
          },
        ],
      },
    ],
  });
  const worker = createWorker(fake.fetch);
  const body = await responseJson(
    await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "verify" }),
      configuredEnv,
    ),
  );

  assert.equal(body.type, "answer");
  assert.equal(body.text.includes(internalCitation), false);
  assert.equal(body.text.includes(internalEntity), false);
  assert.match(body.text, /Przed\s+potwierdzony parametr\s+dalej\./);
  const visibleStart = body.text.indexOf(citedFragment);
  assert.deepEqual(body.sources, [
    {
      title: "Manufacturer manual",
      url: "https://manufacturer.example/manual",
      startIndex: visibleStart,
      endIndex: visibleStart + citedFragment.length,
    },
  ]);
});

test("availability of web_search does not count as a search call or fee", async () => {
  const fake = fakeOpenAI(withUsage(answerPayload("No search.")));
  const worker = createWorker(fake.fetch);
  const body = await responseJson(
    await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "general question" }),
      configuredEnv,
    ),
  );

  assert.equal(body.webSearchCalls, 0);
  assert.equal(body.usage.estimatedCostUsd, 0.000114);
});

test("completed web search is counted when usage is missing", async () => {
  const structured = answerPayload("Web answer");
  const payload = {
    ...structured,
    output: [
      {
        type: "web_search_call",
        id: "ws_missing_usage",
        status: "completed",
        action: { type: "search", query: "q" },
      },
      ...structured.output,
    ],
  };
  const fake = fakeOpenAI(payload);
  const worker = createWorker(fake.fetch);

  const body = await responseJson(
    await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "verify" }),
      configuredEnv,
    ),
  );

  assert.equal(body.webSearchCalls, 1);
  assert.equal(Object.hasOwn(body, "usage"), false);
});

test("completed web search is counted when usage is malformed", async () => {
  const structured = answerPayload("Web answer");
  const payload = {
    ...structured,
    model: OPENAI_MODEL,
    usage: {
      input_tokens: "bad",
      output_tokens: 1,
      total_tokens: 1,
    },
    output: [
      {
        type: "web_search_call",
        id: "ws_bad_usage",
        status: "completed",
        action: { type: "search", query: "q" },
      },
      ...structured.output,
    ],
  };
  const fake = fakeOpenAI(payload);
  const worker = createWorker(fake.fetch);

  const body = await responseJson(
    await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "verify" }),
      configuredEnv,
    ),
  );

  assert.equal(body.webSearchCalls, 1);
  assert.equal(Object.hasOwn(body, "usage"), false);
});

test("citation offsets map safely through structured JSON string escapes", async () => {
  const answerText = 'Parametr "A" ma wartość 10.';
  const structured = JSON.stringify({
    text: answerText,
    productRefs: [],
  });
  const rawFragment = 'Parametr \\"A\\" ma wartość 10.';
  const rawStart = structured.indexOf(rawFragment);
  const rawEnd = rawStart + rawFragment.length;
  const fake = fakeOpenAI({
    id: "resp_citation_escape",
    output_text: structured,
    output: [
      {
        type: "message",
        content: [
          {
            type: "output_text",
            text: structured,
            annotations: [
              {
                type: "url_citation",
                start_index: rawStart,
                end_index: rawEnd,
                title: "Manual",
                url: "https://manufacturer.example/manual",
              },
            ],
          },
        ],
      },
    ],
  });
  const worker = createWorker(fake.fetch);
  const body = await responseJson(
    await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "verify" }),
      configuredEnv,
    ),
  );

  assert.deepEqual(body.sources, [
    {
      title: "Manual",
      url: "https://manufacturer.example/manual",
      startIndex: 0,
      endIndex: answerText.length,
    },
  ]);
});

test("unknown model after a completed search remains unpriced but preserves search count", async () => {
  const structured = answerPayload("Web answer");
  const payload = withUsage({
    ...structured,
    output: [
      {
        type: "web_search_call",
        id: "ws_unknown_model",
        status: "completed",
        action: { type: "search", query: "q" },
      },
      ...structured.output,
    ],
  });
  payload.model = "unknown-model";

  const fake = fakeOpenAI(payload);
  const worker = createWorker(fake.fetch);
  const body = await responseJson(
    await worker.fetch(
      jsonRequest("/v1/agent/start", { message: "verify" }),
      configuredEnv,
    ),
  );

  assert.equal(body.webSearchCalls, 1);
  assert.equal(body.usage.estimatedCostUsd, null);
  assert.equal(body.usage.pricingVersion, null);
});

test("unknown model function call is a bounded upstream failure", async () => {
  const fake = fakeOpenAI(toolPayload('{"storeNumber":"075","queries":[{"query":"x","limit":1}]}', "unknown_tool"));
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    jsonRequest("/v1/agent/start", { message: "hello" }),
    configuredEnv,
  );

  assert.equal(response.status, 502);
  assert.deepEqual(await responseJson(response), { error: "upstream_failure" });
});

test("malformed model function arguments are a bounded upstream failure", async () => {
  const fake = fakeOpenAI(toolPayload('{"storeNumber":"075","queries":[{"query":"x","limit":99}]}'));
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
  assert.equal(capture.body.tools.length, 2);
  assert.equal(capture.body.tools[0].name, LOCAL_TOOL_NAME);
  assert.deepEqual(capture.body.tools[1], WEB_SEARCH_TOOL);
  assert.equal(capture.body.tool_choice, "auto");
  assert.equal(
    capture.body.max_tool_calls,
    MAX_WEB_SEARCH_CALLS_PER_RESPONSE,
  );
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
    webSearchCalls: 0,
  });

  const capture = fake.captures[0];
  assert.equal(capture.body.model, OPENAI_MODEL);
  assert.equal(capture.body.instructions, agentInstructionsForStore("075"));
  assert.equal(capture.body.previous_response_id, "resp_previous");
  assert.deepEqual(capture.body.reasoning, { effort: "low" });
  assert.equal(capture.body.tools.length, 2);
  assert.equal(capture.body.tools[0].name, LOCAL_TOOL_NAME);
  assert.deepEqual(capture.body.tools[1], WEB_SEARCH_TOOL);
  assert.equal(capture.body.tool_choice, "auto");
  assert.equal(
    capture.body.max_tool_calls,
    MAX_WEB_SEARCH_CALLS_PER_RESPONSE,
  );
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
    webSearchCalls: 0,
  });
});

test("continue can return another normalized local tool request", async () => {
  const fake = fakeOpenAI(toolPayload('{"storeNumber":"074","queries":[{"query":"second synthetic query","limit":2}]}'));
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
    storeNumber: "074",
    queries: [
      { query: "second synthetic query", limit: 2 },
    ],
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

test("local tool limit continuation disables find_obi_products and can finish", async () => {
  const fake = fakeOpenAI(
    answerPayload(
      "Use already verified products and general guidance.",
      ["1234567"],
    ),
  );
  const worker = createWorker(fake.fetch);
  const body = validContinueBody();
  body.result = {
    storeNumber: "075",
    queries: [{ query: "third category", limit: 1 }],
    rejection: "local_tool_limit_reached",
  };

  const response = await worker.fetch(
    jsonRequest("/v1/agent/continue", body),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  const normalized = await responseJson(response);
  assert.equal(normalized.type, "answer");
  const capture = fake.captures[0];
  assert.deepEqual(capture.body.tools, [WEB_SEARCH_TOOL]);
  assert.equal(capture.body.tool_choice, "auto");
  assert.deepEqual(
    JSON.parse(capture.body.input[0].output),
    body.result,
  );
});

test("local tool limit continuation rejects another application function call", async () => {
  const fake = fakeOpenAI(
    toolPayload(
      '{"storeNumber":"075","queries":[{"query":"fourth category","limit":1}]}',
    ),
  );
  const worker = createWorker(fake.fetch);
  const body = validContinueBody();
  body.result = {
    storeNumber: "075",
    queries: [{ query: "third category", limit: 1 }],
    rejection: "local_tool_limit_reached",
  };

  const response = await worker.fetch(
    jsonRequest("/v1/agent/continue", body),
    configuredEnv,
  );

  assert.equal(response.status, 502);
  assert.deepEqual(
    await responseJson(response),
    { error: "upstream_failure" },
  );
  assert.equal(fake.captures[0].body.tools.length, 1);
  assert.deepEqual(fake.captures[0].body.tools[0], WEB_SEARCH_TOOL);
});

test("continue preserves verified, unavailable, and not_found groups independently", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);
  const body = validContinueBody();
  body.result.results.push(
    {
      query: "pistolet",
      status: "unavailable",
      products: [],
    },
    {
      query: "wygładzanie",
      status: "not_found",
      products: [],
    },
  );

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
  assert.deepEqual(output.results[0].products[0], body.result.results[0].products[0]);
});

test("continue accepts null and empty optional rich product facts", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);
  const body = validContinueBody();
  body.result.results[0].products[0].brand = null;
  body.result.results[0].products[0].shortDescription = null;
  body.result.results[0].products[0].technicalFacts = [];

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
  body.result.results[0].products[0].rawNuxt = { secret: "raw" };

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
    mutate(body.result.results[0].products[0]);

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
  body.result.results[0].products = Array.from(
    { length: 6 },
    (_, index) => ({
      obik: String(1_000_000 + index),
      name: `Synthetic product ${index}`,
      brand: null,
      shortDescription: null,
      technicalFacts: [],
      stock: 1,
      price: 1,
    }),
  );

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
  body.result.results[0].products[0].obik = "123";

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
  body.result.results[0].products[0].stock = -1;

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
  body.result.results[0].products[0].stock = null;

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
  body.result.results[0].products[0].price = null;

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
  body.result.results[0].products[0].price = -0.01;

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
      '{"storeNumber":"074","queries":[{"query":"klej","limit":3}]}',
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
        storeNumber: "074",
        queries: [
          { query: "klej", limit: 3 },
        ],
      },
    },
    webSearchCalls: 0,
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
      output.results[0].products[0],
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
    storeNumber: "999",
    queries: [{ query: "klej", limit: 1 }],
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

test("backward instruction aliases are composed from the shared policy lineage", () => {
  assert.match(
    AGENT_INSTRUCTIONS,
    /harmless assortment variation/i,
  );
  assert.match(
    AGENT_INSTRUCTIONS,
    /exact seven-digit OBIK/i,
  );
  assert.match(
    PROVIDER_V3_INSTRUCTIONS,
    /Evidence and trust policy/i,
  );
  assert.match(
    KWANT_V3_APPENDIX,
    /electrical wholesaler/i,
  );
});

test("OBI broad black cable-tie intent is explicitly browseable without size clarification", () => {
  assert.match(
    AGENT_INSTRUCTIONS,
    /Klient potrzebuje czarnych trytytek/i,
  );
  assert.match(
    AGENT_INSTRUCTIONS,
    /do not require length or width before any lookup/i,
  );
  assert.match(
    AGENT_INSTRUCTIONS,
    /browse several relevant variants instead of forcing clarification/i,
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

const upstreamFailureCases = [
  {
    name: "network exception",
    expectedCategory: "upstream_network",
    expectedStatus: null,
    fake: () => fakeOpenAI(answerPayload(), { throwNetwork: true }),
  },
  {
    name: "HTTP 400",
    expectedCategory: "upstream_http_4xx",
    expectedStatus: 400,
    fake: () => fakeOpenAI(
      { error: { message: "UPSTREAM_BODY_SENTINEL_400" } },
      { status: 400 },
    ),
  },
  {
    name: "HTTP 429",
    expectedCategory: "upstream_rate_limit",
    expectedStatus: 429,
    fake: () => fakeOpenAI(
      { error: { message: "UPSTREAM_BODY_SENTINEL_429" } },
      { status: 429 },
    ),
  },
  {
    name: "HTTP 500",
    expectedCategory: "upstream_http_5xx",
    expectedStatus: 500,
    fake: () => fakeOpenAI(
      { error: { message: "UPSTREAM_BODY_SENTINEL_500" } },
      { status: 500 },
    ),
  },
  {
    name: "HTTP 200 invalid JSON",
    expectedCategory: "upstream_invalid_json",
    expectedStatus: 200,
    fake: () => fakeOpenAI("UPSTREAM_BODY_SENTINEL_NOT_JSON"),
  },
  {
    name: "HTTP 200 malformed Responses envelope",
    expectedCategory: "upstream_invalid_envelope",
    expectedStatus: 200,
    fake: () => fakeOpenAI({
      id: "resp_bad_envelope",
      output: "UPSTREAM_BODY_SENTINEL_BAD_ENVELOPE",
    }),
  },
  {
    name: "HTTP 200 incompatible function call",
    expectedCategory: "upstream_invalid_tool_call",
    expectedStatus: 200,
    fake: () => fakeOpenAI(
      toolPayload(
        '{"storeNumber":"075","queries":[{"query":"secret-query","limit":1}]}',
        "wrong_tool",
      ),
    ),
  },
  {
    name: "HTTP 200 invalid structured final answer",
    expectedCategory: "upstream_invalid_final_answer",
    expectedStatus: 200,
    fake: () => fakeOpenAI({
      id: "resp_bad_final",
      output: [
        {
          type: "message",
          content: [
            {
              type: "output_text",
              text: JSON.stringify({
                text: "UPSTREAM_BODY_SENTINEL_BAD_FINAL",
                productRefs: "not-an-array",
              }),
            },
          ],
        },
      ],
    }),
  },
];

for (const scenario of upstreamFailureCases) {
  test(`${scenario.name} logs safe failure category and keeps public 502 contract`, async () => {
    const userText = "USER_TEXT_MUST_NOT_APPEAR";
    const fake = scenario.fake();
    const worker = createWorker(fake.fetch);

    const { result: response, lines } = await captureWarnLogs(() =>
      worker.fetch(
        jsonRequest("/v1/agent/start", { message: userText }),
        configuredEnv,
      ),
    );

    assert.equal(response.status, 502);
    assert.deepEqual(
      await responseJson(response),
      { error: "upstream_failure" },
    );

    const diagnostic = parsedWarn(lines);
    assert.deepEqual(
      {
        event: diagnostic.event,
        protocolVersion: diagnostic.protocolVersion,
        endpointStage: diagnostic.endpointStage,
        responseEnvelopeType: diagnostic.responseEnvelopeType,
        validationFailureCategory: diagnostic.validationFailureCategory,
        upstreamFailureCategory: diagnostic.upstreamFailureCategory,
        upstreamStatus: diagnostic.upstreamStatus,
      },
      {
        event: "advisor_protocol",
        protocolVersion: 2,
        endpointStage: "START",
        responseEnvelopeType: null,
        validationFailureCategory: null,
        upstreamFailureCategory: scenario.expectedCategory,
        upstreamStatus: scenario.expectedStatus,
      },
    );

    const logged = lines.join("\n");
    assert.equal(logged.includes(userText), false);
    assert.equal(logged.includes(APP_TOKEN), false);
    assert.equal(logged.includes(OPENAI_KEY), false);
    assert.equal(logged.includes("Bearer"), false);
    assert.equal(logged.includes("UPSTREAM_BODY_SENTINEL"), false);
    assert.equal(logged.includes("secret-query"), false);
  });
}

test("v4 attachment upstream failure log excludes attachment filename and payload content", async () => {
  const attachmentFilename = "SECRET_ATTACHMENT_FILENAME.pdf";
  const userText = "SECRET_ATTACHMENT_USER_TEXT";
  const upstreamBodySentinel = "SECRET_UPSTREAM_RESPONSE_BODY";
  const fake = fakeOpenAI(
    { error: { message: upstreamBodySentinel } },
    { status: 500 },
  );
  const worker = createWorker(fake.fetch);
  const form = new FormData();
  const payload = {
    protocolVersion: 4,
    message: userText,
    providerId: "obi-pl",
    branchId: "075",
  };
  form.set("payload", JSON.stringify(payload));
  form.set(
    "attachment",
    new File(
      [new TextEncoder().encode("%PDF-1.7\n")],
      attachmentFilename,
      { type: "application/pdf" },
    ),
  );
  const request = new Request(
    "https://proxy.example/v1/agent/start",
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${APP_TOKEN}`,
        "Content-Length": "1024",
      },
      body: form,
    },
  );

  const { result: response, lines } = await captureWarnLogs(() =>
    worker.fetch(request, configuredEnv),
  );

  assert.equal(response.status, 502);
  assert.deepEqual(
    await responseJson(response),
    { error: "upstream_failure" },
  );
  const diagnostic = parsedWarn(lines);
  assert.equal(diagnostic.protocolVersion, 4);
  assert.equal(diagnostic.endpointStage, "START");
  assert.equal(
    diagnostic.upstreamFailureCategory,
    "upstream_http_5xx",
  );
  assert.equal(diagnostic.upstreamStatus, 500);

  const logged = lines.join("\n");
  for (const secret of [
    attachmentFilename,
    userText,
    upstreamBodySentinel,
    APP_TOKEN,
    OPENAI_KEY,
    "Bearer",
  ]) {
    assert.equal(logged.includes(secret), false);
  }
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
