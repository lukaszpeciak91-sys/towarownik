import json
import unittest
from pathlib import Path

import obi_live_contract_probe as probe

FIXTURE_PATH = Path(__file__).with_name("fixtures") / "obi_flattened_nuxt.json"


def flattened_fixture():
    return json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))


class NuxtExtractionTest(unittest.TestCase):
    def test_extracts_nuxt_script(self):
        html = (
            '<html><script id="__NUXT_DATA__" type="application/json">'
            '[{"product":1},"3496072"]'
            '</script></html>'
        )
        self.assertEqual('[{"product":1},"3496072"]', probe.extract_nuxt_payload(html))

    def test_missing_nuxt_script_is_explicit_failure(self):
        with self.assertRaisesRegex(ValueError, "Missing __NUXT_DATA__"):
            probe.extract_nuxt_payload("<html></html>")

    def test_malformed_nuxt_json_is_explicit_failure(self):
        with self.assertRaisesRegex(ValueError, "not valid JSON"):
            probe.parse_nuxt_payload("[not-json]")


class FlattenedReferenceTest(unittest.TestCase):
    def test_reference_trace_resolves_scalar_once_without_treating_scalar_as_another_index(self):
        root = [None] * 30
        root[3] = 25
        root[25] = "must-not-be-followed"

        trace = probe.reference_trace(root, 3, reveal_scalar=True)

        self.assertEqual(
            {
                "kind": "reference",
                "chain": [{"index": 3, "resolved": 25}],
            },
            trace,
        )
        self.assertEqual("ref top[3] -> 25", probe.format_reference_trace(trace))

    def test_reference_trace_follows_known_nuxt_wrapper(self):
        root = [None, ["ShallowRef", 2], {"skuId": 3}, "3496072"]

        trace = probe.reference_trace(root, 1, reveal_scalar=True)

        self.assertEqual("reference", trace["kind"])
        self.assertEqual([1, 2], [item["index"] for item in trace["chain"]])
        self.assertEqual("wrapper", trace["chain"][0]["resolved"]["type"])
        self.assertEqual("object", trace["chain"][1]["resolved"]["type"])

    def test_keyword_hit_reports_resolved_live_shape_values_not_reference_indices(self):
        root = flattened_fixture()

        hits = probe.keyword_hits(root)
        store_article = next(hit for hit in hits if hit["objectPath"] == "$[10]" and hit["key"] == "stock")
        store_pricing = next(hit for hit in hits if hit["objectPath"] == "$[10]" and hit["key"] == "pricing")
        gross = next(hit for hit in hits if hit["objectPath"] == "$[12]" and hit["key"] == "grossPrice")
        seller = next(hit for hit in hits if hit["objectPath"] == "$[14]" and hit["key"] == "stock")

        self.assertEqual(
            "ref top[11] -> 25",
            probe.format_reference_trace(store_article["referenceTrace"]),
        )
        self.assertEqual(
            "ref top[12] -> object keys=['grossPrice']",
            probe.format_reference_trace(store_pricing["referenceTrace"]),
        )
        self.assertEqual(
            "ref top[15] -> 12.99",
            probe.format_reference_trace(gross["referenceTrace"]),
        )
        self.assertEqual(
            "ref top[17] -> 9",
            probe.format_reference_trace(seller["referenceTrace"]),
        )

    def test_safe_summary_does_not_expose_non_allowlisted_scalar_values(self):
        secret = "VERY_SECRET_TOKEN"
        root = [
            {"productAuthToken": 1, "storeSessionToken": 2, "articleRequestId": 3},
            secret,
            "SESSION_SECRET_123",
            "REQUEST_UUID_SECRET",
        ]
        payload = json.dumps(root)
        html = (
            '<html><script id="__NUXT_DATA__" type="application/json">'
            + payload
            + '</script></html>'
        )

        hits = probe.keyword_hits(root)
        serialized_hits = json.dumps(hits, ensure_ascii=False)
        self.assertNotIn(secret, serialized_hits)
        self.assertNotIn("SESSION_SECRET_123", serialized_hits)
        self.assertNotIn("REQUEST_UUID_SECRET", serialized_hits)

        for hit in hits:
            formatted = probe.format_reference_trace(hit["referenceTrace"])
            self.assertIn("string length=", formatted)

        summary = probe.build_summary(
            html=html,
            root=root + ["3496072", "075"],
            payload=payload,
            obik="3496072",
            store="075",
            status="200",
            final_url="https://www.obi.pl/p/3496072/example",
        )

        temp_path = Path(self._testMethodName + ".txt")
        try:
            probe.write_text_summary(summary, temp_path)
            text_summary = temp_path.read_text(encoding="utf-8")
        finally:
            temp_path.unlink(missing_ok=True)

        json_summary = json.dumps(summary, ensure_ascii=False)
        for forbidden in (secret, "SESSION_SECRET_123", "REQUEST_UUID_SECRET"):
            self.assertNotIn(forbidden, json_summary)
            self.assertNotIn(forbidden, text_summary)

    def test_reverse_reference_layers_walk_from_value_to_owner(self):
        root = [
            None,
            {"product": 2},
            ["ShallowRef", 3],
            {"skuId": 4},
            "3496072",
        ]

        layers = probe.reference_layers(root, [4], depth=3)

        self.assertEqual([3], [edge["ownerIndex"] for edge in layers[0]["edges"]])
        self.assertEqual([2], [edge["ownerIndex"] for edge in layers[1]["edges"]])
        self.assertEqual([1], [edge["ownerIndex"] for edge in layers[2]["edges"]])

    def test_bounded_collectors_honor_limits(self):
        root = ["3496072"] * (probe.MAX_EXACT_MATCHES + 20)
        self.assertEqual(probe.MAX_EXACT_MATCHES, len(probe.exact_matches(root, "3496072")))

        many_keys = {f"stock{index}": index for index in range(probe.MAX_KEY_HITS + 20)}
        self.assertEqual(probe.MAX_KEY_HITS, len(probe.keyword_hits([many_keys])))


