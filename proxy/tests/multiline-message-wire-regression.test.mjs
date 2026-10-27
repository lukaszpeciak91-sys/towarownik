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


function legacyV2Request(path, text) {
  return jsonRequest(path, {
    protocolVersion: 2, storeNumber: "075",
    ...(path.endsWith("/message") ? { previousResponseId: "resp_previous" } : {}),
    message: text,
  });
}

function multipartByVersion(path, payload, count = 1) {
  const form = new FormData();
  form.append("payload", JSON.stringify(payload));
  const parts = [
    { bytes: Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, 1]), name: "first.jpg", type: "image/jpeg" },
    { bytes: Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1]), name: "second.png", type: "image/png" },
    { bytes: new TextEncoder().encode("%PDF-1.7\n"), name: "third.pdf", type: "application/pdf" },
  ];
  for (const { bytes, name, type } of parts.slice(0, count)) {
    form.append("attachment", new File([bytes], name, { type }));
  }
  return new Request("https://proxy.example" + path, {
    method: "POST",
    headers: {
      Authorization: "Bearer " + env.TOWAROWNIK_APP_TOKEN,
      "Content-Length": "2048",
      ...(payload.protocolVersion === 5 ? { "X-Taksula-Attachment-Protocol": "5" } : {}),
    },
    body: form,
  });
}

test("R3 v2 legacy grouped OBI JSON START and MESSAGE keep model-boundary line order", async () => {
  for (const path of ["/v1/agent/start", "/v1/agent/message"]) {
    const { worker, received } = modelCapture();
    const result = await worker.fetch(legacyV2Request(path, message), env);
    assert.equal(result.status, 200);
    assert.deepEqual(received, [message]);
    assert.equal(received[0].split("\n").length, 11);
  }
});

test("R3 v3 JSON preserves CRLF CR and LF as canonical LF with Unicode and tabs", async () => {
  const expected = "  • 0001234567890\t— 2 szt.\n\n\t30×70 cm\n  Zażółć gęślą jaźń\n";
  for (const lineEnding of ["\n", "\r\n", "\r"]) {
    for (const path of ["/v1/agent/start", "/v1/agent/message"]) {
      const { worker, received } = modelCapture();
      const input = expected.replaceAll("\n", lineEnding);
      const result = await worker.fetch(jsonRequest(path, {
        protocolVersion: 3, providerId: "kwant-pl", branchId: "205",
        ...(path.endsWith("/message") ? { previousResponseId: "resp_previous" } : {}),
        message: input,
      }), env);
      assert.equal(result.status, 200);
      assert.deepEqual(received, [expected]);
    }
  }
});

test("R3 v4 multipart START and MESSAGE preserve same USER string as JSON plus attachment", async () => {
  const structured = "  • EAN 0001234567890\t— 2 szt.\n\n  30×70 cm\n";
  for (const path of ["/v1/agent/start", "/v1/agent/message"]) {
    const { worker, received } = modelCapture();
    const response = await worker.fetch(multipartByVersion(path, {
      protocolVersion: 4, providerId: "kwant-pl", branchId: "205",
      ...(path.endsWith("/message") ? { previousResponseId: "resp_previous" } : {}),
      message: structured,
    }), env);
    assert.equal(response.status, 200);
    assert.equal(received.length, 1);
    assert.equal(received[0][0].content[0].text, structured);
    assert.deepEqual(received[0][0].content.map(entry => entry.type), ["input_text", "input_image"]);
  }
});

test("R3 v5 multipart START and MESSAGE preserve text with 1 to 3 ordered attachments", async () => {
  const structured = "Pierwszy\n\n  drugi\t— 0000123456789\n";
  for (const count of [1, 2, 3]) {
    for (const path of ["/v1/agent/start", "/v1/agent/message"]) {
      const { worker, received } = modelCapture();
      const response = await worker.fetch(multipartByVersion(path, {
        protocolVersion: 5, providerId: "kwant-pl", branchId: "205",
        ...(path.endsWith("/message") ? { previousResponseId: "resp_previous" } : {}),
        message: structured,
      }, count), env);
      assert.equal(response.status, 200);
      assert.equal(received[0][0].content[0].text, structured);
      assert.deepEqual(
        received[0][0].content.map(entry => entry.type),
        ["input_text", "input_image", "input_image", "input_file"].slice(0, count + 1),
      );
    }
  }
});

