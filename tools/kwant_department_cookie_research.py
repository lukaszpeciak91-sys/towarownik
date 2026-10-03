#!/usr/bin/env python3
"""Research-only discovery for the public KWANT departmentCookie frontend contract.

Raw frontend excerpts are deliberately short-lived research evidence. The safe
summary never contains the observed cookie value.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

from kwant_live_contract_probe import (
    DEPARTMENT_COOKIE_NAME,
    KWANT_HOST,
    KWANT_ORIGIN,
    UNKNOWN,
    canonical_branch_page_url,
    choose_branch,
    cookie_values,
    sanitize_kwant_url,
)

MAX_EXCERPT_RADIUS = 1400
MAX_EXCERPTS = 24
RESEARCH_TERMS = (
    "departmentCookie",
    "setUnauthDepartmentCookie",
    "getUnauthDepartmentCookie",
    "department_stock_id",
)


def same_origin_script_urls(page: Any) -> list[str]:
    try:
        sources = page.locator("script[src]").evaluate_all(
            "els => els.map(el => el.src)"
        )
    except Exception:
        return []

    result: list[str] = []
    for source in sources:
        expected, safe = sanitize_kwant_url(str(source))
        if not expected:
            continue
        if safe not in result:
            result.append(safe)
    return result


def cookie_excerpts(
    script_path: str,
    text: str,
) -> list[dict[str, str]]:
    result: list[dict[str, str]] = []
    seen: set[tuple[str, int]] = set()
    for term in RESEARCH_TERMS:
        offset = 0
        while len(result) < MAX_EXCERPTS:
            index = text.find(term, offset)
            if index < 0:
                break
            marker = (term, index)
            if marker not in seen:
                seen.add(marker)
                start = max(0, index - MAX_EXCERPT_RADIUS)
                end = min(
                    len(text),
                    index + len(term) + MAX_EXCERPT_RADIUS,
                )
                result.append(
                    {
                        "scriptPath": script_path,
                        "matchTerm": term,
                        "excerpt": text[start:end],
                    }
                )
            offset = index + len(term)
    return result


def collect_cookie_bundle_evidence(
    context: Any,
    script_urls: list[str],
) -> tuple[list[str], list[dict[str, str]]]:
    scanned: list[str] = []
    excerpts: list[dict[str, str]] = []
    for source in script_urls:
        expected, safe = sanitize_kwant_url(source)
        if not expected:
            continue
        path = urlsplit(safe).path
        scanned.append(path)
        try:
            response = context.request.get(source, timeout=15000)
            if not response.ok:
                continue
            body = response.text()
        except Exception:
            continue
        if not any(term in body for term in RESEARCH_TERMS):
            continue
        excerpts.extend(cookie_excerpts(path, body))
        if len(excerpts) >= MAX_EXCERPTS:
            break
    return scanned, excerpts[:MAX_EXCERPTS]


def department_cookie_options(cookies: list[dict[str, Any]]) -> dict[str, Any]:
    matches = [
        cookie
        for cookie in cookies
        if cookie.get("name") == DEPARTMENT_COOKIE_NAME
    ]
    if len(matches) != 1:
        return {}
    cookie = matches[0]
    return {
        "domain": str(cookie.get("domain", "")),
        "path": str(cookie.get("path", "")),
        "secure": bool(cookie.get("secure", False)),
        "httpOnly": bool(cookie.get("httpOnly", False)),
        "sameSite": cookie.get("sameSite") or UNKNOWN,
        "expiresPresent": bool(cookie.get("expires", -1) not in (-1, None)),
    }


def safe_summary(
    *,
    script_paths: list[str],
    excerpts: list[dict[str, str]],
    cookie_options: dict[str, Any],
) -> dict[str, Any]:
    source_paths = sorted(
        {
            item["scriptPath"]
            for item in excerpts
            if item.get("scriptPath")
        }
    )
    return {
        "departmentCookieConstructorFound": bool(excerpts),
        "constructorSourcePath": (
            source_paths[0] if len(source_paths) == 1 else UNKNOWN
        ),
        "valueFormat": UNKNOWN,
        "sourceBranchFields": [],
        "encodingSteps": [],
        "cookieOptions": cookie_options or UNKNOWN,
        "reproducibleFromPublicData": UNKNOWN,
        "backendBranchIdentifier": UNKNOWN,
        "constructedValueMatchesObserved": UNKNOWN,
        "sameOriginScriptsScanned": len(script_paths),
        "constructorCandidateCount": len(excerpts),
    }


def write_summary(summary: dict[str, Any], out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    lines = ["KWANT DEPARTMENT COOKIE RESEARCH"]
    for key in (
        "departmentCookieConstructorFound",
        "constructorSourcePath",
        "valueFormat",
        "sourceBranchFields",
        "encodingSteps",
        "cookieOptions",
        "reproducibleFromPublicData",
        "backendBranchIdentifier",
        "constructedValueMatchesObserved",
        "sameOriginScriptsScanned",
        "constructorCandidateCount",
    ):
        value = summary.get(key, UNKNOWN)
        if isinstance(value, (dict, list)):
            rendered = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
        elif value is True:
            rendered = "true"
        elif value is False:
            rendered = "false"
        else:
            rendered = str(value)
        lines.append(f"{key}={rendered}")
    (out_dir / "summary.txt").write_text(
        "\n".join(lines) + "\n",
        encoding="utf-8",
    )


def run_research(
    *,
    branch_label: str,
    branch_page_url: str,
    product_url: str,
    out_dir: Path,
) -> dict[str, Any]:
    try:
        from playwright.sync_api import sync_playwright
    except ImportError as exc:
        raise RuntimeError("Playwright is required") from exc

    raw_dir = out_dir / "raw"
    raw_dir.mkdir(parents=True, exist_ok=True)
    branch_page_url = canonical_branch_page_url(branch_page_url)
    if not branch_page_url:
        raise ValueError("branch page URL must be public same-origin KWANT")

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context(
            locale="pl-PL",
            user_agent=(
                "Mozilla/5.0 (X11; Linux x86_64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/140.0.0.0 Safari/537.36"
            ),
            viewport={"width": 1440, "height": 1000},
        )
        page = context.new_page()

        script_urls: list[str] = []
        for url in (
            f"{KWANT_ORIGIN}/lista-hurtowni-elektrycznych",
            branch_page_url,
            product_url,
        ):
            page.goto(url, wait_until="domcontentloaded", timeout=45000)
            page.wait_for_timeout(600)
            for source in same_origin_script_urls(page):
                if source not in script_urls:
                    script_urls.append(source)

        scanned, excerpts = collect_cookie_bundle_evidence(
            context,
            script_urls,
        )
        (raw_dir / "frontend-cookie-excerpts.json").write_text(
            json.dumps(excerpts, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

        page.goto(KWANT_ORIGIN, wait_until="domcontentloaded", timeout=45000)
        raw_probe_dir = raw_dir / "selection"
        choose_branch(
            page,
            recorder=type(
                "_NoopRecorder",
                (),
                {
                    "set_action": lambda self, _action: None,
                    "records": [],
                },
            )(),
            branch_label=branch_label,
            raw_dir=raw_probe_dir,
        )
        _cookie_map, cookies = cookie_values(context)
        options = department_cookie_options(cookies)

        summary = safe_summary(
            script_paths=scanned,
            excerpts=excerpts,
            cookie_options=options,
        )
        browser.close()

    write_summary(summary, out_dir)
    return summary


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--branch-label", default="Nowy Sącz")
    parser.add_argument(
        "--branch-page-url",
        default=(
            "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
            "hurtownia-elektryczna-nowy%20sacz/205"
        ),
    )
    parser.add_argument(
        "--product-url",
        default=(
            "https://kwant.net.pl/produkt/"
            "wylacznik-nadpradowy-b16-a-1p-6ka-mbn116e-hager-580"
        ),
    )
    parser.add_argument(
        "--out-dir",
        default="build/kwant-department-cookie-research",
    )
    args = parser.parse_args()

    summary = run_research(
        branch_label=args.branch_label,
        branch_page_url=args.branch_page_url,
        product_url=args.product_url,
        out_dir=Path(args.out_dir),
    )
    print(
        (Path(args.out_dir) / "summary.txt").read_text(
            encoding="utf-8"
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