class UrlSanitizationTest(unittest.TestCase):
    def test_expected_obi_url_drops_query_and_fragment(self):
        expected, safe = probe.sanitize_obi_final_url(
            "https://www.obi.pl/p/3496072/example?token=secret#fragment"
        )
        self.assertTrue(expected)
        self.assertEqual("https://www.obi.pl/p/3496072/example", safe)

    def test_unexpected_or_invalid_url_is_redacted(self):
        cases = [
            "https://example.com/p/3496072?token=secret",
            "http://www.obi.pl/p/3496072",
            "https://user:pass@www.obi.pl/p/3496072",
            "https://www.obi.pl:444/p/3496072",
            "https://[invalid",
        ]
        for value in cases:
            with self.subTest(value=value):
                expected, safe = probe.sanitize_obi_final_url(value)
                self.assertFalse(expected)
                self.assertTrue(safe.startswith("REDACTED_"))


class InputValidationTest(unittest.TestCase):
    def test_accepts_expected_identifier_shapes(self):
        probe.validate_identifiers("3496072", "075")

    def test_rejects_invalid_identifier_shapes(self):
        invalid = [
            ("349607", "075"),
            ("3496072x", "075"),
            ("3496072", "75"),
            ("3496072", "07x"),
        ]
        for obik, store in invalid:
            with self.subTest(obik=obik, store=store):
                with self.assertRaises(ValueError):
                    probe.validate_identifiers(obik, store)



