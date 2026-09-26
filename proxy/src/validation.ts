import {
  CONTINUE_BODY_MAX_BYTES,
  MAX_CALL_ID_CHARS,
  MAX_PRODUCT_NAME_CHARS,
  MAX_RESPONSE_ID_CHARS,
  MAX_TOOL_PRODUCTS,
  MAX_TOOL_QUERY_CHARS,
  START_BODY_MAX_BYTES,
  START_MESSAGE_MAX_CHARS,
} from "./config";
import type { ToolArguments, VerifiedProduct, VerifiedToolResult } from "./types";

export class InvalidRequestError extends Error {}
export class RequestTooLargeError extends Error {}

export async function readJsonBody(
  request: Request,
  maxBytes: number,
): Promise<unknown> {
  if (!isJsonContentType(request.headers.get("Content-Type"))) {
    throw new InvalidRequestError();
  }

  const declaredLength = parseContentLength(request.headers.get("Content-Length"));
  if (declaredLength !== null && declaredLength > maxBytes) {
    throw new RequestTooLargeError();
  }

  const text = await readUtf8Body(request, maxBytes);

  try {
    return JSON.parse(text) as unknown;
  } catch {
    throw new InvalidRequestError();
  }
}

export async function parseStartRequest(request: Request): Promise<string> {
  const value = await readJsonBody(request, START_BODY_MAX_BYTES);
  const object = exactObject(value, ["message"]);

  if (typeof object.message !== "string") {
    throw new InvalidRequestError();
  }

  const message = normalizeWhitespace(object.message);
  if (!message || message.length > START_MESSAGE_MAX_CHARS) {
    throw new InvalidRequestError();
  }

  return message;
}

export async function parseContinueRequest(
  request: Request,
): Promise<{
  responseId: string;
  callId: string;
  tool: "find_available_obi_075";
  result: VerifiedToolResult;
}> {
  const value = await readJsonBody(request, CONTINUE_BODY_MAX_BYTES);
  const object = exactObject(value, ["responseId", "callId", "tool", "result"]);

  const responseId = boundedString(object.responseId, MAX_RESPONSE_ID_CHARS);
  const callId = boundedString(object.callId, MAX_CALL_ID_CHARS);

  if (object.tool !== "find_available_obi_075") {
    throw new InvalidRequestError();
  }

  const resultObject = exactObject(object.result, ["query", "products"]);
  const query = normalizeWhitespace(
    boundedString(resultObject.query, MAX_TOOL_QUERY_CHARS),
  );
  if (!query) {
    throw new InvalidRequestError();
  }

  if (!Array.isArray(resultObject.products) || resultObject.products.length > MAX_TOOL_PRODUCTS) {
    throw new InvalidRequestError();
  }

  const products = resultObject.products.map(validateProduct);

  return {
    responseId,
    callId,
    tool: "find_available_obi_075",
    result: {
      query,
      products,
    },
  };
}

export function parseToolArguments(raw: string): ToolArguments {
  let value: unknown;
  try {
    value = JSON.parse(raw) as unknown;
  } catch {
    throw new InvalidRequestError();
  }

  const object = exactObject(value, ["query", "limit"]);
  const query = normalizeWhitespace(
    boundedString(object.query, MAX_TOOL_QUERY_CHARS),
  );

  if (
    !query ||
    typeof object.limit !== "number" ||
    !Number.isInteger(object.limit) ||
    object.limit < 1 ||
    object.limit > MAX_TOOL_PRODUCTS
  ) {
    throw new InvalidRequestError();
  }

  return {
    query,
    limit: object.limit,
  };
}

function validateProduct(value: unknown): VerifiedProduct {
  const object = exactObject(value, ["obik", "name", "stock", "price"]);

  if (typeof object.obik !== "string" || !/^\d{7}$/.test(object.obik)) {
    throw new InvalidRequestError();
  }

  const name = normalizeWhitespace(
    boundedString(object.name, MAX_PRODUCT_NAME_CHARS),
  );
  if (!name) {
    throw new InvalidRequestError();
  }

  const stock = validateNullableStock(object.stock);
  const price = validateNullablePrice(object.price);

  return {
    obik: object.obik,
    name,
    stock,
    price,
  };
}

function validateNullableStock(value: unknown): number | null {
  if (value === null) return null;
  if (typeof value !== "number" || !Number.isInteger(value) || value < 0) {
    throw new InvalidRequestError();
  }
  return value;
}

function validateNullablePrice(value: unknown): number | null {
  if (value === null) return null;
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) {
    throw new InvalidRequestError();
  }
  return value;
}

function exactObject(
  value: unknown,
  allowedKeys: readonly string[],
): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new InvalidRequestError();
  }

  const object = value as Record<string, unknown>;
  const keys = Object.keys(object);
  if (
    keys.length !== allowedKeys.length ||
    keys.some((key) => !allowedKeys.includes(key)) ||
    allowedKeys.some((key) => !(key in object))
  ) {
    throw new InvalidRequestError();
  }

  return object;
}

function boundedString(value: unknown, maxChars: number): string {
  if (typeof value !== "string" || value.length === 0 || value.length > maxChars) {
    throw new InvalidRequestError();
  }
  return value;
}

function normalizeWhitespace(value: string): string =
  value.replace(/[\s\u0000-\u001f\u007f]+/g, " ").trim();

function isJsonContentType(value: string | null): boolean {
  if (!value) return false;
  return value.split(";", 1)[0]?.trim().toLowerCase() === "application/json";
}

function parseContentLength(value: string | null): number | null {
  if (!value) return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
}

async function readUtf8Body(request: Request, maxBytes: number): Promise<string> {
  if (!request.body) {
    throw new InvalidRequestError();
  }

  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let totalBytes = 0;

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!value) continue;

      totalBytes += value.byteLength;
      if (totalBytes > maxBytes) {
        throw new RequestTooLargeError();
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  if (totalBytes === 0) {
    throw new InvalidRequestError();
  }

  const bytes = new Uint8Array(totalBytes);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }

  return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
}
