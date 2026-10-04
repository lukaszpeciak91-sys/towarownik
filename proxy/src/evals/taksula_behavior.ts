import {
  OBI_GROUPED_ADVISOR_PROTOCOL_VERSION,
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
  ProviderToolArguments,
  ProviderVerifiedProduct,
  ProviderVerifiedQueryResult,
  ToolContinuationResult,
  UpstreamFetch,
  VerifiedProduct,
  VerifiedQueryResult,
  VerifiedToolResult,
} from "../types.js";

export const DEFAULT_BEHAVIOR_TRIALS = 1;
export const MAX_BEHAVIOR_TRIALS = 10;
export const DEFAULT_EVAL_STORE_NUMBER = "075";
export const DEFAULT_EVAL_KWANT_BRANCH_ID = "205";
export type BehaviorProvider = "obi-v2" | "kwant-v3";
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
  | "H_AMBIGUOUS"
  | "I"
  | "PRODUCT_INTENT";

export type WebPolicy = "forbidden" | "allowed" | "required";

export interface BehaviorScenario {
  id: BehaviorScenarioId;
  name: string;
  userMessage: string;
  webPolicy: WebPolicy;
  semanticRubric: string[];
}

interface BehaviorProviderContext {
  provider: BehaviorProvider;
  providerId: "obi-pl" | "kwant-pl";
  branchId: string;
}

type EvalToolArguments = ToolArguments | ProviderToolArguments;

export interface ModelOutputTrace {
  order: number;
  stage: "START" | "CONTINUE";
  type: "answer" | "tool_request";
  responseId: string;
  webSearchCalls: number;
  text: string | null;
  productRefs: ProductRef[];
  toolArguments: EvalToolArguments | null;
}

export interface LocalToolCallTrace {
  order: number;
  arguments: EvalToolArguments;
  mockedResult: ToolContinuationResult;
  executedMockProvider: boolean;
}

export interface TerminalAnswerTrace {
  kind: "clarification_candidate" | "final_answer";
  text: string;
  productRefs: ProductRef[];
}

export interface BehaviorTrace {
  provider: BehaviorProvider;
  scenario: BehaviorScenarioId;
  trial: number;
  userMessage: string;
  modelOutputs: ModelOutputTrace[];
  clarificationOrFinalAnswer: TerminalAnswerTrace | null;
  localProductCalls: LocalToolCallTrace[];
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
  start(message: string, branchId: string): Promise<AgentResult>;
  continueTurn(
    responseId: string,
    callId: string,
    branchId: string,
    result: ToolContinuationResult,
  ): Promise<AgentResult>;
}

export interface BehaviorTrialResult {
  scenario: BehaviorScenarioId;
  scenarioName: string;
  trial: number;
  status: "PASS" | "FAIL" | "ERROR";
  localToolCallCount: number;
  webSearchCount: number;
  reason: string;
  trace: BehaviorTrace;
}

