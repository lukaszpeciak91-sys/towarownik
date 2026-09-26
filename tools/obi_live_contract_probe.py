#!/usr/bin/env python3
"""Inspect the current public OBI Nuxt payload without changing Android code."""

from __future__ import annotations

import argparse
import json
from html.parser import HTMLParser
from pathlib import Path
from typing import Any

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
        return {"type": "array", "length": len(value)}
    rendered = repr(value)
    return rendered if len(rendered) <= limit else rendered[: limit - 3] + "..."


def exact_matches(root: Any, target: str) -> list[dict[str, Any]]:
    matches: list[dict[str, Any]] = []
    for path, value in walk(root):
        if isinstance(value, (str, int)) and str(value) == target:
            matches.append({"path": path, "value": value})
            if len(matches) >= MAX_EXACT_MATCHES:
                break
    return matches


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
                        "value": compact(child),
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
        if isinstance(value, (str, int)) and str(value) == target
    ]


def direct_top_level_references(root: Any, referenced_indices: set[int]) -> list[dict[str, Any]]:
    if not isinstance(root, list) or not referenced_indices:
        return []

    edges: list[dict[str, Any]] = []
    for owner_index, value in enumerate(root):
        if isinstance(value, dict):
            for key, child in value.items():
                if isinstance(child, int) and child in referenced_indices:
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
                if isinstance(child, int) and child in referenced_indices:
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
            f"- {hit['objectPath']}.{hit['key']} = {hit['value']!r}; "
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

    html_path = Path(args.html)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    html = html_path.read_text(encoding="utf-8", errors="replace")
    extractor = NuxtScriptExtractor()
    extractor.feed(html)
    payload = extractor.payload

    if not payload:
        raise SystemExit("Missing __NUXT_DATA__ script in downloaded OBI HTML")

    try:
        root = json.loads(payload)
    except json.JSONDecodeError as exc:
        raise SystemExit(f"__NUXT_DATA__ is not valid JSON: {exc}") from exc

    nuxt_path = out_dir / "nuxt.json"
    nuxt_path.write_text(
        json.dumps(root, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )

    obik_indices = top_level_scalar_indices(root, args.obik)
    store_indices = top_level_scalar_indices(root, args.store)

    summary = {
        "transport": {
            "status": args.status,
            "finalUrl": args.final_url,
        },
        "obik": args.obik,
        "store": args.store,
        "htmlUtf8Bytes": len(html.encode("utf-8")),
        "nuxtUtf8Bytes": len(payload.encode("utf-8")),
        "nuxtRootType": type(root).__name__,
        "nuxtTopLevelLength": len(root) if isinstance(root, list) else None,
        "obikTopLevelScalarIndices": obik_indices,
        "storeTopLevelScalarIndices": store_indices,
        "obikExactMatches": exact_matches(root, args.obik),
        "storeExactMatches": exact_matches(root, args.store),
        "obikReferenceLayers": reference_layers(root, obik_indices),
        "storeReferenceLayers": reference_layers(root, store_indices),
        "keywordHits": keyword_hits(root),
    }

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
