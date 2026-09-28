# Day 18 — MCP Weather Scheduler

Kotlin/Spring AI multi-module приложение: погодный агент сохраняет динамические расписания в SQLite, автономно собирает данные Open-Meteo, агрегирует измерения и публикует периодические русскоязычные сводки через отдельный OpenAI-вызов.

Функциональность Дня 17 сохранена: чат, `POST /api/chat`, STDIO MCP-процесс и инструмент `get_current_weather` продолжают работать.

## Архитектура

```text
Browser → agent-app → weatherChatClient + model-facing MCP tools
   │          │
   │          ├─ SummaryPublisher → summaryChatClient (без tools)
   │          │                         │
   │          └──────── McpSyncClient ──┤
   │                                    ↕ STDIO
   └─ GET /api/schedules, /api/summaries
                                  mcp-weather-server
                                    ├─ SQLite/JdbcClient
                                    ├─ @Scheduled dispatcher
                                    └─ Open-Meteo HTTP
```

- `mcp-weather-server` единолично владеет SQLite, расписаниями, измерениями и агрегатами.
- `agent-app` не подключается к базе: чат, publisher и REST-контроллеры работают через MCP.
- MCP starter запускает `mcp-weather-server.jar` как дочерний STDIO-процесс. `WeatherSchedulerMcpClient` явно вызывает `closeGracefully` при остановке контекста; server-side parent watchdog завершает процесс, если родитель был аварийно убит.
- Dispatcher и publisher имеют отдельные однопоточные Spring scheduler-ы в своих JVM.
- HTTP Open-Meteo и OpenAI выполняются вне JDBC-транзакций.

## Версии и ограничения

- JDK 21, Kotlin 2.3.21, Gradle 8.14.3;
- Spring Boot 4.1.1, Spring AI 2.0.1;
- SQLite JDBC 3.53.4.0;
- Spring JDBC `JdbcClient`, без JPA/Hibernate;
- без Quartz, Spring Batch, cron-выражений, WebSocket/SSE и новых внешних API.

## SQLite

По умолчанию используется файловая база `weather-agent.db`. Путь переопределяется переменной `WEATHER_DB_PATH`. `schema.sql` выполняется при каждом старте, но использует только `CREATE TABLE/INDEX IF NOT EXISTS` и не удаляет данные.

Таблицы:

- `weather_schedule` — город, интервалы, статус, следующие времена запуска, lease, счётчик ошибок;
- `weather_observation` — фактические погодные измерения; `UNIQUE(schedule_id, observed_at)`;
- `weather_summary` — агрегат временного окна, claim/delivery state, число попыток и готовый `rendered_text`; `UNIQUE(schedule_id, period_started_at, period_ended_at)`.

Timestamps хранятся как ISO-8601 UTC `TEXT`; production-код использует `Instant` и внедрённый `Clock`. Forecast-запрос явно задаёт `timezone=UTC`, неожиданное будущее время observation отклоняется, а исторические future-dated observations исключаются из SQL-агрегатов без удаления данных. SQLite работает с одним connection, foreign keys, WAL и `busy_timeout=5000`.

Файлы `*.db`, `*.db-wal`, `*.db-shm` исключены из Git. Не удаляйте рабочую базу между перезапусками.

## MCP tools

Модели доступны только allowlist-инструменты:

1. `get_current_weather`;
2. `schedule_weather_summary`;
3. `get_weather_schedule_status`;
4. `get_latest_weather_summary`;
5. `cancel_weather_schedule`.

`McpToolFilter` скрывает внутренние инструменты от `weatherChatClient`, но `WeatherSchedulerMcpClient` может вызывать их напрямую:

- `claim_pending_weather_summaries`;
- `complete_weather_summary_delivery`;
- `fail_weather_summary_delivery`;
- `list_weather_schedules`;
- `list_delivered_weather_summaries`.

