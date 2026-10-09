import json
import unittest
from pathlib import Path

import kwant_live_contract_probe as probe


class UrlSanitizationTest(unittest.TestCase):
    def test_expected_kwant_url_is_accepted_and_query_is_dropped(self):
        expected, safe = probe.sanitize_kwant_url(
            "https://kwant.net.pl/produkt/example-580?token=secret#frag"
        )
        self.assertTrue(expected)
        self.assertEqual(
            "https://kwant.net.pl/produkt/example-580",
            safe,
        )

    def test_network_sanitizer_accepts_only_kwant_frontend_and_services_hosts(self):
        for value in (
            "https://kwant.net.pl/",
            "https://services.kwant.net.pl/api/front/search-engine/page",
        ):
            with self.subTest(value=value):
                expected, safe = probe.sanitize_kwant_network_url(value)
                self.assertTrue(expected)
                self.assertTrue(safe.startswith("https://"))

        expected, safe = probe.sanitize_kwant_network_url(
            "https://services.kwant.net.pl.evil.example/api/front/search-engine/page"
        )
        self.assertFalse(expected)
        self.assertTrue(safe.startswith("REDACTED_"))

    def test_unexpected_hosts_and_unsafe_url_shapes_are_redacted(self):
        cases = [
            "https://example.com/produkt/example-580",
            "http://kwant.net.pl/produkt/example-580",
            "https://user:pass@kwant.net.pl/produkt/example-580",
            "https://kwant.net.pl:444/produkt/example-580",
            "https://[invalid",
        ]
        for value in cases:
            with self.subTest(value=value):
                expected, safe = probe.sanitize_kwant_url(value)
                self.assertFalse(expected)
                self.assertTrue(safe.startswith("REDACTED_"))


class SanitizationTest(unittest.TestCase):
    def test_secret_query_and_body_values_do_not_leak(self):
        secret = "SUPER_SECRET_SESSION_TOKEN"
        query = probe.sanitized_query(
            "https://kwant.net.pl/search?"
            "query=MBN116E&sessionToken=" + secret
        )
        body = probe.sanitize_body_shape(
            json.dumps(
                {
                    "query": "MBN116E",
                    "warehouseId": "205",
                    "csrfToken": secret,
                    "auth": secret,
                }
            ),
            content_type="application/json",
        )

        self.assertIn("sessionToken", query["names"])
        self.assertNotIn("sessionToken", query["safeValues"])
        self.assertEqual("MBN116E", query["safeValues"]["query"])

        self.assertIn("csrfToken", body["fields"])
        self.assertIn("auth", body["fields"])
        self.assertNotIn("csrfToken", body["safeValues"])
        self.assertNotIn("auth", body["safeValues"])
        self.assertEqual("MBN116E", body["safeValues"]["query"])
        self.assertEqual("205", body["safeValues"]["warehouseId"])

        serialized = json.dumps(
            {"query": query, "body": body},
            ensure_ascii=False,
        )
        self.assertNotIn(secret, serialized)

    def test_selected_branch_depstock_is_safe_query_evidence(self):
        query = probe.sanitized_query(
            "https://services.kwant.net.pl/api/front/products/580/current"
            "?depstock=205"
        )

        self.assertIn("depstock", query["names"])
        self.assertEqual("205", query["safeValues"]["depstock"])

    def test_cookie_summary_contains_names_not_values(self):
        secret = "COOKIE_VALUE_SHOULD_NOT_LEAK"
        summary = probe.cookie_summary(
            [
                {
                    "name": "branch",
                    "value": secret,
                    "domain": "kwant.net.pl",
                    "path": "/",
                    "secure": True,
                    "httpOnly": False,
                    "sameSite": "Lax",
                }
            ]
        )

        serialized = json.dumps(summary)
        self.assertIn("branch", serialized)
        self.assertNotIn(secret, serialized)

    def test_department_cookie_exposes_only_conservative_public_branch_ids(self):
        accepted = {
            "205": "205",
            "NS205": "NS205",
            "205-NS": "205-NS",
            "NS-205": "NS-205",
        }
        for raw, expected in accepted.items():
            with self.subTest(raw=raw):
                self.assertEqual(
                    expected,
                    probe.safe_department_cookie_value(raw),
                )

    def test_department_cookie_rejects_secret_random_and_structured_values(self):
        rejected = [
            "eyJhbGciOiJIUzI1NiJ9.abc.def",
            "aZ9K3mP2xQ8L7tR5",
            '{"branch":205}',
            "%7B%22branch%22%3A205%7D",
            "session-205",
            "205 Nowy Sącz",
            "A" * 40,
        ]
        for raw in rejected:
            with self.subTest(raw=raw):
                self.assertEqual(
                    probe.REDACTED,
                    probe.safe_department_cookie_value(raw),
                )

    def test_unrelated_cookie_values_remain_names_only(self):
        secret = "UNRELATED_COOKIE_SECRET_VALUE"
        cookies = [
            {
                "name": "trackingCookie",
                "value": secret,
                "domain": "kwant.net.pl",
                "path": "/",
                "secure": True,
                "httpOnly": False,
                "sameSite": "Lax",
            }
        ]

        serialized = json.dumps(
            probe.cookie_summary(cookies),
            ensure_ascii=False,
        )

        self.assertIn("trackingCookie", serialized)
        self.assertNotIn(secret, serialized)
        self.assertEqual(
            probe.UNKNOWN,
            probe.department_cookie_value(
                {
                    "kwant.net.pl|/|trackingCookie": secret,
                }
            ),
        )

    def test_safe_summary_revalidates_department_cookie_value_at_report_boundary(self):
        secret = "eyJhbGciOiJIUzI1NiJ9.secret.signature"
        summary = probe.build_safe_summary(
            requested_branch_label="Nowy Sącz",
            department_cookie_observed=True,
            department_cookie_persisted=True,
            department_cookie_value_safe=secret,
        )

        self.assertEqual(
            probe.REDACTED,
            summary["branch"]["departmentCookieValue"],
        )
        self.assertFalse(
            probe.safe_report_contains_secret(summary, secret)
        )

    def test_generic_secret_sanitization_is_unchanged_by_department_cookie_exception(self):
        secret = "GENERIC_AUTH_SECRET"
        body = probe.sanitize_body_shape(
            json.dumps(
                {
                    "warehouseId": "205",
                    "authToken": secret,
                }
            ),
            content_type="application/json",
        )

        self.assertEqual(
            "205",
            body["safeValues"]["warehouseId"],
        )
        self.assertNotIn(
            "authToken",
            body["safeValues"],
        )
        self.assertNotIn(
            secret,
            json.dumps(body),
        )

    def test_safe_summary_does_not_contain_injected_secret(self):
        secret = "VERY_SECRET_TOKEN"
        summary = probe.build_safe_summary(
            requested_branch_label="Nowy Sącz",
            network=[
                {
                    "action": "branch:select",
                    "method": "POST",
                    "path": "/branch/select",
                    "query": {"names": ["token"], "safeValues": {}},
                    "body": {
                        "encoding": "json",
                        "fields": ["token"],
                        "safeValues": {},
                    },
                    "status": 200,
                    "contentType": "application/json",
                }
            ],
        )
        self.assertFalse(
            probe.safe_report_contains_secret(summary, secret)
        )



class NumericProductRouteProbeTest(unittest.TestCase):
    def test_next_data_product_id_reads_public_product_identity(self):
        html = """
        <html><body>
        <script id="__NEXT_DATA__" type="application/json">
        {"props":{"pageProps":{"product":{"id":580}}}}
        </script>
        </body></html>
        """
        self.assertEqual(
            "580",
            probe.next_data_product_id(html),
        )

    def test_next_data_product_id_is_unknown_when_missing(self):
        self.assertEqual(
            probe.UNKNOWN,
            probe.next_data_product_id("<html></html>"),
        )

    def test_safe_summary_carries_only_safe_numeric_route_evidence(self):
        route = {
            "requestedPath": "/produkt/580",
            "responseStatus": 308,
            "redirectChain": [
                {
                    "status": 308,
                    "fromPath": "/produkt/580",
                    "toPath": "/produkt/example-580",
                }
            ],
            "finalStatus": 200,
            "finalUrl": "https://kwant.net.pl/produkt/example-580",
            "finalParsedProductId": "580",
            "matchesRequestedProductId": True,
        }
        summary = probe.build_safe_summary(
            requested_branch_label="Nowy Sącz",
            numeric_product_route=route,
        )

        self.assertEqual(route, summary["numericProductRoute"])
        serialized = json.dumps(summary, ensure_ascii=False)
        self.assertNotIn("COOKIE_SECRET_VALUE", serialized)
        self.assertNotIn("AUTHORIZATION_SECRET_VALUE", serialized)

    def test_numeric_product_id_input_is_strictly_bounded_digits(self):
        probe.validate_inputs(
            "MBN116E",
            "Nowy Sącz",
            "wyłącznik nadprądowy B16 Hager",
            "580",
        )
        with self.assertRaises(ValueError):
            probe.validate_inputs(
                "MBN116E",
                "Nowy Sącz",
                "wyłącznik nadprądowy B16 Hager",
                "../580",
            )


class MissingEvidenceTest(unittest.TestCase):
    def test_unknown_is_used_when_evidence_is_absent(self):
        summary = probe.build_safe_summary(
            requested_branch_label="Nowy Sącz",
        )

        self.assertFalse(
            summary["branch"]["targetBranchResolved"]
        )
        self.assertEqual(
            probe.UNKNOWN,
            summary["branch"]["targetBranchPagePath"],
        )
        self.assertEqual(
            probe.UNKNOWN,
            summary["branch"]["selectionObserved"],
        )
        self.assertEqual(
            probe.UNKNOWN,
            summary["branch"]["selectionPersisted"],
        )
        self.assertEqual(
            probe.UNKNOWN,
            summary["branch"]["departmentCookieObserved"],
        )
        self.assertEqual(
            probe.UNKNOWN,
            summary["branch"]["departmentCookiePersisted"],
        )
        self.assertEqual(
            probe.UNKNOWN,
            summary["branch"]["departmentCookieValue"],
        )
        self.assertEqual(
            probe.UNKNOWN,
            summary["branch"]["backendBranchIdentifier"],
        )
        self.assertEqual(
            probe.UNKNOWN,
            summary["search"]["articleCode"]["status"],
        )
        self.assertEqual(
            probe.UNKNOWN,
            summary["product"]["ean"],
        )
        self.assertEqual([], summary["product"]["technicalData"])

    def test_optional_product_fields_are_robustly_missing(self):
        snapshot = probe.product_snapshot(
            page_url=(
                "https://kwant.net.pl/produkt/"
                "synthetic-mbn116e-580"
            ),
            body_text="Synthetic product",
            json_ld_blocks=[],
            branch_label="Nowy Sącz",
        )

        self.assertEqual("580", snapshot["productId"])
        self.assertEqual(probe.UNKNOWN, snapshot["articleNumber"])
        self.assertEqual(probe.UNKNOWN, snapshot["ean"])
        self.assertEqual(probe.UNKNOWN, snapshot["manufacturer"])
        self.assertEqual(probe.UNKNOWN, snapshot["price"])
        self.assertEqual(
            probe.UNKNOWN,
            snapshot["selectedBranchStock"],
        )


