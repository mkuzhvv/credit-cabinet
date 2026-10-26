# Путь запроса по Java-коду

Документ объясняет последовательность вызовов в текущей реализации. Обзор компонентов — в [архитектуре](BACKEND_ARCHITECTURE.md), HTTP-форматы — в [контрактах](contracts/README.md), запуск — в [Docker](../docker/README.md).

## 1. Запуск Java

[CreditCabinetApplication](../backend/src/main/java/com/creditcabinet/CreditCabinetApplication.java) запускает Spring Boot. Spring создаёт контроллеры, сервисы, репозитории, HTTP-клиенты, пулы выполнения и планировщик.

Datasource подключается к PostgreSQL. Flyway применяет недостающие миграции; Hibernate сверяет таблицы с сущностями. В Compose Java запускается после healthcheck БД, Python и подготовленной Ollama. Указанные сервисы уже существуют как отдельные процессы; Java не запускает их сама.

## 2. Общий путь HTTP

`Nginx → servlet filters → Spring Security → controller → service → repository / integration`.

[RequestIdFilter](../backend/src/main/java/com/creditcabinet/config/RequestIdFilter.java) назначает UUID запроса, добавляет `X-Request-Id` в ответ и помещает его в MDC логов. Он же задаёт `no-store` для API и записывает метод, путь и HTTP-статус.

[SecurityConfig](../backend/src/main/java/com/creditcabinet/config/SecurityConfig.java) проверяет сессию и CSRF до контроллера. Поэтому ошибки Security формируются [SecurityErrorHandler](../backend/src/main/java/com/creditcabinet/config/SecurityErrorHandler.java), а исключения контроллеров и сервисов — глобальным [ApiExceptionHandler](../backend/src/main/java/com/creditcabinet/common/error/ApiExceptionHandler.java). Общий [ApiProblemFactory](../backend/src/main/java/com/creditcabinet/common/error/ApiProblemFactory.java) собирает одинаковый ProblemDetail для обоих путей.

[JsonConfig](../backend/src/main/java/com/creditcabinet/config/JsonConfig.java) запрещает неизвестные поля, несколько JSON-документов подряд и неявное превращение неподходящих типов. Аннотация [MoneyJson](../backend/src/main/java/com/creditcabinet/common/json/MoneyJson.java) подключает чтение и запись денежных строк через BigDecimal. Например, API принимает `"42.00"`, а не `42.0` или `"42,00"`.

## 3. Демосессия и доступ

[IdentityService](../backend/src/main/java/com/creditcabinet/identity/service/IdentityService.java) создаёт аутентификацию для подготовленного пользователя. При первом входе идентификатор сессии меняется; старый CSRF-токен после входа больше не используется.

Последовательность клиента:

1. `GET /api/v1/csrf`.
2. `POST /api/v1/demo/session` с cookie и полученным CSRF.
3. Повторный `GET /api/v1/csrf`.
4. `GET /api/v1/me`.

[CurrentUserProvider](../backend/src/main/java/com/creditcabinet/identity/service/CurrentUserProvider.java) предоставляет сервисам серверные userId/companyId. Они не берутся из тела пользовательской команды. Репозитории и сервисы проверяют принадлежность ресурса перед чтением или изменением.

## 4. Каталог и черновик

[CatalogService](../backend/src/main/java/com/creditcabinet/catalog/service/CatalogService.java) читает активные продукты, собирает ProductTerms и возвращает каталог интерфейсу. Источник начальных условий — миграция V3.

[ApplicationController](../backend/src/main/java/com/creditcabinet/credit/api/ApplicationController.java) передаёт команды в [ApplicationService](../backend/src/main/java/com/creditcabinet/credit/service/ApplicationService.java).

При создании сервис проверяет активный продукт и идемпотентность, создаёт `DRAFT`, закрепляет продукт, UTC-дату расчёта и финансовый период. Сохранение заявки, события создания и успешного ответа команды происходит атомарно.

Сохранение формы — `PUT /{id}/form` с `expectedVersion` и всеми девятью полями формы. Это полная замена, а не PATCH. Частичная анкета допустима в черновике; неприменимые продуктовые поля должны быть null.

Версия сущности защищает от двух вкладок. Если прочитанная версия устарела, сервис возвращает `409 VERSION_CONFLICT`; запрос не перетирает более свежие данные. Продукт существующей заявки не меняется.

В `DRAFT` и `NEEDS_INFO` показываются текущие условия каталога. Для отправленной заявки используются закреплённые условия её попытки. `allowedActions` вычисляются сервером из состояния и доступа.

## 5. Что происходит до ответа 202

[SubmitApplicationService](../backend/src/main/java/com/creditcabinet/credit/service/SubmitApplicationService.java) принимает отправку сохранённой формы.

