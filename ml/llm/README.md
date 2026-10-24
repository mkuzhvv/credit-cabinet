# Кредитный помощник

**Фронт → Java / Spring AI → локальная Ollama.** Python в этой папке — стенд и эталон логики; HTTP-сервера здесь нет. Интеграцию с Java ещё нужно реализовать и проверить.

## Запуск на машине с моделью

Нужны Ollama и Python 3.12. Команды из `ml/llm`:

```sh
python3.12 -m venv .venv
.venv/bin/python -m pip install -r requirements.lock.txt
ollama pull qwen3.5:9b-q4_K_M
.venv/bin/python setup_model.py
.venv/bin/python chat.py
```

Приложение Ollama должно быть запущено; адрес по умолчанию `http://localhost:11434`. Если окружение уже подготовлено, достаточно последней команды. `/reset` очищает историю, `/exit` закрывает чат. Освободить память: `ollama stop qwen3.5:9b-q4_K_M`.

Модель, digest и параметры закреплены в `config.json`; токенизатор — в `tokenizer_metadata-qwen35.json`. Веса скачиваются отдельно. Лицензия модели — [Apache-2.0](https://huggingface.co/Qwen/Qwen3.5-9B). Проверено на M2 / 16 GB RAM с Ollama 0.40.10; модель занимала около 5,2 GB.

## Бэкенду

1. Java проверяет доступ к диалогу/заявке и собирает **AssistantContext v2**: текущий вопрос, до 20 сообщений истории, актуальный каталог, определения полей и разрешённые данные заявки. Без заявки — `application: null`. Каталог и историю берёт сам бэк, не браузер. Формат: [схема входа](contracts/input.schema.json), [пример подбора](contracts/examples/product-request.json), [пример поля](contracts/examples/field-request.json).
2. Сборка запроса — как в `assistant.py`: JSON контекста и `system_prompt.txt` в системном сообщении, история с ролями `user` / `assistant`, текущий вопрос последним `user`. AssistantContext не является готовым телом Ollama `/api/chat`.
3. Через Spring AI вызвать Ollama `/api/chat`: модель и options из `config.json`, `stream: false`, `think: false`. Передать [схему ответа](contracts/output.schema.json) через provider structured output (`format` в нативном Ollama); разрешённые коды сузить до текущего каталога плюс `null`. На старте сверить digest через `/api/tags`.
4. Разобрать JSON из `message.content`. В нём только `answer` и `recommendedProductCode`. Проверить схему и принадлежность кода каталогу, применить правила `grounding.py`, затем проверить итог повторно. Ставки, сроки, периоды и поддерживаемые расчёты берутся из того же каталога/определений; в Java лучше использовать исходные типизированные данные. **Один промпт без этого слоя не даёт ту же точность.** Python-парсер рассчитан на согласованный MVP-формат `conditions`.
5. Сохранить успешную пару USER + ASSISTANT атомарно. `answer` записать в `assistantMessage.text`, код — в `assistantMessage.recommendedProductCode`; ID, sequence и время создаёт Java. Сырой ответ модели и технические метаданные не показывать как готовый ответ пользователю.

Для длинной истории удаляются старые полные пары, сохраняя правила, каталог и текущий вопрос. Считать токены с резервом из config, а не только символы; если обязательный контекст не помещается — техническая ошибка.

По Backend OpenAPI бюджет чата — **60 секунд**. `timeout_seconds: 180` в config относится к Python-стенду; Java должна соблюдать API-дедлайн. Ошибки: недоступность — `503 ASSISTANT_UNAVAILABLE`, невалидный ответ — `502 ASSISTANT_INVALID_RESPONSE`, таймаут — `504 ASSISTANT_TIMEOUT`. Ошибка не превращается в успешную рекомендацию.

## Фронтенду

По согласованному Backend OpenAPI, базовый путь `/api/v1`:

- `POST /assistant/conversations` без тела → сохранить полученный `id` диалога.
- `GET /assistant/conversations/{id}/messages` → восстановить историю; доступны `afterSequence` и `limit`.
- `POST /assistant/conversations/{id}/messages` → отправить сообщение:

```json
{
  "text": "Какой кредит подойдёт для покупки станка?",
  "context": {
    "applicationId": null,
    "productCode": null,
    "fieldCode": null
  }
}
```

Для помощи с полем передать ID доступной заявки, выбранный код продукта и путь поля, например `form.averageMonthlyOutflow`. В ответе Java использовать `assistantMessage.text`; при непустом `recommendedProductCode` показать кнопку выбора продукта. Нажатие выполняет обычное действие интерфейса; текст LLM сам форму не меняет.

Использовать cookie-сессию общего API. Для POST нужны `X-CSRF-TOKEN` (получить через `GET /api/v1/csrf`) и `Idempotency-Key` — UUID действия. Новое сообщение получает новый ключ, повтор того же запроса — прежний. При ошибке сохранить введённый текст; чат не должен блокировать форму или отправку заявки. Фронт не обращается к Ollama напрямую.

Эти маршруты описаны контрактом, а не реализованы Python-стендом. Источник версии схем — [SOURCE.json](contracts/SOURCE.json); после изменения Backend OpenAPI сверить маршруты с бэком.

## Проверка перед демонстрацией

```sh
.venv/bin/python chat.py --input contracts/examples/product-request.json
.venv/bin/python chat.py --input contracts/examples/field-request.json
```

Примеры содержат тестовый каталог; в приложении его формирует Java. На M2 холодный вызов занял около 48 секунд, медиана основного набора — 12 секунд; смена каталога может дать больше 60 секунд. Прогрейте модель на актуальном контексте и обработайте `504` на фронте. В проверенном наборе компонент прошёл 36 основных и 9 дополнительных сценариев; это не гарантия для любого будущего вопроса.
