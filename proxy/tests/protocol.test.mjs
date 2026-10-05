import test from "node:test";
import assert from "node:assert/strict";

import {
  LOCAL_TOOL_NAME,
  PROVIDER_LOCAL_TOOL_NAME,
} from "../.test-dist/config.js";
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

function multipartRequest(path, payload, bytes, mimeType, extraFile = null) {
  const form = new FormData();
  form.set("payload", JSON.stringify(payload));
  form.set("attachment", new File([bytes], "attachment", { type: mimeType }));
  if (extraFile) form.append("attachment", extraFile);
  return new Request(`https://proxy.example${path}`, {
    method: "POST",
    headers: { Authorization: `Bearer ${APP_TOKEN}` },
    body: form,
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

function toolPayload(argumentsValue, name = LOCAL_TOOL_NAME) {
  return {
    id: "resp_tool",
    output: [
      {
        type: "function_call",
        call_id: "call_tool",
        name,
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
  assert.equal(body.tool.name, LOCAL_TOOL_NAME);
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

  const upstreamTool =
    fake.captures[0].body.tools.find(
      (tool) => tool.type === "function",
    );
  assert.equal(upstreamTool.name, LOCAL_TOOL_NAME);
  assert.deepEqual(
    upstreamTool.parameters.required,
    ["storeNumber", "queries"],
  );
  assert.match(
    fake.captures[0].body.instructions,
    /Current conversation store for this USER turn is OBI 075/,
  );
  assert.match(
    fake.captures[0].body.instructions,
    /find_obi_products/,
  );
  assert.equal(
    fake.captures[0].body.instructions.includes("find_products"),
    false,
  );
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

test("protocol v3 KWANT request receives provider-aware product tool", async () => {
  const fake = fakeOpenAI(
    toolPayload(
      {
        providerId: "kwant-pl",
        branchId: "205",
        requestedBranch: null,
        queries: [
          { query: "MBN116E", limit: 2 },
        ],
      },
      PROVIDER_LOCAL_TOOL_NAME,
    ),
  );
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    request("/v1/agent/start", {
      protocolVersion: 3,
      message: "sprawdź MBN116E",
      providerId: "kwant-pl",
      branchId: "205",
    }),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  const body = await responseJson(response);
  assert.equal(body.type, "tool_request");
  assert.equal(body.tool.name, PROVIDER_LOCAL_TOOL_NAME);
  assert.deepEqual(body.tool.arguments, {
    providerId: "kwant-pl",
    branchId: "205",
    requestedBranch: null,
    queries: [
      { query: "MBN116E", limit: 2 },
    ],
  });

  const upstreamTool =
    fake.captures[0].body.tools.find(
      (tool) => tool.type === "function",
    );
  assert.deepEqual(
    upstreamTool.parameters.required,
    ["providerId", "branchId", "requestedBranch", "queries"],
  );
  const providerQuerySchema = upstreamTool.parameters.properties
    .queries.items;
  assert.deepEqual(
    providerQuerySchema.required,
    ["query", "limit"],
  );
  assert.equal(
    Object.hasOwn(providerQuerySchema.properties, "productId"),
    false,
  );
  assert.match(
    fake.captures[0].body.instructions,
    /providerId=kwant-pl.*branchId=205/i,
  );
  assert.match(
    fake.captures[0].body.instructions,
    /priceScope=online/,
  );
  assert.doesNotMatch(
    fake.captures[0].body.instructions,
    /Current conversation store for this USER turn is OBI/,
  );
  assert.match(
    fake.captures[0].body.instructions,
    /do not describe a KWANT identifier as OBIK/,
  );
});

test("protocol v4 image multipart supports attachment-only and high-detail image input", async () => {
  const fake = fakeOpenAI(answerPayload("image"));
  const worker = createWorker(fake.fetch);
  const jpeg = Uint8Array.from([0xff, 0xd8, 0xff, 0xdb, 1]);
  const response = await worker.fetch(multipartRequest("/v1/agent/start", {
    protocolVersion: 4, message: "", providerId: "obi-pl", branchId: "075",
  }, jpeg, "image/jpeg"), configuredEnv);
  assert.equal(response.status, 200);
  const input = fake.captures[0].body.input;
  assert.equal(input[0].content.length, 1);
  assert.deepEqual(input[0].content[0], {
    type: "input_image", image_url: `data:image/jpeg;base64,${Buffer.from(jpeg).toString("base64")}`, detail: "high",
  });
});

test("protocol v4 PDF message preserves previous_response_id and uses input_file", async () => {
  const fake = fakeOpenAI(answerPayload("pdf"));
  const worker = createWorker(fake.fetch);
  const pdf = new TextEncoder().encode("%PDF-1.7\n");
  const response = await worker.fetch(multipartRequest("/v1/agent/message", {
    protocolVersion: 4, previousResponseId: "resp_previous", message: "odczytaj kod",
    providerId: "kwant-pl", branchId: "205",
  }, pdf, "application/pdf"), configuredEnv);
  assert.equal(response.status, 200);
  assert.equal(fake.captures[0].body.previous_response_id, "resp_previous");
  assert.deepEqual(fake.captures[0].body.input[0].content.map((part) => part.type), ["input_text", "input_file"]);
  assert.equal(fake.captures[0].body.input[0].content[1].file_data,
    `data:application/pdf;base64,${Buffer.from(pdf).toString("base64")}`);
});

test("protocol v4 multipart rejects wrong MIME signature and multiple attachments", async () => {
  const worker = createWorker(fakeOpenAI(answerPayload()).fetch);
  const payload = { protocolVersion: 4, message: "x", providerId: "obi-pl", branchId: "075" };
  const wrong = await worker.fetch(multipartRequest("/v1/agent/start", payload,
    new TextEncoder().encode("%PDF-1.7"), "image/png"), configuredEnv);
  assert.equal(wrong.status, 400);
  const multiple = await worker.fetch(multipartRequest("/v1/agent/start", payload,
    Uint8Array.from([0xff, 0xd8, 0xff]), "image/jpeg",
    new File(["%PDF-"], "second.pdf", { type: "application/pdf" })), configuredEnv);
  assert.equal(multiple.status, 400);
});

test("protocol v4 multipart rejects an attachment above 16 MiB", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);
  const oversized = new Uint8Array(16 * 1024 * 1024 + 1);
  oversized.set([0xff, 0xd8, 0xff]);
  const response = await worker.fetch(multipartRequest("/v1/agent/start", {
    protocolVersion: 4, message: "x", providerId: "obi-pl", branchId: "075",
  }, oversized, "image/jpeg"), configuredEnv);
  assert.equal(response.status, 413);
  assert.equal(fake.captures.length, 0);
});

test("protocol v4 continue remains JSON-only and does not resend attachment", async () => {
  const fake = fakeOpenAI(answerPayload("continued"));
  const worker = createWorker(fake.fetch);
  const response = await worker.fetch(request("/v1/agent/continue", {
    protocolVersion: 4,
    responseId: "resp_with_attachment",
    callId: "call_products",
    providerId: "obi-pl",
    branchId: "075",
    tool: "find_products",
    result: {
      providerId: "obi-pl",
      branchId: "075",
      queries: [{ query: "kod ze zdjęcia", limit: 1 }],
      rejection: "local_tool_limit_reached",
    },
  }), configuredEnv);
  assert.equal(response.status, 200);
  assert.equal(fake.captures[0].body.previous_response_id, "resp_with_attachment");
  assert.deepEqual(fake.captures[0].body.input.map((entry) => entry.type), ["function_call_output"]);
  assert.equal(JSON.stringify(fake.captures[0].body).includes("data:"), false);
});

test("protocol v3 KWANT continue preserves provider product refs", async () => {
  const structured = JSON.stringify({
    text: "Mam zweryfikowany produkt.",
    productRefs: [
      {
        providerId: "kwant-pl",
        branchId: "205",
        productId: "580",
      },
    ],
  });
  const fake = fakeOpenAI({
    id: "resp_final",
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
  });
  const worker = createWorker(fake.fetch);
  const result = {
    providerId: "kwant-pl",
    branchId: "205",
    results: [
      {
        query: "MBN116E",
        status: "verified",
        products: [
          {
            productId: "580",
            articleNumber: "MBN116E/HAG",
            name: "Wyłącznik nadprądowy B16",
            brand: "Hager",
            shortDescription: null,
            technicalFacts: [],
            stock: 140,
            centralStock: 5918,
            price: 14.55,
            priceScope: "online",
          },
        ],
      },
    ],
  };

  const response = await worker.fetch(
    request("/v1/agent/continue", {
      protocolVersion: 3,
      responseId: "resp_tool",
      callId: "call_tool",
      providerId: "kwant-pl",
      branchId: "205",
      tool: PROVIDER_LOCAL_TOOL_NAME,
      result,
    }),
    configuredEnv,
  );

  assert.equal(response.status, 200);
  const body = await responseJson(response);
  assert.equal(body.type, "answer");
  assert.deepEqual(body.productRefs, [
    {
      providerId: "kwant-pl",
      branchId: "205",
      productId: "580",
    },
  ]);
  assert.deepEqual(
    JSON.parse(fake.captures[0].body.input[0].output),
    result,
  );
});

test("unsupported future protocol version fails explicitly before upstream work", async () => {
  const fake = fakeOpenAI(answerPayload());
  const worker = createWorker(fake.fetch);

  const response = await worker.fetch(
    request("/v1/agent/start", {
      protocolVersion: 5,
      message: "future client",
      providerId: "kwant-pl",
      branchId: "205",
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
