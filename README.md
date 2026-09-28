# Weather MCP Pipeline

Учебное приложение на Kotlin и Spring Boot, в котором OpenAI-модель через настоящий Spring AI tool-calling loop автоматически выполняет цепочку:

```text
Browser ──HTTP/SSE──> agent-app ──OpenAI ChatClient──> MCP callbacks
                              └──STDIO child process──> mcp-pipeline-server
                                                        ├── Open-Meteo
                                                        ├── SQLite
                                                        └── reports/*.md
```

`mcp-pipeline-server` — единственный владелец SQLite, артефактов, журнала событий и Markdown-файлов. Он не подключается к OpenAI. `agent-app` автоматически запускает и останавливает сервер как дочерний JVM-процесс.

## Требования

- JDK 21;
- сеть для загрузки Gradle-зависимостей;
- `OPENAI_API_KEY` и доступ к OpenAI только для live-запуска;
- доступ к Open-Meteo только для live-запуска.

Автоматические тесты используют локальный HTTP stub и stub `ChatModel`; реальные OpenAI и Open-Meteo не вызываются.

## Модули

- `mcp-pipeline-server` — STDIO MCP server, Open-Meteo client, state machine, SQLite repositories, deterministic summarizer, безопасная запись файлов и recovery;
- `agent-app` — OpenAI `ChatClient`, allowlist MCP callbacks, асинхронный executor, REST/SSE API, Actuator и статический web UI.

## Agent-facing tools

Модели доступны ровно три инструмента:

1. `search_weather_forecast(city, days)` — сохраняет полный нормализованный прогноз как `SEARCH_RESULT` и возвращает компактный `artifactId`;
2. `summarize_weather_forecast(searchArtifactId)` — детерминированно создаёт Markdown `WEATHER_SUMMARY` без OpenAI;
3. `save_weather_report(summaryArtifactId, fileName)` — атомарно и безопасно сохраняет `.md`, создаёт `SAVED_REPORT` и завершает run.

Большой погодный JSON не проходит через модель. Между шагами передаются только идентификаторы сохранённых артефактов.

## Internal MCP tools

Эти инструменты вызываются приложением программно и скрыты от модели allowlist-фильтром:

- `create_pipeline_run`;
- `mark_pipeline_agent_started`;
- `fail_pipeline_run`;
- `get_pipeline_run`;
- `list_pipeline_events`;
- `get_pipeline_report`.

## Конфигурация

