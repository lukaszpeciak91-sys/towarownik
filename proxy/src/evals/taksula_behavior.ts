import {
  CURRENT_ADVISOR_PROTOCOL_VERSION,
  LOCAL_TOOL_NAME,
  OPENAI_MAX_OUTPUT_TOKENS,
  OPENAI_MODEL,
  OPENAI_REASONING_EFFORT,
  OPENAI_RESPONSES_URL,
} from "../config.js";
import { continueAgent, startAgent } from "../openai.js";
import type {
  AgentResult,
  ProductRef,
  ToolArguments,
  ToolContinuationResult,
  UpstreamFetch,
  VerifiedProduct,
  VerifiedQueryResult,
  VerifiedToolResult,
} from "../types.js";

export const DEFAULT_BEHAVIOR_TRIALS = 1;
export const MAX_BEHAVIOR_TRIALS = 10;
export const DEFAULT_EVAL_STORE_NUMBER = "075";
const PRODUCTION_LOCAL_TOOL_LIMIT = 3;
const MAX_MODEL_STEPS = 8;

export type BehaviorScenarioId =
  | "A"
  | "B"
  | "C"
  | "D"
  | "E"
  | "F"
  | "G_ZERO"
  | "G_NULL"
  | "G_NOT_FOUND"
  | "G_UNAVAILABLE"
  | "H"
  | "I";

export interface BehaviorScenario {
  id: BehaviorScenarioId;
  name: string;
  userMessage: string;
  semanticRubric: string[];
}

export interface ModelOutputTrace {
  order: number;
  stage: "START" | "CONTINUE";
  type: "answer" | "tool_request";
  responseId: string;
  webSearchCalls: number;
  text: string | null;
  productRefs: ProductRef[];
  toolArguments: ToolArguments | null;
}

export interface LocalToolCallTrace {
  order: number;
  arguments: ToolArguments;
  mockedResult: ToolContinuationResult;
  executedMockObi: boolean;
}

export interface TerminalAnswerTrace {
  kind: "clarification_candidate" | "final_answer";
  text: string;
  productRefs: ProductRef[];
}

export interface BehaviorTrace {
  scenario: BehaviorScenarioId;
  trial: number;
  userMessage: string;
  modelOutputs: ModelOutputTrace[];
  clarificationOrFinalAnswer: TerminalAnswerTrace | null;
  findObiProductsCalls: LocalToolCallTrace[];
  webSearchCount: number;
  mockedToolResults: ToolContinuationResult[];
  finalProductRefs: ProductRef[];
}

export interface SemanticGrade {
  pass: boolean;
  reason: string;
}

export interface SemanticGradeInput {
  scenario: BehaviorScenario;
  trace: BehaviorTrace;
}

export interface SemanticJudge {
  grade(input: SemanticGradeInput): Promise<SemanticGrade>;
}

export interface AdvisorDriver {
  start(message: string, storeNumber: string): Promise<AgentResult>;
  continueTurn(
    responseId: string,
    callId: string,
    storeNumber: string,
    result: ToolContinuationResult,
  ): Promise<AgentResult>;
}

export interface BehaviorTrialResult {
  scenario: BehaviorScenarioId;
  scenarioName: string;
  trial: number;
  status: "PASS" | "FAIL";
  localToolCallCount: number;
  webSearchCount: number;
  reason: string;
  trace: BehaviorTrace;
}

export interface BehaviorSuiteOptions {
  trials?: number;
  scenarioIds?: BehaviorScenarioId[];
  driverFactory: (
    scenario: BehaviorScenario,
    trial: number,
  ) => AdvisorDriver;
  semanticJudge: SemanticJudge;
}

