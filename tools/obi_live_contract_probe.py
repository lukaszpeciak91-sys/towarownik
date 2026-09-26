#!/usr/bin/env python3
"""Inspect the current public OBI Nuxt payload without changing Android code."""

from __future__ import annotations

import argparse
import json
import re
from html.parser import HTMLParser
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit, urlunsplit

KEY_TERMS = (
    "product",
    "article",
    "obik",
    "sku",
    "ean",
    "gtin",
    "store",
    "stock",
    "availability",
    "inventory",
    "price",
    "pricing",
    "gross",
)
MAX_EXACT_MATCHES = 100
MAX_KEY_HITS = 250
MAX_REFERENCE_EDGES = 200
MAX_REFERENCE_TRACE_DEPTH = 4
REFERENCE_WRAPPERS = {"Ref", "ShallowRef"}


class NuxtScriptExtractor(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=False)
        self.in_target = False
        self.parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag.lower() != "script":
            return
        attr_map = {key.lower(): value for key, value in attrs}
        if attr_map.get("id") == "__NUXT_DATA__":
            self.in_target = True

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() == "script" and self.in_target:
            self.in_target = False

    def handle_data(self, data: str) -> None:
        if self.in_target:
            self.parts.append(data)

    @property
    def payload(self) -> str:
        return "".join(self.parts).strip()


def validate_identifiers(obik: str, store: str) -> None:
    if re.fullmatch(r"\d{7}", obik) is None:
        raise ValueError("OBIK must contain exactly 7 digits")
    if re.fullmatch(r"\d{3}", store) is None:
        raise ValueError("Store number must contain exactly 3 digits")


def sanitize_obi_final_url(raw_url: str) -> tuple[bool, str]:
    try:
        parsed = urlsplit(raw_url)
        port = parsed.port
    except ValueError:
        return False, "REDACTED_INVALID_URL"

    expected = bool(
        parsed.scheme == "https"
        and parsed.hostname == "www.obi.pl"
        and port in (None, 443)
        and parsed.username is None
        and parsed.password is None
    )
    if not expected:
        return False, "REDACTED_UNEXPECTED_HOST"

    return True, urlunsplit(("https", "www.obi.pl", parsed.path or "/", "", ""))


def extract_nuxt_payload(html: str) -> str:
    extractor = NuxtScriptExtractor()
    extractor.feed(html)
    payload = extractor.payload
    if not payload:
        raise ValueError("Missing __NUXT_DATA__ script in downloaded OBI HTML")
    return payload


def parse_nuxt_payload(payload: str) -> Any:
    try:
        return json.loads(payload)
    except json.JSONDecodeError as exc:
        raise ValueError(f"__NUXT_DATA__ is not valid JSON: {exc}") from exc


def path_join(path: str, part: str) -> str:
    if part.startswith("["):
        return f"{path}{part}"
    return f"{path}.{part}"


def walk(node: Any, path: str = "$"):
    yield path, node
    if isinstance(node, dict):
        for key, value in node.items():
            yield from walk(value, path_join(path, str(key)))
    elif isinstance(node, list):
        for index, value in enumerate(node):
            yield from walk(value, path_join(path, f"[{index}]"))


def compact(value: Any, limit: int = 180) -> Any:
    if isinstance(value, dict):
        return {"type": "object", "keys": list(value.keys())[:30]}
    if isinstance(value, list):
        if (
            len(value) >= 2
            and isinstance(value[0], str)
            and value[0] in REFERENCE_WRAPPERS
            and type(value[1]) is int
        ):
            return {"type": "wrapper", "tag": value[0], "nextRef": value[1]}
        return {"type": "array", "length": len(value)}
    if isinstance(value, str) and len(value) > limit:
        return value[: limit - 3] + "..."
    return value


def exact_matches(root: Any, target: str) -> list[dict[str, Any]]:
    matches: list[dict[str, Any]] = []
    for path, value in walk(root):
        if isinstance(value, (str, int)) and not isinstance(value, bool) and str(value) == target:
            matches.append({"path": path, "value": value})
            if len(matches) >= MAX_EXACT_MATCHES:
                break
    return matches