class ProductParsingTest(unittest.TestCase):
    def test_structured_product_data_extracts_public_identity(self):
        blocks = [
            json.dumps(
                {
                    "@context": "https://schema.org",
                    "@type": "Product",
                    "name": "Wyłącznik MBN116E HAGER",
                    "sku": "MBN116E/HAG",
                    "gtin13": "3250614312762",
                    "brand": {"@type": "Brand", "name": "HAGER"},
                    "image": [
                        "https://cdn.example.test/product.jpg"
                    ],
                    "url": (
                        "https://kwant.net.pl/produkt/"
                        "wylacznik-mbn116e-hager-580"
                    ),
                    "offers": {
                        "@type": "Offer",
                        "price": "19.99",
                    },
                }
            )
        ]

        fields = probe.structured_product_fields(
            blocks,
            "https://kwant.net.pl/produkt/wylacznik-580",
        )

        self.assertEqual(
            "Wyłącznik MBN116E HAGER",
            fields["name"],
        )
        self.assertEqual("MBN116E/HAG", fields["articleNumber"])
        self.assertEqual("3250614312762", fields["ean"])
        self.assertEqual("HAGER", fields["manufacturer"])
        self.assertEqual("19.99", fields["structuredPrice"])
        self.assertEqual(
            "https://cdn.example.test/product.jpg",
            fields["imageUrl"],
        )

    def test_text_parser_extracts_known_public_fields_and_stock_scopes(self):
        body = """
        Wyłącznik nadprądowy B16 A 1P 6kA MBN116E HAGER
        Producent:
        HAGER
        Kod produktu:
        MBN116E/HAG
        EAN:
        3250614312762
        19,99 zł brutto
        Centrala: 15 szt.
        W oddziałach: 7 szt.
        Nowy Sącz: 3 szt.
        Specyfikacja
        Napięcie znamionowe [V]
        230 / 400 V
        Prąd znamionowy [A]
        16A
        Opis
        """

        snapshot = probe.product_snapshot(
            page_url=(
                "https://kwant.net.pl/produkt/"
                "wylacznik-nadpradowy-mbn116e-hager-580"
            ),
            body_text=body,
            json_ld_blocks=[],
            branch_label="Nowy Sącz",
        )

        self.assertEqual("MBN116E/HAG", snapshot["articleNumber"])
        self.assertEqual("3250614312762", snapshot["ean"])
        self.assertEqual("HAGER", snapshot["manufacturer"])
        self.assertEqual("19,99", snapshot["price"])
        self.assertEqual("ONLINE", snapshot["priceScope"])
        self.assertEqual("15 szt.", snapshot["centralStock"])
        self.assertEqual(
            "7 szt.",
            snapshot["aggregateBranchStock"],
        )
        self.assertEqual(
            "3 szt.",
            snapshot["selectedBranchStock"],
        )
        self.assertIn(
            "Napięcie znamionowe [V]",
            snapshot["technicalData"],
        )


class _FakeCandidate:
    def __init__(self, text):
        self._text = text

    def inner_text(self, timeout=0):
        return self._text


class _FakeLocator:
    def __init__(self, items):
        self._items = items

    def count(self):
        return len(self._items)

    def nth(self, index):
        return self._items[index]


class _FakeBranchButton:
    def __init__(self, token, *, visible=True, enabled=True):
        self.token = token
        self._visible = visible
        self._enabled = enabled
        self.clicked = False

    def is_visible(self):
        return self._visible

    def is_enabled(self):
        return self._enabled

    def click(self, timeout=0):
        self.clicked = True


class _FakeBranchLink:
    def __init__(self, href, text, parent=None):
        self.href = href
        self.text = text
        self.parent = parent

    def get_attribute(self, name):
        return self.href if name == "href" else None

    def inner_text(self, timeout=0):
        return self.text

    def locator(self, selector):
        if selector == "xpath=.." and self.parent is not None:
            return _FakeLocatorWithFirst([self.parent])
        return _FakeLocatorWithFirst([])


class _FakeBranchCard:
    def __init__(self, text, links, buttons, parent=None):
        self.text = text
        self.links = links
        self.buttons = buttons
        self.parent = parent
        for link in self.links:
            link.parent = self

    def inner_text(self, timeout=0):
        return self.text

    def locator(self, selector):
        if selector == "xpath=..":
            return _FakeLocatorWithFirst(
                [self.parent] if self.parent is not None else []
            )
        if "a[href*='hurtownia-elektryczna']" in selector:
            return _FakeLocatorWithFirst(self.links)
        return _FakeLocatorWithFirst([])

    def get_by_role(self, role, name=None):
        if role == "button":
            return _FakeLocatorWithFirst(self.buttons)
        return _FakeLocatorWithFirst([])


class _FakeLocatorWithFirst(_FakeLocator):
    @property
    def first(self):
        return self._items[0]


class _FakeBranchPage:
    def __init__(self, url, cards):
        self.url = url
        self.links = [
            link
            for card in cards
            for link in card.links
        ]

    def locator(self, selector):
        if "a[href*='hurtownia-elektryczna']" in selector:
            return _FakeLocatorWithFirst(self.links)
        return _FakeLocatorWithFirst([])


def _fake_branch_page(*, hrefs, buttons, card_text="Nowy Sącz Wybierz oddział"):
    links = [
        _FakeBranchLink(href, "Nowy Sącz")
        for href in hrefs
    ]
    card = _FakeBranchCard(card_text, links, buttons)
    return _FakeBranchPage(
        "https://kwant.net.pl/lista-hurtowni-elektrycznych",
        [card],
    ), card


class _GenericBranchListPage:
    def locator(self, selector):
        if "[class*='branch']" in selector:
            return _FakeLocator(
                [_FakeCandidate("Oddział Nowy Sącz Wybierz oddział")]
            )
        return _FakeLocator([])


class _SelectedBranchPage:
    def locator(self, selector):
        if "[aria-current='true']" in selector:
            return _FakeLocator(
                [_FakeCandidate("Aktualny oddział Nowy Sącz")]
            )
        return _FakeLocator([])