class MultiMarketResearchTest(unittest.TestCase):
    """Synthetic, offline; no browser/network contact in PR CI."""

    @classmethod
    def setUpClass(cls):
        import obi_locations_research as research
        cls.r = research
        root = Path(__file__).resolve().parents[1]
        cls.stores = research.canonical_stores(
            root / "app/src/main/java/pl/lukaszpeciak/towarownik/product/ObiStores.kt"
        )
        cls.obik = "3496072"

    def observation(self, data, *, path="/api/products/3496072/stores",
                    action="availability:open", status=200,
                    query=None, body=None):
        r = self.r
        return {
            "action": action, "host": "www.obi.pl", "method": "GET",
            "path": path, "status": status,
            "query": query or {"names": [], "safeValues": {}},
            "body": body, "shape": r.response_shape(
                data, self.obik, self.stores, path,
            ),
        }

    class FakeExactButton:
        def __init__(self, *, name=None, visible=True, enabled=True,
                     component="PdpLink", accessible_prefix="arrow-right"):
            self.name = name
            self.visible = visible
            self.enabled = enabled
            self.component = component
            self.accessible_name = (
                accessible_prefix + " " + (name or "")
            )
            self.clicks = 0
            self.scrolled = 0

        def inner_text(self, timeout=0):
            return self.name or ""

        def is_visible(self):
            return self.visible

        def is_enabled(self):
            return self.enabled

        def get_attribute(self, name):
            return self.component if name == "data-component" else None

        def scroll_into_view_if_needed(self, timeout=0):
            self.scrolled += 1

        def click(self, timeout=0):
            self.clicks += 1

    @staticmethod
    def fake_exact_button_page(*buttons):
        class Locator:
            def __init__(self, matches):
                self.matches = matches

            def count(self):
                return len(self.matches)

            def nth(self, index):
                return self.matches[index]

        class Page:
            def __init__(self, items):
                self.buttons = items
                self.last_lookup = None

            def locator(self, selector):
                self.last_lookup = selector
                if selector != 'button[data-component="PdpLink"]':
                    raise AssertionError("Must use observed native button selector")
                return Locator([
                    b for b in self.buttons if b.component == "PdpLink"
                ])

            def wait_for_timeout(self, millis):
                pass

        return Page(buttons)

    def test_observed_exact_button_selected_without_dom_index_or_recommendations(self):
        r = self.r
        for order in (("recommendation", "real"), ("real", "recommendation")):
            with self.subTest(order=order):
                exact = self.FakeExactButton(
                    name=" \nSprawdź   dostępność w innym sklepie\t"
                )
                recommendation = self.FakeExactButton(
                    name="Dostępność Dragon klej w innym sklepie"
                )
                by_name = {"real": exact, "recommendation": recommendation}
                page = self.fake_exact_button_page(
                    *(by_name[key] for key in order)
                )
                selected, status = r.resolve_exact_availability_opener(page)
                self.assertIs(selected, exact)
                self.assertEqual("EXACT_BUTTON_RESOLVED", status)
                result = r.click_safe_control(
                    page, [{"index": 0, "label": "dostępność (recommendation)"}]
                )
                self.assertEqual("CLICKED", result["status"])
                self.assertEqual(
                    'button[data-component="PdpLink"]', page.last_lookup
                )
                self.assertEqual(1, exact.clicks)
                self.assertEqual(1, exact.scrolled)
                self.assertEqual(0, recommendation.clicks)
                # Live SVG title influences accessible name but never innerText.
                self.assertIn("arrow-right", exact.accessible_name)

    def test_exact_availability_duplicate_missing_hidden_disabled_changed_fail_closed(self):
        r = self.r
        exact = lambda **kwargs: self.FakeExactButton(
            name=r.OBSERVED_AVAILABILITY_BUTTON, **kwargs
        )
        examples = (
            ([], "EXACT_BUTTON_MISSING"),
            ([exact(), exact()], "EXACT_BUTTON_AMBIGUOUS"),
            ([exact(visible=False)], "EXACT_BUTTON_HIDDEN"),
            ([exact(enabled=False)], "EXACT_BUTTON_DISABLED"),
            ([exact(component="Checkout")], "EXACT_BUTTON_MISSING"),
            ([self.FakeExactButton(name="Sprawdź dostępność")], "EXACT_BUTTON_MISSING"),
            ([self.FakeExactButton(name="Sprawdź dostępność w innym sklepie — kup")],
             "EXACT_BUTTON_MISSING"),
        )
        for buttons, expected in examples:
            with self.subTest(expected=expected):
                page = self.fake_exact_button_page(*buttons)
                result = r.click_safe_control(page, [])
                self.assertEqual(expected, result["status"])
                self.assertTrue(all(b.clicks == 0 for b in buttons))

    def test_recommendation_links_with_same_words_do_not_match_native_pdp_button(self):
        real = self.FakeExactButton(name=self.r.OBSERVED_AVAILABILITY_BUTTON)
        other = self.FakeExactButton(
            name=self.r.OBSERVED_AVAILABILITY_BUTTON, component="a"
        )
        page = self.fake_exact_button_page(other, real)
        self.assertIs(self.r.resolve_exact_availability_opener(page)[0], real)
        self.assertEqual(0, other.clicks)

    class FakeModalLocator:
        def __init__(self, nodes=()):
            self.nodes = list(nodes)

        def count(self):
            return len(self.nodes)

        def nth(self, index):
            return self.nodes[index]

    class FakeModalNode:
        def __init__(self, *, content="", visible=True, enabled=True,
                     attributes=None, children=None):
            self.content = content
            self.visible = visible
            self.enabled = enabled
            self.attributes = attributes or {}
            self.children = children or {}
            self.clicks = 0

        def is_visible(self):
            return self.visible

        def is_enabled(self):
            return self.enabled

        def get_attribute(self, name):
            return self.attributes.get(name)

        def inner_text(self, timeout=0):
            return self.content

        def evaluate(self, expression):
            return list(self.attributes)[:45]

        def locator(self, selector):
            return MultiMarketResearchTest.FakeModalLocator(
                self.children.get(selector, ())
            )

        def scroll_into_view_if_needed(self, timeout=0):
            pass

        def click(self, timeout=0):
            self.clicks += 1

    def test_post_open_ui_exposes_only_bounded_modal_structure_and_canonical_ids(self):
        r = self.r
        heading = self.FakeModalNode(
            content="Wybierz sklep",
            attributes={"role": "heading"},
        )
        search = self.FakeModalNode(
            attributes={
                "type": "search",
                "placeholder": "Wyszukaj sklep",
                "aria-label": "Wyszukaj sklep",
            },
        )
        private_address = self.stores["003"]["address"]
        market = self.FakeModalNode(
            content="Kraków " + private_address + " 30-999 PRIVATE_UNSAFE",
            attributes={
                "data-store-number": "003",
                "data-analytics-secret": "SECRET_SESSION",
                "data-market-token": "PRIVATE_TOKEN",
            },
        )
        other = self.FakeModalNode(
            content="Łódź " + self.stores["074"]["address"],
            attributes={"data-market-id": "074"},
        )
        controls = 'button,[role="button"],[role="option"],a[href],[data-store-number],[data-store-id],[data-market-id]'
        dialog = self.FakeModalNode(
            visible=True,
            attributes={"role": "dialog"},
            children={
                'h1,h2,h3,h4,[role="heading"],label': [heading],
                'input,textarea,[role="combobox"],[role="searchbox"]': [search],
                controls: [market, other],
            },
        )
        page = self.FakeModalNode(children={
            'dialog,[role="dialog"],[aria-modal="true"]': [dialog],
            '[role="region"]': [],
        })
        result = r.inspect_post_open_ui(page, self.stores)
        self.assertEqual(1, result["visibleDialogCount"])
        self.assertEqual("ONE_VISIBLE_DIALOG", result["scope"])
        self.assertTrue(result["storeClickScopeVerified"])
        self.assertEqual(1, result["visibleInputCount"])
        self.assertTrue(result["storeSearchInputObserved"])
        self.assertEqual("search", result["inputs"][0]["type"])
        self.assertEqual(2, result["candidateStoreRowsCount"])
        self.assertEqual(
            {"003", "074"},
            {x["storeNumber"] for x in result["canonicalStoreCandidates"]}
        )
        self.assertIn(
            "data-store-number", result["dataAttributeNames"]
        )
        self.assertTrue(result["structuralOnly"])
        safe_json = json.dumps(result, ensure_ascii=False)
        for private in (private_address, "PRIVATE_UNSAFE", "30-999",
                        "SECRET_SESSION", "PRIVATE_TOKEN", "data-market-token",
                        "data-analytics-secret"):
            self.assertNotIn(private, safe_json)

    def test_canonical_address_needs_city_and_address_not_city_only(self):
        r = self.r
        addr = self.stores["003"]["address"]
        known = r.identify_canonical_dom_store(
            "Kraków " + addr + " SECRET_RAW", {}, self.stores,
        )
        self.assertEqual("003", known["storeNumber"])
        self.assertEqual("VERIFIED", known["identityStatus"])
        self.assertNotIn(addr, json.dumps(known))
        self.assertNotIn("SECRET_RAW", json.dumps(known))
        unknown = r.identify_canonical_dom_store(
            "Kraków", {}, self.stores,
        )
        self.assertIsNone(unknown["storeNumber"])
        self.assertEqual("UNKNOWN", unknown["identityStatus"])
        wrong_city = r.identify_canonical_dom_store(
            "Warszawa " + addr, {}, self.stores
        )
        self.assertEqual("UNKNOWN", wrong_city["identityStatus"])
        conflict = r.identify_canonical_dom_store(
            "Kraków " + addr,
            {"data-market-id": "019"}, self.stores
        )
        self.assertIsNone(conflict["storeNumber"])
        self.assertEqual("AMBIGUOUS", conflict["identityStatus"])
        self.assertNotIn("Kraków", json.dumps(conflict))

    def test_unscoped_or_search_only_ui_never_triggers_guessed_store_selection(self):
        r = self.r
        controls = 'button,[role="button"],[role="option"],a[href],[data-store-number],[data-store-id],[data-market-id]'
        search = self.FakeModalNode(
            attributes={"type": "text", "placeholder": "Znajdź sklep"}
        )
        dialog = self.FakeModalNode(children={
            'h1,h2,h3,h4,[role="heading"],label': [],
            'input,textarea,[role="combobox"],[role="searchbox"]': [search],
            controls: [],
        })
        page = self.FakeModalNode(children={
            'dialog,[role="dialog"],[aria-modal="true"]': [dialog],
            '[role="region"]': [],
        })
        summary = r.inspect_post_open_ui(page, self.stores)
        self.assertEqual(0, summary["candidateStoreRowsCount"])
        self.assertTrue(summary["storeSearchInputObserved"])
        self.assertEqual(
            "NO_UNAMBIGUOUS_STORE_ROW",
            r._try_market_choice(page, "003", self.stores)["status"]
        )
        unscoped = self.FakeModalNode(children={
            'dialog,[role="dialog"],[aria-modal="true"]': [],
            '[role="region"]': [],
        })
        summary = r.inspect_post_open_ui(unscoped, self.stores)
        self.assertFalse(summary["storeClickScopeVerified"])
        self.assertEqual(
            "NO_UNAMBIGUOUS_DIALOG",
            r._try_market_choice(unscoped, "003", self.stores)["status"]
        )
        self.assertEqual(0, search.clicks)

    def test_initial_empty_sp_and_post_open_rows_are_distinct_action_evidence(self):
        path = "/api/pdp/v1/availability/sp/3496072"
        initial = self.observation(
            {"pickupStores": []}, path=path, action="initial:page"
        )
        self.assertEqual(0, initial["shape"]["pickupStoresStructure"]["containerLength"])
        self.assertEqual("F_INCONCLUSIVE",
            self.r.classify_contract([initial], self.stores, self.obik)["type"])
        post = self.observation(
            {"pickupStores": [
                {"storeNumber": "075", "stock": 0},
                {"storeNumber": "003", "availability": "available"},
            ]}, path=path, action="availability:open"
        )
        self.assertNotEqual(initial["action"], post["action"])
        self.assertEqual("initial:page", initial["action"])
        self.assertEqual("availability:open", post["action"])
        self.assertEqual(0, len(initial["shape"]["storeRows"]))
        self.assertEqual(2, len(post["shape"]["storeRows"]))
        self.assertEqual(0, post["shape"]["storeRows"][0]["value"])
        self.assertNotIn("074", self.r.verified_rows(post, self.stores))
        self.assertEqual(
            "B_ONE_SHOT_SUBSET",
            self.r.classify_contract([initial, post], self.stores, self.obik)["type"]
        )

    def test_observed_sp_structural_diagnostics_no_unknown_field_classification(self):
        r = self.r
        payload = {"pickupStores": [
            {"storeCode": "075", "stateOfGoods": "top-secret-free-text",
             "quantityMaybe": 0, "someOtherStock": None,
             "street": "ul. SECRET 99", "postalCode": "33-300",
             "coordinates": [49.0, 20.0], "apiToken": "SECRET_TOKEN"},
            {"storeCode": "003", "stateOfGoods": "second-secret-value",
             "quantityMaybe": 4, "userSession": "SESSION-PRIVATE"},
        ]}
        path = "/api/pdp/v1/availability/sp/3496072"
        record = self.observation(payload, path=path, action="initial:page")
        structure = record["shape"]["pickupStoresStructure"]
        self.assertEqual("list", structure["containerType"])
        self.assertEqual(2, structure["containerLength"])
        self.assertTrue(structure["structuralOnly"])
        self.assertTrue(structure["representativeObjectKeys"])
        self.assertIn({"field": "storeCode", "storeNumber": "075"},
                      structure["canonicalStoreIdFields"])
        self.assertIn({"field": "storeCode", "storeNumber": "003"},
                      structure["canonicalStoreIdFields"])
        self.assertTrue(any(
            entry["field"] == "quantityMaybe" and entry["scalarType"] == "integer"
            and entry["numericCandidate"] == 0
            for entry in structure["availabilityCandidateFields"]
        ))
        self.assertEqual([], record["shape"]["storeRows"])
        self.assertEqual(
            "SINGULAR_REQUEST_PRODUCT_PATH",
            r.product_identity(record, self.obik)
        )
        self.assertTrue(r.observed_availability_response(record, self.obik))
        self.assertEqual("F_INCONCLUSIVE",
            r.classify_contract([record], self.stores, self.obik)["type"])
        safe = json.dumps(record, ensure_ascii=False)
        for secret in ("SECRET", "33-300", "49.0", "20.0", "userSession",
                       "postalCode", "coordinates", "stateOfGoods\": \"top"):
            self.assertNotIn(secret, safe)

    def test_sp_structural_diagnostic_object_nesting_without_stock_row(self):
        data = {"pickupStores": {"results": {"items": [
            {"branchCode": "075", "unknownState": False},
            {"branchCode": "003", "unknownState": None},
        ]}}}
        record = self.observation(
            data, path="/api/pdp/v1/availability/sp/3496072",
            action="initial:page"
        )
        tree = record["shape"]["pickupStoresStructure"]
        self.assertEqual("object", tree["containerType"])
        self.assertIn("results", tree["containerKeys"])
        self.assertGreaterEqual(len(tree["nestingShape"]), 3)
        self.assertEqual("F_INCONCLUSIVE",
            self.r.classify_contract([record], self.stores, self.obik)["type"])

    def test_initial_product_bound_sp_can_classify_a_and_b_only_with_known_states(self):
        path = "/api/pdp/v1/availability/sp/3496072"
        all_stores = {"pickupStores": [
            {"storeNumber": store, "stock": 0 if i == 0 else i + 2}
            for i, store in enumerate(self.stores)
        ]}
        record = self.observation(
            all_stores, path=path, action="initial:page"
        )
        self.assertEqual("A_ONE_SHOT_ALL_STORES",
            self.r.classify_contract([record], self.stores, self.obik)["type"])
        self.assertEqual("known_zero",
                         record["shape"]["storeRows"][0]["state"])
        self.assertEqual(0, record["shape"]["storeRows"][0]["value"])
        partial = self.observation({"pickupStores": [
            {"storeNumber": "075", "stock": 0},
            {"storeNumber": "003", "availability": "available"},
        ]}, path=path, action="initial:page")
        self.assertEqual("B_ONE_SHOT_SUBSET",
            self.r.classify_contract([partial], self.stores, self.obik)["type"])
        self.assertNotIn("074",
            self.r.verified_rows(partial, self.stores))
        invalid = self.observation({"pickupStores": [
            {"storeNumber": "075", "stock": None},
            {"storeNumber": "003", "availability": None},
        ]}, path=path, action="initial:page")
        self.assertEqual("F_INCONCLUSIVE",
            self.r.classify_contract([invalid], self.stores, self.obik)["type"])

    def test_generic_status_and_requested_quantity_are_not_inventory_evidence(self):
        path = "/api/pdp/v1/availability/sp/3496072"
        for field, value in (("status", "available"), ("quantity", 2)):
            with self.subTest(field=field):
                record = self.observation(
                    {"pickupStores": [
                        {"storeNumber": "075", field: value},
                        {"storeNumber": "003", field: value},
                    ]},
                    path=path, action="initial:page",
                )
                self.assertEqual(
                    "F_INCONCLUSIVE",
                    self.r.classify_contract(
                        [record], self.stores, self.obik
                    )["type"],
                )
                self.assertEqual({}, self.r.verified_rows(record, self.stores))
                self.assertTrue(
                    record["shape"]["pickupStoresStructure"]["structuralOnly"]
                )

    def test_exact_opener_click_timeout_returns_bounded_category(self):
        class TimeoutButton(self.FakeExactButton):
            def click(self, timeout=0):
                raise TimeoutError("SENSITIVE page text and URL token=SECRET")

        button = TimeoutButton(name=self.r.OBSERVED_AVAILABILITY_BUTTON)
        page = self.fake_exact_button_page(button)
        result = self.r.click_safe_control(page, [])
        self.assertEqual("CLICK_FAILED", result["status"])
        # Python TimeoutError should remain bounded, without exception text.
        self.assertEqual("TIMEOUT", result["failureCategory"])
        self.assertNotIn("SECRET", json.dumps(result))
        self.assertNotIn("SENSITIVE", json.dumps(result))

    def test_initial_page_generic_and_hd_separate_without_market_states(self):
        r = self.r
        rows = [
            {"storeNumber": "075", "stock": 3},
            {"storeNumber": "003", "stock": 2},
        ]
        for path in (
            "/api/teasers", "/api/recommendations/3496072",
            "/api/stores", "/api/cms/3496072",
        ):
            with self.subTest(path=path):
                record = self.observation(
                    {"pickupStores": rows}, path=path, action="initial:page"
                )
                self.assertFalse(
                    r.observed_availability_response(record, self.obik)
                )
                self.assertEqual("F_INCONCLUSIVE",
                    r.classify_contract([record], self.stores, self.obik)["type"])
        hd = self.observation(
            {"deliveryDataPerSeller": {
                "sellerMetadata": {"name": "PRIVATE seller title"},
                "deliveryState": "low",
            }},
            path="/api/pdp/v1/availability/hd/3496072",
            action="initial:page",
        )
        self.assertIn("deliveryDataPerSellerStructure", hd["shape"])
        self.assertNotIn("pickupStoresStructure", hd["shape"])
        self.assertEqual([], hd["shape"]["storeRows"])
        self.assertEqual("F_INCONCLUSIVE",
            r.classify_contract([hd], self.stores, self.obik)["type"])
        self.assertNotIn("PRIVATE seller title", json.dumps(hd))
        wrong = self.observation(
            {"pickupStores": rows},
            path="/api/pdp/v1/availability/sp/3496073",
            action="initial:page",
        )
        self.assertEqual("F_INCONCLUSIVE",
            r.classify_contract([wrong], self.stores, self.obik)["type"])
        self.assertNotIn("pickupStoresStructure", wrong["shape"])

    def test_canonical_store_directory_is_exact_not_inferred_from_city(self):
        self.assertGreaterEqual(len(self.stores), 50)
        self.assertEqual("Nowy Sącz", self.stores["075"]["city"])
        self.assertIn("Wielicka", self.stores["003"]["address"])
        self.assertIn("Bora-Komorowskiego", self.stores["019"]["address"])
        self.assertIn("Wieniawskiego", self.stores["074"]["address"])
        self.assertNotEqual(self.stores["003"]["address"], self.stores["019"]["address"])

    def test_product_bound_one_shot_all_canonical_stores(self):
        data = {"list": [
            {"storeNumber": store, "stock": 0 if i == 0 else i}
            for i, store in enumerate(self.stores)
        ]}
        item = self.observation(data)
        self.assertEqual(
            "SINGULAR_REQUEST_PRODUCT_PATH",
            self.r.product_identity(item, self.obik),
        )
        self.assertEqual(
            len(self.stores), len(self.r.verified_rows(item, self.stores))
        )
        self.assertEqual(
            "A_ONE_SHOT_ALL_STORES",
            self.r.classify_contract([item], self.stores, self.obik)["type"]
        )
        self.assertEqual("known_zero", item["shape"]["storeRows"][0]["state"])
        self.assertEqual(0, item["shape"]["storeRows"][0]["value"])

    def test_wrong_product_and_generic_directory_never_product_bind(self):
        data = {"list": [
            {"storeNumber": "075", "stock": 3},
            {"storeNumber": "003", "stock": 2},
        ]}
        for path in (
            "/api/products/3496073/stores",
            "/api/stores",
            "/api/catalog/3496072",
            "/api/products/34960721/stores",
        ):
            with self.subTest(path=path):
                item = self.observation(data, path=path)
                self.assertEqual("UNKNOWN", self.r.product_identity(item, self.obik))
                self.assertEqual("F_INCONCLUSIVE",
                    self.r.classify_contract([item], self.stores, self.obik)["type"])
        conflict = self.observation(
            {"productId": "3496073", "list": data["list"]}
        )
        self.assertEqual("CONFLICT", self.r.product_identity(conflict, self.obik))
        self.assertEqual("F_INCONCLUSIVE",
            self.r.classify_contract([conflict], self.stores, self.obik)["type"])

    def test_safe_product_body_and_query_bind_only_exact_identifiers(self):
        item = self.observation(
            {"list": [{"storeNumber": "075", "stock": 4}]},
            path="/api/availability",
            query={"names": ["skuId"], "safeValues": {"skuId": "3496072"}},
        )
        self.assertEqual("EXACT_REQUEST_PRODUCT_ID",
            self.r.product_identity(item, self.obik))
        item["query"] = {"names": [], "safeValues": {}}
        item["body"] = {"type": "object", "names": ["obik"],
                        "safeValues": {"obik": "3496072"}}
        self.assertEqual("EXACT_REQUEST_PRODUCT_ID",
            self.r.product_identity(item, self.obik))
        item["body"]["safeValues"]["obik"] = "3496073"
        self.assertEqual("UNKNOWN", self.r.product_identity(item, self.obik))

    def test_noncanonical_and_duplicate_store_numbers_fail(self):
        data = {"list": [
            {"storeNumber": "075", "stock": 4},
            {"storeNumber": "099", "stock": 9},
        ]}
        item = self.observation(data)
        self.assertEqual({}, self.r.verified_rows(item, self.stores))
        self.assertEqual("F_INCONCLUSIVE",
            self.r.classify_contract([item], self.stores, self.obik)["type"])
        duplicate = self.observation({"list": [
            {"storeNumber": "075", "stock": 1},
            {"storeNumber": "075", "stock": 2},
        ]})
        self.assertEqual({}, self.r.verified_rows(duplicate, self.stores))
        ambiguous = self.observation({"list": [
            {"storeId": "003", "storeNumber": "019", "stock": 4},
        ]})
        self.assertEqual({}, self.r.verified_rows(ambiguous, self.stores))
        numeric_without_leading_zero = self.observation({"list": [
            {"storeNumber": 75, "stock": 5},
        ]})
        self.assertEqual({}, self.r.verified_rows(numeric_without_leading_zero, self.stores))

    def test_zero_null_missing_and_qualitative_are_distinct(self):
        item = self.observation({"list": [
            {"storeNumber": "075", "stock": 0},
            {"storeNumber": "003", "stock": None},
            {"storeNumber": "074", "availability": "low"},
            {"storeNumber": "019", "pickupAvailable": False},
        ]})
        rows = self.r.verified_rows(item, self.stores)
        self.assertEqual(0, rows["075"]["value"])
        self.assertEqual("known_zero", rows["075"]["state"])
        self.assertEqual("unknown_null", rows["003"]["state"])
        self.assertNotIn("value", rows["003"])
        self.assertEqual("qualitative", rows["074"]["state"])
        self.assertEqual("low", rows["074"]["value"])
        self.assertEqual("qualitative", rows["019"]["state"])
        self.assertIs(rows["019"]["value"], False)
        self.assertNotIn("072", rows)

    def test_one_shot_subset_omissions_remain_unknown(self):
        item = self.observation({"list": [
            {"storeNumber": "075", "stock": 0},
            {"storeNumber": "003", "stock": 8},
        ]})
        result = self.r.classify_contract([item], self.stores, self.obik)
        self.assertEqual("B_ONE_SHOT_SUBSET", result["type"])
        self.assertEqual(2, result["observedStoreCount"])
        self.assertNotIn("019", self.r.verified_rows(item, self.stores))

    def test_bounded_fanout_and_two_request_pattern(self):
        def single(store):
            return self.observation(
                {"storeNumber": store, "stock": 1},
                path="/api/products/3496072/availability",
                action="availability:store-" + store,
                query={"names": ["storeNumber"],
                       "safeValues": {"storeNumber": store}},
            )
        two = [single("075"), single("003")]
        three = two + [single("074")]
        self.assertEqual("D_MULTI_REQUEST_BOUNDED",
            self.r.classify_contract(two, self.stores, self.obik)["type"])
        self.assertEqual("E_PER_STORE_FANOUT",
            self.r.classify_contract(three, self.stores, self.obik)["type"])

    def test_unrelated_initial_request_and_nonproduct_traffic_ignored(self):
        data = {"list": [
            {"storeNumber": "075", "stock": 1},
            {"storeNumber": "003", "stock": 1},
        ]}
        initial = self.observation(data, action="initial:page")
        generic = self.observation(data, path="/api/stores")
        self.assertEqual("F_INCONCLUSIVE",
            self.r.classify_contract([initial, generic], self.stores, self.obik)["type"])

    def test_nuxt_product_owned_preloaded_vs_global_store_directory(self):
        rows = [
            {"storeNumber": "075", "availability": "available"},
            {"storeNumber": "003", "stock": 0},
        ]
        # Flattened Nuxt: skuId resolves a scalar in the shared list.
        owned = [
            {"data": 1, "globalStoreDirectory": rows},
            {"skuId": 2, "storeAvailability": rows},
            "3496072",
        ]
        def html(root):
            return ('<script id="__NUXT_DATA__" type="application/json">'
                    + json.dumps(root) + "</script>")
        result = self.r.inspect_initial_nuxt(html(owned), self.obik, self.stores)
        self.assertTrue(result["productIdentityVerified"])
        self.assertEqual(2, len(result["verifiedProductOwnedRows"]))
        self.assertEqual("C_FRONTEND_PRELOADED",
            self.r.classify_contract([], self.stores, self.obik, result)["type"])
        unrelated = [{"data": 1, "storeAvailability": rows},
                     {"skuId": 2}, "3496072"]
        result = self.r.inspect_initial_nuxt(html(unrelated), self.obik, self.stores)
        self.assertEqual([], result["verifiedProductOwnedRows"])
        self.assertEqual("F_INCONCLUSIVE",
            self.r.classify_contract([], self.stores, self.obik, result)["type"])

    def test_flattened_nuxt_product_owned_references_preserve_real_zero(self):
        # Here the sku, list, two rows, ID and stock values are all
        # referenced from flattened Nuxt entries; zero is a VALUE, not
        # the next top-level index to follow.
        root = [
            {"data": 1},
            {"skuId": 2, "storeAvailability": 3},
            "3496072",
            [4, 5],
            {"storeNumber": 6, "stock": 7},
            {"storeNumber": 8, "availability": 9},
            "075",
            0,
            "003",
            "available",
        ]
        html = (
            '<script id="__NUXT_DATA__" type="application/json">'
            + json.dumps(root) + "</script>"
        )
        result = self.r.inspect_initial_nuxt(html, self.obik, self.stores)
        self.assertTrue(result["productIdentityVerified"])
        self.assertEqual(2, len(result["verifiedProductOwnedRows"]))
        first = result["verifiedProductOwnedRows"][0]
        self.assertEqual("075", first["storeNumber"])
        self.assertEqual("known_zero", first["state"])
        self.assertEqual(0, first["value"])
        self.assertEqual(
            "C_FRONTEND_PRELOADED",
            self.r.classify_contract([], self.stores, self.obik, result)["type"]
        )

    def test_product_owned_store_context_proves_selected_market_before_click(self):
        root = [
            {"data": 1, "globalStoreDirectory": [
                {"storeId": "003"}, {"storeId": "019"},
            ]},
            {"skuId": 2, "store": 3},
            "3496072",
            {"information": 4},
            {"storeId": 5},
            "075",
        ]
        html = (
            '<script id="__NUXT_DATA__" type="application/json">'
            + json.dumps(root) + "</script>"
        )
        correct = self.r.inspect_initial_nuxt(
            html, self.obik, self.stores, selected_store="075"
        )
        self.assertTrue(correct["productIdentityVerified"])
        self.assertEqual("075", correct["selectedStoreIdFromProductContext"])
        self.assertTrue(correct["selectedStoreVerified"])
        other = self.r.inspect_initial_nuxt(
            html, self.obik, self.stores, selected_store="003"
        )
        self.assertFalse(other["selectedStoreVerified"])
        self.assertEqual([], other["verifiedProductOwnedRows"])

    def test_invalid_or_ambiguous_nuxt_product_owner_fails_closed(self):
        html = ('<script id="__NUXT_DATA__" type="application/json">'
                + json.dumps([{"skuId": 2}, {"skuId": 2}, "3496072"])
                + "</script>")
        result = self.r.inspect_initial_nuxt(html, self.obik, self.stores)
        self.assertFalse(result["productIdentityVerified"])
        self.assertEqual([], result["verifiedProductOwnedRows"])

    def test_conflicting_safe_query_product_id_is_rejected_without_leaking_value(self):
        raw = self.r.safe_url_request(
            "GET",
            "https://www.obi.pl/api/products/3496072/stores"
            "?productId=3496073&storeNumber=075",
            None, self.obik, self.stores,
        )
        self.assertTrue(raw["query"]["conflictingProductIdentifier"])
        self.assertNotIn("3496073", json.dumps(raw))
        record = self.observation(
            {"list": [{"storeNumber": "075", "stock": 4}]},
        )
        record.update(raw)
        self.assertEqual(
            "CONFLICT", self.r.product_identity(record, self.obik)
        )

    def test_qualitative_subset_requires_usable_state_not_null_only(self):
        unknown = self.observation({"list": [
            {"storeNumber": "075", "stock": None},
            {"storeNumber": "003", "stock": None},
        ]})
        self.assertEqual(
            "F_INCONCLUSIVE",
            self.r.classify_contract([unknown], self.stores, self.obik)["type"],
        )
        qualitative = self.observation({"list": [
            {"storeNumber": "075", "availability": "low"},
            {"storeNumber": "003", "pickupAvailable": False},
        ]})
        self.assertEqual(
            "B_ONE_SHOT_SUBSET",
            self.r.classify_contract([qualitative], self.stores, self.obik)["type"],
        )

    def test_safe_path_redacts_short_session_or_tracking_identifiers(self):
        url = (
            "https://www.obi.pl/api/session/abc12def34"
            "/availability/sp/3496072?storeNumber=075"
        )
        safe = self.r.safe_url_request(
            "GET", url, None, self.obik, self.stores
        )
        self.assertIsNotNone(safe)
        self.assertNotIn("abc12def34", json.dumps(safe))
        self.assertNotIn("/session/", safe["path"])
        self.assertIn("/availability/sp/3496072", safe["path"])
        real_sp = self.r.safe_url_request(
            "GET",
            "https://www.obi.pl/api/pdp/v1/availability/sp/3496072"
            "?postalCode=SECRET&quantity=1",
            None, self.obik, self.stores,
        )
        self.assertEqual(
            "/api/pdp/v1/availability/sp/3496072", real_sp["path"]
        )
        self.assertNotIn("SECRET", json.dumps(real_sp))

    def test_request_privacy_filters_sensitive_headers_tokens_urls_and_bodies(self):
        r = self.r
        url = ("https://www.obi.pl/api/products/3496072/stores?"
               "storeNumber=075&token=SECRET&trackingId=123&obik=3496072"
               "&email=person@example.com")
        observation = r.safe_url_request("POST", url,
            json.dumps({"obik": "3496072", "storeNumber": "075",
                        "token": "BAD_SECRET", "userEmail": "p@x.com",
                        "comment": "free form PRIVATE"}),
            self.obik, self.stores)
        data = json.dumps(observation)
        for forbidden in ("SECRET", "PRIVATE", "p@x.com", "person@example.com",
                          "cookie", "token", "userEmail"):
            self.assertNotIn(forbidden, data)
        self.assertEqual({"storeNumber": "075", "obik": "3496072"},
                         observation["query"]["safeValues"])
        self.assertEqual("3496072", observation["body"]["safeValues"]["obik"])
        self.assertFalse(r.trusted_url("https://evil-obi.pl/api/abc"))
        self.assertFalse(r.trusted_url("https://obi.pl.evil.com/api"))
        self.assertFalse(r.trusted_url("https://user:pass@www.obi.pl/api"))

    def test_control_metadata_sanitized_and_purchase_controls_excluded(self):
        r = self.r
        self.assertEqual("", r.safe_control_text("My account token=secret"))
        self.assertIn("REDACTED", r.safe_control_text(
            "Wybierz sklep kontakt hello@example.com"
        ))
        self.assertIsNotNone(r.UNSAFE_ACTION.search("Dodaj do koszyka"))
        self.assertIsNone(r.UNSAFE_ACTION.search("Sprawdź dostępność w sklepie"))


if __name__ == "__main__":
    unittest.main()
