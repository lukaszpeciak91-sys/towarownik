import test from "node:test";
import assert from "node:assert/strict";

import {
  CURRENT_MODEL_PRICING,
  LOCAL_TOOL_NAME,
  OPENAI_MODEL,
  PROVIDER_LOCAL_TOOL_NAME,
} from "../.test-dist/config.js";
import { createWorker } from "../.test-dist/index.js";
import { TRACE_HEADER_NAME } from "../.test-dist/observability.js";

const APP_TOKEN = "OBS_APP_TOKEN_SENTINEL";
const OPENAI_KEY = "OBS_OPENAI_KEY_SENTINEL";
const TRACE_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const env = {
  TOWAROWNIK_APP_TOKEN: APP_TOKEN,
  OPENAI_API_KEY: OPENAI_KEY,
};

function jsonRequest(path, body, options = {}) {
  return new Request(`https://proxy.example${path}`, {
    method: options.method ?? "POST",
    headers: {
      Authorization: `Bearer ${APP_TOKEN}`,
      "Content-Type": "application/json",
      ...(options.traceId
        ? { [TRACE_HEADER_NAME]: options.traceId }
        : {}),
    },
    body: JSON.stringify(body),
  });
}

function answerPayload({
  text = "Synthetic answer",
  productRefs = [],
  id = "resp_observability_sentinel",
  usage = false,
} = {}) {
  const structured = JSON.stringify({ text, productRefs });
  return {
    id,
    model: OPENAI_MODEL,
    output: [
      {
        type: "message",
        content: [{ type: "output_text", text: structured }],
      },
    ],
    ...(usage
      ? {
          usage: {
            input_tokens: 1_000,
            input_tokens_details: {
              cached_tokens: 400,
              cache_write_tokens: 0,
            },
            output_tokens: 100,
            output_tokens_details: { reasoning_tokens: 50 },
            total_tokens: 1_100,
          },
        }
      : {}),
  };
}

function toolPayload(
  args,
  name = LOCAL_TOOL_NAME,
  callId = "call_observability_sentinel",
) {
  return {
    id: "resp_tool_observability_sentinel",
    output: [
      {
        type: "function_call",
        call_id: callId,
        name,
        arguments: JSON.stringify(args),
      },
    ],
  };
}

function fakeOpenAI(payload, options = {}) {
  const status = options.status ?? 200;
  return async (_input, init) => {
    if (options.throwNetwork) {
      throw new Error("network sentinel");
    }
    assert.equal(
      new Headers(init?.headers).get("Authorization"),
      `Bearer ${OPENAI_KEY}`,
    );
    return new Response(
      typeof payload === "string"
        ? payload
        : JSON.stringify(payload),
      {
        status,
        headers: { "Content-Type": "application/json" },
      },
    );
  };
}

async function captureDiagnostic(run) {
  const originalInfo = console.info;
  const originalWarn = console.warn;
  const lines = [];
  console.info = (value) => lines.push(String(value));
  console.warn = (value) => lines.push(String(value));
  try {
    const result = await run();
    assert.equal(lines.length, 1);
    return {
      result,
      line: lines[0],
      event: JSON.parse(lines[0]),
    };
  } finally {
    console.info = originalInfo;
    console.warn = originalWarn;
  }
}

async function responseJson(response) {
  return JSON.parse(await response.text());
}

function v2Start(message = "hello") {
  return {
    protocolVersion: 2,
    message,
    storeNumber: "075",
  };
}

function v2Message(message = "hello") {
  return {
    protocolVersion: 2,
    previousResponseId: "resp_previous_private",
    message,
    storeNumber: "075",
  };
}

function v2Continue(result) {
  return {
    protocolVersion: 2,
    responseId: "resp_previous_private",
    callId: "call_previous_private",
    storeNumber: "075",
    tool: LOCAL_TOOL_NAME,
    result,
  };
}

