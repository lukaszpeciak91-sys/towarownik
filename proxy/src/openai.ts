import {
  agentInstructionsForStore,
  CURRENT_MODEL_PRICING,
  FINAL_ANSWER_FORMAT,
  LOCAL_TOOL_NAME,
  MAX_ANSWER_CHARS,
  MAX_MODEL_NAME_CHARS,
  MAX_SELECTED_PRODUCT_REFS,
  MAX_USAGE_TOKEN_COUNT,
  OPENAI_MAX_OUTPUT_TOKENS,
  OPENAI_MODEL,
  OPENAI_REASONING_EFFORT,
  OPENAI_RESPONSES_URL,
  OBI_TOOL,
} from "./config.js";
import { InvalidRequestError, parseToolArguments } from "./validation.js";
import type {
  AgentRequestType,
  AgentResult,
  AgentUsage,
  ProductRef,
  ToolContinuationResult,
  UpstreamFetch,
} from "./types.js";

export class UpstreamFailureError extends Error {}

export async function startAgent(
  message: string,
  storeNumber: string,
  apiKey: string,
  upstreamFetch: UpstreamFetch,
): Promise<AgentResult> {
  return requestOpenAI(
    {
      model: OPENAI_MODEL,
      instructions: agentInstructionsForStore(storeNumber),
      input: message,
      reasoning: {
        effort: OPENAI_REASONING_EFFORT,
      },
      max_output_tokens: OPENAI_MAX_OUTPUT_TOKENS,
      parallel_tool_calls: false,
      store: true,
      text: {
        format: FINAL_ANSWER_FORMAT,
      },
      tools: [OBI_TOOL],
    },
    apiKey,
    upstreamFetch,
    "START",
  );
}

export async function messageAgent(
  previousResponseId: string,
  message: string,
  storeNumber: string,
  apiKey: string,
  upstreamFetch: UpstreamFetch,
): Promise<AgentResult> {
  return requestOpenAI(
    {
      model: OPENAI_MODEL,
      instructions: agentInstructionsForStore(storeNumber),
      previous_response_id: previousResponseId,
      input: message,
      reasoning: {
        effort: OPENAI_REASONING_EFFORT,
      },
      max_output_tokens: OPENAI_MAX_OUTPUT_TOKENS,
      parallel_tool_calls: false,
      store: true,
      text: {
        format: FINAL_ANSWER_FORMAT,
      },
      tools: [OBI_TOOL],
    },
    apiKey,
    upstreamFetch,
    "MESSAGE",
  );
}

export async function continueAgent(
  responseId: string,
  callId: string,
  storeNumber: string,
  result: ToolContinuationResult,
  apiKey: string,
  upstreamFetch: UpstreamFetch,
): Promise<AgentResult> {
  return requestOpenAI(
    {
      model: OPENAI_MODEL,
      instructions: agentInstructionsForStore(storeNumber),
      previous_response_id: responseId,
      input: [
        {
          type: "function_call_output",
          call_id: callId,
          output: JSON.stringify(result),
        },
      ],
      reasoning: {
        effort: OPENAI_REASONING_EFFORT,
      },
      max_output_tokens: OPENAI_MAX_OUTPUT_TOKENS,
      parallel_tool_calls: false,
      store: true,
      text: {
        format: FINAL_ANSWER_FORMAT,
      },
      tools: [OBI_TOOL],
    },
    apiKey,
    upstreamFetch,
    "CONTINUE",
  );
}

async function requestOpenAI(
  body: unknown,
  apiKey: string,
  upstreamFetch: UpstreamFetch,
  requestType: AgentRequestType,
): Promise<AgentResult> {
  let response: Response;
  try {
    response = await upstreamFetch(OPENAI_RESPONSES_URL, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    });
  } catch {
    throw new UpstreamFailureError();
  }

  if (!response.ok) {
    throw new UpstreamFailureError();
  }

  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    throw new UpstreamFailureError();
  }

  return normalizeOpenAIResponse(payload, requestType);
}

