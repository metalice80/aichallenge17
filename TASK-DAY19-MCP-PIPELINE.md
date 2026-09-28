# День 19. Композиция MCP-инструментов: наблюдаемый погодный pipeline

## 1. Назначение

Реализовать с нуля учебное приложение на Kotlin и Spring Boot, в котором AI-агент автоматически составляет и выполняет последовательный pipeline из нескольких MCP-инструментов:

```text
search_weather_forecast
        ↓ searchArtifactId
summarize_weather_forecast
        ↓ summaryArtifactId
save_weather_report
        ↓ saved report
```

Пользователь задаёт поручение через минимальный веб-интерфейс, например:

> Найди прогноз погоды для Новосибирска на 5 дней, подготовь краткую сводку и сохрани её в файл novosibirsk.md.

Агент должен самостоятельно:

1. вызвать инструмент получения данных;
2. передать идентификатор результата в инструмент обработки;
3. передать идентификатор сводки в инструмент сохранения;
4. завершить ответ только после успешного сохранения файла.

Каждый запуск должен быть наблюдаемым: пользователь видит состояние pipeline, порядок шагов, длительность, созданные артефакты и ошибку конкретного шага.

Итог задания — работающее приложение, демонстрирующее:

- регистрацию нескольких MCP-инструментов;
- автоматическую композицию инструментов агентом;
- корректную передачу данных между инструментами;
- сохранение конечного результата в Markdown-файл;
- журнал выполнения в SQLite;
- live-отображение прогресса в браузере;
- структурированные логи, метрики и correlation ID;
- автоматические тесты pipeline.

Эта спецификация рассчитана на реализацию из пустого каталога и не зависит от проектов предыдущих дней.

---

## 2. Обязательный стек

- JDK 21;
- Kotlin 2.3.21;
- Spring Boot 4.1.1;
- Gradle Kotlin DSL;
- Spring AI 2.0.1;
- официальный Java MCP SDK, транзитивно используемый Spring AI;
- Spring AI MCP server и MCP client starters;
- STDIO transport;
- Spring AI OpenAI ChatModel и ChatClient;
- OpenAI API;
- Open-Meteo Geocoding API и Forecast API;
- Spring JDBC / `JdbcClient`;
- SQLite JDBC 3.53.4.0;
- Spring Boot Actuator и Micrometer;
- HTML, CSS и vanilla JavaScript;
- JUnit 5, Spring Boot Test и HTTP stub для интеграционных тестов.

Версии должны быть централизованы в Gradle-конфигурации. Не заменять Kotlin DSL на Maven или Groovy DSL.

### 2.1. Модель OpenAI

Идентификатор модели обязательно выносится в конфигурацию:

```yaml
app:
  agent:
    model: ${OPENAI_MODEL:gpt-5.6-luna}
    reasoning-effort: ${OPENAI_REASONING_EFFORT:none}
```

Значение по умолчанию — `gpt-5.6-luna`. Пользователь может указать другую модель через `OPENAI_MODEL`.

Spring AI 2.0.1 использует OpenAI Chat Completions для `OpenAiChatModel`. Для `gpt-5.6-luna` явно передавать `reasoning_effort=none`, чтобы получить предсказуемый и недорогой tool-calling цикл. Не задавать `temperature` для reasoning-моделей.

Если выбранная модель не поддерживает function calling через используемый endpoint, приложение должно завершаться с понятным сообщением конфигурационной ошибки либо получить документированную совместимую настройку. Не делать скрытый fallback на другую модель.

---

## 3. Prerequisites

На машине должны быть установлены:

```bash
java -version
```

Ожидается JDK 21.

Также нужны:

- доступ в интернет для Gradle;
- доступ к `api.openai.com`;
- доступ к `geocoding-api.open-meteo.com`;
- доступ к `api.open-meteo.com`;
- действующий OpenAI API key.

Перед live-запуском:

```bash
export OPENAI_API_KEY="..."
```

Node.js и `npx` для этого проекта не требуются: MCP-сервер реализуется на Kotlin и запускается как отдельный JVM child process.

Ключ API нельзя хранить в Git, `application.yaml`, тестах, логах или примерах вывода.

---

## 4. Архитектура

Создать Gradle multi-module проект:

```text
Browser
   │ HTTP + SSE
   ▼
agent-app
   ├── REST API и статический Web UI
   ├── PipelineExecutionService
   ├── Spring AI ChatClient
   ├── OpenAI ChatModel
   ├── MCP ToolCallbackProvider
   └── MCP SyncClient для внутренних запросов
            │ STDIO
            ▼
mcp-pipeline-server
   ├── 3 agent-facing MCP tools
   ├── internal observability MCP tools
   ├── Open-Meteo client
   ├── deterministic summarizer
   ├── safe file writer
   ├── SQLite repositories
   └── pipeline event journal
```

OpenAI не подключается напрямую к MCP-серверу. Цепочка выглядит так:

```text
пользователь
  → agent-app
  → OpenAI model
  → tool request
  → Spring AI tool-calling loop
  → локальный MCP client
  → STDIO
  → mcp-pipeline-server
  → tool result
  → OpenAI model
  → следующий tool request или итоговый ответ
```

### 4.1. Почему два модуля

`mcp-pipeline-server` является отдельным исполняемым MCP-сервером. Он не содержит UI и не обращается к OpenAI.

`agent-app` является веб-приложением, автоматически запускающим MCP-сервер как STDIO child process. Пользователь не должен вручную запускать сервер в отдельном терминале.

### 4.2. Источник истины

MCP-сервер является единственным владельцем:

- SQLite-базы;
- состояний pipeline;
- журнала событий;
- промежуточных артефактов;
- конечных Markdown-файлов.

`agent-app` получает состояние и события только через внутренние MCP-инструменты. Не подключать оба JVM-процесса к одной SQLite-базе напрямую.

---

## 5. Структура проекта

