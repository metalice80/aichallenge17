# День 20. Orchestration MCP: длинный flow через несколько MCP-серверов

## 1. Назначение

Реализовать с нуля учебное приложение на Kotlin и Spring Boot, в котором AI-агент подключён одновременно к нескольким независимым MCP-серверам, выбирает подходящие инструменты, корректно маршрутизирует запросы и выполняет длинный зависимый flow.

Основной пользовательский сценарий — подготовка плана поездки:

> Подготовь план поездки в Казань на ближайшие 3 дня. Учти погоду, выбери три достопримечательности, добавь краткие описания и сохрани отчёт в файл `kazan-trip.md`.

Для выполнения агент должен использовать инструменты трёх разных MCP-серверов:

```text
mcp-weather-server
  1. weather_resolve_location
  2. weather_get_forecast

mcp-guide-server
  3. guide_search_articles
  4. guide_get_article_summary
  5. guide_get_article_summary
  6. guide_get_article_summary

mcp-files-server
  7. files_save_markdown_report
```

Итог задания — работающее приложение, демонстрирующее:

- регистрацию нескольких MCP-серверов;
- автоматическое подключение к каждому серверу;
- discovery инструментов каждого сервера;
- выбор агентом инструмента нужного домена;
- корректную маршрутизацию tool calls;
- передачу данных между инструментами разных серверов;
- длинный tool-calling loop;
- проверку допустимого порядка вызовов;
- безопасное выполнение side effect последним шагом;
- журнал выполнения, live timeline и автоматические тесты.

Спецификация рассчитана на реализацию из пустого каталога и не зависит от проектов предыдущих дней.

---

## 2. Ключевое архитектурное решение

Использовать гибридную оркестрацию:

1. модель решает, какой MCP tool вызвать следующим;
2. Spring AI выполняет tool-calling loop;
3. `RoutingPolicy` в `agent-app` проверяет зависимости и разрешения до выполнения каждого tool;
4. три MCP-сервера выполняют только собственные доменные операции;
5. `agent-app` записывает единый межсерверный журнал вызовов.

Нельзя полагаться только на prompt. Если модель пытается сохранить файл до получения погоды и трёх описаний, side effect должен быть заблокирован программно.

Корректный порядок определяется зависимостями, а не единственным жёстким списком:

```text
weather_resolve_location
        ↓
weather_get_forecast

guide_search_articles
        ↓
guide_get_article_summary × 3 unique articles

forecast + 3 summaries
        ↓
files_save_markdown_report
```

Погодная и справочная ветки независимы и могут следовать друг за другом в любом порядке. В минимальной версии parallel tool calls отключены, поэтому фактическое исполнение остаётся последовательным и наблюдаемым.

---

## 3. Обязательный стек

- JDK 21;
- Kotlin 2.3.21;
- Spring Boot 4.1.1;
- Gradle Kotlin DSL;
- Spring AI 2.0.1;
- официальный Java MCP SDK, используемый Spring AI;
- Spring AI MCP server и MCP client starters;
- три локальных STDIO MCP-сервера;
- Spring AI OpenAI ChatModel и ChatClient;
- OpenAI API;
- Open-Meteo Geocoding API и Forecast API;
- MediaWiki REST API;
- Spring JDBC / `JdbcClient`;
- SQLite JDBC 3.53.4.0;
- Spring Boot Actuator и Micrometer;
- HTML, CSS и vanilla JavaScript;
- JUnit 5 и Spring Boot Test;
- локальные HTTP stubs для интеграционных тестов.

Не заменять Gradle Kotlin DSL на Maven или Groovy DSL.

### 3.1. Модель

Идентификатор модели обязательно вынести в конфигурацию:

```yaml
app:
  agent:
    model: ${OPENAI_MODEL:gpt-5.6-terra}
    reasoning-effort: ${OPENAI_REASONING_EFFORT:none}
```

`gpt-5.6-terra` используется как значение по умолчанию для более надёжного multi-step routing. Для экономичного запуска пользователь может задать `gpt-5.6-luna` через `OPENAI_MODEL`.

Spring AI 2.0.1 использует OpenAI Chat Completions для `OpenAiChatModel`. Передавать `reasoning_effort=none`, не задавать `temperature` для reasoning-модели и не делать скрытый fallback на другую модель.

Если выбранная модель не поддерживает function calling через используемый endpoint, завершать запрос понятной конфигурационной ошибкой.

### 3.2. Почему direct tool calling

Использовать обычный Spring AI tool-calling loop. Не внедрять OpenAI Programmatic Tool Calling, Agents API или Agents SDK.

В этом задании модель должна анализировать результаты поиска и выбирать релевантные статьи. Это adaptive/semantic routing, поэтому direct tool calls подходят лучше и проще проверяются в Spring AI.

---

## 4. Prerequisites

Проверить:

```bash
java -version
```

Ожидается JDK 21.

Также нужны:

- доступ Gradle к Maven Central;
- доступ к `api.openai.com`;
- доступ к Open-Meteo;
- доступ к Wikipedia/MediaWiki;
- действующий OpenAI API key для live smoke test.

Перед live-запуском:

```bash
export OPENAI_API_KEY="..."
```

Node.js и `npx` не требуются. Все MCP-серверы реализуются на Kotlin и запускаются как отдельные JVM child processes.

Не хранить API key в Git, YAML, исходниках, тестах или логах.

---

## 5. Архитектура

```text
Browser
   │ HTTP + SSE
   ▼
agent-app
   ├── REST API и Web UI
   ├── OrchestrationService
   ├── RoutingPolicy
   ├── Guarded MCP ToolCallbacks
   ├── Spring AI ChatClient
   ├── OpenAI ChatModel
   ├── SQLite orchestration journal
   │
   ├── STDIO connection: weather
   │       ▼
   │   mcp-weather-server
   │       └── Open-Meteo
   │
   ├── STDIO connection: guide
   │       ▼
   │   mcp-guide-server
   │       └── MediaWiki REST API
   │
   └── STDIO connection: files
           ▼
       mcp-files-server
           └── configured reports directory
```

### 5.1. Границы ответственности

`mcp-weather-server`:

- разрешает название города;
- выдаёт непрозрачный `locationRef`;
- получает прогноз;
- не знает про статьи и файлы.

`mcp-guide-server`:

- ищет статьи;
- выдаёт непрозрачные `articleRef`;
- получает краткое описание статьи;
- не знает про погоду и файлы.

`mcp-files-server`:

- безопасно сохраняет готовый Markdown;
- не обращается к OpenAI, Open-Meteo или MediaWiki;
- не предоставляет произвольный доступ к файловой системе.

`agent-app`:

- подключается ко всем серверам;
- передаёт инструменты модели;
- выполняет policy checks;
- ведёт единый run и timeline;
- является единственным оркестратором.

