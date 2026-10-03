import json
import unittest

import kwant_live_contract_probe as probe


class KwantNumericRouteLiveProof(unittest.TestCase):
    def test_live_numeric_route_580(self):
        result = probe.probe_numeric_product_route_http("580")
        print("KWANT_NUMERIC_ROUTE_PROOF=" + json.dumps(result, ensure_ascii=False))
        self.assertEqual("/produkt/580", result["requestedPath"])
        self.assertEqual("580", result["finalParsedProductId"])
        self.assertTrue(result["matchesRequestedProductId"])


if __name__ == "__main__":
    unittest.main()