`schedule_weather_summary` принимает город, collection interval 1–1440 минут и summary interval 5–10080 минут. Summary interval не может быть меньше collection interval. Город нормализуется; повтор идентичного ACTIVE-расписания возвращает существующий ID.

Пример в чате:

```text
Собирай погоду в Новосибирске каждые 10 минут,
сводку делай каждый час.
```

Проверка и отмена:

```text
Покажи состояние мониторинга <scheduleId>.
Останови мониторинг <scheduleId>.
```

Отмена идемпотентна и не удаляет историю.

## Dispatcher, восстановление и retries

`WeatherScheduleDispatcher.scheduledTick()` вызывается через `@Scheduled`; основная логика доступна как `tick(now)` для детерминированных тестов.

1. Ограниченный batch читает due ACTIVE schedules.
2. Атомарный SQL update получает lease.
3. Транзакция завершается до вызова Open-Meteo.
4. Новая транзакция сохраняет observation, рассчитывает SQL-агрегат и создаёт `PENDING` summary.
5. Следующие времена считаются от текущего `now`, поэтому интервалы простоя не воспроизводятся циклом.
6. Ошибка сохраняет безопасный `last_error`, увеличивает `consecutive_failures`, освобождает lease и назначает retry.

`AtomicBoolean` исключает overlap ticks. Ошибка одного schedule не останавливает batch. После restart schedules читаются из SQLite. Просроченные `PROCESSING` summaries возвращаются в `PENDING`; уникальные constraints не допускают повторное измерение или summary-window.

## SummaryPublisher

`SummaryPublisher` раз в `app.summary-publisher.scan-delay` атомарно claims ограниченный batch `PENDING` summaries. Пустой claim завершает run без OpenAI.

Для каждого агрегата отдельный `summaryChatClient` без MCP tools:

- получает только один готовый числовой агрегат;
- не способен создавать расписания;
- генерирует краткий русский текст без изменения чисел;
- success сохраняет текст и переводит summary в `DELIVERED`;
- ошибка возвращает запись в `PENDING`, а после лимита переводит в `FAILED`;
- ошибка одной записи не мешает остальному batch.

Периодические OpenAI-вызовы расходуют средства: один вызов приходится на каждый сформированный aggregate summary, а не на scheduler tick или observation.

## REST API и UI

- `POST /api/chat` — существующий чат;
- `GET /api/schedules` — расписания и безопасное состояние;
- `GET /api/summaries?after=<ISO-8601>&limit=20` — только `DELIVERED`, максимум 100.

UI опрашивает schedules каждые 30 секунд и summaries каждые 15 секунд, использует `after`, дедуплицирует карточки и приостанавливает polling в скрытой вкладке. Все серверные строки добавляются через `textContent`. Браузер — только viewer: сбор продолжается при закрытой странице.

## Конфигурация

| Переменная | Назначение | По умолчанию |
|---|---|---|
| `OPENAI_API_KEY` | OpenAI API key | отсутствует |
| `OPENAI_MODEL` | model ID | `gpt-4.1-mini` |
| `OPENAI_BASE_URL` | OpenAI API base URL | `https://api.openai.com/v1` |
| `WEATHER_DB_PATH` | путь SQLite | `weather-agent.db` |
| `OPEN_METEO_GEOCODING_URL` | geocoding endpoint | официальный Open-Meteo |
| `OPEN_METEO_FORECAST_URL` | forecast endpoint | официальный Open-Meteo |
| `WEATHER_SCHEDULER_ENABLED` | dispatcher enabled | `true` |
| `WEATHER_SCHEDULER_SCAN_DELAY` | scan delay | `10s` |
| `WEATHER_SCHEDULER_BATCH_SIZE` | due batch | `10` |
| `WEATHER_SCHEDULER_LEASE_DURATION` | lease | `2m` |
| `WEATHER_SCHEDULER_RETRY_DELAY` | collection retry | `1m` |
| `WEATHER_STUCK_SUMMARY_TIMEOUT` | PROCESSING recovery | `5m` |
| `SUMMARY_PUBLISHER_MAX_DELIVERY_ATTEMPTS` | delivery attempts | `3` |
| `SUMMARY_PUBLISHER_ENABLED` | publisher enabled | `true` |
| `SUMMARY_PUBLISHER_SCAN_DELAY` | publisher scan | `15s` |
| `SUMMARY_PUBLISHER_BATCH_SIZE` | publisher batch | `5` |
| `MCP_SERVER_COMMAND` | Java launcher | `java` |
| `MCP_REQUEST_TIMEOUT` | timeout одного MCP-вызова; должен превышать два последовательных Open-Meteo timeout | `45s` |
| `MCP_SERVER_JAR` | server jar | `mcp-weather-server/build/libs/mcp-weather-server.jar` |