class BranchTargetingTest(unittest.TestCase):
    def candidate(
        self,
        *,
        label,
        page_url,
        card_text,
        branch_page_urls=None,
        select_button_count=1,
        button_token="button",
    ):
        return {
            "linkIndex": 0,
            "linkText": label,
            "pageUrl": page_url,
            "cardText": card_text,
            "branchPageUrls": (
                branch_page_urls
                if branch_page_urls is not None
                else [page_url]
            ),
            "selectButtonCount": select_button_count,
            "buttonToken": button_token,
        }

    def test_multiple_branch_cards_resolve_exact_requested_branch(self):
        bialystok = self.candidate(
            label="Białystok",
            page_url=(
                "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
                "hurtownia-elektryczna-bialystok/216"
            ),
            card_text="Białystok Wybierz oddział",
            button_token="bialystok-button",
        )
        nowy_sacz = self.candidate(
            label="Nowy Sącz",
            page_url=(
                "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
                "hurtownia-elektryczna-nowy%20sacz/205"
            ),
            card_text="Nowy Sącz Wybierz oddział",
            button_token="nowy-sacz-button",
        )
        central = self.candidate(
            label="Magazyn centralny",
            page_url=(
                "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
                "magazyn-centralny/1"
            ),
            card_text="Magazyn centralny Wybierz oddział",
            button_token="central-button",
        )

        resolved = probe.resolve_unique_branch_candidate(
            [bialystok, nowy_sacz, central],
            "Nowy Sącz",
        )

        self.assertIsNotNone(resolved)
        self.assertEqual("nowy-sacz-button", resolved["buttonToken"])
        self.assertEqual(
            "205",
            probe.extract_branch_page_identifier(
                resolved["pageUrl"]
            ),
        )

    def test_generic_ancestor_with_multiple_branch_buttons_is_ineligible(self):
        generic_ancestor = self.candidate(
            label="Nowy Sącz",
            page_url=(
                "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
                "hurtownia-elektryczna-nowy%20sacz/205"
            ),
            card_text=(
                "Białystok Wybierz oddział "
                "Nowy Sącz Wybierz oddział"
            ),
            branch_page_urls=[
                (
                    "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
                    "hurtownia-elektryczna-bialystok/216"
                ),
                (
                    "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
                    "hurtownia-elektryczna-nowy%20sacz/205"
                ),
            ],
            select_button_count=2,
            button_token="first-generic-button",
        )

        self.assertIsNone(
            probe.resolve_unique_branch_candidate(
                [generic_ancestor],
                "Nowy Sącz",
            )
        )

    def test_duplicate_candidates_same_branch_identity_resolve_but_missing_label_does_not(self):
        first = self.candidate(
            label="Nowy Sącz",
            page_url=(
                "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
                "hurtownia-elektryczna-nowy%20sacz/205"
            ),
            card_text="Nowy Sącz Wybierz oddział",
            button_token="first",
        )
        duplicate = dict(first)
        duplicate["linkIndex"] = 1
        duplicate["buttonToken"] = "second"

        resolved = probe.resolve_unique_branch_candidate(
            [first, duplicate],
            "Nowy Sącz",
        )

        self.assertIsNotNone(resolved)
        self.assertEqual("first", resolved["buttonToken"])
        self.assertIsNone(
            probe.resolve_unique_branch_candidate(
                [first],
                "Kraków",
            )
        )

    def test_three_raw_links_to_same_nowy_sacz_url_are_one_unique_identity(self):
        url = (
            "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
            "hurtownia-elektryczna-nowy%20sacz/205"
        )
        candidate = self.candidate(
            label="Nowy Sącz",
            page_url=url,
            card_text="Nowy Sącz Wybierz oddział",
            branch_page_urls=[url, f"{url}/", url],
            select_button_count=2,
        )

        self.assertEqual(
            [url],
            probe.unique_branch_page_urls(
                candidate["branchPageUrls"],
            ),
        )
        self.assertTrue(
            probe.branch_target_candidate_valid(
                candidate,
                "Nowy Sącz",
            )
        )

    def test_two_matching_buttons_keep_candidate_valid_and_first_visible_enabled_is_selected(self):
        url = (
            "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
            "hurtownia-elektryczna-nowy%20sacz/205"
        )
        first = _FakeBranchButton("first")
        second = _FakeBranchButton("second")
        page, _ = _fake_branch_page(
            hrefs=[url, url, url],
            buttons=[first, second],
        )

        candidates = probe.enumerate_branch_candidates(
            page,
            "Nowy Sącz",
        )
        target = probe.resolve_unique_branch_candidate(
            candidates,
            "Nowy Sącz",
        )
        selected = probe.validated_branch_button(
            page,
            candidate=target,
            branch_label="Nowy Sącz",
        )

        self.assertIsNotNone(target)
        self.assertEqual(2, target["selectButtonCount"])
        self.assertIs(first, selected)

    def test_hidden_or_disabled_first_button_falls_through_to_visible_enabled_second(self):
        url = (
            "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
            "hurtownia-elektryczna-nowy%20sacz/205"
        )
        hidden = _FakeBranchButton(
            "hidden",
            visible=False,
            enabled=True,
        )
        disabled = _FakeBranchButton(
            "disabled",
            visible=True,
            enabled=False,
        )
        usable = _FakeBranchButton(
            "usable",
            visible=True,
            enabled=True,
        )
        page, _ = _fake_branch_page(
            hrefs=[url, url],
            buttons=[hidden, disabled, usable],
        )

        target = probe.resolve_unique_branch_candidate(
            probe.enumerate_branch_candidates(
                page,
                "Nowy Sącz",
            ),
            "Nowy Sącz",
        )
        selected = probe.validated_branch_button(
            page,
            candidate=target,
            branch_label="Nowy Sącz",
        )

        self.assertIs(usable, selected)

    def test_multiple_raw_links_to_different_branch_urls_are_rejected(self):
        nowy_sacz = (
            "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
            "hurtownia-elektryczna-nowy%20sacz/205"
        )
        bialystok = (
            "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
            "hurtownia-elektryczna-bialystok/216"
        )
        candidate = self.candidate(
            label="Nowy Sącz",
            page_url=nowy_sacz,
            card_text=(
                "Białystok Wybierz oddział "
                "Nowy Sącz Wybierz oddział"
            ),
            branch_page_urls=[
                nowy_sacz,
                bialystok,
                nowy_sacz,
            ],
            select_button_count=3,
        )

        self.assertFalse(
            probe.branch_target_candidate_valid(
                candidate,
                "Nowy Sącz",
            )
        )
        self.assertIsNone(
            probe.resolve_unique_branch_candidate(
                [candidate],
                "Nowy Sącz",
            )
        )

    def test_generic_multi_branch_ancestor_with_multiple_buttons_never_selects(self):
        nowy_sacz = (
            "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
            "hurtownia-elektryczna-nowy%20sacz/205"
        )
        bialystok = (
            "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
            "hurtownia-elektryczna-bialystok/216"
        )
        page, _ = _fake_branch_page(
            hrefs=[bialystok, nowy_sacz],
            buttons=[
                _FakeBranchButton("first"),
                _FakeBranchButton("second"),
            ],
            card_text=(
                "Białystok Wybierz oddział "
                "Nowy Sącz Wybierz oddział"
            ),
        )

        self.assertEqual(
            [],
            probe.enumerate_branch_candidates(
                page,
                "Nowy Sącz",
            ),
        )

    def test_no_visible_enabled_button_returns_none_without_clicking(self):
        url = (
            "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
            "hurtownia-elektryczna-nowy%20sacz/205"
        )
        hidden = _FakeBranchButton(
            "hidden",
            visible=False,
            enabled=True,
        )
        disabled = _FakeBranchButton(
            "disabled",
            visible=True,
            enabled=False,
        )
        page, _ = _fake_branch_page(
            hrefs=[url, url],
            buttons=[hidden, disabled],
        )

        target = probe.resolve_unique_branch_candidate(
            probe.enumerate_branch_candidates(
                page,
                "Nowy Sącz",
            ),
            "Nowy Sącz",
        )
        selected = probe.validated_branch_button(
            page,
            candidate=target,
            branch_label="Nowy Sącz",
        )

        self.assertIsNotNone(target)
        self.assertIsNone(selected)
        self.assertFalse(hidden.clicked)
        self.assertFalse(disabled.clicked)

    def test_branch_card_text_can_resolve_when_href_slug_omits_label(self):
        numeric_href = self.candidate(
            label="Szczegóły oddziału",
            page_url=(
                "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
                "hurtownia-elektryczna/312"
            ),
            card_text="Tarnów Wybierz oddział",
        )

        resolved = probe.resolve_unique_branch_candidate(
            [numeric_href],
            "Tarnów",
        )

        self.assertIsNotNone(resolved)
        self.assertEqual(
            "312",
            probe.extract_branch_page_identifier(
                resolved["pageUrl"]
            ),
        )

    def test_branch_resolution_uses_label_and_link_not_hard_coded_205(self):
        tarnow = self.candidate(
            label="Tarnów",
            page_url=(
                "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
                "hurtownia-elektryczna-tarnow/312"
            ),
            card_text="Tarnów Wybierz oddział",
        )

        resolved = probe.resolve_unique_branch_candidate(
            [tarnow],
            "Tarnów",
        )

        self.assertIsNotNone(resolved)
        self.assertEqual(
            "312",
            probe.extract_branch_page_identifier(
                resolved["pageUrl"]
            ),
        )


class BranchEvidenceTest(unittest.TestCase):
    def test_page_identifier_is_not_automatically_backend_identifier(self):
        summary = probe.build_safe_summary(
            requested_branch_label="Nowy Sącz",
            branch_page_url=(
                "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
                "hurtownia-elektryczna-nowy-sacz/205"
            ),
            network=[],
        )

        self.assertEqual(
            False,
            summary["branch"]["targetBranchResolved"],
        )
        self.assertEqual(
            (
                "/lista-hurtowni-elektrycznych/"
                "hurtownia-elektryczna-nowy-sacz/205"
            ),
            summary["branch"]["targetBranchPagePath"],
        )
        self.assertEqual(
            "205",
            summary["branch"]["branchPageIdentifier"],
        )
        self.assertEqual(
            probe.UNKNOWN,
            summary["branch"]["backendBranchIdentifier"],
        )

    def test_safe_summary_reports_uniquely_resolved_target_branch(self):
        summary = probe.build_safe_summary(
            requested_branch_label="Nowy Sącz",
            branch_page_url=(
                "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
                "hurtownia-elektryczna-nowy%20sacz/205"
            ),
            target_branch_resolved=True,
            backend_branch_identifier=probe.UNKNOWN,
        )

        self.assertTrue(
            summary["branch"]["targetBranchResolved"]
        )
        self.assertEqual(
            (
                "/lista-hurtowni-elektrycznych/"
                "hurtownia-elektryczna-nowy%20sacz/205"
            ),
            summary["branch"]["targetBranchPagePath"],
        )
        self.assertEqual(
            "205",
            summary["branch"]["branchPageIdentifier"],
        )
        self.assertEqual(
            probe.UNKNOWN,
            summary["branch"]["backendBranchIdentifier"],
        )

    def test_generic_branch_list_label_is_not_selection_evidence(self):
        self.assertFalse(
            probe.branch_marker(
                _GenericBranchListPage(),
                "Nowy Sącz",
            )
        )

        observed, persisted = probe.derive_branch_selection_status(
            branch_click_ok=True,
            marker_after_select=False,
            marker_after_reload=False,
            changed={
                "cookies": [],
                "localStorage": [],
                "sessionStorage": [],
            },
            persisted={
                "cookies": [],
                "localStorage": [],
                "sessionStorage": [],
            },
            network=[],
        )

        self.assertIsNone(observed)
        self.assertIsNone(persisted)
        summary = probe.build_safe_summary(
            requested_branch_label="Nowy Sącz",
            selection_observed=observed,
            selection_persisted=persisted,
        )
        self.assertEqual(
            probe.UNKNOWN,
            summary["branch"]["selectionObserved"],
        )
        self.assertEqual(
            probe.UNKNOWN,
            summary["branch"]["selectionPersisted"],
        )

    def test_explicit_selected_branch_ui_is_valid_selection_evidence(self):
        self.assertTrue(
            probe.branch_marker(
                _SelectedBranchPage(),
                "Nowy Sącz",
            )
        )

    def test_branch_related_state_change_and_reload_can_prove_selection(self):
        observed, persisted = probe.derive_branch_selection_status(
            branch_click_ok=True,
            marker_after_select=False,
            marker_after_reload=False,
            changed={
                "cookies": ["kwant.net.pl|/|selectedBranch"],
                "localStorage": [],
                "sessionStorage": [],
            },
            persisted={
                "cookies": ["kwant.net.pl|/|selectedBranch"],
                "localStorage": [],
                "sessionStorage": [],
            },
            network=[],
        )

        self.assertTrue(observed)
        self.assertTrue(persisted)

    def test_department_cookie_creation_proves_selection_and_exposes_public_id(self):
        changed = {
            "cookies": [
                "kwant.net.pl|/|departmentCookie",
            ],
            "localStorage": [],
            "sessionStorage": [],
        }
        persisted = {
            "cookies": [],
            "localStorage": [],
            "sessionStorage": [],
        }

        observed, selection_persisted = (
            probe.derive_branch_selection_status(
                branch_click_ok=True,
                marker_after_select=False,
                marker_after_reload=False,
                changed=changed,
                persisted=persisted,
                network=[],
            )
        )
        value = probe.department_cookie_value(
            {
                "kwant.net.pl|/|departmentCookie": "205",
            }
        )
        summary = probe.build_safe_summary(
            requested_branch_label="Nowy Sącz",
            selection_observed=observed,
            selection_persisted=selection_persisted,
            department_cookie_observed=(
                probe.department_cookie_observed(changed)
            ),
            department_cookie_persisted=(
                probe.department_cookie_persisted(persisted)
            ),
            department_cookie_value_safe=value,
            backend_branch_identifier=probe.UNKNOWN,
        )

        self.assertTrue(observed)
        self.assertIsNone(selection_persisted)
        self.assertTrue(
            summary["branch"]["departmentCookieObserved"]
        )
        self.assertFalse(
            summary["branch"]["departmentCookiePersisted"]
        )
        self.assertEqual(
            "205",
            summary["branch"]["departmentCookieValue"],
        )
        self.assertEqual(
            probe.UNKNOWN,
            summary["branch"]["backendBranchIdentifier"],
        )

    def test_same_department_cookie_survives_reload_and_proves_persistence(self):
        before_cookies = {}
        after_cookies = {
            "kwant.net.pl|/|departmentCookie": "205",
        }
        reload_cookies = dict(after_cookies)
        empty_storage = {
            "localStorage": {},
            "sessionStorage": {},
        }

        changed = probe.state_changed_keys(
            before_cookies,
            after_cookies,
            empty_storage,
            empty_storage,
        )
        persisted = probe.persisted_state_keys(
            after_cookies,
            reload_cookies,
            empty_storage,
            empty_storage,
            changed,
        )
        observed, selection_persisted = (
            probe.derive_branch_selection_status(
                branch_click_ok=True,
                marker_after_select=False,
                marker_after_reload=False,
                changed=changed,
                persisted=persisted,
                network=[],
            )
        )

        self.assertTrue(observed)
        self.assertTrue(selection_persisted)
        self.assertTrue(
            probe.department_cookie_observed(changed)
        )
        self.assertTrue(
            probe.department_cookie_persisted(persisted)
        )
        self.assertEqual(
            "205",
            probe.department_cookie_value(after_cookies),
        )

    def test_generic_branch_page_request_is_not_selection_request_evidence(self):
        network = [
            {
                "action": "branch:select",
                "path": "/store/nowy-sacz",
                "query": {"safeValues": {}},
                "body": None,
            }
        ]

        self.assertFalse(
            probe.branch_selection_request_observed(network)
        )

    def test_explicit_branch_change_request_is_selection_evidence(self):
        network = [
            {
                "action": "branch:select",
                "path": "/api/store/change",
                "query": {"safeValues": {}},
                "body": None,
            }
        ]

        self.assertTrue(
            probe.branch_selection_request_observed(network)
        )

    def test_backend_identifier_requires_observed_selection_request_field(self):
        network = [
            {
                "action": "branch:select",
                "query": {
                    "safeValues": {
                        "warehouseId": "NS-12",
                    }
                },
                "body": None,
            }
        ]

        self.assertEqual(
            "NS-12",
            probe.infer_backend_branch_identifier(network),
        )


