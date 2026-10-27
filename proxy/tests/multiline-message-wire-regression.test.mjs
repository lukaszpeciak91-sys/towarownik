import test from "node:test";
import assert from "node:assert/strict";
import { createWorker } from "../.test-dist/index.js";

// R3 test-only: capture exact message received by OpenAI mock. No external API calls.
const env = {
  TOWAROWNIK_APP_TOKEN: "r3-app-token",
  OPENAI_API_KEY: "r3-openai-key",
};
const message = [
  "w ofercie wpisałem poniższe czy się zgadza?",
  "60MGP-1zp — 50 szt.",
  "60MGPB-1zp — 8 szt.",
  "60MWP-1 — 6 szt.",
  "60MWP-2 — 2 szt.",
  "60MWP-3 — 6 szt.",
  "60MWP-6 — 1 szt.",
  "60MWP-4 — 1 szt.",
  "60MRTO-3 — 11 szt.",
  "60MRTO-2 — 18 szt.",
  "60MRTO-1 — 13 szt.",
].join("\n");

function modelCapture() {
  const received = [];
  const upstreamReply = {
    id: "resp_r3", status: "completed",
    output: [{
      type: "message",
      content: [{ type: "output_text", text: JSON.stringify({ text: "OK", productRefs: [] }) }],
    }],
  };
  const worker = createWorker(async (_url, options) => {
    received.push(JSON.parse(String(options.body)).input);
    return new Response(JSON.stringify(upstreamReply), {
      status: 200, headers: { "Content-Type": "application/json" },
    });
  });
  return { worker, received };
}

function jsonRequest(path, payload) {
  return new Request("https://proxy.example" + path, {
    method: "POST",
    headers: {
      Authorization: "Bearer " + env.TOWAROWNIK_APP_TOKEN,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(payload),
  });
}

function multipartRequest(path, payload) {
  const form = new FormData();
  form.append("payload", JSON.stringify(payload));
  form.append("attachment", new File(
    [Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, 1])],
    "photo.jpg", { type: "image/jpeg" },
  ));
  return new Request("https://proxy.example" + path, {
    method: "POST",
    headers: {
      Authorization: "Bearer " + env.TOWAROWNIK_APP_TOKEN,
      "X-Taksula-Attachment-Protocol": "5",
      "Content-Length": "1024",
    },
    body: form,
  });
}

test("R3 RED provider v3 JSON START preserves 11 lines through Worker into Responses", async () => {
  const { worker, received } = modelCapture();
  const result = await worker.fetch(jsonRequest("/v1/agent/start", {
    protocolVersion: 3, providerId: "kwant-pl", branchId: "205", message,
  }), env);
  assert.equal(result.status, 200);
  assert.equal(received.length, 1);
  assert.equal(received[0], message);
  assert.equal(received[0].split("\n").length, 11);
});

test("R3 RED provider v3 JSON MESSAGE preserves the quote and previous response", async () => {
  const { worker, received } = modelCapture();
  const result = await worker.fetch(jsonRequest("/v1/agent/message", {
    protocolVersion: 3, providerId: "kwant-pl", branchId: "205",
    previousResponseId: "resp_previous", message,
  }), env);
  assert.equal(result.status, 200);
  assert.equal(received.length, 1);
  // OpenAI's MESSAGE still has the original structured current USER message.
  assert.equal(received[0], message);
});

test("R3 RED provider v5 multipart START preserves meaningful edge indentation and trailing LF", async () => {
  const { worker, received } = modelCapture();
  const structured = "  • 0001234567890\t— 2 szt.\n\n\t- Śruby × 4\n";
  const result = await worker.fetch(multipartRequest("/v1/agent/start", {
    protocolVersion: 5, providerId: "kwant-pl", branchId: "205", message: structured,
  }), env);
  assert.equal(result.status, 200);
  assert.equal(received.length, 1);
  assert.equal(received[0][0].content[0].text, structured);
});

test("R3 RED provider v5 multipart MESSAGE preserves structured order without trimming", async () => {
  const { worker, received } = modelCapture();
  const structured = "  Wiersz 1\n\n  Wiersz 2\n";
  const result = await worker.fetch(multipartRequest("/v1/agent/message", {
    protocolVersion: 5, providerId: "kwant-pl", branchId: "205",
    previousResponseId: "resp_before", message: structured,
  }), env);
  assert.equal(result.status, 200);
  assert.equal(received.length, 1);
  assert.equal(received[0][0].content[0].text, structured);
});

test("R3 current Worker still supports safe single line v3 and attachment only v5", async () => {
  const plain = modelCapture();
  assert.equal((await plain.worker.fetch(jsonRequest("/v1/agent/start", {
    protocolVersion: 3, providerId: "kwant-pl", branchId: "205",
    message: "Sprawdź MBN116E",
  }), env)).status, 200);
  assert.equal(plain.received[0], "Sprawdź MBN116E");

  const attach = modelCapture();
  assert.equal((await attach.worker.fetch(multipartRequest("/v1/agent/start", {
    protocolVersion: 5, providerId: "kwant-pl", branchId: "205",
    message: "",
  }), env)).status, 200);
  assert.equal(attach.received[0][0].content[0].type, "input_image");
});