```text
day19-mcp-pipeline/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── gradle/
│   └── wrapper/
├── .gitignore
├── README.md
├── mcp-pipeline-server/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── kotlin/.../server/
│       │   │   ├── McpPipelineServerApplication.kt
│       │   │   ├── config/
│       │   │   │   ├── PipelineServerProperties.kt
│       │   │   │   ├── DatabaseConfiguration.kt
│       │   │   │   └── ClockConfiguration.kt
│       │   │   ├── weather/
│       │   │   │   ├── OpenMeteoClient.kt
│       │   │   │   ├── LocationResolver.kt
│       │   │   │   └── WeatherModels.kt
│       │   │   ├── pipeline/
│       │   │   │   ├── PipelineRun.kt
│       │   │   │   ├── PipelineStep.kt
│       │   │   │   ├── PipelineArtifact.kt
│       │   │   │   ├── PipelineEvent.kt
│       │   │   │   ├── PipelineStateMachine.kt
│       │   │   │   ├── PipelineObserver.kt
│       │   │   │   └── PipelineRecovery.kt
│       │   │   ├── repository/
│       │   │   │   ├── PipelineRunRepository.kt
│       │   │   │   ├── PipelineStepRepository.kt
│       │   │   │   ├── PipelineArtifactRepository.kt
│       │   │   │   └── PipelineEventRepository.kt
│       │   │   ├── tool/
│       │   │   │   ├── WeatherPipelineTools.kt
│       │   │   │   └── PipelineQueryTools.kt
│       │   │   ├── summary/
│       │   │   │   └── WeatherSummaryService.kt
│       │   │   └── file/
│       │   │       └── SafeReportWriter.kt
│       │   └── resources/
│       │       ├── application.yaml
│       │       └── schema.sql
│       └── test/
│           └── kotlin/.../server/
└── agent-app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── kotlin/.../agent/
        │   │   ├── AgentApplication.kt
        │   │   ├── config/
        │   │   │   ├── AgentProperties.kt
        │   │   │   ├── ChatClientConfiguration.kt
        │   │   │   ├── McpToolFilterConfiguration.kt
        │   │   │   └── ExecutorConfiguration.kt
        │   │   ├── pipeline/
        │   │   │   ├── PipelineExecutionService.kt
        │   │   │   ├── PipelineQueryService.kt
        │   │   │   ├── PipelineEventStreamService.kt
        │   │   │   └── PipelineDtos.kt
        │   │   └── web/
        │   │       ├── PipelineController.kt
        │   │       └── ApiExceptionHandler.kt
        │   └── resources/
        │       ├── application.yaml
        │       └── static/
        │           ├── index.html
        │           ├── app.js
        │           └── styles.css
        └── test/
            └── kotlin/.../agent/
```

Имена пакетов можно выбрать самостоятельно, но границы ответственности должны сохраняться.

---

## 6. Gradle

### 6.1. Корневой проект

`settings.gradle.kts` должен подключать оба модуля:

```kotlin
rootProject.name = "day19-mcp-pipeline"

include("mcp-pipeline-server")
include("agent-app")
```

В корневом `build.gradle.kts` централизовать версии плагинов и общие настройки:

- JVM toolchain 21;
- UTF-8;
- JUnit Platform;
- строгая обработка nullability JSR-305;
- Spring AI BOM 2.0.1;
- единый стиль Kotlin compiler options.

### 6.2. `mcp-pipeline-server`

Минимальные зависимости:

```kotlin
implementation("org.springframework.ai:spring-ai-starter-mcp-server")
implementation("org.springframework.boot:spring-boot-starter")
implementation("org.springframework.boot:spring-boot-starter-jdbc")
implementation("org.springframework.boot:spring-boot-starter-validation")
implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
runtimeOnly("org.xerial:sqlite-jdbc:3.53.4.0")

testImplementation("org.springframework.boot:spring-boot-starter-test")
```

Для вызова Open-Meteo использовать Spring `RestClient`. Если он требует web dependency, подключить минимальный servlet/web starter, но сервер всё равно должен запускаться с `web-application-type: none`.

Имя executable jar должно быть стабильным:

```text
mcp-pipeline-server.jar
```

### 6.3. `agent-app`

Минимальные зависимости:

```kotlin
implementation("org.springframework.boot:spring-boot-starter-web")
implementation("org.springframework.boot:spring-boot-starter-validation")
implementation("org.springframework.boot:spring-boot-starter-actuator")
implementation("org.springframework.ai:spring-ai-starter-model-openai")
implementation("org.springframework.ai:spring-ai-starter-mcp-client")
implementation("io.micrometer:micrometer-registry-prometheus")
implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

testImplementation("org.springframework.boot:spring-boot-starter-test")
```

Если добавляется OTLP exporter, он должен быть опциональным и включаться конфигурацией. Для прохождения задания внешний Jaeger/Tempo не обязателен.

### 6.4. Зависимость сборки

Запуск и интеграционные тесты `agent-app` должны зависеть от задачи:

```text
:mcp-pipeline-server:bootJar
```

Нельзя предполагать, что jar MCP-сервера уже был собран вручную.

---

## 7. Конфигурация MCP-сервера

`mcp-pipeline-server/src/main/resources/application.yaml`:

```yaml
spring:
  application:
    name: mcp-pipeline-server
  main:
    web-application-type: none
    banner-mode: off
  ai:
    mcp:
      server:
        name: weather-pipeline-server
        version: 1.0.0
        type: SYNC
        stdio: true
  datasource:
    url: jdbc:sqlite:${PIPELINE_DB_PATH:./data/pipeline.db}
    driver-class-name: org.sqlite.JDBC
  sql:
    init:
      mode: always

app:
  reports:
    directory: ${REPORTS_DIR:./reports}
  weather:
    geocoding-base-url: ${GEOCODING_BASE_URL:https://geocoding-api.open-meteo.com}
    forecast-base-url: ${FORECAST_BASE_URL:https://api.open-meteo.com}
    connect-timeout: 3s
    read-timeout: 10s
  pipeline:
    stale-run-timeout: 5m

logging:
  pattern:
    console: "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level runId=%X{runId:-} stepId=%X{stepId:-} tool=%X{toolName:-} %logger{36} - %msg%n"
```

Для STDIO transport протокольные сообщения идут через stdout. Поэтому приложение не должно писать обычные логи, banner или `println` в stdout.

Настроить логирование MCP-сервера в stderr. Любой диагностический вывод — только через logger.

При старте сервер должен:

1. создать родительский каталог базы;
2. создать каталог отчётов;
3. привести оба пути к абсолютному нормализованному виду;
4. применить `schema.sql`;
5. выполнить recovery зависших запусков;
6. зарегистрировать MCP tools.