function verifiedV2Result({
  query = "SEARCH_QUERY_SENTINEL",
  productName = "PRODUCT_NAME_SENTINEL",
  obik = "7654321",
} = {}) {
  return {
    storeNumber: "075",
    results: [
      {
        query,
        status: "verified",
        products: [
          {
            obik,
            name: productName,
            brand: "BRAND_SENTINEL",
            shortDescription: "DESCRIPTION_SENTINEL",
            technicalFacts: [
              { label: "LABEL_SENTINEL", value: "VALUE_SENTINEL" },
            ],
            stock: 7,
            price: 12.34,
          },
        ],
      },
      {
        query: "SECOND_QUERY_SENTINEL",
        status: "not_found",
        products: [],
      },
      {
        query: "THIRD_QUERY_SENTINEL",
        status: "unavailable",
        products: [],
      },
    ],
  };
}

test("START creates a fresh trace, returns it, logs it, and leaves JSON unchanged", async () => {
  const inbound = "11111111-1111-4111-8111-111111111111";
  const worker = createWorker(fakeOpenAI(answerPayload()));
  const { result: response, event } = await captureDiagnostic(() =>
    worker.fetch(
      jsonRequest("/v1/agent/start", v2Start(), {
        traceId: inbound,
      }),
      env,
    ),
  );

  const traceId = response.headers.get(TRACE_HEADER_NAME);
  assert.match(traceId, TRACE_PATTERN);
  assert.notEqual(traceId, inbound);
  assert.equal(event.traceId, traceId);
  assert.match(event.requestId, TRACE_PATTERN);
  assert.notEqual(event.requestId, event.traceId);
  assert.equal(event.schemaVersion, 2);
  assert.deepEqual(await responseJson(response), {
    type: "answer",
    responseId: "resp_observability_sentinel",
    text: "Synthetic answer",
    productRefs: [],
    webSearchCalls: 0,
  });
});

test("MESSAGE always starts a fresh trace even with a valid inbound trace", async () => {
  const inbound = "22222222-2222-4222-8222-222222222222";
  const worker = createWorker(fakeOpenAI(answerPayload()));
  const { result: response, event } = await captureDiagnostic(() =>
    worker.fetch(
      jsonRequest("/v1/agent/message", v2Message(), {
        traceId: inbound,
      }),
      env,
    ),
  );

  const traceId = response.headers.get(TRACE_HEADER_NAME);
  assert.match(traceId, TRACE_PATTERN);
  assert.notEqual(traceId, inbound);
  assert.equal(event.traceId, traceId);
  assert.equal(event.endpointStage, "MESSAGE");
});

test("CONTINUE reuses a syntactically valid trace header", async () => {
  const traceId = "33333333-3333-4333-8333-333333333333";
  const worker = createWorker(fakeOpenAI(answerPayload()));
  const { result: response, event } = await captureDiagnostic(() =>
    worker.fetch(
      jsonRequest(
        "/v1/agent/continue",
        v2Continue(verifiedV2Result()),
        { traceId },
      ),
      env,
    ),
  );

  assert.equal(response.headers.get(TRACE_HEADER_NAME), traceId);
  assert.equal(event.traceId, traceId);
  assert.equal(event.endpointStage, "CONTINUE");
});

test("CONTINUE missing or invalid trace header succeeds with a new trace", async () => {
  for (const inbound of [undefined, "not-a-valid-trace"]) {
    const worker = createWorker(fakeOpenAI(answerPayload()));
    const { result: response, event } = await captureDiagnostic(() =>
      worker.fetch(
        jsonRequest(
          "/v1/agent/continue",
          v2Continue(verifiedV2Result()),
          inbound ? { traceId: inbound } : {},
        ),
        env,
      ),
    );

    assert.equal(response.status, 200);
    const traceId = response.headers.get(TRACE_HEADER_NAME);
    assert.match(traceId, TRACE_PATTERN);
    assert.notEqual(traceId, inbound);
    assert.equal(event.traceId, traceId);
  }
});

