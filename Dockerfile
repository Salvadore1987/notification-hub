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
COPY adapter/build.gradle.kts adapter/
COPY bootstrap/build.gradle.kts bootstrap/
RUN ./gradlew --no-daemon dependencies --configuration runtimeClasspath || true

COPY config config
COPY domain/src domain/src
COPY application/src application/src
COPY adapter/src adapter/src
COPY bootstrap/src bootstrap/src
# Тесты в образе не гоняются: их гоняет пайплайн Банка, включая SAST и проверку зависимостей (SEC-09).
RUN ./gradlew --no-daemon :bootstrap:bootJar -x test

FROM ${RUNTIME_IMAGE}
WORKDIR /app

# Непривилегированный пользователь: контейнеру Hub'а нечего писать в файловую систему, кроме /tmp.
RUN useradd --system --uid 10001 --create-home commhub
USER 10001

COPY --from=build /workspace/bootstrap/build/libs/notification-hub.jar app.jar

# Секреты монтируются каталогом (SEC-04): SecretResolverAdapter читает файлы, а не переменные,
# и ротация подхватывается по TTL кэша без рестарта.
VOLUME ["/etc/commhub/secrets"]

EXPOSE 8080

# Контейнерные лимиты уважаются JVM автоматически; здесь фиксируется только то, что от них не следует:
# UTC как таймзона хранения (UI-04) и JSON-логи в контуре (OBS-03).
ENV TZ=UTC \
    COMMHUB_LOG_FORMAT=ecs \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
