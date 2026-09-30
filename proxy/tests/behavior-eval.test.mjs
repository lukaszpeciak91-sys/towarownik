import test from "node:test";
import assert from "node:assert/strict";

import {
  CURRENT_ADVISOR_PROTOCOL_VERSION,
  FINAL_ANSWER_FORMAT,
  OPENAI_MAX_OUTPUT_TOKENS,
  OPENAI_MODEL,
  OPENAI_REASONING_EFFORT,
  WEB_SEARCH_TOOL,
  agentInstructionsForStore,
  obiToolForProtocol,
} from "../.test-dist/config.js";
import {
  BEHAVIOR_SCENARIOS,
  behaviorScenario,
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
      ? group.products.map((product) => ({
          storeNumber: result.storeNumber,
          obik: product.obik,
        }))
      : [],
  );
}

function scriptedDriver(scenario) {
  return {
    async start() {
      switch (scenario.id) {
        case "A":
          return answer(
            "Jakiej długości potrzebuje i do czego będą używane?",
          );
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
        case "B":
        case "E":
          return answer(
            "Znalazłam m.in. kilka wariantów różniących się wymiarami.",
            refs,
          );
        case "C":
          return answer("Pasuje zweryfikowany wariant.", refs);
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
    browse.trace.findObiProductsCalls[0].arguments.queries[0]
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

test("clarification-first scenarios fail on any local OBI lookup before clarification", async () => {
  const cases = [
    ["A", "czarne trytytki"],
    ["A", "czarne trytytki 4,2 x 380 mm"],
    ["A", "młotki murarskie"],
    ["D", "końcówka do kranu"],
    ["H_AMBIGUOUS", "silikon do umywalki"],
  ];

  for (const [id, query] of cases) {
    const scenario = behaviorScenario(id);
    const result = await runBehaviorTrial(
      scenario,
      1,
      {
        async start() {
          return toolRequest([{ query, limit: 3 }]);
        },
        async continueTurn() {
          return answer("Jaki dokładnie wariant jest potrzebny?");
        },
      },
      passingSemanticJudge,
    );

    assert.equal(result.status, "FAIL");
    assert.match(
      result.reason,
      /local OBI lookup occurred before clarification/i,
    );
  }
});

test("scenario A accepts one natural clarification covering two tightly related decision-critical details", async () => {
  const scenario = behaviorScenario("A");
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
        const usefulClarification =
          /\?/.test(text) &&
          /długoś|dlugos/i.test(text) &&
          /do czego|używane|uzywane/i.test(text);
        return {
          pass: usefulClarification,
          reason: usefulClarification
            ? "one natural clarification covers related missing details"
            : "missing useful clarification",
        };
      },
    },
  );

  assert.equal(result.status, "PASS");
  assert.equal(result.localToolCallCount, 0);
  assert.equal(result.trace.finalProductRefs.length, 0);
  assert.equal(
    observedRubric.some((line) =>
      /more than one tightly related decision-critical detail/i.test(line),
    ),
    true,
  );
  assert.equal(
    observedRubric.some((line) =>
      /do not require one exact parameter/i.test(line),
    ),
    true,
  );
});

test("scenario A rejects even a successful broad same-category lookup before clarification", async () => {
  const scenario = behaviorScenario("A");
  let semanticCalls = 0;
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
        assert.equal(group.products.length, 3);
        return answer(
          "Jaki rozmiar i do jakiego zastosowania mają być te trytytki?",
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
    /local OBI lookup occurred before clarification/i,
  );
  assert.equal(result.trace.finalProductRefs.length, 0);
  assert.equal(semanticCalls, 0);
});

test("scenario A rejects a concrete recommendation without clarification even without productRefs", async () => {
  const scenario = behaviorScenario("A");
  let semanticCalls = 0;
  const result = await runBehaviorTrial(
    scenario,
    1,
    {
      async start() {
        return answer(
          "Polecam konkretny wariant za 12,99 zł, mamy 5 sztuk.",
        );
      },
      async continueTurn() {
        throw new Error("unexpected continuation");
      },
    },
    {
      async grade({ scenario: gradedScenario, trace }) {
        semanticCalls += 1;
        assert.equal(
          gradedScenario.semanticRubric.some((line) =>
            /does not select or recommend a concrete SKU/i.test(line),
          ),
          true,
        );
        const text =
          trace.clarificationOrFinalAnswer?.text ?? "";
        const prematureRecommendation =
          /polecam|zł|sztuk/i.test(text);
        return {
          pass: !prematureRecommendation,
          reason: prematureRecommendation
            ? "concrete recommendation or candidate-specific price/stock appeared before clarification"
            : "no premature recommendation",
        };
      },
    },
  );

  assert.equal(result.status, "FAIL");
  assert.match(
    result.reason,
    /before clarification/i,
  );
  assert.equal(result.localToolCallCount, 0);
  assert.equal(result.trace.finalProductRefs.length, 0);
  assert.equal(semanticCalls, 1);
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
    /automatic local OBI lookup without explicit store intent/i,
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
      result.trace.findObiProductsCalls[0].arguments.queries[0]
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
        ? /browse request did not use local OBI/i
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
        CURRENT_ADVISOR_PROTOCOL_VERSION,
      ),
    );
    assert.deepEqual(body.text, {
      format: FINAL_ANSWER_FORMAT,
    });
    assert.deepEqual(body.tools, [
      obiToolForProtocol(
        CURRENT_ADVISOR_PROTOCOL_VERSION,
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
    findObiProductsCalls: [],
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
      findObiProductsCalls: [],
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