const ZIP_TIES: VerifiedProduct[] = [
  product(
    "6100001",
    "Mock opaski kablowe czarne 2,5 x 100 mm",
    24,
    7.99,
    [
      ["Wymiary", "2,5 x 100 mm"],
      ["Zastosowanie", "wewnątrz"],
    ],
  ),
  product(
    "6100002",
    "Mock opaski kablowe czarne 4,2 x 380 mm",
    11,
    16.49,
    [
      ["Wymiary", "4,2 x 380 mm"],
      ["Zastosowanie", "wewnątrz"],
    ],
  ),
  product(
    "6100003",
    "Mock opaski kablowe czarne 4,8 x 300 mm UV",
    8,
    19.99,
    [
      ["Wymiary", "4,8 x 300 mm"],
      ["Zastosowanie", "zewnątrz"],
      ["Odporność UV", "tak"],
    ],
  ),
];

const DIRECT_PRODUCT = product(
  "7000001",
  "Mock produkt do kontroli ceny i stanu",
  7,
  12.49,
  [["Parametr", "wartość testowa"]],
);

const ZERO_STOCK_PRODUCT = product(
  "7000002",
  "Mock produkt ze stanem zero",
  0,
  21.99,
  [],
);

const UNKNOWN_STOCK_PRODUCT = product(
  "7000003",
  "Mock produkt z nieznanym stanem",
  null,
  18.49,
  [],
);

const KIT_PRODUCTS = [
  product(
    "7200001",
    "Mock silikon sanitarny",
    10,
    29.99,
    [["Zastosowanie", "uszczelnianie sanitarne"]],
  ),
  product(
    "7200002",
    "Mock wyciskacz do kartuszy",
    6,
    24.99,
    [["Typ", "ręczny"]],
  ),
  product(
    "7200003",
    "Mock narzędzie do wygładzania silikonu",
    4,
    14.99,
    [["Typ", "zestaw profili"]],
  ),
];

export const BEHAVIOR_SCENARIOS: readonly BehaviorScenario[] = [
  {
    id: "A",
    name: "Ambiguous black cable ties",
    userMessage: "Klient potrzebuje czarnych trytytek.",
    semanticRubric: [
      "The answer is one concise, relevant clarification before any concrete SKU selection.",
      "The clarification asks for missing selection information that materially affects the choice, such as size and/or indoor-versus-outdoor application.",
      "It does not pretend that a concrete variant has already been selected.",
    ],
  },
  {
    id: "B",
    name: "Browse black cable-tie assortment",
    userMessage: "Jakie czarne trytytki mamy?",
    semanticRubric: [
      "The answer treats this as browse/assortment intent rather than asking an unnecessary selection clarification.",
      "Several materially fitting verified variants are surfaced or compared instead of silently collapsing them to one.",
      "The answer does not imply that the mocked bounded subset is the complete assortment; wording equivalent to examples or among others is acceptable.",
    ],
  },
  {
    id: "C",
    name: "Specified cable-tie selection",
    userMessage:
      "Potrzebuję czarnych trytytek 4,2 x 380 mm do środka.",
    semanticRubric: [],
  },
  {
    id: "D",
    name: "Ambiguous faucet aerator",
    userMessage: "Klient potrzebuje końcówki z sitkiem do kranu.",
    semanticRubric: [
      "The answer asks one concise clarification about the missing connection/thread/size or fitting type before concrete selection.",
      "It does not invent compatibility or select an arbitrary concrete SKU.",
    ],
  },
  {
    id: "E",
    name: "Three fitting verified variants",
    userMessage:
      "Pokaż czarne trytytki do środka i sensowne warianty rozmiarowe.",
    semanticRubric: [
      "The answer presents or compares the useful fitting alternatives, or clearly explains why one is preferred over the other fitting verified variants.",
      "It does not imply that only one matching product exists.",
    ],
  },
  {
    id: "F",
    name: "Direct current stock and price by OBIK",
    userMessage: "Jaki jest teraz stan i cena OBIK 7000001?",
    semanticRubric: [
      "The final answer directly answers current stock and price using the mocked verified product.",
      "It does not turn the answer into a complement-shopping list.",
    ],
  },
  {
    id: "G_ZERO",
    name: "Availability semantics: stock zero",
    userMessage: "Sprawdź dostępność OBIK 7000002.",
    semanticRubric: [
      "The answer treats stock = 0 as confirmed zero/unavailable in the verified store.",
      "It does not describe the stock as merely unknown.",
    ],
  },
  {
    id: "G_NULL",
    name: "Availability semantics: stock unknown",
    userMessage: "Sprawdź dostępność OBIK 7000003.",
    semanticRubric: [
      "The answer treats stock = null as unknown or unconfirmed availability.",
      "It does not claim zero stock, out of stock, or confirmed unavailability.",
    ],
  },
  {
    id: "G_NOT_FOUND",
    name: "Availability semantics: not found",
    userMessage: "Sprawdź dostępność OBIK 7000004.",
    semanticRubric: [
      "The answer says that no verified matching product was found for the query.",
      "It does not convert not_found into stock zero or confirmed store unavailability.",
    ],
  },
  {
    id: "G_UNAVAILABLE",
    name: "Availability semantics: retrieval unavailable",
    userMessage: "Sprawdź dostępność OBIK 7000005.",
    semanticRubric: [
      "The answer says that retrieval could not establish the current fact or that availability could not be verified.",
      "It does not present the result as not_found or stock zero.",
    ],
  },
  {
    id: "H",
    name: "Small washbasin-sealing kit",
    userMessage: "Co potrzebuję do uszczelnienia umywalki?",
    semanticRubric: [
      "The answer forms a small practical essentials-first kit for sealing a washbasin.",
      "It does not require separate confirmation for every category.",
      "It avoids an exhaustive or absurd shopping list.",
    ],
  },
  {
    id: "I",
    name: "General SDS Plus versus SDS Max knowledge",
    userMessage: "Czym różni się SDS+ od SDS Max?",
    semanticRubric: [
      "The answer gives a useful general technical distinction between SDS+ and SDS Max.",
      "It does not require current OBI assortment facts to answer the general technical question.",
    ],
  },
];

