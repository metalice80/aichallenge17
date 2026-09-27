# День 17. Первый MCP-инструмент: погодный агент с веб-интерфейсом

## 1. Назначение задания

С нуля создать учебное multi-module приложение на Kotlin и Spring Boot, которое демонстрирует полный агентный цикл с собственным MCP-инструментом:

1. собственный MCP-сервер регистрирует инструмент `get_current_weather`;
2. инструмент принимает название города через явно описанную входную схему;
3. MCP-сервер обращается к внешнему Open-Meteo API;
4. инструмент возвращает нормализованный результат с текущей погодой;
5. отдельное приложение-агент автоматически запускает MCP-сервер как дочерний JVM-процесс через STDIO;
6. агент подключается к модели OpenAI;
7. MCP-инструмент передаётся модели как доступный tool;
8. модель инициирует вызов инструмента;
9. результат MCP-вызова возвращается модели и используется в итоговом ответе;
10. пользователь общается с агентом через минимальный веб-интерфейс.

Результатом должен быть полностью собираемый и запускаемый проект, а не отдельные фрагменты кода.

## 2. Демонстрационный сценарий

Пользователь открывает `http://localhost:8080` и отправляет вопрос:

```text
Какая сейчас погода в Новосибирске и нужна ли тёплая куртка?
```

Ожидаемый поток выполнения:

```text
Browser
  → POST /api/chat
  → agent-app
  → OpenAI model
  → tool request: get_current_weather(city="Novosibirsk")
  → MCP client
  → STDIO
  → mcp-weather-server
  → Open-Meteo Geocoding API
  → Open-Meteo Forecast API
  → MCP tool result
  → OpenAI model
  → итоговый ответ
  → Browser
```

Пример итогового ответа:

```text
Сейчас в Новосибирске около +8 °C, ощущается как +6 °C,
ветер 14 км/ч. Тёплая куртка будет уместна.
```

Числа в ответе должны поступать из MCP-инструмента, а не быть придуманы моделью.

## 3. Обязательный стек и версии

- Kotlin `2.3.21`;
- Spring Boot `4.1.1`;
- Spring AI `2.0.1`;
- официальный MCP Java SDK, используемый Spring AI MCP;
- Gradle Kotlin DSL;
- Gradle Wrapper;
- JDK 21;
- Spring MVC;
- OpenAI Chat Model через Spring AI;
- MCP STDIO transport;
- Open-Meteo API;
- HTML, CSS и vanilla JavaScript;
- JUnit 5.

Не использовать milestone, RC или snapshot-зависимости.

## 4. Архитектура проекта

Создать один Gradle multi-module проект:

```text
day17-mcp-weather-agent/
├── settings.gradle.kts
├── build.gradle.kts
├── gradlew
├── gradlew.bat
├── gradle/
│   └── wrapper/
├── .gitignore
├── README.md
├── mcp-weather-server/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── kotlin/com/example/mcpweather/
│       │   │   ├── McpWeatherServerApplication.kt
│       │   │   ├── config/OpenMeteoProperties.kt
│       │   │   ├── model/GeocodingResponse.kt
│       │   │   ├── model/ForecastResponse.kt
│       │   │   ├── model/CurrentWeatherResult.kt
│       │   │   ├── service/OpenMeteoClient.kt
│       │   │   ├── service/WeatherService.kt
│       │   │   └── tool/WeatherMcpTool.kt
│       │   └── resources/
│       │       ├── application.yaml
│       │       └── logback-spring.xml
│       └── test/
│           └── kotlin/com/example/mcpweather/
│               ├── WeatherServiceTest.kt
│               └── WeatherMcpIntegrationTest.kt
└── agent-app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── kotlin/com/example/weatheragent/
        │   │   ├── WeatherAgentApplication.kt
        │   │   ├── agent/WeatherAgent.kt
        │   │   ├── config/AgentProperties.kt
        │   │   ├── config/AgentConfiguration.kt
        │   │   └── web/
        │   │       ├── ChatController.kt
        │   │       ├── ChatRequest.kt
        │   │       ├── ChatResponse.kt
        │   │       ├── ApiError.kt
        │   │       └── GlobalExceptionHandler.kt
        │   └── resources/
        │       ├── application.yaml
        │       └── static/
        │           ├── index.html
        │           ├── styles.css
        │           └── app.js
        └── test/
            └── kotlin/com/example/weatheragent/
                ├── ChatControllerTest.kt
                └── AgentConfigurationTest.kt
```

