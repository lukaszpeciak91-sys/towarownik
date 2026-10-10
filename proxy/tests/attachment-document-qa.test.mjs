import test from "node:test";
import assert from "node:assert/strict";
import { createWorker } from "../.test-dist/index.js";

const env = { TOWAROWNIK_APP_TOKEN: "doc-qa-test-token", OPENAI_API_KEY: "doc-qa-model-token" };
const csv = "article,product,price,quantity\nMBN116E,Hager B16,19.00,5\nEAT-B16,Eaton B16,24.00,3\n";
const md = "| product | quantity |\n| --- | --- |\n| Hager B16 | 5 |\n| Eaton B16 | 3 |\n";

function multipart(message, filename, mimeType, source, providerId = "kwant-pl", branchId = "205") {
  const form = new FormData();
  form.set("payload", JSON.stringify({ protocolVersion: 5, message, providerId, branchId }));
  form.append("attachment", new File([source], filename, { type: mimeType }));
  return new Request("https://proxy.example/v1/agent/start", {
    method: "POST",
    headers: {
      Authorization: "Bearer " + env.TOWAROWNIK_APP_TOKEN,
      "X-Taksula-Attachment-Protocol": "5",
      "Content-Length": "1024",
    },
    body: form,
  });
}

function jsonContinue(body) {
  return new Request("https://proxy.example/v1/agent/continue", {
    method: "POST",
    headers: { Authorization: "Bearer " + env.TOWAROWNIK_APP_TOKEN, "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

function answer(text) {
  return {
    id: "resp_answer",
    output: [{ type: "message", content: [{ type: "output_text", text: JSON.stringify({ text, productRefs: [] }) }] }],
  };
}

function findProducts() {
  return {
    id: "resp_tool",
    output: [{
      type: "function_call", call_id: "call_price", name: "find_products",
      arguments: JSON.stringify({
        providerId: "kwant-pl", branchId: "205", requestedBranch: null,
        queries: [{ query: "MBN116E", limit: 1 }],
      }),
    }],
  };
}

function upstream(...script) {
  const calls = [];
  return {
    calls,
    fetch: async (_url, opts) => {
      calls.push(JSON.parse(String(opts.body)));
      const result = script.shift();
      if (!result) throw Error("Unexpected model call");
      return new Response(JSON.stringify(result), {
        status: 200, headers: { "Content-Type": "application/json" },
      });
    },
  };
}

function userParts(call) {
  return call.input[0].content;
}

test("document CSV price QA has complete user evidence and an attributed answer without forced lookup", async () => {
  const mock = upstream(answer("W przesłanym cenniku Hager B16 ma cenę 19,00 zł."));
  const res = await createWorker(mock.fetch).fetch(
    multipart("Ile kosztuje Hager B16 w przesłanym cenniku?", "cennik.csv", "text/csv", csv), env,
  );
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.type, "answer");
  assert.match(body.text, /w przesłanym cenniku/i);
  assert.equal(mock.calls.length, 1, "document QA should not force provider lookup");
  assert.deepEqual(userParts(mock.calls[0]).map(x => x.type), ["input_text", "input_text"]);
  assert.match(userParts(mock.calls[0])[1].text, /Filename: "cennik.csv"/);
  assert.match(userParts(mock.calls[0])[1].text, /MBN116E,Hager B16,19.00,5/);
  assert.match(mock.calls[0].instructions, /document-only question does NOT authorize or require local provider lookup/i);
});

test("compare product rows in attached MD stays document QA, not verified provider facts", async () => {
  const mock = upstream(answer("W przesłanej tabeli Hager ma 5 szt., a Eaton 3 szt.; różnica to 2 szt."));
  const res = await createWorker(mock.fetch).fetch(
    multipart("Porównaj ilości dwóch produktów w załączniku", "zestawienie.md", "text/markdown", md,
      "obi-pl", "075"), env,
  );
  assert.equal(res.status, 200);
  assert.equal((await res.json()).type, "answer");
  assert.equal(mock.calls.length, 1);
  assert.match(userParts(mock.calls[0])[1].text, /Hager B16 \| 5/);
  assert.match(mock.calls[0].instructions, /compare rows or products, filter, calculate/i);
  assert.match(mock.calls[0].instructions, /w przesłanym cenniku/);
});

test("question about current KWANT price follows provider verification path, not attachment authority", async () => {
  const mock = upstream(findProducts());
  const res = await createWorker(mock.fetch).fetch(
    multipart("Czy 19 zł to aktualna cena w Kwancie?", "cennik.csv", "text/csv", csv), env,
  );
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.type, "tool_request");
  assert.equal(body.tool.name, "find_products");
  assert.equal(body.tool.arguments.providerId, "kwant-pl");
  assert.match(mock.calls[0].instructions, /request fresh local provider verification/i);
  assert.match(userParts(mock.calls[0])[1].text, /Hager B16,19.00/);
});

test("conflicting document and fresh provider price remain distinct across JSON-only continuation", async () => {
  const mock = upstream(
    findProducts(),
    answer("W przesłanym cenniku cena wynosi 19 zł. Aktualna zweryfikowana cena online w Kwancie wynosi 22 zł; nie potwierdza to ceny oddziałowej."),
  );
  const worker = createWorker(mock.fetch);
  const start = await worker.fetch(
    multipart("Czy cena z cennika to aktualna cena w Kwancie?", "cennik.csv", "text/csv", csv), env,
  );
  assert.equal(start.status, 200);
  const tool = await start.json();
  assert.equal(tool.type, "tool_request");

  const verified = {
    providerId: "kwant-pl", branchId: "205",
    results: [{
      query: "MBN116E", status: "verified",
      products: [{
        productId: "kw-1", articleNumber: "MBN116E", name: "Hager B16",
        brand: "Hager", shortDescription: null, technicalFacts: [],
        stock: 2, centralStock: null, price: 22, priceScope: "online",
      }],
    }],
  };
  const continued = await worker.fetch(jsonContinue({
    protocolVersion: 5, responseId: tool.responseId, callId: tool.tool.callId,
    providerId: "kwant-pl", branchId: "205", tool: "find_products", result: verified,
  }), env);
  assert.equal(continued.status, 200);
  const final = await continued.json();
  assert.equal(final.type, "answer");
  assert.match(final.text, /w przesłanym cenniku.*19 zł/i);
  assert.match(final.text, /aktualna zweryfikowana cena online.*22 zł/i);
  assert.deepEqual(JSON.parse(mock.calls[1].input[0].output), verified);
  assert.equal(mock.calls[1].previous_response_id, tool.responseId);
  assert.match(mock.calls[0].instructions, /local provider result wins for CURRENT provider claims/i);
  assert.equal(mock.calls.length, 2);
});

// These deterministic mocks verify prompt composition, transport and continuation.
// Live-model compliance with the clarified policy requires a separate behavioral evaluation.
