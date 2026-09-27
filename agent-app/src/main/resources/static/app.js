"use strict";

const form = document.querySelector("#chat-form");
const input = document.querySelector("#message");
const submit = document.querySelector("#submit");
const messages = document.querySelector("#messages");
const status = document.querySelector("#status");
const error = document.querySelector("#error");

function appendMessage(author, text, role) {
    const item = document.createElement("div");
    item.classList.add("message", role);

    const label = document.createElement("span");
    label.className = "message-author";
    label.textContent = author;

    const content = document.createElement("p");
    content.textContent = text;

    item.append(label, content);
    messages.append(item);
    item.scrollIntoView({ behavior: "smooth", block: "end" });
}

function setBusy(busy) {
    input.disabled = busy;
    submit.disabled = busy;
    status.hidden = !busy;
}

async function sendMessage() {
    const message = input.value.trim();
    if (!message) {
        return;
    }

    error.hidden = true;
    appendMessage("Вы", message, "user");
    setBusy(true);

    try {
        const response = await fetch("/api/chat", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ message }),
        });
        let payload;
        try {
            payload = await response.json();
        } catch {
            throw new Error("Сервер вернул некорректный ответ");
        }
        if (!response.ok) {
            throw new Error(typeof payload.error === "string" ? payload.error : "Не удалось получить ответ");
        }
        if (typeof payload.answer !== "string" || !payload.answer.trim()) {
            throw new Error("Сервер вернул пустой ответ");
        }
        appendMessage("Агент", payload.answer, "agent");
        input.value = "";
    } catch (requestError) {
        error.textContent = requestError instanceof Error ? requestError.message : "Не удалось получить ответ";
        error.hidden = false;
    } finally {
        setBusy(false);
        input.focus();
    }
}

form.addEventListener("submit", (event) => {
    event.preventDefault();
    void sendMessage();
});

input.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey) {
        event.preventDefault();
        if (!input.disabled) {
            form.requestSubmit();
        }
    }
});