---

## 8. Конфигурация agent-app

`agent-app/src/main/resources/application.yaml`:

```yaml
server:
  port: ${SERVER_PORT:8080}

spring:
  application:
    name: day19-agent-app
  ai:
    openai:
      api-key: ${OPENAI_API_KEY:}
      chat:
        model: ${OPENAI_MODEL:gpt-5.6-luna}
        reasoning-effort: ${OPENAI_REASONING_EFFORT:none}
    mcp:
      client:
        type: SYNC
        request-timeout: 30s
        stdio:
          connections:
            pipeline-server:
              command: ${JAVA_COMMAND:java}
              args:
                - -jar
                - ${MCP_SERVER_JAR:../mcp-pipeline-server/build/libs/mcp-pipeline-server.jar}
              env:
                PIPELINE_DB_PATH: ${PIPELINE_DB_PATH:./data/pipeline.db}
                REPORTS_DIR: ${REPORTS_DIR:./reports}
                GEOCODING_BASE_URL: ${GEOCODING_BASE_URL:https://geocoding-api.open-meteo.com}
                FORECAST_BASE_URL: ${FORECAST_BASE_URL:https://api.open-meteo.com}

app:
  agent:
    model: ${OPENAI_MODEL:gpt-5.6-luna}
    reasoning-effort: ${OPENAI_REASONING_EFFORT:none}
    timeout: 90s
  pipeline:
    executor-threads: 2
    event-poll-interval: 500ms
    sse-timeout: 2m

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus

logging:
  level:
    root: INFO
    org.springframework.ai: INFO
```

Относительный путь к jar зависит от working directory. Реализация должна выбрать один документированный режим запуска и проверить существование jar до создания MCP-соединения. При отсутствии файла выдавать понятное сообщение с командой сборки.

В production-подобном запуске рекомендуется передавать абсолютные значения `MCP_SERVER_JAR`, `PIPELINE_DB_PATH` и `REPORTS_DIR`.

---

## 9. База SQLite

Включить:

```sql
PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
PRAGMA busy_timeout = 5000;
```

Не использовать JPA/Hibernate. Для задания достаточно `JdbcClient`, SQL и явных repository-классов.

Все timestamps хранить в UTC в ISO-8601 или epoch milliseconds. В коде внедрять `Clock`, а не вызывать `Instant.now()` в доменной логике напрямую.

### 9.1. `pipeline_run`

Поля:

```text
id TEXT PRIMARY KEY
request_text TEXT NOT NULL
status TEXT NOT NULL
current_step TEXT
model_id TEXT
created_at TEXT NOT NULL
started_at TEXT
updated_at TEXT NOT NULL
finished_at TEXT
result_file TEXT
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

### 9.2. `pipeline_step`

Поля:

```text
id TEXT PRIMARY KEY
run_id TEXT NOT NULL
sequence_number INTEGER NOT NULL
tool_name TEXT NOT NULL
status TEXT NOT NULL
input_artifact_id TEXT
output_artifact_id TEXT
request_fingerprint TEXT
started_at TEXT
finished_at TEXT
duration_ms INTEGER
attempt INTEGER NOT NULL DEFAULT 1
error_code TEXT
error_message TEXT
FOREIGN KEY(run_id) REFERENCES pipeline_run(id)
UNIQUE(run_id, sequence_number)
```

Статусы:

```text
PENDING
RUNNING
SUCCEEDED
FAILED
SKIPPED
```

### 9.3. `pipeline_artifact`

Поля:

```text
id TEXT PRIMARY KEY
run_id TEXT NOT NULL
type TEXT NOT NULL
source_artifact_id TEXT
media_type TEXT NOT NULL
content TEXT NOT NULL
sha256 TEXT NOT NULL
created_at TEXT NOT NULL
FOREIGN KEY(run_id) REFERENCES pipeline_run(id)
FOREIGN KEY(source_artifact_id) REFERENCES pipeline_artifact(id)
```

Типы:

```text
SEARCH_RESULT
WEATHER_SUMMARY
SAVED_REPORT
```

### 9.4. `pipeline_event`

Поля:

```text
id TEXT PRIMARY KEY
run_id TEXT NOT NULL
step_id TEXT
sequence_number INTEGER NOT NULL
event_type TEXT NOT NULL
occurred_at TEXT NOT NULL
payload_json TEXT NOT NULL
FOREIGN KEY(run_id) REFERENCES pipeline_run(id)
FOREIGN KEY(step_id) REFERENCES pipeline_step(id)
UNIQUE(run_id, sequence_number)
```

События:

```text
RUN_CREATED
RUN_STARTED
AGENT_STARTED
TOOL_REQUESTED
TOOL_STARTED
TOOL_SUCCEEDED
TOOL_FAILED
ARTIFACT_CREATED
FILE_SAVED
RUN_COMPLETED
RUN_FAILED
RUN_RECOVERED_AS_FAILED
```

События являются append-only. Нельзя обновлять или удалять уже записанные события в обычном ходе работы.

Изменение текущего состояния и добавление соответствующего события должны выполняться в одной транзакции.

---

## 10. Correlation и MCP metadata

`runId` генерируется приложением, а не моделью.

Алгоритм начала запуска:

1. `agent-app` программно вызывает скрытый MCP tool `create_pipeline_run`;
2. MCP-сервер создаёт запись и возвращает `runId`;
3. `agent-app` вызывает `ChatClient`, добавляя `runId` через `ToolContext`;
4. `ToolContextToMcpMetaConverter` переносит `runId` в `_meta` каждого MCP `tools/call`;
5. MCP tools читают metadata через специальный параметр `McpMeta` или `McpSyncRequestContext`;
6. `runId` не включается в JSON schema agent-facing tools и не виден модели как редактируемый аргумент.

Передавать минимум:

```text
pipelineRunId
conversationId
traceparent, если tracing включён
```

На сервере обязательна проверка:

- `pipelineRunId` присутствует;
- run существует;
- run не находится в terminal state;
- вызываемый шаг допустим для текущего состояния.

При отсутствии metadata возвращать структурированную ошибку `MISSING_PIPELINE_CONTEXT`.

---

## 11. Agent-facing MCP tools

Модели доступны ровно три инструмента.

Все инструменты должны:

- использовать `@McpTool` и `@McpToolParam`;
- иметь подробное английское описание, понятное модели;
- генерировать output schema для DTO-результатов;
- возвращать структурированный результат;
- валидировать вход;
- фиксировать step и events;
- добавлять `runId`, `stepId`, `toolName` в MDC на время выполнения;
- никогда не возвращать stack trace пользователю или модели.

### 11.1. `search_weather_forecast`

Назначение: найти город и получить нормализованный прогноз.

Входная schema:

```json
{
  "city": "Новосибирск",
  "days": 5
}
```

Ограничения:

- `city`: обязательная непустая строка, максимум 120 символов;
- `days`: целое число от 1 до 7.

Инструмент:

1. разрешает город через Open-Meteo Geocoding API;
2. если результатов нет, возвращает `CITY_NOT_FOUND`;
3. использует первый однозначный результат;
4. получает daily forecast;
5. нормализует даты и числовые значения;
6. сохраняет полный JSON как `SEARCH_RESULT`;
7. вычисляет SHA-256 по каноническому содержимому;
8. возвращает компактный DTO.

Запрашиваемые daily-поля:

```text
weather_code
temperature_2m_min
temperature_2m_max
precipitation_sum
precipitation_probability_max
wind_speed_10m_max
```

Результат:

```json
{
  "runId": "run-123",
  "stepId": "step-1",
  "artifactId": "search-456",
  "artifactType": "SEARCH_RESULT",
  "city": "Новосибирск",
  "country": "Россия",
  "timezone": "Asia/Novosibirsk",
  "days": 5,
  "recordCount": 5,
  "sha256": "...",
  "status": "SEARCHED",
  "nextTool": "summarize_weather_forecast"
}
```

Описание инструмента должно явно говорить модели: после успеха передай `artifactId` в `summarize_weather_forecast` без изменения.

### 11.2. `summarize_weather_forecast`

Назначение: обработать сохранённые данные прогноза и создать детерминированный Markdown.

Вход:

```json
{
  "searchArtifactId": "search-456"
}
```

Инструмент обязан проверить:

- артефакт существует;
- принадлежит текущему `runId`;
- имеет тип `SEARCH_RESULT`;
- первый шаг завершён успешно;
- второй шаг ещё не завершён либо вызов является безопасным повтором.

Вычислить:

- минимальную температуру периода;
- максимальную температуру периода;
- среднее дневных минимумов и максимумов;
- суммарные осадки;
- число дней с осадками;
- день с максимальной вероятностью осадков;
- максимальный ветер;
- самый холодный и самый тёплый день.

Сформировать Markdown следующего типа:

```markdown
# Прогноз погоды: Новосибирск