class DepartmentCookieConstructorResearchTest(unittest.TestCase):
    CONSTRUCTOR_EXCERPT = (
        'let a=()=>dayjs().add(360,"days").toDate();'
        'let o="departmentCookie";'
        'getUnauthDepartmentCookie=()=>{let e=getCookie(o);'
        'return e?JSON.parse(e):null};'
        'setUnauthDepartmentCookie=e=>{'
        'setCookie(o,JSON.stringify(e),{expires:a()})};'
        'useUserStockDepartment=()=>{'
        'let{department_stock_id:e,department_stock_name:t,'
        'department_stock_postcode:r,department_stock_street:a}=p;'
        'return{department_stock_id:e,department_stock_name:t,'
        'department_stock_postcode:r,department_stock_street:a};'
        'f=e=>setUnauthDepartmentCookie(e)}'
    )

    PUBLIC_BRANCH = {
        "department_id": 205,
        "city": "Nowy Sącz",
        "name": "Nowy Sącz",
        "postcode": "33-300",
        "street": "Tarnowska 149",
    }
    COOKIE_OBJECT = {
        "department_stock_id": 205,
        "department_stock_name": "Nowy Sącz",
        "department_stock_postcode": "33-300",
        "department_stock_street": "Tarnowska 149",
    }

    def test_real_style_setter_getter_constructor_is_confirmed(self):
        result = probe.analyze_department_cookie_constructor(
            "/_next/static/chunks/example.js",
            self.CONSTRUCTOR_EXCERPT,
        )

        self.assertTrue(
            result["departmentCookieConstructorFound"]
        )
        self.assertEqual(
            "/_next/static/chunks/example.js",
            result["constructorSourcePath"],
        )
        self.assertEqual(
            "JSON serialized branch object",
            result["valueFormat"],
        )
        self.assertEqual(
            ["JSON.stringify -> cookie value"],
            result["encodingSteps"],
        )
        self.assertEqual(
            {"expires": "360 days"},
            result["cookieOptions"],
        )

    def test_getter_must_read_department_cookie_with_json_parse(self):
        confirmed = probe.analyze_department_cookie_constructor(
            "/_next/static/chunks/example.js",
            self.CONSTRUCTOR_EXCERPT,
        )
        missing_parse = probe.analyze_department_cookie_constructor(
            "/_next/static/chunks/example.js",
            self.CONSTRUCTOR_EXCERPT.replace(
                "JSON.parse(e)",
                "String(e)",
            ),
        )

        self.assertTrue(
            confirmed["departmentCookieConstructorFound"]
        )
        self.assertEqual(
            probe.UNKNOWN,
            missing_parse["departmentCookieConstructorFound"],
        )

    def test_branch_fields_are_extracted_only_from_confirmed_use_path(self):
        result = probe.analyze_department_cookie_constructor(
            "/_next/static/chunks/example.js",
            self.CONSTRUCTOR_EXCERPT,
        )

        self.assertEqual(
            list(probe.CONFIRMED_DEPARTMENT_COOKIE_FIELDS),
            result["sourceBranchFields"],
        )
        self.assertEqual(
            {
                "department_stock_id": "department_id",
                "department_stock_name": "name",
                "department_stock_postcode": "postcode",
                "department_stock_street": "street",
            },
            probe.public_branch_field_mapping(
                result["sourceBranchFields"],
                self.PUBLIC_BRANCH,
            ),
        )

    def test_unrelated_department_cookie_string_does_not_confirm_constructor(self):
        unrelated = (
            'const label="departmentCookie";'
            'const serialized=JSON.stringify(otherObject);'
            'const options={expires:360};'
            'console.log(label,serialized,options)'
        )

        result = probe.analyze_department_cookie_constructor(
            "/_next/static/chunks/unrelated.js",
            unrelated,
        )

        self.assertEqual(
            probe.UNKNOWN,
            result["departmentCookieConstructorFound"],
        )

    def test_missing_or_ambiguous_constructor_stays_unknown(self):
        absent = probe.analyze_department_cookie_constructor(
            "/_next/static/chunks/unrelated.js",
            "console.log('no cookie constructor here')",
        )
        partial = probe.analyze_department_cookie_constructor(
            "/_next/static/chunks/partial.js",
            'let o="departmentCookie";getCookie(o);'
            'JSON.parse("{}")',
        )

        self.assertEqual(
            probe.UNKNOWN,
            absent["departmentCookieConstructorFound"],
        )
        self.assertEqual(
            probe.UNKNOWN,
            partial["departmentCookieConstructorFound"],
        )

    def test_public_branch_data_reproduces_confirmed_cookie_object(self):
        fields = list(probe.CONFIRMED_DEPARTMENT_COOKIE_FIELDS)
        mapping = probe.public_branch_field_mapping(
            fields,
            self.PUBLIC_BRANCH,
        )

        self.assertTrue(
            probe.public_branch_reproduces_cookie_fields(
                self.COOKIE_OBJECT,
                mapping,
                self.PUBLIC_BRANCH,
            )
        )
        self.assertEqual(
            "205",
            probe.safe_department_stock_id(
                self.COOKIE_OBJECT,
                mapping,
                self.PUBLIC_BRANCH,
                "205",
            ),
        )

    def test_controlled_serialized_comparison_true_false_and_unknown(self):
        fields = list(probe.CONFIRMED_DEPARTMENT_COOKIE_FIELDS)
        mapping = probe.public_branch_field_mapping(
            fields,
            self.PUBLIC_BRANCH,
        )
        observed = json.dumps(
            self.COOKIE_OBJECT,
            ensure_ascii=False,
            separators=(",", ":"),
        )

        self.assertTrue(
            probe.constructed_cookie_value_matches_observed(
                observed,
                mapping,
                self.PUBLIC_BRANCH,
            )
        )
        different_public = dict(self.PUBLIC_BRANCH)
        different_public["street"] = "Different street"
        self.assertFalse(
            probe.constructed_cookie_value_matches_observed(
                observed,
                mapping,
                different_public,
            )
        )
        incomplete_public = dict(self.PUBLIC_BRANCH)
        incomplete_public.pop("postcode")
        self.assertIsNone(
            probe.constructed_cookie_value_matches_observed(
                observed,
                probe.public_branch_field_mapping(
                    fields,
                    incomplete_public,
                ),
                incomplete_public,
            )
        )

    def test_percent_encoded_observed_cookie_matches_without_exposure(self):
        fields = list(probe.CONFIRMED_DEPARTMENT_COOKIE_FIELDS)
        mapping = probe.public_branch_field_mapping(
            fields,
            self.PUBLIC_BRANCH,
        )
        encoded = (
            "%7B%22department_stock_id%22%3A205%2C"
            "%22department_stock_name%22%3A%22Nowy%20S%C4%85cz%22%2C"
            "%22department_stock_postcode%22%3A%2233-300%22%2C"
            "%22department_stock_street%22%3A%22Tarnowska%20149%22%7D"
        )
        parsed, steps = probe.parse_department_cookie_object(encoded)

        self.assertEqual(self.COOKIE_OBJECT, parsed)
        self.assertEqual(
            ["JSON.stringify", "percent-encoding-by-cookie-helper"],
            steps,
        )
        self.assertTrue(
            probe.constructed_cookie_value_matches_observed(
                encoded,
                mapping,
                self.PUBLIC_BRANCH,
            )
        )

    def test_public_branch_object_is_extracted_by_page_identifier(self):
        html = (
            '<script>self.__next_f.push([1,"'
            '{\\"department_id\\":204,\\"name\\":\\"Other\\"},'
            '{\\"department_id\\":205,\\"name\\":\\"Nowy Sącz\\",'
            '\\"postcode\\":\\"33-300\\",'
            '\\"street\\":\\"Tarnowska 149\\"}'
            '"])</script>'
        ).replace('\\\"', '"')

        branch = probe.public_branch_object_from_html(html, "205")

        self.assertEqual(205, branch["department_id"])
        self.assertEqual("Nowy Sącz", branch["name"])
        self.assertEqual("33-300", branch["postcode"])
        self.assertEqual("Tarnowska 149", branch["street"])

    def test_safe_summary_never_exposes_raw_cookie_session_or_token_values(self):
        secret = "SUPER_SECRET_COOKIE_VALUE"
        safe = probe.safe_department_cookie_research(
            {
                "departmentCookieConstructorFound": True,
                "constructorSourcePath":
                    "/_next/static/chunks/example.js",
                "valueFormat": "JSON serialized branch object",
                "sourceBranchFields": [
                    "department_stock_id",
                    "department_stock_name",
                    "authToken",
                ],
                "encodingSteps": [
                    "JSON.stringify -> cookie value",
                ],
                "cookieOptions": {
                    "expires": "360 days",
                    "auth": secret,
                },
                "reproducibleFromPublicData": True,
                "constructedValueMatchesObserved": True,
            }
        )
        summary = probe.build_safe_summary(
            requested_branch_label="Nowy Sącz",
            branch_page_url=(
                "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
                "hurtownia-elektryczna-nowy-sacz/205"
            ),
            department_cookie_value_safe=secret,
            department_stock_id="205",
            department_cookie_research=safe,
        )
        serialized = json.dumps(summary, ensure_ascii=False)

        self.assertNotIn(secret, serialized)
        self.assertNotIn("authToken", serialized)
        self.assertNotIn('"auth"', serialized)
        self.assertEqual(
            probe.REDACTED,
            summary["branch"]["departmentCookieValue"],
        )
        self.assertEqual(
            "205",
            summary["branch"]["departmentStockId"],
        )
        self.assertTrue(
            summary["departmentCookieResearch"][
                "constructedValueMatchesObserved"
            ]
        )



