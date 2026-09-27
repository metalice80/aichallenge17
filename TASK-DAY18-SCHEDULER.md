# День 18. Планировщик и фоновые задачи: периодические погодные сводки

## 1. Назначение задания

Расширить приложение Дня 17 и реализовать долговременные периодические задачи через MCP.

Пользователь должен иметь возможность написать агенту:

```text
Собирай погоду в Новосибирске каждые 10 минут
и формируй сводку каждый час.
```

После этого система должна без дальнейшего участия пользователя:

1. сохранить расписание в SQLite;
2. периодически получать погоду через Open-Meteo;
3. сохранять измерения;
4. агрегировать данные за заданный период;
5. формировать человекочитаемую сводку через агента;
6. сохранять опубликованную сводку;
7. автоматически показывать новые сводки в веб-интерфейсе;
8. восстанавливать расписания после перезапуска.

Итоговый результат — агентное приложение, способное работать длительное время, выполнять сохранённые фоновые задачи и регулярно выдавать сводки.

## 2. Исходное состояние

Задача является продолжением проекта Дня 17.

Ожидается существующий Gradle multi-module проект:

```text
day17-mcp-weather-agent/
├── mcp-weather-server/
└── agent-app/
```

В проекте уже должны работать:

- собственный STDIO MCP-сервер;
- инструмент `get_current_weather`;
- получение текущей погоды через Open-Meteo;
- автоматический запуск server jar из `agent-app`;
- OpenAI `ChatClient` с MCP tools;
- `POST /api/chat`;
- минимальный веб-интерфейс.

Не пересоздавать проект и не ломать функциональность Дня 17. Если имена пакетов или классов немного отличаются, адаптировать реализацию к фактической структуре, сохранив требования этой спецификации.

## 3. Обязательный стек

Сохранить версии и технологии проекта Дня 17:

- Kotlin `2.3.21`;
- Spring Boot `4.1.1`;
- Spring AI `2.0.1`;
- JDK 21;
- Gradle Kotlin DSL;
- MCP STDIO transport;
- OpenAI Chat Model;
- Open-Meteo API;
- Spring MVC;
- HTML/CSS/vanilla JavaScript;
- JUnit 5.

Добавить:

- Spring Scheduling;
- Spring JDBC и `JdbcClient`;
- SQLite;
- `org.xerial:sqlite-jdbc:3.53.4.0`;
- файловую схему SQL через `schema.sql`.

Не добавлять Quartz, Spring Batch, JPA или Hibernate: для учебной однопроцессной задачи достаточно собственного диспетчера поверх Spring Scheduling и SQLite.

## 4. Архитектура

```text
Пользователь
  → POST /api/chat
  → Weather Agent
  → MCP tool: schedule_weather_summary
  → MCP Weather Server
  → SQLite: weather_schedule

Weather Schedule Dispatcher
  → находит задачи с наступившим next_collection_at
  → Open-Meteo
  → SQLite: weather_observation
  → при наступившем next_summary_at рассчитывает агрегат
  → SQLite: weather_summary(PENDING)

Agent Summary Publisher
  → через внутренний MCP tool забирает PENDING summaries
  → вызывает отдельный OpenAI ChatClient без tools
  → сохраняет готовый текст через MCP
  → weather_summary(DELIVERED)

Web UI
  → периодически получает расписания и DELIVERED summaries
  → показывает новую сводку пользователю
```

Ключевое разделение ответственности:

- MCP-сервер владеет SQLite, расписаниями, измерениями и агрегатами;
- `agent-app` не подключается к SQLite напрямую;
- `agent-app` отвечает за LLM и веб-интерфейс;
- взаимодействие между модулями происходит только через MCP;
- фоновые измерения не вызывают OpenAI;
- OpenAI вызывается только при появлении готовой агрегированной сводки.

## 5. Границы задачи

### Входит в задачу

- динамическое создание периодического расписания через MCP;
- хранение расписаний и данных в SQLite;
- периодический сбор погоды;
- агрегирование измерений;
- формирование текстовой сводки агентом;
- сохранение и публикация сводки;
- просмотр состояния расписаний;
- отмена расписания;
- восстановление после перезапуска;
- безопасная повторная обработка ошибок;
- отображение фоновых сводок в существующем UI;
- unit-, repository-, MCP- и scheduler-тесты.

### Не входит в задачу

Не добавлять:

- новый отдельный сервис или новый Gradle-модуль;
- готовый внешний scheduler;
- Quartz или Spring Batch;
- PostgreSQL/MySQL;
- распределённый кластер scheduler-ов;
- WebSocket или SSE;
- push-уведомления, email, Telegram, Slack;
- новые внешние API, кроме уже используемого Open-Meteo;
- новые LLM-провайдеры;
- RAG, embeddings или vector database;
- пользовательские cron-выражения;
- произвольное исполнение команд;
- удаление существующего инструмента `get_current_weather`;
- пересоздание базы при каждом запуске;
- вызов OpenAI при каждом scheduler tick или погодном измерении.

## 6. Пользовательский сценарий

### Создание расписания

Пользователь:

```text
Собирай погоду в Новосибирске каждые 10 минут,
сводку делай каждый час.
```

Модель должна вызвать:

```text
schedule_weather_summary(
  city="Novosibirsk",
  collectionIntervalMinutes=10,
  summaryIntervalMinutes=60
)
```

Ответ агента:

```text
Мониторинг создан. Идентификатор: <scheduleId>.
Первый сбор начнётся в ближайшее время, сводка будет формироваться каждый час.
```

### Автономная работа

После создания расписания:

- браузер может быть закрыт;
- пользовательские запросы не требуются;
- MCP-сервер продолжает сбор, пока работает `agent-app`;
- сводки сохраняются в SQLite;
- после повторного открытия страницы ранее созданные сводки доступны.

### Отмена

Пользователь:

```text
Останови мониторинг <scheduleId>.
```

Модель вызывает `cancel_weather_schedule`.

## 7. MCP-инструменты

### 7.1. Инструменты, доступные модели

Модель должна видеть только следующие инструменты:

1. `get_current_weather` — существующий инструмент Дня 17;
2. `schedule_weather_summary`;
3. `get_weather_schedule_status`;
4. `get_latest_weather_summary`;
5. `cancel_weather_schedule`.

### 7.2. Внутренние MCP-инструменты

Для фонового издателя в `agent-app` дополнительно реализовать:

1. `claim_pending_weather_summaries`;
2. `complete_weather_summary_delivery`;
3. `fail_weather_summary_delivery`;
4. `list_weather_schedules`;
5. `list_delivered_weather_summaries`.

Эти инструменты вызываются программно через `McpSyncClient` и не должны передаваться модели как tool callbacks.

### 7.3. Фильтрация инструментов для модели

В `agent-app` создать `McpToolFilter`, разрешающий только agent-facing allowlist:

```kotlin
private val allowedAgentTools = setOf(
    "get_current_weather",
    "schedule_weather_summary",
    "get_weather_schedule_status",
    "get_latest_weather_summary",
    "cancel_weather_schedule",
)
```

Фильтр должен возвращать `true` только для имён из allowlist. Автоконфигурация Spring AI применяет `McpToolFilter` к `SyncMcpToolCallbackProvider`, но не запрещает прямой программный вызов внутренних инструментов через `McpSyncClient`.

Добавить тест, проверяющий, что внутренние инструменты отсутствуют среди callbacks, доступных `ChatClient`.

## 8. Инструмент `schedule_weather_summary`

### Входная схема

```json
{
  "type": "object",
  "properties": {
    "city": {
      "type": "string",
      "description": "Город для мониторинга"
    },
    "collectionIntervalMinutes": {
      "type": "integer",
      "description": "Интервал сбора погодных данных в минутах",
      "minimum": 1,
      "maximum": 1440
    },
    "summaryIntervalMinutes": {
      "type": "integer",
      "description": "Интервал формирования сводки в минутах",
      "minimum": 5,
      "maximum": 10080
    }
  },
  "required": [
    "city",
    "collectionIntervalMinutes",
    "summaryIntervalMinutes"
  ],
  "additionalProperties": false
}
```

Дополнительная бизнес-валидация:

- `city` после trim не пустой и не длиннее 120 символов;
- `summaryIntervalMinutes >= collectionIntervalMinutes`;
- интервал сводки должен позволять получить минимум одно измерение;
- для одного города нельзя незаметно создавать полностью идентичные ACTIVE-расписания;
- повторный запрос с теми же параметрами должен вернуть существующее активное расписание либо понятную ошибку, а не создать дубликат.

### Результат

```json
{
  "scheduleId": "UUID",
  "city": "Novosibirsk",
  "status": "ACTIVE",
  "collectionIntervalMinutes": 10,
  "summaryIntervalMinutes": 60,
  "nextCollectionAt": "2026-09-28T11:10:00Z",
  "nextSummaryAt": "2026-09-28T12:00:00Z"
}
```

