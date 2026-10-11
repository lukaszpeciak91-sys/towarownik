import assert from "node:assert/strict";
import { test } from "node:test";
import { parseContinueRequest, InvalidRequestError } from "../.test-dist/validation.js";

const evidence = ({
  checkedIds = ["128", "216"],
  returnedIds = [],
  missingIds = ["128", "216"],
  locations = [
    { branchId: "128", name: "Zamość", stock: null },
    { branchId: "216", name: "Tarnów", stock: null },
  ],
  coverage = "unknown",
  status = "unavailable",
  reason = "transport",
} = {}) => ({
  providerId: "kwant-pl",
  productId: "580",
  status,
  reason,
  coverage,
  checkedIds,
  returnedIds,
  missingIds,
  locations,
  verifiedAtMillis: null,
  centralStock: null,
});

function request(result, providerId = "kwant-pl") {
  return new Request("https://localhost/v1/agent/continue", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Taksula-Locations-Capability": "1",
    },
    body: JSON.stringify({
      protocolVersion: providerId === "obi-pl" ? 2 : 3,
      responseId: "resp_prior",
      callId: "call_loc",
      ...(providerId === "obi-pl"
        ? { storeNumber: "075" }
        : { providerId, branchId: "205" }),
      tool: "find_product_locations",
      result,
    }),
  });
}

test("KWANT transport, timeout, malformed and no trusted rows are valid canonical unknown evidence", async () => {
  for (const reason of ["transport", "malformed_response", "no_trusted_rows"]) {
    const parsed = await parseContinueRequest(request(evidence({ reason })));
    assert.equal(parsed.tool, "find_product_locations");
    assert.deepEqual(parsed.result.checkedIds, ["128", "216"]);
    assert.deepEqual(parsed.result.missingIds, ["128", "216"]);
    assert.deepEqual(parsed.result.returnedIds, []);
    assert.deepEqual(parsed.result.locations.map(x => x.stock), [null, null]);
  }
});

test("KWANT directory unavailable may send a completely empty unknown scope", async () => {
  const result = evidence({
    checkedIds: [], missingIds: [], locations: [], reason: "directory_unavailable",
  });
  const parsed = await parseContinueRequest(request(result));
  assert.deepEqual(parsed.result.checkedIds, []);
  assert.deepEqual(parsed.result.locations, []);
  assert.equal(parsed.result.status, "unavailable");
});

test("Worker rejects original Android mismatch and fabricated zero on unavailable", async () => {
  for (const invalid of [
    evidence({ locations: [] }),
    evidence({ locations: [{ branchId: "128", name: "Zamość", stock: 0 }] }),
    evidence({ checkedIds: ["128"], missingIds: [], locations: [
      { branchId: "128", name: "Zamość", stock: null },
    ] }),
    evidence({ coverage: "requested_subset" }),
  ]) {
    await assert.rejects(() => parseContinueRequest(request(invalid)), InvalidRequestError);
  }
});

test("Worker accepts partial KWANT and unchanged OBI null-stock coverage semantics", async () => {
  const partial = evidence({
    status: "verified",
    reason: null,
    coverage: "partial",
    checkedIds: ["128", "216"],
    returnedIds: ["128"],
    missingIds: ["216"],
    locations: [
      { branchId: "128", name: "Zamość", stock: 0 },
      { branchId: "216", name: "Tarnów", stock: null },
    ],
  });
  partial.verifiedAtMillis = 123456;
  const parsed = await parseContinueRequest(request(partial));
  assert.equal(parsed.result.locations[0].stock, 0);
  assert.equal(parsed.result.locations[1].stock, null);

  const obi = { ...evidence({
    checkedIds: ["075"],
    missingIds: ["075"],
    locations: [{ branchId: "075", name: "Nowy Sącz", stock: null }],
  }), providerId: "obi-pl", productId: "3496072" };
  const obiParsed = await parseContinueRequest(request(obi, "obi-pl"));
  assert.equal(obiParsed.result.providerId, "obi-pl");
  assert.equal(obiParsed.result.locations[0].stock, null);
});
