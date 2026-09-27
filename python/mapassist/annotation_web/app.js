"use strict";

const $ = (selector) => document.querySelector(selector);
const state = {
  annotator: localStorage.getItem("mapassist.annotator") || "",
  dataset: localStorage.getItem("mapassist.dataset") || "",
  bootstrap: null,
  task: null,
  image: null,
  boxes: [],
  selected: -1,
  dirty: false,
  view: "minimap",
  drag: null,
  tasks: [],
  busy: false,
};

const canvas = $("#annotationCanvas");
const context = canvas.getContext("2d");
let toastTimer = null;

function datasetUrl(path, dataset = state.dataset) {
  if (!dataset) return path;
  const url = new URL(path, window.location.origin);
  url.searchParams.set("dataset", dataset);
  return `${url.pathname}${url.search}`;
}

async function api(path, options = {}, dataset = state.dataset) {
  const response = await fetch(datasetUrl(path, dataset), {
    ...options,
    headers: {"Content-Type": "application/json", ...(options.headers || {})},
  });
  let body = {};
  try { body = await response.json(); } catch (_) { /* empty */ }
  if (!response.ok) throw new Error(body.error || `请求失败 (${response.status})`);
  return body;
}

function toast(message, error = false) {
  const node = $("#toast");
  node.textContent = message;
  node.className = `toast show${error ? " error" : ""}`;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { node.className = "toast"; }, 3200);
}

function seconds(ms) {
  const total = ms / 1000;
  const minutes = Math.floor(total / 60);
  const rest = (total - minutes * 60).toFixed(3).padStart(6, "0");
  return `${minutes}:${rest}`;
}

function statusLabel(status) {
  return ({pending: "待标注", accepted: "已接受", corrected: "已修正", negative: "负样本", excluded: "非对局界面", skip: "已跳过"})[status] || status;
}

function selectionLabel(selection) {
  return ({
    cue: "提示候选",
    background: "背景候选",
    systematic_blind: "均匀盲测",
    minimap_layout_systematic: "小地图边界",
  })[selection] || selection;
}

async function bootstrap() {
  state.bootstrap = await api("/api/bootstrap");
  state.dataset = state.bootstrap.current_dataset;
  localStorage.setItem("mapassist.dataset", state.dataset);
  const datasetSelect = $("#datasetSelect");
  datasetSelect.innerHTML = "";
  state.bootstrap.datasets.forEach((dataset) => {
    const option = document.createElement("option");
    option.value = dataset.id;
    option.textContent = `${dataset.label} · ${dataset.stats.completed}/${dataset.stats.total}`;
    option.selected = dataset.id === state.dataset;
    datasetSelect.appendChild(option);
  });
  const assistedManual = state.bootstrap.review_mode === "manual" &&
    state.bootstrap.suggestions_available;
  const mode = assistedManual ? "模型辅助人工标注" :
    ({blind: "盲标测试", manual: "人工框标注"})[
      state.bootstrap.review_mode
    ] || "建议框复核";
  $("#datasetLabel").textContent = `${state.bootstrap.kind} · ${mode} · ${state.bootstrap.stats.total} 张图片`;
  const accept = $("#acceptButton");
  accept.querySelector("strong").textContent = "建议框正确";
  accept.querySelector("small").textContent = "直接接受当前建议";
  accept.title = "";
  if (["blind", "manual"].includes(state.bootstrap.review_mode)) {
    accept.disabled = true;
    accept.title = assistedManual ?
      "请逐个检查建议框后按 C 保存；漏框、错框需先修改" :
      "没有建议框：有目标请画框保存，无目标请标负样本";
    accept.querySelector("strong").textContent = assistedManual ?
      "逐框人工确认" : "人工标注无建议";
    accept.querySelector("small").textContent = assistedManual ?
      "检查或修改后按 C，无目标按 N" : "有目标按 C，无目标按 N";
  }
  const filter = $("#matchFilter");
  filter.innerHTML = '<option value="">全部对局</option>';
  state.bootstrap.matches.forEach((match) => {
    const option = document.createElement("option");
    option.value = match.id;
    option.textContent = `${match.id} · ${match.split} · ${match.samples} 张`;
    filter.appendChild(option);
  });
  renderStats(state.bootstrap.stats);
  await loadQueue();
}

