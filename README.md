# Day 17 — MCP Weather Agent

Учебное multi-module приложение на Kotlin: веб-агент Spring AI вызывает собственный MCP-инструмент `get_current_weather` через STDIO, а MCP-сервер получает текущие данные Open-Meteo.

## Архитектура

```text
Browser → POST /api/chat → agent-app → OpenAI Chat Model
                                      ↕ tool calling loop
                           SyncMcpToolCallbackProvider
                                      ↕ STDIO JSON-RPC
                           mcp-weather-server.jar
                                      ↕ HTTPS
                 Open-Meteo Geocoding + Forecast APIs
```

- `mcp-weather-server` — Spring Boot CLI без web-сервера; регистрирует один read-only MCP tool и пишет логи только в stderr.
- `agent-app` — Spring MVC, `ChatClient`, автоматический STDIO MCP-клиент и статический HTML/CSS/vanilla JavaScript интерфейс.
- MCP starter запускает `java -jar mcp-weather-server/build/libs/mcp-weather-server.jar`, выполняет initialize/discovery и закрывает клиент с дочерним процессом вместе со Spring-контекстом.

## Версии

| Компонент | Версия |
|---|---:|
| JDK | 21 |
| Gradle Wrapper | 8.14.3 |
| Kotlin | 2.3.21 |
| Spring Boot | 4.1.1 |
| Spring AI | 2.0.1 |
| MCP Java SDK (транзитивно через Spring AI) | 2.0.0 |
| JUnit | 5 (из Spring Boot BOM) |

Используются только стабильные зависимости из Maven Central.

## Требования

- JDK 21;
- доступ к Maven Central, OpenAI и Open-Meteo;
- действующий OpenAI API key;
- модель OpenAI с поддержкой function/tool calling.

Ключ передаётся только через окружение:

```bash
export OPENAI_API_KEY="..."
```

Не помещайте ключ в Git, `.env`, YAML, команды shell history или frontend. Live-вызовы OpenAI могут расходовать средства.

Модель по умолчанию — `gpt-4.1-mini`: она поддерживает Chat Completions и function calling.
Переопределение без изменения кода:

```bash
export OPENAI_MODEL="gpt-4.1-mini"
```

## Сборка и тестирование

Из корня репозитория:

```bash
./gradlew clean test
./gradlew integrationTest
./gradlew :mcp-weather-server:bootJar :agent-app:bootJar
```

`test` не обращается к OpenAI или реальному Open-Meteo. HTTP unit-тесты используют локальный JDK `HttpServer` на случайном порту.

`integrationTest` собирает настоящий `mcp-weather-server.jar`, запускает его через `ServerParameters`/`StdioClientTransport`, выполняет MCP `initialize`, `tools/list` и `tools/call`, проверяет успешный и ошибочный вызовы, затем вызывает `closeGracefully` и ждёт завершения процесса. Open-Meteo в этом тесте заменён локальным HTTP stub; OpenAI не используется.

Артефакты:

```text
mcp-weather-server/build/libs/mcp-weather-server.jar
agent-app/build/libs/agent-app.jar
```

## Запуск

```bash
export OPENAI_API_KEY="..."
export OPENAI_MODEL="gpt-4.1-mini"
./gradlew :agent-app:bootRun
```

`bootRun` сначала собирает MCP server jar и запускается из корня проекта. MCP-сервер вручную запускать не нужно: это долгоживущий STDIO-процесс, которым управляет Spring AI MCP client.

Откройте <http://localhost:8080> и задайте, например:

```text
Какая сейчас погода в Новосибирске и нужна ли тёплая куртка?
```

Проверка API:

```bash
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"Какая сейчас погода в Новосибирске?"}'
```

Запуск собранных jar из корня:

```bash
./gradlew :mcp-weather-server:bootJar :agent-app:bootJar
OPENAI_API_KEY="..." OPENAI_MODEL="gpt-4.1-mini" \
  java -jar agent-app/build/libs/agent-app.jar
```

При штатном старте INFO-логи показывают выбранную модель, безопасный OpenAI endpoint без credentials/query, подключение MCP, обнаружение `get_current_weather`, вызов инструмента и HTTP status Open-Meteo. API key, Authorization headers, сырые запросы/ответы OpenAI и MCP JSON-RPC не логируются.

