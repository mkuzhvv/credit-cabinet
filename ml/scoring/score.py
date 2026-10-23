"""Предсказание на одной заявке и отдельное решение по правилам прототипа."""

import argparse
import hashlib
import json

import joblib
import pandas as pd
from catboost import CatBoostClassifier

from common import ROOT, CATALOG_PATH, check_product_rules, prepare_features, validate_application


def score(application):
    validate_application(application)
    rules = check_product_rules(application)
    result = {
        "data_origin": "fully_synthetic", "validated_for_real_lending": False,
        "product_id": application["product_id"],
        "policy_decision": None, "synthetic_default_probability": None,
        "reasons": rules["violations"] + rules["review_reasons"],
    }
    # Нарушение известного продуктового требования не нужно предсказывать ML-моделью.
    if rules["violations"]:
        result["policy_decision"] = "decline"
        return result

    artifacts = ROOT / "artifacts"
    metadata = json.loads((artifacts / "model_metadata.json").read_text())
    if metadata["catalog_sha256"] != hashlib.sha256(CATALOG_PATH.read_bytes()).hexdigest():
        raise ValueError("Каталог изменился: нужно пересоздать данные и переобучить модель")
    model = CatBoostClassifier()
    model.load_model(str(artifacts / "risk_model.cbm"))
    calibrator = joblib.load(artifacts / "calibrator.joblib")
    features = prepare_features(pd.DataFrame([application]))
    raw = model.predict(features, prediction_type="RawFormulaVal").reshape(-1, 1)
    probability = float(calibrator.predict_proba(raw)[0, 1])
    result["synthetic_default_probability"] = round(probability, 6)
    result["observation_months"] = min(application["requested_term_months"], 12)

    policy = metadata["policy"]
    if probability >= policy["decline_min_risk"]:
        result["policy_decision"] = "decline"
        result["reasons"].append("Оценка риска выше условного порога прототипа")
    elif rules["review_reasons"] or probability > policy["approve_max_risk"]:
        result["policy_decision"] = "review"
        if probability > policy["approve_max_risk"]:
            result["reasons"].append("Оценка риска требует дополнительного рассмотрения по правилам прототипа")
    else:
        result["policy_decision"] = "approve"
        result["reasons"].append("Известные ограничения выполнены, риск ниже условного порога прототипа")
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("application", help="Путь к JSON одной заявки")
    args = parser.parse_args()
    application = json.loads(open(args.application, encoding="utf-8").read())
    print(json.dumps(score(application), ensure_ascii=False, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