export interface BehaviorSuiteOptions {
  provider?: BehaviorProvider;
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

const INDOOR_ZIP_TIES: VerifiedProduct[] = [
  product(
    "6110001",
    "Mock opaski kablowe czarne 2,5 x 100 mm indoor",
    20,
    7.49,
    [
      ["Wymiary", "2,5 x 100 mm"],
      ["Zastosowanie", "wewnątrz"],
    ],
  ),
  product(
    "6110002",
    "Mock opaski kablowe czarne 3,6 x 200 mm indoor",
    15,
    11.99,
    [
      ["Wymiary", "3,6 x 200 mm"],
      ["Zastosowanie", "wewnątrz"],
    ],
  ),
  product(
    "6110003",
    "Mock opaski kablowe czarne 4,8 x 300 mm indoor",
    9,
    18.49,
    [
      ["Wymiary", "4,8 x 300 mm"],
      ["Zastosowanie", "wewnątrz"],
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

const RECOMMENDED_BREAKER: VerifiedProduct = {
  obik: "7300001",
  name: "Wyłącznik nadprądowy B16 1P 6 kA Hager",
  brand: "Hager",
  shortDescription:
    "Wyłącznik nadprądowy 1P, charakterystyka B, 16 A, zdolność zwarciowa 6 kA.",
  technicalFacts: [
    { label: "Charakterystyka", value: "B" },
    { label: "Prąd znamionowy", value: "16 A" },
    { label: "Liczba biegunów", value: "1" },
    { label: "Zdolność zwarciowa", value: "6 kA" },
  ],
  stock: 12,
  price: 24.99,
};

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
    name: "Broad black cable-tie product intent",
    userMessage: "Klient potrzebuje czarnych trytytek.",
    webPolicy: "forbidden",
    semanticRubric: [
      "The answer treats this as broad concrete-product intent that can safely browse the current provider without requiring a clarification first.",
      "It surfaces several relevant verified black cable-tie variants or otherwise makes the available variation visible, rather than pretending one unspecified size is definitively correct.",
      "It does not imply that the bounded verified subset is the complete assortment; a brief follow-up question to narrow size or use is acceptable after or alongside the useful options.",
    ],
  },
  {
    id: "B",
    name: "Browse black cable-tie assortment",
    userMessage: "Jakie czarne trytytki mamy?",
    webPolicy: "forbidden",
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
      "Potrzebuję czarnych trytytek 4,2 x 380 mm do środka. Sprawdź co mamy.",
    webPolicy: "forbidden",
    semanticRubric: [],
  },
  {
    id: "D",
    name: "Ambiguous faucet aerator",
    userMessage: "Klient potrzebuje końcówki z sitkiem do kranu.",
    webPolicy: "forbidden",
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
    webPolicy: "forbidden",
    semanticRubric: [
      "The answer surfaces or meaningfully compares several useful verified black cable-tie variants that fit the user's indoor browse request.",
      "It does not falsely present the bounded verified results as the complete assortment.",
      "Judge the usefulness and truthfulness of the answer, not whether the tool query repeats every constraint already stated by the user.",
    ],
  },
  {
    id: "F",
    name: "Direct current stock and price by OBIK",
    userMessage: "Jaki jest teraz stan i cena OBIK 7000001?",
    webPolicy: "forbidden",
    semanticRubric: [
      "The final answer directly answers current stock and price using the mocked verified product.",
      "It does not turn the answer into a complement-shopping list.",
    ],
  },
  {
    id: "G_ZERO",
    name: "Availability semantics: stock zero",
    userMessage: "Sprawdź dostępność OBIK 7000002.",
    webPolicy: "forbidden",
    semanticRubric: [
      "The answer treats stock = 0 as confirmed zero/unavailable in the verified store.",
      "It does not describe the stock as merely unknown.",
    ],
  },
  {
    id: "G_NULL",
    name: "Availability semantics: stock unknown",
    userMessage: "Sprawdź dostępność OBIK 7000003.",
    webPolicy: "forbidden",
    semanticRubric: [
      "The answer treats stock = null as unknown or unconfirmed availability.",
      "It does not claim zero stock, out of stock, or confirmed unavailability.",
    ],
  },
  {
    id: "G_NOT_FOUND",
    name: "Availability semantics: not found",
    userMessage: "Sprawdź dostępność OBIK 7000004.",
    webPolicy: "forbidden",
    semanticRubric: [
      "The answer says that no verified matching product was found for the query.",
      "It does not convert not_found into stock zero or confirmed store unavailability.",
    ],
  },
  {
    id: "G_UNAVAILABLE",
    name: "Availability semantics: retrieval unavailable",
    userMessage: "Sprawdź dostępność OBIK 7000005.",
    webPolicy: "forbidden",
    semanticRubric: [
      "The answer says that retrieval could not establish the current fact or that availability could not be verified.",
      "It does not present the result as not_found or stock zero.",
    ],
  },
  {
    id: "H",
    name: "Understood washbasin-to-wall sealing job advice",
    userMessage:
      "Co potrzebuję do uszczelnienia silikonem szczeliny między umywalką a ścianą?",
    webPolicy: "forbidden",
    semanticRubric: [
      "The answer gives useful practical advice for sealing the specified washbasin-to-wall gap with silicone.",
      "Natural useful guidance may include sanitary silicone, suitable color guidance, a cartridge gun, cleaning or degreasing, masking, or finishing technique; no exact item list, labels, or answer structure is required.",
      "Judge the answer as advisor-first guidance rather than a shopping-format response; current provider assortment facts or product cards are not required for this understood job.",
    ],
  },
  {
    id: "H_AMBIGUOUS",
    name: "Ambiguous washbasin sealing job",
    userMessage: "Co potrzebuję do uszczelnienia umywalki?",
    webPolicy: "forbidden",
    semanticRubric: [
      "The answer must acknowledge the material ambiguity instead of silently assuming one sealing problem. It may either ask a concise clarification about what is leaking/being sealed, or give a clearly conditional answer for one interpretation while explicitly noting that a drain/siphon leak is a different problem.",
      "Equivalent conversational strategies are acceptable as long as the materially different interpretations remain explicit and no interpretation is presented as certain without support.",
      "It does not select or present a concrete provider kit while the ambiguity remains unresolved.",
    ],
  },
  {
    id: "I",
    name: "General SDS Plus versus SDS Max knowledge",
    userMessage: "Czym różni się SDS+ od SDS Max?",
    webPolicy: "forbidden",
    semanticRubric: [
      "The answer gives a useful general technical distinction between SDS+ and SDS Max.",
      "It does not require current provider assortment facts to answer the general technical question.",
    ],
  },
];

export const PRODUCT_INTENT_SCENARIO: BehaviorScenario = {
  id: "PRODUCT_INTENT",
  name: "Concrete product recommendation uses current provider",
  userMessage:
    "Potrzebuję konkretnego wyłącznika nadprądowego B16, 1P, 6 kA. Co polecasz?",
  webPolicy: "forbidden",
  semanticRubric: [
    "The answer treats the sufficiently specified request as concrete product/recommendation intent and uses the current provider without requiring an extra request to check the market or branch.",
    "The final recommendation is grounded in a fitting product verified by the local provider tool.",
    "No unnecessary clarification is required because the decision-critical product parameters are already specified.",
  ],
};

export const BEHAVIOR_REGRESSION_SCENARIOS: readonly BehaviorScenario[] = [
  ...BEHAVIOR_SCENARIOS,
  PRODUCT_INTENT_SCENARIO,
];

export function behaviorScenario(
  id: BehaviorScenarioId,
): BehaviorScenario {
  const scenario = BEHAVIOR_REGRESSION_SCENARIOS.find(
    (candidate) => candidate.id === id,
  );
  if (!scenario) {
    throw new Error(`Unknown behavior scenario: ${id}`);
  }
  return scenario;
}

export function behaviorScenarios(
  provider: BehaviorProvider,
): BehaviorScenario[] {
  return BEHAVIOR_REGRESSION_SCENARIOS.map((scenario) =>
    behaviorScenarioForProvider(scenario.id, provider),
  );
}

export function behaviorScenarioForProvider(
  id: BehaviorScenarioId,
  provider: BehaviorProvider,
): BehaviorScenario {
  const scenario = behaviorScenario(id);
  if (provider === "obi-v2") return scenario;

  const articleNumber = kwantArticleNumber(id);
  if (!articleNumber) return { ...scenario };
  return {
    ...scenario,
    name: scenario.name.replace("OBIK", "KWANT article number"),
    userMessage: scenario.userMessage.replace(
      /OBIK\s+700000[1-5]/,
      `numeru artykułu ${articleNumber}`,
    ),
    semanticRubric: [
      ...scenario.semanticRubric,
      ...(id === "F"
        ? [
            "The mocked KWANT price has online scope, so the answer describes it as an online price and does not claim it is a branch, counter, negotiated, or customer-specific price.",
          ]
        : []),
    ],
  };
}

function providerContext(
  provider: BehaviorProvider,
): BehaviorProviderContext {
  return provider === "obi-v2"
    ? {
        provider,
        providerId: "obi-pl",
        branchId: DEFAULT_EVAL_STORE_NUMBER,
      }
    : {
        provider,
        providerId: "kwant-pl",
        branchId: DEFAULT_EVAL_KWANT_BRANCH_ID,
      };
}

function kwantArticleNumber(
  id: BehaviorScenarioId,
): string | null {
  switch (id) {
    case "F":
      return "MBN116E/HAG";
    case "G_ZERO":
      return "EVAL-KW-0002";
    case "G_NULL":
      return "EVAL-KW-0003";
    case "G_NOT_FOUND":
      return "EVAL-KW-0004";
    case "G_UNAVAILABLE":
      return "EVAL-KW-0005";
    default:
      return null;
  }
}

export function createProductionAdvisorDriver(
  apiKey: string,
  upstreamFetch: UpstreamFetch,
  provider: BehaviorProvider = "obi-v2",
): AdvisorDriver {
  const context = providerContext(provider);
  return {
    start(message, branchId) {
      return startAgent(
        message,
        context.providerId,
        branchId,
        apiKey,
        upstreamFetch,
        provider === "obi-v2"
          ? OBI_GROUPED_ADVISOR_PROTOCOL_VERSION
          : CURRENT_ADVISOR_PROTOCOL_VERSION,
      );
    },
    continueTurn(responseId, callId, branchId, result) {
      return continueAgent(
        responseId,
        callId,
        context.providerId,
        branchId,
        result,
        apiKey,
        upstreamFetch,
        provider === "obi-v2"
          ? OBI_GROUPED_ADVISOR_PROTOCOL_VERSION
          : CURRENT_ADVISOR_PROTOCOL_VERSION,
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
      const requestBody = JSON.stringify({
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
          localToolCalls: input.trace.localProductCalls.map(
            (call) => ({
              arguments: call.arguments,
              mockedResult: call.mockedResult,
            }),
          ),
        }),
        reasoning: {
          effort: OPENAI_REASONING_EFFORT,
        },
        max_output_tokens: OPENAI_MAX_OUTPUT_TOKENS,
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
      });

      let lastOutputError: SemanticGraderOutputError | null = null;
      for (let attempt = 1; attempt <= 2; attempt += 1) {
        const response = await upstreamFetch(OPENAI_RESPONSES_URL, {
          method: "POST",
          headers: {
            Authorization: `Bearer ${apiKey}`,
            "Content-Type": "application/json",
          },
          body: requestBody,
        });

        if (!response.ok) {
          throw new Error(
            `Semantic grader request failed with HTTP ${response.status}`,
          );
        }

        let payload: unknown;
        try {
          payload = (await response.json()) as unknown;
        } catch {
          lastOutputError = new SemanticGraderOutputError(
            "Semantic grader returned an unreadable response payload",
          );
          if (attempt === 1) continue;
          throw lastOutputError;
        }

        try {
          return parseSemanticGrade(payload);
        } catch (error) {
          if (!(error instanceof SemanticGraderOutputError)) {
            throw error;
          }
          lastOutputError = error;
          if (attempt === 1) continue;
          throw lastOutputError;
        }
      }

      throw (
        lastOutputError ??
        new SemanticGraderOutputError(
          "Semantic grader output could not be consumed",
        )
      );
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

  const provider = options.provider ?? "obi-v2";
  const scenarios = behaviorScenarios(provider);
  const selected = options.scenarioIds?.length
    ? options.scenarioIds.map((id) =>
        behaviorScenarioForProvider(id, provider),
      )
    : scenarios;

  const results: BehaviorTrialResult[] = [];
  for (const scenario of selected) {
    for (let trial = 1; trial <= trials; trial += 1) {
      results.push(
        await runBehaviorTrial(
          scenario,
          trial,
          options.driverFactory(scenario, trial),
          options.semanticJudge,
          provider,
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
  provider: BehaviorProvider = "obi-v2",
): Promise<BehaviorTrialResult> {
  const context = providerContext(provider);
  const trace: BehaviorTrace = {
    provider,
    scenario: scenario.id,
    trial,
    userMessage: scenario.userMessage,
    modelOutputs: [],
    clarificationOrFinalAnswer: null,
    localProductCalls: [],
    webSearchCount: 0,
    mockedToolResults: [],
    finalProductRefs: [],
  };

  let result: AgentResult;
  try {
    result = await driver.start(
      scenario.userMessage,
      context.branchId,
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
          (scenario.id === "D" ||
            scenario.id === "H_AMBIGUOUS") &&
          trace.localProductCalls.length === 0
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
    const callOrder = trace.localProductCalls.length + 1;
    const executedMockProvider =
      callOrder <= PRODUCTION_LOCAL_TOOL_LIMIT;
    const mockedResult = executedMockProvider
      ? mockToolResultForScenario(scenario, args, callOrder, context)
      : {
          ...(provider === "obi-v2"
            ? { storeNumber: context.branchId }
            : {
                providerId: context.providerId,
                branchId: context.branchId,
              }),
          queries: args.queries,
          rejection: "local_tool_limit_reached" as const,
        };

    trace.localProductCalls.push({
      order: callOrder,
      arguments: args,
      mockedResult,
      executedMockProvider,
    });
    trace.mockedToolResults.push(mockedResult);

    try {
      result = await driver.continueTurn(
        result.responseId,
        result.tool.callId,
        context.branchId,
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
    "provider\tscenario\ttrial\tresult\tlocal_calls\tweb_searches\treason",
  ];
  for (const result of results) {
    lines.push(
      [
        result.trace.provider,
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
  const failedNames = uniqueScenarioNames(results, "FAIL");
  const erroredNames = uniqueScenarioNames(results, "ERROR");

  lines.push("");
  lines.push(`passed: ${passed} / ${results.length}`);
  lines.push(
    `failed scenarios: ${failedNames.length ? failedNames.join(", ") : "none"}`,
  );
  lines.push(
    `errored scenarios: ${erroredNames.length ? erroredNames.join(", ") : "none"}`,
  );
  return lines.join("\n");
}

export function behaviorSuiteExitCode(
  results: readonly BehaviorTrialResult[],
): number {
  return results.some((result) => result.status !== "PASS") ? 1 : 0;
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
): EvalToolArguments {
  const args = result.tool.arguments;
  if (
    !("queries" in args) ||
    !Array.isArray(args.queries) ||
    (!("storeNumber" in args) &&
      !("providerId" in args && "branchId" in args))
  ) {
    throw new Error(
      "Behavior eval requires the grouped v2 or provider v3 advisor tool contract",
    );
  }
  return "storeNumber" in args
    ? {
        storeNumber: args.storeNumber,
        queries: args.queries.map((query) => ({ ...query })),
      }
    : {
        providerId: args.providerId,
        branchId: args.branchId,
        ...(args.requestedBranch !== undefined
          ? { requestedBranch: args.requestedBranch }
          : {}),
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
      localToolCallCount: trace.localProductCalls.length,
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
        status: "ERROR",
        localToolCallCount: trace.localProductCalls.length,
        webSearchCount: trace.webSearchCount,
        reason: `semantic grader infrastructure failed: ${errorMessage(error)}`,
        trace,
      };
    }

    if (!semantic.pass) {
      return {
        scenario: scenario.id,
        scenarioName: scenario.name,
        trial,
        status: "FAIL",
        localToolCallCount: trace.localProductCalls.length,
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
      localToolCallCount: trace.localProductCalls.length,
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
    localToolCallCount: trace.localProductCalls.length,
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

  if (
    scenario.webPolicy === "forbidden" &&
    trace.webSearchCount > 0
  ) {
    failures.push(
      "web search was used even though this scenario forbids it",
    );
  }
  if (
    scenario.webPolicy === "required" &&
    trace.webSearchCount === 0
  ) {
    failures.push(
      "web search was required by this scenario but was not used",
    );
  }

  const verifiedRefs = collectVerifiedRefs(
    trace.mockedToolResults,
  );
  for (const ref of trace.finalProductRefs) {
    if (!verifiedRefs.has(refKey(ref))) {
      failures.push(
        `ungrounded productRef ${refKey(ref)}`,
      );
    }
  }

  const duplicate = duplicateNormalizedQuery(
    trace.localProductCalls,
  );
  if (duplicate) {
    failures.push(`redundant repeated query: ${duplicate}`);
  }

  const calls = trace.localProductCalls;
  const firstCall = calls[0];
  const firstQueries = firstCall?.arguments.queries ?? [];

  switch (scenario.id) {
    case "A":
      if (calls.length === 0) {
        failures.push(
          "broad concrete product request did not use the local provider",
        );
      }
      if (!firstQueries.some((query) => query.limit > 1)) {
        failures.push(
          "broad concrete product lookup did not request multiple variants",
        );
      }
      if (verifiedRefs.size < 2) {
        failures.push(
          "broad concrete product lookup did not yield multiple relevant verified variants",
        );
      }
      break;

    case "D":
    case "H_AMBIGUOUS":
      if (calls.length !== 0) {
        failures.push(
          "local provider lookup occurred before clarification",
        );
      }
      if (trace.finalProductRefs.length !== 0) {
        failures.push(
          "concrete productRef returned before clarification",
        );
      }
      break;

    case "B":
      if (calls.length === 0) {
        failures.push("browse request did not use the local provider");
      }
      if (!firstQueries.some((query) => query.limit > 1)) {
        failures.push(
          "browse lookup did not request multiple results",
        );
      }
      if (verifiedRefs.size < 2) {
        failures.push(
          "browse lookup did not yield multiple relevant verified variants",
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
          (query) => hasCableTieDimension42x380(query),
        )
      ) {
        failures.push(
          "verification query did not preserve the specified 4.2 x 380 mm size",
        );
      }
      if (
        !verifiedRefs.has(
          expectedRefKey(trace.provider, "6100002"),
        )
      ) {
        failures.push(
          "specified cable-tie lookup did not yield the intended verified variant",
        );
      }
      break;

    case "PRODUCT_INTENT":
      if (calls.length === 0) {
        failures.push(
          "concrete product recommendation did not use the current provider",
        );
      }
      if (
        !verifiedRefs.has(
          expectedRefKey(trace.provider, "7300001"),
        )
      ) {
        failures.push(
          "concrete product recommendation did not yield the intended verified product",
        );
      }
      break;

    case "E":
      if (calls.length === 0) {
        failures.push(
          "variant comparison did not use verified provider data",
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
      if (
        flattenQueries(calls).length !== 1 ||
        !hasExpectedIdentifier(
          flattenQueries(calls)[0] ?? "",
          trace.provider,
          "F",
        )
      ) {
        failures.push(
          "direct identifier lookup was broadened into unrelated searches",
        );
      }
      break;

    case "G_ZERO":
      requireIdentifierVerification(calls, trace.provider, "G_ZERO", failures);
      break;
    case "G_NULL":
      requireIdentifierVerification(calls, trace.provider, "G_NULL", failures);
      break;
    case "G_NOT_FOUND":
      requireIdentifierVerification(calls, trace.provider, "G_NOT_FOUND", failures);
      break;
    case "G_UNAVAILABLE":
      requireIdentifierVerification(calls, trace.provider, "G_UNAVAILABLE", failures);
      break;

    case "H":
      if (calls.length !== 0) {
        failures.push(
          "understood job advice used automatic local provider lookup without explicit provider intent",
        );
      }
      if (trace.finalProductRefs.length !== 0) {
        failures.push(
          "understood job advice unexpectedly returned productRefs",
        );
      }
      break;

    case "I":
      if (calls.length !== 0) {
        failures.push(
          "general SDS question used unnecessary local provider lookup",
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

function requireIdentifierVerification(
  calls: readonly LocalToolCallTrace[],
  provider: BehaviorProvider,
  scenarioId: BehaviorScenarioId,
  failures: string[],
): void {
  const queries = flattenQueries(calls);
  if (calls.length === 0) {
    failures.push("availability check did not use the local provider");
    return;
  }
  if (!queries.some((query) =>
    hasExpectedIdentifier(query, provider, scenarioId),
  )) {
    const identifier = provider === "obi-v2"
      ? obiIdentifier(scenarioId)
      : kwantArticleNumber(scenarioId);
    failures.push(
      `availability lookup did not preserve identifier ${identifier}`,
    );
  }
}

function hasExpectedIdentifier(
  query: string,
  provider: BehaviorProvider,
  scenarioId: BehaviorScenarioId,
): boolean {
  const identifier = provider === "obi-v2"
    ? obiIdentifier(scenarioId)
    : kwantArticleNumber(scenarioId);
  return identifier !== null && normalizeQuery(query).includes(
    normalizeQuery(identifier),
  );
}

function obiIdentifier(id: BehaviorScenarioId): string | null {
  const suffix: Partial<Record<BehaviorScenarioId, string>> = {
    F: "7000001",
    G_ZERO: "7000002",
    G_NULL: "7000003",
    G_NOT_FOUND: "7000004",
    G_UNAVAILABLE: "7000005",
  };
  return suffix[id] ?? null;
}

function mockToolResultForScenario(
  scenario: BehaviorScenario,
  args: EvalToolArguments,
  callOrder: number,
  context: BehaviorProviderContext,
): VerifiedToolResult | import("../types.js").ProviderVerifiedToolResult {
  const results = args.queries.map((query, queryIndex) =>
      mockQueryResult(
        scenario.id,
        query.query,
        query.limit,
        callOrder,
        queryIndex,
        context.provider,
      ),
    );
  if (context.provider === "obi-v2") {
    return {
      storeNumber: context.branchId,
      results: results as VerifiedQueryResult[],
    };
  }
  return {
    providerId: context.providerId,
    branchId: context.branchId,
    results: results as ProviderVerifiedQueryResult[],
  };
}

function mockQueryResult(
  scenarioId: BehaviorScenarioId,
  query: string,
  limit: number,
  _callOrder: number,
  _queryIndex: number,
  provider: BehaviorProvider,
): VerifiedQueryResult | ProviderVerifiedQueryResult {
  const obiResult = mockObiQueryResult(scenarioId, query, limit);
  return provider === "obi-v2"
    ? obiResult
    : providerQueryResult(obiResult);
}

function mockObiQueryResult(
  scenarioId: BehaviorScenarioId,
  query: string,
  limit: number,
): VerifiedQueryResult {
  switch (scenarioId) {
    case "A":
      return isBlackCableTieQuery(query)
        ? verifiedQuery(
            query,
            ZIP_TIES.slice(0, Math.min(limit, ZIP_TIES.length)),
          )
        : notFoundQuery(query);

    case "B":
      return isBlackCableTieQuery(query)
        ? verifiedQuery(
            query,
            ZIP_TIES.slice(0, Math.min(limit, ZIP_TIES.length)),
          )
        : notFoundQuery(query);

    case "C":
      return isSpecifiedBlackCableTieQuery(query)
        ? verifiedQuery(
            query,
            [ZIP_TIES[1]].slice(0, limit),
          )
        : notFoundQuery(query);

    case "E":
      return isBlackCableTieQuery(query)
        ? verifiedQuery(
            query,
            INDOOR_ZIP_TIES.slice(
              0,
              Math.min(limit, INDOOR_ZIP_TIES.length),
            ),
          )
        : notFoundQuery(query);

    case "PRODUCT_INTENT":
      return isConcreteBreakerRecommendationQuery(query)
        ? verifiedQuery(
            query,
            [RECOMMENDED_BREAKER].slice(0, limit),
          )
        : notFoundQuery(query);

    case "F":
      return hasExpectedIdentifier(query, "obi-v2", "F") ||
        hasExpectedIdentifier(query, "kwant-v3", "F")
        ? verifiedQuery(
            query,
            [DIRECT_PRODUCT].slice(0, limit),
          )
        : notFoundQuery(query);

    case "G_ZERO":
      return hasExpectedIdentifier(query, "obi-v2", "G_ZERO") ||
        hasExpectedIdentifier(query, "kwant-v3", "G_ZERO")
        ? verifiedQuery(
            query,
            [ZERO_STOCK_PRODUCT].slice(0, limit),
          )
        : notFoundQuery(query);

    case "G_NULL":
      return hasExpectedIdentifier(query, "obi-v2", "G_NULL") ||
        hasExpectedIdentifier(query, "kwant-v3", "G_NULL")
        ? verifiedQuery(
            query,
            [UNKNOWN_STOCK_PRODUCT].slice(0, limit),
          )
        : notFoundQuery(query);

    case "G_NOT_FOUND":
      return notFoundQuery(query);

    case "G_UNAVAILABLE":
      return hasExpectedIdentifier(query, "obi-v2", "G_UNAVAILABLE") ||
        hasExpectedIdentifier(query, "kwant-v3", "G_UNAVAILABLE")
        ? {
            query,
            status: "unavailable",
            products: [],
          }
        : notFoundQuery(query);

    case "H": {
      const normalized = normalizeQuery(query);
      if (isSanitarySiliconeQuery(normalized)) {
        return verifiedQuery(
          query,
          [KIT_PRODUCTS[0]].slice(0, limit),
        );
      }
      if (isCartridgeGunQuery(normalized)) {
        return verifiedQuery(
          query,
          [KIT_PRODUCTS[1]].slice(0, limit),
        );
      }
      if (isSiliconeFinishingToolQuery(normalized)) {
        return verifiedQuery(
          query,
          [KIT_PRODUCTS[2]].slice(0, limit),
        );
      }
      return notFoundQuery(query);
    }

    case "D":
    case "H_AMBIGUOUS":
    case "I":
      return notFoundQuery(query);
  }
}

function notFoundQuery(query: string): VerifiedQueryResult {
  return {
    query,
    status: "not_found",
    products: [],
  };
}

function providerQueryResult(
  result: VerifiedQueryResult,
): ProviderVerifiedQueryResult {
  return {
    ...result,
    products: result.products.map(providerProduct),
  };
}

function providerProduct(
  value: VerifiedProduct,
): ProviderVerifiedProduct {
  const articleById: Record<string, string> = {
    "7000001": "MBN116E/HAG",
    "7300001": "MBN116E/HAG",
    "7000002": "EVAL-KW-0002",
    "7000003": "EVAL-KW-0003",
  };
  const directProduct = value.obik === "7000001";
  return {
    productId: `kw-${value.obik}`,
    articleNumber: articleById[value.obik] ?? `KW-${value.obik}`,
    name: directProduct
      ? "Wyłącznik nadprądowy B16 A 1P 6kA MBN116E Hager"
      : value.name.replace("Mock", "KWANT mock"),
    brand: directProduct ? "Hager" : value.brand,
    shortDescription: value.shortDescription,
    technicalFacts: directProduct
      ? [
          { label: "Charakterystyka", value: "B" },
          { label: "Prąd znamionowy", value: "16 A" },
          { label: "Liczba biegunów", value: "1" },
        ]
      : value.technicalFacts,
    stock: value.stock,
    centralStock: null,
    price: value.price,
    priceScope: "online",
  };
}

function isConcreteBreakerRecommendationQuery(
  query: string,
): boolean {
  const normalized = normalizeQuery(query);
  return (
    /\b(wyłącznik|wylacznik|nadprądow|nadpradow|eska)\w*/.test(
      normalized,
    ) &&
    /\bb\s*16\b/.test(normalized) &&
    /\b1\s*p\b/.test(normalized) &&
    /\b6\s*ka\b/.test(normalized)
  );
}

function isBlackCableTieQuery(query: string): boolean {
  const normalized = normalizeQuery(query);
  return (
    /\b(czarn\w*)\b/.test(normalized) &&
    /\b(trytyt\w*|opask\w*)\b/.test(normalized)
  );
}

function isSpecifiedBlackCableTieQuery(
  query: string,
): boolean {
  return (
    isBlackCableTieQuery(query) &&
    hasCableTieDimension42x380(query)
  );
}

function hasCableTieDimension42x380(
  query: string,
): boolean {
  return (
    /4\s*[,.]\s*2/.test(query) &&
    /(?:^|\D)380(?:\D|$)/.test(query)
  );
}

function hasExactObik(
  query: string,
  obik: string,
): boolean {
  return new RegExp(
    `(^|\\D)${obik}(\\D|$)`,
  ).test(query);
}

function isSanitarySiliconeQuery(
  normalized: string,
): boolean {
  return (
    /\bsilikon\w*\b/.test(normalized) &&
    /\b(sanit\w*|umywal\w*|uszczeln\w*)\b/.test(
      normalized,
    )
  );
}

function isCartridgeGunQuery(
  normalized: string,
): boolean {
  return (
    /\b(pistolet\w*|wycisk\w*)\b/.test(normalized) &&
    /\b(kartusz\w*|silikon\w*)\b/.test(normalized)
  );
}

function isSiliconeFinishingToolQuery(
  normalized: string,
): boolean {
  return (
    /\b(gladz\w*|profil\w*|narzedz\w*)\b/.test(
      normalized,
    ) &&
    /\bsilikon\w*\b/.test(normalized)
  );
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
        if ("storeNumber" in result && "obik" in productValue) {
          refs.add(refKey({
            storeNumber: result.storeNumber,
            obik: productValue.obik,
          }));
        } else if (
          "providerId" in result &&
          "productId" in productValue
        ) {
          refs.add(refKey({
            providerId: result.providerId,
            branchId: result.branchId,
            productId: productValue.productId,
          }));
        }
      }
    }
  }
  return refs;
}

function expectedRefKey(
  provider: BehaviorProvider,
  fixtureId: string,
): string {
  return provider === "obi-v2"
    ? refKey({
        storeNumber: DEFAULT_EVAL_STORE_NUMBER,
        obik: fixtureId,
      })
    : refKey({
        providerId: "kwant-pl",
        branchId: DEFAULT_EVAL_KWANT_BRANCH_ID,
        productId: `kw-${fixtureId}`,
      });
}

function refKey(ref: ProductRef): string {
  return "storeNumber" in ref
    ? `${ref.storeNumber}:${ref.obik}`
    : `${ref.providerId}:${ref.branchId}:${ref.productId}`;
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
    status: "ERROR",
    localToolCallCount: trace.localProductCalls.length,
    webSearchCount: trace.webSearchCount,
    reason: compactReason(reason),
    trace,
  };
}

class SemanticGraderOutputError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "SemanticGraderOutputError";
  }
}

function parseSemanticGrade(payload: unknown): SemanticGrade {
  if (
    isRecord(payload) &&
    payload.status === "incomplete" &&
    isRecord(payload.incomplete_details) &&
    payload.incomplete_details.reason === "max_output_tokens"
  ) {
    throw new SemanticGraderOutputError(
      "Semantic grader response was incomplete because max_output_tokens was reached",
    );
  }

  const raw = extractOutputText(payload);
  if (!raw) {
    throw new SemanticGraderOutputError(
      "Semantic grader returned no text",
    );
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw) as unknown;
  } catch {
    throw new SemanticGraderOutputError(
      "Semantic grader returned invalid JSON",
    );
  }

  if (
    !isRecord(parsed) ||
    typeof parsed.pass !== "boolean" ||
    typeof parsed.reason !== "string" ||
    parsed.reason.trim().length === 0
  ) {
    throw new SemanticGraderOutputError(
      "Semantic grader returned an invalid grade",
    );
  }

  return {
    pass: parsed.pass,
    reason: parsed.reason.trim().slice(0, 240),
  };
}

function uniqueScenarioNames(
  results: readonly BehaviorTrialResult[],
  status: BehaviorTrialResult["status"],
): string[] {
  return [
    ...new Map(
      results
        .filter((result) => result.status === status)
        .map((result) => [
          result.scenario,
          `${result.scenario} (${result.scenarioName})`,
        ]),
    ).values(),
  ];
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
