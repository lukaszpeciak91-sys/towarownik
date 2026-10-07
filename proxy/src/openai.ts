import {
  agentInstructionsForProfile,
  CURRENT_MODEL_PRICING,
  finalAnswerFormatForProtocol,
  LOCAL_TOOL_NAME,
  PROVIDER_LOCAL_TOOL_NAME,
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
  CURRENT_ADVISOR_PROTOCOL_VERSION,
  localToolForProtocol,
  WEB_SEARCH_TOOL,
} from "./config.js";
import { InvalidRequestError, parseToolArguments } from "./validation.js";
import type {
  AdvisorProtocolVersion,
  AdvisorAttachment,
  AgentRequestType,
  AgentResult,
  AgentUsage,
  ProductRef,
  ToolContinuationResult,
  WebSource,
  UpstreamFetch,
} from "./types.js";

export type UpstreamFailureCategory =
  | "upstream_network"
  | "upstream_http_4xx"
  | "upstream_rate_limit"
  | "upstream_http_5xx"
  | "upstream_invalid_json"
  | "upstream_invalid_envelope"
  | "upstream_invalid_tool_call"
  | "upstream_invalid_final_answer";

export class UpstreamFailureError extends Error {
  constructor(
    readonly category: UpstreamFailureCategory,
    readonly upstreamStatus: number | null = null,
  ) {
    super(category);
    this.name = "UpstreamFailureError";
  }
}

export async function startAgent(
  message: string,
  providerId: string,
  branchId: string,
  apiKey: string,
  upstreamFetch: UpstreamFetch,
  protocolVersion: AdvisorProtocolVersion =
    CURRENT_ADVISOR_PROTOCOL_VERSION,
  attachment?: AdvisorAttachment,
): Promise<AgentResult> {
  return requestOpenAI(
    {
      model: OPENAI_MODEL,
      instructions: agentInstructionsForProfile(
        providerId,
        branchId,
        protocolVersion,
      ),
      input: modelInput(message, attachment),
      reasoning: {
        effort: OPENAI_REASONING_EFFORT,
      },
      max_output_tokens: OPENAI_MAX_OUTPUT_TOKENS,
      parallel_tool_calls: false,
      tool_choice: "auto",
      max_tool_calls: MAX_WEB_SEARCH_CALLS_PER_RESPONSE,
      store: true,
      text: {
        format: finalAnswerFormatForProtocol(protocolVersion),
      },
      tools: [localToolForProtocol(protocolVersion), WEB_SEARCH_TOOL],
    },
    apiKey,
    upstreamFetch,
    "START",
    true,
    protocolVersion,
  );
}

export async function messageAgent(
  previousResponseId: string,
  message: string,
  providerId: string,
  branchId: string,
  apiKey: string,
  upstreamFetch: UpstreamFetch,
  protocolVersion: AdvisorProtocolVersion =
    CURRENT_ADVISOR_PROTOCOL_VERSION,
  attachment?: AdvisorAttachment,
): Promise<AgentResult> {
  return requestOpenAI(
    {
      model: OPENAI_MODEL,
      instructions: agentInstructionsForProfile(
        providerId,
        branchId,
        protocolVersion,
      ),
      previous_response_id: previousResponseId,
      input: modelInput(message, attachment),
      reasoning: {
        effort: OPENAI_REASONING_EFFORT,
      },
      max_output_tokens: OPENAI_MAX_OUTPUT_TOKENS,
      parallel_tool_calls: false,
      tool_choice: "auto",
      max_tool_calls: MAX_WEB_SEARCH_CALLS_PER_RESPONSE,
      store: true,
      text: {
        format: finalAnswerFormatForProtocol(protocolVersion),
      },
      tools: [localToolForProtocol(protocolVersion), WEB_SEARCH_TOOL],
    },
    apiKey,
    upstreamFetch,
    "MESSAGE",
    true,
    protocolVersion,
  );
}

