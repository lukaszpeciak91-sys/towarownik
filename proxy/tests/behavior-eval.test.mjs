import test from "node:test";
import assert from "node:assert/strict";

import {
  OBI_GROUPED_ADVISOR_PROTOCOL_VERSION,
  PROVIDER_ADVISOR_PROTOCOL_VERSION,
  FINAL_ANSWER_FORMAT,
  PROVIDER_FINAL_ANSWER_FORMAT,
  OPENAI_MAX_OUTPUT_TOKENS,
  OPENAI_MODEL,
  OPENAI_REASONING_EFFORT,
  WEB_SEARCH_TOOL,
  agentInstructionsForProfile,
  agentInstructionsForStore,
  localToolForProtocol,
  obiToolForProtocol,
} from "../.test-dist/config.js";
import {
  BEHAVIOR_REGRESSION_SCENARIOS,
  BEHAVIOR_SCENARIOS,
  behaviorScenario,
  behaviorScenarioForProvider,
  behaviorSuiteExitCode,
  createOpenAISemanticJudge,
  createProductionAdvisorDriver,
  formatBehaviorSummary,
  runBehaviorSuite,
  runBehaviorTrial,
} from "../.test-dist/evals/taksula_behavior.js";

function answer(text, productRefs = [], webSearchCalls = 0) {
  return {
    type: "answer",
    responseId: "resp_final",
    text,
    productRefs,
    webSearchCalls,
  };
}

function toolRequest(queries) {
  return {
    type: "tool_request",
    responseId: "resp_tool",
    tool: {
      name: "find_obi_products",
      callId: "call_tool",
      arguments: {
        storeNumber: "075",
        queries,
      },
    },
    webSearchCalls: 0,
  };
}

function verifiedRefs(result) {
  if (!result || !("results" in result)) return [];
  return result.results.flatMap((group) =>
    group.status === "verified"
      ? group.products.map((product) =>
          "providerId" in result
            ? {
                providerId: result.providerId,
                branchId: result.branchId,
                productId: product.productId,
              }
            : {
                storeNumber: result.storeNumber,
                obik: product.obik,
              },
        )
      : [],
  );
}

function kwantScriptedDriver(scenario) {
  const base = scriptedDriver(scenario);
  return {
    async start(...args) {
      if (scenario.id === "D") {
        return answer(
          "Czy potrzebny jest wyłącznik 1P, czy inna konfiguracja biegunów i zastosowanie?",
        );
      }

      const result = await base.start(...args);
      if (result.type !== "tool_request") return result;
      const identifiers = {
        F: "MBN116E/HAG",
        G_ZERO: "EVAL-KW-0002",
        G_NULL: "EVAL-KW-0003",
        G_NOT_FOUND: "EVAL-KW-0004",
        G_UNAVAILABLE: "EVAL-KW-0005",
      };
      const identifier = identifiers[scenario.id];
      return {
        ...result,
        tool: {
          ...result.tool,
          name: "find_products",
          arguments: {
            providerId: "kwant-pl",
            branchId: "205",
            queries: result.tool.arguments.queries.map((query) => ({
              ...query,
              query:
                scenario.id === "ONLY_IN_STOCK"
                  ? "wyłączniki nadprądowe B16 1P"
                  : identifier ?? query.query,
            })),
          },
        },
      };
    },
    async continueTurn(...args) {
      if (scenario.id === "ONLY_IN_STOCK") {
        const result = args[3];
        const qualifying = verifiedRefs(result).filter(
          (ref) =>
            "productId" in ref &&
            ref.productId === "kw-7400002",
        );
        return answer(
          "Na pewno na stanie w wybranym oddziale jest zweryfikowany wyłącznik B16 1P: 4 szt. To wynik ograniczonego sprawdzenia, nie pełny dowód braku innych wariantów.",
          qualifying,
        );
      }
      return base.continueTurn(...args);
    },
  };
}

function scriptedDriver(scenario) {
  return {
    async start() {
      switch (scenario.id) {
        case "A":
          return toolRequest([
            { query: "czarne trytytki", limit: 3 },
          ]);
        case "D":
          return answer(
            "Jaki gwint lub średnicę przyłącza ma mieć ta końcówka?",
          );
        case "I":
          return answer(
            "SDS+ jest do lżejszych prac i mniejszych młotowiertarek, a SDS Max do cięższych prac i większych średnic.",
          );
        case "B":
          return toolRequest([
            { query: "czarne trytytki", limit: 3 },
          ]);
        case "E":
          return toolRequest([
            {
              query: "czarne trytytki",
              limit: 3,
            },
          ]);
        case "C":
          return toolRequest([
            {
              query:
                "czarne opaski zaciskowe trytytki 4,2 x 380 mm",
              limit: 1,
            },
          ]);
        case "OBI_NATURAL_BRANCH":
        case "OBI_AMBIGUOUS_BRANCH":
          return toolRequest([
            { query: "OBIK 7000001", limit: 1 },
          ]);
        case "ONLY_IN_STOCK":
          return toolRequest([
            { query: "miski", limit: 3 },
          ]);
        case "PRODUCT_INTENT":
          return toolRequest([
            {
              query: "wyłącznik nadprądowy B16 1P 6 kA",
              limit: 1,
            },
          ]);
        case "F":
          return toolRequest([
            { query: "OBIK 7000001", limit: 1 },
          ]);
        case "G_ZERO":
          return toolRequest([
            { query: "OBIK 7000002", limit: 1 },
          ]);
        case "G_NULL":
          return toolRequest([
            { query: "OBIK 7000003", limit: 1 },
          ]);
        case "G_NOT_FOUND":
          return toolRequest([
            { query: "OBIK 7000004", limit: 1 },
          ]);
        case "G_UNAVAILABLE":
          return toolRequest([
            { query: "OBIK 7000005", limit: 1 },
          ]);
        case "H":
          return answer(
            "Do takiej szczeliny użyj silikonu sanitarnego; biały albo bezbarwny dobierz do wykończenia. Powierzchnię najpierw oczyść i odtłuść. Przy kartuszu przyda się wyciskacz, a taśma malarska lub gładzik mogą ułatwić równe wykończenie.",
          );
        case "H_AMBIGUOUS":
          return answer(
            "Czy chodzi o uszczelnienie szczeliny przy ścianie/blacie, czy o przeciek przy odpływie lub syfonie?",
          );
      }
    },

    async continueTurn(
      _responseId,
      _callId,
      _storeNumber,
      result,
    ) {
      const refs = verifiedRefs(result);
      switch (scenario.id) {
        case "A":
        case "B":
        case "E":
          return answer(
            "Znalazłam m.in. kilka wariantów różniących się wymiarami.",
            refs,
          );
        case "C":
          return answer("Pasuje zweryfikowany wariant.", refs);
        case "OBI_NATURAL_BRANCH":
          return answer(
            "Sprawdziłam OBIK 7000001 w OBI Wielicka.",
            refs,
          );
        case "OBI_AMBIGUOUS_BRANCH":
          return answer(
            "Który market OBI w Krakowie masz na myśli? Podaj proszę ulicę lub dokładniejszą lokalizację.",
          );
        case "ONLY_IN_STOCK": {
          const qualifying = refs.filter((ref) =>
            "obik" in ref
              ? ref.obik === "7400002"
              : ref.productId === "kw-7400002",
          );
          return answer(
            "Na pewno na stanie w wybranym oddziale jest Miska ceramiczna B: 4 szt.",
            qualifying,
          );
        }
        case "PRODUCT_INTENT":
          return answer(
            "Polecam ten zweryfikowany wyłącznik B16 1P 6 kA.",
            refs,
          );
        case "F":
          return answer("Stan 7 szt., cena 12,49 zł.", refs);
        case "G_ZERO":
          return answer("Stan wynosi 0 szt.", refs);
        case "G_NULL":
          return answer("Stan nie jest obecnie potwierdzony.", refs);
        case "G_NOT_FOUND":
          return answer("Nie znaleziono zweryfikowanego produktu.");
        case "G_UNAVAILABLE":
          return answer("Nie udało się potwierdzić dostępności.");
        default:
          throw new Error(
            `Unexpected continuation for ${scenario.id}`,
          );
      }
    },
  };
}

