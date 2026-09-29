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
            "Jaki mniej więcej rozmiar i czy mają być do środka czy na zewnątrz?",
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
        case "E":
          return toolRequest([
            { query: "czarne trytytki", limit: 3 },
          ]);
        case "C":
          return toolRequest([
            {
              query:
                "czarne trytytki 4,2 x 380 mm do wewnątrz",
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
          return toolRequest([
            { query: "silikon sanitarny", limit: 1 },
            { query: "pistolet do kartuszy", limit: 1 },
            {
              query: "narzędzie do wygładzania silikonu",
              limit: 1,
            },
          ]);
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
        case "H":
          return answer(
            "Podstawowy zestaw: silikon, wyciskacz i narzędzie do wygładzania.",
            refs,
          );
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

test("semantic grader failure is surfaced as the trial diagnostic", async () => {
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

test("real semantic grader adapter is deterministic under mocked upstream I/O", async () => {
  const captures = [];
  const fakeFetch = async (_input, init) => {
    captures.push(JSON.parse(init.body));
    const structured = JSON.stringify({
      pass: true,
      reason: "observable behavior satisfies the rubric",
    });
    return new Response(
      JSON.stringify({
        id: "resp_grade",
        output_text: structured,
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
  const trace = (
    await runBehaviorTrial(
      scenario,
      1,
      scriptedDriver(scenario),
      passingSemanticJudge,
    )
  ).trace;

  const grade = await judge.grade({ scenario, trace });
  assert.deepEqual(grade, {
    pass: true,
    reason: "observable behavior satisfies the rubric",
  });
  assert.equal(captures[0].model, OPENAI_MODEL);
  assert.deepEqual(captures[0].reasoning, {
    effort: OPENAI_REASONING_EFFORT,
  });
  assert.equal(
    Object.prototype.hasOwnProperty.call(
      captures[0],
      "tools",
    ),
    false,
  );
});
