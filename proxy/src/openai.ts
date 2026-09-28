import {
  agentInstructionsForStore,
  CURRENT_MODEL_PRICING,
  FINAL_ANSWER_FORMAT,
  LOCAL_TOOL_NAME,
  MAX_ANSWER_CHARS,
  MAX_MODEL_NAME_CHARS,
  MAX_SELECTED_PRODUCT_REFS,
  MAX_USAGE_TOKEN_COUNT,
  MAX_WEB_CITATIONS,
  MAX_WEB_CITATION_TITLE_CHARS,
  MAX_WEB_CITATION_URL_CHARS,
  MAX_WEB_SEARCH_CALLS_PER_RESPONSE,
  OPENAI_MAX_OUTPUT_TOKENS,
  OPENAI_MODEL,
  OPENAI_REASONING_EFFORT,
  OPENAI_RESPONSES_URL,
  OBI_TOOL,
  WEB_SEARCH_TOOL,
} from "./config.js";
import { InvalidRequestError, parseToolArguments } from "./validation.js";
import type {
  AgentRequestType,
  AgentResult,
  AgentUsage,
  ProductRef,
  ToolContinuationResult,
  WebSource,
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
      tool_choice: "auto",
      max_tool_calls: MAX_WEB_SEARCH_CALLS_PER_RESPONSE,
      store: true,
      text: {
        format: FINAL_ANSWER_FORMAT,
      },
      tools: [OBI_TOOL, WEB_SEARCH_TOOL],
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
      tool_choice: "auto",
      max_tool_calls: MAX_WEB_SEARCH_CALLS_PER_RESPONSE,
      store: true,
      text: {
        format: FINAL_ANSWER_FORMAT,
      },
      tools: [OBI_TOOL, WEB_SEARCH_TOOL],
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
      tool_choice: "auto",
      max_tool_calls: MAX_WEB_SEARCH_CALLS_PER_RESPONSE,
      store: true,
      text: {
        format: FINAL_ANSWER_FORMAT,
      },
      tools: [OBI_TOOL, WEB_SEARCH_TOOL],
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
  if (!Array.isArray(payload.output)) {
    throw new UpstreamFailureError();
  }

  const webSearchCalls = countCompletedWebSearchCalls(payload.output);
  const usage = parseUsage(payload, requestType, webSearchCalls);
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
  const sources = extractWebSources(payload.output);

  return {
    type: "answer",
    responseId,
    text: answer.text,
    productRefs: answer.productRefs,
    sources,
    ...(usage ? { usage } : {}),
  };
}

function parseUsage(
  payload: Record<string, unknown>,
  requestType: AgentRequestType,
  webSearchCalls: number,
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
    const cacheWriteTokens = optionalNestedTokenCount(
      rawUsage.input_tokens_details,
      "cache_write_tokens",
    );
    if (
      (cachedInputTokens !== null &&
        cachedInputTokens > inputTokens) ||
      (cacheWriteTokens !== null &&
        cacheWriteTokens > inputTokens) ||
      (cachedInputTokens !== null &&
        cacheWriteTokens !== null &&
        cachedInputTokens + cacheWriteTokens > inputTokens)
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
      cacheWriteTokens,
      outputTokens,
      webSearchCalls,
    );

    return {
      model,
      requestType,
      inputTokens,
      cachedInputTokens,
      cacheWriteTokens,
      outputTokens,
      reasoningTokens,
      totalTokens,
      webSearchCalls,
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
  cacheWriteTokens: number | null,
  outputTokens: number,
  webSearchCalls: number,
): { estimatedCostUsd: number; pricingVersion: string } | null {
  if (
    model !== CURRENT_MODEL_PRICING.model ||
    cachedInputTokens === null ||
    cacheWriteTokens === null ||
    cachedInputTokens + cacheWriteTokens > inputTokens ||
    webSearchCalls < 0 ||
    webSearchCalls > MAX_WEB_SEARCH_CALLS_PER_RESPONSE
  ) {
    return null;
  }

  const ordinaryInputTokens =
    inputTokens - cachedInputTokens - cacheWriteTokens;
  const rates =
    inputTokens > CURRENT_MODEL_PRICING.longContextInputThreshold
      ? CURRENT_MODEL_PRICING.longContextNanoUsdPerToken
      : CURRENT_MODEL_PRICING.nanoUsdPerToken;
  const nanoUsd =
    BigInt(ordinaryInputTokens) * rates.uncachedInput +
    BigInt(cachedInputTokens) * rates.cachedInput +
    BigInt(cacheWriteTokens) * rates.cacheWriteInput +
    BigInt(outputTokens) * rates.output +
    BigInt(webSearchCalls) *
      CURRENT_MODEL_PRICING.webSearchNanoUsdPerCall;

  return {
    estimatedCostUsd: Number(formatNanoUsd(nanoUsd)),
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

function countCompletedWebSearchCalls(
  output: unknown[],
): number {
  let count = 0;
  for (const item of output) {
    if (
      !isRecord(item) ||
      item.type !== "web_search_call" ||
      item.status !== "completed" ||
      !isRecord(item.action) ||
      item.action.type !== "search"
    ) {
      continue;
    }
    count += 1;
  }
  return count;
}

function extractWebSources(output: unknown[]): WebSource[] {
  const sources: WebSource[] = [];
  const seen = new Set<string>();

  for (const item of output) {
    if (!isRecord(item) || item.type !== "message" || !Array.isArray(item.content)) {
      continue;
    }
    for (const content of item.content) {
      if (
        !isRecord(content) ||
        content.type !== "output_text" ||
        !Array.isArray(content.annotations)
      ) {
        continue;
      }
      for (const annotation of content.annotations) {
        if (
          !isRecord(annotation) ||
          annotation.type !== "url_citation" ||
          typeof annotation.url !== "string" ||
          typeof annotation.title !== "string"
        ) {
          continue;
        }
        const url = normalizeHttpsUrl(annotation.url);
        if (!url || seen.has(url)) continue;
        const title = annotation.title
          .replace(/\s+/g, " ")
          .trim()
          .slice(0, MAX_WEB_CITATION_TITLE_CHARS);
        if (!title) continue;
        seen.add(url);
        sources.push({ title, url });
        if (sources.length >= MAX_WEB_CITATIONS) {
          return sources;
        }
      }
    }
  }
  return sources;
}

function normalizeHttpsUrl(raw: string): string | null {
  if (raw.length === 0 || raw.length > MAX_WEB_CITATION_URL_CHARS) {
    return null;
  }
  try {
    const url = new URL(raw);
    if (url.protocol !== "https:") return null;
    return url.toString();
  } catch {
    return null;
  }
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
