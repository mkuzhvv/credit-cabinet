"""Генерация полностью вымышленных компаний, заявок и исходов."""

import argparse
import hashlib

import numpy as np
import pandas as pd

from common import ROOT, CATALOG_PATH, json_write, prepare_features, product_map

SEED = 42


def generate(companies, seed, stress=False):
    rng = np.random.default_rng(seed)
    products = product_map()
    rows = []
    audit = []
    for company in range(companies):
        company_id = f"{'STRESS' if stress else 'SYN'}-{company:06d}"
        # Одна компания может иметь несколько заявок, но только один split.
        split = rng.choice(["train", "selection", "calibration", "test"], p=[0.6, 0.15, 0.10, 0.15])
        revenue = float(np.clip(rng.lognormal(np.log(1600000), 0.9), 150000, 20000000))
        expenses_share = float(rng.uniform(0.48, 0.94))
        if stress:
            expenses_share += 0.07
        age = int(rng.integers(2, 181))
        cash_ratio = float(rng.uniform(0.02, 1.4)) * (0.55 if stress else 1)
        volatility = float(rng.uniform(0.03, 0.5))
        trend = float(np.clip(rng.normal(-0.15 if stress else 0.02, 0.18), -0.75, 0.65))
        late_days = int(rng.choice([0, 5, 15, 30, 60], p=[0.70, 0.12, 0.09, 0.06, 0.03]))
        # Скрытый фактор есть только у симулятора. В признаки он не попадёт.
        hidden_risk = float(rng.normal(0, 0.65)) + (0.45 if stress else 0)

        for attempt in range(1 if stress else 3):
            product_id = str(rng.choice(list(products)))
            product = products[product_id]
            limits = product.get("amount_rub", product.get("limit_rub"))
            term = product.get("tranche_term_months", product.get("term_months"))
            months = 1 if product_id == "overdraft" else int(rng.integers(term["min"] or 1, term["max"] + 1))
            amount = float(np.clip(revenue * rng.lognormal(-0.25, 0.9), limits["min"], limits["max"]))
            # Часть заявок намеренно не проходит продуктовые ограничения.
            if rng.random() < 0.08:
                amount = limits["max"] * 1.2
            row = {
                "application_id": f"{company_id}-{attempt}", "company_id": company_id,
                "split": "stress" if stress else str(split), "product_id": product_id,
                "business_age_months": age,
                "monthly_revenue_rub": round(revenue, 2),
                "monthly_expenses_rub": round(revenue * expenses_share, 2),
                "existing_monthly_payments_rub": round(revenue * rng.uniform(0, 0.22), 2),
                "cash_balance_rub": round(revenue * cash_ratio, 2),
                "inflow_mean_3m_rub": round(revenue * rng.uniform(0.8, 1.2), 2),
                "revenue_volatility": round(volatility, 4),
                "revenue_change_6m": round(trend, 4),
                "late_payment_days_12m": late_days,
                "requested_amount_rub": round(amount, 2), "requested_term_months": months,
                "legal_form": str(rng.choice(["ИП", "ООО"])), "registered_in_russia": True,
                "current_overdue": bool(rng.random() < 0.04),
                "has_bank_account": bool(rng.random() < 0.95),
                "regular_inflow_months": int(rng.integers(1, 13)),
                "expected_inflow_days": int(rng.integers(3, 46)),
                "has_signed_44fz_contract": bool(rng.random() < 0.85),
                "purpose_confirmed": True,
            }
            rows.append(row)
            audit.append({"application_id": row["application_id"], "hidden_risk": hidden_risk})

    frame = pd.DataFrame(rows)
    features = prepare_features(frame)
    # Придуманные коэффициенты, а не банковская модель. Источник меток открыт.
    z = (
        -3.1
        + 5.0 * features["planning_burden_to_revenue"].clip(0, 2)
        + 3.0 * (0.2 - features["operating_margin"])
        + 1.2 * features["revenue_volatility"]
        - 1.0 * features["revenue_change_6m"]
        + 0.018 * features["late_payment_days_12m"]
        - 0.65 * features["cash_buffer_months"].clip(0, 2)
        + 0.5 * (features["business_age_months"] < 12)
        + np.array([row["hidden_risk"] for row in audit])
    )
    annual_probability = 1 / (1 + np.exp(-z.clip(-12, 12)))
    horizon = frame["requested_term_months"].clip(upper=12)
    probability = 1 - (1 - annual_probability) ** (horizon / 12)
    # Исход случаен: одинаковые наблюдаемые признаки не гарантируют одну метку.
    frame["simulated_default"] = rng.binomial(1, probability)
    audit = pd.DataFrame(audit)
    audit["simulator_probability"] = probability
    audit["observation_months"] = horizon
    return frame, audit


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--companies", type=int, default=12000)
    args = parser.parse_args()
    if args.companies < 1000:
        parser.error("Для устойчивых выборок используйте хотя бы 1000 компаний")
    data = ROOT / "data"
    data.mkdir(exist_ok=True)
    frame, audit = generate(args.companies, SEED)
    stress, stress_audit = generate(max(1000, args.companies // 3), SEED + 1, stress=True)
    assert frame.groupby("company_id")["split"].nunique().max() == 1
    frame.to_csv(data / "applications.csv", index=False)
    stress.to_csv(data / "stress.csv", index=False)
    pd.concat([audit, stress_audit]).to_csv(data / "simulator_audit.csv", index=False)
    json_write(data / "manifest.json", {
        "origin": "fully_synthetic", "seed": SEED, "rows": len(frame), "stress_rows": len(stress),
        "companies": args.companies,
        "target": "simulated_default",
        "target_definition": "Случайное событие невозврата в течение min(срок займа или транша, 12) месяцев; у овердрафта 1 месяц означает 30 дней.",
        "catalog_sha256": hashlib.sha256(CATALOG_PATH.read_bytes()).hexdigest(),
        "warning": "Все распределения и коэффициенты придуманы. Результаты на этих данных не подтверждают пригодность для реального кредитного скоринга.",
        "split_sizes": frame["split"].value_counts().to_dict(),
        "stress_change": "Меньше ликвидности, выше расходы, хуже динамика выручки, скрытый сдвиг риска +0.45. Генератор той же семьи, не независимая реальная проверка.",
    })
    print(f"Создано {len(frame)} заявок и {len(stress)} стресс-примеров")


if __name__ == "__main__":
    main()