---

## 6. Структура проекта

```text
day20-mcp-orchestration/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── gradle/wrapper/
├── .gitignore
├── README.md
│
├── mcp-weather-server/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── kotlin/.../weather/
│       │   │   ├── WeatherServerApplication.kt
│       │   │   ├── config/WeatherProperties.kt
│       │   │   ├── api/OpenMeteoClient.kt
│       │   │   ├── cache/LocationReferenceStore.kt
│       │   │   ├── tool/WeatherTools.kt
│       │   │   └── model/WeatherDtos.kt
│       │   └── resources/application.yaml
│       └── test/kotlin/.../weather/
│
├── mcp-guide-server/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── kotlin/.../guide/
│       │   │   ├── GuideServerApplication.kt
│       │   │   ├── config/GuideProperties.kt
│       │   │   ├── api/MediaWikiClient.kt
│       │   │   ├── cache/ArticleReferenceStore.kt
│       │   │   ├── tool/GuideTools.kt
│       │   │   └── model/GuideDtos.kt
│       │   └── resources/application.yaml
│       └── test/kotlin/.../guide/
│
├── mcp-files-server/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── kotlin/.../files/
│       │   │   ├── FilesServerApplication.kt
│       │   │   ├── config/FilesProperties.kt
│       │   │   ├── tool/FilesTools.kt
│       │   │   ├── service/SafeMarkdownWriter.kt
│       │   │   └── model/FileDtos.kt
│       │   └── resources/application.yaml
│       └── test/kotlin/.../files/
│
└── agent-app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── kotlin/.../agent/
        │   │   ├── AgentApplication.kt
        │   │   ├── config/
        │   │   │   ├── AgentProperties.kt
        │   │   │   ├── ChatClientConfiguration.kt
        │   │   │   ├── McpConfiguration.kt
        │   │   │   └── ExecutorConfiguration.kt
        │   │   ├── orchestration/
        │   │   │   ├── OrchestrationService.kt
        │   │   │   ├── RoutingPolicy.kt
        │   │   │   ├── RoutingState.kt
        │   │   │   ├── GuardedToolCallback.kt
        │   │   │   ├── OrchestrationObserver.kt
        │   │   │   └── OrchestrationRecovery.kt
        │   │   ├── repository/
        │   │   │   ├── OrchestrationRunRepository.kt
        │   │   │   ├── ToolInvocationRepository.kt
        │   │   │   └── OrchestrationEventRepository.kt
        │   │   └── web/
        │   │       ├── OrchestrationController.kt
        │   │       ├── OrchestrationEventStream.kt
        │   │       └── ApiExceptionHandler.kt
        │   └── resources/
        │       ├── application.yaml
        │       ├── schema.sql
        │       └── static/
        │           ├── index.html
        │           ├── app.js
        │           └── styles.css
        └── test/kotlin/.../agent/
```

Можно изменить package names, но модульные границы обязательны.

---

## 7. Gradle

### 7.1. Корневой проект

`settings.gradle.kts`:

```kotlin
rootProject.name = "day20-mcp-orchestration"

include("mcp-weather-server")
include("mcp-guide-server")
include("mcp-files-server")
include("agent-app")
```

В корневом `build.gradle.kts` централизовать:

- Spring Boot 4.1.1;
- Kotlin 2.3.21;
- Spring dependency management;
- Spring AI BOM 2.0.1;
- JVM toolchain 21;
- UTF-8;
- JUnit Platform;
- Kotlin compiler options.

### 7.2. MCP server modules

Каждый сервер использует минимум:

```kotlin
implementation("org.springframework.ai:spring-ai-starter-mcp-server")
implementation("org.springframework.boot:spring-boot-starter")
implementation("org.springframework.boot:spring-boot-starter-validation")
implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

testImplementation("org.springframework.boot:spring-boot-starter-test")
```

Weather и guide servers используют Spring `RestClient`. При необходимости добавить минимальный web starter, но запускать серверы с `web-application-type: none`.

Стабильные executable jar names:

```text
mcp-weather-server.jar
mcp-guide-server.jar
mcp-files-server.jar
```

### 7.3. `agent-app`

```kotlin
implementation("org.springframework.boot:spring-boot-starter-web")
implementation("org.springframework.boot:spring-boot-starter-validation")
implementation("org.springframework.boot:spring-boot-starter-jdbc")
implementation("org.springframework.boot:spring-boot-starter-actuator")
implementation("org.springframework.ai:spring-ai-starter-model-openai")
implementation("org.springframework.ai:spring-ai-starter-mcp-client")
implementation("io.micrometer:micrometer-registry-prometheus")
implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
runtimeOnly("org.xerial:sqlite-jdbc:3.53.4.0")

testImplementation("org.springframework.boot:spring-boot-starter-test")
```

### 7.4. Build dependencies

Задачи запуска и integration tests `agent-app` должны зависеть от:

```text
:mcp-weather-server:bootJar
:mcp-guide-server:bootJar
:mcp-files-server:bootJar
```

Не предполагать, что jar уже собраны вручную.

---

## 8. Общие требования к STDIO MCP-серверам

Каждый сервер:

- запускается как отдельный Spring Boot процесс;
- использует synchronous MCP server;
- имеет уникальные `name` и `version`;
- резервирует stdout исключительно для MCP JSON-RPC;
- пишет обычные логи только в stderr;
- отключает banner;
- не открывает HTTP port;
- корректно завершает работу при закрытии stdin/client;
- регистрирует tools через `@McpTool`;
- генерирует JSON input/output schema;
- возвращает структурированные DTO;
- не возвращает stack traces модели.

Базовый YAML каждого сервера:

```yaml
spring:
  main:
    web-application-type: none
    banner-mode: off
  ai:
    mcp:
      server:
        type: SYNC
        stdio: true
```

Нельзя использовать `println` в server code.

---

## 9. `mcp-weather-server`

Server info:

```text
name: weather-server
version: 1.0.0
```

Конфигурация:

```yaml
app:
  weather:
    geocoding-base-url: ${GEOCODING_BASE_URL:https://geocoding-api.open-meteo.com}
    forecast-base-url: ${FORECAST_BASE_URL:https://api.open-meteo.com}
    reference-ttl: 10m
    reference-cache-size: 1000
    connect-timeout: 3s
    read-timeout: 10s
```

### 9.1. `weather_resolve_location`

Назначение: разрешить название города и создать непрозрачную ссылку на местоположение.

Вход:

```json
{
  "city": "Казань"
}
```

Ограничения:

- обязательная строка;
- после trim от 2 до 120 символов;
- не принимать URL или управляющие символы.

Вызвать Open-Meteo Geocoding API с `count=1`.

