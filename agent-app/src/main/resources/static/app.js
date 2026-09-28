const byId = id => document.getElementById(id);
const button = byId('run');
const terminal = new Set(['COMPLETED','FAILED','CANCELLED']);
let source;

button.addEventListener('click', async () => {
  byId('submit-error').textContent = '';
  button.disabled = true;
  try {
    const response = await fetch('/api/pipelines', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({message:byId('request').value})});
    const body = await response.json();
    if (!response.ok) throw new Error(body.message || 'Не удалось запустить pipeline');
    showRun(body.runId);
  } catch (error) {
    byId('submit-error').textContent = error.message;
  } finally {
    button.disabled = false;
  }
});

function showRun(runId) {
  if (source) source.close();
  byId('result').classList.remove('hidden');
  byId('run-id').textContent = runId;
  source = new EventSource(`/api/pipelines/${encodeURIComponent(runId)}/events/stream`);
  source.addEventListener('snapshot', event => render(JSON.parse(event.data)));
  source.addEventListener('pipeline-event', () => refresh(runId));
  source.onerror = () => { if (source.readyState === EventSource.CLOSED) refresh(runId); };
}

async function refresh(runId) {
  const response = await fetch(`/api/pipelines/${encodeURIComponent(runId)}`);
  if (!response.ok) return;
  const run = await response.json();
  render(run);
  if (terminal.has(run.status)) {
    source?.close();
    if (run.status === 'COMPLETED') loadReport(runId);
  }
}

function render(run) {
  byId('status').textContent = run.status;
  const list = byId('timeline'); list.replaceChildren();
  const labels = ['Получение прогноза','Подготовка сводки','Сохранение файла'];
  run.steps.forEach((step,index) => {
    const li=document.createElement('li');
    const icon=document.createElement('span');
    icon.textContent=step.status==='SUCCEEDED'?'✓':step.status==='RUNNING'?'●':step.status==='FAILED'?'×':'○';
    icon.className=step.status==='SUCCEEDED'?'ok':step.status==='RUNNING'?'running':step.status==='FAILED'?'failed':'';
    const detail=document.createElement('div');
    const title=document.createElement('strong'); title.textContent=labels[index]||step.tool; detail.append(title);
    const meta=document.createElement('div'); meta.className='meta';
    meta.textContent=[step.inputArtifactId&&`in: ${step.inputArtifactId}`,step.outputArtifactId&&`out: ${step.outputArtifactId}`].filter(Boolean).join(' · ');
    detail.append(meta);
    const time=document.createElement('time'); time.textContent=step.durationMs==null?'':`${(step.durationMs/1000).toFixed(2)} с`;
    li.append(icon,detail,time); list.append(li);
  });
  const fileRow=byId('file-row'); fileRow.classList.toggle('hidden',!run.resultFile); byId('file').textContent=run.resultFile||'';
  const error=byId('error'); error.classList.toggle('hidden',!run.error); error.textContent=run.error?`${run.error.code}: ${run.error.message}`:'';
}

async function loadReport(runId) {
  const response=await fetch(`/api/pipelines/${encodeURIComponent(runId)}/report`);
  if (!response.ok) return;
  const report=await response.json();
  byId('report').textContent=report.markdown;
  byId('preview').classList.remove('hidden');
}