export function normalizeOpenAIResponse(
  payload: unknown,
  requestType: AgentRequestType = "START",
): AgentResult {
  if (!isRecord(payload)) {
    throw new UpstreamFailureError();
  }

  const responseId = boundedUpstreamId(payload.id);
  const usage = parseUsage(payload, requestType);
  if (!Array.isArray(payload.output)) {
    throw new UpstreamFailureError();
  }

  const functionCalls = payload.output.filter(
    (item): item is Record<string, unknown> =>
      isRecord(item) && item.type === "function_call",
  );

  if (functionCalls.length > 1) {
    throw new UpstreamFailureError();
  }

  if (functionCalls.length === 1) {
    const call = functionCalls[0];
    if (
      call.name !== LOCAL_TOOL_NAME ||
      typeof call.call_id !== "string" ||
      !call.call_id ||
      call.call_id.length > 256 ||
      typeof call.arguments !== "string"
    ) {
      throw new UpstreamFailureError();
    }

    let argumentsValue;
    try {
      argumentsValue = parseToolArguments(call.arguments);
    } catch (error) {
      if (error instanceof InvalidRequestError) {
        throw new UpstreamFailureError();
      }
      throw error;
    }

    return {
      type: "tool_request",
      responseId,
      tool: {
        name: LOCAL_TOOL_NAME,
        callId: call.call_id,
        arguments: argumentsValue,
      },
      ...(usage ? { usage } : {}),
    };
  }

  const answer = parseStructuredAnswer(extractAnswerText(payload));

  return {
    type: "answer",
    responseId,
    text: answer.text,
    productRefs: answer.productRefs,
    ...(usage ? { usage } : {}),
  };
}

function parseUsage(
  payload: Record<string, unknown>,
  requestType: AgentRequestType,
): AgentUsage | undefined {
  try {
    const model = boundedModelName(payload.model);
    const rawUsage = payload.usage;
    if (!isRecord(rawUsage)) return undefined;

    const inputTokens = boundedTokenCount(rawUsage.input_tokens);
    const outputTokens = boundedTokenCount(rawUsage.output_tokens);
    const totalTokens = boundedTokenCount(rawUsage.total_tokens);

    const cachedInputTokens = optionalNestedTokenCount(
      rawUsage.input_tokens_details,
      "cached_tokens",
    );
    if (
      cachedInputTokens !== null &&
      cachedInputTokens > inputTokens
    ) {
      return undefined;
    }

    const reasoningTokens = optionalNestedTokenCount(
      rawUsage.output_tokens_details,
      "reasoning_tokens",
    );
    if (
      reasoningTokens !== null &&
      reasoningTokens > outputTokens
    ) {
      return undefined;
    }

    const pricing = priceUsage(
      model,
      inputTokens,
      cachedInputTokens,
      outputTokens,
    );

    return {
      model,
      requestType,
      inputTokens,
      cachedInputTokens,
      outputTokens,
      reasoningTokens,
      totalTokens,
      estimatedCostUsd: pricing?.estimatedCostUsd ?? null,
      pricingVersion: pricing?.pricingVersion ?? null,
    };
  } catch {
    return undefined;
  }
}

function priceUsage(
  model: string,
  inputTokens: number,
  cachedInputTokens: number | null,
  outputTokens: number,
): { estimatedCostUsd: string; pricingVersion: string } | null {
  if (
    model !== CURRENT_MODEL_PRICING.model ||
    cachedInputTokens === null
  ) {
    return null;
  }

  const uncachedInputTokens = inputTokens - cachedInputTokens;
  const nanoUsd =
    BigInt(uncachedInputTokens) *
      CURRENT_MODEL_PRICING.nanoUsdPerToken.uncachedInput +
    BigInt(cachedInputTokens) *
      CURRENT_MODEL_PRICING.nanoUsdPerToken.cachedInput +
    BigInt(outputTokens) *
      CURRENT_MODEL_PRICING.nanoUsdPerToken.output;

  return {
    estimatedCostUsd: formatNanoUsd(nanoUsd),
    pricingVersion: CURRENT_MODEL_PRICING.pricingVersion,
  };
}