async function switchDataset(dataset) {
  if (!dataset || dataset === state.dataset || state.busy) return;
  const previous = state.dataset;
  if (state.dirty && !confirm("当前框有未保存修改，确定切换数据集吗？")) {
    $("#datasetSelect").value = previous;
    return;
  }
  state.busy = true;
  try {
    if (state.task && state.annotator) {
      try {
        await api(`/api/tasks/${state.task.id}/release`, {
          method: "POST", body: JSON.stringify({annotator: state.annotator}),
        }, previous);
      } catch (_) { /* expired or completed leases need no release */ }
    }
    clearTask();
    state.dataset = dataset;
    localStorage.setItem("mapassist.dataset", dataset);
    await bootstrap();
    toast(`已切换到 ${dataset}`);
  } catch (error) {
    state.dataset = previous;
    localStorage.setItem("mapassist.dataset", previous);
    $("#datasetSelect").value = previous;
    toast(error.message, true);
  } finally { state.busy = false; }
}

function renderStats(stats) {
  const total = stats.total || 0;
  const completed = stats.completed || 0;
  const percent = total ? Math.round(completed / total * 100) : 0;
  $("#progressText").textContent = `${completed} / ${total}`;
  $("#progressPercent").textContent = `${percent}%`;
  $("#progressBar").style.width = `${percent}%`;
  $("#queueSummary").textContent = `待标注 ${stats.counts.pending || 0} · 已完成 ${completed}`;
  const contributors = $("#contributors");
  contributors.innerHTML = "";
  if (!stats.contributors.length) {
    contributors.innerHTML = '<span class="muted">还没有提交记录</span>';
  } else {
    stats.contributors.forEach((item) => {
      const row = document.createElement("div");
      row.className = "contributor";
      row.innerHTML = `<span>${escapeHtml(item.name)}</span><span>${item.count} 张</span>`;
      contributors.appendChild(row);
    });
  }
}

function escapeHtml(value) {
  const element = document.createElement("span");
  element.textContent = String(value);
  return element.innerHTML;
}

async function refreshStats() {
  try { renderStats(await api("/api/stats")); } catch (error) { toast(error.message, true); }
}

async function loadQueue() {
  const status = $("#statusFilter").value;
  const match = $("#matchFilter").value;
  const mine = $("#mineFilter").checked;
  const params = new URLSearchParams({status, limit: "200"});
  if (match) params.set("match_id", match);
  if (mine && status !== "pending" && state.annotator) params.set("annotator", state.annotator);
  try {
    const result = await api(`/api/tasks?${params}`);
    state.tasks = result.tasks;
    renderQueue();
  } catch (error) { toast(error.message, true); }
}

function renderQueue() {
  const list = $("#taskList");
  list.innerHTML = "";
  if (!state.tasks.length) {
    list.innerHTML = '<div class="muted" style="padding:18px 4px">这个筛选条件下没有图片。</div>';
    return;
  }
  state.tasks.forEach((task) => {
    const button = document.createElement("button");
    const leasedByOther = task.lease_owner && task.lease_owner !== state.annotator;
    button.className = `task-card${state.task && state.task.id === task.id ? " active" : ""}`;
    button.disabled = leasedByOther;
    const right = leasedByOther ? `${task.lease_owner} 标注中` : statusLabel(task.review_status);
    button.innerHTML = `
      <span class="stripe ${task.selection}"></span>
      <span><strong>${escapeHtml(task.match_id)} · ${seconds(task.at_ms)}</strong><small>${selectionLabel(task.selection)} · ${task.split}</small></span>
      <span class="state">${escapeHtml(right)}</span>`;
    button.addEventListener("click", () => claimTask(task.id));
    list.appendChild(button);
  });
}

function requireAnnotator() {
  if (!state.annotator) {
    $("#loginDialog").classList.remove("hidden");
    return false;
  }
  return true;
}