export function behaviorScenario(
  id: BehaviorScenarioId,
): BehaviorScenario {
  const scenario = BEHAVIOR_SCENARIOS.find(
    (candidate) => candidate.id === id,
  );
  if (!scenario) {
    throw new Error(`Unknown behavior scenario: ${id}`);
  }
  return scenario;
}

export function createProductionAdvisorDriver(
  apiKey: string,
  upstreamFetch: UpstreamFetch,
): AdvisorDriver {
  return {
    start(message, storeNumber) {
      return startAgent(
        message,
        storeNumber,
        apiKey,
        upstreamFetch,
        CURRENT_ADVISOR_PROTOCOL_VERSION,
      );
    },
    continueTurn(responseId, callId, storeNumber, result) {
      return continueAgent(
        responseId,
        callId,
        storeNumber,
        result,
        apiKey,
        upstreamFetch,
        CURRENT_ADVISOR_PROTOCOL_VERSION,
      );
    },
  };
}

export function createOpenAISemanticJudge(
  apiKey: string,
  upstreamFetch: UpstreamFetch,
): SemanticJudge {
  return {
    async grade(input) {
      const response = await upstreamFetch(OPENAI_RESPONSES_URL, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${apiKey}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          model: OPENAI_MODEL,
          instructions:
            "You are a strict behavioral-evaluation grader. Judge only the explicit rubric against the supplied observable trace. Do not require exact wording. Do not infer or request hidden reasoning. Return only the requested JSON with a boolean pass and one short diagnostic sentence.",
          input: JSON.stringify({
            scenario: input.scenario.id,
            userMessage: input.trace.userMessage,
            rubric: input.scenario.semanticRubric,
            answer:
              input.trace.clarificationOrFinalAnswer?.text ?? null,
            productRefs: input.trace.finalProductRefs,
            localToolCalls: input.trace.findObiProductsCalls.map(
              (call) => ({
                arguments: call.arguments,
                mockedResult: call.mockedResult,
              }),
            ),
          }),
          reasoning: {
            effort: OPENAI_REASONING_EFFORT,
          },
          max_output_tokens: Math.min(160, OPENAI_MAX_OUTPUT_TOKENS),
          store: false,
          text: {
            format: {
              type: "json_schema",
              name: "taksula_behavior_grade",
              strict: true,
              schema: {
                type: "object",
                properties: {
                  pass: { type: "boolean" },
                  reason: {
                    type: "string",
                    minLength: 1,
                    maxLength: 240,
                  },
                },
                required: ["pass", "reason"],
                additionalProperties: false,
              },
            },
          },
        }),
      });

      if (!response.ok) {
        throw new Error(
          `Semantic grader request failed with HTTP ${response.status}`,
        );
      }

      const payload = (await response.json()) as unknown;
      const raw = extractOutputText(payload);
      if (!raw) {
        throw new Error("Semantic grader returned no text");
      }

      let parsed: unknown;
      try {
        parsed = JSON.parse(raw) as unknown;
      } catch {
        throw new Error("Semantic grader returned invalid JSON");
      }

      if (
        !isRecord(parsed) ||
        typeof parsed.pass !== "boolean" ||
        typeof parsed.reason !== "string" ||
        parsed.reason.trim().length === 0
      ) {
        throw new Error("Semantic grader returned an invalid grade");
      }

      return {
        pass: parsed.pass,
        reason: parsed.reason.trim().slice(0, 240),
      };
    },
  };
}