| Переменная | Значение по умолчанию | Назначение |
|---|---|---|
| `OPENAI_API_KEY` | пусто | секрет OpenAI; не хранится в проекте |
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` | базовый URL OpenAI-compatible API с API-префиксом |
| `OPENAI_MODEL` | `gpt-4o-mini` | идентификатор модели |
| `OPENAI_REASONING_EFFORT` | пусто | задаётся только для reasoning-моделей |
| `SERVER_PORT` | `8080` | HTTP-порт agent-app |
| `JAVA_COMMAND` | `java` | JVM для child process |
| `MCP_SERVER_JAR` | `./mcp-pipeline-server/build/libs/mcp-pipeline-server.jar` | executable MCP jar |
| `PIPELINE_DB_PATH` | `./data/pipeline.db` | SQLite-файл, которым владеет MCP server |
| `REPORTS_DIR` | `./reports` | каталог Markdown-отчётов |
| `GEOCODING_BASE_URL` | Open-Meteo production URL | geocoding endpoint |
| `FORECAST_BASE_URL` | Open-Meteo production URL | forecast endpoint |

Для production-подобного запуска рекомендуется использовать абсолютные пути для jar, базы и reports directory.

## Сборка и тесты

```bash
./gradlew clean test
./gradlew integrationTest
./gradlew :mcp-pipeline-server:bootJar :agent-app:bootJar
```

`integrationTest` запускает настоящий `mcp-pipeline-server.jar` как STDIO child process, выполняет MCP initialize/tools/list, вызывает полный pipeline с `_meta.pipelineRunId`, проверяет lineage, events и файл, а затем закрывает клиент и child process. Вторая проверка использует stub `ChatModel`, но настоящий Spring AI tool-calling loop, MCP callbacks и STDIO server.

Стабильные jar-файлы:

```text
mcp-pipeline-server/build/libs/mcp-pipeline-server.jar
agent-app/build/libs/agent-app.jar
```

## Live-запуск

Из корня проекта:

```bash
OPENAI_API_KEY="..." \
OPENAI_MODEL="gpt-4o-mini" \
PIPELINE_DB_PATH="./data/pipeline.db" \
REPORTS_DIR="./reports" \
./gradlew :agent-app:bootRun
```

Открыть <http://localhost:8080> и отправить:

```text
Найди прогноз для Новосибирска на 5 дней, подготовь краткую сводку и сохрани её в файл novosibirsk.md.
```

MCP server вручную запускать не нужно: STDIO-клиент создаёт и закрывает его автоматически. Без `OPENAI_API_KEY` приложение запускается для health/диагностики, а создание pipeline возвращает понятную ошибку `OPENAI_UNAVAILABLE`; production fake-модель отсутствует.

## HTTP API

- `POST /api/pipelines` — проверяет запрос, создаёт асинхронный run и возвращает HTTP 202;
- `GET /api/pipelines/{runId}` — run, три шага, durations, artifact IDs и безопасная ошибка;
- `GET /api/pipelines/{runId}/events?after=0&limit=100` — упорядоченные append-only events;
- `GET /api/pipelines/{runId}/events/stream` — SSE snapshot, новые events, heartbeat и resume через `Last-Event-ID`;
- `GET /api/pipelines/{runId}/report` — preview отчёта по `runId`, без пользовательского пути;
- `GET /actuator/health`;
- `GET /actuator/prometheus`.

## Correlation, состояние и recovery

`runId` генерирует MCP server после программного `create_pipeline_run`. `agent-app` помещает его в Spring AI `ToolContext`; `ToolContextToMcpMetaConverter` переносит только `pipelineRunId`, `conversationId` и `traceparent` в MCP `_meta`. Agent-facing JSON schema не содержит `runId`.

SQLite хранит:

- `pipeline_run` — состояние и optimistic `version`;
- `pipeline_step` — строгий порядок SEARCH → SUMMARY → SAVE, fingerprint и duration;
- `pipeline_artifact` — содержимое, SHA-256 и lineage через `source_artifact_id`;
- `pipeline_event` — append-only timeline с уникальным sequence number.

Повтор шага с тем же fingerprint возвращает прежний результат без повторного HTTP/file side effect. Повтор с другим input отклоняется. При старте stale runs в `CREATED`/`RUNNING` переводятся в `FAILED` с `PROCESS_INTERRUPTED`; автоматический повтор OpenAI или файлового side effect не выполняется.

## Наблюдаемость и диагностика

Логи содержат MDC-поля `runId`, `stepId`, `toolName`; MCP server пишет обычные логи только в stderr, поскольку stdout занят протоколом. Payload инструментов, полный prompt, отчёт и API key не логируются.

Метрики используют только bounded labels:

```text
pipeline.runs{status}
pipeline.active
pipeline.step.duration{tool,status}
pipeline.step.failures{tool,error_code}
pipeline.artifacts{type}
```

Micrometer observations создаются для `pipeline.run` и MCP tool execution. Spring AI content export отключён через `spring.ai.tools.observations.include-content=false`.

Если pipeline завис или упал:

1. получить `GET /api/pipelines/{runId}`;
2. прочитать events через REST или SSE;
3. найти первый `TOOL_FAILED`/`RUN_FAILED` и стабильный `errorCode`;
4. проверить stderr agent-app/MCP child по `runId`;
5. проверить `/actuator/health` и `/actuator/prometheus`;
6. после аварийного рестарта искать `RUN_RECOVERED_AS_FAILED`.

`OPENAI_MODEL_NOT_FOUND` означает HTTP 404 от OpenAI-compatible API: неверен `OPENAI_BASE_URL`, указанный `OPENAI_MODEL` не существует либо не доступен текущему API project. Для Spring AI 2.0.1 официальный base URL должен оканчиваться на `/v1`. Default model — `gpt-4o-mini`; скрытого fallback нет.

`OPENAI_AUTHENTICATION_FAILED` означает HTTP 401: `OPENAI_API_KEY` отсутствует, неверен или не подходит для API из `OPENAI_BASE_URL`.

## Безопасность файлов

Допустимо только простое имя до 120 символов со строгим расширением `.md`. Блокируются каталоги, `/`, `\\`, `..`, NUL и control characters. Нормализованный target обязан находиться внутри `REPORTS_DIR`. Файл создаётся через temporary file и atomic move; существующий файл принимается только при совпадающем SHA-256.

## Ограничения

- один локальный MCP server и один SQLite owner;
- нет авторизации, multi-tenancy и удаления отчётов через UI;
- нет retry всей агентной цепочки после рестарта;
- STDIO transport локальный, hosted MCP не реализован;
- live pipeline требует совместимой с Chat Completions/function calling модели, OpenAI API и сети;
- вызовы OpenAI платные; контролируйте выбранную модель и usage.