test("successful answer log contains safe runtime, usage, and latency metadata", async () => {
  const worker = createWorker(
    fakeOpenAI(
      answerPayload({
        text: "Answer body sentinel",
        productRefs: [
          { storeNumber: "075", obik: "1234567" },
          { storeNumber: "075", obik: "7654321" },
        ],
        usage: true,
      }),
    ),
  );

  const { result: response, event } = await captureDiagnostic(() =>
    worker.fetch(
      jsonRequest("/v1/agent/start", v2Start("USER_MESSAGE_SENTINEL")),
      env,
    ),
  );

  assert.equal(response.status, 200);
  assert.deepEqual(
    {
      providerId: event.providerId,
      branchId: event.branchId,
      protocolVersion: event.protocolVersion,
      endpointStage: event.endpointStage,
      inputKind: event.inputKind,
      responseEnvelopeType: event.responseEnvelopeType,
      finalProductRefCount: event.finalProductRefCount,
      webSearchCalls: event.webSearchCalls,
      webSourceCount: event.webSourceCount,
      outcome: event.outcome,
      httpStatus: event.httpStatus,
    },
    {
      providerId: "obi-pl",
      branchId: "075",
      protocolVersion: 2,
      endpointStage: "START",
      inputKind: "text",
      responseEnvelopeType: "answer",
      finalProductRefCount: 2,
      webSearchCalls: 0,
      webSourceCount: 0,
      outcome: "success",
      httpStatus: 200,
    },
  );
  assert.equal(event.model, OPENAI_MODEL);
  assert.equal(event.requestType, "START");
  assert.equal(event.inputTokens, 1_000);
  assert.equal(event.cachedInputTokens, 400);
  assert.equal(event.cacheWriteTokens, 0);
  assert.equal(event.outputTokens, 100);
  assert.equal(event.reasoningTokens, 50);
  assert.equal(event.totalTokens, 1_100);
  assert.equal(event.estimatedCostUsd, 0.000114);
  assert.equal(
    event.pricingVersion,
    CURRENT_MODEL_PRICING.pricingVersion,
  );
  assert.equal(Number.isFinite(event.durationMs), true);
  assert.ok(event.durationMs >= 0);
});

test("tool-request log records aggregate request shape without query text", async () => {
  const searchQuery = "PRIVATE_SEARCH_QUERY_SENTINEL";
  const worker = createWorker(
    fakeOpenAI(
      toolPayload({
        storeNumber: "075",
        queries: [
          { query: searchQuery, limit: 2 },
          { query: "SECOND_PRIVATE_QUERY", limit: 3 },
        ],
      }),
    ),
  );

  const { event, line } = await captureDiagnostic(() =>
    worker.fetch(
      jsonRequest("/v1/agent/start", v2Start("PRIVATE_USER_MESSAGE")),
      env,
    ),
  );

  assert.equal(event.responseEnvelopeType, "tool_request");
  assert.equal(event.localToolRequested, true);
  assert.equal(event.localQueryCount, 2);
  assert.equal(event.requestedProductLimitTotal, 5);
  assert.equal(event.requestedBranchPresent, null);
  assert.equal(event.finalProductRefCount, 0);
  assert.equal(line.includes(searchQuery), false);
  assert.equal(line.includes("SECOND_PRIVATE_QUERY"), false);
  assert.equal(line.includes("call_observability_sentinel"), false);
  assert.equal(line.includes("resp_tool_observability_sentinel"), false);
});