Все timestamps передавать в ISO-8601 UTC.

## 9. Остальные agent-facing инструменты

### `get_weather_schedule_status`

Вход:

```json
{ "scheduleId": "UUID" }
```

Результат содержит:

- статус;
- город;
- интервалы;
- `nextCollectionAt`;
- `nextSummaryAt`;
- `lastCollectionAt`;
- число сохранённых измерений;
- краткую информацию о последней ошибке без stack trace.

### `get_latest_weather_summary`

Вход:

```json
{ "scheduleId": "UUID" }
```

Возвращает последнюю DELIVERED-сводку с агрегатами и текстом агента. Если готовой сводки ещё нет, возвращает понятный статус `NOT_READY`, а не фиктивные значения.

### `cancel_weather_schedule`

Вход:

```json
{ "scheduleId": "UUID" }
```

Переводит расписание в `CANCELLED`. Уже сохранённые измерения и сводки не удаляются.

Повторная отмена должна быть идемпотентной.

## 10. SQLite

### 10.1. Зависимости MCP-сервера

Добавить в `mcp-weather-server/build.gradle.kts`:

```kotlin
implementation("org.springframework.boot:spring-boot-starter-jdbc")
runtimeOnly("org.xerial:sqlite-jdbc:3.53.4.0")
```

Использовать `JdbcClient` и явные SQL-запросы. Не добавлять ORM.

### 10.2. Конфигурация

```yaml
spring:
  datasource:
    url: jdbc:sqlite:${WEATHER_DB_PATH:weather-agent.db}
    driver-class-name: org.sqlite.JDBC
    hikari:
      maximum-pool-size: 1
      minimum-idle: 1
      connection-timeout: 5000
  sql:
    init:
      mode: always
      schema-locations: classpath:schema.sql

app:
  scheduler:
    enabled: true
    scan-delay: 10s
    batch-size: 10
    lease-duration: 2m
    retry-delay: 1m
    stuck-summary-timeout: 5m
```

Использование файлового пути должно позволять переопределить базу через `WEATHER_DB_PATH`.

Файл базы, `*.db`, `*.db-shm` и `*.db-wal` добавить в `.gitignore`.

### 10.3. `schema.sql`

Создать таблицы через `CREATE TABLE IF NOT EXISTS`.

#### `weather_schedule`

Обязательные поля:

```text
id TEXT PRIMARY KEY
city TEXT NOT NULL
normalized_city TEXT NOT NULL
collection_interval_minutes INTEGER NOT NULL
summary_interval_minutes INTEGER NOT NULL
status TEXT NOT NULL
next_collection_at TEXT NOT NULL
next_summary_at TEXT NOT NULL
last_collection_at TEXT NULL
lease_owner TEXT NULL
lease_until TEXT NULL
consecutive_failures INTEGER NOT NULL DEFAULT 0
last_error TEXT NULL
created_at TEXT NOT NULL
updated_at TEXT NOT NULL
```

Допустимые статусы:

```text
ACTIVE
PAUSED
CANCELLED
```

Добавить индекс по `(status, next_collection_at)`.

#### `weather_observation`

```text
id TEXT PRIMARY KEY
schedule_id TEXT NOT NULL
observed_at TEXT NOT NULL
temperature_celsius REAL NOT NULL
apparent_temperature_celsius REAL NOT NULL
wind_speed_kmh REAL NOT NULL
weather_code INTEGER NOT NULL
created_at TEXT NOT NULL
FOREIGN KEY(schedule_id) REFERENCES weather_schedule(id)
UNIQUE(schedule_id, observed_at)
```

Добавить индекс по `(schedule_id, observed_at)`.

#### `weather_summary`

```text
id TEXT PRIMARY KEY
schedule_id TEXT NOT NULL
period_started_at TEXT NOT NULL
period_ended_at TEXT NOT NULL
sample_count INTEGER NOT NULL
min_temperature_celsius REAL NOT NULL
max_temperature_celsius REAL NOT NULL
avg_temperature_celsius REAL NOT NULL
max_wind_speed_kmh REAL NOT NULL
latest_weather_code INTEGER NOT NULL
delivery_status TEXT NOT NULL
delivery_attempts INTEGER NOT NULL DEFAULT 0
claimed_by TEXT NULL
claimed_at TEXT NULL
rendered_text TEXT NULL
last_delivery_error TEXT NULL
created_at TEXT NOT NULL
delivered_at TEXT NULL
FOREIGN KEY(schedule_id) REFERENCES weather_schedule(id)
UNIQUE(schedule_id, period_started_at, period_ended_at)
```