export async function runBehaviorSuite(
  options: BehaviorSuiteOptions,
): Promise<BehaviorTrialResult[]> {
  const trials = options.trials ?? DEFAULT_BEHAVIOR_TRIALS;
  if (
    !Number.isInteger(trials) ||
    trials < 1 ||
    trials > MAX_BEHAVIOR_TRIALS
  ) {
    throw new Error(
      `trials must be an integer from 1 to ${MAX_BEHAVIOR_TRIALS}`,
    );
  }

  const selected = options.scenarioIds?.length
    ? options.scenarioIds.map(behaviorScenario)
    : [...BEHAVIOR_SCENARIOS];

  const results: BehaviorTrialResult[] = [];
  for (const scenario of selected) {
    for (let trial = 1; trial <= trials; trial += 1) {
      results.push(
        await runBehaviorTrial(
          scenario,
          trial,
          options.driverFactory(scenario, trial),
          options.semanticJudge,
        ),
      );
    }
  }
  return results;
}

export async function runBehaviorTrial(
  scenario: BehaviorScenario,
  trial: number,
  driver: AdvisorDriver,
  semanticJudge: SemanticJudge,
): Promise<BehaviorTrialResult> {
  const trace: BehaviorTrace = {
    scenario: scenario.id,
    trial,
    userMessage: scenario.userMessage,
    modelOutputs: [],
    clarificationOrFinalAnswer: null,
    findObiProductsCalls: [],
    webSearchCount: 0,
    mockedToolResults: [],
    finalProductRefs: [],
  };

  let result: AgentResult;
  try {
    result = await driver.start(
      scenario.userMessage,
      DEFAULT_EVAL_STORE_NUMBER,
    );
  } catch (error) {
    return failedInfrastructureResult(
      scenario,
      trial,
      trace,
      `advisor start failed: ${errorMessage(error)}`,
    );
  }

  for (let step = 0; step < MAX_MODEL_STEPS; step += 1) {
    recordModelOutput(trace, result, step === 0 ? "START" : "CONTINUE");

    if (result.type === "answer") {
      trace.finalProductRefs = [...result.productRefs];
      trace.clarificationOrFinalAnswer = {
        kind:
          scenario.id === "A" || scenario.id === "D"
            ? "clarification_candidate"
            : "final_answer",
        text: result.text,
        productRefs: [...result.productRefs],
      };
      return gradeCompletedTrial(
        scenario,
        trial,
        trace,
        semanticJudge,
      );
    }

    const args = currentToolArguments(result);
    const callOrder = trace.findObiProductsCalls.length + 1;
    const executedMockObi =
      callOrder <= PRODUCTION_LOCAL_TOOL_LIMIT;
    const mockedResult = executedMockObi
      ? mockToolResultForScenario(scenario, args, callOrder)
      : {
          storeNumber: args.storeNumber,
          queries: args.queries,
          rejection: "local_tool_limit_reached" as const,
        };

    trace.findObiProductsCalls.push({
      order: callOrder,
      arguments: args,
      mockedResult,
      executedMockObi,
    });
    trace.mockedToolResults.push(mockedResult);

    try {
      result = await driver.continueTurn(
        result.responseId,
        result.tool.callId,
        args.storeNumber,
        mockedResult,
      );
    } catch (error) {
      return failedInfrastructureResult(
        scenario,
        trial,
        trace,
        `advisor continuation failed: ${errorMessage(error)}`,
      );
    }
  }

  return failedInfrastructureResult(
    scenario,
    trial,
    trace,
    "model did not reach a final answer within the eval step bound",
  );
}

