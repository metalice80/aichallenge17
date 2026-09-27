"use strict";

const form = document.querySelector("#chat-form");
const input = document.querySelector("#message");
const submit = document.querySelector("#submit");
const messages = document.querySelector("#messages");
const status = document.querySelector("#status");
const error = document.querySelector("#error");
const schedules = document.querySelector("#schedules");
const summaries = document.querySelector("#summaries");
const schedulesState = document.querySelector("#schedules-state");
const summariesState = document.querySelector("#summaries-state");

const SCHEDULE_INTERVAL = 30_000;
const SUMMARY_INTERVAL = 15_000;
const MAX_RETRY_DELAY = 60_000;
let scheduleDelay = SCHEDULE_INTERVAL;
let summaryDelay = SUMMARY_INTERVAL;
let scheduleTimer;
let summaryTimer;
let summaryCursor;
const renderedSummaryIds = new Set();

function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
}

function appendMessage(author, text, role) {
    const item = element("div", `message ${role}`);
    item.append(element("span", "message-author", author), element("p", "", text));
    messages.append(item);
    item.scrollIntoView({ behavior: "smooth", block: "end" });
}

function setBusy(busy) {
    input.disabled = busy;
    submit.disabled = busy;
    status.hidden = !busy;
}

async function fetchJson(url, options) {
    const response = await fetch(url, options);
    let payload;
    try {
        payload = await response.json();
    } catch {
        throw new Error("Сервер вернул некорректный ответ");
    }
    if (!response.ok) {
        throw new Error(typeof payload.error === "string" ? payload.error : "Ошибка запроса");
    }
    return payload;
}

async function sendMessage() {
    const message = input.value.trim();
    if (!message) return;

    error.hidden = true;
    appendMessage("Вы", message, "user");
    setBusy(true);

    try {
        const payload = await fetchJson("/api/chat", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ message }),
        });
        if (typeof payload.answer !== "string" || !payload.answer.trim()) {
            throw new Error("Сервер вернул пустой ответ");
        }
        appendMessage("Агент", payload.answer, "agent");
        input.value = "";
        void pollSchedules();
    } catch (requestError) {
        error.textContent = requestError instanceof Error ? requestError.message : "Не удалось получить ответ";
        error.hidden = false;
    } finally {
        setBusy(false);
        input.focus();
    }
}

function formatTime(value) {
    if (!value) return "—";
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? value : date.toLocaleString("ru-RU");
}

function labeled(label, value) {
    const row = element("p", "metadata-row");
    row.append(element("strong", "", `${label}: `), document.createTextNode(value));
    return row;
}

function renderSchedules(items) {
    schedules.replaceChildren();
    if (!items.length) {
        schedules.append(element("p", "empty-state", "Расписаний пока нет. Создайте одно через чат."));
        return;
    }
    items.forEach((schedule) => {
        const card = element("article", "data-card schedule-card");
        const heading = element("div", "card-heading");
        heading.append(
            element("h3", "", schedule.city),
            element("span", `badge ${String(schedule.status).toLowerCase()}`, schedule.status),
        );
        card.append(
            heading,
            labeled("Сбор", `каждые ${schedule.collectionIntervalMinutes} мин.`),
            labeled("Сводка", `каждые ${schedule.summaryIntervalMinutes} мин.`),
            labeled("Следующий сбор", formatTime(schedule.nextCollectionAt)),
            labeled("Следующая сводка", formatTime(schedule.nextSummaryAt)),
            labeled("Последний сбор", formatTime(schedule.lastCollectionAt)),
            labeled("Измерений", String(schedule.observationCount)),
            labeled("ID", schedule.id),
        );
        if (schedule.lastError) card.append(element("p", "card-error", schedule.lastError));
        schedules.append(card);
    });
}

function appendSummaries(items) {
    if (items.length && summaries.querySelector(".empty-state")) summaries.replaceChildren();
    items.forEach((summary) => {
        if (renderedSummaryIds.has(summary.id)) return;
        renderedSummaryIds.add(summary.id);
        const card = element("article", "data-card summary-card");
        card.dataset.summaryId = summary.id;
        card.append(
            element("h3", "", summary.city),
            element("p", "summary-text", summary.renderedText || "Сводка без текста"),
            labeled("Период", `${formatTime(summary.periodStartedAt)} — ${formatTime(summary.periodEndedAt)}`),
            labeled("Температура min/max/avg", `${summary.minTemperatureCelsius} / ${summary.maxTemperatureCelsius} / ${summary.avgTemperatureCelsius} °C`),
            labeled("Максимальный ветер", `${summary.maxWindSpeedKmh} км/ч`),
            labeled("Измерений", String(summary.sampleCount)),
            labeled("Опубликовано", formatTime(summary.deliveredAt)),
        );
        summaries.prepend(card);
        if (summary.deliveredAt && (!summaryCursor || summary.deliveredAt > summaryCursor)) {
            summaryCursor = summary.deliveredAt;
        }
    });
}

function nextDelay(current, base) {
    return Math.min(Math.max(base, current * 2), MAX_RETRY_DELAY);
}

async function pollSchedules() {
    clearTimeout(scheduleTimer);
    if (document.hidden) return;
    try {
        const payload = await fetchJson("/api/schedules");
        renderSchedules(Array.isArray(payload.schedules) ? payload.schedules : []);
        schedulesState.textContent = "";
        scheduleDelay = SCHEDULE_INTERVAL;
    } catch {
        schedulesState.textContent = "Временно недоступно";
        scheduleDelay = nextDelay(scheduleDelay, SCHEDULE_INTERVAL);
    } finally {
        if (!document.hidden) scheduleTimer = setTimeout(pollSchedules, scheduleDelay);
    }
}

async function pollSummaries() {
    clearTimeout(summaryTimer);
    if (document.hidden) return;
    const params = new URLSearchParams({ limit: "100" });
    if (summaryCursor) params.set("after", summaryCursor);
    try {
        const payload = await fetchJson(`/api/summaries?${params.toString()}`);
        appendSummaries(Array.isArray(payload.summaries) ? payload.summaries : []);
        summariesState.textContent = "";
        summaryDelay = SUMMARY_INTERVAL;
    } catch {
        summariesState.textContent = "Временно недоступно";
        summaryDelay = nextDelay(summaryDelay, SUMMARY_INTERVAL);
    } finally {
        if (!document.hidden) summaryTimer = setTimeout(pollSummaries, summaryDelay);
    }
}

function startPolling() {
    scheduleDelay = SCHEDULE_INTERVAL;
    summaryDelay = SUMMARY_INTERVAL;
    void pollSchedules();
    void pollSummaries();
}

function stopPolling() {
    clearTimeout(scheduleTimer);
    clearTimeout(summaryTimer);
}

form.addEventListener("submit", (event) => {
    event.preventDefault();
    void sendMessage();
});

input.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey) {
        event.preventDefault();
        if (!input.disabled) form.requestSubmit();
    }
});

document.addEventListener("visibilitychange", () => {
    if (document.hidden) stopPolling();
    else startPolling();
});

startPolling();