const passingSemanticJudge = {
  async grade() {
    return {
      pass: true,
      reason: "semantic rubric satisfied",
    };
  },
};

test("deterministic harness passes all initial scenarios with scripted observable behavior", async () => {
  const results = await runBehaviorSuite({
    trials: 1,
    scenarioIds: BEHAVIOR_SCENARIOS.map((scenario) => scenario.id),
    driverFactory: (scenario) => scriptedDriver(scenario),
    semanticJudge: passingSemanticJudge,
  });

  assert.equal(results.length, BEHAVIOR_SCENARIOS.length);
  assert.equal(
    results.every((result) => result.status === "PASS"),
    true,
  );

  const browse = results.find(
    (result) => result.scenario === "B",
  );
  assert.ok(browse);
  assert.equal(browse.localToolCallCount, 1);
  assert.equal(browse.trace.modelOutputs.length, 2);
  assert.equal(
    browse.trace.localProductCalls[0].arguments.queries[0]
      .limit,
    3,
  );
  assert.equal(
    browse.trace.mockedToolResults.length,
    1,
  );
  assert.equal(browse.trace.finalProductRefs.length, 3);
  assert.equal(
    Object.prototype.hasOwnProperty.call(
      browse.trace,
      "reasoning",
    ),
    false,
  );
});

test("scenario A remains identical for OBI and KWANT", () => {
  const obi = behaviorScenarioForProvider("A", "obi-v2");
  const kwant = behaviorScenarioForProvider("A", "kwant-v3");

  assert.equal(obi.name, "Broad black cable-tie product intent");
  assert.equal(obi.userMessage, "Klient potrzebuje czarnych trytytek.");
  assert.deepEqual(kwant, obi);
});

test("scenario D keeps OBI plumbing content and shapes KWANT to electrical clarification", async () => {
  const obi = behaviorScenarioForProvider("D", "obi-v2");
  const kwant = behaviorScenarioForProvider("D", "kwant-v3");

  assert.equal(
    obi.userMessage,
    "Klient potrzebuje końcówki z sitkiem do kranu.",
  );
  assert.match(obi.name, /faucet|aerator/i);
  assert.equal(
    kwant.userMessage,
    "Klient potrzebuje wyłącznika nadprądowego B16. Co mu dać?",
  );
  assert.match(kwant.name, /B16 breaker/i);
  assert.notDeepEqual(kwant.semanticRubric, obi.semanticRubric);
  assert.equal(
    kwant.semanticRubric.some((line) =>
      /decision-critical|pole configuration|application/i.test(line),
    ),
    true,
  );

  const obiResult = await runBehaviorTrial(
    obi,
    1,
    scriptedDriver(obi),
    passingSemanticJudge,
    "obi-v2",
  );
  const kwantResult = await runBehaviorTrial(
    kwant,
    1,
    kwantScriptedDriver(kwant),
    passingSemanticJudge,
    "kwant-v3",
  );

  for (const result of [obiResult, kwantResult]) {
    assert.equal(result.status, "PASS");
    assert.equal(result.localToolCallCount, 0);
    assert.equal(result.trace.finalProductRefs.length, 0);
    assert.equal(
      result.trace.clarificationOrFinalAnswer.kind,
      "clarification_candidate",
    );
  }
});

test("ONLY_IN_STOCK messages are explicit and provider-shaped", () => {
  const obi = behaviorScenarioForProvider(
    "ONLY_IN_STOCK",
    "obi-v2",
  );
  const kwant = behaviorScenarioForProvider(
    "ONLY_IN_STOCK",
    "kwant-v3",
  );

  assert.equal(
    obi.userMessage,
    "Pokaż tylko te miski, które mamy na pewno na stanie.",
  );
  assert.match(kwant.userMessage, /wyłączniki B16 1P/i);
  assert.match(kwant.userMessage, /oddziale/i);
  assert.doesNotMatch(kwant.userMessage, /misk/i);
});

test("the same behavior families pass through provider v3 KWANT mocks", async () => {
  const results = await runBehaviorSuite({
    provider: "kwant-v3",
    trials: 1,
    scenarioIds: BEHAVIOR_SCENARIOS.map((scenario) => scenario.id),
    driverFactory: (scenario) => kwantScriptedDriver(scenario),
    semanticJudge: passingSemanticJudge,
  });

  assert.equal(results.length, BEHAVIOR_SCENARIOS.length);
  assert.equal(results.every((result) => result.status === "PASS"), true);
  const direct = results.find((result) => result.scenario === "F");
  assert.equal(direct.trace.provider, "kwant-v3");
  assert.deepEqual(
    direct.trace.localProductCalls[0].arguments,
    {
      providerId: "kwant-pl",
      branchId: "205",
      queries: [{ query: "MBN116E/HAG", limit: 1 }],
    },
  );
  assert.equal(direct.trace.finalProductRefs[0].providerId, "kwant-pl");
});

test("concrete product recommendation triggers provider lookup on OBI v2 and KWANT v3 without market-check phrase", async () => {
  const obiScenario = behaviorScenarioForProvider(
    "PRODUCT_INTENT",
    "obi-v2",
  );
  const kwantScenario = behaviorScenarioForProvider(
    "PRODUCT_INTENT",
    "kwant-v3",
  );

  assert.doesNotMatch(obiScenario.userMessage, /sprawdź|market|branch/i);
  assert.equal(obiScenario.userMessage, kwantScenario.userMessage);

  const obi = await runBehaviorTrial(
    obiScenario,
    1,
    scriptedDriver(obiScenario),
    passingSemanticJudge,
    "obi-v2",
  );
  const kwant = await runBehaviorTrial(
    kwantScenario,
    1,
    kwantScriptedDriver(kwantScenario),
    passingSemanticJudge,
    "kwant-v3",
  );

  assert.equal(obi.status, "PASS");
  assert.equal(kwant.status, "PASS");
  assert.equal(obi.localToolCallCount, 1);
  assert.equal(kwant.localToolCallCount, 1);

  assert.deepEqual(
    obi.trace.localProductCalls[0].arguments,
    {
      storeNumber: "075",
      queries: [
        {
          query: "wyłącznik nadprądowy B16 1P 6 kA",
          limit: 1,
        },
      ],
    },
  );
  assert.deepEqual(
    kwant.trace.localProductCalls[0].arguments,
    {
      providerId: "kwant-pl",
      branchId: "205",
      queries: [
        {
          query: "wyłącznik nadprądowy B16 1P 6 kA",
          limit: 1,
        },
      ],
    },
  );

  assert.equal(obi.trace.finalProductRefs[0].obik, "7300001");
  assert.equal(
    kwant.trace.finalProductRefs[0].providerId,
    "kwant-pl",
  );
  assert.equal(
    kwant.trace.finalProductRefs[0].branchId,
    "205",
  );
  assert.equal(
    kwant.trace.finalProductRefs[0].productId,
    "kw-7300001",
  );
});

