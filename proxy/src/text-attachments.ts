/**
 * Strict v5 text-attachment contract. These are USER data, never instructions.
 * The conservative bound avoids converting a 16 MiB text file into a huge
 * decoded prompt while leaving all existing image/PDF transport limits intact.
 */
export const TEXT_ATTACHMENT_MAX_BYTES = 1024 * 1024;

const ALLOWED: Readonly<Record<string, readonly string[]>> = Object.freeze({
  txt: ["text/plain"],
  md: ["text/markdown", "text/plain"],
  csv: ["text/csv", "text/plain"],
  json: ["application/json", "text/json"],
  xml: ["application/xml", "text/xml"],
  yaml: ["application/yaml", "application/x-yaml", "text/yaml", "text/x-yaml"],
  yml: ["application/yaml", "application/x-yaml", "text/yaml", "text/x-yaml"],
  log: ["text/plain"],
  ini: ["text/plain"],
  conf: ["text/plain"],
});

export function isAllowedTextAttachment(filename: string, mimeType: string): boolean {
  const extension = filename.toLowerCase().match(/\.([a-z]+)$/)?.[1];
  return extension !== undefined && (ALLOWED[extension]?.includes(mimeType) ?? false);
}

export function decodeTextAttachment(bytes: Uint8Array): string | null {
  if (bytes.byteLength < 1 || bytes.byteLength > TEXT_ATTACHMENT_MAX_BYTES) return null;
  // Reject common binary containers even when a provider lies about MIME and extension.
  if (
    (bytes[0] === 0x25 && bytes[1] === 0x50 && bytes[2] === 0x44 && bytes[3] === 0x46 && bytes[4] === 0x2d) ||
    (bytes[0] === 0x50 && bytes[1] === 0x4b && bytes[2] === 0x03 && bytes[3] === 0x04) ||
    (bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47) ||
    (bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) ||
    (bytes[0] === 0x1f && bytes[1] === 0x8b) ||
    (bytes[0] === 0x7f && bytes[1] === 0x45 && bytes[2] === 0x4c && bytes[3] === 0x46)
  ) return null;
  let decoded: string;
  try {
    decoded = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    return null;
  }
  // Disallow NUL/C0/C1 terminal and binary control characters, except line breaks and tabs.
  for (const char of decoded) {
    const point = char.codePointAt(0)!;
    if ((point < 0x20 && point !== 0x09 && point !== 0x0a && point !== 0x0d) ||
        (point >= 0x7f && point <= 0x9f)) return null;
  }
  return decoded;
}
