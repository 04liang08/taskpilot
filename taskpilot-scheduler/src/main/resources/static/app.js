const API = '/api/v1';

const state = {
  tab: 'overview',
  page: 1,
  size: 10,
  keyword: '',
  status: '',
  group: '',
  total: 0,
  editingId: null,
};

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

async function api(path, options = {}) {
  const response = await fetch(API + path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  const text = await response.text();
  let body = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch (ignored) {
      body = null;
    }
  }
  if (!response.ok || (body && body.code !== 0)) {
    throw new Error((body && body.message) || `请求失败（HTTP ${response.status}）`);
  }
  return body ? body.data : null;
}

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, (char) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;',
  }[char]));
}

function formatDateTime(value) {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString('zh-CN', { hour12: false });
}

function toInt(value, fallback) {
  const parsed = Number.parseInt(value, 10);
  return Number.isNaN(parsed) ? fallback : parsed;
}

function toast(message, type = 'info') {
  const element = document.createElement('div');
  element.className = `toast ${type}`;
  element.textContent = message;
  $('#toast-container').appendChild(element);
  setTimeout(() => element.remove(), 3200);
}

function switchTab(tab) {
  state.tab = tab;
  history.replaceState(null, '', `#${tab}`);
  $$('.tab').forEach((button) => button.classList.toggle('active', button.dataset.tab === tab));
  $$('.panel').forEach((panel) => panel.classList.toggle('active', panel.id === `panel-${tab}`));
  if (tab === 'overview') {
    loadOverview();
  }
  if (tab === 'tasks') {
    loadTasks();
  }
}

async function loadOverview() {
  try {
    const data = await api('/dashboard/summary');
    $('#stat-task-count').textContent = data.taskCount;
    $('#stat-enabled').textContent = data.enabledTaskCount;
    $('#stat-online').textContent = data.onlineExecutorCount;
    $('#stat-success').textContent = data.todaySuccess;
    $('#stat-failed').textContent = data.todayFailed;
    $('#stat-running').textContent = data.runningCount;
  } catch (error) {
    toast(error.message, 'error');
  }
}

async function loadTasks() {
  const params = new URLSearchParams({ page: state.page, size: state.size });
  if (state.keyword) {
    params.set('keyword', state.keyword);
  }
  if (state.status) {
    params.set('status', state.status);
  }
  if (state.group) {
    params.set('group', state.group);
  }

  try {
    const data = await api(`/tasks?${params.toString()}`);
    state.total = data.total;
    renderTasks(data.records);

    const pages = Math.max(1, Math.ceil(data.total / data.size));
    $('#page-info').textContent = `共 ${data.total} 条 · 第 ${data.page} / ${pages} 页`;
    $('#btn-prev').disabled = data.page <= 1;
    $('#btn-next').disabled = data.page >= pages;
  } catch (error) {
    $('#task-tbody').innerHTML = `<tr><td colspan="8" class="empty">${escapeHtml(error.message)}</td></tr>`;
  }
}

function renderTasks(tasks) {
  const tbody = $('#task-tbody');
  if (!tasks.length) {
    tbody.innerHTML = '<tr><td colspan="8" class="empty">暂无任务</td></tr>';
    return;
  }
  tbody.innerHTML = tasks.map((task) => {
    const enabled = task.status === 'ENABLED';
    return `
      <tr>
        <td>${task.id}</td>
        <td>
          <strong>${escapeHtml(task.taskName)}</strong>
          <div class="sub">${escapeHtml(task.description || '')}</div>
        </td>
        <td>${escapeHtml(task.taskGroup)}</td>
        <td><code>${escapeHtml(task.cronExpression)}</code></td>
        <td>${escapeHtml(task.handlerName)}</td>
        <td><span class="badge ${enabled ? 'on' : 'off'}">${enabled ? '启用' : '停用'}</span></td>
        <td>${task.timeoutSeconds}s</td>
        <td class="actions">
          <button class="link" data-action="edit" data-id="${task.id}">编辑</button>
          <button class="link ${enabled ? 'danger' : 'success'}"
                  data-action="toggle" data-id="${task.id}">${enabled ? '停用' : '启用'}</button>
        </td>
      </tr>`;
  }).join('');
}

function openDialog(task = null) {
  state.editingId = task ? task.id : null;
  $('#dialog-title').textContent = task ? `编辑任务 #${task.id}` : '新建任务';
  clearCronResult();

  const form = $('#task-form');
  form.reset();
  form.elements.timeoutSeconds.value = 60;
  form.elements.shardTotal.value = 1;
  form.elements.retryMax.value = 0;
  form.elements.retryIntervalSeconds.value = 10;

  if (task) {
    form.elements.taskName.value = task.taskName || '';
    form.elements.taskGroup.value = task.taskGroup || '';
    form.elements.cronExpression.value = task.cronExpression || '';
    form.elements.handlerName.value = task.handlerName || '';
    form.elements.handlerParam.value = task.handlerParam || '';
    form.elements.timeoutSeconds.value = task.timeoutSeconds ?? 60;
    form.elements.shardTotal.value = task.shardTotal ?? 1;
    form.elements.retryMax.value = task.retryMax ?? 0;
    form.elements.retryIntervalSeconds.value = task.retryIntervalSeconds ?? 10;
    form.elements.description.value = task.description || '';
  }

  $('#task-dialog').showModal();
}