Создать cryptographically random UUID `locationRef` и сохранить в bounded TTL cache:

```text
locationRef
runId
normalizedName
country
latitude
longitude
timezone
expiresAt
```

`runId` получить из MCP metadata. Reference нельзя использовать в другом orchestration run.

Результат:

```json
{
  "locationRef": "loc-8e39...",
  "name": "Казань",
  "country": "Россия",
  "latitude": 55.79,
  "longitude": 49.12,
  "timezone": "Europe/Moscow",
  "expiresAt": "2026-09-28T12:10:00Z",
  "nextTool": "weather_get_forecast"
}
```

Ошибки:

```text
CITY_NOT_FOUND
GEOCODING_UNAVAILABLE
INVALID_CITY
MISSING_ORCHESTRATION_CONTEXT
```

### 9.2. `weather_get_forecast`

Вход:

```json
{
  "locationRef": "loc-8e39...",
  "days": 3
}
```

Ограничения:

- `days` от 1 до 7;
- reference существует;
- reference не истёк;
- reference принадлежит текущему run.

Запрашиваемые daily values:

```text
weather_code
temperature_2m_min
temperature_2m_max
precipitation_sum
precipitation_probability_max
wind_speed_10m_max
```

Результат должен быть компактным, максимум семь записей:

```json
{
  "locationRef": "loc-8e39...",
  "location": "Казань, Россия",
  "timezone": "Europe/Moscow",
  "days": [
    {
      "date": "2026-09-29",
      "temperatureMinC": 8.2,
      "temperatureMaxC": 15.4,
      "precipitationMm": 1.3,
      "precipitationProbabilityPercent": 60,
      "maxWindKmh": 22.1,
      "weatherDescription": "Небольшой дождь"
    }
  ]
}
```

Ошибки:

```text
LOCATION_REF_NOT_FOUND
LOCATION_REF_EXPIRED
LOCATION_REF_FROM_ANOTHER_RUN
FORECAST_UNAVAILABLE
INVALID_FORECAST_RESPONSE
```

Descriptions инструментов должны явно требовать передавать `locationRef` без изменения.

---

## 10. `mcp-guide-server`

Server info:

```text
name: guide-server
version: 1.0.0
```

Использовать MediaWiki REST API конкретной Wikipedia language edition.

Конфигурация:

```yaml
app:
  guide:
    base-url: ${MEDIAWIKI_BASE_URL:https://ru.wikipedia.org}
    user-agent: ${MEDIAWIKI_USER_AGENT:day20-mcp-orchestration/1.0}
    reference-ttl: 10m
    reference-cache-size: 1000
    max-summary-characters: 1500
    connect-timeout: 3s
    read-timeout: 10s
```

Отправлять корректный User-Agent. Не загружать произвольные URL из ответа модели.

### 10.1. `guide_search_articles`

Вход:

```json
{
  "city": "Казань",
  "query": "достопримечательности",
  "limit": 6
}
```

Ограничения:

- `city` и `query` обязательны;
- длина каждого поля не более 120 символов;
- `limit` от 3 до 8.

Сформировать безопасный search query и вызвать:

```text
/w/rest.php/v1/search/page
```

Для каждого результата создать random `articleRef` и сохранить в bounded TTL cache:

```text
articleRef
runId
pageKey
title
description
sourceUrl
expiresAt
```

Результат:

```json
{
  "query": "Казань достопримечательности",
  "articles": [
    {
      "articleRef": "article-101",
      "title": "Казанский кремль",
      "description": "Историческая крепость...",
      "sourceUrl": "https://ru.wikipedia.org/wiki/..."
    }
  ],
  "instruction": "Select three unique relevant articleRef values and call guide_get_article_summary for each"
}
```

Удалить HTML markup из excerpts. Ограничить длину каждого description.

Ошибки:

```text
GUIDE_NOT_FOUND
GUIDE_SEARCH_UNAVAILABLE
INVALID_GUIDE_QUERY
```

### 10.2. `guide_get_article_summary`

Вход:

```json
{
  "articleRef": "article-101"
}
```

Проверить reference, TTL и принадлежность run. Использовать только сохранённый `pageKey`, а не URL или title от модели.

Получить краткое содержимое страницы через документированный MediaWiki endpoint. Ограничить summary до configured maximum, завершив по границе предложения, если возможно.

Результат:

```json
{
  "articleRef": "article-101",
  "title": "Казанский кремль",
  "summary": "...",
  "sourceUrl": "https://ru.wikipedia.org/wiki/..."
}
```

Ошибки:

```text
ARTICLE_REF_NOT_FOUND
ARTICLE_REF_EXPIRED
ARTICLE_REF_FROM_ANOTHER_RUN
ARTICLE_UNAVAILABLE
INVALID_ARTICLE_RESPONSE
```

---

## 11. `mcp-files-server`

Server info:

```text
name: files-server
version: 1.0.0
```

Конфигурация:

```yaml
app:
  files:
    reports-directory: ${REPORTS_DIR:./reports}
    max-content-bytes: 51200
```

При старте каталог создаётся и нормализуется в absolute canonical path.

Не использовать универсальный filesystem MCP server.

### 11.1. `files_save_markdown_report`

Вход:

```json
{
  "fileName": "kazan-trip.md",
  "content": "# План поездки...",
  "weatherLocationRef": "loc-8e39...",
  "articleRefs": ["article-101", "article-102", "article-103"],
  "sourceUrls": ["https://ru.wikipedia.org/wiki/..."]
}
```

Evidence fields нужны для межсерверной policy-проверки. Files server не обращается к другим серверам и не пытается сам подтверждать факты.

Валидация:

- filename — только basename;
- расширение строго `.md`;
- длина не более 120;
- запретить `/`, `\\`, `..`, null byte и control characters;
- normalized destination обязан оставаться внутри reports directory;
- content не пустой;
- размер в UTF-8 не больше configured limit;
- ровно один `weatherLocationRef`;
- три уникальных `articleRefs`;
- source URLs ограничить до десяти и разрешить только `https`;
- не следовать source URLs и не загружать их.

Запись:

1. вычислить SHA-256;
2. записать temporary file в том же каталоге;
3. выполнить atomic move;
4. если atomic move недоступен, использовать безопасный fallback;
5. не перезаписывать существующий файл с другим содержимым;
6. повтор с тем же filename и hash считать идемпотентным.

Результат:

```json
{
  "fileName": "kazan-trip.md",
  "relativePath": "reports/kazan-trip.md",
  "sizeBytes": 3251,
  "sha256": "...",
  "status": "SAVED"
}
```

Ошибки:

```text
UNSAFE_FILE_NAME
INVALID_REPORT_CONTENT
REPORT_TOO_LARGE
FILE_ALREADY_EXISTS
FILE_WRITE_FAILED
```