async function claimTask(id) {
  if (!requireAnnotator()) return;
  if (state.busy) return;
  if (state.dirty && !confirm("当前框有未保存修改，确定切换图片吗？")) return;
  state.busy = true;
  setLoading(true);
  try {
    const task = await api(`/api/tasks/${id}/claim`, {method: "POST", body: JSON.stringify({annotator: state.annotator})});
    await showTask(task);
  } catch (error) {
    toast(error.message, true);
    await loadQueue();
  } finally { state.busy = false; setLoading(false); }
}

async function nextTask() {
  if (!requireAnnotator()) return;
  if (state.busy) return;
  if (state.dirty && !confirm("当前框有未保存修改，确定领取下一张吗？")) return;
  state.busy = true;
  setLoading(true);
  try {
    const result = await api("/api/tasks/next", {
      method: "POST",
      body: JSON.stringify({annotator: state.annotator, match_id: $("#matchFilter").value || null}),
    });
    if (!result.task) {
      toast("当前对局没有可领取的待标注图片");
      clearTask("当前队列已处理完", "可以切换对局或状态筛选查看结果。");
      return;
    }
    await showTask(result.task);
  } catch (error) { toast(error.message, true); }
  finally { state.busy = false; setLoading(false); }
}

function cloneBoxes(boxes) {
  return (boxes || []).map((box) => box.map(Number));
}

function taskRoi(task = state.task) {
  return task?.roi || state.bootstrap?.roi || [0, 0, 1, 1];
}

function initialBoxes(task) {
  if (task.review_status === "corrected" && task.reviewed_boxes) return cloneBoxes(task.reviewed_boxes);
  if (["negative", "skip", "excluded"].includes(task.review_status)) return [];
  return cloneBoxes(task.suggested_boxes);
}

async function showTask(task) {
  state.task = task;
  state.image = null;
  state.boxes = initialBoxes(task);
  state.selected = -1;
  state.dirty = false;
  $("#canvasShell").classList.remove("empty");
  $("#emptyState").classList.add("hidden");
  $("#taskMeta").textContent = `${task.match_id.toUpperCase()} · ${task.split.toUpperCase()} · ${selectionLabel(task.selection)}`;
  $("#taskTitle").textContent = `${seconds(task.at_ms)} · ${statusLabel(task.review_status)}`;
  setControls(true);
  const image = new Image();
  const taskId = task.id;
  image.decoding = "async";
  image.onload = () => {
    if (!state.task || state.task.id !== taskId) return;
    state.image = image;
    resizeCanvas();
    renderBoxCount();
    setLoading(false);
  };
  image.onerror = () => {
    if (!state.task || state.task.id !== taskId) return;
    setLoading(false);
    toast("图片加载失败", true);
  };
  const imageUrl = datasetUrl(`/media?path=${encodeURIComponent(task.frame)}&v=${task.version}`);
  image.src = imageUrl;
  showContextPreview(task, imageUrl);
  loadTemporalContext(task);
  await loadQueue();
  if (window.matchMedia("(max-width: 820px)").matches) {
    $(".annotation-panel").scrollIntoView({behavior: "smooth", block: "start"});
  }
}

function showContextPreview(task, imageUrl) {
  const preview = $("#contextPreview");
  const contextImage = $("#contextImage");
  const imageWrap = preview.querySelector(".context-image-wrap");
  const marker = $("#contextMinimapMarker");
  const [x, y, w, h] = taskRoi(task);
  contextImage.onload = () => {
    if (contextImage.naturalWidth && contextImage.naturalHeight) {
      imageWrap.style.aspectRatio = `${contextImage.naturalWidth} / ${contextImage.naturalHeight}`;
    }
  };
  contextImage.src = imageUrl;
  contextImage.alt = `${task.match_id} ${seconds(task.at_ms)} 的完整游戏画面`;
  marker.style.left = `${x * 100}%`;
  marker.style.top = `${y * 100}%`;
  marker.style.width = `${w * 100}%`;
  marker.style.height = `${h * 100}%`;
  preview.classList.remove("empty");
  preview.disabled = false;
}