Порядок: доступ → поиск успешного идемпотентного повтора → состояние и версии → [SubmissionValidator](../backend/src/main/java/com/creditcabinet/credit/decision/SubmissionValidator.java) → ответы на уточнения → создание попытки.

Валидатор проверяет полноту, суммы, сроки, обязательные поля продукта, финансовый период, предел овердрафта и госконтракта, соотношения дат. Такие ошибки возвращают `422` с полями и не создают новую попытку.

[AssessmentAcceptanceService](../backend/src/main/java/com/creditcabinet/credit/service/AssessmentAcceptanceService.java) записывает одной транзакцией:

- попытку `READY` и её номер;
- неизменяемый input_snapshot версии 2: форма, компания, расчётные даты, условия, подтверждения;
- параметры политики, порог и ожидаемую версию скоринга;
- статус заявки `CHECKING`, текущую попытку и новую версию;
- событие и сохранённый HTTP-ответ `202`.

Вызов Python и получение кредитной истории здесь не выполняются. `202` подтверждает запись команды; решение появится после фоновой обработки.

## 6. Захват фоновой попытки

[AssessmentWorker](../backend/src/main/java/com/creditcabinet/credit/processing/AssessmentWorker.java) запускает цикл с паузой 1 секунду. Он сначала проверяет просроченное выполнение, затем пытается получить доступную работу.

[AssessmentClaimService](../backend/src/main/java/com/creditcabinet/credit/processing/AssessmentClaimService.java) вызывает [AssessmentClaimRepository](../backend/src/main/java/com/creditcabinet/credit/persistence/AssessmentClaimRepository.java) в короткой `REQUIRES_NEW`-транзакции. SQL использует блокировку строк и `SKIP LOCKED`, чтобы несколько исполнителей не брали одну попытку одновременно.

Результат — [AssessmentClaim](../backend/src/main/java/com/creditcabinet/credit/processing/AssessmentClaim.java): assessmentId, applicationId, token, lockedUntil, executionCount. Это право текущего запуска обрабатывать попытку до указанного времени. Оно не является решением о кредите.

После commit блокировка БД освобождена. Логическое владение сохраняется через токен и срок 30 секунд.

## 7. Рассмотрение вне транзакции

[ProcessApplicationService](../backend/src/main/java/com/creditcabinet/credit/service/ProcessApplicationService.java) имеет `Propagation.NEVER`: длительный сценарий не должен выполняться внутри транзакции.

Он загружает закреплённые данные и выполняет порядок:

1. [CreditDecisionPolicy](../backend/src/main/java/com/creditcabinet/credit/decision/CreditDecisionPolicy.java) проверяет возраст бизнеса и требования продукта.
2. [CreditHistoryService](../backend/src/main/java/com/creditcabinet/credit/credithistory/CreditHistoryService.java) получает отчёт, если он ещё не сохранён. Бюджет — 5 секунд.
3. [AssessmentStateService](../backend/src/main/java/com/creditcabinet/credit/service/AssessmentStateService.java) в отдельной короткой транзакции сохраняет валидный отчёт.
4. Текущая просрочка даёт отказ до скоринга и до уточнения нуля.
5. Нулевые поступления без подтверждения дают `NEEDS_INFO` с `ZERO_INFLOW_CONFIRMATION`.
6. [ScoringRequestFactory](../backend/src/main/java/com/creditcabinet/credit/scoring/ScoringRequestFactory.java) собирает признаки схемы 4.
7. [HttpRiskScoringClient](../backend/src/main/java/com/creditcabinet/credit/scoring/HttpRiskScoringClient.java) проверяет readiness Python и вызывает расчёт с общим бюджетом 10 секунд.
8. Java проверяет ID, схему, версию, бинарный результат и применяет порог 0,50.
9. AssessmentStateService атомарно записывает результат, причины, одобренные условия либо уточнение, статус/версию заявки и событие.

Финальная запись проверяет `RUNNING`, токен, срок по времени PostgreSQL и актуальность попытки заявки. При потере захвата изменения откатываются. Старый исполнитель не может завершить уже заменённую работу.

## 8. Уточнение, технический повтор и восстановление

[ClarificationService](../backend/src/main/java/com/creditcabinet/credit/service/ClarificationService.java) связывает ответ с конкретным актуальным вопросом. Для `CONFIRM_VALUE` сохраняется ноль. Для `VALUE_CORRECTED` сначала требуется сохранить положительное значение формы. Следующий submit создаёт новую попытку и фиксирует ответ на старое уточнение.

[RetryApplicationService](../backend/src/main/java/com/creditcabinet/credit/service/RetryApplicationService.java) работает для `TECHNICAL_HOLD`: создаёт новую попытку с прежними данными и параметрами, копирует полученный кредитный отчёт при наличии. Это повтор той же проверки, а не новая анкета.