Tool description должна сообщать модели, что инструмент является финальным side effect и вызывается только после получения всех данных.

---

## 12. Конфигурация нескольких MCP-подключений

`agent-app/src/main/resources/application.yaml`:

```yaml
server:
  port: ${SERVER_PORT:8080}

spring:
  application:
    name: day20-agent-app
  datasource:
    url: jdbc:sqlite:${ORCHESTRATION_DB_PATH:./data/orchestration.db}
    driver-class-name: org.sqlite.JDBC
  sql:
    init:
      mode: always
  ai:
    openai:
      api-key: ${OPENAI_API_KEY:}
      chat:
        model: ${OPENAI_MODEL:gpt-5.6-terra}
        reasoning-effort: ${OPENAI_REASONING_EFFORT:none}
        parallel-tool-calls: false
    tools:
      throw-exception-on-error: false
      limits:
        max-total-tool-calls: 12
        max-calls-per-tool-default: 5
        max-calls-per-tool:
          weather_resolve_location: 2
          weather_get_forecast: 2
          guide_search_articles: 2
          guide_get_article_summary: 5
          files_save_markdown_report: 1
        on-limit-exceeded: THROW
    mcp:
      client:
        name: day20-orchestrator
        version: 1.0.0
        type: SYNC
        initialized: true
        request-timeout: 30s
        stdio:
          connections:
            weather:
              command: ${JAVA_COMMAND:java}
              args:
                - -jar
                - ${WEATHER_SERVER_JAR:../mcp-weather-server/build/libs/mcp-weather-server.jar}
              env:
                GEOCODING_BASE_URL: ${GEOCODING_BASE_URL:https://geocoding-api.open-meteo.com}
                FORECAST_BASE_URL: ${FORECAST_BASE_URL:https://api.open-meteo.com}

            guide:
              command: ${JAVA_COMMAND:java}
              args:
                - -jar
                - ${GUIDE_SERVER_JAR:../mcp-guide-server/build/libs/mcp-guide-server.jar}
              env:
                MEDIAWIKI_BASE_URL: ${MEDIAWIKI_BASE_URL:https://ru.wikipedia.org}
                MEDIAWIKI_USER_AGENT: ${MEDIAWIKI_USER_AGENT:day20-mcp-orchestration/1.0}

            files:
              command: ${JAVA_COMMAND:java}
              args:
                - -jar
                - ${FILES_SERVER_JAR:../mcp-files-server/build/libs/mcp-files-server.jar}
              env:
                REPORTS_DIR: ${REPORTS_DIR:./reports}

app:
  agent:
    model: ${OPENAI_MODEL:gpt-5.6-terra}
    reasoning-effort: ${OPENAI_REASONING_EFFORT:none}
    execution-timeout: 90s
  orchestration:
    executor-threads: 2
    queue-capacity: 20
    stale-run-timeout: 5m
    event-poll-interval: 500ms
    sse-timeout: 2m

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
```

Уточнить property names по фактическому Spring AI 2.0.1 configuration metadata. Если структура `max-calls-per-tool` не bindится как map в YAML, создать `ToolCallingManager` программно с эквивалентными лимитами. Не удалять ограничения.

До открытия HTTP endpoint приложение должно проверить существование всех трёх jar и завершиться с понятным сообщением при отсутствии любого из них.

---

## 13. Имена и discovery инструментов

Ожидаемые raw tool names:

```text
weather_resolve_location
weather_get_forecast
guide_search_articles
guide_get_article_summary
files_save_markdown_report
```

Имена намеренно содержат доменный префикс. Не создавать одинаковые tools `search`, `get` или `save` на разных серверах.

Оставить стандартный `DefaultMcpToolNamePrefixGenerator`: при отсутствии дубликатов имена сохраняются предсказуемыми, а возможные будущие конфликты безопасно разрешаются.

На старте `agent-app` логировать без schemas и аргументов:

```text
connection=weather server=weather-server tools=2
connection=guide server=guide-server tools=2
connection=files server=files-server tools=1
```

Если любой сервер не инициализировался либо обязательный tool отсутствует, health должен быть DOWN и новые orchestration runs не принимаются.

---

## 14. Tool allowlist

Создать единственный composite `McpToolFilter`, разрешающий модели только пять ожидаемых tools и только от ожидаемых server connections.

Allowlist должна проверять одновременно:

- server info / connection info;
- имя tool;
- соответствие tool серверу.

Пример соответствий:

```text
weather-server → weather_*
guide-server   → guide_*
files-server   → files_save_markdown_report
```

Любой неизвестный tool отклоняется по умолчанию.

Не включать tool resolution fallback, позволяющий выполнить отсутствующий в request tool через глобальный resolver.

---

## 15. Orchestration context

Перед началом `agent-app` создаёт:

```text
runId = UUID
```

При вызове ChatClient передавать через `ToolContext`:

```text
orchestrationRunId
conversationId
traceparent, если доступен
```

Spring AI `ToolContextToMcpMetaConverter` должен переносить эти значения в MCP `_meta`. Каждый сервер читает run ID через `McpMeta` или `McpSyncRequestContext`.

Run ID:

- не является model-generated argument;
- не входит в tool JSON schemas;
- используется для ограничения opaque references;
- находится в MDC и журнале вызовов;
- одинаков для вызовов всех трёх серверов.

При отсутствии run ID сервер возвращает `MISSING_ORCHESTRATION_CONTEXT`.

---

## 16. SQLite в `agent-app`

Только `agent-app` владеет orchestration database. MCP-серверы не подключаются к ней.

Включить:

```sql
PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
PRAGMA busy_timeout = 5000;
```

Использовать `JdbcClient`, не JPA/Hibernate.

Внедрять `Clock` для timestamps.

### 16.1. `orchestration_run`

```text
id TEXT PRIMARY KEY
request_text TEXT NOT NULL
status TEXT NOT NULL
model_id TEXT NOT NULL
created_at TEXT NOT NULL
started_at TEXT
updated_at TEXT NOT NULL
finished_at TEXT
final_answer TEXT
report_path TEXT
error_code TEXT
error_message TEXT
version INTEGER NOT NULL DEFAULT 0
```

Статусы:

```text
CREATED
RUNNING
COMPLETED
FAILED
CANCELLED
```

### 16.2. `tool_invocation`

```text
id TEXT PRIMARY KEY
run_id TEXT NOT NULL
sequence_number INTEGER NOT NULL
server_name TEXT NOT NULL
tool_name TEXT NOT NULL
tool_call_id TEXT
status TEXT NOT NULL
started_at TEXT NOT NULL
finished_at TEXT
duration_ms INTEGER
input_hash TEXT NOT NULL
output_hash TEXT
input_refs_json TEXT NOT NULL
output_refs_json TEXT
error_code TEXT
error_message TEXT
FOREIGN KEY(run_id) REFERENCES orchestration_run(id)
UNIQUE(run_id, sequence_number)
```