class SearchEvidenceTest(unittest.TestCase):
    def test_unrelated_product_links_do_not_prove_search_support(self):
        status = probe.safe_search_status(
            interaction_ok=True,
            target_found=False,
            query_specific_evidence=False,
            any_products=True,
            body_text=(
                "Polecane produkty "
                "/produkt/unrelated-one /produkt/unrelated-two"
            ),
        )

        self.assertEqual(probe.UNKNOWN, status)

    def test_query_specific_success_with_products_is_supported(self):
        status = probe.safe_search_status(
            interaction_ok=True,
            target_found=False,
            query_specific_evidence=True,
            any_products=True,
            body_text="Wyniki wyszukiwania dla MBN116E",
        )

        self.assertEqual("SUPPORTED", status)

    def test_explicit_no_results_remains_unsupported_even_with_unrelated_products(self):
        status = probe.safe_search_status(
            interaction_ok=True,
            target_found=False,
            query_specific_evidence=True,
            any_products=True,
            body_text=(
                "Brak wyników dla podanego zapytania. "
                "Polecane produkty poniżej."
            ),
        )

        self.assertEqual("UNSUPPORTED", status)

    def test_search_request_requires_real_frontend_api_and_submitted_query(self):
        network = [
            {
                "action": "search:article",
                "method": "POST",
                "host": probe.KWANT_SERVICES_HOST,
                "path": probe.KWANT_SEARCH_API_PATH,
                "query": {"safeValues": {}},
                "body": {
                    "safeValues": {
                        "q": "MBN116E",
                        "page": 1,
                        "limit": 12,
                    }
                },
            }
        ]

        self.assertTrue(
            probe.search_request_observed(
                network,
                action="search:article",
                query="MBN116E",
            )
        )
        self.assertFalse(
            probe.search_request_observed(
                network,
                action="search:article",
                query="6743009",
            )
        )

        wrong_contract = [dict(network[0], path="/_next/data/build/search.json")]
        self.assertFalse(
            probe.search_request_observed(
                wrong_contract,
                action="search:article",
                query="MBN116E",
            )
        )

    def test_matching_product_url_does_not_fall_back_to_unrelated_link(self):
        result = {
            "_links": [
                {
                    "url": (
                        "https://kwant.net.pl/produkt/"
                        "unrelated-product-580"
                    ),
                    "text": "Polecany inny produkt",
                }
            ]
        }

        self.assertEqual(
            "",
            probe.matching_product_url(result, "MBN116E"),
        )


class StatePersistenceTest(unittest.TestCase):
    def test_changed_and_persisted_state_reports_key_names_only(self):
        before_cookies = {"kwant.net.pl|/|branch": "old"}
        after_cookies = {"kwant.net.pl|/|branch": "new"}
        reload_cookies = {"kwant.net.pl|/|branch": "new"}
        before_storage = {
            "localStorage": {},
            "sessionStorage": {},
        }
        after_storage = {
            "localStorage": {"selectedBranch": "205"},
            "sessionStorage": {},
        }
        reload_storage = {
            "localStorage": {"selectedBranch": "205"},
            "sessionStorage": {},
        }

        changed = probe.state_changed_keys(
            before_cookies,
            after_cookies,
            before_storage,
            after_storage,
        )
        persisted = probe.persisted_state_keys(
            after_cookies,
            reload_cookies,
            after_storage,
            reload_storage,
            changed,
        )

        self.assertEqual(
            ["kwant.net.pl|/|branch"],
            changed["cookies"],
        )
        self.assertEqual(
            ["selectedBranch"],
            changed["localStorage"],
        )
        self.assertEqual(changed, persisted)


class ReportWriterTest(unittest.TestCase):
    def test_report_writer_uses_unknown_and_safe_network_shapes(self):
        summary = probe.build_safe_summary(
            requested_branch_label="Nowy Sącz",
            selection_observed=True,
            selection_persisted=None,
            network=[
                {
                    "action": "search:article",
                    "method": "POST",
                    "host": probe.KWANT_SERVICES_HOST,
                    "path": probe.KWANT_SEARCH_API_PATH,
                    "query": {
                        "names": [],
                        "safeValues": {},
                    },
                    "body": {
                        "fields": ["q", "page", "limit", "tags"],
                        "safeValues": {
                            "q": "MBN116E",
                            "page": 1,
                            "limit": 12,
                        },
                    },
                    "status": 200,
                    "contentType": "application/json",
                }
            ],
        )

        out_dir = Path(self._testMethodName)
        try:
            probe.write_safe_summary(summary, out_dir)
            text = (out_dir / "summary.txt").read_text(
                encoding="utf-8"
            )
            parsed = json.loads(
                (out_dir / "summary.json").read_text(
                    encoding="utf-8"
                )
            )
        finally:
            for path in out_dir.glob("*"):
                path.unlink()
            out_dir.rmdir()

        self.assertIn("selectionObserved=true", text)
        self.assertIn("selectionPersisted=UNKNOWN", text)
        self.assertIn("departmentCookieObserved=UNKNOWN", text)
        self.assertIn("departmentCookiePersisted=UNKNOWN", text)
        self.assertIn("departmentCookieValue=UNKNOWN", text)
        self.assertIn("articleCode=UNKNOWN", text)
        self.assertEqual(
            probe.UNKNOWN,
            parsed["product"]["selectedBranchStock"],
        )



class LocationsResearchTest(unittest.TestCase):
    def test_public_directory_maps_numeric_department_id_to_public_name(self):
        html = (
            '<script id="__NEXT_DATA__" type="application/json">'
            + json.dumps({"props": {"pageProps": {"departments": {"list": [
                {"department_id": 205, "name": "Nowy Sącz"},
                {"department_id": 204, "name": "Tarnów"},
                {"department_id": "BAD", "name": "Unknown"},
                {"department_id": 300, "name": "token=SECRET"},
            ]}}}})
            + "</script>"
        )
        self.assertEqual(
            {"205": "Nowy Sącz", "204": "Tarnów"},
            probe.public_branch_directory_from_html(html),
        )
        self.assertEqual({}, probe.public_branch_directory_from_html(""))

    def test_search_fields_do_not_claim_selected_branch_stock(self):
        shape = probe.research_json_shape(
            {"hits": [
                {"id": 580, "name": "control", "code": "MBN116E/HAG",
                 "stock": 999, "central_stock": 345},
            ]},
            "580",
            {"205": "Nowy Sącz"},
        )
        self.assertEqual(1, shape["searchHitCount"])
        self.assertEqual(["central_stock", "stock"], shape["searchHitStockFields"])
        self.assertEqual([], shape["branchRows"])
        self.assertEqual(probe.UNKNOWN, shape["productIdentityMatches"])

    def test_per_branch_stock_zero_positive_null_and_directory_mapping(self):
        shape = probe.research_json_shape(
            {"product_id": 580, "branches": [
                {"department_id": 205, "stock": 0},
                {"department_id": 204, "stock": 18},
                {"department_id": 999, "stock": None},
                {"department_id": 20, "stock": "?"}
            ]},
            "580",
            {"205": "Nowy Sącz", "204": "Tarnów"},
        )
        self.assertTrue(shape["productIdentityMatches"])
        self.assertEqual(4, shape["branchDistinctIds"])
        rows = shape["branchRows"]
        self.assertEqual(
            ["known_zero", "known_positive", "unknown_null", "invalid"],
            [r["stockState"] for r in rows],
        )
        self.assertEqual([0, 18, None, None], [r["candidateValue"] for r in rows])
        self.assertEqual([True, True, False, False],
                         [r["branchKnownInDirectory"] for r in rows])
        self.assertNotIn("name", str(shape))
        self.assertEqual("Tarnów", rows[1]["branchNameFromDirectory"])

    def test_mismatched_product_identity_remains_mismatched(self):
        shape = probe.research_json_shape(
            {"product_id": 581, "department_stock": {
                "department_id": 205, "stock": 3,
            }},
            "580", {"205": "Nowy Sącz"},
        )
        self.assertFalse(shape["productIdentityMatches"])
        self.assertEqual(1, shape["branchDistinctIds"])

    def test_array_root_response_is_not_dropped(self):
        shape = probe.research_json_shape(
            [
                {"department_id": 205, "stock": 0},
                {"department_id": 204, "stock": 18},
            ],
            "580",
            {"205": "Nowy Sącz", "204": "Tarnów"},
        )
        self.assertEqual("list", shape["rootType"])
        self.assertEqual(probe.UNKNOWN, shape["productIdentityMatches"])
        self.assertEqual(2, shape["branchDistinctIds"])

    def test_unexpected_public_host_not_inspected(self):
        class Request:
            url = "https://evil.example/api/front/products/580/current"
            method = "GET"
            resource_type = "fetch"

        class Response:
            request = Request()
            url = Request.url
            headers = {"content-type": "application/json"}
            status = 200

            def body(self):
                raise AssertionError("should never read external response bodies")

        network = probe.NetworkRecorder(action="locations:page")
        capture = probe.ObservedLocationsResponses(network, "580")
        capture.on_response(Response())
        self.assertEqual([], capture.records)

    def test_research_schema_contains_no_secret_source_values(self):
        secret = "SENSITIVE_ACCOUNT_TOKEN"
        shape = probe.research_json_shape({
            "product_id": 580,
            "sessionToken": secret,
            "departments": [
                {"department_id": 205, "stock": 4,
                 "account": secret, "cookie": secret}
            ],
        }, "580", {"205": "Nowy Sącz"})
        self.assertNotIn(secret, json.dumps(shape, ensure_ascii=False))
        self.assertNotIn("sessionToken", shape["rootFields"])


