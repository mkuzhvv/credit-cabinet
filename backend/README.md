# Бэкенд

Каркас Spring Boot-проекта: Maven, зависимости, стартовый класс и структура пакетов. Реализация и настройки компонентов добавляются отдельно.

## Основа

- Java 21, Spring Boot 4.1.2, Maven Wrapper 3.9.16.
- Корневой пакет и Maven groupId: `com.creditcabinet`.
- В `pom.xml` добавлены Spring MVC, RestClient, Validation, JPA, PostgreSQL, Flyway, Security, Spring AI / Ollama, springdoc, Actuator и тестовые зависимости.
- Пакеты `identity`, `catalog`, `credit`, `assistant`, `config`, `common.error` сохранены через `package-info.java` с описанием назначения.
- `application.yaml` содержит только имя приложения.
- Каталоги миграций и тестов пока пустые.

## Сборка

Требуется JDK 21. `JAVA_HOME` указывает на каталог JDK. Команды выполняются из `backend/`.

Windows / PowerShell:

```powershell
.\mvnw.cmd clean verify
```

Linux/macOS:

```bash
sh ./mvnw clean verify
```

Maven Wrapper скачивает Maven и зависимости. Сборка не запускает приложение и не требует Docker или базы данных. Тестов пока нет.

JAR: `target/credit-cabinet-backend-0.0.1-SNAPSHOT.jar`.

## IntelliJ IDEA

Импортировать `backend/pom.xml` как Maven-проект. Project SDK и JDK Maven Runner — Java 21; Maven distribution — Wrapper.

При открытом корне репозитория конфигурация `Backend` из `.run/Backend.run.xml` указывает на главный класс `com.creditcabinet.CreditCabinetApplication` и рабочую папку `backend/`. При необходимости обновить проект через Maven → Reload All Maven Projects.

Настройка запуска приложения относится к реализации: стартер JPA автоматически включает поддержку БД, поэтому для запуска потребуется настроить источник данных. Сейчас подключение к БД, Security и интеграции не настроены.

## Проектные решения

- [Архитектура и стек](../docs/BACKEND_ARCHITECTURE.md).
- [Сценарии](../docs/BACKEND_SCENARIOS.md).
- [Кредитная политика](../docs/CREDIT_POLICY_AND_ML_REQUIREMENTS.md).
- [Схема БД](../docs/DATABASE_SCHEMA.md).
- [Контракты](../docs/contracts/README.md).