Допустимые delivery statuses:

```text
PENDING
PROCESSING
DELIVERED
FAILED
```

Добавить индексы по `(delivery_status, created_at)` и `(schedule_id, period_ended_at)`.

### 10.4. Время

- хранить timestamps как ISO-8601 UTC `TEXT`;
- во всём production-коде использовать `Instant`;
- внедрить `Clock` bean вместо прямых вызовов `Instant.now()`;
- в тестах использовать фиксируемый или изменяемый `Clock`;
- не использовать локальную timezone для расчёта интервалов.

## 11. Репозитории

Реализовать небольшие JDBC-репозитории:

- `WeatherScheduleRepository`;
- `WeatherObservationRepository`;
- `WeatherSummaryRepository`.

Не объединять весь SQL в одном огромном классе.

Репозитории должны поддерживать:

- создание и поиск расписания;
- поиск due schedules ограниченным batch;
- атомарное получение lease;
- обновление следующего времени запуска;
- сохранение измерения;
- агрегирующий SQL;
- создание summary с защитой от дублей;
- claim pending summaries;
- complete/fail delivery;
- list schedules;
- list delivered summaries;
- отмену расписания.

Не держать транзакцию открытой во время HTTP-вызова Open-Meteo или OpenAI-вызова.

## 12. Планировщик MCP-сервера

### 12.1. Конфигурация

Добавить `@EnableScheduling` и отдельный scheduler bean с одним worker thread для MCP-сервера.

Один поток выбран намеренно:

- SQLite имеет одного writer;
- исключается параллельное выполнение одного расписания;
- поведение проще тестировать;
- распределённый режим не входит в задачу.

### 12.2. Dispatcher

Реализовать:

```kotlin
@Scheduled(fixedDelayString = "\${app.scheduler.scan-delay:10s}")
fun scheduledTick() {
    tick(clock.instant())
}
```

Всю основную логику поместить в отдельно вызываемый `tick(now: Instant)`, чтобы тесты могли запускать её без ожидания реального времени.

Алгоритм:

1. найти ACTIVE-расписания с `next_collection_at <= now`;
2. атомарно получить lease для одного расписания;
3. зафиксировать транзакцию;
4. вызвать Open-Meteo вне транзакции;
5. в новой транзакции сохранить observation;
6. обновить `last_collection_at`;
7. рассчитать следующий `next_collection_at`;
8. если `next_summary_at <= now`, агрегировать окно;
9. создать `weather_summary(PENDING)`;
10. рассчитать следующий `next_summary_at`;
11. сбросить lease и счётчик ошибок.

При ошибке:

- записать краткий `last_error`;
- увеличить `consecutive_failures`;
- освободить lease;
- назначить следующий запуск через `retry-delay`;
- продолжить обработку других расписаний;
- не останавливать scheduler thread.

### 12.3. Восстановление после простоя

Если приложение было выключено несколько часов:

- выполнить одно актуальное измерение после запуска;
- не воспроизводить все пропущенные collection intervals;
- построить сводку только по реально существующим observations;
- следующее время рассчитывать относительно `now`, а не циклом от старого значения;
- PENDING summaries сохранить для издателя;
- PROCESSING summaries старше `stuck-summary-timeout` вернуть в PENDING.

## 13. Агрегация

Вычислять агрегат SQL-запросом по временному окну:

```sql
SELECT
    COUNT(*) AS sample_count,
    MIN(temperature_celsius) AS min_temperature,
    MAX(temperature_celsius) AS max_temperature,
    AVG(temperature_celsius) AS avg_temperature,
    MAX(wind_speed_kmh) AS max_wind_speed
FROM weather_observation
WHERE schedule_id = :scheduleId
  AND observed_at >= :periodStartedAt
  AND observed_at < :periodEndedAt
```

Дополнительно получить `weather_code` последнего observation в окне.

Агрегированный результат:

```json
{
  "summaryId": "UUID",
  "scheduleId": "UUID",
  "city": "Novosibirsk",
  "periodStartedAt": "2026-09-28T11:00:00Z",
  "periodEndedAt": "2026-09-28T12:00:00Z",
  "sampleCount": 6,
  "minTemperatureCelsius": 6.8,
  "maxTemperatureCelsius": 9.1,
  "avgTemperatureCelsius": 8.2,
  "maxWindSpeedKmh": 18.4,
  "latestWeatherCode": 3
}
```