function formatNanoUsd(nanoUsd: bigint): string {
  const whole = nanoUsd / 1_000_000_000n;
  const fraction = (nanoUsd % 1_000_000_000n)
    .toString()
    .padStart(9, "0")
    .replace(/0+$/, "");
  return fraction ? `${whole}.${fraction}` : whole.toString();
}

function optionalNestedTokenCount(
  value: unknown,
  key: string,
): number | null {
  if (value === undefined || value === null) return null;
  if (!isRecord(value)) throw new Error("Invalid token details");
  if (!(key in value)) return null;
  return boundedTokenCount(value[key]);
}

function boundedTokenCount(value: unknown): number {
  if (
    typeof value !== "number" ||
    !Number.isSafeInteger(value) ||
    value < 0 ||
    value > MAX_USAGE_TOKEN_COUNT
  ) {
    throw new Error("Invalid token count");
  }
  return value;
}

function boundedModelName(value: unknown): string {
  if (
    typeof value !== "string" ||
    value.length === 0 ||
    value.length > MAX_MODEL_NAME_CHARS ||
    !/^[A-Za-z0-9._-]+$/.test(value)
  ) {
    throw new Error("Invalid model");
  }
  return value;
}

function parseStructuredAnswer(
  raw: string | null,
): {
  text: string;
  productRefs: ProductRef[];
} {
  if (!raw) {
    throw new UpstreamFailureError();
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw) as unknown;
  } catch {
    throw new UpstreamFailureError();
  }

  if (!isRecord(parsed)) {
    throw new UpstreamFailureError();
  }

  const keys = Object.keys(parsed);
  if (
    keys.length !== 2 ||
    !keys.includes("text") ||
    !keys.includes("productRefs")
  ) {
    throw new UpstreamFailureError();
  }

  if (typeof parsed.text !== "string") {
    throw new UpstreamFailureError();
  }
  const text = parsed.text.trim();
  if (!text || text.length > MAX_ANSWER_CHARS) {
    throw new UpstreamFailureError();
  }

  if (
    !Array.isArray(parsed.productRefs) ||
    parsed.productRefs.length > MAX_SELECTED_PRODUCT_REFS
  ) {
    throw new UpstreamFailureError();
  }

  const deduplicated: ProductRef[] = [];
  const seen = new Set<string>();
  for (const value of parsed.productRefs) {
    if (!isRecord(value)) {
      throw new UpstreamFailureError();
    }
    const refKeys = Object.keys(value);
    if (
      refKeys.length !== 2 ||
      !refKeys.includes("storeNumber") ||
      !refKeys.includes("obik") ||
      typeof value.storeNumber !== "string" ||
      !/^\d{3}$/.test(value.storeNumber) ||
      typeof value.obik !== "string" ||
      !/^\d{7}$/.test(value.obik)
    ) {
      throw new UpstreamFailureError();
    }
    const key = value.storeNumber + ":" + value.obik;
    if (!seen.has(key)) {
      seen.add(key);
      deduplicated.push({
        storeNumber: value.storeNumber,
        obik: value.obik,
      });
    }
  }

  return {
    text,
    productRefs: deduplicated,
  };
}

function extractAnswerText(payload: Record<string, unknown>): string | null {
  if (typeof payload.output_text === "string" && payload.output_text.trim()) {
    return payload.output_text.trim();
  }

  const output = payload.output;
  if (!Array.isArray(output)) return null;

  const parts: string[] = [];
  for (const item of output) {
    if (!isRecord(item) || item.type !== "message" || !Array.isArray(item.content)) {
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

  const joined = parts.join("").trim();
  return joined || null;
}

function boundedUpstreamId(value: unknown): string {
  if (typeof value !== "string" || !value || value.length > 256) {
    throw new UpstreamFailureError();
  }
  return value;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