test("KWANT provider-neutral rubrics contain no OBI-specific wording outside provider-shaped identifier scenarios", () => {
  for (const id of ["H", "H_AMBIGUOUS", "I", "PRODUCT_INTENT"]) {
    const scenario = behaviorScenarioForProvider(id, "kwant-v3");
    assert.equal(
      scenario.semanticRubric.some((line) => /\bOBI\b|OBIK/i.test(line)),
      false,
      `${id} rubric must stay provider-neutral`,
    );
  }

  assert.equal(BEHAVIOR_SCENARIOS.length, 13);
  assert.equal(BEHAVIOR_REGRESSION_SCENARIOS.length, 17);
});

test("OBI branch scenarios share the same exact unambiguous product identifier", () => {
  const natural = behaviorScenario("OBI_NATURAL_BRANCH");
  const ambiguous = behaviorScenario("OBI_AMBIGUOUS_BRANCH");

  assert.equal(
    natural.userMessage,
    "Sprawdź OBIK 7000001 w OBI Wielicka.",
  );
  assert.equal(
    ambiguous.userMessage,
    "Sprawdź OBIK 7000001 w OBI Kraków.",
  );
  for (const scenario of [natural, ambiguous]) {
    assert.match(scenario.userMessage, /OBIK 7000001/i);
    assert.doesNotMatch(scenario.userMessage, /\bmiski\b/i);
  }
});

test("natural OBI branch uses conversation store in the model call and simulates Android rewrite to 003", async () => {
  const scenario = behaviorScenario("OBI_NATURAL_BRANCH");
  const result = await runBehaviorTrial(
    scenario,
    1,
    scriptedDriver(scenario),
    passingSemanticJudge,
    "obi-v2",
  );

  assert.equal(result.status, "PASS");
  assert.equal(result.localToolCallCount, 1);
  assert.equal(
    result.trace.localProductCalls[0].arguments.storeNumber,
    "075",
  );
  assert.deepEqual(
    result.trace.localProductCalls[0].arguments.queries,
    [{ query: "OBIK 7000001", limit: 1 }],
  );
  assert.equal(
    result.trace.mockedToolResults[0].storeNumber,
    "003",
  );
  assert.equal(
    result.trace.mockedToolResults[0].results[0].products[0].obik,
    "7000001",
  );
  assert.deepEqual(result.trace.finalProductRefs, [
    { storeNumber: "003", obik: "7000001" },
  ]);
  assert.equal(
    /3[- ]?cyfrow|numer marketu/i.test(
      result.trace.clarificationOrFinalAnswer.text,
    ),
    false,
  );
});

test("ambiguous OBI city fails closed and asks for a more precise location", async () => {
  const scenario = behaviorScenario("OBI_AMBIGUOUS_BRANCH");
  const result = await runBehaviorTrial(
    scenario,
    1,
    scriptedDriver(scenario),
    passingSemanticJudge,
    "obi-v2",
  );

  assert.equal(result.status, "PASS");
  assert.equal(result.localToolCallCount, 1);
  assert.equal(
    result.trace.localProductCalls[0].arguments.storeNumber,
    "075",
  );
  assert.deepEqual(
    result.trace.localProductCalls[0].arguments.queries,
    [{ query: "OBIK 7000001", limit: 1 }],
  );
  assert.equal(
    result.trace.mockedToolResults[0].storeNumber,
    "075",
  );
  assert.equal(
    result.trace.mockedToolResults[0].rejection,
    "store_not_authorized",
  );
  assert.equal(result.trace.finalProductRefs.length, 0);
  assert.equal(
    result.trace.finalProductRefs.some((ref) =>
      "storeNumber" in ref && ref.storeNumber !== "075"
    ),
    false,
  );
  assert.match(
    result.trace.clarificationOrFinalAnswer.text,
    /ulic|lokaliz|market/i,
  );
  assert.equal(
    scenario.semanticRubric.some((line) =>
      /075.*Nowy Sącz|Nowy Sącz.*075/i.test(line)
    ),
    true,
  );
});

test("only-in-stock fixtures qualify only positive selected-branch stock", async () => {
  for (const provider of ["obi-v2", "kwant-v3"]) {
    const scenario = behaviorScenarioForProvider(
      "ONLY_IN_STOCK",
      provider,
    );
    const driver =
      provider === "obi-v2"
        ? scriptedDriver(scenario)
        : kwantScriptedDriver(scenario);
    const result = await runBehaviorTrial(
      scenario,
      1,
      driver,
      passingSemanticJudge,
      provider,
    );

    assert.equal(result.status, "PASS");
    assert.equal(result.finalProductRefs, undefined);
    assert.equal(result.trace.finalProductRefs.length, 1);
    const ref = result.trace.finalProductRefs[0];
    assert.equal(
      "obik" in ref ? ref.obik : ref.productId,
      provider === "obi-v2" ? "7400002" : "kw-7400002",
    );

    const products =
      result.trace.mockedToolResults[0].results[0].products;
    assert.deepEqual(
      products.map((product) => product.stock),
      [0, 4, null],
    );

    if (provider === "kwant-v3") {
      assert.equal(
        result.trace.localProductCalls[0].arguments.queries[0].query,
        "wyłączniki nadprądowe B16 1P",
      );
      assert.equal(
        products.every((product) =>
          /wyłącznik/i.test(product.name)
        ),
        true,
      );
      assert.equal(products[0].centralStock, 20);
      assert.equal(products[2].centralStock, 20);
      assert.equal(products[1].stock > 0, true);
    }
  }
});

test("KWANT central stock alone cannot qualify ONLY_IN_STOCK", async () => {
  const scenario = behaviorScenarioForProvider(
    "ONLY_IN_STOCK",
    "kwant-v3",
  );
  const base = kwantScriptedDriver(scenario);
  const result = await runBehaviorTrial(
    scenario,
    1,
    {
      start: (...args) => base.start(...args),
      async continueTurn(
        _responseId,
        _callId,
        _branchId,
        mockedResult,
      ) {
        const zeroStock = verifiedRefs(mockedResult).find(
          (ref) =>
            "productId" in ref &&
            ref.productId === "kw-7400001",
        );
        return answer(
          "Jest dostępny dzięki stanowi centralnemu.",
          zeroStock ? [zeroStock] : [],
        );
      },
    },
    passingSemanticJudge,
    "kwant-v3",
  );

  assert.equal(result.status, "FAIL");
  assert.match(
    result.reason,
    /zero\/null selected-branch stock/i,
  );
  const products =
    result.trace.mockedToolResults[0].results[0].products;
  assert.equal(products[0].stock, 0);
  assert.equal(products[0].centralStock, 20);
});

