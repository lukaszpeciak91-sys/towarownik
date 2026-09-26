import {
  AGENT_INSTRUCTIONS,
  LOCAL_TOOL_NAME,
  MAX_ANSWER_CHARS,
  OPENAI_MAX_OUTPUT_TOKENS,
  OPENAI_MODEL,
  OPENAI_REASONING_EFFORT,
  OPENAI_RESPONSES_URL,
  OBI_TOOL,
} from "./config.js";
import { InvalidRequestError, parseToolArguments } from "./validation.js";
import type { AgentResult, UpstreamFetch, VerifiedToolResult } from "./types.js";

export class UpstreamFailureError extends Error {}

export async function startAgent(
  message: string,
  apiKey: string,
  upstreamFetch: UpstreamFetch,
): Promise<AgentResult> {
  return requestOpenAI(
    {
      model: OPENAI_MODEL,
      instructions: AGENT_INSTRUCTIONS,
      input: message,
      reasoning: {
        effort: OPENAI_REASONING_EFFORT,
      },
      max_output_tokens: OPENAI_MAX_OUTPUT_TOKENS,
      parallel_tool_calls: false,
      store: true,
      tools: [OBI_TOOL],
    },
    apiKey,
    upstreamFetch,
  );
}

export async function continueAgent(
  responseId: string,
  callId: string,
  result: VerifiedToolResult,
  apiKey: string,
  upstreamFetch: UpstreamFetch,
): Promise<AgentResult> {
  return requestOpenAI(
    {
      model: OPENAI_MODEL,
      instructions: AGENT_INSTRUCTIONS,
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
      tools: [OBI_TOOL],
    },
    apiKey,
    upstreamFetch,
  );
}

async function requestOpenAI(
  body: unknown,
  apiKey: string,
  upstreamFetch: UpstreamFetch,
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

  return normalizeOpenAIResponse(payload);
}

export function normalizeOpenAIResponse(payload: unknown): AgentResult {
  if (!isRecord(payload)) {
    throw new UpstreamFailureError();
  }

  const responseId = boundedUpstreamId(payload.id);
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
    };
  }

  const text = extractAnswerText(payload);
  if (!text || text.length > MAX_ANSWER_CHARS) {
    throw new UpstreamFailureError();
  }

  return {
    type: "answer",
    responseId,
    text,
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