Если в окне нет measurements, не создавать фиктивную сводку. Записать диагностическое событие и перейти к следующему окну.

Округление выполнять только при отображении. В базе хранить исходные числовые значения.

## 14. Внутренние MCP-инструменты доставки

### `claim_pending_weather_summaries`

Вход:

```json
{
  "workerId": "agent-instance-id",
  "limit": 5
}
```

Поведение:

- атомарно выбрать PENDING summaries;
- перевести их в PROCESSING;
- записать `claimed_by`, `claimed_at`;
- увеличить `delivery_attempts`;
- вернуть агрегаты;
- один summary не должен одновременно выдаваться двум worker-ам.

### `complete_weather_summary_delivery`

Вход:

```json
{
  "summaryId": "UUID",
  "workerId": "agent-instance-id",
  "renderedText": "За последний час..."
}
```

Переводит PROCESSING → DELIVERED только при совпадении `workerId`.

### `fail_weather_summary_delivery`

Вход:

```json
{
  "summaryId": "UUID",
  "workerId": "agent-instance-id",
  "error": "Краткое безопасное описание"
}
```

Для первых попыток возвращает summary в PENDING. После настраиваемого максимума попыток переводит в FAILED.

Не сохранять stack trace в SQLite.

## 15. Фоновый издатель в `agent-app`

### 15.1. Конфигурация

```yaml
app:
  summary-publisher:
    enabled: true
    scan-delay: 15s
    batch-size: 5
    max-delivery-attempts: 3
```

Добавить `@EnableScheduling` в `agent-app` и отдельный однопоточный scheduler для издателя.

### 15.2. Прямой MCP client

Инжектировать список `McpSyncClient`, созданный MCP client starter. В проекте ожидается ровно одно соединение с `weather-mcp-server`.

При выборе клиента:

- проверить server info после initialize;
- не выбирать клиент только по позиции без проверки;
- завершить startup с понятной ошибкой при отсутствии или неоднозначности клиента.

Создать адаптер `WeatherSchedulerMcpClient`, скрывающий низкоуровневые `callTool` и преобразование JSON.

### 15.3. Отдельный ChatClient

Существующий `weatherChatClient` содержит MCP tools и обслуживает пользователя.

Для форматирования агрегатов создать отдельный `summaryChatClient`:

- без MCP tools;
- с тем же сконфигурированным model ID;
- с отдельным system prompt;
- не способный создавать новые расписания;
- получающий только агрегат одного summary.

System prompt:

```text
Ты формируешь краткую погодную сводку по уже рассчитанным данным.
Не изменяй числа, не добавляй отсутствующие факты и не вызывай инструменты.
Укажи город, период, диапазон температуры, среднюю температуру,
максимальный ветер и число измерений. Отвечай на русском языке.
```

### 15.4. Алгоритм publisher

```kotlin
@Scheduled(fixedDelayString = "\${app.summary-publisher.scan-delay:15s}")
fun publishPendingSummaries() {
    publishBatch()
}
```

`publishBatch()`:

1. вызвать `claim_pending_weather_summaries`;
2. если список пуст, завершиться без обращения к OpenAI;
3. для каждого summary вызвать `summaryChatClient`;
4. проверить непустой ответ;
5. вызвать `complete_weather_summary_delivery`;
6. при ошибке вызвать `fail_weather_summary_delivery`;
7. продолжить обработку остальных summaries.

Не вызывать OpenAI, когда новых сводок нет.

Не держать JDBC-транзакцию: `agent-app` не владеет базой.

## 16. Web API

Сохранить `POST /api/chat` и добавить:

### `GET /api/schedules`

Возвращает активные и недавно отменённые расписания:

```json
{
  "schedules": [
    {
      "id": "UUID",
      "city": "Novosibirsk",
      "status": "ACTIVE",
      "collectionIntervalMinutes": 10,
      "summaryIntervalMinutes": 60,
      "nextCollectionAt": "...",
      "nextSummaryAt": "...",
      "lastCollectionAt": "..."
    }
  ]
}
```

### `GET /api/summaries`

Query parameters:

- `after` — optional ISO-8601 timestamp;
- `limit` — default 20, maximum 100.

Возвращает только DELIVERED summaries, включая `renderedText` и числовой агрегат.

Контроллеры должны вызывать MCP через `WeatherSchedulerMcpClient`, а не читать SQLite.

Ошибки MCP возвращать в существующем безопасном формате без stack trace.

## 17. Web UI

Расширить существующую страницу двумя блоками.

### Активные расписания

