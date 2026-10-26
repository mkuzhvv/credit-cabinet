# Контракты API

## 1. Артефакты и версии

| Материал | Назначение |
|---|---|
| [backend.openapi.yaml](backend.openapi.yaml) | Backend API 0.5.0, базовый путь `/api/v1` |
| [scoring.openapi.yaml](scoring.openapi.yaml) | Scoring API 0.4.0, признаки версии `4` |
| [LLM-контракт](llm/README.md) | AssistantContext v2 и AssistantReply v1 |
| [input.schema.json](llm/input.schema.json) / [output.schema.json](llm/output.schema.json) | JSON Schema 2020-12 входа и ответа модели |
| [acceptance-cases.json](llm/acceptance-cases.json) | Смысловые ожидания для помощника |

Спецификации в Git определяют согласованные форматы. При запущенном Java Swagger доступен по `/swagger-ui/index.html`, runtime OpenAPI — по `/v3/api-docs`. Запуск и внутренние адреса описаны в [Docker](../../docker/README.md).

## 2. Общие форматы

- UUID — строка идентификатора, в том числе Idempotency-Key.
- Деньги — строки RUB с двумя знаками после точки, диапазон `0.00..999999999999.99`; для полей суммы финансирования требуется положительное значение.
- Время — UTC с `Z`; календарные даты — `YYYY-MM-DD`.
- Версии и sequence — целые в безопасном диапазоне JavaScript, до `9007199254740991`.
- Неизвестные данные — null, известный ноль — `"0.00"`.
- JSON разбирается строго; некорректный тип не исправляется неявно.

Ошибка имеет тип `application/problem+json`: type, title, status, instance, code, requestId и при необходимости errors с field/code/message. `X-Request-Id` связывает ответ с логами, но не управляет повтором бизнес-команды. API использует `Cache-Control: no-store`.

## 3. Сессия и CSRF

`GET /csrf → POST /demo/session → GET /csrf → GET /me`.

Клиент сохраняет cookie JSESSIONID и передаёт `X-CSRF-TOKEN` для изменяющих запросов. После входа сессия и CSRF обновляются. Отсутствие сессии даёт 401, неверный CSRF — 403. Чужие заявки и диалоги скрываются через 404.

Демовход доступен в профиле demo и не требует Idempotency-Key. Команды создания/отправки/повтора заявки и создания/отправки сообщения диалога требуют UUID-ключ.

## 4. Маршруты Java

Все пути таблицы дополняют `/api/v1`.

| Метод | Путь | Результат |
|---|---|---|
| GET | `/csrf` | Токен и имя CSRF-заголовка |
| POST | `/demo/session` | Демовход |
| GET | `/me` | Текущий пользователь и компания |
| GET | `/products` | Действующий каталог |
| POST | `/applications` | Создание DRAFT, 201 |
| GET | `/applications` | Список компании: page, size, status |
| GET | `/applications/{id}` | Карточка заявки |
| PUT | `/applications/{id}/form` | Полная замена формы |
| POST | `/applications/{id}/submit` | Принятие в CHECKING, 202 |
| POST | `/applications/{id}/retry` | Технический повтор, 202 |
| POST | `/assistant/conversations` | Создание диалога без тела, 201 |
| GET | `/assistant/conversations/{id}/messages` | История после sequence |
| POST | `/assistant/conversations/{id}/messages` | Вопрос и успешная пара сообщений, 200 |

Список заявок по умолчанию page=0, size=20, максимум size=100, порядок updatedAt DESC / id DESC. История чата: afterSequence=0, limit=50, максимум 100; следующие страницы читаются по последнему sequence.

## 5. Заявка и версии

Создание принимает productCode. PUT принимает expectedVersion и полную форму из девяти ключей; пример — в [документации интерфейса](../FRONTEND.md).

Submit принимает expectedVersion, expectedProductTermsVersion и clarificationResponses, если требуется. Версия условий находится в `product.terms.version`. Retry принимает expectedVersion и повторяет сохранённую попытку.

Карточка содержит version, status, product, form, calculationDate, reportingPeriod, assessmentId, decision, requiredInformation, processingError, allowedActions и даты.

Статусы: DRAFT, CHECKING, NEEDS_INFO, TECHNICAL_HOLD, APPROVED, DECLINED. Ошибки JSON — 400, валидации — 422, конфликты версии/состояния/ключа — 409. Принятие submit не означает одобрение: итог читается отдельным GET.

Для уточнения нулевых поступлений передаются clarificationId и response: CONFIRM_VALUE либо VALUE_CORRECTED. Исправление сначала сохраняется PUT. Полные ограничения — в [кредитной политике](../CREDIT_POLICY_AND_ML_REQUIREMENTS.md).

## 6. Безопасные повторы

Ключ действует в области пользователя, компании, операции и ресурса. Тот же ключ с тем же DTO после успеха возвращает сохранённый ответ. Изменённое тело под тем же ключом и активная конкурирующая команда дают 409.

Доступ проверяется раньше повтора. Успешный повтор возвращается раньше проверок текущей версии и состояния. В хеш не входят cookie, CSRF и requestId. В сохранённом ответе нет cookie; успешный результат хранится не менее 24 часов.

Новый пользовательский шаг требует нового ключа. Сетевой повтор того же неопределённого результата сохраняет прежний ключ.

## 7. Скоринг и помощник

Java проверяет readiness Python, затем вызывает `POST /internal/v1/assessments`. Схема 4, ожидаемая версия `rules-cashflow-v1-59fdb57552ca`, результат 0/1. Общий бюджет readiness + расчёта — 10 секунд. Технический сбой даёт TECHNICAL_HOLD.

Сообщение чата содержит text и context с тремя обязательными nullable-ключами: applicationId, productCode, fieldCode. Модельный JSON возвращает answer и recommendedProductCode; HTTP API — conversationId, userMessage и assistantMessage. Сохранение пары происходит только после валидного результата.

Бюджет чата — 60 секунд. Ошибки: 503 ASSISTANT_UNAVAILABLE, 502 ASSISTANT_INVALID_RESPONSE, 504 ASSISTANT_TIMEOUT. Streaming и tool calls не используются. Подробности — в [LLM-контракте](llm/README.md).