Допустимо немного изменить разбиение по файлам, но границы модулей и ответственность компонентов должны сохраниться.

## 5. Границы задачи

### Входит в задачу

- собственный MCP-сервер;
- один MCP-инструмент `get_current_weather`;
- явное описание инструмента и параметров;
- вызов реального внешнего Open-Meteo API;
- автоматический запуск MCP-сервера приложением-агентом;
- MCP `initialize`, `tools/list` и `tools/call`;
- агентный tool-calling цикл через модель OpenAI;
- минимальный веб-чат;
- конфигурируемые OpenAI API key и model ID;
- корректное завершение MCP-клиента и дочернего процесса;
- unit-, web- и MCP-интеграционные тесты;
- инструкции запуска.

### Не входит в задачу

Не добавлять:

- готовый внешний MCP-сервер вместо собственного;
- второй MCP-инструмент;
- HTTP/SSE/Streamable HTTP transport для MCP;
- React, Vue, Angular, npm frontend build;
- WebSocket и streaming ответа;
- базу данных;
- регистрацию и авторизацию пользователей;
- сохранение истории диалогов;
- векторную базу, RAG и embeddings;
- Docker и Kubernetes;
- собственный LLM;
- OpenAI Agents API: для учебного проекта достаточно Spring AI `ChatClient` и его tool-calling loop;
- prompts, resources, roots, sampling или elicitation MCP;
- прогноз на несколько дней и другие погодные инструменты;
- передачу model ID или API key из браузера.

## 6. Предпосылки

На машине должны быть доступны:

- JDK 21;
- действующий OpenAI API key с доступом к выбранной модели;
- доступ к Maven Central;
- доступ к `api.openai.com`;
- доступ к Open-Meteo API.

Проверить Java:

```bash
java -version
```

Установить ключ только через окружение:

```bash
export OPENAI_API_KEY="..."
```

Не хранить ключ в `application.yaml`, `.env`, исходном коде, тестовых ресурсах или Git.

## 7. Корневая Gradle-конфигурация

В `settings.gradle.kts` зарегистрировать:

```kotlin
rootProject.name = "day17-mcp-weather-agent"

include("mcp-weather-server")
include("agent-app")
```

В корневом `build.gradle.kts` централизованно определить версии плагинов, `mavenCentral()`, Java toolchain 21 и общие настройки тестов.

Каждый модуль должен использовать:

- Kotlin/JVM;
- Kotlin/Spring;
- Spring Boot;
- Gradle Kotlin DSL;
- JUnit Platform.

Подключить Spring AI BOM `2.0.1`; не задавать разные версии отдельным Spring AI-модулям вручную.

Создать агрегирующую задачу `integrationTest`, запускающую MCP smoke-тест. Стандартная задача `test` должна исключать JUnit-тег `integration`.

## 8. Модуль `mcp-weather-server`

### 8.1. Назначение

Это отдельное Spring Boot CLI-приложение без web-сервера. Оно запускается как дочерний JVM-процесс и общается с клиентом только через stdin/stdout по MCP.

### 8.2. Зависимости

Минимально необходимы:

```kotlin
dependencies {
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.1"))
    implementation("org.springframework.ai:spring-ai-starter-mcp-server")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```

Для HTTP-вызовов допускается:

- JDK `HttpClient`, либо
- Spring `RestClient`, если подключена минимально необходимая Spring HTTP-зависимость.

Не подключать полный web starter к MCP-серверу только ради HTTP-клиента.

### 8.3. Имя jar

Зафиксировать имя исполняемого jar:

```kotlin
tasks.bootJar {
    archiveFileName.set("mcp-weather-server.jar")
}
```

После сборки файл должен находиться здесь:

```text
mcp-weather-server/build/libs/mcp-weather-server.jar
```

### 8.4. Конфигурация MCP-сервера

`mcp-weather-server/src/main/resources/application.yaml`:

```yaml
spring:
  application:
    name: mcp-weather-server
  main:
    web-application-type: none
    banner-mode: off
  ai:
    mcp:
      server:
        enabled: true
        stdio: true
        type: SYNC
        name: weather-mcp-server
        version: 1.0.0
        instructions: >-
          Provides current weather for a city using Open-Meteo.
        capabilities:
          tool: true
          resource: false
          prompt: false
          completion: false

app:
  open-meteo:
    geocoding-url: ${OPEN_METEO_GEOCODING_URL:https://geocoding-api.open-meteo.com/v1/search}
    forecast-url: ${OPEN_METEO_FORECAST_URL:https://api.open-meteo.com/v1/forecast}
    connect-timeout: 5s
    request-timeout: 10s
```

Создать типизированный `OpenMeteoProperties` с проверками:

- URL должен быть абсолютным HTTP/HTTPS URL;
- тайм-ауты должны быть положительными;
- значения нельзя зашивать в `OpenMeteoClient`.

### 8.5. Правила STDIO

STDOUT MCP-сервера является каналом JSON-RPC. Любой баннер, `println`, пользовательский текст или обычный лог в stdout может повредить протокол.

Обязательно:

- отключить Spring banner;
- не использовать `println` и `System.out`;
- настроить `logback-spring.xml` так, чтобы console appender писал в `System.err`;
- не читать stdin самостоятельно;
- позволить MCP transport полностью управлять stdin/stdout;
- при завершении родительского приложения корректно завершать сервер.

### 8.6. Open-Meteo client

Реализовать два HTTP-запроса.

Поиск координат:

```text
GET {geocoding-url}
  ?name={city}
  &count=1
  &language=ru
  &format=json
```

Получение текущей погоды:

```text
GET {forecast-url}
  ?latitude={latitude}
  &longitude={longitude}
  &current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m
  &timezone=auto
```

Не формировать URL строковой конкатенацией без кодирования параметров. Название города должно корректно работать с пробелами, кириллицей и специальными символами.

Обработать:

- пустой ответ геокодинга;
- неизвестный город;
- HTTP 4xx/5xx;
- некорректный JSON;
- connect/read timeout;
- отсутствие обязательных полей ответа.

Не возвращать пользователю сырой ответ Open-Meteo целиком.

### 8.7. Результат инструмента

Нормализованный результат должен содержать:

```kotlin
data class CurrentWeatherResult(
    val city: String,
    val country: String?,
    val latitude: Double,
    val longitude: Double,
    val temperatureCelsius: Double,
    val apparentTemperatureCelsius: Double,
    val windSpeedKmh: Double,
    val weatherCode: Int,
    val observedAt: String,
)
```

Не переводить `weatherCode` в художественное описание внутри MCP-сервера. Агент может использовать структурированные значения при подготовке ответа.

### 8.8. Регистрация инструмента

Использовать аннотации Spring AI MCP, построенные поверх официального MCP Java SDK:

```kotlin
@Component
class WeatherMcpTool(
    private val weatherService: WeatherService,
) {

    @McpTool(
        name = "get_current_weather",
        description = "Returns current weather for a city using live Open-Meteo data.",
        title = "Get current weather",
        generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    )
    fun getCurrentWeather(
        @McpToolParam(
            description = "City name, for example Novosibirsk or Санкт-Петербург",
            required = true,
        )
        city: String,
    ): CurrentWeatherResult = weatherService.getCurrentWeather(city)
}
```

Это ориентир. Итоговый код должен использовать точные Kotlin-совместимые сигнатуры Spring AI `2.0.1` и успешно компилироваться.

Требования к параметру `city`:

- после `trim` не пустой;
- не длиннее 120 символов;
- не принимать управляющие символы;
- не принимать произвольный URL вместо названия города.