def reference_trace(
    root: Any,
    value: Any,
    max_depth: int = MAX_REFERENCE_TRACE_DEPTH,
) -> dict[str, Any]:
    if not isinstance(root, list) or type(value) is not int or value not in range(len(root)):
        return {"kind": "literal", "value": compact(value)}

    chain: list[dict[str, Any]] = []
    seen: set[int] = set()
    index = value

    for _ in range(max_depth):
        if index in seen or index not in range(len(root)):
            break
        seen.add(index)

        resolved = root[index]
        description = compact(resolved)
        chain.append({"index": index, "resolved": description})

        if (
            isinstance(resolved, list)
            and len(resolved) >= 2
            and isinstance(resolved[0], str)
            and resolved[0] in REFERENCE_WRAPPERS
            and type(resolved[1]) is int
            and resolved[1] in range(len(root))
        ):
            index = resolved[1]
            continue
        break

    return {"kind": "reference", "chain": chain}


def format_compact(value: Any) -> str:
    if isinstance(value, dict):
        kind = value.get("type")
        if kind == "object":
            return f"object keys={value.get('keys', [])}"
        if kind == "array":
            return f"array length={value.get('length')}"
        if kind == "wrapper":
            return f"wrapper {value.get('tag')}"
    return repr(value)


def format_reference_trace(trace: dict[str, Any]) -> str:
    if trace["kind"] == "literal":
        return format_compact(trace["value"])

    parts: list[str] = []
    for element in trace["chain"]:
        parts.append(f"ref top[{element['index']}]")
        parts.append(format_compact(element["resolved"]))
    return " -> ".join(parts)


def keyword_hits(root: Any) -> list[dict[str, Any]]:
    hits: list[dict[str, Any]] = []
    for path, value in walk(root):
        if not isinstance(value, dict):
            continue
        for key, child in value.items():
            lowered = str(key).lower()
            if any(term in lowered for term in KEY_TERMS):
                hits.append(
                    {
                        "objectPath": path,
                        "key": key,
                        "referenceTrace": reference_trace(root, child),
                        "objectKeys": list(value.keys())[:40],
                    }
                )
                if len(hits) >= MAX_KEY_HITS:
                    return hits
    return hits


def top_level_scalar_indices(root: Any, target: str) -> list[int]:
    if not isinstance(root, list):
        return []
    return [
        index
        for index, value in enumerate(root)
        if isinstance(value, (str, int))
        and not isinstance(value, bool)
        and str(value) == target
    ]


def direct_top_level_references(root: Any, referenced_indices: set[int]) -> list[dict[str, Any]]:
    if not isinstance(root, list) or not referenced_indices:
        return []

    edges: list[dict[str, Any]] = []
    for owner_index, value in enumerate(root):
        if isinstance(value, dict):
            for key, child in value.items():
                if type(child) is int and child in referenced_indices:
                    edges.append(
                        {
                            "ownerIndex": owner_index,
                            "ownerType": "object",
                            "ownerKeys": list(value.keys())[:40],
                            "via": key,
                            "referencedIndex": child,
                        }
                    )
        elif isinstance(value, list):
            for child_index, child in enumerate(value):
                if type(child) is int and child in referenced_indices:
                    edges.append(
                        {
                            "ownerIndex": owner_index,
                            "ownerType": "array",
                            "ownerLength": len(value),
                            "via": f"[{child_index}]",
                            "referencedIndex": child,
                        }
                    )
        if len(edges) >= MAX_REFERENCE_EDGES:
            break
    return edges


def reference_layers(root: Any, seed_indices: list[int], depth: int = 4) -> list[dict[str, Any]]:
    layers: list[dict[str, Any]] = []
    frontier = set(seed_indices)
    seen = set(seed_indices)

    for level in range(1, depth + 1):
        edges = direct_top_level_references(root, frontier)
        if not edges:
            break
        layers.append({"level": level, "edges": edges})
        owners = {edge["ownerIndex"] for edge in edges}
        frontier = owners - seen
        seen.update(owners)
        if not frontier:
            break
    return layers