Статусы:

```text
REQUESTED
RUNNING
SUCCEEDED
REJECTED
FAILED
```

Не хранить полный report content или полные внешние payloads в этой таблице. Хранить hashes и нормализованные evidence references.

### 16.3. `orchestration_event`

```text
id TEXT PRIMARY KEY
run_id TEXT NOT NULL
sequence_number INTEGER NOT NULL
event_type TEXT NOT NULL
server_name TEXT
tool_name TEXT
occurred_at TEXT NOT NULL
payload_json TEXT NOT NULL
FOREIGN KEY(run_id) REFERENCES orchestration_run(id)
UNIQUE(run_id, sequence_number)
```

События:

```text
RUN_CREATED
RUN_STARTED
MODEL_ITERATION_STARTED
TOOL_REQUESTED
TOOL_ALLOWED
TOOL_REJECTED
TOOL_SUCCEEDED
TOOL_FAILED
EVIDENCE_RECORDED
REPORT_SAVED
RUN_COMPLETED
RUN_FAILED
RUN_RECOVERED_AS_FAILED
```

Events append-only. Изменение текущего state и добавление event должны быть транзакционными.

---

## 17. RoutingState

Для каждого run policy должна уметь восстановить состояние из успешных tool invocations:

```text
resolvedLocationRefs
forecastedLocationRefs
searchedArticleRefs
summarizedArticleRefs
savedReport
totalToolCalls
perToolCallCounts
```

Нельзя полагаться только на in-memory state: после сбоя UI и audit должны восстанавливаться из SQLite.

Opaque reference stores внутри weather/guide servers могут быть in-memory, поскольку автоматическое продолжение run после рестарта серверов не входит в scope.

---

## 18. GuardedToolCallback

Получить callbacks из `SyncMcpToolCallbackProvider` и обернуть каждый agent-facing callback декоратором.

До delegate call:

1. извлечь run ID из `ToolContext`;
2. определить server по tool name и registry;
3. распарсить аргументы строго по ожидаемой schema;
4. вызвать `RoutingPolicy.validateBeforeCall`;
5. создать invocation и event;
6. заполнить MDC;
7. запустить Micrometer Observation;
8. только после успешной проверки вызвать реальный MCP callback.

После delegate call:

1. распарсить structured result;
2. проверить обязательные result fields;
3. извлечь output references;
4. обновить invocation;
5. записать evidence и events;
6. обновить RoutingState;
7. очистить MDC в `finally`.

При policy rejection реальный MCP server не вызывается. Модель получает структурированный результат:

```json
{
  "success": false,
  "code": "PRECONDITION_FAILED",
  "message": "files_save_markdown_report requires a forecast and three unique article summaries",
  "retryable": true
}
```

Policy rejection считается tool response, чтобы модель могла исправить последовательность. Run не обязательно сразу завершать ошибкой, пока не превышены лимиты.

Network, protocol или unrecoverable validation error может завершать run `FAILED`.

---

## 19. RoutingPolicy

### 19.1. `weather_resolve_location`

Разрешён, если:

- run активен;
- tool ещё не превышал limit;
- city соответствует пользовательской цели.

Повтор идентичного resolve допускается максимум один раз после `LOCATION_REF_EXPIRED`.

### 19.2. `weather_get_forecast`

Разрешён только если:

- `locationRef` был возвращён успешным `weather_resolve_location` в этом run;
- forecast для reference ещё не получен;
- days от 1 до 7.

### 19.3. `guide_search_articles`

Разрешён для активного run. Повтор допускается один раз с уточнённым query, если первый поиск вернул недостаточно результатов.

### 19.4. `guide_get_article_summary`

Разрешён только если:

- `articleRef` присутствует в успешном search result текущего run;
- summary для него ещё не получено;
- общее число detail calls не превышает пять.

### 19.5. `files_save_markdown_report`

Разрешён только если:

- получен успешный forecast;
- получены summaries минимум трёх уникальных article refs;
- `weatherLocationRef` совпадает с reference успешного forecast;
- входной список содержит три уникальных refs с успешными summaries;
- source URLs являются подмножеством URLs из tool results;
- save ещё не выполнялся;
- filename и content проходят базовую client-side validation.

После успешного save все дальнейшие tool calls запрещены.

### 19.6. Завершение агента

После финального model response `OrchestrationService` повторно проверяет RoutingState.

Для основного travel-report сценария run может стать `COMPLETED` только если:

```text
forecast exists
unique article summaries >= 3
save result status == SAVED
report path exists in result
```

Если модель завершила ответ раньше:

```text
status = FAILED
error_code = ORCHESTRATION_INCOMPLETE
```

---

## 20. Системная инструкция

Создать dedicated `orchestrationChatClient`, которому доступны только пять guarded callbacks.

Системный prompt:

```text
You are a travel planning orchestration agent connected to three MCP servers.

Tool routing:
- weather_* tools are only for resolving locations and obtaining weather forecasts.
- guide_* tools are only for searching destination articles and obtaining article summaries.
- files_* tools are only for writing the final Markdown report.

For a complete travel report:
1. Resolve the destination with weather_resolve_location.
2. Pass the exact returned locationRef to weather_get_forecast.
3. Search destination articles with guide_search_articles.
4. Select three unique relevant articleRef values from that result.
5. Call guide_get_article_summary once for each selected articleRef.
6. Build a concise Markdown report using only facts returned by tools.
7. Include the forecast, three places, source links, and practical weather-aware advice.
8. Call files_save_markdown_report with the exact evidence references.
9. Return success only after the file tool returns status SAVED.

Never invent or modify locationRef or articleRef values.
Never invent weather values, article facts, source URLs, or saved paths.
Do not skip prerequisites.
Do not repeat a completed side effect.
If a tool returns a retryable routing error, correct the sequence.
If a required external service fails, stop and identify the failed server and tool.
```

Не добавлять скрытые инструкции, требующие раскрывать chain-of-thought. Пользователю показывается только краткий progress и итоговый ответ.

---

## 21. OrchestrationService

`POST` не должен удерживать соединение на время всей цепочки.

Алгоритм:

1. валидировать запрос;
2. создать run и `RUN_CREATED`;
3. вернуть HTTP 202 с run ID;
4. запустить работу в bounded executor;
5. перевести run в `RUNNING`;
6. вызвать ChatClient с guarded callbacks и ToolContext;
7. дождаться конечного model response в пределах timeout;
8. проверить RoutingState;
9. при полном flow сохранить final answer и `COMPLETED`;
10. при ошибке сохранить стабильный error code;
11. при shutdown прервать незавершённую задачу и отметить run.