test("CONTINUE verified result logs aggregate counts without raw product content", async () => {
  const result = verifiedV2Result();
  const worker = createWorker(fakeOpenAI(answerPayload()));

  const { event, line } = await captureDiagnostic(() =>
    worker.fetch(
      jsonRequest("/v1/agent/continue", v2Continue(result)),
      env,
    ),
  );

  assert.equal(event.inputKind, "tool_result");
  assert.equal(event.localResultKind, "mixed");
  assert.equal(event.localResultProviderId, "obi-pl");
  assert.equal(event.localResultBranchId, "075");
  assert.equal(event.resultQueryCount, 3);
  assert.equal(event.verifiedProductCount, 1);
  assert.equal(event.verifiedQueryCount, 1);
  assert.equal(event.notFoundQueryCount, 1);
  assert.equal(event.unavailableQueryCount, 1);
  assert.equal(event.rejectionCategory, null);

  for (const unsafe of [
    "SEARCH_QUERY_SENTINEL",
    "SECOND_QUERY_SENTINEL",
    "THIRD_QUERY_SENTINEL",
    "PRODUCT_NAME_SENTINEL",
    "7654321",
    "BRAND_SENTINEL",
    "DESCRIPTION_SENTINEL",
    "12.34",
  ]) {
    assert.equal(line.includes(unsafe), false);
  }
});

test("rejected CONTINUE exposes only safe rejection and aggregate metadata", async () => {
  const rejected = {
    storeNumber: "075",
    queries: [
      { query: "REJECTED_QUERY_SENTINEL", limit: 2 },
      { query: "ANOTHER_REJECTED_QUERY", limit: 1 },
    ],
    rejection: "store_not_authorized",
  };
  const worker = createWorker(fakeOpenAI(answerPayload()));

  const { event, line } = await captureDiagnostic(() =>
    worker.fetch(
      jsonRequest("/v1/agent/continue", v2Continue(rejected)),
      env,
    ),
  );

  assert.equal(event.localResultKind, "rejected");
  assert.equal(event.rejectionCategory, "store_not_authorized");
  assert.equal(event.resultQueryCount, 2);
  assert.equal(event.verifiedProductCount, 0);
  assert.equal(line.includes("REJECTED_QUERY_SENTINEL"), false);
  assert.equal(line.includes("ANOTHER_REJECTED_QUERY"), false);
});

test("validation and upstream failures retain safe categories and trace correlation", async () => {
  const validationWorker = createWorker(
    fakeOpenAI(answerPayload()),
  );
  const validation = await captureDiagnostic(() =>
    validationWorker.fetch(
      jsonRequest("/v1/agent/start", {
        protocolVersion: 2,
        message: "",
        storeNumber: "075",
      }),
      env,
    ),
  );
  assert.equal(validation.result.status, 400);
  assert.equal(
    validation.event.validationFailureCategory,
    "invalid_request",
  );
  assert.equal(validation.event.outcome, "validation_failure");
  assert.equal(
    validation.result.headers.get(TRACE_HEADER_NAME),
    validation.event.traceId,
  );

  const upstreamBody = "UPSTREAM_BODY_SENTINEL";
  const upstreamWorker = createWorker(
    fakeOpenAI(upstreamBody, { status: 500 }),
  );
  const upstream = await captureDiagnostic(() =>
    upstreamWorker.fetch(
      jsonRequest("/v1/agent/start", v2Start("FAILURE_USER_SENTINEL")),
      env,
    ),
  );
  assert.equal(upstream.result.status, 502);
  assert.equal(
    upstream.event.upstreamFailureCategory,
    "upstream_http_5xx",
  );
  assert.equal(upstream.event.upstreamStatus, 500);
  assert.equal(upstream.event.outcome, "upstream_failure");
  assert.equal(upstream.line.includes(upstreamBody), false);
  assert.equal(
    upstream.result.headers.get(TRACE_HEADER_NAME),
    upstream.event.traceId,
  );
});