class LocationsFollowupResearchTest(unittest.TestCase):
    DIRECTORY = {"205": "Nowy Sącz", "204": "Tarnów", "20": "Rzeszów"}

    def shape(self, rows, *, product_id=580):
        return probe.research_json_shape(
            {"product_id": product_id, "branches": rows},
            "580",
            self.DIRECTORY,
        )

    @staticmethod
    def observation(shape, *, action="locations:availability", path="/observed"):
        return {
            "action": action,
            "host": probe.KWANT_SERVICES_HOST,
            "path": path,
            "method": "GET",
            "status": 200,
            "shape": shape,
        }

    def test_snake_case_stock_stays_diagnostic(self):
        shape = self.shape([
            {"department_id": 205, "stock": 0},
            {"department_stock_id": 204, "stock": 18},
        ])
        self.assertEqual(["stock", "stock"],
                         [x["candidateField"] for x in shape["branchRows"]])
        self.assertEqual(
            ["department_id", "department_stock_id"],
            [x["branchIdField"] for x in shape["branchRows"]],
        )
        self.assertEqual([0, 18],
                         [x["candidateValue"] for x in shape["branchRows"]])
        self.assertEqual(
            ["known_zero", "known_positive"],
            [x["candidateState"] for x in shape["branchRows"]],
        )

    def test_alternative_quantity_and_camel_case(self):
        shape = self.shape([
            {"department_id": 205, "stock_num": 0},
            {"departmentId": 204, "stockNum": 18},
            {"departmentStockId": 20, "availableStock": 5},
        ])
        self.assertEqual(
            ["stock_num", "stockNum", "availableStock"],
            [row["candidateField"] for row in shape["branchRows"]],
        )
        self.assertEqual(
            ["205", "204", "20"],
            [row["branchId"] for row in shape["branchRows"]],
        )
        self.assertTrue(all(row["branchKnownInDirectory"]
                            for row in shape["branchRows"]))

    def test_null_and_invalid_quantities_never_coerce(self):
        shape = self.shape([
            {"department_id": 205, "stock_num": None},
            {"departmentId": 204, "stockNum": "12"},
            {"departmentId": 20, "stockNum": True},
        ])
        self.assertEqual(
            ["unknown_null", "invalid", "invalid"],
            [row["candidateState"] for row in shape["branchRows"]],
        )
        self.assertEqual(
            [None, None, None],
            [row["candidateValue"] for row in shape["branchRows"]],
        )

    def test_unknown_and_conflicting_identity_does_not_map_by_name(self):
        shape = self.shape([
            {"departmentId": 999, "name": "Nowy Sącz", "stock": 7},
            {"department_id": 205, "departmentId": 204, "stock": 12},
            {"name": "Nowy Sącz", "stock": 3},
        ])
        self.assertEqual(1, shape["branchRowCountCaptured"])
        self.assertEqual("999", shape["branchRows"][0]["branchId"])
        self.assertFalse(shape["branchRows"][0]["branchKnownInDirectory"])

    def test_candidate_names_paths_and_values_are_privacy_safe(self):
        secret = "PRIVATE_EMAIL_SUPER_SECRET"
        shape = self.shape([{
            "department_id": 205, "stock_num": 4,
            "authToken": secret,
            "access_token": secret,
            "stock@private": secret,
            "clientEmail": secret,
            "password": secret,
        }])
        rendered = json.dumps(shape, ensure_ascii=False)
        self.assertNotIn(secret, rendered)
        self.assertNotIn("authToken", rendered)
        self.assertNotIn("stock@private", rendered)
        self.assertNotIn("password", rendered)
        self.assertEqual(1, len(shape["branchRows"]))

    def test_complete_directory_coverage_with_zero(self):
        shape = self.shape([
            {"department_id": 205, "stock": 0},
            {"department_id": 204, "stock_num": 18},
            {"departmentId": 20, "stockNum": 7},
        ])
        coverage = probe.evaluate_candidate_coverage(shape, self.DIRECTORY)
        self.assertEqual("ALL_DIRECTORY_BRANCHES", coverage["completeness"])
        self.assertEqual(25, coverage["sumConfirmedObservedBranchStock"])

    def test_positive_only_is_candidate_not_proven_omitted_zeros(self):
        shape = self.shape([
            {"department_id": 205, "stock": 4},
            {"department_id": 204, "stock": 18},
        ])
        coverage = probe.evaluate_candidate_coverage(shape, self.DIRECTORY)
        self.assertEqual("POSITIVE_ONLY_CANDIDATE", coverage["completeness"])
        self.assertEqual(22, coverage["sumConfirmedObservedBranchStock"])

    def test_partial_zero_and_unverified_product_scope(self):
        shape = self.shape([
            {"department_id": 205, "stock": 0},
            {"departmentId": 204, "stock": 18},
        ])
        self.assertEqual(
            "PARTIAL",
            probe.evaluate_candidate_coverage(shape, self.DIRECTORY)["completeness"],
        )
        wrong = self.shape([
            {"department_id": 205, "stock": 0},
            {"departmentId": 204, "stock": 18},
            {"departmentId": 20, "stock": 7},
        ], product_id=581)
        self.assertEqual(
            probe.UNKNOWN,
            probe.evaluate_candidate_coverage(wrong, self.DIRECTORY)["completeness"],
        )
        unknown = probe.research_json_shape(
            [{"department_id": 205, "stock": 0},
             {"department_id": 204, "stock": 18},
             {"department_id": 20, "stock": 7}],
            "580",
            self.DIRECTORY,
        )
        self.assertEqual(
            probe.UNKNOWN,
            probe.evaluate_candidate_coverage(unknown, self.DIRECTORY)["completeness"],
        )

    def test_match_and_mismatch_are_observations_not_failure(self):
        shape = self.shape([
            {"department_id": 205, "stock": 0},
            {"department_id": 204, "stock_num": 18},
            {"departmentId": 20, "stockNum": 7},
        ])
        result = probe.select_strongest_location_candidate(
            [self.observation(shape)], self.DIRECTORY, "25 szt."
        )
        self.assertEqual("MATCH", result["aggregateReconciliation"])
        self.assertEqual(25, result["parsedAggregateBranchStock"])
        self.assertEqual(25, result["sumConfirmedObservedBranchStock"])
        mismatch = probe.select_strongest_location_candidate(
            [self.observation(shape)], self.DIRECTORY, "26 szt."
        )
        self.assertEqual("MISMATCH", mismatch["aggregateReconciliation"])
        self.assertEqual(25, mismatch["sumConfirmedObservedBranchStock"])
        self.assertEqual(26, mismatch["parsedAggregateBranchStock"])

    def test_missing_null_and_duplicate_quantity_prevent_reconciliation(self):
        for rows in (
            [{"department_id": 205, "stock": 0},
             {"department_id": 204, "stock_num": None},
             {"department_id": 20, "stock": 7}],
            [{"department_id": 205, "stock": 0, "stock_num": 0},
             {"department_id": 204, "stock": 18},
             {"department_id": 20, "stock": 7}],
            [{"department_id": 205, "stock": 0},
             {"department_id": 204, "stock": "18"},
             {"department_id": 20, "stock": 7}],
        ):
            with self.subTest(rows=rows):
                shape = self.shape(rows)
                result = probe.select_strongest_location_candidate(
                    [self.observation(shape)], self.DIRECTORY, "25 szt."
                )
                self.assertEqual("NOT_COMPARABLE",
                                 result["aggregateReconciliation"])
                self.assertIsNone(result["sumConfirmedObservedBranchStock"])
        partial = self.shape([
            {"department_id": 205, "stock": 4},
            {"department_id": 204, "stock": 18},
        ])
        result = probe.select_strongest_location_candidate(
            [self.observation(partial)], self.DIRECTORY, "22 szt."
        )
        self.assertEqual("NOT_COMPARABLE", result["aggregateReconciliation"])
        self.assertEqual(22, result["sumConfirmedObservedBranchStock"])

    def test_invalid_aggregate_and_no_response_stay_not_comparable_or_evaluated(self):
        shape = self.shape([
            {"department_id": 205, "stock": 0},
            {"department_id": 204, "stock": 18},
            {"department_id": 20, "stock": 7},
        ])
        result = probe.select_strongest_location_candidate(
            [self.observation(shape)], self.DIRECTORY, "1,5 szt."
        )
        self.assertEqual("NOT_COMPARABLE", result["aggregateReconciliation"])
        self.assertIsNone(result["parsedAggregateBranchStock"])
        absent = probe.select_strongest_location_candidate(
            [], self.DIRECTORY, "25 szt."
        )
        self.assertEqual("NOT_EVALUATED", absent["aggregateReconciliation"])
        self.assertIsNone(absent["strongestCandidate"])

    def test_strongest_candidate_identity_then_quantity_then_ui_then_coverage(self):
        weak = self.shape([
            {"department_id": 205, "stock": 4},
            {"department_id": 204, "stock": 18},
            {"department_id": 20, "stock": 7},
        ], product_id=581)
        strong = self.shape([
            {"department_id": 205, "stock_num": 4},
            {"departmentId": 204, "stockNum": 18},
        ])
        observations = [
            self.observation(weak, action="locations:availability",
                             path="/looks-strong-but-is-wrong"),
            self.observation(strong, action="locations:page",
                             path="/random-A"),
            self.observation(strong, action="locations:availability",
                             path="/random-Z"),
        ]
        best = probe.select_strongest_location_candidate(
            observations, self.DIRECTORY, "22 szt."
        )
        self.assertEqual(2, best["strongestCandidate"]["observationIndex"])
        self.assertTrue(best["strongestCandidate"]["productIdentityVerified"])
        self.assertTrue(best["strongestCandidate"]["uiTriggered"])
        self.assertEqual(
            "POSITIVE_ONLY_CANDIDATE", best["completeness"]
        )
        self.assertEqual("NOT_COMPARABLE", best["aggregateReconciliation"])
        tied = probe.select_strongest_location_candidate(
            [
                self.observation(strong, path="/z"),
                self.observation(strong, path="/a"),
            ], self.DIRECTORY, "22 szt."
        )
        self.assertEqual(0, tied["strongestCandidate"]["observationIndex"])

    def test_search_stock_conclusions_are_scope_conservative(self):
        def search(fields):
            return self.observation(
                probe.research_json_shape(
                    {"hits": [{"id": 580, **fields}]}, "580", self.DIRECTORY,
                ),
                action="search:article",
            )
        for fields, expected in (
            ({}, "NO_STOCK_FIELD_OBSERVED"),
            ({"central_stock": 4, "aggregateBranchStock": 100},
             "CENTRAL_OR_AGGREGATE_LOOKING_ONLY"),
            ({"stock_num": 4}, "STOCK_SHAPED_SCOPE_UNKNOWN"),
        ):
            with self.subTest(fields=fields):
                evidence = probe.summarize_search_ranking_evidence(
                    [search(fields)], self.DIRECTORY, "580"
                )
                self.assertFalse(evidence["selectedBranchStockProven"])
                self.assertEqual(expected, evidence["stockScope"])

    def test_selected_branch_proof_requires_independent_current_corrob(self):
        search_shape = probe.research_json_shape(
            {"hits": [{"id": 580, "departmentId": 205, "stockNum": 4}]},
            "580", {},
        )
        search = self.observation(search_shape, action="search:article",
                                  path=probe.KWANT_SEARCH_API_PATH)
        search["method"] = "POST"
        search["body"] = {"safeValues": {"depstock": 205}}
        current_shape = self.shape([
            {"department_id": 205, "stock": 4},
        ])
        # CURRENT JSON is rooted at department_stock, not a branch list.
        current_shape = probe.research_json_shape(
            {"product_id": 580, "department_stock": {
                "department_id": 205, "stock": 4,
            }},
            "580", self.DIRECTORY,
        )
        current = self.observation(current_shape, action="product:after",
                                   path="/api/front/products/580/current")
        current["query"] = {"safeValues": {"depstock": "205"}}
        unknown = probe.summarize_search_ranking_evidence(
            [search], self.DIRECTORY, "580"
        )
        self.assertEqual("STOCK_SHAPED_SCOPE_UNKNOWN", unknown["stockScope"])
        corroborated = probe.summarize_search_ranking_evidence(
            [search, current], self.DIRECTORY, "580"
        )
        self.assertTrue(corroborated["selectedBranchStockProven"])
        self.assertEqual(
            "SELECTED_BRANCH_STOCK_PROVEN_FOR_CONTROL",
            corroborated["stockScope"],
        )
        current["query"] = {"safeValues": {"depstock": "204"}}
        not_proven = probe.summarize_search_ranking_evidence(
            [search, current], self.DIRECTORY, "580"
        )
        self.assertFalse(not_proven["selectedBranchStockProven"])