Executor:

- configurable fixed pool;
- bounded queue;
- понятный thread prefix;
- graceful shutdown;
- обработка rejection;
- не использовать common ForkJoinPool.

Если очередь заполнена, вернуть 429/503 либо завершить уже созданный run с `EXECUTOR_REJECTED`.

---

## 22. Web API

### `POST /api/orchestrations`

```json
{
  "message": "Подготовь план поездки в Казань на ближайшие 3 дня. Учти погоду, выбери три достопримечательности и сохрани отчёт в kazan-trip.md"
}
```

Ограничения:

- обязательная непустая строка;
- максимум 3000 символов.

Ответ HTTP 202:

```json
{
  "runId": "run-123",
  "status": "CREATED",
  "statusUrl": "/api/orchestrations/run-123",
  "eventsUrl": "/api/orchestrations/run-123/events/stream"
}
```

### `GET /api/orchestrations/{runId}`

Возвращает:

- общий статус;
- модель;
- список tool invocations;
- server name для каждого вызова;
- sequence и duration;
- input/output references;
- report path;
- final answer или безопасную ошибку.

### `GET /api/orchestrations/{runId}/events?after=0&limit=100`

Возвращает события в порядке sequence.

### `GET /api/orchestrations/{runId}/events/stream`

SSE:

- snapshot сразу после соединения;
- новые events без дублей;
- `Last-Event-ID` для reconnect;
- heartbeat comments;
- завершение после terminal event;
- cleanup poller/task после disconnect.

### `GET /api/orchestrations/{runId}/report`

Опционально возвращает сохранённый Markdown только по подтверждённому report path данного run. Нельзя принимать произвольный filesystem path.

---

## 23. Web UI

Минимальная страница без frontend framework.

Содержит:

- textarea;
- пример travel request;
- кнопку запуска;
- run ID;
- общий статус;
- timeline;
- server badges `WEATHER`, `GUIDE`, `FILES`;
- tool name;
- duration;
- evidence refs;
- финальный ответ;
- report path;
- понятную ошибку.

Пример:

```text
✓ WEATHER  weather_resolve_location          0.31 с
✓ WEATHER  weather_get_forecast              0.82 с
✓ GUIDE    guide_search_articles             0.47 с
✓ GUIDE    guide_get_article_summary          0.21 с
✓ GUIDE    guide_get_article_summary          0.19 с
✓ GUIDE    guide_get_article_summary          0.24 с
✓ FILES    files_save_markdown_report         0.08 с
```

Требования:

- `fetch` для запуска;
- `EventSource` для timeline;
- `textContent`, не `innerHTML`;
- возможность одновременно наблюдать разные runs без смешивания;
- responsive layout;
- no stack traces;
- наглядное отображение policy rejection, если оно произошло и модель восстановилась.

---

## 24. Наблюдаемость

### 24.1. Correlation

В каждом log/observation:

```text
runId
invocationId
serverName
toolName
toolCallId
sequenceNumber
```

MDC очищать в `finally`.

### 24.2. Structured logs

Пример:

```json
{
  "event": "orchestration.tool.completed",
  "runId": "run-123",
  "sequence": 4,
  "serverName": "guide-server",
  "toolName": "guide_get_article_summary",
  "status": "SUCCEEDED",
  "durationMs": 210,
  "inputRefs": ["article-101"],
  "outputHash": "..."
}
```

Не логировать API key, полный prompt, полный report content или полный внешний response.

### 24.3. Metrics

```text
orchestration.runs{status}
orchestration.active
orchestration.tool.calls{server,tool,status}
orchestration.tool.duration{server,tool,status}
orchestration.routing.rejections{tool,reason}
orchestration.external.failures{server,error_code}
```

Не использовать run ID, invocation ID, city, filename или article reference как labels.

### 24.4. Tracing

Ожидаемая структура:

```text
orchestration.run
└── agent.tool-loop
    ├── openai.iteration
    ├── mcp.weather_resolve_location
    │   └── openmeteo.geocoding
    ├── openai.iteration
    ├── mcp.weather_get_forecast
    │   └── openmeteo.forecast
    ├── openai.iteration
    ├── mcp.guide_search_articles
    │   └── mediawiki.search
    ├── mcp.guide_get_article_summary × 3
    ├── openai.iteration
    └── mcp.files_save_markdown_report
        └── file.write
```

Использовать Spring AI/Micrometer observations. Tool arguments/results не включать в trace по умолчанию.

Внешний OTLP collector необязателен. Приложение должно работать без Jaeger/Tempo.

---

## 25. Recovery

При старте `agent-app` найти runs:

```text
status IN (CREATED, RUNNING)
updated_at < now - staleRunTimeout
```

Перевести их в `FAILED`:

```text
error_code = PROCESS_INTERRUPTED
```

Добавить `RUN_RECOVERED_AS_FAILED`.

Не продолжать автоматически старую OpenAI conversation и не повторять file save после рестарта.

Weather/guide references после рестарта server processes считаются истёкшими.

---

## 26. Error codes

Использовать стабильные коды:

```text
VALIDATION_ERROR
CONFIGURATION_ERROR
MCP_SERVER_UNAVAILABLE
MCP_TOOL_MISSING
MISSING_ORCHESTRATION_CONTEXT
ORCHESTRATION_NOT_FOUND
ORCHESTRATION_INCOMPLETE
PRECONDITION_FAILED
TOOL_CALL_LIMIT_EXCEEDED
REFERENCE_NOT_FOUND
REFERENCE_EXPIRED
REFERENCE_FROM_ANOTHER_RUN
GEOCODING_UNAVAILABLE
FORECAST_UNAVAILABLE
GUIDE_SEARCH_UNAVAILABLE
ARTICLE_UNAVAILABLE
UNSAFE_FILE_NAME
REPORT_TOO_LARGE
FILE_ALREADY_EXISTS
FILE_WRITE_FAILED
OPENAI_UNAVAILABLE
OPENAI_TIMEOUT
EXECUTOR_REJECTED
PROCESS_INTERRUPTED
INTERNAL_ERROR
```

Ошибка для модели и UI:

```json
{
  "success": false,
  "code": "PRECONDITION_FAILED",
  "message": "Forecast requires a locationRef returned by weather_resolve_location",
  "retryable": true,
  "server": "weather-server",
  "tool": "weather_get_forecast"
}
```

Stack trace — только stderr logs.

---

## 27. Тестирование

Автоматические тесты не используют реальные OpenAI, Open-Meteo и MediaWiki.

### 27.1. Weather server unit tests

Проверить:

- city validation;
- geocoding mapping;
- WMO code mapping;
- forecast mapping;
- location ref TTL;
- reference принадлежит run;
- cache bound/cleanup;
- timeout, 429, 5xx и malformed response.

### 27.2. Guide server unit tests

Проверить:

- query validation;
- MediaWiki search mapping;
- HTML stripping;
- article ref TTL;
- reference принадлежит run;
- summary truncation;
- source URL construction;
- timeout, 429, 5xx и malformed response.

### 27.3. Files server unit tests

Проверить:

- safe filename;
- `../`, absolute paths, separators и control chars;
- `.md` requirement;
- UTF-8 size limit;
- evidence validation;
- atomic write;
- idempotent same-content repeat;
- different-content conflict;
- файл всегда остаётся внутри reports directory.

### 27.4. MCP contract tests

Для каждого jar отдельно:

1. запустить настоящий child process;
2. выполнить initialize;
3. выполнить tools/list;
4. проверить server info;
5. проверить точное множество tools;
6. проверить input/output schemas;
7. выполнить happy-path tool call;
8. корректно закрыть client и process.

External HTTP заменяется локальным JDK HTTP stub.

### 27.5. Multi-server discovery test

Запустить `agent-app` с тремя настоящими STDIO servers и проверить:

- создано три MCP clients;
- каждый client initialized;
- обнаружены ровно пять agent-facing tools;
- tool names уникальны;
- каждый tool связан с ожидаемым server;
- allowlist не пропускает неизвестные tools;
- shutdown закрывает три процесса.

### 27.6. RoutingPolicy tests

Проверить:

- forecast до resolve отклоняется;
- неизвестный locationRef отклоняется;
- article summary до search отклоняется;
- ref, отсутствующий в search result, отклоняется;
- повторный summary того же article отклоняется;
- save без forecast отклоняется;
- save с двумя summaries отклоняется;
- save с изменёнными refs отклоняется;
- save с полным evidence разрешается;
- после save все вызовы отклоняются;
- предел количества вызовов соблюдается.

### 27.7. Tool selection tests

Использовать deterministic stub ChatModel и guarded real callbacks.

Сценарии:

1. Погодный вопрос выбирает только weather tools.
2. Запрос информации о месте выбирает только guide tools.
3. Запрос сохранения без данных не приводит к реальному files tool call.
4. Полный travel report использует все три server domains.

Тест должен проверять actual invocation log, а не только финальный текст модели.

### 27.8. Full orchestration integration test

Использовать:

- настоящий `agent-app` context;
- три настоящих STDIO server jars;
- локальные HTTP stubs;
- temporary SQLite database;
- temporary reports directory;
- stub ChatModel с последовательностью семи tool calls.

Последовательность:

```text
weather_resolve_location
weather_get_forecast
guide_search_articles
guide_get_article_summary(articleRef A)
guide_get_article_summary(articleRef B)
guide_get_article_summary(articleRef C)
files_save_markdown_report
final assistant response
```

Проверить:

- задействованы три разных server names;
- locationRef второго вызова равен output первого;
- три article refs взяты из search output;
- refs уникальны;
- file tool получает тот же locationRef и три summarized refs;
- save является последним tool call;
- sequence numbers непрерывны;
- runId одинаков во всех MCP metadata;
- report создан;
- run `COMPLETED` только после `SAVED`;
- финальный ответ содержит реальный relative path.

### 27.9. Recovery/error integration tests

Проверить:

- модель вызывает tool в неправильном порядке, получает policy error и восстанавливается;
- модель завершает ответ до save — run `ORCHESTRATION_INCOMPLETE`;
- guide server падает — file server не вызывается;
- files server падает — нет ложного success;
- max total tool calls завершает run;
- один MCP child process не стартует — health DOWN;
- stale run восстанавливается как FAILED.

### 27.10. Web/SSE tests

Проверить:

- POST возвращает 202;
- invalid message возвращает 400;
- unknown run возвращает 404;
- snapshot и новые events;
- reconnect через Last-Event-ID;
- отсутствие дублей;
- terminal event завершает stream;
- разные runs не смешиваются.

### 27.11. Live smoke test

При наличии API key:

```bash
export OPENAI_API_KEY="..."
export OPENAI_MODEL="gpt-5.6-terra"
./gradlew :agent-app:bootRun
```

Открыть:

```text
http://localhost:8080
```

Запрос:

```text
Подготовь план поездки в Казань на ближайшие 3 дня. Учти погоду, выбери три достопримечательности, добавь краткие описания и сохрани отчёт в файл kazan-trip.md.
```

Проверить:

- три MCP servers initialized;
- timeline содержит минимум семь вызовов;
- вызовы относятся к трём серверам;
- references передаются без изменения;
- файл создан;
- report использует только полученные weather/facts/URLs;
- run завершён `COMPLETED`;
- все child processes закрываются при остановке agent-app.

---

## 28. Команды проверки

```bash
./gradlew clean test
```

Если создан отдельный source set:

```bash
./gradlew integrationTest
```

Сборка:

```bash
./gradlew \
  :mcp-weather-server:bootJar \
  :mcp-guide-server:bootJar \
  :mcp-files-server:bootJar \
  :agent-app:bootJar
```

Проверка jar:

```bash
ls -l mcp-weather-server/build/libs/mcp-weather-server.jar
ls -l mcp-guide-server/build/libs/mcp-guide-server.jar
ls -l mcp-files-server/build/libs/mcp-files-server.jar
```

Live-запуск:

```bash
OPENAI_API_KEY="..." \
OPENAI_MODEL="gpt-5.6-terra" \
ORCHESTRATION_DB_PATH="./data/orchestration.db" \
REPORTS_DIR="./reports" \
./gradlew :agent-app:bootRun
```

Health:

```bash
curl -fsS http://localhost:8080/actuator/health
```

Metrics:

```bash
curl -fsS http://localhost:8080/actuator/prometheus
```

Не запускать MCP server jars вручную с ожиданием завершения. STDIO server является долгоживущим процессом и должен управляться client/test.

---

## 29. README

README должен содержать:

- назначение проекта;
- архитектурную схему;
- границы трёх серверов;
- список tools по серверам;
- prerequisites;
- environment variables;
- сборку и запуск;
- пример travel request;
- описание RoutingPolicy;
- объяснение partial order;
- описание run ID и MCP metadata;
- SQLite journal и SSE timeline;
- reports directory;
- команды тестов;
- диагностику недоступного server process;
- предупреждение о стоимости OpenAI API;
- ограничения внешних APIs;
- указание, что live smoke test требует сеть и API key.

---

## 30. Ограничения

В задание не входят:

- React, Vue, Angular;
- мобильное приложение;
- authentication/authorization;
- multi-tenancy;
- произвольный filesystem access;
- пользовательская загрузка файлов;
- RAG и vector database;
- OpenAI Agents API или Agents SDK;
- Programmatic Tool Calling;
- remote/hosted MCP;
- HTTP/SSE MCP transport;
- Docker, Kubernetes;
- Kafka/RabbitMQ;
- JPA/Hibernate;
- автоматическое продолжение interrupted run;
- полноценное бронирование поездки;
- покупка билетов или другие финансовые side effects;
- раскрытие chain-of-thought.

Не добавлять production fallback с fake model. Без `OPENAI_API_KEY` offline tests работают, а live endpoint возвращает понятную configuration error.

---

## 31. Критерии приёмки

- [ ] Проект создаётся из пустого каталога.
- [ ] Используются Kotlin, Spring Boot и Gradle Kotlin DSL.
- [ ] Есть четыре независимых Gradle-модуля.
- [ ] Реализованы три отдельных MCP-сервера.
- [ ] Каждый MCP-сервер имеет уникальное server info.
- [ ] `agent-app` автоматически запускает три STDIO child processes.
- [ ] Все три connections выполняют initialize.
- [ ] Для каждого connection корректно выполняется tools/list.
- [ ] Модель видит ровно пять tools.
- [ ] Tools имеют уникальные domain-prefixed names.
- [ ] Weather tools вызывают Open-Meteo.
- [ ] Guide tools вызывают MediaWiki REST API.
- [ ] Files tool пишет только в configured directory.
- [ ] Идентификатор модели вынесен в конфигурацию.
- [ ] API key не хранится в проекте.
- [ ] Агент самостоятельно выбирает инструменты.
- [ ] Полный сценарий использует инструменты трёх серверов.
- [ ] Полный сценарий содержит минимум семь tool calls.
- [ ] `locationRef` передаётся без изменения.
- [ ] `articleRef` берутся только из search result.
- [ ] Получены summaries трёх уникальных статей.
- [ ] Save выполняется только после forecast и summaries.
- [ ] Неправильный порядок блокируется до MCP side effect.
- [ ] После policy rejection модель может исправить flow.
- [ ] Tool call limits предотвращают бесконечный цикл.
- [ ] `runId` передаётся всем серверам через MCP metadata.
- [ ] `agent-app` хранит единый межсерверный журнал SQLite.
- [ ] Timeline показывает server, tool, status и duration.
- [ ] UI получает события через SSE.
- [ ] Логи имеют correlation fields.
- [ ] Метрики не содержат high-cardinality IDs.
- [ ] Path traversal блокируется.
- [ ] Повтор save с тем же содержимым идемпотентен.
- [ ] Unit tests не используют реальные внешние API.
- [ ] MCP contract tests запускают настоящие jars через STDIO.
- [ ] Full orchestration test использует три настоящих MCP processes.
- [ ] Full orchestration test проверяет порядок и передачу references.
- [ ] Все процессы завершаются после tests/application shutdown.
- [ ] `./gradlew clean test` проходит.
- [ ] Все четыре bootJar собираются.
- [ ] При наличии API key live smoke test создаёт Markdown report.

---

## 32. Порядок реализации для OMP

1. Создать multi-module Gradle project и wrapper.
2. Настроить toolchain, BOM и зависимости.
3. Реализовать weather server и unit tests.
4. Реализовать guide server и unit tests.
5. Реализовать files server и security tests.
6. Зафиксировать стабильные jar names.
7. Добавить MCP contract tests каждого сервера.
8. Создать agent-app и SQLite schema.
9. Настроить три STDIO connections.
10. Проверить initialize/tools-list и lifecycle.
11. Добавить единственный composite `McpToolFilter`.
12. Реализовать ToolContext → MCP metadata.
13. Реализовать repositories и RoutingState.
14. Реализовать RoutingPolicy с unit tests.
15. Реализовать `GuardedToolCallback` decorator.
16. Настроить tool call limits.
17. Создать dedicated ChatClient и system prompt.
18. Реализовать bounded executor и OrchestrationService.
19. Реализовать REST API.
20. Реализовать SSE timeline.
21. Создать минимальный Web UI.
22. Добавить logs, MDC, metrics и observations.
23. Добавить recovery stale runs.
24. Реализовать tool selection tests.
25. Реализовать full orchestration integration test.
26. Реализовать negative/recovery tests.
27. Выполнить `clean test` и `integrationTest`.
28. Собрать все bootJar.
29. При наличии key выполнить live smoke test.
30. Обновить README фактическими командами и ограничениями.

OMP не должен останавливаться после scaffolding или ручного запуска долгоживущего MCP server. Требуется завершить реализацию, тесты и сборку.

---

## 33. Официальные материалы

- Spring Boot: <https://spring.io/projects/spring-boot>
- Spring Boot requirements: <https://docs.spring.io/spring-boot/system-requirements.html>
- Kotlin releases: <https://kotlinlang.org/docs/releases.html>
- Spring AI Tool Calling: <https://docs.spring.io/spring-ai/reference/api/tools.html>
- Spring AI ToolCallingAdvisor: <https://docs.spring.io/spring-ai/reference/api/tools/tool-calling-advisor.html>
- Spring AI MCP Client: <https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html>
- Spring AI MCP Server Annotations: <https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-server.html>
- Spring AI MCP metadata: <https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-special-params.html>
- Spring AI Observability: <https://docs.spring.io/spring-ai/reference/observability/>
- Spring AI OpenAI Chat: <https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html>
- OpenAI GPT-5.6 Terra: <https://developers.openai.com/api/docs/models/gpt-5.6-terra>
- OpenAI function calling: <https://developers.openai.com/api/docs/guides/function-calling>
- OpenAI tool orchestration guidance: <https://developers.openai.com/api/docs/guides/tools-programmatic-tool-calling>
- Open-Meteo Geocoding API: <https://open-meteo.com/en/docs/geocoding-api>
- Open-Meteo Forecast API: <https://open-meteo.com/en/docs>
- MediaWiki REST API: <https://www.mediawiki.org/wiki/API%3AREST_API/Reference/en>

---

## 34. Ожидаемый результат

После реализации пользователь запускает один запрос и наблюдает межсерверный flow:

```text
RUN_STARTED
  → WEATHER / weather_resolve_location
  → WEATHER / weather_get_forecast
  → GUIDE   / guide_search_articles
  → GUIDE   / guide_get_article_summary × 3
  → FILES   / files_save_markdown_report
  → RUN_COMPLETED
```

В `reports` появляется Markdown-файл, SQLite содержит единый журнал вызовов всех серверов, UI показывает live timeline, а интеграционные тесты доказывают корректный выбор, маршрутизацию, порядок и передачу данных между MCP-инструментами.