При ошибке предметной области вернуть понятную tool-level ошибку, пригодную для модели, например `City not found: ...`. Не скрывать инфраструктурные ошибки как успешный пустой результат.

### 8.9. Логирование MCP-сервера

Через stderr логировать:

- запуск сервера;
- регистрацию `get_current_weather`;
- вызов инструмента без чувствительных данных;
- найденный город;
- успешный ответ Open-Meteo;
- ошибку внешнего API;
- завершение сервера.

Не логировать полный сырой HTTP-ответ на INFO.

## 9. Модуль `agent-app`

### 9.1. Назначение

Это Spring Boot web-приложение на порту `8080`. Оно:

- обслуживает статический веб-интерфейс;
- принимает сообщения пользователя;
- подключается к OpenAI;
- автоматически запускает `mcp-weather-server.jar`;
- обнаруживает MCP-инструмент;
- предоставляет его модели;
- выполняет tool-calling loop;
- возвращает итоговый ответ браузеру.

### 9.2. Зависимости

```kotlin
dependencies {
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.1"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    implementation("org.springframework.ai:spring-ai-starter-mcp-client")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```

Не подключать официальный OpenAI Java SDK параллельно Spring AI без необходимости: один слой интеграции должен отвечать за вызовы модели.

### 9.3. Зависимость сборки от MCP-сервера

`agent-app:bootRun`, `agent-app:test` при необходимости и MCP integration test должны зависеть от `:mcp-weather-server:bootJar`.

Для `bootRun` установить рабочую директорию в корень проекта, чтобы относительный путь к server jar был стабильным:

```kotlin
tasks.named<BootRun>("bootRun") {
    dependsOn(":mcp-weather-server:bootJar")
    workingDir(rootProject.projectDir)
}
```

Не использовать glob вида `build/libs/*.jar` внутри `ServerParameters`.

### 9.4. Конфигурация агента, OpenAI и MCP-клиента

`agent-app/src/main/resources/application.yaml`:

```yaml
server:
  port: ${SERVER_PORT:8080}

spring:
  application:
    name: weather-agent
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: ${app.agent.model}
    mcp:
      client:
        enabled: true
        initialized: true
        type: SYNC
        request-timeout: 20s
        toolcallback:
          enabled: true
        stdio:
          connections:
            weather:
              command: ${MCP_SERVER_COMMAND:java}
              args:
                - -jar
                - ${MCP_SERVER_JAR:mcp-weather-server/build/libs/mcp-weather-server.jar}

app:
  agent:
    model: ${OPENAI_MODEL:gpt-6-luna}
    max-message-length: 2000
```

Создать типизированный `AgentProperties`:

```kotlin
@ConfigurationProperties(prefix = "app.agent")
@Validated
data class AgentProperties(
    @field:NotBlank
    val model: String,

    @field:Min(1)
    @field:Max(20_000)
    val maxMessageLength: Int,
)
```

Требования:

- model ID не должен встречаться в Kotlin-коде;
- model ID можно переопределить через `OPENAI_MODEL`;
- выбранная модель должна поддерживать function/tool calling;
- API key поступает только через `OPENAI_API_KEY`;
- при отсутствии API key приложение должно завершаться с понятной ошибкой;
- выбранную модель можно записать в startup log, API key логировать нельзя;
- браузер не может выбирать модель и не получает model ID в API-ответе.

Модель по умолчанию `gpt-6-luna` выбрана для небольшого учебного агента. Если она недоступна аккаунту, пользователь должен переопределить `OPENAI_MODEL` на доступную модель с поддержкой tool calling, не меняя код.

### 9.5. MCP-клиент и жизненный цикл

Использовать авто-конфигурацию `spring-ai-starter-mcp-client`:

1. starter создаёт STDIO transport;
2. команда `java -jar mcp-weather-server.jar` запускается как дочерний процесс;
3. клиент выполняет initialize;
4. клиент обнаруживает инструменты;
5. Spring AI создаёт `SyncMcpToolCallbackProvider`;
6. при остановке Spring-контекста клиент и дочерний процесс закрываются.

Не запускать MCP-сервер вторым `ProcessBuilder` вручную и не создавать параллельный MCP-клиент.