export function formatBehaviorSummary(
  results: readonly BehaviorTrialResult[],
): string {
  const lines = [
    "scenario\ttrial\tresult\tlocal_calls\tweb_searches\treason",
  ];
  for (const result of results) {
    lines.push(
      [
        result.scenario,
        result.trial,
        result.status,
        result.localToolCallCount,
        result.webSearchCount,
        compactReason(result.reason),
      ].join("\t"),
    );
  }

  const passed = results.filter(
    (result) => result.status === "PASS",
  ).length;
  const failedNames = [
    ...new Set(
      results
        .filter((result) => result.status === "FAIL")
        .map((result) => result.scenario),
    ),
  ];

  lines.push("");
  lines.push(`passed: ${passed} / ${results.length}`);
  lines.push(
    `failed scenarios: ${failedNames.length ? failedNames.join(", ") : "none"}`,
  );
  return lines.join("\n");
}

function recordModelOutput(
  trace: BehaviorTrace,
  result: AgentResult,
  stage: "START" | "CONTINUE",
): void {
  trace.webSearchCount += result.webSearchCalls;

  if (result.type === "answer") {
    trace.modelOutputs.push({
      order: trace.modelOutputs.length + 1,
      stage,
      type: "answer",
      responseId: result.responseId,
      webSearchCalls: result.webSearchCalls,
      text: result.text,
      productRefs: [...result.productRefs],
      toolArguments: null,
    });
    return;
  }

  trace.modelOutputs.push({
    order: trace.modelOutputs.length + 1,
    stage,
    type: "tool_request",
    responseId: result.responseId,
    webSearchCalls: result.webSearchCalls,
    text: null,
    productRefs: [],
    toolArguments: currentToolArguments(result),
  });
}

function currentToolArguments(
  result: Extract<AgentResult, { type: "tool_request" }>,
): ToolArguments {
  const args = result.tool.arguments;
  if (
    !("queries" in args) ||
    !Array.isArray(args.queries)
  ) {
    throw new Error(
      "Behavior eval requires the current grouped v2 advisor tool contract",
    );
  }
  return {
    storeNumber: args.storeNumber,
    queries: args.queries.map((query) => ({ ...query })),
  };
}