## Сборка и deterministic tests

```bash
./gradlew clean test
./gradlew integrationTest
./gradlew :mcp-weather-server:bootJar :agent-app:bootJar
```

Обычные тесты не вызывают OpenAI или реальный Open-Meteo. Scheduler-тесты используют mutable `Clock`, локальный JDK HTTP stub и отдельные SQLite-файлы в `@TempDir`. Repository-тесты проверяют schema, due query, lease, unique constraints, aggregate SQL, claim/complete/fail и reopen.

`integrationTest` запускает настоящий server jar через STDIO, работает с известной временной базой и локальным Open-Meteo stub, затем вызывает `closeGracefully`, ждёт завершения дочернего процесса и удаляет DB/WAL/SHM.

## Live-запуск

Из корня проекта; MCP jar вручную не запускается. Можно использовать короткий root task
(`mcp-weather-server:bootRun` отключён, чтобы не запускать второй scheduler рядом с дочерним STDIO-процессом):

```bash
OPENAI_API_KEY="..." \
OPENAI_MODEL="gpt-6-luna" \
WEATHER_DB_PATH="weather-agent.db" \
./gradlew bootRun
```

Откройте <http://localhost:8080>. Для live-демонстрации допустимы интервалы 1 и 5 минут. После создания проверьте `/api/schedules`, дождитесь `/api/summaries`, перезапустите приложение с тем же `WEATHER_DB_PATH`, затем отмените schedule через чат.

## Troubleshooting

- **SQLite busy/locked** — убедитесь, что одну базу не открыли несколько экземпляров приложения; дождитесь `busy_timeout`, проверьте права на каталог и место на диске.
- **Сводка остаётся `PENDING`, а `/api/schedules` зависает** — завершите ранее запущенные экземпляры и перезапустите `./gradlew bootRun`. Прямой `:mcp-weather-server:bootRun` отключён: единственный сервер должен запускаться как дочерний STDIO-процесс `agent-app`.
- **Open-Meteo error/unknown city** — проверьте URL, сеть и имя города. Ошибка сохраняется в schedule, после retry dispatcher продолжит работу.
- **OpenAI 401/429/5xx** — проверьте ключ, model access, billing и rate limits. Сбор/агрегация продолжаются; summary остаётся `PENDING` до retry или становится `FAILED` после лимита.
- **Stuck PROCESSING summary** — после `WEATHER_STUCK_SUMMARY_TIMEOUT` claim автоматически восстанавливается в `PENDING`.
- **MCP jar отсутствует** — выполните `./gradlew :mcp-weather-server:bootJar` или используйте `:agent-app:bootRun`.
- **Дочерний JVM не завершается** — текущая версия явно закрывает MCP client и дополнительно проверяет родительский PID. После обновления один раз завершите старые orphan-процессы; не запускайте server jar вручную.

## Что означает 24/7

Приложение работает без открытого браузера, сохраняет schedule/observations/summaries, восстанавливается после штатного restart и повторяет доставку. Оно не может работать при остановленной JVM. Для реального 24/7 нужен внешний supervisor: systemd, launchd, Docker restart policy или облачная платформа; настройка инфраструктуры не входит в проект.
