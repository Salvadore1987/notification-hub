# Образ Notification Hub (NF-05).
#
# Два этапа: сборка на полном JDK, выполнение на JRE. Базовый образ здесь — публичный Temurin;
# в контуре Банка он заменяется на согласованный базовый образ (NF-05), поэтому он вынесен в ARG,
# а не зашит в FROM.
ARG BUILD_IMAGE=eclipse-temurin:25-jdk
ARG RUNTIME_IMAGE=eclipse-temurin:25-jre

FROM ${BUILD_IMAGE} AS build
WORKDIR /workspace

# Сначала — только то, что нужно Gradle для разрешения зависимостей: слой с ними переиспользуется,
# пока не меняются файлы сборки, а меняются они куда реже исходников.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY domain/build.gradle.kts domain/
COPY application/build.gradle.kts application/
COPY bootstrap/build.gradle.kts bootstrap/
COPY adapter/build.gradle.kts adapter/
COPY adapter/in/admin/build.gradle.kts adapter/in/admin/
COPY adapter/in/callback/build.gradle.kts adapter/in/callback/
COPY adapter/in/contract/build.gradle.kts adapter/in/contract/
COPY adapter/in/importer/build.gradle.kts adapter/in/importer/
COPY adapter/in/kafka/build.gradle.kts adapter/in/kafka/
COPY adapter/in/rest/build.gradle.kts adapter/in/rest/
COPY adapter/in/scheduler/build.gradle.kts adapter/in/scheduler/
COPY adapter/in/security/build.gradle.kts adapter/in/security/
COPY adapter/out/compliance/build.gradle.kts adapter/out/compliance/
COPY adapter/out/kafka/build.gradle.kts adapter/out/kafka/
COPY adapter/out/metrics/build.gradle.kts adapter/out/metrics/
COPY adapter/out/persistence/build.gradle.kts adapter/out/persistence/
COPY adapter/out/policy/build.gradle.kts adapter/out/policy/
COPY adapter/out/provider/apns/build.gradle.kts adapter/out/provider/apns/
COPY adapter/out/provider/fcm/build.gradle.kts adapter/out/provider/fcm/
COPY adapter/out/provider/playmobile/build.gradle.kts adapter/out/provider/playmobile/
COPY adapter/out/provider/smsgate/build.gradle.kts adapter/out/provider/smsgate/
COPY adapter/out/provider/smtp/build.gradle.kts adapter/out/provider/smtp/
COPY adapter/out/provider/support/build.gradle.kts adapter/out/provider/support/
COPY adapter/out/secret/build.gradle.kts adapter/out/secret/
COPY adapter/out/time/build.gradle.kts adapter/out/time/
COPY adapter/observability/build.gradle.kts adapter/observability/
RUN ./gradlew --no-daemon dependencies --configuration runtimeClasspath || true

COPY config config
COPY domain/src domain/src
COPY application/src application/src
# Каталог adapter копируется целиком: build-файлы модулей не изменились с прошлого слоя,
# поэтому кэш зависимостей выше не инвалидируется.
COPY adapter adapter
COPY bootstrap/src bootstrap/src
# Тесты в образе не гоняются: их гоняет пайплайн Банка, включая SAST и проверку зависимостей (SEC-09).
RUN ./gradlew --no-daemon :bootstrap:bootJar -x test

FROM ${RUNTIME_IMAGE}
WORKDIR /app

# Непривилегированный пользователь: контейнеру Hub'а нечего писать в файловую систему, кроме /tmp.
RUN useradd --system --uid 10001 --create-home commhub
USER 10001

COPY --from=build /workspace/bootstrap/build/libs/notification-hub.jar app.jar

# Секреты приходят переменными окружения (SEC-04, ADR-0036): SecretResolverAdapter читает окружение,
# каталога секретов у контейнера нет. Ротация — пересоздание контейнера с новым значением.

EXPOSE 8080

# Контейнерные лимиты уважаются JVM автоматически; здесь фиксируется только то, что от них не следует:
# UTC как таймзона хранения (UI-04) и JSON-логи в контуре (OBS-03).
ENV TZ=UTC \
    COMMHUB_LOG_FORMAT=ecs \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