async function gradeCompletedTrial(
  scenario: BehaviorScenario,
  trial: number,
  trace: BehaviorTrace,
  semanticJudge: SemanticJudge,
): Promise<BehaviorTrialResult> {
  const failures = deterministicFailures(scenario, trace);
  if (failures.length > 0) {
    return {
      scenario: scenario.id,
      scenarioName: scenario.name,
      trial,
      status: "FAIL",
      localToolCallCount: trace.findObiProductsCalls.length,
      webSearchCount: trace.webSearchCount,
      reason: failures.slice(0, 2).join("; "),
      trace,
    };
  }

  if (scenario.semanticRubric.length > 0) {
    let semantic: SemanticGrade;
    try {
      semantic = await semanticJudge.grade({ scenario, trace });
    } catch (error) {
      return {
        scenario: scenario.id,
        scenarioName: scenario.name,
        trial,
        status: "FAIL",
        localToolCallCount: trace.findObiProductsCalls.length,
        webSearchCount: trace.webSearchCount,
        reason: `semantic grader failed: ${errorMessage(error)}`,
        trace,
      };
    }

    if (!semantic.pass) {
      return {
        scenario: scenario.id,
        scenarioName: scenario.name,
        trial,
        status: "FAIL",
        localToolCallCount: trace.findObiProductsCalls.length,
        webSearchCount: trace.webSearchCount,
        reason: compactReason(semantic.reason),
        trace,
      };
    }

    return {
      scenario: scenario.id,
      scenarioName: scenario.name,
      trial,
      status: "PASS",
      localToolCallCount: trace.findObiProductsCalls.length,
      webSearchCount: trace.webSearchCount,
      reason: compactReason(semantic.reason),
      trace,
    };
  }

  return {
    scenario: scenario.id,
    scenarioName: scenario.name,
    trial,
    status: "PASS",
    localToolCallCount: trace.findObiProductsCalls.length,
    webSearchCount: trace.webSearchCount,
    reason: "deterministic checks passed",
    trace,
  };
}

function deterministicFailures(
  scenario: BehaviorScenario,
  trace: BehaviorTrace,
): string[] {
  const failures: string[] = [];
  const terminal = trace.clarificationOrFinalAnswer;

  if (!terminal) {
    failures.push("no terminal answer");
    return failures;
  }

  const verifiedRefs = collectVerifiedRefs(
    trace.mockedToolResults,
  );
  for (const ref of trace.finalProductRefs) {
    if (!verifiedRefs.has(refKey(ref))) {
      failures.push(
        `ungrounded productRef ${ref.storeNumber}:${ref.obik}`,
      );
    }
  }

  const duplicate = duplicateNormalizedQuery(
    trace.findObiProductsCalls,
  );
  if (duplicate) {
    failures.push(`redundant repeated query: ${duplicate}`);
  }

  const calls = trace.findObiProductsCalls;
  const firstCall = calls[0];
  const firstQueries = firstCall?.arguments.queries ?? [];

  switch (scenario.id) {
    case "A":
    case "D":
      if (calls.length !== 0) {
        failures.push(
          "local OBI lookup occurred before clarification",
        );
      }
      if (trace.finalProductRefs.length !== 0) {
        failures.push(
          "concrete productRef returned before clarification",
        );
      }
      if (trace.webSearchCount !== 0) {
        failures.push(
          "web search was unnecessary for the clarification",
        );
      }
      break;

    case "B":
      if (calls.length === 0) {
        failures.push("browse request did not use local OBI");
      }
      if (!firstQueries.some((query) => query.limit > 1)) {
        failures.push(
          "browse lookup did not request multiple results",
        );
      }
      break;

    case "C":
      if (calls.length === 0) {
        failures.push(
          "specified selection did not proceed to verification",
        );
      }
      if (
        !flattenQueries(calls).some(
          (query) =>
            /380/.test(query) &&
            /4\s*[,.]?\s*2/.test(query),
        )
      ) {
        failures.push(
          "verification query did not preserve the specified size",
        );
      }
      break;

    case "E":
      if (calls.length === 0) {
        failures.push(
          "variant comparison did not use verified OBI data",
        );
      }
      if (verifiedRefs.size < 3) {
        failures.push(
          "fixture did not expose three verified fitting variants",
        );
      }
      break;

    case "F":
      if (calls.length !== 1) {
        failures.push(
          "direct stock/price request should use one local lookup",
        );
      }
      if (trace.webSearchCount !== 0) {
        failures.push(
          "direct stock/price request used unnecessary web search",
        );
      }
      if (
        flattenQueries(calls).length !== 1 ||
        !flattenQueries(calls)[0]?.includes("7000001")
      ) {
        failures.push(
          "direct OBIK lookup was broadened into unrelated searches",
        );
      }
      break;

    case "G_ZERO":
      requireSingleObikQuery(calls, "7000002", failures);
      break;
    case "G_NULL":
      requireSingleObikQuery(calls, "7000003", failures);
      break;
    case "G_NOT_FOUND":
      requireSingleObikQuery(calls, "7000004", failures);
      break;
    case "G_UNAVAILABLE":
      requireSingleObikQuery(calls, "7000005", failures);
      break;

    case "H":
      if (calls.length === 0) {
        failures.push("kit request did not verify products");
      }
      if (firstQueries.length < 2) {
        failures.push(
          "related kit categories were not batched in the first lookup",
        );
      }
      if (calls.length > 2) {
        failures.push(
          "kit request used too many narrow local lookups",
        );
      }
      break;

    case "I":
      if (calls.length !== 0) {
        failures.push(
          "general SDS question used unnecessary local OBI lookup",
        );
      }
      if (trace.webSearchCount !== 0) {
        failures.push(
          "general SDS question used unnecessary web search",
        );
      }
      if (trace.finalProductRefs.length !== 0) {
        failures.push(
          "general SDS answer unexpectedly returned productRefs",
        );
      }
      break;
  }

  return failures;
}

