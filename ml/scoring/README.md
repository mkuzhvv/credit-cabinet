# Скоринг для бэкенда

Формальная проверка ликвидности для пяти кредитных продуктов. Обученной модели и весов нет: расчёт находится в `engine.py`. Сервис работает без LLM и GPU.

## Запуск

Из папки `ml/scoring`, Python 3.12:

```bash
python3.12 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
python server.py --host 127.0.0.1 --port 8000
```

Для доступа с другого компьютера укажи `--host 0.0.0.0`. Сервис используется внутри сети бэкенда.

## Подключение Java

- `GET /health/live` → `{"status":"UP"}`.
- `GET /health/ready` → `status`, `modelVersion`, `featureSchemaVersion`, `supportedProductCodes`.
- `POST /internal/v1/assessments`, `Content-Type: application/json` → результат проверки. Бюджет вызова — 10 секунд.

Используй `featureSchemaVersion = "4"`, `riskCutoff = 0.5` и закрепи конкретный `expectedModelVersion` из readiness в настройках Java. Проверяй ID, схему и версию каждого ответа. Версия зависит от кода, каталога, параметров и схемы; после их изменения обнови закреплённую версию. Не принимай любую новую версию автоматически.

Полный пример входа — сохрани его в `request.json`:

```json
{
  "assessmentId": "8de449ef-d46c-4a0d-b108-e37d3b00bd94",
  "applicationId": "7be0c0bf-9019-47e0-a6b1-67548f1038f2",
  "featureSchemaVersion": "4",
  "features": {
    "productCode": "WORKING_CAPITAL",
    "currency": "RUB",
    "amount": "500000.00",
    "termMonths": 12,
    "averageMonthlyInflow": "1200000.00",
    "averageMonthlyOutflow": "800000.00",
    "reportingPeriod": {
      "startDate": "2026-04-01",
      "endDate": "2026-09-30",
      "months": 6
    },
    "asOfDate": "2026-10-22",
    "businessAgeMonths": 86,
    "creditHistory": {
      "status": "AVAILABLE",
      "asOfDate": "2026-10-22",
      "delinquencyPeriod": {
        "startDate": "2025-10-01",
        "endDate": "2026-09-30",
        "months": 12
      },
      "totalOutstandingDebt": "350000.00",
      "monthlyDebtPayment": "20000.00",
      "overdueDebtAmount": "0.00",
      "maxDaysPastDueLast12Months": 0
    },
    "projectBudget": null,
    "trancheTermMonths": null,
    "governmentContract": null,
    "cashBalance": "1000000.00"
  }
}
```

Отправка из терминала:

```bash
curl -H 'Content-Type: application/json' \
  --data-binary @request.json \
  http://127.0.0.1:8000/internal/v1/assessments
```

Ответ содержит ровно четыре поля:

```json
{
  "assessmentId": "8de449ef-d46c-4a0d-b108-e37d3b00bd94",
  "featureSchemaVersion": "4",
  "modelVersion": "rules-cashflow-v1-<fingerprint>",
  "riskScore": 0.0
}
```

`<fingerprint>` обозначает фактическую версию из readiness. **0.0 — правила пройдены, 1.0 — отказ по правилам. Это не вероятность дефолта.** Java одобряет при `riskScore < 0.5`, если выполнены остальные обязательные проверки.

Все деньги — строки RUB с двумя знаками после точки. `cashBalance` обязателен: свободные собственные деньги до нового кредита, без недоступных средств. Неизвестные данные нельзя заменять нулями. `averageMonthlyOutflow` уже включает существующие платежи по кредитам; повторно они не вычитаются.

Остальные продукты используют ту же структуру; точные типы и обязательность полей — в `schema.json`, ставки и диапазоны — в `catalog.json`:

| productCode | termMonths | reportingPeriod.months | Дополнительные поля |
|---|---|---|---|
| WORKING_CAPITAL | 3–12 | 6 | Продуктовые поля `null` |
| OVERDRAFT | 12 | 3 | Продуктовые поля `null`; сумма ≤30% поступлений |
| REVOLVING_LINE | 24 | 6 | `trancheTermMonths`: 3–6; остальные `null` |
| LONG_TERM | 24–60 | 12 | Положительный `projectBudget`; остальные `null` |
| GOVERNMENT_CONTRACT_LINE | 1–12 | 12 | `governmentContract`; остальные `null` |

`reportingPeriod` — полные календарные месяцы до месяца рассмотрения, закреплённые при создании черновика. `governmentContract` содержит `contractAmount`, `advanceAmount`, `executionEndDate`, `finalPaymentDate`, `plannedEndDate`. Сумма ≤70% × (стоимость − аванс); `plannedEndDate = asOfDate + termMonths`, не раньше завершения исполнения и не позже окончательной оплаты +30 дней. Исполнение не раньше `asOfDate`.

Для `creditHistory.status = "NO_HISTORY"` все четыре показателя отчёта равны `null`; остальные поля отчёта и период сохраняются. Это не автоматический отказ или одобрение. Период истории — 12 полных месяцев перед месяцем `creditHistory.asOfDate`; дата отчёта не позже `features.asOfDate`.

Ошибки возвращаются как `application/problem+json`: `400 MALFORMED_REQUEST` — некорректный JSON; `422 INVALID_FEATURES` — неверные поля/условия продукта; `503 MODEL_NOT_READY` — сервис не готов; `500 INFERENCE_FAILED` — ошибка расчёта. Технические ошибки Java обрабатывает как `TECHNICAL_HOLD`, а не отказ. Ответы содержат `X-Request-Id` и `Cache-Control: no-store`.

Java самостоятельно проверяет профиль компании, счёт, регулярность поступлений, источник кредитной истории и требования 44-ФЗ; хранит снимок данных и итоговое решение. Каталог Java должен совпадать с `catalog.json`. Фронтенд обращается к Java.

## Расчёт

```text
F = 0.90 × поступления − расходы
C₀ = свободный остаток
Cₜ = Cₜ₋₁ + F − 1.30 × новый платёжₜ
Одобрение: возраст бизнеса достаточен, текущей просрочки нет,
все обязательные условия выполнены и min(C₀, …, Cₕ) ≥ расходы.
```

Параметры находятся в `policy.json`. Платежи округляются вверх до копейки, сравнение остатков — без округления, равенство резерву допускается. Расчёт выполняется через Decimal.

Оборотный и длительный кредит — аннуитет; овердрафт — полная выборка и возврат с процентами за 30/365 каждый месяц срока лимита; возобновляемая линия — последовательные полные транши с равными долями основного долга и процентами на остаток; госконтракт — проценты ежемесячно и основной долг в конце срока. Для месячных сценариев ставка делится на 12. Срок лимита отличается от срока погашения долга/транша.

Это консервативная политика MVP, не проверенный прогноз невозврата. Сезонность, внутримесячные разрывы и дополнительная будущая выручка не прогнозируются.

## Файлы

`engine.py` — формула; `validation.py` — проверки входа; `server.py` — HTTP; `catalog.json` — продукты; `policy.json` — коэффициенты; `schema.json` — контракт; `requirements.txt` — зависимости; `README.md` — эта инструкция. Настройки читаются один раз при запуске. Альтернативные файлы задаются через `--policy`/`--catalog` или `SCORING_POLICY_PATH`/`SCORING_CATALOG_PATH`.