test("repeated trials stay configurable and summary reports pass totals", async () => {
  const results = await runBehaviorSuite({
    trials: 2,
    scenarioIds: ["A", "C"],
    driverFactory: (scenario) => scriptedDriver(scenario),
    semanticJudge: passingSemanticJudge,
  });

  assert.equal(results.length, 4);
  assert.deepEqual(
    results.map((result) => [
      result.scenario,
      result.trial,
    ]),
    [
      ["A", 1],
      ["A", 2],
      ["C", 1],
      ["C", 2],
    ],
  );

  const summary = formatBehaviorSummary(results);
  assert.match(summary, /passed: 4 \/ 4/);
  assert.match(summary, /failed scenarios: none/);
});

test("decision-critical D variants fail on provider lookup before clarification", async () => {
  const cases = [
    {
      provider: "obi-v2",
      scenario: behaviorScenarioForProvider("D", "obi-v2"),
      query: "końcówka do kranu",
    },
    {
      provider: "kwant-v3",
      scenario: behaviorScenarioForProvider("D", "kwant-v3"),
      query: "wyłącznik nadprądowy B16",
    },
  ];

  for (const { provider, scenario, query } of cases) {
    const result = await runBehaviorTrial(
      scenario,
      1,
      {
        async start() {
          return provider === "obi-v2"
            ? toolRequest([{ query, limit: 3 }])
            : {
                type: "tool_request",
                responseId: "resp_tool",
                tool: {
                  name: "find_products",
                  callId: "call_tool",
                  arguments: {
                    providerId: "kwant-pl",
                    branchId: "205",
                    requestedBranch: null,
                    queries: [{ query, limit: 3 }],
                  },
                },
                webSearchCalls: 0,
              };
        },
        async continueTurn() {
          return answer("Jaki dokładnie wariant jest potrzebny?");
        },
      },
      passingSemanticJudge,
      provider,
    );

    assert.equal(result.status, "FAIL");
    assert.match(
      result.reason,
      /local provider lookup occurred before clarification/i,
    );
  }
});

test("scenario A browses multiple black cable-tie variants without mandatory clarification", async () => {
  const scenario = behaviorScenario("A");
  const result = await runBehaviorTrial(
    scenario,
    1,
    scriptedDriver(scenario),
    passingSemanticJudge,
  );

  assert.equal(result.status, "PASS");
  assert.equal(result.localToolCallCount, 1);
  assert.equal(
    result.trace.localProductCalls[0].arguments.queries[0].limit,
    3,
  );
  const group = result.trace.mockedToolResults[0].results[0];
  assert.equal(group.status, "verified");
  assert.equal(group.products.length, 3);
  assert.equal(result.trace.finalProductRefs.length, 3);
});

test("scenario A rejects a clarification-only response that hides useful variants", async () => {
  const scenario = behaviorScenario("A");
  const result = await runBehaviorTrial(
    scenario,
    1,
    {
      async start() {
        return answer(
          "Jakiej długości potrzebuje i do czego będą używane?",
        );
      },
      async continueTurn() {
        throw new Error("unexpected continuation");
      },
    },
    passingSemanticJudge,
  );

  assert.equal(result.status, "FAIL");
  assert.match(
    result.reason,
    /broad concrete product request did not use the local provider/i,
  );
});

test("scenario A rejects arbitrary single-result narrowing", async () => {
  const scenario = behaviorScenario("A");
  const result = await runBehaviorTrial(
    scenario,
    1,
    {
      async start() {
        return toolRequest([
          { query: "czarne trytytki", limit: 1 },
        ]);
      },
      async continueTurn(
        _responseId,
        _callId,
        _storeNumber,
        mockedResult,
      ) {
        return answer(
          "Znalazłam jeden wariant.",
          verifiedRefs(mockedResult),
        );
      },
    },
    passingSemanticJudge,
  );

  assert.equal(result.status, "FAIL");
  assert.match(
    result.reason,
    /multiple variants|multiple relevant verified variants/i,
  );
});

test("browse exhaustive wording is rejected by the behavioral semantic rubric", async () => {
  const scenario = behaviorScenario("B");
  let observedRubric = [];
  const result = await runBehaviorTrial(
    scenario,
    1,
    {
      async start() {
        return toolRequest([
          { query: "czarne trytytki", limit: 3 },
        ]);
      },
      async continueTurn(
        _responseId,
        _callId,
        _storeNumber,
        mockedResult,
      ) {
        return answer(
          "Mamy trzy warianty czarnych trytytek.",
          verifiedRefs(mockedResult),
        );
      },
    },
    {
      async grade({ scenario: gradedScenario, trace }) {
        observedRubric = gradedScenario.semanticRubric;
        const exhaustive =
          /mamy trzy warianty/i.test(
            trace.clarificationOrFinalAnswer?.text ?? "",
          );
        return {
          pass: !exhaustive,
          reason: exhaustive
            ? "bounded subset was presented as the complete assortment"
            : "non-exhaustive wording",
        };
      },
    },
  );

  assert.equal(
    observedRubric.some((line) =>
      /does not imply.*complete assortment/i.test(line),
    ),
    true,
  );
  assert.equal(result.status, "FAIL");
  assert.match(result.reason, /complete assortment/i);
});

test("scenario C accepts a semantic exact-size cable-tie query without literal indoor wording", async () => {
  const scenario = behaviorScenario("C");
  const result = await runBehaviorTrial(
    scenario,
    1,
    {
      async start() {
        return toolRequest([
          {
            query:
              "czarne opaski zaciskowe trytytki 4.2 x 380 mm",
            limit: 1,
          },
        ]);
      },
      async continueTurn(
        _responseId,
        _callId,
        _storeNumber,
        mockedResult,
      ) {
        return answer(
          "Pasuje zweryfikowany wariant do zastosowania wewnątrz.",
          verifiedRefs(mockedResult),
        );
      },
    },
    passingSemanticJudge,
  );

  assert.equal(result.status, "PASS");
  const group =
    result.trace.mockedToolResults[0].results[0];
  assert.equal(group.status, "verified");
  assert.equal(group.products.length, 1);
  assert.equal(result.trace.finalProductRefs.length, 1);
  assert.equal(
    group.products[0].technicalFacts.some(
      (fact) =>
        fact.label === "Zastosowanie" &&
        fact.value === "wewnątrz",
    ),
    true,
  );
});

test("scenario H accepts useful natural job advice without required essential/optional labels", async () => {
  const scenario = behaviorScenario("H");
  let observedRubric = [];
  const result = await runBehaviorTrial(
    scenario,
    1,
    scriptedDriver(scenario),
    {
      async grade({ scenario: gradedScenario, trace }) {
        observedRubric = gradedScenario.semanticRubric;
        const text =
          trace.clarificationOrFinalAnswer?.text ?? "";
        const usefulAdvice =
          /silikon/i.test(text) &&
          /oczyść|odtłuść|odtlusc/i.test(text);
        return {
          pass: usefulAdvice,
          reason: usefulAdvice
            ? "useful practical sealing advice"
            : "advice was not useful for the understood job",
        };
      },
    },
  );

  assert.equal(result.status, "PASS");
  assert.equal(result.localToolCallCount, 0);
  assert.equal(result.trace.finalProductRefs.length, 0);
  assert.equal(
    observedRubric.some((line) =>
      /no exact item list, labels, or answer structure is required/i.test(line),
    ),
    true,
  );
  assert.equal(
    observedRubric.some((line) =>
      /distinguishes essential needs from optional convenience items/i.test(line),
    ),
    false,
  );
});

