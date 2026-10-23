"""Общие признаки и проверки условий. Все деньги — в рублях."""

import json
import os
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parent
CATALOG_PATH = Path(os.getenv("CREDIT_PRODUCTS_PATH", ROOT.parent / "llm" / "products.json"))
CAT_FEATURES = ["product_id"]
NUM_FEATURES = [
    "business_age_months", "monthly_revenue_rub", "monthly_expenses_rub",
    "existing_monthly_payments_rub", "cash_balance_rub", "inflow_mean_3m_rub",
    "revenue_volatility", "revenue_change_6m", "late_payment_days_12m",
    "requested_amount_rub", "requested_term_months",
]
DERIVED_FEATURES = [
    "operating_margin", "cash_buffer_months", "amount_to_revenue",
    "planning_burden_to_revenue",
]
FEATURES = CAT_FEATURES + NUM_FEATURES + DERIVED_FEATURES


def load_catalog():
    return json.loads(CATALOG_PATH.read_text(encoding="utf-8"))


def product_map():
    return {p["id"]: p for p in load_catalog()["products"]}


def prepare_features(frame):
    """Одинаковые преобразования для обучения и получения предсказаний."""
    frame = frame.copy()
    rates = {key: p["annual_rate_percent"] / 100 for key, p in product_map().items()}
    revenue = frame["monthly_revenue_rub"].clip(lower=1)
    amount = frame["requested_amount_rub"]
    months = frame["requested_term_months"].clip(lower=1)
    rate = frame["product_id"].map(rates)

    # Это условная нагрузка для симулятора, НЕ график платежей банка.
    # Для линий считаем, что весь запрошенный лимит выбран сразу.
    planning_burden = amount / months + amount * rate / 12
    frame["operating_margin"] = (revenue - frame["monthly_expenses_rub"]) / revenue
    frame["cash_buffer_months"] = frame["cash_balance_rub"] / revenue
    frame["amount_to_revenue"] = amount / revenue
    frame["planning_burden_to_revenue"] = (
        frame["existing_monthly_payments_rub"] + planning_burden
    ) / revenue
    return frame[FEATURES]


def validate_application(application):
    """Ошибочные данные нельзя незаметно заменять придуманными значениями."""
    required = FEATURES[:len(CAT_FEATURES) + len(NUM_FEATURES)] + [
        "legal_form", "registered_in_russia", "current_overdue",
    ]
    missing = [name for name in required if name not in application]
    if missing:
        raise ValueError("Не хватает полей: " + ", ".join(missing))
    if application["product_id"] not in product_map():
        raise ValueError("Неизвестный product_id")
    for name in NUM_FEATURES:
        value = application[name]
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not np.isfinite(value):
            raise ValueError(f"Поле {name} должно быть конечным числом")
        if name != "revenue_change_6m" and value < 0:
            raise ValueError(f"Поле {name} не может быть отрицательным")
    for name in ["requested_term_months", "requested_amount_rub", "monthly_revenue_rub"]:
        if application[name] <= 0:
            raise ValueError(f"Поле {name} должно быть положительным")
    for name in ["registered_in_russia", "current_overdue"]:
        if not isinstance(application[name], bool):
            raise ValueError(f"Поле {name} должно быть true или false")
    for name in ["has_bank_account", "has_signed_44fz_contract", "purpose_confirmed"]:
        if name in application and not isinstance(application[name], bool):
            raise ValueError(f"Поле {name} должно быть true или false")
    for name in ["expected_inflow_days", "regular_inflow_months"]:
        if name in application:
            value = application[name]
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not np.isfinite(value) or value < 0:
                raise ValueError(f"Поле {name} должно быть неотрицательным числом")
    if application["revenue_change_6m"] < -1:
        raise ValueError("Падение выручки не может быть больше 100%")


def check_product_rules(application):
    """Правила каталога отделены от ML и не требуют обученной модели."""
    product = product_map()[application["product_id"]]
    reasons = []
    review = []
    if application["legal_form"] not in ["ИП", "ООО"]:
        reasons.append("Продукты доступны только ИП и ООО")
    if not application["registered_in_russia"]:
        reasons.append("Нужна регистрация бизнеса в России")
    if application["current_overdue"]:
        reasons.append("Есть текущая просроченная задолженность")
    if application["business_age_months"] < product["minimum_business_age_months"]:
        reasons.append("Срок работы бизнеса меньше требования продукта")

    limits = product.get("amount_rub", product.get("limit_rub"))
    if not limits["min"] <= application["requested_amount_rub"] <= limits["max"]:
        reasons.append("Сумма вне диапазона продукта")
    term = product.get("tranche_term_months", product.get("term_months"))
    if term:
        minimum = term["min"] if term["min"] is not None else 0
        if not minimum <= application["requested_term_months"] <= term["max"]:
            reasons.append("Срок возврата вне диапазона продукта или транша")

    if product["id"] == "overdraft":
        if application["requested_term_months"] != 1:
            reasons.append("В прототипе один месяц овердрафта означает максимум 30 дней")
        if application["requested_amount_rub"] > 0.3 * application["inflow_mean_3m_rub"]:
            reasons.append("Лимит овердрафта превышает 30% среднемесячных поступлений за 3 месяца")
        if "has_bank_account" not in application or "regular_inflow_months" not in application:
            review.append("Нужно подтвердить счёт в банке и регулярность поступлений за 3 месяца")
        elif not application["has_bank_account"] or application["regular_inflow_months"] < 3:
            reasons.append("Не выполнены требования к счёту или регулярным поступлениям")
        if "expected_inflow_days" not in application:
            review.append("Нужно подтвердить поступление для возврата долга за 30 дней")
        elif application["expected_inflow_days"] > 30:
            reasons.append("Ожидаемое поступление позже срока возврата овердрафта")

    if product["id"] == "government_contract_credit_line":
        if "has_signed_44fz_contract" not in application:
            review.append("Нужно подтвердить заключённый контракт по 44-ФЗ")
        elif not application["has_signed_44fz_contract"]:
            reasons.append("Нет заключённого контракта по 44-ФЗ")
        review.append("Нужна проверка лимита по стоимости контракта и авансу, целевых расходов и даты возврата")
    if product["id"] == "revolving_credit_line":
        review.append("Нужно подтвердить, что транш будет погашен до окончания линии")
    if not application.get("purpose_confirmed", False):
        review.append("Нужно подтвердить соответствие цели финансирования продукту")
    return {"violations": reasons, "review_reasons": review}


def json_write(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8")