Приложение должно завершать запуск с ошибкой, если:

- server jar отсутствует;
- `java` невозможно запустить;
- MCP initialize не выполнен;
- инструмент `get_current_weather` не найден.

После остановки `agent-app` не должен оставаться процесс `mcp-weather-server.jar`.

### 9.6. Подключение MCP-инструмента к агенту

Создать `ChatClient` с явной передачей `SyncMcpToolCallbackProvider`:

```kotlin
@Bean
fun weatherChatClient(
    chatModel: ChatModel,
    mcpTools: SyncMcpToolCallbackProvider,
): ChatClient = ChatClient.builder(chatModel)
    .defaultSystem(
        """
        Ты погодный ассистент.
        Для любых вопросов о текущей погоде обязательно используй
        инструмент get_current_weather.
        Не выдумывай температуру, ветер, город или время наблюдения.
        Если инструмент вернул ошибку, честно сообщи об этом пользователю.
        Отвечай кратко и на языке пользователя.
        """.trimIndent(),
    )
    .defaultTools(mcpTools)
    .build()
```

Не использовать устаревший `defaultToolCallbacks`; для Spring AI 2.0 использовать `defaultTools`.

`WeatherAgent` должен быть небольшим сервисом:

```kotlin
@Service
class WeatherAgent(
    private val weatherChatClient: ChatClient,
) {
    fun ask(message: String): String =
        weatherChatClient.prompt()
            .user(message)
            .call()
            .content()
            ?: throw IllegalStateException("OpenAI returned an empty response")
}
```

Прямой вызов `mcpClient.callTool()` разрешён в MCP integration test, но production-запрос пользователя должен проходить через модель и `ChatClient`. Иначе требование «агент вызывает MCP-инструмент» не считается выполненным.

## 10. Web API

### 10.1. Endpoint

Реализовать:

```http
POST /api/chat
Content-Type: application/json
```

Запрос:

```json
{
  "message": "Какая сейчас погода в Новосибирске?"
}
```

Успешный ответ:

```json
{
  "answer": "Сейчас в Новосибирске около ..."
}
```

### 10.2. Валидация

`ChatRequest.message`:

- обязательное;
- после trim не пустое;
- не длиннее `app.agent.max-message-length`;
- не должно содержать нулевые или другие недопустимые управляющие символы.

Не принимать model ID, system prompt, tool name или API key из запроса.

### 10.3. Ошибки

Создать `@RestControllerAdvice` и единый безопасный формат:

```json
{
  "error": "Не удалось получить ответ погодного агента"
}
```

Минимальная таблица статусов:

| Ситуация | HTTP status |
|---|---:|
| Пустое или слишком длинное сообщение | 400 |
| OpenAI недоступен или отклонил запрос | 502 |
| MCP-сервер или Open-Meteo недоступен | 502 |
| Неожиданная внутренняя ошибка | 500 |

Не отдавать браузеру stack trace, внутренние URL, ключи, сырые ответы OpenAI или полный текст исключения.

## 11. Минимальный веб-интерфейс

### 11.1. Общий вид

```text
┌──────────────────────────────────────────────┐
│ MCP Weather Agent                            │
├──────────────────────────────────────────────┤
│ Вы: Какая сейчас погода в Новосибирске?      │
│                                              │
│ Агент: Сейчас около +8 °C...                  │
│                                              │
├──────────────────────────────────────────────┤
│ [Введите сообщение...             ] [Отправить] │
└──────────────────────────────────────────────┘
```

### 11.2. Требования к UI

Страница `/` должна содержать:

- заголовок приложения;
- короткое пояснение, что агент получает погоду через MCP;
- область сообщений;
- поле ввода;
- кнопку отправки;
- индикатор ожидания;
- понятный текст ошибки;
- пример вопроса или placeholder.

Поведение:

- Enter отправляет сообщение;
- Shift+Enter добавляет новую строку, если используется textarea;
- во время запроса кнопка и поле блокируются;
- сообщение пользователя появляется сразу;
- успешный ответ добавляется как сообщение агента;
- при ошибке введённый текст не теряется безвозвратно;
- пустые сообщения не отправляются;
- интерфейс работает на мобильной ширине;
- базовые label/ARIA-атрибуты присутствуют.