Период: 2026-09-28 — 2026-10-02

## Краткая сводка

- Температура: от ... до ... °C
- Средняя температура: ... °C
- Осадки: ... мм
- Дней с осадками: ...
- Максимальный ветер: ... км/ч

## По дням

| Дата | Мин. | Макс. | Осадки | Вероятность | Ветер |
|---|---:|---:|---:|---:|---:|
```

Не вызывать OpenAI из MCP-сервера для составления сводки. Результат должен быть воспроизводимым и тестируемым.

Сохранить Markdown как `WEATHER_SUMMARY`, указав `source_artifact_id`.

Результат:

```json
{
  "runId": "run-123",
  "stepId": "step-2",
  "artifactId": "summary-789",
  "sourceArtifactId": "search-456",
  "artifactType": "WEATHER_SUMMARY",
  "title": "Прогноз погоды: Новосибирск",
  "preview": "Температура от ...",
  "sha256": "...",
  "status": "SUMMARIZED",
  "nextTool": "save_weather_report"
}
```

### 11.3. `save_weather_report`

Назначение: безопасно сохранить Markdown-артефакт в файл.

Вход:

```json
{
  "summaryArtifactId": "summary-789",
  "fileName": "novosibirsk.md"
}
```

Правила безопасности:

- разрешено только простое имя файла без каталогов;
- расширение строго `.md`;
- запретить `/`, `\\`, `..`, null byte и control characters;
- после `resolve(...).normalize()` путь обязан начинаться с configured reports directory;
- максимальная длина имени — 120 символов;
- файл не перезаписывать, если его содержимое отличается;
- если файл уже существует и SHA-256 совпадает, считать повтор идемпотентным;
- запись выполнять во временный файл в том же каталоге;
- финализация — atomic move, если файловая система поддерживает;
- при отсутствии atomic move использовать безопасный fallback и зафиксировать это в логах.

Проверить, что артефакт принадлежит текущему run и имеет тип `WEATHER_SUMMARY`.

После успешной записи:

- создать артефакт `SAVED_REPORT`;
- записать `FILE_SAVED`;
- перевести run в `COMPLETED`;
- записать `RUN_COMPLETED`.

Результат:

```json
{
  "runId": "run-123",
  "stepId": "step-3",
  "sourceArtifactId": "summary-789",
  "reportArtifactId": "report-012",
  "fileName": "novosibirsk.md",
  "relativePath": "reports/novosibirsk.md",
  "sizeBytes": 1842,
  "sha256": "...",
  "status": "SAVED"
}
```

Описание инструмента должно явно запрещать модели сообщать об успехе до получения `status=SAVED`.

---

## 12. Внутренние MCP tools

Следующие инструменты вызываются программно через `McpSyncClient` и не передаются модели:

### `create_pipeline_run`

Вход:

```json
{
  "requestText": "...",
  "modelId": "gpt-5.6-luna"
}
```

Создаёт run со статусом `CREATED`, три шага `PENDING` и событие `RUN_CREATED`.

### `mark_pipeline_agent_started`

Переводит run в `RUNNING`, пишет `RUN_STARTED` и `AGENT_STARTED`.

### `fail_pipeline_run`

Используется agent-app, если ошибка произошла до или вне конкретного MCP tool: OpenAI timeout, отказ API, неполная цепочка, shutdown.

Повторный вызов для terminal run не должен затирать исходную причину.

### `get_pipeline_run`

Возвращает run и список шагов без содержимого больших артефактов.

### `list_pipeline_events`

Вход:

```json
{
  "runId": "run-123",
  "afterSequence": 12,
  "limit": 100
}
```

Возвращает события строго по возрастанию sequence number.

### `get_pipeline_report`

Опциональный внутренний инструмент, возвращающий сохранённый Markdown для preview/download. Ограничить размер результата.

Все internal tools должны иметь явный префикс в описании: `INTERNAL — never expose to the language model`.

---

## 13. Фильтрация инструментов

Создать `McpToolFilter`, который разрешает модели только:

```text
search_weather_forecast
summarize_weather_forecast
save_weather_report
```

В тесте проверить, что среди `ToolCallback`, переданных `pipelineChatClient`, отсутствуют:

```text
create_pipeline_run
mark_pipeline_agent_started
fail_pipeline_run
get_pipeline_run
list_pipeline_events
get_pipeline_report
```

Для защиты от будущих случайно добавленных tools использовать allowlist, а не denylist.

Не включать fallback, позволяющий модели вызвать tool по имени через глобальный resolver, если tool отсутствует в request callbacks.

---

## 14. State machine

Допустимая последовательность:

```text
CREATED
  → RUNNING
  → SEARCH SUCCEEDED
  → SUMMARY SUCCEEDED
  → SAVE SUCCEEDED
  → COMPLETED
