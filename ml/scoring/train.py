"""Обучение и честная по отношению к симулятору проверка моделей."""

import hashlib
import json
import platform
from importlib.metadata import version

import joblib
import numpy as np
import pandas as pd
from catboost import CatBoostClassifier
from sklearn.compose import ColumnTransformer
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score, brier_score_loss, log_loss, roc_auc_score
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler

from common import ROOT, CATALOG_PATH, CAT_FEATURES, FEATURES, json_write, prepare_features


def metrics(y, probability):
    return {
        "rows": len(y), "default_share": float(np.mean(y)),
        "roc_auc": float(roc_auc_score(y, probability)),
        "average_precision": float(average_precision_score(y, probability)),
        "brier": float(brier_score_loss(y, probability)),
        "log_loss": float(log_loss(y, probability)),
    }


def main():
    data = pd.read_csv(ROOT / "data/applications.csv")
    stress = pd.read_csv(ROOT / "data/stress.csv")
    manifest = json.loads((ROOT / "data/manifest.json").read_text())
    catalog_hash = hashlib.sha256(CATALOG_PATH.read_bytes()).hexdigest()
    if manifest["catalog_sha256"] != catalog_hash:
        raise ValueError("Каталог изменился после генерации: заново запустите generate.py")

    # Проверяем, что заявки одной компании не разошлись по разным выборкам.
    groups = {}
    for split in ["train", "selection", "calibration", "test"]:
        groups[split] = set(data.loc[data["split"] == split, "company_id"])
    for left in groups:
        for right in groups:
            if left != right:
                assert groups[left].isdisjoint(groups[right])
    assert set(stress["company_id"]).isdisjoint(set(data["company_id"]))

    frames = {key: data[data["split"] == key].copy() for key in groups}
    x = {key: prepare_features(frame) for key, frame in frames.items()}
    y = {key: frame["simulated_default"] for key, frame in frames.items()}
    numeric = [name for name in FEATURES if name not in CAT_FEATURES]
    assert not set(FEATURES) & {"simulated_default", "simulator_probability", "hidden_risk", "split", "company_id"}

    baseline = make_pipeline(
        ColumnTransformer([
            ("numeric", make_pipeline(SimpleImputer(), StandardScaler()), numeric),
            ("category", OneHotEncoder(handle_unknown="ignore"), CAT_FEATURES),
        ]),
        LogisticRegression(max_iter=1500, random_state=42),
    )
    baseline.fit(x["train"], y["train"])

    model = CatBoostClassifier(
        iterations=700, depth=5, learning_rate=0.05,
        loss_function="Logloss", eval_metric="Logloss", random_seed=42,
        thread_count=4, allow_writing_files=False, verbose=100,
    )
    # selection используется только для остановки обучения, test не участвует.
    model.fit(x["train"], y["train"], cat_features=CAT_FEATURES,
              eval_set=(x["selection"], y["selection"]), early_stopping_rounds=70)

    # Отдельные компании для калибровки: исправляем масштаб вероятностей.
    raw = model.predict(x["calibration"], prediction_type="RawFormulaVal").reshape(-1, 1)
    calibrator = LogisticRegression(C=1000, random_state=42)
    calibrator.fit(raw, y["calibration"])

    artifacts = ROOT / "artifacts"
    artifacts.mkdir(exist_ok=True)
    model.save_model(str(artifacts / "risk_model.cbm"))
    joblib.dump(calibrator, artifacts / "calibrator.joblib")

    report = {
        "evaluation_scope": "Только синтетический симулятор. Это не качество на реальных заёмщиках.",
        "split": "Непересекающиеся компании: train / early stopping / calibration / test.",
        "metrics_definition": "average_precision — sklearn Average Precision, не трапецеидальная площадь под PR-кривой.",
        "best_iteration": int(model.best_iteration_),
        "evaluations": {},
    }
    for split, frame in [("test", frames["test"]), ("stress", stress)]:
        values = prepare_features(frame)
        target = frame["simulated_default"]
        raw = model.predict(values, prediction_type="RawFormulaVal").reshape(-1, 1)
        calibrated = calibrator.predict_proba(raw)[:, 1]
        report["evaluations"][split] = {
            "constant_baseline": metrics(target, np.full(len(frame), y["train"].mean())),
            "logistic_regression": metrics(target, baseline.predict_proba(values)[:, 1]),
            "catboost_uncalibrated": metrics(target, model.predict_proba(values)[:, 1]),
            "catboost_calibrated": metrics(target, calibrated),
            "by_product": {},
        }
        for product_id in frame["product_id"].unique():
            mask = (frame["product_id"] == product_id).to_numpy()
            report["evaluations"][split]["by_product"][product_id] = metrics(target[mask], calibrated[mask])
        predictions = frame[["application_id", "company_id", "product_id", "simulated_default"]].copy()
        predictions["synthetic_default_probability"] = calibrated
        predictions.to_csv(artifacts / f"{split}_predictions.csv", index=False)

    report["feature_importance"] = dict(sorted(zip(FEATURES, model.feature_importances_.tolist()), key=lambda item: -item[1]))
    json_write(artifacts / "metrics.json", report)
    json_write(artifacts / "model_metadata.json", {
        "model": "CatBoostClassifier + sigmoid calibration", "data_origin": "fully_synthetic",
        "seed": 42, "features": FEATURES, "catalog_sha256": catalog_hash,
        "python": platform.python_version(),
        "packages": {name: version(name) for name in ["catboost", "scikit-learn", "pandas", "numpy", "joblib"]},
        "target_definition": manifest["target_definition"],
        "policy": {"approve_max_risk": 0.15, "decline_min_risk": 0.45,
                   "origin": "Условные пороги автора для прототипа; не банковские и не оптимизированы по реальным убыткам."},
    })
    print(json.dumps(report["evaluations"]["test"]["catboost_calibrated"], indent=2))


if __name__ == "__main__":
    main()