class AvailabilityLiveFollowupTest(unittest.TestCase):
    DIRECTORY = {"205": "Nowy Sącz", "204": "Tarnów", "20": "Rzeszów"}

    @staticmethod
    def response(shape, *, action="locations:open-branches",
                 path="/api/front/observed", query=None):
        return {
            "shape": shape, "action": action,
            "method": "GET", "host": probe.KWANT_SERVICES_HOST,
            "status": 200, "path": path,
            "query": {"safeValues": query or {}},
        }

    def shape(self, rows, product_id=580):
        return probe.research_json_shape(
            {"product_id": product_id, "list": rows},
            "580", self.DIRECTORY,
        )

    class FakeClickableParent:
        def __init__(self, text, *, visible=True, enabled=True, role="button"):
            self.text = text
            self.visible = visible
            self.enabled = enabled
            self.role = role
            self.clicks = 0

        def get_attribute(self, attr):
            return self.role if attr == "role" else None

        def inner_text(self, timeout=1200):
            return self.text

        def is_visible(self):
            return self.visible

        def is_enabled(self):
            return self.enabled

        def click(self):
            self.clicks += 1

    @staticmethod
    def fake_availability_page(parents):
        """Use the observed DOM split: labelled child, clickable ancestor."""
        class Ancestor:
            def __init__(self, parent):
                self.parent = parent

            def count(self):
                return 1

            @property
            def first(self):
                return self.parent

        class Child:
            def __init__(self, parent):
                self.parent = parent

            def is_visible(self):
                return self.parent.is_visible()

            def locator(self, selector):
                assert selector == probe.AVAILABILITY_PARENT_SELECTOR
                return Ancestor(self.parent)

        class Descendants:
            def __init__(self, parents):
                self.children = [Child(p) for p in parents]

            def count(self):
                return len(self.children)

            def nth(self, index):
                return self.children[index]

        class Page:
            def __init__(self, parents):
                self.children = Descendants(parents)
                self.last_selector = None

            def locator(self, selector):
                self.last_selector = selector
                return self.children

        return Page(parents)

    def test_aria_child_clickable_parent_and_duplicate_expert_row(self):
        stock = self.FakeClickableParent("424 szt. w Nowy Sącz")
        expert = self.FakeClickableParent("Zapytaj eksperta")
        for order in ((expert, stock), (stock, expert)):
            with self.subTest(first=order[0].text):
                page = self.fake_availability_page(order)
                target = probe.availability_button(page, "Nowy Sącz")
                self.assertIs(target, stock)
                self.assertEqual(
                    probe.AVAILABILITY_CHILD_SELECTOR, page.last_selector
                )
                self.assertNotEqual(target, expert)
        chosen = probe.availability_button(
            self.fake_availability_page([expert, stock]), "Nowy Sącz"
        )
        chosen.click()
        self.assertEqual(1, stock.clicks)
        self.assertEqual(0, expert.clicks)

    def test_zero_and_unavailable_stock_row_still_resolve(self):
        expert = self.FakeClickableParent("Zapytaj eksperta")
        for phrase in (
            "0 szt. w Nowy Sącz",
            "Brak w Nowy Sącz",
            "Niedostępny w Nowy Sącz",
        ):
            with self.subTest(phrase=phrase):
                target = self.FakeClickableParent(phrase)
                page = self.fake_availability_page([expert, target])
                self.assertIs(
                    probe.availability_button(page, "Nowy Sącz"), target
                )

    def test_selected_branch_missing_expert_or_other_branch_not_clicked(self):
        expert = self.FakeClickableParent("Zapytaj eksperta")
        other = self.FakeClickableParent("424 szt. w Tarnów")
        page = self.fake_availability_page([expert, other])
        self.assertIsNone(probe.availability_button(page, "Nowy Sącz"))
        self.assertEqual(0, expert.clicks)
        self.assertEqual(0, other.clicks)
        self.assertEqual(
            "F_INCONCLUSIVE",
            probe.classify_locations_contract(
                [], self.DIRECTORY, "580"
            )["type"],
        )

    def test_multiple_stock_rows_or_disabled_stock_row_fail_closed(self):
        first = self.FakeClickableParent("424 szt. w Nowy Sącz")
        second = self.FakeClickableParent("0 szt. w Nowy Sącz")
        self.assertIsNone(probe.availability_button(
            self.fake_availability_page([first, second]), "Nowy Sącz"
        ))
        disabled = self.FakeClickableParent(
            "424 szt. w Nowy Sącz", enabled=False
        )
        self.assertIsNone(probe.availability_button(
            self.fake_availability_page([disabled]), "Nowy Sącz"
        ))
        hidden = self.FakeClickableParent(
            "424 szt. w Nowy Sącz", visible=False
        )
        self.assertIsNone(probe.availability_button(
            self.fake_availability_page([hidden]), "Nowy Sącz"
        ))
        self.assertIsNone(probe.availability_button(
            self.fake_availability_page([first]), ""
        ))

    def test_no_availability_button_is_inconclusive_not_endpoint_absent(self):
        self.assertIsNone(
            probe.availability_button(
                self.fake_availability_page([]), "Nowy Sącz"
            )
        )
        result = probe.classify_locations_contract([], self.DIRECTORY, "580")
        self.assertEqual("F_INCONCLUSIVE", result["type"])

    def test_product_identity_bound_by_request_or_response_not_generic(self):
        shape = self.shape([
            {"department_id": 205, "stock": 0},
            {"department_id": 204, "stock": 18},
            {"department_id": 20, "stock": 7},
        ])
        item = self.response(shape)
        self.assertTrue(probe.request_bound_product_identity(item, "580"))
        item["shape"]["productIdentityMatches"] = probe.UNKNOWN
        self.assertFalse(probe.request_bound_product_identity(item, "580"))
        item["path"] = "/api/front/products/580/observed"
        self.assertTrue(probe.request_bound_product_identity(item, "580"))
        item["path"] = "/api/front/products/581/observed"
        self.assertFalse(probe.request_bound_product_identity(item, "580"))

    def test_one_shot_full_directory_preserves_zero(self):
        shape = self.shape([
            {"department_id": 205, "stock": 0},
            {"department_id": 204, "stock_num": 18},
            {"departmentId": 20, "stockNum": 7},
        ])
        result = probe.classify_locations_contract(
            [self.response(shape)], self.DIRECTORY, "580")
        self.assertEqual("A_ONE_SHOT_ALL_BRANCHES", result["type"])
        self.assertEqual(0, shape["branchRows"][0]["candidateValue"])

    def test_positive_subset_does_not_invent_omitted_zero(self):
        shape = self.shape([
            {"department_id": 205, "stock": 5},
            {"department_id": 204, "stock": 18},
        ])
        result = probe.classify_locations_contract(
            [self.response(shape)], self.DIRECTORY, "580")
        self.assertEqual("B_ONE_SHOT_POSITIVE_ONLY", result["type"])
        self.assertEqual(
            "POSITIVE_ONLY_CANDIDATE",
            probe.evaluate_candidate_coverage(shape, self.DIRECTORY)["completeness"],
        )
        self.assertNotIn("20", [x["branchId"] for x in shape["branchRows"]])

    def test_unbound_multi_branch_is_not_verification(self):
        shape = probe.research_json_shape(
            [{"department_id": 205, "stock": 2},
             {"department_id": 204, "stock": 10}],
            "580", self.DIRECTORY,
        )
        result = probe.classify_locations_contract(
            [self.response(shape)], self.DIRECTORY, "580")
        self.assertEqual("F_INCONCLUSIVE", result["type"])

    def test_preloaded_main_product_requires_identity(self):
        data = {"props": {"pageProps": {"product": {
            "id": 580, "stock": 321,
            "other": {"recommendations": [
                {"id": 777, "stock": 999},
            ]},
        }}}}
        exact = probe.extract_exact_product_central_stock(data, "580")
        self.assertEqual(321, exact["stock"])
        self.assertEqual("NEXT_DATA_EXACT_PRODUCT_ID", exact["source"])
        data["props"]["pageProps"]["product"]["id"] = 581
        self.assertIsNone(
            probe.extract_exact_product_central_stock(data, "580")["stock"]
        )
        self.assertIsNone(
            probe.extract_exact_product_central_stock(
                {"props": {"pageProps": {"recommendations": [
                    {"id": 580, "stock": 4444}
                ]}}},
                "580"
            )["stock"]
        )

    def test_per_branch_frontend_fanout_needs_distinct_depstocks(self):
        scoped = []
        for depstock in ("205", "204", "20"):
            shape = self.shape([{
                "department_id": int(depstock), "stock": 3,
            }])
            scoped.append(self.response(
                shape, path="/api/front/products/580/current",
                query={"depstock": depstock},
            ))
        self.assertEqual(
            "E_PER_BRANCH_FANOUT",
            probe.classify_locations_contract(
                scoped, self.DIRECTORY, "580"
            )["type"],
        )

    def test_multi_request_bounded_directory_coverage(self):
        items = [
            self.response(self.shape([
                {"department_id": 205, "stock": 1},
                {"department_id": 204, "stock": 4},
            ])),
            self.response(self.shape([
                {"department_id": 204, "stock": 4},
                {"department_id": 20, "stock": 7},
            ]), path="/api/front/some-other-real-observation"),
        ]
        result = probe.classify_locations_contract(
            items, self.DIRECTORY, "580",
        )
        self.assertEqual("D_MULTI_REQUEST_BOUNDED", result["type"])

    def test_available_only_filter_local_and_request_triggering(self):
        before = {"visibleDirectoryNames": ["Nowy Sącz", "Tarnów"],
                  "zeroLabelVisible": True}
        after = {"visibleDirectoryNames": ["Tarnów"],
                 "zeroLabelVisible": False}
        local = probe.classify_availability_filter(before, after, 0, True)
        self.assertEqual("CLIENT_SIDE_FILTER_CANDIDATE", local["classification"])
        self.assertEqual(1, local["afterVisibleBranchNameCount"])
        remote = probe.classify_availability_filter(before, after, 1, True)
        self.assertEqual("REQUEST_TRIGGERED", remote["classification"])
        self.assertFalse(remote["afterZeroVisible"])

    def test_current_stock_diagnostic_excludes_recommendation_values(self):
        item = self.response(probe.research_json_shape(
            {"product_id": 580, "stock": 123,
             "department_stock": {"department_id": 205, "stock": 424},
             "recommendations": [{
                 "product_id": 581,
                 "department_stock": {"department_id": 205, "stock": 999},
             }]},
            "580", self.DIRECTORY,
        ), action="product:after",
            path="/api/front/products/580/current",
            query={"depstock": "205"})
        exact = probe.selected_branch_current_diagnostic(
            [item], "580", "205"
        )
        self.assertEqual(424, exact["stock"])
        self.assertEqual("CURRENT_IDENTITY_AND_DEPSTOCK_VERIFIED", exact["source"])
        item["shape"]["productIdentityMatches"] = False
        self.assertIsNone(
            probe.selected_branch_current_diagnostic(
                [item], "580", "205")["stock"]
        )

    def test_current_stock_rejects_missing_or_wrong_depstock(self):
        shape = probe.research_json_shape({
            "product_id": 580,
            "department_stock": {"department_id": 205, "stock": 0},
        }, "580", self.DIRECTORY)
        item = self.response(shape, action="product:after",
                             path="/api/front/products/580/current",
                             query={"depstock": "204"})
        self.assertIsNone(probe.selected_branch_current_diagnostic(
            [item], "580", "205")["stock"])

    def test_search_corrob_remains_control_only(self):
        search = self.response(probe.research_json_shape({
            "hits": [{"id": 580, "department_stock":
                      {"department_id": 205, "stock": 424, "stock_num": 424}}]
        }, "580", self.DIRECTORY), action="search:article",
            path=probe.KWANT_SEARCH_API_PATH)
        search["method"] = "POST"
        search["body"] = {"safeValues": {"q": "MBN116E", "depstock": 205}}
        current = self.response(probe.research_json_shape({
            "product_id": 580,
            "department_stock": {"department_id": 205, "stock": 424}
        }, "580", self.DIRECTORY), action="product:after",
            path="/api/front/products/580/current",
            query={"depstock": "205"})
        proof = probe.summarize_search_ranking_evidence(
            [search, current], self.DIRECTORY, "580"
        )
        self.assertEqual("SELECTED_BRANCH_STOCK_PROVEN_FOR_CONTROL",
                         proof["stockScope"])
        self.assertTrue(proof["selectedBranchStockProven"])
        self.assertFalse(
            probe.summarize_search_ranking_evidence(
                [search], self.DIRECTORY, "580"
            )["selectedBranchStockProven"]
        )

    def test_availability_request_capture_is_bounded_and_sanitized(self):
        class Request:
            resource_type = "xhr"
            url = ("https://services.kwant.net.pl/api/front/seen"
                   "?depstock=205&sessionToken=SECRET_VALUE")
            method = "GET"
            post_data = None
            headers = {}
        recorder = probe.NetworkRecorder(action="locations:open-branches")
        capture = probe.ObservedLocationsResponses(recorder, "580")
        capture.on_request(Request())
        self.assertEqual(1, len(capture.request_records))
        self.assertEqual(
            "205", capture.request_records[0]["query"]["safeValues"]["depstock"]
        )
        self.assertNotIn(
            "SECRET_VALUE", json.dumps(capture.request_records)
        )
        recorder.set_action("product:after")
        capture.on_request(Request())
        self.assertEqual(1, len(capture.request_records))
        Request.url = "https://untrusted.invalid/api/front/seen"
        recorder.set_action("locations:open-branches")
        capture.on_request(Request())
        self.assertEqual(1, len(capture.request_records))
        Request.url = "https://kwant.net.pl/api/front/visible"
        capture.on_request(Request())
        self.assertEqual(2, len(capture.request_records))
        self.assertEqual("kwant.net.pl", capture.request_records[-1]["host"])

    def test_batch_product_identity_never_inferred_from_list_order(self):
        url = "/api/front/products/prices/580,581"
        good = probe.batch_prices_stock_evidence(url, {"list": [
            {"product_id": 580,
             "department_stock": {"department_id": 205, "stock": 4}},
            {"product_id": 581,
             "department_stock": {"department_id": 205, "stock": 999}},
        ]}, "580", depstock="205")
        self.assertEqual(2, good["identityBoundRowCount"])
        self.assertEqual([{"branchId": "205", "stock": 4, "candidateField": "stock"}],
                         good["controlProductRows"])
        self.assertTrue(good["usableBatchCandidate"])
        bad = probe.batch_prices_stock_evidence(url, {"list": [
            {"department_stock": {"department_id": 205, "stock": 4}},
            {"product_id": 581,
             "department_stock": {"department_id": 205, "stock": 999}},
        ]}, "580", depstock="205")
        self.assertEqual([], bad["controlProductRows"])
        self.assertFalse(bad["usableBatchCandidate"])
        wrong_requested_id = probe.batch_prices_stock_evidence(url, {
            "list": [
                {"product_id": 580, "department_stock":
                    {"department_id": 205, "stock": 4}},
                {"product_id": 582, "department_stock":
                    {"department_id": 205, "stock": 7}},
            ],
        }, "580", depstock="205")
        self.assertFalse(wrong_requested_id["usableBatchCandidate"])
        wrong_branch = probe.batch_prices_stock_evidence(url, {"list": [
            {"product_id": 580, "department_stock":
                {"department_id": 204, "stock": 4}},
            {"product_id": 581, "department_stock":
                {"department_id": 205, "stock": 7}},
        ]}, "580", depstock="205")
        self.assertFalse(wrong_branch["usableBatchCandidate"])
        without_depstock = probe.batch_prices_stock_evidence(url, {"list": [
            {"product_id": 580, "department_stock":
                {"department_id": 205, "stock": 4}},
            {"product_id": 581, "department_stock":
                {"department_id": 205, "stock": 7}},
        ]}, "580")
        self.assertFalse(without_depstock["usableBatchCandidate"])
        alternate = probe.batch_prices_stock_evidence(url, {"list": [
            {"product_id": 580, "department_stock":
                {"department_id": 205, "stock_num": 4}},
            {"product_id": 581, "department_stock":
                {"department_id": 205, "stockNum": 8}},
        ]}, "580", depstock="205")
        self.assertTrue(alternate["usableBatchCandidate"])
        self.assertEqual("stock_num",
                         alternate["controlProductRows"][0]["candidateField"])
        conflicting_fields = probe.batch_prices_stock_evidence(url, {"list": [
            {"product_id": 580, "department_stock":
                {"department_id": 205, "stock": 4, "stock_num": 8}},
            {"product_id": 581, "department_stock":
                {"department_id": 205, "stock": 8}},
        ]}, "580", depstock="205")
        self.assertFalse(conflicting_fields["usableBatchCandidate"])


if __name__ == "__main__":
    unittest.main()