function requireSingleObikQuery(
  calls: readonly LocalToolCallTrace[],
  obik: string,
  failures: string[],
): void {
  const queries = flattenQueries(calls);
  if (calls.length !== 1 || queries.length !== 1) {
    failures.push(
      "availability check should use one focused local lookup",
    );
    return;
  }
  if (!queries[0]?.includes(obik)) {
    failures.push(
      `availability lookup did not preserve OBIK ${obik}`,
    );
  }
}

function mockToolResultForScenario(
  scenario: BehaviorScenario,
  args: ToolArguments,
  callOrder: number,
): VerifiedToolResult {
  return {
    storeNumber: args.storeNumber,
    results: args.queries.map((query, queryIndex) =>
      mockQueryResult(
        scenario.id,
        query.query,
        query.limit,
        callOrder,
        queryIndex,
      ),
    ),
  };
}

function mockQueryResult(
  scenarioId: BehaviorScenarioId,
  query: string,
  limit: number,
  callOrder: number,
  queryIndex: number,
): VerifiedQueryResult {
  switch (scenarioId) {
    case "B":
    case "E":
      return verifiedQuery(
        query,
        ZIP_TIES.slice(0, Math.min(limit, ZIP_TIES.length)),
      );

    case "C":
      return verifiedQuery(
        query,
        [ZIP_TIES[1]].slice(0, limit),
      );

    case "F":
      return verifiedQuery(query, [DIRECT_PRODUCT].slice(0, limit));

    case "G_ZERO":
      return verifiedQuery(
        query,
        [ZERO_STOCK_PRODUCT].slice(0, limit),
      );

    case "G_NULL":
      return verifiedQuery(
        query,
        [UNKNOWN_STOCK_PRODUCT].slice(0, limit),
      );

    case "G_NOT_FOUND":
      return {
        query,
        status: "not_found",
        products: [],
      };

    case "G_UNAVAILABLE":
      return {
        query,
        status: "unavailable",
        products: [],
      };

    case "H": {
      const normalized = normalizeQuery(query);
      let selected: VerifiedProduct;
      if (/silik|uszczeln/.test(normalized)) {
        selected = KIT_PRODUCTS[0];
      } else if (/pistolet|wycisk|kartusz/.test(normalized)) {
        selected = KIT_PRODUCTS[1];
      } else if (/gladz|szpach|profil|narzedz/.test(normalized)) {
        selected = KIT_PRODUCTS[2];
      } else {
        selected =
          KIT_PRODUCTS[
            (callOrder + queryIndex - 1) % KIT_PRODUCTS.length
          ];
      }
      return verifiedQuery(query, [selected].slice(0, limit));
    }

    case "A":
    case "D":
    case "I":
      return {
        query,
        status: "not_found",
        products: [],
      };
  }
}

