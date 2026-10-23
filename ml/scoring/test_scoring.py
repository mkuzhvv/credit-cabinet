"""Проверки контракта и границ прототипа. Запуск: python -m unittest -v."""

import json
import unittest

import pandas as pd

from common import FEATURES, ROOT, validate_application
from generate import generate
from score import score


def healthy():
    return json.loads((ROOT / "examples/healthy.json").read_text())


class ScoringTests(unittest.TestCase):
    def test_no_target_or_oracle_in_features(self):
        self.assertFalse(set(FEATURES) & {"simulated_default", "hidden_risk", "simulator_probability", "split", "company_id"})

    def test_reproducible_and_grouped_generation(self):
        left, left_audit = generate(100, 999)
        right, right_audit = generate(100, 999)
        pd.testing.assert_frame_equal(left, right)
        pd.testing.assert_frame_equal(left_audit, right_audit)
        self.assertEqual(left.groupby("company_id")["split"].nunique().max(), 1)

    def test_incomplete_or_nonfinite_input_is_not_filled_in(self):
        for name, value in [("monthly_revenue_rub", float("nan")), ("purpose_confirmed", "yes")]:
            application = healthy()
            application[name] = value
            with self.assertRaises(ValueError):
                validate_application(application)
        with self.assertRaises(ValueError):
            validate_application({"product_id": "long_term_loan"})

    def test_hard_rule_beats_risk_model(self):
        application = healthy()
        application["business_age_months"] = 2
        result = score(application)
        self.assertEqual(result["policy_decision"], "decline")
        self.assertIsNone(result["synthetic_default_probability"])

    def test_overdraft_has_percentage_cap(self):
        application = healthy()
        application.update(product_id="overdraft", requested_term_months=1,
                           requested_amount_rub=900000, inflow_mean_3m_rub=1000000,
                           has_bank_account=True, regular_inflow_months=3, expected_inflow_days=7)
        result = score(application)
        self.assertEqual(result["policy_decision"], "decline")
        self.assertTrue(any("30%" in reason for reason in result["reasons"]))

    def test_unknown_contract_formula_blocks_automatic_approval(self):
        application = healthy()
        application.update(product_id="government_contract_credit_line", requested_term_months=12,
                           has_signed_44fz_contract=True)
        self.assertNotEqual(score(application)["policy_decision"], "approve")

    def test_persisted_model_and_different_profiles(self):
        safe = score(healthy())
        risky = score(json.loads((ROOT / "examples/risky.json").read_text()))
        self.assertFalse(safe["validated_for_real_lending"])
        self.assertEqual(safe, score(healthy()))
        self.assertLess(safe["synthetic_default_probability"], risky["synthetic_default_probability"])


if __name__ == "__main__":
    unittest.main()
