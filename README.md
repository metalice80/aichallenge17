# Day 20 — MCP orchestration

Учебное приложение на Kotlin и Spring Boot: один пользовательский запрос запускает model-directed tool loop через три независимых MCP-сервера по STDIO. `agent-app` проверяет partial order до каждого вызова, сохраняет единый SQLite-журнал и отдаёт live timeline через SSE.

## Архитектура

```text
Browser -- HTTP/SSE --> agent-app
                         |-- ChatClient / OpenAI ChatModel
                         |-- RoutingPolicy + guarded ToolCallbacks
                         |-- SQLite journal
                         |-- STDIO --> mcp-weather-server --> Open-Meteo
                         |-- STDIO --> mcp-guide-server   --> MediaWiki REST
                         `-- STDIO --> mcp-files-server   --> reports/
```

Границы модулей:

- `mcp-weather-server` знает только Open-Meteo, выдаёт run-scoped `locationRef` и возвращает прогноз;
- `mcp-guide-server` знает только MediaWiki, выдаёт run-scoped `articleRef` и возвращает краткие описания;
- `mcp-files-server` не имеет сетевых клиентов и атомарно пишет только безопасный Markdown basename в configured directory;
- `agent-app` — единственный оркестратор, владелец policy, модели, REST/SSE и SQLite.

Agent-facing tools:

| Server | Tools |
|---|---|
| `weather-server` | `weather_resolve_location`, `weather_get_forecast` |
| `guide-server` | `guide_search_articles`, `guide_get_article_summary` |
| `files-server` | `files_save_markdown_report` |

Composite `McpToolFilter` сверяет одновременно server info и точное имя. Неизвестные серверы и tools запрещены. Tool-resolution fallback выключен.

## Порядок flow

Модель, а не заранее написанный service workflow, выбирает следующий tool. Spring AI `ToolCallingAdvisor` выполняет loop. `RoutingPolicy` восстанавливает состояние из SQLite перед каждым вызовом и допускает только зависимости:

```text
weather_resolve_location -> weather_get_forecast ----┐
                                                     ├-> files_save_markdown_report
 guide_search_articles -> 3 unique article summaries ┘
```

Weather и guide ветки независимы. Финальный side effect разрешён только после forecast и summaries трёх уникальных `articleRef`; `weatherLocationRef`, `articleRefs` и `sourceUrls` должны точно соответствовать evidence из предыдущих tool results. После `SAVED` любые tool calls запрещены. Policy rejection возвращается модели как структурированный retryable tool result, но реальный MCP callback не вызывается.

`runId` создаёт `agent-app`, помещает в Spring AI `ToolContext` и передаёт всем серверам в MCP `_meta` как `orchestrationRunId`. Он не виден модели в JSON schema. Weather/guide reference stores проверяют run ownership и TTL.

## Требования

- JDK 21;
- доступ Gradle к Maven Central;
- для live flow: сеть до OpenAI, Open-Meteo и MediaWiki и действующий `OPENAI_API_KEY`.

Node.js, Docker и ручной запуск server jars не нужны. MCP child processes запускает и закрывает Spring AI client starter.

## Сборка и тесты

```bash
./gradlew clean test

./gradlew \
  :mcp-weather-server:bootJar \
  :mcp-guide-server:bootJar \
  :mcp-files-server:bootJar \
  :agent-app:bootJar
```

Стабильные artifacts:

```text
mcp-weather-server/build/libs/mcp-weather-server.jar
mcp-guide-server/build/libs/mcp-guide-server.jar
mcp-files-server/build/libs/mcp-files-server.jar
agent-app/build/libs/agent-app.jar
```

Автоматические тесты используют локальный JDK HTTP stub, temporary SQLite и temporary reports directory. Contract tests запускают каждый настоящий jar, выполняют `initialize`, `tools/list`, schema checks и happy-path call. Full integration test поднимает настоящий `agent-app` context и три STDIO JVM processes; deterministic `ChatModel` всё равно проходит штатный Spring AI tool loop и реальные guarded MCP callbacks. Live external APIs в тестах не используются.

## Локальный запуск

Сначала задайте ключ:

```bash
export OPENAI_API_KEY="..."
export OPENAI_MODEL="gpt-5.6-terra"   # gpt-5.6-luna — экономичный override