function verifiedQuery(
  query: string,
  products: VerifiedProduct[],
): VerifiedQueryResult {
  return {
    query,
    status: products.length ? "verified" : "not_found",
    products,
  };
}

function product(
  obik: string,
  name: string,
  stock: number | null,
  price: number | null,
  facts: readonly (readonly [string, string])[],
): VerifiedProduct {
  return {
    obik,
    name,
    brand: "Mock Brand",
    shortDescription: "Deterministic behavioral-eval fixture.",
    technicalFacts: facts.map(([label, value]) => ({
      label,
      value,
    })),
    stock,
    price,
  };
}

function collectVerifiedRefs(
  results: readonly ToolContinuationResult[],
): Set<string> {
  const refs = new Set<string>();
  for (const result of results) {
    if (!("results" in result)) continue;
    for (const queryResult of result.results) {
      if (queryResult.status !== "verified") continue;
      for (const productValue of queryResult.products) {
        refs.add(
          refKey({
            storeNumber: result.storeNumber,
            obik: productValue.obik,
          }),
        );
      }
    }
  }
  return refs;
}

function refKey(ref: ProductRef): string {
  return `${ref.storeNumber}:${ref.obik}`;
}

function flattenQueries(
  calls: readonly LocalToolCallTrace[],
): string[] {
  return calls.flatMap((call) =>
    call.arguments.queries.map((query) => query.query),
  );
}

function duplicateNormalizedQuery(
  calls: readonly LocalToolCallTrace[],
): string | null {
  const seen = new Set<string>();
  for (const query of flattenQueries(calls)) {
    const normalized = normalizeQuery(query);
    if (!normalized) continue;
    if (seen.has(normalized)) return query;
    seen.add(normalized);
  }
  return null;
}

function normalizeQuery(value: string): string {
  return value
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim()
    .replace(/\s+/g, " ");
}

function failedInfrastructureResult(
  scenario: BehaviorScenario,
  trial: number,
  trace: BehaviorTrace,
  reason: string,
): BehaviorTrialResult {
  return {
    scenario: scenario.id,
    scenarioName: scenario.name,
    trial,
    status: "FAIL",
    localToolCallCount: trace.findObiProductsCalls.length,
    webSearchCount: trace.webSearchCount,
    reason: compactReason(reason),
    trace,
  };
}

function compactReason(reason: string): string {
  return reason.replace(/\s+/g, " ").trim().slice(0, 240);
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function extractOutputText(payload: unknown): string | null {
  if (!isRecord(payload)) return null;
  if (
    typeof payload.output_text === "string" &&
    payload.output_text.trim()
  ) {
    return payload.output_text;
  }
  if (!Array.isArray(payload.output)) return null;

  const parts: string[] = [];
  for (const item of payload.output) {
    if (
      !isRecord(item) ||
      item.type !== "message" ||
      !Array.isArray(item.content)
    ) {
      continue;
    }
    for (const content of item.content) {
      if (
        isRecord(content) &&
        content.type === "output_text" &&
        typeof content.text === "string"
      ) {
        parts.push(content.text);
      }
    }
  }
  const joined = parts.join("");
  return joined.trim() ? joined : null;
}

function isRecord(
  value: unknown,
): value is Record<string, unknown> {
  return (
    typeof value === "object" &&
    value !== null &&
    !Array.isArray(value)
  );
}
