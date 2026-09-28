#!/usr/bin/env python3
"""Research-only OBI Poland cross-market availability probe.

Emits bounded safe summaries only: no cookies, full response bodies, auth material,
or request-specific identifiers are persisted.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import http.cookiejar
import json
import re
import statistics
import time
import urllib.error
import urllib.parse
import urllib.request
from decimal import Decimal, InvalidOperation
from html.parser import HTMLParser
from pathlib import Path
from typing import Any

BASE = "https://www.obi.pl"
USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
HTML_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
JSON_ACCEPT = "application/json,text/plain,*/*"
ACCEPT_LANGUAGE = "pl-PL,pl;q=0.9"
MAX_BODY_BYTES = 2 * 1024 * 1024
MAX_CHALLENGE_PREVIEW = 128 * 1024
CHALLENGE_TERMS = (
    "captcha",
    "robot",
    "access denied",
    "verify you are human",
    "challenge-platform",
)
OBIK_RE = re.compile(r"^\d{7}$")
STORE_RE = re.compile(r"^\d{3}$")
REFERENCE_WRAPPERS = {"Ref", "ShallowRef"}


class NuxtScriptExtractor(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=False)
        self.in_target = False
        self.parts: list[str] = []

    def handle_starttag(self, tag, attrs):
        if (
            tag.lower() == "script"
            and dict((key.lower(), value) for key, value in attrs).get("id")
            == "__NUXT_DATA__"
        ):
            self.in_target = True

    def handle_endtag(self, tag):
        if tag.lower() == "script" and self.in_target:
            self.in_target = False

    def handle_data(self, data):
        if self.in_target:
            self.parts.append(data)

    @property
    def payload(self) -> str:
        return "".join(self.parts).strip()


def validate_obik(value: str) -> None:
    if not OBIK_RE.fullmatch(value):
        raise ValueError(f"invalid OBIK: {value!r}")


def validate_store(value: str) -> None:
    if not STORE_RE.fullmatch(value):
        raise ValueError(f"invalid store: {value!r}")


def transport_profile_matches_kotlin(kotlin_source: str) -> bool:
    return all(
        value in kotlin_source
        for value in (USER_AGENT, HTML_ACCEPT, ACCEPT_LANGUAGE)
    )


def make_opener() -> urllib.request.OpenerDirector:
    jar = http.cookiejar.CookieJar()
    return urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(jar),
    )


def is_challenge(body: bytes) -> bool:
    text = body[:MAX_CHALLENGE_PREVIEW].decode(
        "utf-8",
        "replace",
    ).lower()
    return any(term in text for term in CHALLENGE_TERMS)


def safe_content_type(headers) -> str | None:
    value = headers.get("Content-Type") if headers else None
    return (
        value.split(";", 1)[0].strip().lower()
        if value
        else None
    )


def fetch(
    opener: urllib.request.OpenerDirector,
    url: str,
    accept: str,
) -> dict[str, Any]:
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Accept": accept,
            "Accept-Language": ACCEPT_LANGUAGE,
        },
    )
    started = time.monotonic()
    try:
        with opener.open(request, timeout=30) as response:
            body = response.read(MAX_BODY_BYTES + 1)
            truncated = len(body) > MAX_BODY_BYTES
            if truncated:
                body = body[:MAX_BODY_BYTES]
            return {
                "httpStatus": int(response.status),
                "contentType": safe_content_type(response.headers),
                "durationMs": round(
                    (time.monotonic() - started) * 1000,
                ),
                "challengeDetected": is_challenge(body),
                "truncated": truncated,
                "body": body,
            }
    except urllib.error.HTTPError as exc:
        body = exc.read(MAX_CHALLENGE_PREVIEW)
        return {
            "httpStatus": int(exc.code),
            "contentType": safe_content_type(exc.headers),
            "durationMs": round(
                (time.monotonic() - started) * 1000,
            ),
            "challengeDetected": is_challenge(body),
            "truncated": False,
            "body": body,
        }
    except Exception as exc:
        return {
            "httpStatus": None,
            "contentType": None,
            "durationMs": round(
                (time.monotonic() - started) * 1000,
            ),
            "challengeDetected": False,
            "truncated": False,
            "transportError": exc.__class__.__name__,
            "body": b"",
        }


def json_shape(value: Any) -> str:
    if isinstance(value, list):
        if not value:
            return "array(empty)"
        first = value[0]
        if isinstance(first, dict):
            keys = ",".join(sorted(first.keys())[:12])
            return f"array<object keys={keys}>"
        return f"array<{type(first).__name__}>"
    if isinstance(value, dict):
        keys = ",".join(sorted(value.keys())[:16])
        return f"object keys={keys}"
    return type(value).__name__


def decimal_or_none(value: Any) -> str | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        decimal = Decimal(str(value))
    except (InvalidOperation, ValueError):
        return None
    if decimal < 0:
        return None
    return format(decimal, "f")


def nonnegative_int_or_none(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int) and value >= 0:
        return value
    return None


def parse_availability(
    body: bytes,
    obik: str,
) -> dict[str, Any]:
    try:
        root = json.loads(body.decode("utf-8"))
    except Exception:
        return {
            "semantic": "malformed_json",
            "shape": "invalid_json",
        }

    shape = json_shape(root)
    if not isinstance(root, list):
        return {
            "semantic": "unexpected_json",
            "shape": shape,
        }

    match = next(
        (
            item
            for item in root
            if isinstance(item, dict)
            and str(item.get("articleNumber", "")) == obik
        ),
        None,
    )
    if match is None:
        return {
            "semantic": "article_missing",
            "shape": shape,
            "obikPresent": False,
            "stock": None,
            "price": None,
            "currency": None,
        }

    store_specific = match.get("storeSpecificArticle")
    currency = match.get("currency")
    stock = (
        nonnegative_int_or_none(store_specific.get("stock"))
        if isinstance(store_specific, dict)
        else None
    )
    price = (
        decimal_or_none(store_specific.get("price"))
        if isinstance(store_specific, dict)
        else None
    )
    iso_code = (
        currency.get("isoCode")
        if isinstance(currency, dict)
        and isinstance(currency.get("isoCode"), str)
        else None
    )
    return {
        "semantic": (
            "verified_stock"
            if stock is not None
            else "missing_stock"
        ),
        "shape": shape,
        "recordKeys": sorted(match.keys())[:16],
        "storeSpecificKeys": (
            sorted(store_specific.keys())[:16]
            if isinstance(store_specific, dict)
            else []
        ),
        "obikPresent": True,
        "stock": stock,
        "price": price,
        "currency": iso_code,
    }


def extract_nuxt(html: str) -> Any:
    parser = NuxtScriptExtractor()
    parser.feed(html)
    if not parser.payload:
        raise ValueError("missing_nuxt")
    return json.loads(parser.payload)


def decode_nuxt(root: Any) -> Any:
    if not isinstance(root, list):
        return root

    flattened = root
    visiting: set[int] = set()

    def resolve(
        value: Any,
        references_allowed: bool = True,
    ) -> Any:
        if isinstance(value, dict):
            return {
                key: resolve(child, True)
                for key, child in value.items()
            }

        if isinstance(value, list):
            if (
                len(value) >= 2
                and isinstance(value[0], str)
                and value[0] in REFERENCE_WRAPPERS
                and type(value[1]) is int
                and 0 <= value[1] < len(flattened)
            ):
                index = value[1]
                if index in visiting:
                    return value
                visiting.add(index)
                try:
                    return resolve(
                        flattened[index],
                        False,
                    )
                finally:
                    visiting.remove(index)
            return [
                resolve(child, True)
                for child in value
            ]

        if (
            references_allowed
            and type(value) is int
            and 0 <= value < len(flattened)
        ):
            if value in visiting:
                return value
            visiting.add(value)
            try:
                return resolve(
                    flattened[value],
                    False,
                )
            finally:
                visiting.remove(value)

        return value

    return resolve(
        flattened[0] if flattened else None,
        True,
    )


def walk(value: Any):
    yield value
    if isinstance(value, dict):
        for child in value.values():
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)


def full_page_fact(
    html: str,
    obik: str,
    store: str,
) -> dict[str, Any]:
    try:
        decoded = decode_nuxt(extract_nuxt(html))
    except Exception as exc:
        return {
            "semantic": "malformed_or_missing_nuxt",
            "error": exc.__class__.__name__,
            "stock": None,
            "price": None,
        }

    product_keys = (
        "skuId",
        "obik",
        "productNumber",
        "articleNumber",
        "sku",
    )
    for node in walk(decoded):
        if not isinstance(node, dict):
            continue
        if not any(
            str(node.get(key, "")) == obik
            for key in product_keys
        ):
            continue

        store_object = node.get("store")
        if not isinstance(store_object, dict):
            continue

        information = store_object.get("information")
        if not isinstance(information, dict):
            continue

        actual_store = str(
            information.get("storeId")
            or information.get("storeNumber")
            or ""
        )
        if actual_store != store:
            continue

        article_data = store_object.get("articleData")
        if not isinstance(article_data, dict):
            return {
                "semantic": "missing_article_data",
                "store": actual_store,
                "stock": None,
                "price": None,
            }

        pricing = article_data.get("pricing")
        return {
            "semantic": "verified_product_page",
            "store": actual_store,
            "stock": nonnegative_int_or_none(
                article_data.get("stock"),
            ),
            "price": (
                decimal_or_none(pricing.get("grossPrice"))
                if isinstance(pricing, dict)
                else None
            ),
        }

    return {
        "semantic": "product_or_store_missing",
        "stock": None,
        "price": None,
    }


def parse_store_directory(
    body: bytes,
    allowlist: set[str],
) -> dict[str, Any]:
    try:
        root = json.loads(body.decode("utf-8"))
    except Exception:
        return {
            "semantic": "malformed_json",
            "shape": "invalid_json",
        }

    shape = json_shape(root)
    stores = (
        root.get("stores")
        if isinstance(root, dict)
        else None
    )
    if not isinstance(stores, list):
        return {
            "semantic": "unexpected_json",
            "shape": shape,
        }

    safe_stores = []
    for item in stores:
        if not isinstance(item, dict):
            continue
        store_number = str(
            item.get("storeNumber") or "",
        )
        if not STORE_RE.fullmatch(store_number):
            continue

        address = (
            item.get("address")
            if isinstance(item.get("address"), dict)
            else {}
        )

        def first(*values):
            return next(
                (
                    value
                    for value in values
                    if value not in (None, "")
                ),
                None,
            )

        safe_stores.append(
            {
                "storeNumber": store_number,
                "name": (
                    item.get("name")
                    if isinstance(item.get("name"), str)
                    else None
                ),
                "city": first(
                    item.get("city"),
                    address.get("city"),
                ),
                "street": first(
                    item.get("street"),
                    address.get("street"),
                ),
                "zip": first(
                    item.get("zip"),
                    address.get("zip"),
                ),
                "lat": first(
                    item.get("lat"),
                    address.get("lat"),
                ),
                "lon": first(
                    item.get("lon"),
                    address.get("lon"),
                ),
                "isActive": (
                    item.get("isActive")
                    if isinstance(
                        item.get("isActive"),
                        bool,
                    )
                    else None
                ),
            },
        )

    active = {
        item["storeNumber"]
        for item in safe_stores
        if item["isActive"] is not False
    }
    return {
        "semantic": "verified_directory",
        "shape": shape,
        "storeCount": len(safe_stores),
        "activeStoreCount": len(active),
        "sample": safe_stores[:5],
        "activeNotInAllowlist": sorted(
            active - allowlist,
        ),
        "allowlistNotActive": sorted(
            allowlist - active,
        ),
    }


def load_allowlist(path: Path) -> set[str]:
    text = path.read_text(encoding="utf-8")
    return set(
        re.findall(r'"(\d{3})"', text),
    )


def availability_url(
    store: str,
    obik: str,
) -> str:
    encoded = urllib.parse.quote(obik)
    return (
        f"{BASE}/api/disc/article-service-proxy/"
        f"store-specific-articles/v2/PL/pl/{store}"
        f"?articleNumbers={encoded}"
    )


def full_page_url(
    store: str,
    obik: str,
) -> str:
    query = urllib.parse.urlencode(
        {
            "storeNumber": store,
            "redirectUrl": f"/p/{obik}",
        },
    )
    return f"{BASE}/api/disc/store/change?{query}"


def safe_transport(
    raw: dict[str, Any],
) -> dict[str, Any]:
    keys = (
        "httpStatus",
        "contentType",
        "durationMs",
        "challengeDetected",
        "truncated",
        "transportError",
    )
    return {
        key: raw.get(key)
        for key in keys
        if key in raw
    }


def probe_pair(
    store: str,
    obik: str,
    *,
    prime_session: bool = False,
) -> dict[str, Any]:
    opener = make_opener()
    prime_transport = None
    if prime_session:
        prime = fetch(
            opener,
            full_page_url(store, obik),
            HTML_ACCEPT,
        )
        prime_transport = safe_transport(prime)

    raw = fetch(
        opener,
        availability_url(store, obik),
        JSON_ACCEPT,
    )
    result = {
        "store": store,
        "obik": obik,
        "sessionPrimed": prime_session,
        **safe_transport(raw),
    }
    if prime_transport is not None:
        result["primeTransport"] = prime_transport

    if raw.get("httpStatus") == 200:
        parsed = parse_availability(
            raw["body"],
            obik,
        )
        result.update(parsed)
        if (
            parsed.get("semantic")
            in ("malformed_json", "unexpected_json")
            and raw.get("challengeDetected")
        ):
            result["semantic"] = "challenge_or_unexpected_payload"
    else:
        result["semantic"] = (
            "blocked_or_http_failure"
            if raw.get("httpStatus") is not None
            else "transport_failure"
        )
    return result


def crosscheck_pair(
    store: str,
    obik: str,
) -> dict[str, Any]:
    opener = make_opener()
    full_raw = fetch(
        opener,
        full_page_url(store, obik),
        HTML_ACCEPT,
    )
    full = {
        "transport": safe_transport(full_raw),
    }
    if full_raw.get("httpStatus") == 200:
        full.update(
            full_page_fact(
                full_raw["body"].decode(
                    "utf-8",
                    "replace",
                ),
                obik,
                store,
            ),
        )
    else:
        full["semantic"] = (
            "blocked_or_http_failure"
            if full_raw.get("httpStatus") is not None
            else "transport_failure"
        )

    light_raw = fetch(
        opener,
        availability_url(store, obik),
        JSON_ACCEPT,
    )
    lightweight = {
        "store": store,
        "obik": obik,
        "sessionPrimed": True,
        "primeTransport": safe_transport(full_raw),
        **safe_transport(light_raw),
    }
    if light_raw.get("httpStatus") == 200:
        parsed = parse_availability(
            light_raw["body"],
            obik,
        )
        lightweight.update(parsed)
        if (
            parsed.get("semantic")
            in ("malformed_json", "unexpected_json")
            and light_raw.get("challengeDetected")
        ):
            lightweight["semantic"] = "challenge_or_unexpected_payload"
    else:
        lightweight["semantic"] = (
            "blocked_or_http_failure"
            if light_raw.get("httpStatus") is not None
            else "transport_failure"
        )

    stock_match = (
        lightweight.get("stock")
        == full.get("stock")
        if (
            lightweight.get("semantic")
            in ("verified_stock", "missing_stock")
            and full.get("semantic")
            == "verified_product_page"
        )
        else None
    )
    price_match = (
        lightweight.get("price")
        == full.get("price")
        if (
            lightweight.get("price") is not None
            and full.get("price") is not None
        )
        else None
    )
    return {
        "store": store,
        "obik": obik,
        "lightweight": lightweight,
        "fullProductPage": full,
        "stockMatch": stock_match,
        "priceMatch": price_match,
    }


def extract_api_paths(text: str) -> list[str]:
    paths = set()
    pattern = re.compile(
        r'["\'](/api/disc/[^"\'\\\s]{1,220})["\']',
    )
    for match in pattern.finditer(text):
        raw = match.group(1)
        safe = raw.split("?", 1)[0]
        if any(
            term in safe.lower()
            for term in (
                "store",
                "article",
                "availability",
                "market",
            )
        ):
            paths.add(safe)
    return sorted(paths)[:50]


def discover_ui_endpoints(
    html: str,
) -> dict[str, Any]:
    script_urls = []
    pattern = re.compile(
        r'<script\b[^>]*\bsrc=["\']([^"\']+)["\']',
        re.IGNORECASE,
    )
    for match in pattern.finditer(html):
        url = urllib.parse.urljoin(
            BASE,
            match.group(1),
        )
        parsed = urllib.parse.urlsplit(url)
        if (
            parsed.scheme == "https"
            and parsed.hostname == "www.obi.pl"
            and parsed.path.endswith(".js")
        ):
            script_urls.append(url)

    paths = set(extract_api_paths(html))
    scanned = 0
    failures = 0
    for url in script_urls[:12]:
        raw = fetch(
            make_opener(),
            url,
            "*/*",
        )
        if raw.get("httpStatus") != 200:
            failures += 1
            continue
        scanned += 1
        text = raw["body"].decode(
            "utf-8",
            "replace",
        )
        paths.update(
            extract_api_paths(text),
        )

    return {
        "scriptCandidates": len(script_urls),
        "scriptsScanned": scanned,
        "scriptFailures": failures,
        "discoveredApiPaths": sorted(paths)[:50],
    }


def performance(
    stores: list[str],
    obik: str,
) -> dict[str, Any]:
    sequential = [
        probe_pair(store, obik)
        for store in stores[:3]
    ]

    started = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(
        max_workers=min(5, len(stores)),
    ) as executor:
        parallel = list(
            executor.map(
                lambda store: probe_pair(
                    store,
                    obik,
                ),
                stores[:5],
            ),
        )
    parallel_wall_ms = round(
        (time.monotonic() - started) * 1000,
    )

    def stats(items):
        values = [
            item["durationMs"]
            for item in items
            if isinstance(
                item.get("durationMs"),
                int,
            )
        ]
        return {
            "count": len(values),
            "sumMs": sum(values),
            "meanMs": (
                round(statistics.mean(values))
                if values
                else None
            ),
            "maxMs": max(values) if values else None,
        }

    return {
        "oneRequestMs": (
            sequential[0].get("durationMs")
            if sequential
            else None
        ),
        "threeSequential": stats(sequential),
        "parallelSample": {
            "stores": stores[:5],
            "wallMs": parallel_wall_ms,
            **stats(parallel),
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--obiks",
        default="3496072,6743009,7156243",
    )
    parser.add_argument(
        "--stores",
        default="075,074,078",
    )
    parser.add_argument(
        "--allowlist",
        default=(
            "app/src/main/java/pl/lukaszpeciak/"
            "towarownik/product/ObiStores.kt"
        ),
    )
    parser.add_argument(
        "--profile",
        default=(
            "app/src/main/java/pl/lukaszpeciak/"
            "towarownik/product/"
            "ObiBrowserCompatibilityProfile.kt"
        ),
    )
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    obiks = [
        value.strip()
        for value in args.obiks.split(",")
        if value.strip()
    ]
    stores = [
        value.strip()
        for value in args.stores.split(",")
        if value.strip()
    ]

    for obik in obiks:
        validate_obik(obik)
    for store in stores:
        validate_store(store)
    if len(obiks) < 3 or len(stores) < 3:
        raise SystemExit(
            "probe requires at least 3 OBIKs and 3 stores",
        )

    profile_source = Path(
        args.profile,
    ).read_text(encoding="utf-8")
    if not transport_profile_matches_kotlin(
        profile_source,
    ):
        raise SystemExit(
            "research transport profile drifted "
            "from Android profile",
        )

    allowlist = load_allowlist(
        Path(args.allowlist),
    )

    matrix = []
    for obik in obiks:
        for store in stores:
            matrix.append(
                probe_pair(
                    store,
                    obik,
                ),
            )

    primed_sample = [
        probe_pair(
            store,
            obik,
            prime_session=True,
        )
        for store, obik in zip(
            stores[:3],
            obiks[:3],
        )
    ]

    selected = list(
        zip(
            stores[:3],
            obiks[:3],
        ),
    )
    cross_checks = [
        crosscheck_pair(store, obik)
        for store, obik in selected
    ]

    discovery_raw = fetch(
        make_opener(),
        full_page_url(
            stores[0],
            obiks[0],
        ),
        HTML_ACCEPT,
    )
    if discovery_raw.get("httpStatus") == 200:
        ui_discovery = discover_ui_endpoints(
            discovery_raw["body"].decode(
                "utf-8",
                "replace",
            ),
        )
    else:
        ui_discovery = {
            "semantic": (
                "product_page_unavailable_for_static_scan"
            ),
            "transport": safe_transport(
                discovery_raw,
            ),
        }

    directory_raw = fetch(
        make_opener(),
        f"{BASE}/api/disc/store/locator/country/PL",
        JSON_ACCEPT,
    )
    directory = {
        "transport": safe_transport(
            directory_raw,
        ),
    }
    if (
        directory_raw.get("httpStatus") == 200
        and not directory_raw.get(
            "challengeDetected",
        )
    ):
        directory.update(
            parse_store_directory(
                directory_raw["body"],
                allowlist,
            ),
        )
    else:
        directory["semantic"] = (
            "blocked_or_http_failure"
            if directory_raw.get(
                "httpStatus",
            )
            is not None
            else "transport_failure"
        )

    summary = {
        "transportProfile": {
            "userAgent": "android-chrome-synthetic",
            "acceptLanguage": ACCEPT_LANGUAGE,
            "cookieSession": True,
            "profileMatchesAndroid": True,
        },
        "candidateEndpoint": (
            "/api/disc/article-service-proxy/"
            "store-specific-articles/v2/PL/pl/"
            "{storeNumber}?articleNumbers={OBIK}"
        ),
        "matrix": matrix,
        "primedSample": primed_sample,
        "crossChecks": cross_checks,
        "storeDirectory": directory,
        "uiEndpointDiscovery": ui_discovery,
        "performance": performance(
            stores,
            obiks[0],
        ),
    }

    destination = Path(args.out)
    destination.parent.mkdir(
        parents=True,
        exist_ok=True,
    )
    destination.write_text(
        json.dumps(
            summary,
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    print(
        json.dumps(
            summary,
            ensure_ascii=False,
            indent=2,
        ),
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