test("scenario H fails if advice is automatically converted into a verified shopping kit", async () => {
  const scenario = behaviorScenario("H");
  let semanticCalls = 0;
  const result = await runBehaviorTrial(
    scenario,
    1,
    {
      async start() {
        return toolRequest([
          { query: "silikon sanitarny do umywalki", limit: 1 },
          { query: "pistolet do silikonu", limit: 1 },
          {
            query: "narzędzie do wygładzania silikonu",
            limit: 1,
          },
        ]);
      },
      async continueTurn(
        _responseId,
        _callId,
        _storeNumber,
        mockedResult,
      ) {
        const verifiedGroups = mockedResult.results.filter(
          (group) => group.status === "verified",
        );
        assert.equal(verifiedGroups.length, 3);
        return answer(
          "Gotowy zestaw ze sklepu: silikon, wyciskacz i wygładzacz.",
          verifiedRefs(mockedResult),
        );
      },
    },
    {
      async grade() {
        semanticCalls += 1;
        return { pass: true, reason: "should not run" };
      },
    },
  );

  assert.equal(result.status, "FAIL");
  assert.match(
    result.reason,
    /automatic local provider lookup without explicit provider intent/i,
  );
  assert.equal(result.localToolCallCount, 1);
  assert.equal(result.trace.finalProductRefs.length, 3);
  assert.equal(semanticCalls, 0);
});

test("H_AMBIGUOUS accepts a concise clarification before local lookup", async () => {
  const scenario = behaviorScenario("H_AMBIGUOUS");
  const result = await runBehaviorTrial(
    scenario,
    1,
    scriptedDriver(scenario),
    passingSemanticJudge,
  );

  assert.equal(result.status, "PASS");
  assert.equal(result.localToolCallCount, 0);
  assert.equal(result.trace.finalProductRefs.length, 0);
  assert.equal(
    result.trace.clarificationOrFinalAnswer?.kind,
    "clarification_candidate",
  );
  assert.match(
    result.trace.clarificationOrFinalAnswer?.text ?? "",
    /ścian|blat|odpływ|syfon/i,
  );
});

test("H_AMBIGUOUS accepts a clearly conditional answer that keeps materially different interpretations explicit", async () => {
  const scenario = behaviorScenario("H_AMBIGUOUS");
  let observedRubric = [];
  const result = await runBehaviorTrial(
    scenario,
    1,
    {
      async start() {
        return answer(
          "Jeśli chodzi o szczelinę między umywalką a ścianą lub blatem, stosuje się silikon sanitarny po oczyszczeniu podłoża. Jeśli natomiast cieknie przy odpływie albo syfonie, to jest inny problem i trzeba najpierw ustalić miejsce przecieku.",
        );
      },
      async continueTurn() {
        throw new Error("unexpected continuation");
      },
    },
    {
      async grade({ scenario: gradedScenario, trace }) {
        observedRubric = gradedScenario.semanticRubric;
        const text =
          trace.clarificationOrFinalAnswer?.text ?? "";
        const conditional =
          /jeśli|jesli/i.test(text) &&
          /ścian|blat/i.test(text) &&
          /odpływ|odplyw|syfon/i.test(text) &&
          /inny problem/i.test(text);
        return {
          pass: conditional,
          reason: conditional
            ? "conditional answer keeps both job interpretations explicit"
            : "answer silently assumed one interpretation",
        };
      },
    },
  );

  assert.equal(result.status, "PASS");
  assert.equal(result.localToolCallCount, 0);
  assert.equal(result.trace.finalProductRefs.length, 0);
  assert.equal(
    observedRubric.some((line) =>
      /may either ask a concise clarification.*or give a clearly conditional answer/i.test(line),
    ),
    true,
  );
});

test("H_AMBIGUOUS rejects silently assuming one materially different sealing problem", async () => {
  const scenario = behaviorScenario("H_AMBIGUOUS");
  const result = await runBehaviorTrial(
    scenario,
    1,
    {
      async start() {
        return answer(
          "Potrzebujesz silikonu sanitarnego do szczeliny między umywalką a ścianą.",
        );
      },
      async continueTurn() {
        throw new Error("unexpected continuation");
      },
    },
    {
      async grade({ trace }) {
        const text =
          trace.clarificationOrFinalAnswer?.text ?? "";
        const acknowledgesAmbiguity =
          /jeśli|jesli|czy chodzi|odpływ|odplyw|syfon|inny problem/i.test(
            text,
          );
        return {
          pass: acknowledgesAmbiguity,
          reason: acknowledgesAmbiguity
            ? "material ambiguity is explicit"
            : "answer silently assumed one materially different interpretation",
        };
      },
    },
  );

  assert.equal(result.status, "FAIL");
  assert.equal(result.localToolCallCount, 0);
  assert.equal(result.trace.finalProductRefs.length, 0);
  assert.match(
    result.reason,
    /silently assumed one materially different interpretation/i,
  );
});

test("availability semantics allow a production-style substitute lookup after the exact item", async () => {
  const scenario = behaviorScenario("G_ZERO");
  let continuation = 0;
  const driver = {
    async start() {
      return toolRequest([
        { query: "OBIK 7000002", limit: 1 },
      ]);
    },
    async continueTurn() {
      continuation += 1;
      if (continuation === 1) {
        return toolRequest([
          { query: "zamiennik produktu 7000002", limit: 1 },
        ]);
      }
      return answer("Stan produktu bazowego wynosi 0 szt.");
    },
  };

  const result = await runBehaviorTrial(
    scenario,
    1,
    driver,
    passingSemanticJudge,
  );

  assert.equal(result.status, "PASS");
  assert.equal(result.localToolCallCount, 2);
});

test("all initial scenarios explicitly forbid web search", () => {
  assert.equal(
    BEHAVIOR_SCENARIOS.every(
      (scenario) => scenario.webPolicy === "forbidden",
    ),
    true,
  );
});

test("forbidden web search fails deterministically without hiding the production web tool", async () => {
  let semanticCalls = 0;
  const scenario = behaviorScenario("I");
  const result = await runBehaviorTrial(
    scenario,
    1,
    {
      async start() {
        return answer(
          "SDS+ i SDS Max różnią się zastosowaniem i rozmiarem osprzętu.",
          [],
          1,
        );
      },
      async continueTurn() {
        throw new Error("unexpected continuation");
      },
    },
    {
      async grade() {
        semanticCalls += 1;
        return { pass: true, reason: "should not run" };
      },
    },
  );

  assert.equal(result.status, "FAIL");
  assert.match(result.reason, /scenario forbids it/i);
  assert.equal(result.webSearchCount, 1);
  assert.equal(semanticCalls, 0);
});

test("unrelated browse queries cannot receive B or E cable-tie fixtures or pass", async () => {
  for (const id of ["B", "E"]) {
    const scenario = behaviorScenario(id);
    const result = await runBehaviorTrial(
      scenario,
      1,
      {
        async start() {
          return toolRequest([
            { query: "młotki murarskie", limit: 3 },
          ]);
        },
        async continueTurn(
          _responseId,
          _callId,
          _storeNumber,
          mockedResult,
        ) {
          return answer(
            "Znalazłam kilka wariantów.",
            verifiedRefs(mockedResult),
          );
        },
      },
      passingSemanticJudge,
    );

    assert.equal(result.status, "FAIL");
    const group =
      result.trace.mockedToolResults[0].results[0];
    assert.equal(group.status, "not_found");
    assert.equal(group.products.length, 0);
  }
});