function clearContextPreview() {
  const preview = $("#contextPreview");
  const contextImage = $("#contextImage");
  preview.classList.add("empty");
  preview.disabled = true;
  contextImage.onload = null;
  contextImage.removeAttribute("src");
  contextImage.alt = "";
}

function clearTemporalContext() {
  $("#temporalContext").classList.add("hidden");
  $("#temporalContext").classList.remove("unavailable");
  ["contextBeforeCanvas", "contextAfterCanvas"].forEach((id) => {
    const temporalCanvas = $(`#${id}`);
    temporalCanvas.width = 1;
    temporalCanvas.height = 1;
  });
}

function drawTemporalMinimap(temporalCanvas, image, task) {
  const [x, y, w, h] = taskRoi(task);
  const sourceWidth = w * image.naturalWidth;
  const sourceHeight = h * image.naturalHeight;
  const targetWidth = 240;
  const targetHeight = Math.max(1, Math.round(targetWidth * sourceHeight / sourceWidth));
  temporalCanvas.width = targetWidth;
  temporalCanvas.height = targetHeight;
  const temporalContext = temporalCanvas.getContext("2d");
  temporalContext.imageSmoothingEnabled = false;
  temporalContext.drawImage(
    image, x * image.naturalWidth, y * image.naturalHeight,
    sourceWidth, sourceHeight, 0, 0, targetWidth, targetHeight,
  );
}

function loadTemporalContext(task) {
  clearTemporalContext();
  const offsets = state.bootstrap.context_offsets_ms || [];
  const matches = state.bootstrap.context_matches || [];
  if (!offsets.includes(-500) || !offsets.includes(500) || !matches.includes(task.match_id)) return;
  const section = $("#temporalContext");
  section.classList.remove("hidden");
  [[-500, "contextBeforeCanvas"], [500, "contextAfterCanvas"]].forEach(([offset, id]) => {
    const temporalImage = new Image();
    const taskId = task.id;
    temporalImage.onload = () => {
      if (!state.task || state.task.id !== taskId) return;
      drawTemporalMinimap($(`#${id}`), temporalImage, task);
    };
    temporalImage.onerror = () => {
      if (state.task && state.task.id === taskId) section.classList.add("unavailable");
    };
    temporalImage.src = datasetUrl(`/api/tasks/${task.id}/context?offset_ms=${offset}&v=${task.version}`);
  });
}

function clearTask(title = "从左侧领取一张图片", detail = "等待领取图片") {
  state.task = null;
  state.image = null;
  state.boxes = [];
  state.selected = -1;
  state.drag = null;
  state.dirty = false;
  $("#taskMeta").textContent = "尚未领取任务";
  $("#taskTitle").textContent = title;
  $("#emptyState strong").textContent = detail;
  $("#canvasShell").classList.add("empty");
  $("#emptyState").classList.remove("hidden");
  clearContextPreview();
  clearTemporalContext();
  setControls(false);
  renderBoxCount();
}

function setLoading(value) {
  $("#loadingState").classList.toggle("hidden", !value);
}

function setControls(enabled) {
  ["acceptButton", "correctButton", "negativeButton", "excludedButton", "skipButton", "resetBoxesButton", "deleteBoxButton", "releaseTaskButton"]
    .forEach((id) => { $(`#${id}`).disabled = !enabled; });
  if (["blind", "manual"].includes(state.bootstrap?.review_mode)) {
    $("#acceptButton").disabled = true;
  }
  if (state.bootstrap?.kind === "minimap_region") {
    $("#negativeButton").disabled = true;
  }
}

function viewRect() {
  if (state.view === "minimap" && state.bootstrap) {
    const [x, y, w, h] = taskRoi();
    return {x, y, w, h};
  }
  return {x: 0, y: 0, w: 1, h: 1};
}

function annotationBounds() {
  if (!state.bootstrap) return {x: 0, y: 0, w: 1, h: 1};
  const [x, y, w, h] = taskRoi();
  return {x, y, w, h};
}

function pointInBounds(point, bounds) {
  return point.x >= bounds.x && point.x <= bounds.x + bounds.w &&
    point.y >= bounds.y && point.y <= bounds.y + bounds.h;
}