test("attachment logs expose only generic image/pdf input kind", async () => {
  const cases = [
    {
      mime: "application/pdf",
      filename: "PRIVATE_FILENAME.pdf",
      bytes: new TextEncoder().encode("%PDF-1.7\nPRIVATE_PDF_CONTENT"),
      expected: "pdf",
    },
    {
      mime: "image/jpeg",
      filename: "PRIVATE_FILENAME.jpg",
      bytes: Uint8Array.from([0xff, 0xd8, 0xff, 0xdb, 1, 2, 3]),
      expected: "image",
    },
  ];

  for (const item of cases) {
    const form = new FormData();
    form.set(
      "payload",
      JSON.stringify({
        protocolVersion: 4,
        message: "PRIVATE_ATTACHMENT_MESSAGE",
        providerId: "obi-pl",
        branchId: "075",
      }),
    );
    form.set(
      "attachment",
      new File([item.bytes], item.filename, {
        type: item.mime,
      }),
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

    const worker = createWorker(fakeOpenAI(answerPayload()));
    const { event, line } = await captureDiagnostic(() =>
      worker.fetch(request, env),
    );

    assert.equal(event.inputKind, item.expected);
    assert.equal(line.includes(item.filename), false);
    assert.equal(
      line.includes("PRIVATE_ATTACHMENT_MESSAGE"),
      false,
    );
    assert.equal(line.includes("PRIVATE_PDF_CONTENT"), false);
  }
});

test("privacy regression: structured event excludes messages, queries, product content, ids, and secrets", async () => {
  const userMessage = "USER_MESSAGE_PRIVACY_SENTINEL";
  const query = "QUERY_PRIVACY_SENTINEL";
  const productName = "PRODUCT_PRIVACY_SENTINEL";
  const responseId = "resp_PRIVATE_RESPONSE_ID";
  const callId = "call_PRIVATE_CALL_ID";
  const result = verifiedV2Result({
    query,
    productName,
    obik: "9999999",
  });

  const worker = createWorker(
    fakeOpenAI(
      toolPayload(
        {
          storeNumber: "075",
          queries: [{ query, limit: 1 }],
        },
        LOCAL_TOOL_NAME,
        callId,
      ),
    ),
  );
  const start = await captureDiagnostic(() =>
    worker.fetch(
      jsonRequest("/v1/agent/start", v2Start(userMessage)),
      env,
    ),
  );

  const continuationWorker = createWorker(
    fakeOpenAI(
      answerPayload({
        id: responseId,
        productRefs: [
          { storeNumber: "075", obik: "9999999" },
        ],
      }),
    ),
  );
  const continuation = await captureDiagnostic(() =>
    continuationWorker.fetch(
      jsonRequest(
        "/v1/agent/continue",
        {
          protocolVersion: 2,
          responseId,
          callId,
          storeNumber: "075",
          tool: LOCAL_TOOL_NAME,
          result,
        },
        { traceId: start.event.traceId },
      ),
      env,
    ),
  );

  const combined = start.line + "\n" + continuation.line;
  for (const unsafe of [
    userMessage,
    query,
    productName,
    "9999999",
    APP_TOKEN,
    OPENAI_KEY,
    responseId,
    callId,
    "resp_previous_private",
    "call_previous_private",
    "Bearer",
  ]) {
    assert.equal(
      combined.includes(unsafe),
      false,
      `log leaked ${unsafe}`,
    );
  }
});

test("provider v3 tool request records requestedBranch presence without its value", async () => {
  const requestedBranch = "PRIVATE_BRANCH_HINT_SENTINEL";
  const worker = createWorker(
    fakeOpenAI(
      toolPayload(
        {
          providerId: "kwant-pl",
          branchId: "205",
          requestedBranch,
          queries: [{ query: "PRIVATE_KWANT_QUERY", limit: 2 }],
        },
        PROVIDER_LOCAL_TOOL_NAME,
      ),
    ),
  );

  const { event, line } = await captureDiagnostic(() =>
    worker.fetch(
      jsonRequest("/v1/agent/start", {
        protocolVersion: 3,
        message: "PRIVATE_KWANT_MESSAGE",
        providerId: "kwant-pl",
        branchId: "205",
      }),
      env,
    ),
  );

  assert.equal(event.providerId, "kwant-pl");
  assert.equal(event.branchId, "205");
  assert.equal(event.requestedBranchPresent, true);
  assert.equal(line.includes(requestedBranch), false);
  assert.equal(line.includes("PRIVATE_KWANT_QUERY"), false);
});