Показывать:

- город;
- статус;
- интервалы;
- следующее измерение;
- следующую сводку;
- последнюю ошибку в безопасной форме;
- идентификатор расписания.

Создание и отмена выполняются через обычный чат с агентом. Отдельная административная форма не обязательна.

### Периодические сводки

Показывать карточки:

- город;
- период;
- человекочитаемый `renderedText`;
- min/max/avg temperature;
- max wind;
- sample count;
- время публикации.

Frontend должен:

- опрашивать `/api/schedules` каждые 30 секунд;
- опрашивать `/api/summaries` каждые 15 секунд;
- использовать `after` для получения только новых записей;
- не дублировать уже показанные summaries;
- при временной ошибке продолжать polling с ограниченной задержкой;
- прекращать polling при скрытии страницы и возобновлять при возвращении;
- добавлять текст только через `textContent`, не `innerHTML`.

Если браузер закрыт, сбор и создание summaries продолжаются. UI является только способом просмотра.

## 18. Конкурентность и идемпотентность

Даже для однопроцессного проекта реализовать базовые гарантии:

- один dispatcher thread;
- один publisher thread;
- lease на schedule;
- claim на summary;
- уникальное временное окно summary;
- идемпотентная отмена;
- отсутствие повторной публикации DELIVERED summary;
- повторная попытка FAILED/PENDING согласно правилам;
- `AtomicBoolean` или аналогичная защита от наложения двух ticks в одном процессе;
- HTTP/LLM-вызовы выполняются вне транзакций.

Не реализовывать распределённые блокировки и несколько экземпляров приложения.

## 19. Обработка ошибок

Обработать:

- неизвестный город;
- Open-Meteo timeout или 4xx/5xx;
- SQLite busy/locked;
- повреждённый или неожиданный ответ API;
- приложение остановилось в середине collection;
- приложение остановилось при PROCESSING summary;
- OpenAI недоступен;
- OpenAI rate limit;
- пустой ответ модели;
- MCP timeout;
- неверный schedule ID;
- повторное создание одинакового расписания;
- повторная отмена;
- отсутствие measurements в summary window.

Ошибка одного расписания не должна останавливать остальные расписания или scheduler целиком.

Если OpenAI недоступен, сбор и агрегация должны продолжаться, а summary оставаться доступным для повторной доставки.

## 20. Логирование

### MCP server — только stderr

Логировать:

- startup и путь к SQLite без секретов;
- создание/отмену расписания;
- начало и завершение scheduler tick на DEBUG;
- schedule ID и город при collection;
- длительность Open-Meteo вызова;
- создание summary;
- ошибки и число попыток;
- восстановление stuck summary;
- shutdown.

### Agent app

Логировать:

- старт SummaryPublisher;
- число claimed summaries;
- OpenAI-вызов только при наличии pending summary;
- успешную публикацию summary ID;
- ошибку публикации;
- shutdown.

Не логировать:

- OpenAI API key;
- полный prompt и полный ответ модели на INFO;
- сырые MCP JSON-RPC сообщения на INFO;
- stack trace более одного раза;
- произвольные персональные данные.

## 21. Тестирование

### 21.1. Unit-тесты

Проверить:

- валидацию интервалов;
- нормализацию города;
- расчёт `nextCollectionAt`;
- расчёт `nextSummaryAt`;
- поведение после длительного простоя;
- отсутствие replay всех пропущенных запусков;
- переходы статусов;
- retry policy;
- форматирование агрегата для LLM prompt;
- пустой claim не вызывает `summaryChatClient`.

Использовать fake/mutable `Clock`, не `Thread.sleep`.

### 21.2. Repository-тесты

Использовать временный SQLite-файл, уникальный для теста.

Проверить:

- применение `schema.sql`;
- сохранение и чтение schedule;
- due query;
- lease;
- сохранение observation;
- unique observation;
- агрегирующий SQL;
- unique summary window;
- claim pending summaries;
- complete/fail delivery;
- list delivered summaries;
- отмену без удаления данных;
- повторное открытие того же DB-файла сохраняет данные.

После теста удалить только созданный временный файл и его `-wal`/`-shm` companions.

### 21.3. Scheduler integration test

Без реального ожидания минут:

1. создать schedule;
2. использовать локальный Open-Meteo HTTP stub;
3. установить fake clock на due time;
4. вызвать `dispatcher.tick(now)` напрямую;
5. проверить observation;
6. продвинуть clock до summary time;
7. вызвать tick снова;
8. проверить агрегат и PENDING summary;
9. создать новый application context на том же временном DB-файле;
10. проверить восстановление schedule и pending summary.