function resizeCanvas() {
  if (!state.image) return;
  const shell = $("#canvasShell");
  const view = viewRect();
  const aspect = (state.image.width * view.w) / (state.image.height * view.h);
  const cssWidth = Math.max(300, shell.clientWidth);
  const maxHeight = Math.min(window.innerHeight * .68, 680);
  const cssHeight = Math.min(cssWidth / aspect, maxHeight);
  const actualWidth = cssHeight === maxHeight ? maxHeight * aspect : cssWidth;
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  canvas.style.width = `${actualWidth}px`;
  canvas.style.height = `${cssHeight}px`;
  canvas.width = Math.round(actualWidth * dpr);
  canvas.height = Math.round(cssHeight * dpr);
  draw();
}

function draw() {
  if (!state.image) return;
  const view = viewRect();
  const sx = view.x * state.image.width;
  const sy = view.y * state.image.height;
  const sw = view.w * state.image.width;
  const sh = view.h * state.image.height;
  context.clearRect(0, 0, canvas.width, canvas.height);
  context.imageSmoothingEnabled = state.view !== "minimap";
  context.drawImage(state.image, sx, sy, sw, sh, 0, 0, canvas.width, canvas.height);
  state.boxes.forEach((box, index) => drawBox(box, index === state.selected));
}

function drawBox(box, selected) {
  const view = viewRect();
  const x = (box[0] - view.x) / view.w * canvas.width;
  const y = (box[1] - view.y) / view.h * canvas.height;
  const w = box[2] / view.w * canvas.width;
  const h = box[3] / view.h * canvas.height;
  if (x + w < 0 || y + h < 0 || x > canvas.width || y > canvas.height) return;
  const scale = canvas.width / Math.max(1, canvas.clientWidth);
  context.lineWidth = (selected ? 3 : 2) * scale;
  context.strokeStyle = selected ? "#f6c85f" : "#35e1a2";
  context.fillStyle = selected ? "rgba(246,200,95,.13)" : "rgba(53,225,162,.10)";
  context.fillRect(x, y, w, h);
  context.strokeRect(x, y, w, h);
  if (selected) {
    const radius = 6 * scale;
    context.beginPath();
    context.arc(x + w, y + h, radius, 0, Math.PI * 2);
    context.fillStyle = "#f6c85f";
    context.fill();
  }
}

function pointFromEvent(event) {
  const rect = canvas.getBoundingClientRect();
  const xCanvas = (event.clientX - rect.left) / rect.width * canvas.width;
  const yCanvas = (event.clientY - rect.top) / rect.height * canvas.height;
  const view = viewRect();
  return {
    x: view.x + xCanvas / canvas.width * view.w,
    y: view.y + yCanvas / canvas.height * view.h,
    xCanvas, yCanvas,
  };
}

function clamp(value, low, high) { return Math.max(low, Math.min(high, value)); }

function hitBox(point) {
  const view = viewRect();
  const handle = 11 * canvas.width / Math.max(1, canvas.clientWidth);
  for (let index = state.boxes.length - 1; index >= 0; index--) {
    const box = state.boxes[index];
    const right = (box[0] + box[2] - view.x) / view.w * canvas.width;
    const bottom = (box[1] + box[3] - view.y) / view.h * canvas.height;
    if (Math.hypot(point.xCanvas - right, point.yCanvas - bottom) <= handle) return {index, mode: "resize"};
    if (point.x >= box[0] && point.x <= box[0] + box[2] && point.y >= box[1] && point.y <= box[1] + box[3]) return {index, mode: "move"};
  }
  return null;
}

canvas.addEventListener("pointerdown", (event) => {
  if (!state.task || !state.image) return;
  const point = pointFromEvent(event);
  const hit = hitBox(point);
  if (!hit && !pointInBounds(point, annotationBounds())) return;
  canvas.setPointerCapture(event.pointerId);
  if (hit) {
    state.selected = hit.index;
    state.drag = {mode: hit.mode, start: point, original: [...state.boxes[hit.index]]};
  } else {
    state.selected = -1;
    state.drag = {mode: "draw", start: point, current: point};
  }
  draw();
});

