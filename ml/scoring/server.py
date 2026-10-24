"""Внутренний HTTP-сервис Java → правила. Стандартная библиотека, без ML-сервера."""

import argparse
import json
import logging
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
from uuid import UUID, uuid4

from engine import RuleEngine
from validation import InvalidFeatures

LOG = logging.getLogger('scoring')
MAX_BODY = 128 * 1024


def unique_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Повторяющийся ключ JSON')
        result[key] = value
    return result


def reject_constant(value):
    raise ValueError('Неконечные числа запрещены в JSON')


def problem(status, code, title, request_id, path, issues=None):
    result = {'type': 'urn:credit-cabinet:problem:' + code.lower().replace('_', '-'),
              'title': title, 'status': status, 'code': code, 'requestId': request_id, 'instance': path}
    if issues:
        result['errors'] = issues
    return result


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.connection.settimeout(10)

    def log_message(self, format, *args):
        # Не пишем тело запроса и финансовые данные в стандартный access log.
        pass

    def request_id(self):
        value = self.headers.get('X-Request-Id', '')
        try:
            return str(UUID(value))
        except ValueError:
            return str(uuid4())

    def send_json(self, status, value, request_id):
        body = json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(',', ':')).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/problem+json' if status >= 400 else 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Request-Id', request_id)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        request_id = self.request_id()
        path = urlsplit(self.path).path
        if path == '/health/live':
            self.send_json(200, {'status': 'UP'}, request_id)
        elif path == '/health/ready':
            if self.server.engine is None:
                self.send_json(503, problem(503, 'MODEL_NOT_READY', 'Правила не готовы', request_id, path), request_id)
            else:
                self.send_json(200, self.server.engine.ready(), request_id)
        else:
            self.send_json(404, problem(404, 'NOT_FOUND', 'Маршрут не найден', request_id, '/'), request_id)

    def do_POST(self):
        request_id = self.request_id()
        path = urlsplit(self.path).path
        if path != '/internal/v1/assessments':
            self.send_json(404, problem(404, 'NOT_FOUND', 'Маршрут не найден', request_id, '/'), request_id)
            return
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if length <= 0 or length > MAX_BODY or self.headers.get_content_type() != 'application/json':
                raise ValueError('Нужен JSON допустимого размера')
            raw = self.rfile.read(length)
            if len(raw) != length:
                raise ValueError('Тело запроса не завершено')
            document = json.loads(raw.decode('utf-8'), object_pairs_hook=unique_keys, parse_constant=reject_constant)
        except (ValueError, UnicodeError, TimeoutError):
            self.send_json(400, problem(400, 'MALFORMED_REQUEST', 'Некорректный JSON или HTTP-запрос', request_id, path), request_id)
            return
        if self.server.engine is None:
            self.send_json(503, problem(503, 'MODEL_NOT_READY', 'Правила не готовы', request_id, path), request_id)
            return
        try:
            assessment = self.server.engine.evaluate(document)
        except InvalidFeatures as error:
            self.send_json(422, problem(422, 'INVALID_FEATURES', 'Признаки не соответствуют контракту', request_id, path, error.issues), request_id)
            return
        except Exception:
            LOG.exception('Rule evaluation failed requestId=%s', request_id)
            self.send_json(500, problem(500, 'INFERENCE_FAILED', 'Не удалось вычислить результат правил', request_id, path), request_id)
            return
        LOG.info('requestId=%s assessmentId=%s decision=%s reasons=%s version=%s', request_id,
                 assessment['response']['assessmentId'], assessment['decision'], ','.join(assessment['reasonCodes']),
                 assessment['response']['modelVersion'])
        self.send_json(200, assessment['response'], request_id)


def create_server(host='127.0.0.1', port=8000, engine=None):
    server = ThreadingHTTPServer((host, port), Handler)
    server.daemon_threads = True
    server.engine = engine
    return server


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', default='0.0.0.0')
    parser.add_argument('--port', type=int, default=8000)
    parser.add_argument('--policy', default=os.getenv('SCORING_POLICY_PATH'))
    parser.add_argument('--catalog', default=os.getenv('SCORING_CATALOG_PATH'))
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format='%(message)s')
    engine = None
    try:
        engine = RuleEngine(args.policy, args.catalog)
    except Exception:
        LOG.exception('Rule engine is not ready; health/live remains available')
    server = create_server(args.host, args.port, engine)
    LOG.info('Listening on %s:%s; version=%s', *server.server_address, engine.model_version if engine else 'NOT_READY')
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