test("scenario E exposes three distinct indoor-compatible cable-tie variants", async () => {
  const scenario = behaviorScenario("E");
  const result = await runBehaviorTrial(
    scenario,
    1,
    scriptedDriver(scenario),
    passingSemanticJudge,
  );

  assert.equal(result.status, "PASS");
  const group =
    result.trace.mockedToolResults[0].results[0];
  assert.equal(group.status, "verified");
  assert.equal(group.products.length, 3);

  const dimensions = new Set();
  for (const product of group.products) {
    const application = product.technicalFacts.find(
      (fact) => fact.label === "Zastosowanie",
    );
    const dimension = product.technicalFacts.find(
      (fact) => fact.label === "Wymiary",
    );
    assert.equal(application?.value, "wewnątrz");
    assert.ok(dimension?.value);
    dimensions.add(dimension.value);
  }
  assert.equal(dimensions.size, 3);
});

test("scenario E maps size-specific queries to distinct indoor variants", async () => {
  const scenario = behaviorScenario("E");
  const result = await runBehaviorTrial(
    scenario,
    1,
    {
      async start() {
        return toolRequest([
          { query: "czarne trytytki 2,5 x 100 mm", limit: 1 },
          { query: "czarne trytytki 3,6 x 200 mm", limit: 1 },
          { query: "czarne trytytki 4,8 x 300 mm", limit: 1 },
        ]);
      },
      async continueTurn(
        _responseId,
        _callId,
        _storeNumber,
        mockedResult,
      ) {
        return answer(
          "Mam trzy zweryfikowane warianty czarnych trytytek do środka.",
          verifiedRefs(mockedResult),
        );
      },
    },
    passingSemanticJudge,
  );

  assert.equal(result.status, "PASS");
  assert.equal(result.localToolCallCount, 1);
  const groups = result.trace.mockedToolResults[0].results;
  assert.deepEqual(
    groups.map((group) => group.products.length),
    [1, 1, 1],
  );
  assert.deepEqual(
    groups.map((group) =>
      group.products[0].technicalFacts.find(
        (fact) => fact.label === "Wymiary",
      )?.value
    ),
    [
      "2,5 x 100 mm",
      "3,6 x 200 mm",
      "4,8 x 300 mm",
    ],
  );
  assert.equal(result.trace.finalProductRefs.length, 3);
  assert.equal(
    new Set(
      result.trace.finalProductRefs.map((ref) =>
        "obik" in ref ? ref.obik : ref.productId
      ),
    ).size,
    3,
  );
});

test("scenario E accepts a relevant black-cable-tie query without repeating the indoor constraint", async () => {
  const scenario = behaviorScenario("E");
  let observedRubric = [];
  const result = await runBehaviorTrial(
    scenario,
    1,
    {
      async start() {
        return toolRequest([
          { query: "czarne trytytki", limit: 3 },
        ]);
      },
      async continueTurn(
        _responseId,
        _callId,
        _storeNumber,
        mockedResult,
      ) {
        const group = mockedResult.results[0];
        assert.equal(group.status, "verified");
        return answer(
          "Znalazłam m.in. kilka czarnych wariantów do zastosowania wewnątrz, różniących się wymiarami.",
          verifiedRefs(mockedResult),
        );
      },
    },
    {
      async grade({ scenario: gradedScenario, trace }) {
        observedRubric = gradedScenario.semanticRubric;
        const text =
          trace.clarificationOrFinalAnswer?.text ?? "";
        const nonExhaustive =
          /m\.in\.|między innymi|wsrod|wśród/i.test(text) &&
          !/to wszystkie|pełna lista|pelna lista/i.test(text);
        return {
          pass: nonExhaustive,
          reason: nonExhaustive
            ? "useful variants are presented without false completeness"
            : "bounded results were presented as complete",
        };
      },
    },
  );

  assert.equal(result.status, "PASS");
  assert.equal(result.localToolCallCount, 1);
  assert.equal(
    /wewn|środ|srod/i.test(
      result.trace.localProductCalls[0].arguments.queries[0]
        .query,
    ),
    false,
  );
  const group =
    result.trace.mockedToolResults[0].results[0];
  assert.equal(group.products.length, 3);
  assert.equal(
    group.products.every((product) =>
      product.technicalFacts.some(
        (fact) =>
          fact.label === "Zastosowanie" &&
          fact.value === "wewnątrz",
      ),
    ),
    true,
  );
  assert.equal(
    observedRubric.some((line) =>
      /does not falsely present.*complete assortment/i.test(line),
    ),
    true,
  );
  assert.equal(
    observedRubric.some((line) =>
      /not whether the tool query repeats every constraint/i.test(line),
    ),
    true,
  );
});

test("scenario E size-specific fixture matching is provider-neutral for KWANT", async () => {
  const scenario = behaviorScenarioForProvider("E", "kwant-v3");
  const base = {
    async start() {
      return {
        type: "tool_request",
        responseId: "resp_tool",
        tool: {
          name: "find_products",
          callId: "call_tool",
          arguments: {
            providerId: "kwant-pl",
            branchId: "205",
            requestedBranch: null,
            queries: [
              { query: "czarne trytytki 100 mm", limit: 1 },
              { query: "czarne trytytki 200 mm", limit: 1 },
              { query: "czarne trytytki 300 mm", limit: 1 },
            ],
          },
        },
        webSearchCalls: 0,
      };
    },
    async continueTurn(
      _responseId,
      _callId,
      _branchId,
      mockedResult,
    ) {
      return answer(
        "Mam trzy zweryfikowane warianty czarnych trytytek do środka.",
        verifiedRefs(mockedResult),
      );
    },
  };

  const result = await runBehaviorTrial(
    scenario,
    1,
    base,
    passingSemanticJudge,
    "kwant-v3",
  );

  assert.equal(result.status, "PASS");
  const groups = result.trace.mockedToolResults[0].results;
  assert.deepEqual(
    groups.map((group) => group.products[0].productId),
    ["kw-6110001", "kw-6110002", "kw-6110003"],
  );
  assert.equal(result.trace.finalProductRefs.length, 3);
});

test("explicit browse and direct current-store fact scenarios still require local OBI", async () => {
  for (const id of ["B", "F"]) {
    const scenario = behaviorScenario(id);
    const result = await runBehaviorTrial(
      scenario,
      1,
      {
        async start() {
          return answer(
            id === "B"
              ? "Mogę opisać ogólne rodzaje trytytek."
              : "Nie sprawdzam teraz stanu ani ceny.",
          );
        },
        async continueTurn() {
          throw new Error("unexpected continuation");
        },
      },
      passingSemanticJudge,
    );

    assert.equal(result.status, "FAIL");
    assert.match(
      result.reason,
      id === "B"
        ? /browse request did not use the local provider/i
        : /direct stock\/price request should use one local lookup/i,
    );
  }
});

test("scenario C rejects 42 x 380 as a substitute for the 4.2 x 380 dimension", async () => {
  const scenario = behaviorScenario("C");
  const result = await runBehaviorTrial(
    scenario,
    1,
    {
      async start() {
        return toolRequest([
          {
            query:
              "czarne trytytki 42 x 380 mm do wewnątrz",
            limit: 1,
          },
        ]);
      },
      async continueTurn(
        _responseId,
        _callId,
        _storeNumber,
        mockedResult,
      ) {
        return answer(
          "Pasuje zweryfikowany wariant.",
          verifiedRefs(mockedResult),
        );
      },
    },
    passingSemanticJudge,
  );

  assert.equal(result.status, "FAIL");
  assert.equal(
    result.trace.mockedToolResults[0].results[0].status,
    "not_found",
  );
  assert.match(result.reason, /4\.2 x 380 mm/i);
});

