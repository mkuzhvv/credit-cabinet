FROM maven:3.9.16-eclipse-temurin-21 AS build

WORKDIR /app

COPY backend/pom.xml ./
COPY backend/src/main/ src/main/
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home app

WORKDIR /app

COPY --from=build --chown=app:app /app/target/credit-cabinet-backend-*.jar app.jar

USER app

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