./gradlew :agent-app:bootRun
```

Откройте <http://localhost:8080> и отправьте:

```text
Подготовь план поездки в Казань на ближайшие 3 дня. Учти погоду, выбери три достопримечательности, добавь краткие описания и сохрани отчёт в файл kazan-trip.md.
```

REST:

```text
POST /api/orchestrations
GET  /api/orchestrations/{runId}
GET  /api/orchestrations/{runId}/events?after=0&limit=100
GET  /api/orchestrations/{runId}/events/stream
GET  /api/orchestrations/{runId}/report
```

`POST` возвращает `202` сразу; bounded executor выполняет flow асинхронно. SSE поддерживает snapshot, `Last-Event-ID`, heartbeat, отсутствие дублей и завершение на terminal event.

Без `OPENAI_API_KEY` приложение может запускаться для диагностики MCP/UI, но новый run завершается стабильной `CONFIGURATION_ERROR`; production fake model и скрытого fallback нет.

## Данные и наблюдаемость

По умолчанию:

- SQLite: `./data/orchestration.db`;
- Markdown: `./reports/<safe-name>.md`;
- health: `GET /actuator/health`;
- Prometheus: `GET /actuator/prometheus`.

SQLite tables: `orchestration_run`, `tool_invocation`, `orchestration_event`. Journal работает в WAL mode, события append-only. Tool rows содержат hashes и evidence refs, но не полный prompt, внешний payload или report content. При старте stale `CREATED`/`RUNNING` runs переводятся в `FAILED / PROCESS_INTERRUPTED`; автоматического продолжения и повторного save нет.

Логи содержат `runId`, `invocationId`, `serverName`, `toolName`, `toolCallId`, `sequenceNumber`; MCP server logs направлены в stderr, stdout зарезервирован для JSON-RPC. Метрики:

```text
orchestration.runs{status}
orchestration.active
orchestration.tool.calls{server,tool,status}
orchestration.tool.duration{server,tool,status}
orchestration.routing.rejections{tool,reason}
orchestration.external.failures{server,error_code}
```

Run IDs, refs, city и filename не используются как metric labels.

## Environment variables

| Variable | Default | Назначение |
|---|---|---|
| `OPENAI_API_KEY` | empty | OpenAI secret; никогда не логируется |
| `OPENAI_MODEL` | `gpt-5.6-terra` | model ID |
| `OPENAI_REASONING_EFFORT` | `none` | `reasoning_effort` |
| `OPENAI_TIMEOUT` | `60s` | OpenAI request timeout |
| `SERVER_PORT` | `8080` | HTTP port |
| `ORCHESTRATION_DB_PATH` | `./data/orchestration.db` | SQLite file |
| `REPORTS_DIR` | `./reports` | единственный writable report root |
| `JAVA_COMMAND` | `java` | JVM executable для STDIO children |
| `WEATHER_SERVER_JAR` | module build artifact | weather jar path |
| `GUIDE_SERVER_JAR` | module build artifact | guide jar path |
| `FILES_SERVER_JAR` | module build artifact | files jar path |
| `MCP_REQUEST_TIMEOUT` | `30s` | MCP request timeout |
| `AGENT_EXECUTION_TIMEOUT` | `90s` | полный run timeout |
| `MAX_TOTAL_TOOL_CALLS` | `12` | hard loop limit |
| `ORCHESTRATION_EXECUTOR_THREADS` | `2` | bounded worker count |
| `ORCHESTRATION_QUEUE_CAPACITY` | `20` | bounded queue size |
| `STALE_RUN_TIMEOUT` | `5m` | recovery threshold |
| `EVENT_POLL_INTERVAL` | `500ms` | SSE journal poll interval |
| `SSE_TIMEOUT` | `2m` | SSE connection timeout |
| `GEOCODING_BASE_URL` | Open-Meteo | weather geocoding API; tests override |
| `FORECAST_BASE_URL` | Open-Meteo | weather forecast API; tests override |
| `MEDIAWIKI_BASE_URL` | `https://ru.wikipedia.org` | MediaWiki REST API; tests override |
| `MEDIAWIKI_PUBLIC_BASE_URL` | MediaWiki base | HTTPS source links when API is locally stubbed |
| `MEDIAWIKI_USER_AGENT` | project identifier | required MediaWiki User-Agent |
| `WEATHER_REFERENCE_TTL` | `10m` | weather reference TTL |
| `WEATHER_REFERENCE_CACHE_SIZE` | `1000` | weather cache bound |
| `GUIDE_REFERENCE_TTL` | `10m` | article reference TTL |
| `GUIDE_REFERENCE_CACHE_SIZE` | `1000` | guide cache bound |
| `GUIDE_MAX_SUMMARY_CHARACTERS` | `1500` | summary limit |
| `REPORT_MAX_CONTENT_BYTES` | `51200` | UTF-8 report size limit |

## Диагностика

Если один server jar отсутствует или не проходит `initialize/tools/list`, startup сообщает process/protocol error, health становится `DOWN`, и новые runs не принимаются. Проверьте jar path, JDK 21, stderr child process и права на `REPORTS_DIR`. Не запускайте STDIO jar вручную в foreground: процесс ожидает MCP client на stdin.

Open-Meteo и MediaWiki могут применять rate limits, менять доступность данных и возвращать неполные ответы. Ошибки преобразуются в стабильные безопасные коды без stack trace для модели/UI. Live smoke test требует сеть и расходует OpenAI API quota.

## Совместимость и отклонения

Используются версии из задания: Kotlin 2.3.21, Spring Boot 4.1.1, Spring AI 2.0.1, SQLite JDBC 3.53.4.0 и Gradle Kotlin DSL. Spring Boot 4.1 использует новые health contributor packages; код использует их фактический API. `SyncMcpToolCallback` Spring AI 2.0.1 сериализует MCP `content` как JSON array, даже когда server объявляет output schema; guarded callback нормализует этот официальный формат перед evidence validation. `MEDIAWIKI_PUBLIC_BASE_URL` добавлен только для сохранения HTTPS evidence URLs при локальном HTTP stub. Функциональных сокращений и отклонений от обязательного flow нет.
