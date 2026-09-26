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


if __name__ == "__main__":
    unittest.main()