## Конфигурация

| Переменная | Назначение | Значение по умолчанию |
|---|---|---|
| `OPENAI_API_KEY` | обязательный OpenAI API key | отсутствует |
| `OPENAI_MODEL` | model ID с tool calling | `gpt-4.1-mini` |
| `OPENAI_BASE_URL` | базовый URL OpenAI API для official SDK | `https://api.openai.com/v1` |
| `SERVER_PORT` | HTTP-порт agent-app | `8080` |
| `MCP_SERVER_COMMAND` | Java launcher MCP-процесса | `java` |
| `MCP_SERVER_JAR` | точный путь к server jar | `mcp-weather-server/build/libs/mcp-weather-server.jar` |
| `OPEN_METEO_GEOCODING_URL` | URL Geocoding API | официальный Open-Meteo URL |
| `OPEN_METEO_FORECAST_URL` | URL Forecast API | официальный Open-Meteo URL |

Open-Meteo URL можно переопределить для локального тестового сервера. Браузер не может менять URL, model ID, tool name или API key.

## MCP-инструмент

`get_current_weather` принимает один обязательный параметр `city` (непустой после trim, максимум 120 символов, без управляющих символов и URL). JSON Schema включает описание параметра и output schema.

Сервер кодирует query parameters и последовательно вызывает:

1. Open-Meteo Geocoding API для координат;
2. Open-Meteo Forecast API для текущей температуры, ощущаемой температуры, скорости ветра, weather code и времени наблюдения.

Результат нормализован в поля `city`, `country`, `latitude`, `longitude`, `temperatureCelsius`, `apparentTemperatureCelsius`, `windSpeedKmh`, `weatherCode`, `observedAt`. Ошибки города, HTTP, JSON и timeout возвращаются как tool-level failures; фиктивные данные не создаются.

## Устранение проблем

- **`OPENAI_API_KEY` отсутствует** — приложение завершится при старте; экспортируйте действующий ключ.
- **Модель недоступна или не поддерживает tools** — задайте доступный model ID через `OPENAI_MODEL`.
- **OpenAI 404 / `model_not_found`** — ключ относится к API-проекту без доступа к модели. Если `code`, `type` и `param` в логе имеют значение `unspecified`, сервер вернул неструктурированный 404: проверьте startup-строку `Using OpenAI endpoint`, proxy/VPN и переменные base URL. Подписка ChatGPT не заменяет API billing.
- **Server jar отсутствует** — выполните `./gradlew :mcp-weather-server:bootJar` или запускайте через `:agent-app:bootRun`.
- **Не запускается дочерний JVM** — проверьте `java -version` либо задайте абсолютный `MCP_SERVER_COMMAND`.
- **MCP initialize timeout / инструмент не обнаружен** — проверьте stderr MCP-сервера, точный `MCP_SERVER_JAR` и отсутствие вывода в stdout вне JSON-RPC.
- **Open-Meteo недоступен** — проверьте сеть и URL; server использует connect/request timeout и вернёт ошибку инструменту.
- **OpenAI 401/429/5xx** — проверьте ключ, доступ к модели, лимиты и billing; браузер получит безопасный 502 без внутренних деталей.

Список моделей, доступных именно текущему API key:

```bash
curl https://api.openai.com/v1/models \
  -H "Authorization: Bearer $OPENAI_API_KEY"
```

Выберите ID из ответа, поддерживающий function calling, и передайте его через `OPENAI_MODEL`. Для Spring AI `2.0.1` и official OpenAI Java SDK startup-лог должен показывать `https://api.openai.com/v1`: SDK добавляет к нему `/chat/completions`. Удалите случайные переопределения перед запуском:

```bash
unset OPENAI_BASE_URL
unset SPRING_AI_OPENAI_BASE_URL
unset SPRING_AI_OPENAI_CHAT_BASE_URL
```

Остановка `agent-app` закрывает Spring-контекст, MCP-клиент и дочерний MCP-процесс. Не запускайте server jar как обычную одноразовую команду: он ожидает MCP JSON-RPC в stdin.