```

Любая необработанная ошибка переводит текущий step и run в `FAILED`.

Нельзя:

- запустить summary без успешного search;
- передать search artifact другого run;
- сохранить search artifact вместо summary;
- вызвать save до summary;
- изменить terminal run;
- перейти из `FAILED` обратно в `RUNNING`;
- пометить run `COMPLETED`, если хотя бы один обязательный шаг не `SUCCEEDED`.

`PipelineStateMachine` должен быть отдельным классом с unit-тестами.

### 14.1. Идемпотентность

Модель или клиент могут повторить вызов.

Для каждого шага вычислять `request_fingerprint` из нормализованного входа и run ID.

Если шаг уже `SUCCEEDED` и fingerprint совпадает:

- не повторять внешний API или запись файла;
- вернуть предыдущий результат;
- записать диагностическое событие повторного использования, если оно добавлено в enum.

Если fingerprint отличается, возвращать `STEP_ALREADY_COMPLETED_WITH_DIFFERENT_INPUT`.

---

## 15. PipelineObserver

Создать единый компонент, оборачивающий выполнение каждого MCP tool:

```kotlin
pipelineObserver.execute(runId, stepName, inputFingerprint) {
    // business operation
}
```

Обёртка должна:

1. проверить state transition;
2. создать или активировать step;
3. записать `TOOL_REQUESTED` и `TOOL_STARTED`;
4. заполнить MDC;
5. запустить Micrometer Observation;
6. измерить duration;
7. выполнить бизнес-операцию;
8. сохранить output artifact;
9. отметить step `SUCCEEDED`;
10. записать `ARTIFACT_CREATED` и `TOOL_SUCCEEDED`;
11. при исключении сохранить безопасный error code;
12. отметить step и run `FAILED`;
13. записать `TOOL_FAILED` и `RUN_FAILED`;
14. очистить MDC в `finally`.

Не дублировать эту логику в каждом инструменте.

---

## 16. Агент и автоматическая композиция

Создать отдельный `pipelineChatClient`, которому передаются только три agent-facing MCP callbacks.

Системная инструкция:

```text
You are a weather report pipeline agent.

When the user asks to find weather data, summarize it, and save a report,
you must complete exactly this dependency chain:

1. Call search_weather_forecast.
2. Read artifactId from the successful result.
3. Call summarize_weather_forecast with that exact artifactId.
4. Read artifactId from the successful summary result.
5. Call save_weather_report with that exact artifactId and the requested safe .md file name.
6. Return a final success message only after save_weather_report returns status SAVED.