test("R3 current USER controls are sanitized without flattening authorized layout characters", async () => {
  const { worker, received } = modelCapture();
  const result = await worker.fetch(jsonRequest("/v1/agent/start", {
    protocolVersion: 3, providerId: "kwant-pl", branchId: "205",
    message: "  A\tB\r\nC\u0000\u0001D\u007fE\r\nŻ\t00001234\n",
  }), env);
  assert.equal(result.status, 200);
  assert.deepEqual(received, ["  A\tB\nC  D E\nŻ\t00001234\n"]);
});

test("R3 JSON blankness, 2000 UTF-16 units and 4 KiB BODY bound still fail closed", async () => {
  for (const input of [" \r\n\t ", "\u0000\n\t"]) {
    const { worker, received } = modelCapture();
    const response = await worker.fetch(jsonRequest("/v1/agent/start", {
      protocolVersion: 3, providerId: "kwant-pl", branchId: "205", message: input,
    }), env);
    assert.equal(response.status, 400);
    assert.equal(received.length, 0);
  }
  const max = modelCapture();
  assert.equal((await max.worker.fetch(jsonRequest("/v1/agent/start", {
    protocolVersion: 3, providerId: "kwant-pl", branchId: "205",
    message: "A".repeat(2000),
  }), env)).status, 200);
  assert.equal(max.received[0].length, 2000);

  for (const value of ["A".repeat(2001), "ą".repeat(2000)]) {
    const { worker, received } = modelCapture();
    const response = await worker.fetch(jsonRequest("/v1/agent/start", {
      protocolVersion: 3, providerId: "kwant-pl", branchId: "205", message: value,
    }), env);
    assert.equal(response.status, 413);
    assert.equal(received.length, 0);
  }
  const utf16 = modelCapture();
  assert.equal((await utf16.worker.fetch(jsonRequest("/v1/agent/start", {
    protocolVersion: 3, providerId: "kwant-pl", branchId: "205",
    message: "😀".repeat(900),
  }), env)).status, 200);
  assert.equal(utf16.received[0].length, 1800);
});

test("R3 v5 attachment-only remains valid and over-2000 USER characters reject", async () => {
  const blank = modelCapture();
  assert.equal((await blank.worker.fetch(multipartByVersion("/v1/agent/start", {
    protocolVersion: 5, providerId: "kwant-pl", branchId: "205", message: "",
  }), env)).status, 200);
  assert.deepEqual(blank.received[0][0].content.map(x => x.type), ["input_image"]);

  const over = modelCapture();
  const response = await over.worker.fetch(multipartByVersion("/v1/agent/start", {
    protocolVersion: 5, providerId: "kwant-pl", branchId: "205", message: "A".repeat(2001),
  }), env);
  assert.equal(response.status, 400);
  assert.equal(over.received.length, 0);
});

test("R3 CONTINUE sends only function_call_output, never original USER message again", async () => {
  const { worker, received } = modelCapture();
  const response = await worker.fetch(jsonRequest("/v1/agent/continue", {
    protocolVersion: 5, providerId: "kwant-pl", branchId: "205",
    responseId: "resp_tool", callId: "call_1", tool: "find_products",
    result: {
      providerId: "kwant-pl", branchId: "205",
      queries: [{ query: "MBN116E", limit: 1 }],
      rejection: "local_tool_limit_reached",
    },
  }), env);
  assert.equal(response.status, 200);
  assert.deepEqual(received[0].map(entry => entry.type), ["function_call_output"]);
  assert.equal(JSON.stringify(received[0]).includes("60MGP-1zp"), false);
});
