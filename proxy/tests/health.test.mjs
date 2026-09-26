import test from "node:test";
import assert from "node:assert/strict";
import worker from "../.test-dist/index.js";

const secretEnv = {
  OPENAI_API_KEY: "openai-secret-placeholder",
  TOWAROWNIK_APP_TOKEN: "app-token-placeholder",
};

async function responseJson(response) {
  return JSON.parse(await response.text());
}

test("GET /health returns stable 200 JSON", async () => {
  const response = await worker.fetch(
    new Request("https://proxy.example/health", { method: "GET" }),
    secretEnv,
  );

  assert.equal(response.status, 200);
  assert.equal(response.headers.get("Content-Type"), "application/json");
  assert.deepEqual(await responseJson(response), {
    ok: true,
    service: "towarownik-proxy",
  });
});

test("unknown route returns bounded JSON 404", async () => {
  const response = await worker.fetch(
    new Request("https://proxy.example/unknown"),
    secretEnv,
  );

  assert.equal(response.status, 404);
  assert.deepEqual(await responseJson(response), {
    error: "not_found",
  });
});

test("unsupported method on /health returns 405", async () => {
  const response = await worker.fetch(
    new Request("https://proxy.example/health", { method: "POST" }),
    secretEnv,
  );

  assert.equal(response.status, 405);
  assert.equal(response.headers.get("Allow"), "GET");
  assert.deepEqual(await responseJson(response), {
    error: "method_not_allowed",
  });
});

test("responses never expose environment or secret values", async () => {
  const health = await worker.fetch(
    new Request("https://proxy.example/health"),
    secretEnv,
  );
  const missing = await worker.fetch(
    new Request("https://proxy.example/missing"),
    secretEnv,
  );

  const combined = (await health.text()) + (await missing.text());

  assert.equal(combined.includes(secretEnv.OPENAI_API_KEY), false);
  assert.equal(combined.includes(secretEnv.TOWAROWNIK_APP_TOKEN), false);
  assert.equal(combined.includes("OPENAI_API_KEY"), false);
  assert.equal(combined.includes("TOWAROWNIK_APP_TOKEN"), false);
});