canvas.addEventListener("pointermove", (event) => {
  if (!state.drag) return;
  const point = pointFromEvent(event);
  const bounds = annotationBounds();
  if (state.drag.mode === "draw") {
    state.drag.current = point;
    draw();
    const x = Math.min(state.drag.start.x, point.x);
    const y = Math.min(state.drag.start.y, point.y);
    drawBox([x, y, Math.abs(point.x - state.drag.start.x), Math.abs(point.y - state.drag.start.y)], true);
    return;
  }
  const box = [...state.drag.original];
  if (state.drag.mode === "move") {
    box[0] = clamp(box[0] + point.x - state.drag.start.x, bounds.x, bounds.x + bounds.w - box[2]);
    box[1] = clamp(box[1] + point.y - state.drag.start.y, bounds.y, bounds.y + bounds.h - box[3]);
  } else {
    box[2] = clamp(box[2] + point.x - state.drag.start.x, .002, bounds.x + bounds.w - box[0]);
    box[3] = clamp(box[3] + point.y - state.drag.start.y, .002, bounds.y + bounds.h - box[1]);
  }
  state.boxes[state.selected] = box;
  state.dirty = true;
  renderBoxCount();
  draw();
});

canvas.addEventListener("pointerup", (event) => {
  if (!state.drag) return;
  if (state.drag.mode === "draw") {
    const point = pointFromEvent(event);
    const bounds = annotationBounds();
    const x = clamp(Math.min(state.drag.start.x, point.x), bounds.x, bounds.x + bounds.w);
    const y = clamp(Math.min(state.drag.start.y, point.y), bounds.y, bounds.y + bounds.h);
    const right = clamp(Math.max(state.drag.start.x, point.x), bounds.x, bounds.x + bounds.w);
    const bottom = clamp(Math.max(state.drag.start.y, point.y), bounds.y, bounds.y + bounds.h);
    if (right - x > .002 && bottom - y > .002) {
      state.boxes.push([x, y, right - x, bottom - y]);
      state.selected = state.boxes.length - 1;
      state.dirty = true;
    }
  }
  state.drag = null;
  renderBoxCount();
  draw();
});

canvas.addEventListener("pointercancel", () => { state.drag = null; draw(); });

function renderBoxCount() {
  $("#boxCount").textContent = state.boxes.length;
  $("#deleteBoxButton").disabled = !state.task || state.selected < 0;
}

function deleteSelected() {
  if (state.selected < 0) return;
  state.boxes.splice(state.selected, 1);
  state.selected = -1;
  state.dirty = true;
  renderBoxCount();
  draw();
}

function resetBoxes() {
  if (!state.task) return;
  state.boxes = cloneBoxes(state.task.suggested_boxes);
  state.selected = -1;
  state.dirty = false;
  renderBoxCount();
  draw();
}

async function save(status) {
  if (!state.task || !requireAnnotator()) return;
  if (state.busy) return;
  if (status === "corrected" && !state.boxes.length) {
    toast("没有框时请使用“没有敌人”", true);
    return;
  }
  state.busy = true;
  setControls(false);
  try {
    const saved = await api(`/api/tasks/${state.task.id}`, {
      method: "PUT",
      body: JSON.stringify({
        annotator: state.annotator,
        version: state.task.version,
        status,
        boxes: status === "corrected" ? state.boxes : undefined,
      }),
    });
    state.dirty = false;
    toast(`${statusLabel(status)}已保存`);
    await Promise.all([refreshStats(), loadQueue()]);
    if (state.task && state.task.id === saved.id) clearTask();
    state.busy = false;
    await nextTask();
  } catch (error) {
    toast(error.message, true);
    setControls(true);
  } finally {
    state.busy = false;
  }
}

