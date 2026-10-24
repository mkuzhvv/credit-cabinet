"""Проверка одной заявки: тот же JSON, что присылает Java в HTTP-сервис."""

import argparse
import json
import sys

from engine import RuleEngine, load_json
from server import problem
from validation import InvalidFeatures
from uuid import uuid4


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('request', help='JSON запроса Scoring API, схема 4')
    parser.add_argument('--explain', action='store_true', help='Показать расчёты и причины, только для CLI')
    args = parser.parse_args()
    try:
        engine = RuleEngine()
        result = engine.evaluate(load_json(args.request))
    except InvalidFeatures as error:
        print(json.dumps(problem(422, 'INVALID_FEATURES', 'Признаки не соответствуют контракту', str(uuid4()), '/internal/v1/assessments', error.issues), ensure_ascii=False, indent=2), file=sys.stderr)
        raise SystemExit(2)
    print(json.dumps(result if args.explain else result['response'], ensure_ascii=False, indent=2, allow_nan=False))


if __name__ == '__main__':
    main()