Never invent, shorten, translate, or otherwise modify artifact identifiers.
Never skip a step.
Never claim that a report was saved if the save tool did not succeed.
If any tool returns an error, stop the chain and explain which stage failed.
```

`PipelineExecutionService`:

1. валидирует пользовательский запрос;
2. вызывает `create_pipeline_run`;
3. возвращает `runId` HTTP-клиенту;
4. запускает агентную работу в bounded executor;
5. вызывает `mark_pipeline_agent_started`;
6. передаёт `pipelineRunId` через `ToolContext`;
7. выполняет `pipelineChatClient.prompt(...).call()`;
8. после финального ответа повторно читает run;
9. если run не `COMPLETED`, вызывает `fail_pipeline_run` с кодом `PIPELINE_INCOMPLETE`;
10. сохраняет или логирует финальный ответ агента без помещения полного prompt в обычные INFO-логи.

Не реализовывать основной happy path обычным последовательным вызовом трёх сервисов из `PipelineExecutionService`. Цель задания — автоматический tool-calling loop модели.

При этом state machine MCP-сервера должна гарантировать корректность независимо от поведения модели.

---

## 17. Асинхронный запуск

`POST /api/pipelines` не должен удерживать HTTP-соединение на время всей OpenAI/MCP цепочки.

Использовать выделенный bounded `TaskExecutor`:

- configurable pool size;
- понятные thread names;
- ограниченная queue capacity;
- обработчик rejection;
- graceful shutdown;
- не использовать `ForkJoinPool.commonPool()`.

При переполнении очереди вернуть HTTP 429 или 503 и не оставлять run в состоянии `CREATED`. Если run уже создан, перевести его в `FAILED` с `EXECUTOR_REJECTED`.

---

## 18. Web API

### `POST /api/pipelines`

Запрос:

```json
{
  "message": "Найди прогноз для Новосибирска на 5 дней, подготовь сводку и сохрани в novosibirsk.md"
}
```

Ограничения:

- обязательная непустая строка;
- максимум 2000 символов.

Ответ HTTP 202:

```json
{
  "runId": "run-123",
  "status": "CREATED",
  "statusUrl": "/api/pipelines/run-123",
  "eventsUrl": "/api/pipelines/run-123/events/stream"
}
```

### `GET /api/pipelines/{runId}`

Возвращает:

- статус запуска;
- current step;
- три шага;
- длительность;
- результат или безопасную ошибку;
- относительный путь сохранённого файла.

Для неизвестного ID вернуть 404.

### `GET /api/pipelines/{runId}/events?after=0&limit=100`

Возвращает упорядоченный список событий. Проверять границы `limit`.

### `GET /api/pipelines/{runId}/events/stream`

SSE endpoint:

- немедленно отправляет событие `snapshot`;
- затем отправляет новые события по sequence number;
- не дублирует события после reconnect;
- поддерживает `Last-Event-ID`;
- завершает stream после terminal event;
- отправляет heartbeat comment;
- освобождает scheduler/task при disconnect или timeout.

Допускается реализовать SSE как polling внутреннего MCP tool каждые 500 ms. Busy loop запрещён.

### `GET /api/pipelines/{runId}/report`

Опционально возвращает Markdown для завершённого pipeline. Не разрешать произвольный путь пользователя. Поиск только через `runId` и сохранённый artifact.

---

## 19. Web UI

Создать простой одностраничный интерфейс без React/Vue и сборщика frontend.

Интерфейс содержит:

- textarea с пользовательским поручением;
- готовый пример запроса;
- кнопку «Запустить pipeline»;
- отображение `runId`;
- общий статус;
- timeline из трёх обязательных шагов;
- длительность каждого шага;
- идентификаторы входного и выходного артефакта;
- итоговый путь к файлу;
- понятную ошибку;
- preview отчёта, если endpoint реализован.

Пример timeline:

```text
✓ Получение прогноза       1.24 с
✓ Подготовка сводки        0.04 с
● Сохранение файла         выполняется
○ Завершение
```

Требования:

- запуск через `fetch`;
- события через `EventSource`;
- reconnect при временном разрыве;
- disable кнопки только до получения HTTP 202, после чего можно создать другой run;
- разные runs не смешиваются;
- пользовательский и серверный текст вставлять через `textContent`, не через `innerHTML`;
- не отображать stack traces;
- интерфейс должен быть usable на узком экране.

---

## 20. Наблюдаемость

Наблюдаемость состоит из четырёх независимых уровней.

### 20.1. Domain journal

SQLite `pipeline_run`, `pipeline_step`, `pipeline_artifact`, `pipeline_event` — основной источник истины для пользователя и тестов.

Даже если logs или tracing недоступны, по базе должно быть возможно понять:

- что запускалось;
- какие tools были вызваны;
- в каком порядке;
- что было передано между шагами;
- сколько занял каждый шаг;
- где произошла ошибка;
- какой файл создан.

### 20.2. Структурированные логи

Каждая важная запись содержит:

```text
event
runId
stepId
toolName
status
durationMs
inputArtifactId
outputArtifactId
errorCode
```

Пример:

```json
{
  "event": "pipeline.step.completed",
  "runId": "run-123",
  "stepId": "step-2",
  "toolName": "summarize_weather_forecast",
  "status": "SUCCEEDED",
  "durationMs": 38,
  "inputArtifactId": "search-456",
  "outputArtifactId": "summary-789"
}
```

Допускается plain-text console pattern с key-value полями. JSON logger не обязателен для минимальной реализации.

Не логировать API key, полный prompt, tool payload целиком или содержимое отчёта.

### 20.3. Метрики

Минимальные custom metrics:

```text
pipeline.runs{status}
pipeline.active
pipeline.step.duration{tool,status}
pipeline.step.failures{tool,error_code}
pipeline.artifacts{type}
```

В Prometheus они могут получить стандартные преобразованные имена.

Не использовать `runId`, `stepId`, city, fileName или error message как metric labels из-за высокой cardinality.

Spring AI observations для chat и tool calls оставить включёнными. Содержимое аргументов и результатов в tracing по умолчанию не включать:

```yaml
spring:
  ai:
    tools:
      observations:
        include-content: false
