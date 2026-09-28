const message = document.querySelector('#message');
const start = document.querySelector('#start');
const runId = document.querySelector('#run-id');
const statusEl = document.querySelector('#status');
const timeline = document.querySelector('#timeline');
const answer = document.querySelector('#answer');
const errorEl = document.querySelector('#error');
const report = document.querySelector('#report');
let stream;

start.addEventListener('click', async () => {
  errorEl.textContent = '';
  start.disabled = true;
  timeline.replaceChildren();
  answer.textContent = 'Запуск…';
  report.hidden = true;
  if (stream) stream.close();
  try {
    const response = await fetch('/api/orchestrations', { method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({message: message.value}) });
    const body = await response.json();
    if (!response.ok) throw new Error(body.message || 'Не удалось запустить flow');
    runId.textContent = body.runId;
    statusEl.textContent = body.status;
    watch(body.runId, body.eventsUrl);
  } catch (error) {
    errorEl.textContent = error.message;
    start.disabled = false;
  }
});

function watch(id, url) {
  stream = new EventSource(url);
  stream.onmessage = event => addEvent(JSON.parse(event.data));
  const eventNames = ['RUN_CREATED','RUN_STARTED','TOOL_REQUESTED','TOOL_ALLOWED','TOOL_REJECTED','TOOL_SUCCEEDED','TOOL_FAILED','REPORT_SAVED','RUN_COMPLETED','RUN_FAILED'];
  eventNames.forEach(name => stream.addEventListener(name, event => {
    addEvent(JSON.parse(event.data));
    if (name === 'RUN_COMPLETED' || name === 'RUN_FAILED') finish(id);
  }));
  stream.onerror = () => { if (stream.readyState === EventSource.CLOSED) finish(id); };
}

function addEvent(event) {
  if (!event.toolName && !event.eventType.startsWith('RUN_')) return;
  const item = document.createElement('li');
  const badge = document.createElement('b');
  badge.textContent = event.serverName ? event.serverName.replace('-server','').toUpperCase() : 'RUN';
  badge.className = `badge ${badge.textContent.toLowerCase()}`;
  const text = document.createElement('span');
  text.textContent = event.toolName || event.eventType;
  const state = document.createElement('small');
  state.textContent = event.eventType;
  item.append(badge, text, state);
  timeline.append(item);
}

async function finish(id) {
  stream?.close();
  const response = await fetch(`/api/orchestrations/${id}`);
  const body = await response.json();
  statusEl.textContent = body.status;
  answer.textContent = body.finalAnswer || `${body.errorCode || 'ERROR'}: ${body.errorMessage || 'Flow failed'}`;
  if (body.reportPath) {
    report.href = `/api/orchestrations/${id}/report`;
    report.textContent = body.reportPath;
    report.hidden = false;
  }
  start.disabled = false;
}