[AssessmentRecoveryService](../backend/src/main/java/com/creditcabinet/credit/processing/AssessmentRecoveryService.java) обнаруживает истёкший захват после аварии Java. До предела трёх запусков возвращает ту же попытку в `READY` с задержкой; после исчерпания фиксирует `TECHNICAL_HOLD / PROCESSING_INTERRUPTED`.

Полученный технический ответ источника или Python обычно сразу завершает попытку технической остановкой. Автовосстановление просроченного захвата и пользовательский `retry` — разные механизмы.

## 9. Общая идемпотентность

[IdempotencyService](../backend/src/main/java/com/creditcabinet/common/idempotency/IdempotencyService.java) используется созданием заявки, submit/retry и командами помощника.

Область ключа: userId, companyId, operation, resourceScope и UUID из `Idempotency-Key`. [RequestHasher](../backend/src/main/java/com/creditcabinet/common/idempotency/RequestHasher.java) вычисляет SHA-256 канонического DTO, чтобы отличать повтор исходной команды от попытки использовать тот же ключ с другим содержимым. Это сравнение запроса, а не шифрование данных.

`begin` возвращает один из двух вариантов [IdempotencyResult](../backend/src/main/java/com/creditcabinet/common/idempotency/IdempotencyResult.java):

- `Claim` — команда может выполняться под выданным токеном.
- `Replay` — уже есть успешный ответ, который нужно вернуть без повторного эффекта.

Активная параллельная команда и смена тела под тем же ключом дают `409`. `complete` требует уже существующую бизнес-транзакцию и сохраняет результат вместе с изменением сущности. [StoredResponse](../backend/src/main/java/com/creditcabinet/common/idempotency/StoredResponse.java) хранит 2xx-статус, JSON-тело и разрешённые заголовки, без cookie. Успешная запись сохраняется не менее 24 часов; периодическая очистка удаляет истёкшие результаты.

## 10. Путь сообщения помощнику

[AssistantController](../backend/src/main/java/com/creditcabinet/assistant/api/AssistantController.java) предоставляет создание диалога, историю и отправку вопроса.

[AssistantService](../backend/src/main/java/com/creditcabinet/assistant/service/AssistantService.java) разделяет работу на три части:

1. [AssistantStateService](../backend/src/main/java/com/creditcabinet/assistant/service/AssistantStateService.java) проверяет владельца, резервирует идемпотентную команду и единственную генерацию диалога, собирает разрешённый контекст.
2. Вне транзакции вызывается [OllamaAssistantGateway](../backend/src/main/java/com/creditcabinet/assistant/integration/OllamaAssistantGateway.java). Он использует Spring AI, проверяет модель, собирает промпт, получает и валидирует JSON.
3. При успехе StateService одной транзакцией добавляет USER + ASSISTANT с последовательными номерами, метаданные и сохранённый HTTP-ответ. Оба токена и сроки проверяются перед commit.

Бюджет ответа — 60 секунд, захваты — 75 секунд. При ошибке пара не добавляется; команду можно повторить. Новый ключ не позволяет параллельно генерировать второй ответ в том же занятом диалоге.

[AssistantPromptBuilder](../backend/src/main/java/com/creditcabinet/assistant/integration/AssistantPromptBuilder.java) использует точный токенизатор и удаляет старые полные пары из модельного контекста при переполнении. История в БД от этого не удаляется.

[AssistantGrounding](../backend/src/main/java/com/creditcabinet/assistant/integration/AssistantGrounding.java) получает факты из типизированных условий продукта. Подробности модели, структуры и ограничений — в [документе помощника](ASSISTANT.md).

## 11. Транзакции, потоки и соединения

| Механизм | Где и зачем |
|---|---|
| `REQUIRED` | Обычные бизнес-изменения: начать транзакцию или присоединиться к существующей |
| `REQUIRES_NEW` | Короткий независимый commit захвата, снимка или результата |
| `MANDATORY` | Завершение идемпотентной команды только вместе с бизнес-результатом |
| `NEVER` | Запрет держать транзакцию вокруг длительного внешнего сценария |

Входящие HTTP-запросы обслуживает встроенный Tomcat. Планировщик запускает фоновый цикл, ограниченные по времени внешние операции используют настроенные исполнители. HTTP-клиенты создаются как долгоживущие объекты Spring; создание клиента в конструкторе не означает сетевое подключение на каждый экземпляр запроса. Реальные обращения происходят при выполнении запроса, транспорт может переиспользовать соединения.

JPA/JDBC получают соединение PostgreSQL из пула только на время операций БД. Ожидание Python и модели не должно удерживать открытую транзакцию и блокировку строки на всё время ответа.
