import unittest

import obi_live_contract_probe as probe


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

        trace = probe.reference_trace(root, 3)

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

        trace = probe.reference_trace(root, 1)

        self.assertEqual("reference", trace["kind"])
        self.assertEqual([1, 2], [item["index"] for item in trace["chain"]])
        self.assertEqual("wrapper", trace["chain"][0]["resolved"]["type"])
        self.assertEqual("object", trace["chain"][1]["resolved"]["type"])

    def test_keyword_hit_reports_resolved_stock_and_price_not_reference_indices(self):
        root = [
            {"articleData": 1},
            {"stock": 2, "pricing": 3},
            25,
            {"grossPrice": 4},
            12.99,
        ]

        hits = probe.keyword_hits(root)
        by_key = {hit["key"]: hit for hit in hits if hit["objectPath"] == "$[1]"}

        self.assertEqual(
            "ref top[2] -> 25",
            probe.format_reference_trace(by_key["stock"]["referenceTrace"]),
        )
        self.assertEqual(
            "ref top[3] -> object keys=['grossPrice']",
            probe.format_reference_trace(by_key["pricing"]["referenceTrace"]),
        )

        gross = next(hit for hit in hits if hit["objectPath"] == "$[3]" and hit["key"] == "grossPrice")
        self.assertEqual(
            "ref top[4] -> 12.99",
            probe.format_reference_trace(gross["referenceTrace"]),
        )

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


if __name__ == "__main__":
    unittest.main()