Не обращаться к реальному Open-Meteo.

### 21.4. MCP integration test

Запустить реальный `mcp-weather-server.jar` через STDIO и проверить:

- `tools/list` содержит все новые инструменты;
- `schedule_weather_summary` создаёт запись;
- `get_weather_schedule_status` возвращает её;
- повторное создание не создаёт дубликат;
- `cancel_weather_schedule` работает идемпотентно;
- internal claim/complete tools меняют delivery status;
- client и child process корректно закрываются.

Использовать временный `WEATHER_DB_PATH` и stub Open-Meteo URLs.

### 21.5. Agent-tool filter test

Проверить, что `SyncMcpToolCallbackProvider` отдаёт модели только allowlist и скрывает:

- `claim_pending_weather_summaries`;
- `complete_weather_summary_delivery`;
- `fail_weather_summary_delivery`;
- list tools для UI.

### 21.6. SummaryPublisher test

С mock MCP adapter и mock `summaryChatClient` проверить:

- пустой claim не вызывает OpenAI;
- агрегат превращается в текст;
- success вызывает complete;
- ошибка вызывает fail;
- ошибка одного summary не останавливает batch;
- пустой LLM response считается ошибкой;
- один publisher run не обрабатывает больше batch size.

### 21.7. Web tests

Через MockMvc проверить:

- `GET /api/schedules`;
- `GET /api/summaries`;
- валидацию `after` и `limit`;
- безопасную обработку MCP errors;
- сохранение существующего `POST /api/chat`.

### 21.8. Live smoke-тест

При наличии `OPENAI_API_KEY`:

1. запустить приложение;
2. создать через чат короткое учебное расписание;
3. убедиться, что модель вызвала `schedule_weather_summary`;
4. проверить запись schedule в UI;
5. дождаться observation и summary;
6. подтвердить, что OpenAI был вызван только после появления PENDING summary;
7. проверить появление DELIVERED summary в UI;
8. перезапустить приложение;
9. проверить сохранение schedule и истории;
10. отменить schedule;
11. остановить приложение и проверить отсутствие дочернего MCP-процесса.

Для live-демонстрации разрешить интервалы `1` и `5` минут. Не уменьшать production minimum до секунд.

## 22. Команды проверки

Из корня проекта:

```bash
./gradlew clean test
./gradlew integrationTest
./gradlew :mcp-weather-server:bootJar :agent-app:bootJar
```

Live-запуск:

```bash
export OPENAI_API_KEY="..."
export OPENAI_MODEL="gpt-6-luna"
export WEATHER_DB_PATH="weather-agent.db"

./gradlew :agent-app:bootRun
```

Открыть:

```text
http://localhost:8080
```

База `weather-agent.db` не должна удаляться при штатном перезапуске.

Не запускать `mcp-weather-server.jar` вручную и не ждать его завершения: дочерним STDIO-процессом управляет MCP-клиент.

## 23. README

Обновить README:

- описание Дня 18;
- архитектура scheduler и publisher;
- схема таблиц;
- список MCP-инструментов;
- какие инструменты доступны модели;
- конфигурация SQLite;
- конфигурация scheduler;
- путь к базе;
- команды тестирования;
- live-запуск;
- создание расписания через чат;
- восстановление после restart;
- отмена расписания;
- просмотр сводок;
- стоимость периодических OpenAI-вызовов;
- troubleshooting SQLite locked, Open-Meteo error, OpenAI error, stuck summary;
- объяснение, что 24/7 требует постоянно запущенного JVM-процесса или внешнего process supervisor.

## 24. Что означает «работает 24/7»

В рамках приложения это означает:

- работа без открытого браузера;
- сохранение schedule/observations/summaries;
- восстановление после штатного рестарта;
- изоляция ошибок одного расписания;
- retry доставки;
- отсутствие зависимости от пользовательского запроса после создания schedule.

Код не может обеспечить 24/7, если сам JVM-процесс выключен. Для реального постоянного размещения нужен внешний supervisor: systemd, launchd, Docker restart policy или облачная платформа. Настройка инфраструктуры не входит в это задание, но ограничение должно быть явно описано в README.

## 25. Критерии приемки

Задание считается выполненным только при выполнении всех условий:

- [ ] функциональность Дня 17 сохранена;
- [ ] добавлена файловая SQLite;
- [ ] схема создаётся через `schema.sql` без удаления существующих данных;
- [ ] расписания сохраняются между перезапусками;
- [ ] реализован `schedule_weather_summary`;
- [ ] входные параметры инструмента описаны и валидируются;
- [ ] модель может создать расписание через чат;
- [ ] реализованы status/latest/cancel tools;
- [ ] реализованы внутренние delivery tools;
- [ ] внутренние tools скрыты от модели через `McpToolFilter`;
- [ ] scheduler запускается через `@Scheduled`;
- [ ] динамические расписания читаются из SQLite;
- [ ] Open-Meteo вызывается по расписанию;
- [ ] observations сохраняются;
- [ ] агрегат рассчитывается по временному окну;
- [ ] summary защищён от дублей unique constraint;
- [ ] PENDING summary формируется без OpenAI;
- [ ] OpenAI вызывается только при наличии PENDING summary;
- [ ] используется отдельный `summaryChatClient` без tools;
- [ ] текст сводки сохраняется как DELIVERED;
- [ ] UI показывает schedules и delivered summaries;
- [ ] UI обновляется автоматически;
- [ ] работа продолжается при закрытом браузере;
- [ ] после restart schedules восстанавливаются;
- [ ] пропущенные интервалы не воспроизводятся массово;
- [ ] PROCESSING summary восстанавливается после timeout;
- [ ] ошибка одной задачи не останавливает dispatcher;
- [ ] HTTP и OpenAI вызовы выполняются вне DB-транзакций;
- [ ] внедрён `Clock` и тесты не ждут реальные минуты;
- [ ] автоматические тесты не вызывают реальный OpenAI;
- [ ] обычные тесты не вызывают реальный Open-Meteo;
- [ ] MCP integration test использует настоящий STDIO server jar;
- [ ] `./gradlew clean test` завершается успешно;
- [ ] `./gradlew integrationTest` завершается успешно;
- [ ] при наличии ключа выполнен live smoke-тест;
- [ ] при shutdown дочерний MCP-процесс завершается;
- [ ] секреты и SQLite-файлы не попадают в Git;
- [ ] README обновлён.

## 26. Порядок реализации для OMP

1. Проанализировать существующую реализацию Дня 17 и сохранить её поведение.
2. Добавить JDBC/SQLite зависимости и конфигурацию.
3. Добавить `schema.sql` и repository tests.
4. Добавить модели schedule, observation и summary.
5. Реализовать JDBC repositories.
6. Внедрить `Clock`.
7. Реализовать scheduling domain service.
8. Реализовать dispatcher и recovery.
9. Добавить agent-facing MCP tools.
10. Добавить internal delivery/list MCP tools.
11. Добавить `McpToolFilter` allowlist.
12. Реализовать `WeatherSchedulerMcpClient` в `agent-app`.
13. Создать `summaryChatClient` без tools.
14. Реализовать `SummaryPublisher`.
15. Добавить REST endpoints schedules/summaries.
16. Расширить HTML/CSS/JS.
17. Добавить unit-, repository-, scheduler-, MCP-, filter-, publisher- и web-тесты.
18. Выполнить offline-команды проверки.
19. При наличии API key выполнить live smoke-тест.
20. Проверить restart persistence и cleanup процессов.
21. Обновить README.
22. Подготовить итоговый отчёт с фактически выполненными проверками.

OMP не должен останавливаться после написания плана. Нужно реализовать изменения, собрать проект, исправить ошибки и выполнить доступные проверки.

Если `OPENAI_API_KEY` недоступен, нельзя подменять production OpenAI mock-реализацией. Нужно выполнить все offline-тесты, явно отметить единственную невыполненную live-проверку и предоставить точную команду её запуска.

## 27. Официальные справочные материалы

- Spring Scheduling: <https://docs.spring.io/spring-framework/reference/integration/scheduling.html>
- Spring Boot SQL databases и `JdbcClient`: <https://docs.spring.io/spring-boot/reference/data/sql.html>
- Spring Boot database initialization: <https://docs.spring.io/spring-boot/how-to/data-initialization.html>
- Spring AI MCP client filtering: <https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html>
- Spring AI tool calling: <https://docs.spring.io/spring-ai/reference/api/tools.html>
- SQLite JDBC: <https://github.com/xerial/sqlite-jdbc>
- Open-Meteo API: <https://open-meteo.com/en/docs>

При расхождении примеров с компилятором использовать публичные API зафиксированных версий и сохранить описанную архитектуру. Не менять STDIO на HTTP, SQLite на другую БД или scheduler на Quartz ради обхода ошибки.