```

Если фактический property path версии отличается, использовать документированный путь Spring AI 2.0.1 и покрыть binding тестом.

### 20.4. Tracing

Создать observations/spans:

```text
pipeline.run
├── agent.turn
│   ├── openai.request
│   ├── mcp.search_weather_forecast
│   │   └── openmeteo.http
│   ├── openai.request
│   ├── mcp.summarize_weather_forecast
│   ├── openai.request
│   └── mcp.save_weather_report
│       └── file.write
└── pipeline.complete
```

Базовая реализация должна работать без внешнего collector. OTLP export включается только при наличии соответствующих environment variables.

Не считать OpenAI Agents API tracing частью обязательной реализации: приложение использует Spring AI ChatClient, поэтому собственный domain journal и Micrometer tracing остаются обязательными.

---

## 21. Recovery

При старте MCP-сервера найти runs:

```text
status IN (CREATED, RUNNING)
updated_at < now - staleRunTimeout
```

Перевести их в `FAILED`:

```text
error_code = PROCESS_INTERRUPTED
error_message = Pipeline was interrupted before completion
```

Активный step также перевести в `FAILED` и записать `RUN_RECOVERED_AS_FAILED`.

Не пытаться автоматически повторно обратиться к OpenAI или повторить file side effect после рестарта в рамках этого задания.

---

## 22. Обработка ошибок

Использовать стабильные error codes:

```text
VALIDATION_ERROR
MISSING_PIPELINE_CONTEXT
PIPELINE_NOT_FOUND
INVALID_PIPELINE_STATE
CITY_NOT_FOUND
GEOCODING_UNAVAILABLE
FORECAST_UNAVAILABLE
INVALID_ARTIFACT
ARTIFACT_FROM_ANOTHER_RUN
STEP_ALREADY_COMPLETED_WITH_DIFFERENT_INPUT
UNSAFE_FILE_NAME
FILE_ALREADY_EXISTS
FILE_WRITE_FAILED
OPENAI_UNAVAILABLE
OPENAI_TIMEOUT
PIPELINE_INCOMPLETE
EXECUTOR_REJECTED
PROCESS_INTERRUPTED
INTERNAL_ERROR
```

Ошибки, возвращаемые модели и браузеру, должны содержать:

```json
{
  "code": "CITY_NOT_FOUND",
  "message": "City was not found",
  "retryable": false,
  "runId": "run-123",
  "step": "SEARCH"
}
```

Stack trace остаётся только в stderr logs.

Не включать в error message API key, абсолютный внутренний путь базы или полное тело ответа внешнего API.

---

## 23. Тестирование

Автоматические тесты не должны использовать реальные OpenAI и Open-Meteo API.

### 23.1. Unit tests MCP-сервера

Проверить:

- `PipelineStateMachine` разрешает только допустимые переходы;
- summarizer правильно считает агрегаты;
- Markdown стабилен для фиксированного input;
- SHA-256 воспроизводим;
- filenames валидируются;
- path traversal блокируется;
- atomic write/idempotent повтор;
- artifact другого run отклоняется;
- `Clock` управляет timestamps и durations.

### 23.2. Repository tests

Использовать отдельную временную SQLite-базу на тест.

Проверить:

- применение schema;
- foreign keys;
- уникальный event sequence;
- транзакционность state + event;
- сохранение lineage артефактов;
- optimistic update через `version` или эквивалентную защиту;
- параллельные runs не смешиваются;
- recovery зависшего run.

### 23.3. Open-Meteo client tests

Использовать локальный HTTP stub.

Сценарии:

- успешный geocoding и forecast;
- город не найден;
- timeout;
- HTTP 429;
- HTTP 5xx;
- malformed JSON;
- неполные массивы daily data.

### 23.4. MCP integration test

Тест должен:

1. собрать настоящий `mcp-pipeline-server.jar`;
2. запустить его как child process через STDIO;
3. выполнить initialize;
4. выполнить tools/list;
5. убедиться, что три agent-facing и internal tools зарегистрированы;
6. вызвать `create_pipeline_run`;
7. вызвать три tools последовательно, передавая `pipelineRunId` через MCP metadata;
8. проверить artifact lineage;
9. проверить порядок events;
10. проверить созданный Markdown-файл;
11. закрыть client и убедиться, что child process завершён.

Open-Meteo в этом тесте заменяется локальным HTTP stub через environment variables.

### 23.5. Tool filter test

Проверить точное множество tools, доступных модели:

```text
search_weather_forecast
summarize_weather_forecast
save_weather_report
```

Никакой internal tool не должен попадать в prompt модели.

### 23.6. Agent orchestration test

Использовать fake/stub `ChatModel`, воспроизводящий последовательность:

```text
assistant → search tool call
tool → search result
assistant → summarize tool call
tool → summary result
assistant → save tool call
tool → saved result
assistant → final answer
```

Проверить:

- tools вызваны ровно в нужном порядке;
- `searchArtifactId` второго вызова равен output первого;
- `summaryArtifactId` третьего вызова равен output второго;
- у всех вызовов одинаковый `pipelineRunId` в metadata;
- итоговый ответ появляется после `SAVED`;
- run имеет `COMPLETED`;
- при раннем финальном ответе run становится `FAILED/PIPELINE_INCOMPLETE`;
- при ошибке второго tool третий не вызывается.

Не подменять MCP tools обычными локальными mock-методами в главном integration test. Допустим stub OpenAI transport/model, но реальные MCP callbacks и STDIO server обязательны.

### 23.7. Web tests

Проверить:

- POST возвращает 202 и runId;
- пустой/слишком длинный message возвращает 400;
- unknown run возвращает 404;
- events упорядочены;
- SSE отдаёт snapshot и новые events;
- reconnect по Last-Event-ID не дублирует события;
- terminal event закрывает stream;
- executor rejection корректно завершает run.

### 23.8. Observability tests

Проверить:

- runId присутствует в MDC во время tool execution;
- MDC очищается после выполнения;
- duration записывается;
- custom metrics увеличиваются;
- metric tags не содержат high-cardinality IDs;
- tool payload content не экспортируется по умолчанию;
- события сохраняются даже при ошибке.

### 23.9. Live smoke test

Выполняется вручную при наличии ключа:

```bash
export OPENAI_API_KEY="..."
export OPENAI_MODEL="gpt-5.6-luna"
./gradlew :mcp-pipeline-server:bootJar :agent-app:bootRun
```

Открыть:

```text
http://localhost:8080
```

Отправить:

```text
Найди прогноз для Новосибирска на 5 дней, подготовь краткую сводку и сохрани её в файл novosibirsk.md.
```

Проверить:

- UI сразу получает runId;
- timeline обновляется без перезагрузки;
- шаги идут строго search → summarize → save;
- видны durations и artifact IDs;
- итоговый run `COMPLETED`;
- файл существует в reports directory;
- файл содержит данные выбранного города;
- child MCP process завершается вместе с agent-app.

---

## 24. Команды проверки

```bash
./gradlew clean test
```

Если integration tests выделены в отдельный source set:

```bash
./gradlew integrationTest
```

Сборка jar:

```bash
./gradlew :mcp-pipeline-server:bootJar :agent-app:bootJar
```

Проверка имени MCP jar:

```bash
ls -l mcp-pipeline-server/build/libs/mcp-pipeline-server.jar
```

Запуск:

```bash
OPENAI_API_KEY="..." \
OPENAI_MODEL="gpt-5.6-luna" \
PIPELINE_DB_PATH="./data/pipeline.db" \
REPORTS_DIR="./reports" \
./gradlew :agent-app:bootRun
```

Проверка health:

```bash
curl -fsS http://localhost:8080/actuator/health
```

Проверка Prometheus metrics:

```bash
curl -fsS http://localhost:8080/actuator/prometheus
```

Не запускать `mcp-pipeline-server.jar` вручную и не ждать его завершения: STDIO MCP server является долгоживущим процессом и ожидает команды клиента.

---

## 25. README

README должен содержать:

- назначение проекта;
- архитектурную схему;
- prerequisites;
- список environment variables;
- команды тестов, сборки и запуска;
- описание трёх agent-facing tools;
- список internal tools;
- пример пользовательского запроса;
- описание SQLite и reports directory;
- объяснение `runId`, events и SSE;
- способы диагностики зависшего или упавшего pipeline;
- ограничения;
- предупреждение о стоимости OpenAI API;
- предупреждение, что Open-Meteo и OpenAI требуют сети для live smoke test.

---

## 26. Ограничения

В задание не входят:

- React, Vue, Angular или frontend build pipeline;
- авторизация и роли пользователей;
- multi-tenancy;
- загрузка файлов пользователем;
- произвольный доступ к файловой системе;
- собственная поисковая система;
- RAG и vector database;
- вызов OpenAI из MCP-сервера;
- hosted/remote MCP;
- SSE/HTTP MCP transport;
- Docker, Kubernetes, Kafka, RabbitMQ;
- Quartz, Spring Batch;
- JPA/Hibernate;
- retry всей агентной цепочки после рестарта;
- изменение или удаление созданных отчётов через UI;
- OpenAI Agents SDK или Agents API;
- раскрытие chain-of-thought модели.

Не добавлять fallback mock-agent в production profile. При отсутствии `OPENAI_API_KEY` live agent endpoint должен вернуть понятную configuration error; offline tests продолжают работать со stub/fake model.

---

## 27. Критерии приёмки

- [ ] Проект создаётся из пустого каталога и использует Gradle Kotlin DSL.
- [ ] Есть отдельные модули `mcp-pipeline-server` и `agent-app`.
- [ ] MCP-сервер запускается agent-app автоматически через STDIO.
- [ ] MCP initialize и tools/list выполняются успешно.
- [ ] Зарегистрированы три agent-facing MCP tools.
- [ ] У каждого tool есть понятное описание и JSON schema параметров.
- [ ] Модель видит только три agent-facing tools.
- [ ] Идентификатор модели вынесен в конфигурацию.
- [ ] API key читается только из environment/config secret.
- [ ] Агент автоматически выполняет search → summarize → save.
- [ ] Первый tool получает реальные данные Open-Meteo.
- [ ] Второй tool детерминированно обрабатывает сохранённые данные.
- [ ] Третий tool безопасно сохраняет Markdown.
- [ ] Между шагами передаются artifact IDs, а не переписанный моделью большой JSON.
- [ ] Сервер проверяет принадлежность artifact текущему run.
- [ ] Неправильный порядок tool calls отклоняется.
- [ ] `runId` создаётся приложением и передаётся через MCP metadata.
- [ ] SQLite хранит runs, steps, artifacts и append-only events.
- [ ] State change и event сохраняются транзакционно.
- [ ] UI получает HTTP 202 до завершения pipeline.
- [ ] UI показывает live timeline через SSE.
- [ ] Для каждого шага отображаются status и duration.
- [ ] Ошибка привязана к конкретному шагу.
- [ ] Структурированные логи содержат runId и stepId.
- [ ] Метрики не используют high-cardinality labels.
- [ ] Tool arguments/results не экспортируются в tracing по умолчанию.
- [ ] После рестарта stale run переводится в FAILED.
- [ ] Path traversal и небезопасные filenames блокируются.
- [ ] Повтор идентичного успешного шага идемпотентен.
- [ ] Unit и integration tests проходят без реального OpenAI API.
- [ ] MCP integration test использует настоящий STDIO child process.
- [ ] Agent orchestration test подтверждает порядок и передачу artifact IDs.
- [ ] `./gradlew clean test` завершается успешно.
- [ ] Оба bootJar собираются.
- [ ] При наличии API key live smoke test создаёт реальный Markdown-файл.
- [ ] При завершении agent-app MCP child process корректно закрывается.

---

## 28. Порядок реализации для OMP

1. Создать multi-module Gradle проект и wrapper.
2. Настроить версии, toolchain и зависимости.
3. Реализовать `mcp-pipeline-server` в STDIO-режиме.
4. Добавить SQLite schema и repositories.
5. Реализовать state machine и `PipelineObserver`.
6. Реализовать Open-Meteo client и его HTTP stub tests.
7. Реализовать `search_weather_forecast`.
8. Реализовать deterministic summarizer.
9. Реализовать `summarize_weather_forecast`.
10. Реализовать `SafeReportWriter`.
11. Реализовать `save_weather_report`.
12. Реализовать internal query/control MCP tools.
13. Собрать стабильный MCP server jar.
14. Создать `agent-app` и STDIO connection.
15. Реализовать `ToolContextToMcpMetaConverter` и чтение metadata сервером.
16. Добавить tool allowlist и тест фильтра.
17. Создать dedicated `pipelineChatClient` и system prompt.
18. Реализовать bounded executor и `PipelineExecutionService`.
19. Реализовать REST API.
20. Реализовать SSE polling bridge через internal MCP tools.
21. Создать минимальный Web UI.
22. Добавить structured logging, metrics и observations.
23. Добавить recovery stale runs.
24. Реализовать unit, repository и web tests.
25. Реализовать real-STDIO MCP integration test.
26. Реализовать agent orchestration test со stub ChatModel.
27. Выполнить `clean test`, integration tests и сборку bootJar.
28. При наличии ключа выполнить live smoke test.
29. Дополнить README фактическими командами и известными ограничениями.

OMP не должен останавливать работу на ручном запуске MCP server: это долгоживущий STDIO-процесс. Его запускает тест или agent-app и обязательно закрывает после проверки.

---

## 29. Официальные справочные материалы

- Spring Boot: <https://spring.io/projects/spring-boot>
- Spring Boot system requirements: <https://docs.spring.io/spring-boot/system-requirements.html>
- Kotlin releases: <https://kotlinlang.org/docs/releases.html>
- Spring AI Tool Calling: <https://docs.spring.io/spring-ai/reference/api/tools.html>
- Spring AI MCP Client Starter: <https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html>
- Spring AI MCP Server Annotations: <https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-server.html>
- Spring AI MCP special parameters and metadata: <https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-special-params.html>
- Spring AI Observability: <https://docs.spring.io/spring-ai/reference/observability/>
- Spring AI OpenAI Chat: <https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html>
- OpenAI function calling: <https://developers.openai.com/api/docs/guides/function-calling>
- OpenAI model catalog: <https://developers.openai.com/api/docs/models>
- OpenAI tracing concepts: <https://developers.openai.com/api/docs/guides/agents-api/tracing>
- Open-Meteo Geocoding API: <https://open-meteo.com/en/docs/geocoding-api>
- Open-Meteo Forecast API: <https://open-meteo.com/en/docs>

---

## 30. Ожидаемый результат

После реализации пользователь открывает браузер, отправляет одно поручение и наблюдает выполнение:

```text
RUN_CREATED
  → search_weather_forecast SUCCEEDED
  → summarize_weather_forecast SUCCEEDED
  → save_weather_report SUCCEEDED
  → RUN_COMPLETED
```

В каталоге `reports` появляется Markdown-файл, SQLite содержит полный lineage и журнал событий, а автоматические тесты доказывают корректность порядка и передачи данных между MCP-инструментами.