Безопасность UI:

- добавлять пользовательский и модельный текст через `textContent`, не `innerHTML`;
- не подключать сторонние CDN-скрипты;
- не передавать OpenAI API key в JavaScript;
- не делать прямые запросы из браузера к OpenAI или Open-Meteo;
- все вызовы идут только на same-origin `/api/chat`, поэтому CORS не нужен.

Внешний вид должен быть аккуратным, но минимальным. Не тратить время на сложную дизайн-систему и анимации.

## 12. Логирование

### Agent app

На INFO логировать:

- выбранный model ID;
- запуск подключения к MCP-серверу;
- успешное обнаружение `get_current_weather`;
- начало обработки chat-запроса без полного пользовательского текста;
- успешное завершение ответа;
- корректное закрытие приложения и MCP-клиента.

На DEBUG допускается логировать имя вызванного инструмента и безопасные аргументы.

### MCP server

Логи идут только в stderr. Логировать имя инструмента, город, результат HTTP status и длительность вызова.

### Запрещено логировать

- `OPENAI_API_KEY`;
- Authorization headers;
- полный OpenAI request/response;
- сырые MCP JSON-RPC сообщения на INFO;
- полный stack trace несколько раз на разных слоях.

## 13. Тестирование

### 13.1. Unit-тесты MCP-сервера

Проверить `WeatherService` и/или `OpenMeteoClient`:

- успешное преобразование geocoding + forecast в `CurrentWeatherResult`;
- город не найден;
- пустой город;
- слишком длинный город;
- Open-Meteo вернул 4xx/5xx;
- Open-Meteo вернул некорректный JSON;
- timeout.

Для HTTP-тестов использовать локальный stub на случайном порту. Допустим JDK `HttpServer` в test fixtures. Не обращаться к реальному Open-Meteo из обычного `test`.

### 13.2. MCP integration test

Создать тест с тегом `integration`, который:

1. собирает `mcp-weather-server.jar`;
2. запускает локальный HTTP stub с предсказуемыми geocoding/forecast ответами;
3. запускает server jar через `ServerParameters` и `StdioClientTransport`;
4. передаёт дочернему процессу stub URL через переменные окружения;
5. выполняет `initialize`;
6. выполняет `tools/list`;
7. проверяет наличие ровно требуемого инструмента `get_current_weather`;
8. проверяет описание инструмента;
9. проверяет, что входная схема содержит обязательный `city`;
10. выполняет прямой `callTool` с тестовым городом;
11. проверяет значения структурированного результата;
12. выполняет ошибочный вызов с неизвестным городом;
13. всегда вызывает `closeGracefully`;
14. проверяет, что дочерний процесс завершился.

Не подменять transport или MCP client mock-объектом в этом тесте.

Тест не должен использовать OpenAI API key и не должен расходовать OpenAI-токены.

### 13.3. Web layer test

Через MockMvc проверить:

- `POST /api/chat` возвращает `200` и `answer`;
- пустое сообщение возвращает `400`;
- слишком длинное сообщение возвращает `400`;
- ошибка `WeatherAgent` преобразуется в безопасный `502` или `500` согласно классификации;
- в ответе нет stack trace и секретов.

`WeatherAgent` в web slice тесте замокать. OpenAI и MCP процессы запускать не нужно.

### 13.4. Agent configuration test

Без реального OpenAI-вызова проверить:

- `app.agent.model` загружается;
- переопределение через `OPENAI_MODEL`/test property работает;
- пустой model ID отклоняется;
- `ChatClient` получает `SyncMcpToolCallbackProvider` как tool provider.

### 13.5. Ручной live smoke-тест

Этот тест выполняется вручную, потому что требует сеть, API key и может расходовать средства:

1. запустить приложение с `OPENAI_API_KEY`;
2. открыть браузер;
3. задать вопрос о текущей погоде;
4. по логам подтвердить вызов `get_current_weather`;
5. убедиться, что итоговый ответ содержит значения из tool result;
6. остановить приложение;
7. убедиться, что дочерний MCP-процесс не остался запущенным.

Не добавлять live OpenAI-вызов в стандартную CI-задачу.

## 14. Команды сборки и запуска

Из корня проекта:

```bash
./gradlew clean test
./gradlew integrationTest
./gradlew :mcp-weather-server:bootJar :agent-app:bootJar
```

Запуск:

```bash
export OPENAI_API_KEY="..."
export OPENAI_MODEL="gpt-6-luna"

./gradlew :agent-app:bootRun
```

Открыть:

```text
http://localhost:8080
```

Проверка HTTP API без браузера:

```bash
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"Какая сейчас погода в Новосибирске?"}'
```

Запуск собранного приложения из корня репозитория:

```bash
./gradlew :mcp-weather-server:bootJar :agent-app:bootJar

OPENAI_API_KEY="..." \
OPENAI_MODEL="gpt-6-luna" \
java -jar agent-app/build/libs/agent-app.jar
```

Если имя `agent-app` jar содержит версию, либо зафиксировать его `archiveFileName`, либо указать точное имя в README. Не использовать неоднозначный wildcard в командах, запускающих дочерний MCP-сервер.

## 15. README

Создать `README.md` со следующими разделами:

- назначение проекта;
- архитектурная схема;
- версии Java, Kotlin, Spring Boot, Spring AI и MCP;
- prerequisites;
- получение и безопасная установка `OPENAI_API_KEY`;
- настройка `OPENAI_MODEL`;
- сборка;
- тестирование;
- запуск;
- адрес веб-интерфейса;
- пример запроса;
- пример ожидаемых логов;
- объяснение, что MCP-сервер запускается автоматически;
- способ переопределить `MCP_SERVER_COMMAND` и `MCP_SERVER_JAR`;
- способ переопределить Open-Meteo URL для тестирования;
- troubleshooting для отсутствующего jar, ошибки OpenAI, ошибки MCP и ошибки Open-Meteo;
- предупреждение, что live-запуск OpenAI может расходовать средства.

## 16. Обработка ошибок

Реализовать осмысленную обработку:

- отсутствует `OPENAI_API_KEY`;
- model ID пуст или недоступен;
- OpenAI authentication/rate limit/network error;
- server jar отсутствует;
- дочерний процесс не запускается;
- MCP initialize timeout;
- инструмент не обнаружен;
- tool call timeout;
- город не найден;
- Open-Meteo недоступен;
- Open-Meteo вернул неожиданный ответ;
- браузер отправил некорректное сообщение.

Не возвращать пустой успешный ответ и не маскировать ошибку фиктивными погодными данными.

## 17. Безопасность

- Хранить OpenAI API key только вне репозитория.
- Добавить `.env`, `*.log`, `build/`, `.gradle/`, IDE-файлы в `.gitignore`.
- Не выполнять команды, полученные от модели или пользователя.
- MCP-инструмент read-only и принимает только название города.
- Не разрешать модели менять Open-Meteo URL, server jar или model ID.
- Ограничить длину HTTP-запроса и параметра `city`.
- Кодировать query parameters.
- Использовать HTTP timeout.
- Не рендерить ответы через `innerHTML`.
- Не раскрывать внутренние исключения клиенту.

## 18. Критерии приемки

Задание считается выполненным только при одновременном выполнении всех условий:

- [ ] проект создан с нуля как Gradle multi-module проект;
- [ ] присутствует Gradle Wrapper;
- [ ] весь production-код написан на Kotlin;
- [ ] проект собирается на JDK 21;
- [ ] создан собственный MCP-сервер;
- [ ] сервер использует STDIO transport;
- [ ] stdout сервера не загрязняется логами и banner;
- [ ] зарегистрирован инструмент `get_current_weather`;
- [ ] инструмент имеет понятное описание;
- [ ] параметр `city` обязателен и описан в JSON Schema;
- [ ] инструмент обращается к реальному Open-Meteo API в live-режиме;
- [ ] результат содержит нормализованные погодные данные;
- [ ] `agent-app` автоматически запускает server jar;
- [ ] MCP initialize выполняется успешно;
- [ ] инструмент присутствует в `tools/list`;
- [ ] production-запрос проходит через OpenAI model и tool-calling loop;
- [ ] агент сам инициирует вызов MCP-инструмента;
- [ ] результат инструмента используется в итоговом ответе;
- [ ] model ID находится в конфигурации;
- [ ] model ID переопределяется через `OPENAI_MODEL`;
- [ ] API key читается через `OPENAI_API_KEY` и отсутствует в репозитории;
- [ ] минимальный веб-интерфейс доступен на `/`;
- [ ] `POST /api/chat` работает;
- [ ] frontend не использует `innerHTML` для сообщений;
- [ ] ошибки возвращаются в безопасном формате;
- [ ] unit-тесты не используют реальный Open-Meteo;
- [ ] MCP integration test запускает реальный server jar и выполняет `callTool`;
- [ ] автоматические тесты не вызывают OpenAI;
- [ ] `./gradlew clean test` завершается успешно;
- [ ] `./gradlew integrationTest` завершается успешно;
- [ ] ручной live smoke-тест подтверждает вызов инструмента агентом;
- [ ] при остановке приложения дочерний MCP-процесс завершается;
- [ ] README содержит полные инструкции запуска;
- [ ] отсутствует функциональность вне границ задачи.

## 19. Порядок реализации для OMP

1. Создать корневой Gradle multi-module проект и Wrapper.
2. Настроить версии, BOM, repositories, Java toolchain и JUnit.
3. Создать `mcp-weather-server`.
4. Настроить безопасный STDIO и stderr-логирование.
5. Реализовать конфигурацию и HTTP-клиент Open-Meteo.
6. Реализовать модели и `WeatherService`.
7. Зарегистрировать `get_current_weather` через `@McpTool`.
8. Собрать server jar с фиксированным именем.
9. Создать `agent-app`.
10. Настроить OpenAI, model ID и MCP STDIO connection.
11. Подключить `SyncMcpToolCallbackProvider` к `ChatClient` через `defaultTools`.
12. Реализовать `WeatherAgent`.
13. Реализовать `/api/chat` и безопасную обработку ошибок.
14. Реализовать минимальный HTML/CSS/JS интерфейс.
15. Добавить unit- и web-тесты.
16. Добавить реальный MCP integration test с локальным Open-Meteo stub.
17. Выполнить `clean test` и `integrationTest`.
18. При доступном ключе выполнить live smoke-тест через браузер.
19. Проверить отсутствие оставшегося дочернего процесса.
20. Создать README и итоговый отчёт.

OMP не должен останавливаться после создания структуры или написания плана. Необходимо реализовать код, собрать проект, исправить ошибки и выполнить доступные проверки.

Если live smoke-тест невозможно выполнить из-за отсутствия `OPENAI_API_KEY`, это не разрешает заменять OpenAI mock-ответом в production-коде. Следует выполнить все offline-тесты, явно отметить единственную невыполненную live-проверку и предоставить точную команду для неё.

## 20. Официальные справочные материалы

- Spring AI MCP server annotations: <https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-server.html>
- Spring AI MCP client starter: <https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html>
- Spring AI tool calling: <https://docs.spring.io/spring-ai/reference/api/tools.html>
- Spring AI MCP helpers: <https://docs.spring.io/spring-ai/reference/api/mcp/mcp-helpers.html>
- MCP Java SDK: <https://github.com/modelcontextprotocol/java-sdk>
- Open-Meteo Forecast API: <https://open-meteo.com/en/docs>
- Open-Meteo Geocoding API: <https://open-meteo.com/en/docs/geocoding-api>
- OpenAI API quickstart: <https://developers.openai.com/api/docs/quickstart>
- OpenAI model catalog: <https://developers.openai.com/api/docs/models>

При расхождении примера с компилятором использовать публичные API зафиксированных стабильных версий. Не менять архитектуру, MCP transport или границы задачи ради обхода ошибки.