function clearCronResult() {
  const box = $('#cron-result');
  box.className = 'span-2 cron-result';
  box.innerHTML = '';
}

function showCronResult(message, nextTimes) {
  const box = $('#cron-result');
  if (!nextTimes) {
    box.className = 'span-2 cron-result error';
    box.textContent = message;
    return;
  }
  box.className = 'span-2 cron-result ok';
  box.innerHTML = `<strong>cron 合法</strong><span>未来 5 次触发时间：</span>
    <ul>${nextTimes.map((time) => `<li>${escapeHtml(formatDateTime(time))}</li>`).join('')}</ul>`;
}

async function validateCron() {
  const expression = $('#task-form').elements.cronExpression.value.trim();
  if (!expression) {
    showCronResult('请先填写 cron 表达式');
    return;
  }
  try {
    const result = await api('/cron/validate', {
      method: 'POST',
      body: JSON.stringify({ expression }),
    });
    if (result.valid) {
      showCronResult('ok', result.nextTimes);
    } else {
      showCronResult(result.message || 'cron 表达式不合法');
    }
  } catch (error) {
    showCronResult(error.message);
  }
}

async function submitTask(event) {
  event.preventDefault();
  const form = event.target;
  const payload = {
    taskName: form.elements.taskName.value.trim(),
    taskGroup: form.elements.taskGroup.value.trim() || 'DEFAULT',
    cronExpression: form.elements.cronExpression.value.trim(),
    handlerName: form.elements.handlerName.value.trim(),
    handlerParam: form.elements.handlerParam.value || null,
    shardTotal: toInt(form.elements.shardTotal.value, 1),
    timeoutSeconds: toInt(form.elements.timeoutSeconds.value, 60),
    retryMax: toInt(form.elements.retryMax.value, 0),
    retryIntervalSeconds: toInt(form.elements.retryIntervalSeconds.value, 10),
    description: form.elements.description.value.trim() || null,
  };

  if (!payload.taskName || !payload.cronExpression || !payload.handlerName) {
    toast('任务名称、Cron、处理器名称不能为空', 'error');
    return;
  }

  const saveButton = $('#btn-save');
  saveButton.disabled = true;
  try {
    if (state.editingId) {
      await api(`/tasks/${state.editingId}`, {
        method: 'PUT',
        body: JSON.stringify(payload),
      });
      toast('任务已更新', 'success');
    } else {
      await api('/tasks', {
        method: 'POST',
        body: JSON.stringify(payload),
      });
      toast('任务已创建', 'success');
    }
    $('#task-dialog').close();
    loadTasks();
    loadOverview();
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    saveButton.disabled = false;
  }
}

async function handleTaskAction(event) {
  const button = event.target.closest('button[data-action]');
  if (!button) {
    return;
  }
  const id = button.dataset.id;
  try {
    const task = await api(`/tasks/${id}`);
    if (button.dataset.action === 'edit') {
      openDialog(task);
      return;
    }
    if (button.dataset.action === 'toggle') {
      const action = task.status === 'ENABLED' ? 'disable' : 'enable';
      await api(`/tasks/${id}/${action}`, { method: 'POST' });
      toast(action === 'enable' ? '任务已启用' : '任务已停用', 'success');
      loadTasks();
      loadOverview();
    }
  } catch (error) {
    toast(error.message, 'error');
  }
}

function applyFilters() {
  state.keyword = $('#filter-keyword').value.trim();
  state.status = $('#filter-status').value;
  state.group = $('#filter-group').value.trim();
  state.page = 1;
  loadTasks();
}

function resetFilters() {
  $('#filter-keyword').value = '';
  $('#filter-status').value = '';
  $('#filter-group').value = '';
  state.keyword = '';
  state.status = '';
  state.group = '';
  state.page = 1;
  loadTasks();
}

function bindEvents() {
  $$('.tab').forEach((button) => {
    button.addEventListener('click', () => switchTab(button.dataset.tab));
  });

  $('#refresh-overview').addEventListener('click', loadOverview);
  $('#btn-new-task').addEventListener('click', () => openDialog());
  $('#btn-search').addEventListener('click', applyFilters);
  $('#btn-reset').addEventListener('click', resetFilters);
  $('#filter-keyword').addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      applyFilters();
    }
  });
  $('#btn-prev').addEventListener('click', () => {
    if (state.page > 1) {
      state.page -= 1;
      loadTasks();
    }
  });
  $('#btn-next').addEventListener('click', () => {
    if (state.page * state.size < state.total) {
      state.page += 1;
      loadTasks();
    }
  });
  $('#task-tbody').addEventListener('click', handleTaskAction);
  $('#task-form').addEventListener('submit', submitTask);
  $('#btn-validate-cron').addEventListener('click', validateCron);
  $('#btn-close-dialog').addEventListener('click', () => $('#task-dialog').close());
  $('#btn-cancel').addEventListener('click', () => $('#task-dialog').close());
  $('#task-dialog').addEventListener('click', (event) => {
    if (event.target === $('#task-dialog')) {
      $('#task-dialog').close();
    }
  });

  setInterval(() => {
    if (state.tab === 'overview') {
      loadOverview();
    }
  }, 5000);
}

bindEvents();
const initialTab = location.hash.slice(1);
switchTab(['overview', 'tasks', 'executors', 'executions'].includes(initialTab) ? initialTab : 'overview');