async function releaseTask() {
  if (!state.task || !requireAnnotator() || state.busy) return;
  if (state.dirty && !confirm("当前框有未保存修改，确定释放任务吗？")) return;
  state.busy = true;
  setControls(false);
  try {
    await api(`/api/tasks/${state.task.id}/release`, {
      method: "POST", body: JSON.stringify({annotator: state.annotator}),
    });
    toast("任务已归还队列");
    clearTask();
    await Promise.all([refreshStats(), loadQueue()]);
  } catch (error) {
    toast(error.message, true);
    setControls(true);
  } finally { state.busy = false; }
}

function setView(mode) {
  state.view = mode;
  $("#minimapMode").classList.toggle("active", mode === "minimap");
  $("#fullMode").classList.toggle("active", mode === "full");
  resizeCanvas();
}

function login(name) {
  state.annotator = name.trim();
  localStorage.setItem("mapassist.annotator", state.annotator);
  $("#annotatorName").textContent = state.annotator;
  $("#loginDialog").classList.add("hidden");
  loadQueue();
}

$("#loginForm").addEventListener("submit", (event) => {
  event.preventDefault();
  const name = $("#annotatorInput").value.trim();
  if (name) login(name);
});
$("#changeUserButton").addEventListener("click", () => {
  $("#annotatorInput").value = state.annotator;
  $("#loginDialog").classList.remove("hidden");
  $("#annotatorInput").focus();
});
$("#nextButton").addEventListener("click", nextTask);
$("#refreshQueueButton").addEventListener("click", loadQueue);
$("#refreshStatsButton").addEventListener("click", refreshStats);
$("#datasetSelect").addEventListener("change", (event) => switchDataset(event.target.value));
$("#matchFilter").addEventListener("change", loadQueue);
$("#statusFilter").addEventListener("change", loadQueue);
$("#mineFilter").addEventListener("change", loadQueue);
$("#minimapMode").addEventListener("click", () => setView("minimap"));
$("#fullMode").addEventListener("click", () => setView("full"));
$("#contextPreview").addEventListener("click", () => setView("full"));
$("#deleteBoxButton").addEventListener("click", deleteSelected);
$("#resetBoxesButton").addEventListener("click", resetBoxes);
$("#releaseTaskButton").addEventListener("click", releaseTask);
$("#acceptButton").addEventListener("click", () => {
  if (!["blind", "manual"].includes(state.bootstrap?.review_mode)) save("accepted");
});
$("#correctButton").addEventListener("click", () => save("corrected"));
$("#negativeButton").addEventListener("click", () => save("negative"));
$("#excludedButton").addEventListener("click", () => save("excluded"));
$("#skipButton").addEventListener("click", () => save("skip"));
window.addEventListener("resize", resizeCanvas);
window.addEventListener("beforeunload", (event) => {
  if (state.dirty) { event.preventDefault(); event.returnValue = ""; }
});
window.addEventListener("keydown", (event) => {
  if (event.target.matches("input, select, textarea")) return;
  if (event.key === "Enter") { event.preventDefault(); nextTask(); }
  else if (event.key.toLowerCase() === "a" &&
           !["blind", "manual"].includes(state.bootstrap?.review_mode)) save("accepted");
  else if (event.key.toLowerCase() === "c") save("corrected");
  else if (event.key.toLowerCase() === "n") save("negative");
  else if (event.key.toLowerCase() === "e") save("excluded");
  else if (event.key.toLowerCase() === "s") save("skip");
  else if (event.key.toLowerCase() === "r") resetBoxes();
  else if (event.key.toLowerCase() === "z") setView(state.view === "minimap" ? "full" : "minimap");
  else if (event.key === "Delete" || event.key === "Backspace") deleteSelected();
});

setControls(false);
if (state.annotator) {
  $("#annotatorName").textContent = state.annotator;
  $("#loginDialog").classList.add("hidden");
} else {
  setTimeout(() => $("#annotatorInput").focus(), 50);
}

bootstrap().catch((error) => toast(error.message, true));
setInterval(refreshStats, 15000);
setInterval(async () => {
  if (!state.task || !state.annotator) return;
  try {
    state.task = await api(`/api/tasks/${state.task.id}/heartbeat`, {
      method: "POST", body: JSON.stringify({annotator: state.annotator}),
    });
  } catch (_) { /* a completed or expired task does not need a heartbeat */ }
}, 120000);
