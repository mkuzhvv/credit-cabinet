# Docker для разработки

Сейчас Compose запускает только PostgreSQL. Общая упаковка бэкенда, фронта и моделей выполняется после реализации логики.

## Запуск

Из корня репозитория:

```powershell
docker compose -f docker/compose.db.yaml up -d --wait
```

Настройка одинакова для всей команды, `.env` не нужен:

- контейнер: `credit-cabinet-db`;
- образ: `postgres:18.6-alpine`;
- база и пользователь: `credit_cabinet`;
- адрес: `127.0.0.1:5432`;
- подключение без пароля (`trust`) для локальной разработки;
- том данных: `credit-cabinet-db-data`, путь внутри контейнера — `/var/lib/postgresql`.

Порт опубликован только на компьютере разработчика. Таблицы создаёт Flyway при запуске Java.

Проверить состояние и остановить БД:

```powershell
docker compose -f docker/compose.db.yaml ps
docker compose -f docker/compose.db.yaml stop
```

Остановка сохраняет данные в томе. Повторный `up` использует их снова.

`POSTGRES_HOST_AUTH_METHOD` применяется при первой инициализации. Для существующего тома со старой парольной конфигурацией нужно отдельно изменить настройки доступа; удалять том для этого не требуется.

Запуск Java описан в [backend/README.md](../backend/README.md).
