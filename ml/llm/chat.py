"""Терминальный стенд. В продукте этот адаптер выполняет Java/Spring AI."""

import argparse
import copy
import json
import os
import sys

from assistant import AssistantError, ask, config, contracts_dir, load_json, verify_model


def main():
    parser = argparse.ArgumentParser(description="AssistantContext v2 → AssistantReply v1")
    parser.add_argument("--input", help="JSON контекста v2 от Java или тестового примера")
    parser.add_argument("--question", help="Один вопрос вместо message в примере")
    parser.add_argument("--field", help="Путь поля API, например form.averageMonthlyOutflow")
    parser.add_argument("--base-url", default=None)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.check:
        verify_model(args.base_url or os.getenv("OLLAMA_BASE_URL", "http://localhost:11434"))
        print(f"Локальная модель {config()['model']} доступна. Генерация не проверялась.")
        return

    # Это только fixture для ручных проверок. В продукте весь context даёт Java.
    template = contracts_dir() / "examples/product-request.json"
    context = load_json(args.input or template)
    if args.field:
        context["context"]["currentFieldCode"] = args.field
    if args.question is not None:
        context["message"] = args.question
    if args.input or args.question is not None:
        print(json.dumps(ask(context, args.base_url), ensure_ascii=False, indent=2))
        return

    print(f"Кредитный помощник | {config()['model']} | AssistantContext v2")
    print("/reset — очистить историю, /exit — выйти. Для сырого JSON используйте --input или --question.")
    context["history"] = []
    while True:
        question = input("\nВы: ").strip()
        if question == "/exit":
            return
        if question == "/reset":
            context["history"] = []
            print("История очищена.")
            continue
        if not question:
            continue
        request = copy.deepcopy(context)
        request["message"] = question
        # CLI хранит не больше 20 сообщений. Дополнительное токенное сокращение — в адаптере.
        request["history"] = request["history"][-20:]
        try:
            reply = ask(request, args.base_url)
        except AssistantError as error:
            print(f"{error.code}: {error}")
            continue
        print("\nАссистент:", reply["answer"])
        if reply["recommendedProductCode"]:
            print("Продукт:", reply["recommendedProductCode"])
        context["history"] = request["history"] + [
            {"role": "USER", "text": question},
            {"role": "ASSISTANT", "text": reply["answer"]},
        ]


if __name__ == "__main__":
    try:
        main()
    except (KeyboardInterrupt, EOFError):
        print("\nЧат завершён.")
    except AssistantError as error:
        print(f"{error.code}: {error}", file=sys.stderr)
        raise SystemExit(1)
    except (OSError, ValueError) as error:
        print(f"Ошибка файла или конфигурации: {error}", file=sys.stderr)
        raise SystemExit(1)
