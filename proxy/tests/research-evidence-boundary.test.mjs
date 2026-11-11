import test from "node:test";
import assert from "node:assert/strict";
import { normalizeOpenAIResponse } from "../.test-dist/openai.js";

// Contract investigation only: neither normalizer success nor a citation is
// semantic authorization. No verifier or live service is simulated as trusted.
globalThis.fetch = async () => { throw new Error("Live traffic prohibited"); };
const safe = "Dokumentacja producenta opisuje serię. Nie sprawdzałam zapasów ani cen.";
const mixed = `${safe} Produkt jest dostępny: 999 sztuk po 1 zł.`;
const url = "https://manufacturer.example.invalid/manual";
function payload(text, { span = 10, web = true, title = "Synthetic documentation", extra = {} } = {}) {
  const structured = JSON.stringify({ text, productRefs: [], ...extra });
  const start = structured.indexOf(text);
  return {
    id: "resp_evidence", status: "completed", output: [
      ...(web ? [{ type: "web_search_call", id: "ws_1", status: "completed", action: { type: "search", query: "Synthetic" } }] : []),
      { type: "message", content: [{ type: "output_text", text: structured,
        annotations: [{ type: "url_citation", title, url, start_index: start, end_index: start + Math.min(span, text.length) }] }] },
    ],
  };
}
const normalize = p => normalizeOpenAIResponse(p, "CONTINUE", true, 3);

test("safe and mixed prose have identical non-text protocol evidence", () => {
  const a = normalize(payload(safe));
  const b = normalize(payload(mixed));
  assert.equal(a.type, "answer");
  assert.equal(b.type, "answer");
  assert.deepEqual({ ...a, text: "" }, { ...b, text: "" });
  assert.equal(b.text, mixed);
  assert.deepEqual(b.productRefs, []);
  assert.equal(b.webSearchCalls, 1);
});
test("a citation spanning an entire mixed answer does not verify its claims", () => {
  const answer = normalize(payload(mixed, { span: mixed.length }));
  assert.equal(answer.sources[0].startIndex, 0);
  assert.equal(answer.sources[0].endIndex, mixed.length);
  assert.equal(answer.text, mixed);
  assert.equal("verifiedResearch" in answer, false);
});
test("valid citation metadata can exist without a current completed web call", () => {
  const answer = normalize(payload(safe, { web: false }));
  assert.equal(answer.webSearchCalls, 0);
  assert.equal(answer.sources[0].url, url);
});
test("source titles are metadata rather than verified technical sentences", () => {
  const title = "KWANT 205: 999 sztuk po 1 zł";
  const answer = normalize(payload(safe, { title }));
  assert.equal(answer.sources[0].title, title);
  assert.equal(answer.text, safe);
});
test("model-selected safety labels cannot extend the strict answer contract", () => {
  for (const extra of [{ answerKind: "technical" }, { safe: true }, { approvedResearchFragments: ["invented-proof"] }]) {
    assert.throws(() => normalize(payload(mixed, { extra })));
  }
});
test("a prose URL creates no annotation evidence", () => {
  const p = payload(`${safe} ${url}`);
  p.output.at(-1).content[0].annotations = [];
  const answer = normalize(p);
  assert.equal(answer.sources, undefined);
});