def build_summary(
    html: str,
    root: Any,
    payload: str,
    obik: str,
    store: str,
    status: str,
    final_url: str,
) -> dict[str, Any]:
    obik_indices = top_level_scalar_indices(root, obik)
    store_indices = top_level_scalar_indices(root, store)

    return {
        "transport": {
            "status": status,
            "finalUrl": final_url,
        },
        "obik": obik,
        "store": store,
        "htmlUtf8Bytes": len(html.encode("utf-8")),
        "nuxtUtf8Bytes": len(payload.encode("utf-8")),
        "nuxtRootType": type(root).__name__,
        "nuxtTopLevelLength": len(root) if isinstance(root, list) else None,
        "obikTopLevelScalarIndices": obik_indices,
        "storeTopLevelScalarIndices": store_indices,
        "obikExactMatches": exact_matches(root, obik),
        "storeExactMatches": exact_matches(root, store),
        "obikReferenceLayers": reference_layers(root, obik_indices),
        "storeReferenceLayers": reference_layers(root, store_indices),
        "keywordHits": keyword_hits(root),
    }


def write_text_summary(summary: dict[str, Any], destination: Path) -> None:
    lines = [
        "OBI LIVE CONTRACT SUMMARY",
        f"status={summary['transport']['status']}",
        f"finalUrl={summary['transport']['finalUrl']}",
        f"htmlUtf8Bytes={summary['htmlUtf8Bytes']}",
        f"nuxtUtf8Bytes={summary['nuxtUtf8Bytes']}",
        f"nuxtRootType={summary['nuxtRootType']}",
        f"nuxtTopLevelLength={summary['nuxtTopLevelLength']}",
        f"obik={summary['obik']}",
        f"store={summary['store']}",
        f"obikExactMatches={len(summary['obikExactMatches'])}",
        f"storeExactMatches={len(summary['storeExactMatches'])}",
        f"keywordHits={len(summary['keywordHits'])}",
        "",
        "OBIK EXACT MATCHES",
    ]

    for item in summary["obikExactMatches"][:30]:
        lines.append(f"- {item['path']} = {item['value']!r}")

    lines.append("")
    lines.append("STORE EXACT MATCHES")
    for item in summary["storeExactMatches"][:30]:
        lines.append(f"- {item['path']} = {item['value']!r}")

    def append_layers(title: str, layers: list[dict[str, Any]]) -> None:
        lines.append("")
        lines.append(title)
        if not layers:
            lines.append("- none")
            return
        for layer in layers:
            lines.append(f"level {layer['level']}:")
            for edge in layer["edges"][:40]:
                keys = edge.get("ownerKeys")
                suffix = f" keys={keys}" if keys is not None else f" length={edge.get('ownerLength')}"
                lines.append(
                    f"- top[{edge['ownerIndex']}] via {edge['via']} -> "
                    f"top[{edge['referencedIndex']}]" + suffix
                )

    append_layers("OBIK REFERENCE LAYERS", summary["obikReferenceLayers"])
    append_layers("STORE REFERENCE LAYERS", summary["storeReferenceLayers"])

    lines.append("")
    lines.append("RELEVANT KEY HITS")
    for hit in summary["keywordHits"][:120]:
        lines.append(
            f"- {hit['objectPath']}.{hit['key']} = "
            f"{format_reference_trace(hit['referenceTrace'])}; "
            f"objectKeys={hit['objectKeys']}"
        )

    destination.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--html", required=True)
    parser.add_argument("--obik", required=True)
    parser.add_argument("--store", required=True)
    parser.add_argument("--status", required=True)
    parser.add_argument("--final-url", required=True)
    parser.add_argument("--out-dir", required=True)
    args = parser.parse_args()

    try:
        validate_identifiers(args.obik, args.store)
    except ValueError as exc:
        raise SystemExit(str(exc)) from exc

    expected_host, safe_final_url = sanitize_obi_final_url(args.final_url)
    if not expected_host:
        raise SystemExit("Final URL is not a safe expected OBI URL")

    html_path = Path(args.html)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    html = html_path.read_text(encoding="utf-8", errors="replace")

    try:
        payload = extract_nuxt_payload(html)
        root = parse_nuxt_payload(payload)
    except ValueError as exc:
        raise SystemExit(str(exc)) from exc

    (out_dir / "nuxt.json").write_text(
        json.dumps(root, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )

    summary = build_summary(
        html=html,
        root=root,
        payload=payload,
        obik=args.obik,
        store=args.store,
        status=args.status,
        final_url=safe_final_url,
    )

    (out_dir / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    write_text_summary(summary, out_dir / "summary.txt")

    if not summary["obikExactMatches"]:
        raise SystemExit(f"OBIK {args.obik} is missing from __NUXT_DATA__")
    if not summary["storeExactMatches"]:
        raise SystemExit(f"Store {args.store} is missing from __NUXT_DATA__")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