function modelInput(message: string, attachment?: AdvisorAttachment): unknown {
  if (!attachment) return message;
  const dataUrl = `data:${attachment.mimeType};base64,${bytesToBase64(attachment.bytes)}`;
  const content: Record<string, unknown>[] = [];
  if (message) content.push({ type: "input_text", text: message });
  content.push(attachment.mimeType === "application/pdf"
    ? {
        type: "input_file",
        filename: attachment.filename,
        file_data: dataUrl,
      }
    : { type: "input_image", image_url: dataUrl, detail: "high" });
  return [{ role: "user", content }];
}

function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  const chunk = 0x8000;
  for (let offset = 0; offset < bytes.length; offset += chunk) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunk));
  }
  return btoa(binary);
}

export async function continueAgent(
  responseId: string,
  callId: string,
  providerId: string,
  branchId: string,
  result: ToolContinuationResult,
  apiKey: string,
  upstreamFetch: UpstreamFetch,
  protocolVersion: AdvisorProtocolVersion =
    CURRENT_ADVISOR_PROTOCOL_VERSION,
): Promise<AgentResult> {
  const localToolAvailable =
    !("rejection" in result) ||
    result.rejection !== "local_tool_limit_reached";

  return requestOpenAI(
    {
      model: OPENAI_MODEL,
      instructions: agentInstructionsForProfile(
        providerId,
        branchId,
        protocolVersion,
      ),
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
        format: finalAnswerFormatForProtocol(protocolVersion),
      },
      tools: localToolAvailable
        ? [localToolForProtocol(protocolVersion), WEB_SEARCH_TOOL]
        : [WEB_SEARCH_TOOL],
    },
    apiKey,
    upstreamFetch,
    "CONTINUE",
    localToolAvailable,
    protocolVersion,
  );
}

async function requestOpenAI(
  body: unknown,
  apiKey: string,
  upstreamFetch: UpstreamFetch,
  requestType: AgentRequestType,
  allowLocalTool: boolean,
  protocolVersion: AdvisorProtocolVersion,
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
    throw new UpstreamFailureError("upstream_network");
  }

  if (!response.ok) {
    const category: UpstreamFailureCategory =
      response.status === 429
        ? "upstream_rate_limit"
        : response.status >= 500
          ? "upstream_http_5xx"
          : "upstream_http_4xx";
    throw new UpstreamFailureError(category, response.status);
  }

  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    throw new UpstreamFailureError(
      "upstream_invalid_json",
      response.status,
    );
  }

  try {
    return normalizeOpenAIResponse(
      payload,
      requestType,
      allowLocalTool,
      protocolVersion,
    );
  } catch (error) {
    if (
      error instanceof UpstreamFailureError &&
      error.upstreamStatus === null
    ) {
      throw new UpstreamFailureError(
        error.category,
        response.status,
      );
    }
    throw error;
  }
}