test("wrong direct OBIK queries do not receive F or G fixtures and fail", async () => {
  for (const id of [
    "F",
    "G_ZERO",
    "G_NULL",
    "G_NOT_FOUND",
    "G_UNAVAILABLE",
  ]) {
    const scenario = behaviorScenario(id);
    const result = await runBehaviorTrial(
      scenario,
      1,
      {
        async start() {
          return toolRequest([
            { query: "OBIK 7999999", limit: 1 },
          ]);
        },
        async continueTurn() {
          return answer("Synthetic availability answer.");
        },
      },
      passingSemanticJudge,
    );

    assert.equal(result.status, "FAIL");
    assert.equal(
      result.trace.mockedToolResults[0].results[0].status,
      "not_found",
    );
  }
});

test("productRef grounding fails deterministically before semantic grading", async () => {
  let semanticCalls = 0;
  const judge = {
    async grade() {
      semanticCalls += 1;
      return { pass: true, reason: "should not run" };
    },
  };

  const scenario = behaviorScenario("F");
  const driver = {
    async start() {
      return toolRequest([
        { query: "OBIK 7000001", limit: 1 },
      ]);
    },
    async continueTurn() {
      return answer("Synthetic", [
        { storeNumber: "075", obik: "7999999" },
      ]);
    },
  };

  const result = await runBehaviorTrial(
    scenario,
    1,
    driver,
    judge,
  );

  assert.equal(result.status, "FAIL");
  assert.match(result.reason, /ungrounded productRef/i);
  assert.equal(semanticCalls, 0);
});

test("valid semantic rejection is a behavioral FAIL", async () => {
  const scenario = behaviorScenario("A");
  const result = await runBehaviorTrial(
    scenario,
    1,
    scriptedDriver(scenario),
    {
      async grade() {
        return {
          pass: false,
          reason: "clarification was not relevant enough",
        };
      },
    },
  );

  assert.equal(result.status, "FAIL");
  assert.equal(
    result.reason,
    "clarification was not relevant enough",
  );
});

test("production eval driver reuses production advisor request configuration", async () => {
  const captures = [];
  const fakeFetch = async (_input, init) => {
    captures.push(JSON.parse(init.body));
    const structured = JSON.stringify({
      text: "Synthetic answer",
      productRefs: [],
    });
    return new Response(
      JSON.stringify({
        id: `resp_${captures.length}`,
        output_text: structured,
        output: [
          {
            type: "message",
            content: [
              { type: "output_text", text: structured },
            ],
          },
        ],
      }),
      {
        status: 200,
        headers: { "Content-Type": "application/json" },
      },
    );
  };

  const driver = createProductionAdvisorDriver(
    "test-key",
    fakeFetch,
  );
  await driver.start("Test", "075");
  await driver.continueTurn(
    "resp_tool",
    "call_tool",
    "075",
    {
      storeNumber: "075",
      results: [
        {
          query: "test",
          status: "not_found",
          products: [],
        },
      ],
    },
  );

  assert.equal(captures.length, 2);
  for (const body of captures) {
    assert.equal(body.model, OPENAI_MODEL);
    assert.deepEqual(body.reasoning, {
      effort: OPENAI_REASONING_EFFORT,
    });
    assert.equal(
      body.max_output_tokens,
      OPENAI_MAX_OUTPUT_TOKENS,
    );
    assert.equal(
      body.instructions,
      agentInstructionsForStore(
        "075",
        OBI_GROUPED_ADVISOR_PROTOCOL_VERSION,
      ),
    );
    assert.deepEqual(body.text, {
      format: FINAL_ANSWER_FORMAT,
    });
    assert.deepEqual(body.tools, [
      obiToolForProtocol(
        OBI_GROUPED_ADVISOR_PROTOCOL_VERSION,
      ),
      WEB_SEARCH_TOOL,
    ]);
    assert.equal(body.tool_choice, "auto");
    assert.equal(body.parallel_tool_calls, false);
  }

  assert.equal(captures[1].previous_response_id, "resp_tool");
  assert.equal(
    captures[1].input[0].type,
    "function_call_output",
  );
});

test("production eval driver uses protocol v3 for KWANT start and continue", async () => {
  const captures = [];
  const fakeFetch = async (_input, init) => {
    captures.push(JSON.parse(init.body));
    const output = JSON.stringify({ text: "OK", productRefs: [] });
    return new Response(JSON.stringify({
      id: `resp_kwant_eval_${captures.length}`,
      output_text: output,
      output: [{
        type: "message",
        content: [{ type: "output_text", text: output }],
      }],
    }), { status: 200 });
  };

  const driver = createProductionAdvisorDriver(
    "test-key",
    fakeFetch,
    "kwant-v3",
  );
  await driver.start("Test", "205");
  await driver.continueTurn(
    "resp_tool",
    "call_tool",
    "205",
    {
      providerId: "kwant-pl",
      branchId: "205",
      results: [{
        query: "test",
        status: "not_found",
        products: [],
      }],
    },
  );

  assert.equal(captures.length, 2);
  for (const body of captures) {
    assert.equal(
      body.instructions,
      agentInstructionsForProfile(
        "kwant-pl",
        "205",
        PROVIDER_ADVISOR_PROTOCOL_VERSION,
      ),
    );
    assert.match(body.instructions, /providerId=kwant-pl/);
    assert.match(body.instructions, /branchId=205/);
    assert.doesNotMatch(
      body.instructions,
      /current USER turn may include one image or PDF/i,
    );
    assert.deepEqual(body.text, {
      format: PROVIDER_FINAL_ANSWER_FORMAT,
    });
    assert.deepEqual(body.tools, [
      localToolForProtocol(
        PROVIDER_ADVISOR_PROTOCOL_VERSION,
      ),
      WEB_SEARCH_TOOL,
    ]);
    assert.equal(body.tools[0].name, "find_products");
  }

  assert.equal(captures[1].previous_response_id, "resp_tool");
  assert.equal(
    captures[1].input[0].type,
    "function_call_output",
  );
});

test("semantic grader uses the normal eval output budget and accepts a valid grade", async () => {
  const captures = [];
  const fakeFetch = async (_input, init) => {
    captures.push(JSON.parse(init.body));
    return new Response(
      JSON.stringify({
        id: "resp_grade",
        output_text: JSON.stringify({
          pass: true,
          reason: "observable behavior satisfies the rubric",
        }),
        output: [],
      }),
      {
        status: 200,
        headers: { "Content-Type": "application/json" },
      },
    );
  };

  const judge = createOpenAISemanticJudge(
    "test-key",
    fakeFetch,
  );
  const scenario = behaviorScenario("A");
  const result = await runBehaviorTrial(
    scenario,
    1,
    scriptedDriver(scenario),
    judge,
  );

  assert.equal(result.status, "PASS");
  assert.equal(
    result.reason,
    "observable behavior satisfies the rubric",
  );
  assert.equal(captures.length, 1);
  assert.equal(
    captures[0].max_output_tokens,
    OPENAI_MAX_OUTPUT_TOKENS,
  );
  assert.equal(captures[0].model, OPENAI_MODEL);
  assert.deepEqual(captures[0].reasoning, {
    effort: OPENAI_REASONING_EFFORT,
  });
  assert.equal(
    captures[0].text.format.type,
    "json_schema",
  );
  assert.equal(captures[0].text.format.strict, true);
  assert.deepEqual(
    captures[0].text.format.schema.required,
    ["pass", "reason"],
  );
  assert.equal(
    Object.prototype.hasOwnProperty.call(
      captures[0],
      "tools",
    ),
    false,
  );
});

test("valid semantic rejection returns FAIL without retry", async () => {
  let graderRequests = 0;
  const fakeFetch = async () => {
    graderRequests += 1;
    return new Response(
      JSON.stringify({
        id: "resp_grade_reject",
        output_text: JSON.stringify({
          pass: false,
          reason: "clarification is not decision-critical",
        }),
        output: [],
      }),
      {
        status: 200,
        headers: { "Content-Type": "application/json" },
      },
    );
  };

  const scenario = behaviorScenario("A");
  const judge = createOpenAISemanticJudge(
    "test-key",
    fakeFetch,
  );
  const result = await runBehaviorTrial(
    scenario,
    1,
    scriptedDriver(scenario),
    judge,
  );

  assert.equal(result.status, "FAIL");
  assert.equal(
    result.reason,
    "clarification is not decision-critical",
  );
  assert.equal(graderRequests, 1);
});

test("malformed semantic grader output retries once and accepts the second valid grade", async () => {
  let graderRequests = 0;
  const fakeFetch = async () => {
    graderRequests += 1;
    const outputText =
      graderRequests === 1
        ? '{"pass":true,"reason":'
        : JSON.stringify({
            pass: true,
            reason: "second grader attempt is valid",
          });

    return new Response(
      JSON.stringify({
        id: `resp_grade_${graderRequests}`,
        output_text: outputText,
        output: [],
      }),
      {
        status: 200,
        headers: { "Content-Type": "application/json" },
      },
    );
  };

  const scenario = behaviorScenario("A");
  const judge = createOpenAISemanticJudge(
    "test-key",
    fakeFetch,
  );
  const result = await runBehaviorTrial(
    scenario,
    1,
    scriptedDriver(scenario),
    judge,
  );

  assert.equal(result.status, "PASS");
  assert.equal(result.reason, "second grader attempt is valid");
  assert.equal(graderRequests, 2);
});

test("max-output incomplete semantic grader response retries once", async () => {
  let graderRequests = 0;
  const fakeFetch = async () => {
    graderRequests += 1;
    if (graderRequests === 1) {
      return new Response(
        JSON.stringify({
          id: "resp_grade_incomplete",
          status: "incomplete",
          incomplete_details: {
            reason: "max_output_tokens",
          },
          output: [],
        }),
        {
          status: 200,
          headers: { "Content-Type": "application/json" },
        },
      );
    }

    return new Response(
      JSON.stringify({
        id: "resp_grade_valid",
        output_text: JSON.stringify({
          pass: true,
          reason: "retry recovered the grade",
        }),
        output: [],
      }),
      {
        status: 200,
        headers: { "Content-Type": "application/json" },
      },
    );
  };

  const scenario = behaviorScenario("A");
  const result = await runBehaviorTrial(
    scenario,
    1,
    scriptedDriver(scenario),
    createOpenAISemanticJudge("test-key", fakeFetch),
  );

  assert.equal(result.status, "PASS");
  assert.equal(result.reason, "retry recovered the grade");
  assert.equal(graderRequests, 2);
});

test("two malformed semantic grader attempts produce ERROR rather than FAIL", async () => {
  let graderRequests = 0;
  const fakeFetch = async () => {
    graderRequests += 1;
    return new Response(
      JSON.stringify({
        id: `resp_grade_bad_${graderRequests}`,
        output_text:
          graderRequests === 1 ? "" : '{"pass":"yes"}',
        output: [],
      }),
      {
        status: 200,
        headers: { "Content-Type": "application/json" },
      },
    );
  };

  const scenario = behaviorScenario("A");
  const result = await runBehaviorTrial(
    scenario,
    1,
    scriptedDriver(scenario),
    createOpenAISemanticJudge("test-key", fakeFetch),
  );

  assert.equal(result.status, "ERROR");
  assert.match(
    result.reason,
    /semantic grader infrastructure failed/i,
  );
  assert.equal(graderRequests, 2);
});

test("advisor request infrastructure failure produces ERROR", async () => {
  const scenario = behaviorScenario("A");
  const result = await runBehaviorTrial(
    scenario,
    1,
    {
      async start() {
        throw new Error("synthetic advisor transport failure");
      },
      async continueTurn() {
        throw new Error("unexpected continuation");
      },
    },
    passingSemanticJudge,
  );

  assert.equal(result.status, "ERROR");
  assert.match(result.reason, /advisor start failed/i);
});

test("behavior summary separates FAIL from ERROR", () => {
  const scenarioA = behaviorScenario("A");
  const scenarioH = behaviorScenario("H");
  const emptyTrace = (scenario, trial) => ({
    scenario: scenario.id,
    trial,
    userMessage: scenario.userMessage,
    modelOutputs: [],
    clarificationOrFinalAnswer: null,
    localProductCalls: [],
    webSearchCount: 0,
    mockedToolResults: [],
    finalProductRefs: [],
  });

  const results = [
    {
      scenario: "A",
      scenarioName: scenarioA.name,
      trial: 1,
      status: "FAIL",
      localToolCallCount: 0,
      webSearchCount: 0,
      reason: "behavioral mismatch",
      trace: emptyTrace(scenarioA, 1),
    },
    {
      scenario: "H",
      scenarioName: scenarioH.name,
      trial: 1,
      status: "ERROR",
      localToolCallCount: 0,
      webSearchCount: 0,
      reason: "semantic grader infrastructure failed",
      trace: emptyTrace(scenarioH, 1),
    },
  ];

  const summary = formatBehaviorSummary(results);
  assert.match(summary, /passed: 0 \/ 2/);
  assert.match(summary, /failed scenarios: A \(/);
  assert.match(summary, /errored scenarios: H \(/);
  assert.doesNotMatch(summary, /failed scenarios:.*H \(/);
});

test("behavior CLI exit-code contract is non-zero for FAIL and ERROR", () => {
  const scenario = behaviorScenario("A");
  const base = {
    scenario: "A",
    scenarioName: scenario.name,
    trial: 1,
    localToolCallCount: 0,
    webSearchCount: 0,
    reason: "synthetic",
    trace: {
      scenario: "A",
      trial: 1,
      userMessage: scenario.userMessage,
      modelOutputs: [],
      clarificationOrFinalAnswer: null,
      localProductCalls: [],
      webSearchCount: 0,
      mockedToolResults: [],
      finalProductRefs: [],
    },
  };

  assert.equal(
    behaviorSuiteExitCode([{ ...base, status: "PASS" }]),
    0,
  );
  assert.equal(
    behaviorSuiteExitCode([{ ...base, status: "FAIL" }]),
    1,
  );
  assert.equal(
    behaviorSuiteExitCode([{ ...base, status: "ERROR" }]),
    1,
  );
});