export function normalizeOpenAIResponse(
  payload: unknown,
  requestType: AgentRequestType = "START",
  allowLocalTool = true,
  protocolVersion: AdvisorProtocolVersion =
    CURRENT_ADVISOR_PROTOCOL_VERSION,
): AgentResult {
  if (!isRecord(payload)) {
    throw new UpstreamFailureError("upstream_invalid_envelope");
  }

  const responseId = boundedUpstreamId(
    payload.id,
    "upstream_invalid_envelope",
  );
  if (!Array.isArray(payload.output)) {
    throw new UpstreamFailureError("upstream_invalid_envelope");
  }

  const webSearchCalls = countCompletedWebSearchCalls(payload.output);
  const usage = parseUsage(payload, requestType, webSearchCalls);
  const functionCalls = payload.output.filter(
    (item): item is Record<string, unknown> =>
      isRecord(item) && item.type === "function_call",
  );

  if (functionCalls.length > 1) {
    throw new UpstreamFailureError("upstream_invalid_tool_call");
  }

  if (functionCalls.length === 1) {
    if (!allowLocalTool) {
      throw new UpstreamFailureError("upstream_invalid_tool_call");
    }
    const call = functionCalls[0];
    const expectedToolName =
      protocolVersion >= 3
        ? PROVIDER_LOCAL_TOOL_NAME
        : LOCAL_TOOL_NAME;
    if (
      call.name !== expectedToolName ||
      typeof call.call_id !== "string" ||
      !call.call_id ||
      call.call_id.length > 256 ||
      typeof call.arguments !== "string"
    ) {
      throw new UpstreamFailureError("upstream_invalid_tool_call");
    }

    let argumentsValue;
    try {
      argumentsValue = parseToolArguments(
        call.arguments,
        protocolVersion,
      );
    } catch (error) {
      if (error instanceof InvalidRequestError) {
        throw new UpstreamFailureError("upstream_invalid_tool_call");
      }
      throw error;
    }

    return {
      type: "tool_request",
      responseId,
      tool: {
        name: expectedToolName,
        callId: call.call_id,
        arguments: argumentsValue,
      },
      webSearchCalls,
      ...(usage ? { usage } : {}),
    };
  }

  const rawAnswerText = extractAnswerText(payload);
  const answer = parseStructuredAnswer(
    rawAnswerText,
    protocolVersion,
  );
  const sources = extractWebSources(
    payload.output,
    rawAnswerText,
    answer.textBoundaryMap,
  );

  return {
    type: "answer",
    responseId,
    text: answer.text,
    productRefs: answer.productRefs,
    ...(sources.length ? { sources } : {}),
    webSearchCalls,
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
  protocolVersion: AdvisorProtocolVersion =
    CURRENT_ADVISOR_PROTOCOL_VERSION,
): {
  text: string;
  productRefs: ProductRef[];
  textBoundaryMap: Map<number, number>;
} {
  if (!raw) {
    throw new UpstreamFailureError("upstream_invalid_final_answer");
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw) as unknown;
  } catch {
    throw new UpstreamFailureError("upstream_invalid_final_answer");
  }

  if (!isRecord(parsed)) {
    throw new UpstreamFailureError("upstream_invalid_final_answer");
  }

  const keys = Object.keys(parsed);
  if (
    keys.length !== 2 ||
    !keys.includes("text") ||
    !keys.includes("productRefs")
  ) {
    throw new UpstreamFailureError("upstream_invalid_final_answer");
  }

  if (typeof parsed.text !== "string") {
    throw new UpstreamFailureError("upstream_invalid_final_answer");
  }
  const trimmedText = parsed.text.trim();
  const sanitized = stripUnsupportedInternalMarkup(trimmedText);
  const text = sanitized.text;
  if (!text || text.length > MAX_ANSWER_CHARS) {
    throw new UpstreamFailureError("upstream_invalid_final_answer");
  }
  const rawTextBoundaryMap = mapStructuredTextBoundaries(
    raw,
    parsed.text,
    trimmedText,
  );
  const textBoundaryMap = remapTextBoundaries(
    rawTextBoundaryMap,
    sanitized.boundaryMap,
  );

  if (
    !Array.isArray(parsed.productRefs) ||
    parsed.productRefs.length > MAX_SELECTED_PRODUCT_REFS
  ) {
    throw new UpstreamFailureError("upstream_invalid_final_answer");
  }

  const deduplicated: ProductRef[] = [];
  const seen = new Set<string>();
  for (const value of parsed.productRefs) {
    if (!isRecord(value)) {
      throw new UpstreamFailureError("upstream_invalid_final_answer");
    }
    if (protocolVersion >= 3) {
      const refKeys = Object.keys(value);
      if (
        refKeys.length !== 3 ||
        !refKeys.includes("providerId") ||
        !refKeys.includes("branchId") ||
        !refKeys.includes("productId") ||
        typeof value.providerId !== "string" ||
        !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(value.providerId) ||
        typeof value.branchId !== "string" ||
        !/^[A-Za-z0-9._-]{1,64}$/.test(value.branchId) ||
        typeof value.productId !== "string" ||
        !/^[A-Za-z0-9._-]{1,128}$/.test(value.productId)
      ) {
        throw new UpstreamFailureError("upstream_invalid_final_answer");
      }
      const key =
        value.providerId + ":" + value.branchId + ":" + value.productId;
      if (!seen.has(key)) {
        seen.add(key);
        deduplicated.push({
          providerId: value.providerId,
          branchId: value.branchId,
          productId: value.productId,
        });
      }
    } else {
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
        throw new UpstreamFailureError("upstream_invalid_final_answer");
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
  }

  return {
    text,
    productRefs: deduplicated,
    textBoundaryMap,
  };
}

function extractAnswerText(payload: Record<string, unknown>): string | null {
  const output = payload.output;
  if (Array.isArray(output)) {
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

    const joined = parts.join("");
    if (joined.trim()) return joined;
  }

  if (typeof payload.output_text === "string" && payload.output_text.trim()) {
    return payload.output_text;
  }
  return null;
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

function extractWebSources(
  output: unknown[],
  rawAnswerText: string | null,
  textBoundaryMap: Map<number, number>,
): WebSource[] {
  const sources: WebSource[] = [];
  const indexByUrl = new Map<string, number>();
  const canMapInline =
    rawAnswerText !== null &&
    !containsSurrogatePair(rawAnswerText);

  for (const item of output) {
    if (!isRecord(item) || item.type !== "message" || !Array.isArray(item.content)) {
      continue;
    }
    for (const content of item.content) {
      if (
        !isRecord(content) ||
        content.type !== "output_text" ||
        typeof content.text !== "string" ||
        !Array.isArray(content.annotations)
      ) {
        continue;
      }
      const contentCanMap =
        canMapInline && content.text === rawAnswerText;

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
        if (!url) continue;
        const title = annotation.title
          .replace(/\s+/g, " ")
          .trim()
          .slice(0, MAX_WEB_CITATION_TITLE_CHARS);
        if (!title) continue;

        let startIndex: number | null = null;
        let endIndex: number | null = null;
        if (
          contentCanMap &&
          Number.isSafeInteger(annotation.start_index) &&
          Number.isSafeInteger(annotation.end_index)
        ) {
          const rawStart = annotation.start_index as number;
          const rawEnd = annotation.end_index as number;
          const mappedStart = textBoundaryMap.get(rawStart);
          const mappedEnd = textBoundaryMap.get(rawEnd);
          if (
            rawStart >= 0 &&
            rawEnd > rawStart &&
            mappedStart !== undefined &&
            mappedEnd !== undefined &&
            mappedEnd > mappedStart
          ) {
            startIndex = mappedStart;
            endIndex = mappedEnd;
          }
        }

        const existingIndex = indexByUrl.get(url);
        if (existingIndex !== undefined) {
          const existing = sources[existingIndex];
          if (
            existing.startIndex === null &&
            startIndex !== null &&
            endIndex !== null
          ) {
            sources[existingIndex] = {
              ...existing,
              startIndex,
              endIndex,
            };
          }
          continue;
        }

        indexByUrl.set(url, sources.length);
        sources.push({
          title,
          url,
          startIndex,
          endIndex,
        });
        if (sources.length >= MAX_WEB_CITATIONS) {
          return sources;
        }
      }
    }
  }
  return sources;
}

function stripUnsupportedInternalMarkup(
  text: string,
): {
  text: string;
  boundaryMap: Map<number, number>;
} {
  const unsupported =
    /\uE200(?:cite|entity)\uE202[\s\S]{0,1024}?\uE201/g;
  const removed = new Array<boolean>(text.length).fill(false);
  for (const match of text.matchAll(unsupported)) {
    const start = match.index;
    const end = start + match[0].length;
    for (let index = start; index < end; index += 1) {
      removed[index] = true;
    }
  }

  const boundaryMap = new Map<number, number>();
  let sanitized = "";
  for (let index = 0; index < text.length; index += 1) {
    boundaryMap.set(index, sanitized.length);
    if (!removed[index]) sanitized += text[index];
  }
  boundaryMap.set(text.length, sanitized.length);

  return {
    text: sanitized,
    boundaryMap,
  };
}

function remapTextBoundaries(
  rawToText: Map<number, number>,
  textToSanitized: Map<number, number>,
): Map<number, number> {
  const remapped = new Map<number, number>();
  for (const [rawBoundary, textBoundary] of rawToText) {
    const sanitizedBoundary = textToSanitized.get(textBoundary);
    if (sanitizedBoundary !== undefined) {
      remapped.set(rawBoundary, sanitizedBoundary);
    }
  }
  return remapped;
}

function mapStructuredTextBoundaries(
  raw: string,
  parsedText: string,
  trimmedText: string,
): Map<number, number> {
  const match = /"text"\s*:\s*"/.exec(raw);
  if (!match) return new Map();

  const quoteIndex = match.index + match[0].length - 1;
  const token = parseJsonStringToken(raw, quoteIndex);
  if (!token || token.decoded !== parsedText) {
    return new Map();
  }

  const leadingTrim =
    parsedText.length - parsedText.trimStart().length;
  const trailingBoundary = leadingTrim + trimmedText.length;
  const mapped = new Map<number, number>();
  for (const [rawBoundary, decodedBoundary] of token.boundaries) {
    if (
      decodedBoundary >= leadingTrim &&
      decodedBoundary <= trailingBoundary
    ) {
      mapped.set(
        rawBoundary,
        decodedBoundary - leadingTrim,
      );
    }
  }
  return mapped;
}

function parseJsonStringToken(
  raw: string,
  quoteIndex: number,
): {
  decoded: string;
  boundaries: Map<number, number>;
} | null {
  if (raw[quoteIndex] !== '"') return null;

  const boundaries = new Map<number, number>();
  let position = quoteIndex + 1;
  let decodedLength = 0;
  boundaries.set(position, decodedLength);

  while (position < raw.length) {
    const char = raw[position];
    if (char === '"') {
      try {
        const decoded = JSON.parse(
          raw.slice(quoteIndex, position + 1),
        );
        return typeof decoded === "string"
          ? { decoded, boundaries }
          : null;
      } catch {
        return null;
      }
    }

    if (char === "\\") {
      const escapeType = raw[position + 1];
      if (escapeType === undefined) return null;
      const escapeLength = escapeType === "u" ? 6 : 2;
      const end = position + escapeLength;
      if (end > raw.length) return null;
      let decodedFragment: unknown;
      try {
        decodedFragment = JSON.parse(
          '"' + raw.slice(position, end) + '"',
        );
      } catch {
        return null;
      }
      if (typeof decodedFragment !== "string") return null;
      decodedLength += decodedFragment.length;
      position = end;
      boundaries.set(position, decodedLength);
      continue;
    }

    decodedLength += 1;
    position += 1;
    boundaries.set(position, decodedLength);
  }

  return null;
}

function containsSurrogatePair(value: string): boolean {
  for (let index = 0; index < value.length - 1; index += 1) {
    const first = value.charCodeAt(index);
    const second = value.charCodeAt(index + 1);
    if (
      first >= 0xd800 &&
      first <= 0xdbff &&
      second >= 0xdc00 &&
      second <= 0xdfff
    ) {
      return true;
    }
  }
  return false;
}

function normalizeHttpsUrl(raw: string): string | null {
  if (raw.length === 0 || raw.length > MAX_WEB_CITATION_URL_CHARS) {
    return null;
  }
  try {
    const url = new URL(raw);
    if (url.protocol !== "https:") return null;
    const normalized = url.toString();
    if (normalized.length > MAX_WEB_CITATION_URL_CHARS) {
      return null;
    }
    return normalized;
  } catch {
    return null;
  }
}

function boundedUpstreamId(
  value: unknown,
  category: UpstreamFailureCategory,
): string {
  if (typeof value !== "string" || !value || value.length > 256) {
    throw new UpstreamFailureError(category);
  }
  return value;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
