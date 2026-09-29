"use strict";

const $ = (selector) => document.querySelector(selector);
const state = {
  annotator: localStorage.getItem("mapassist.annotator") || "",
  dataset: new URLSearchParams(window.location.search).get("dataset") ||
    localStorage.getItem("mapassist.dataset") || "",
  bootstrap: null,
  task: null,
  image: null,
  boxes: [],
  categories: [],
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
    green_ring_suggestion: "绿色外圈建议",
    empty: "机器空框",
    systematic_blind: "均匀盲测",
    systematic_development: "均匀开发抽样",
    minimap_layout_systematic: "小地图边界",
  })[selection] || selection;
}

function categoryLabel(category) {
  return ({
    minimap_enemy: "敌方英雄",
    minimap_player: "自己",
    main_enemy: "主画面敌人",
    minimap_region: "小地图范围",
  })[category] || category;
}

function configureWorkbenchCopy(kind, multiclass) {
  const isMainEnemy = kind === "main_enemy";
  const isMinimapPlayer = kind === "minimap_player";
  const copy = isMainEnemy ? {
    title: "主画面边缘数据标注台",
    roiMode: "主画面标注区",
    context: "确认画面处于对局中，再检查左右边缘",
    temporal: "相邻帧可帮助区分真实血条、短暂特效和装饰线",
    guides: [
      ["只框真实敌方威胁", "只标主画面左右边缘可见的敌方英雄血条或明确敌方标记，框住完整可见证据。"],
      ["补齐全部漏框", "左右边缘出现多个敌方目标时必须逐个框出。"],
      ["排除界面与友方元素", "技能区、商店、系统 UI、友方血条、地图图标和装饰红线都不标。"],
      ["识别对局边界", "准备、选人、加载和结算画面选择“非对局界面”，不要当负样本。"],
      ["不完整或模糊时跳过", "无法确认是敌方目标，或关键标记被画面边缘切掉时，选择“无法判断”。"],
      ["只标左右边缘", "仅标注边缘感知区域中的目标；中央战斗目标不属于本队列。"],
      ["机器结果只作建议", "每个建议框和机器空框都要人工检查；修正后按“保存修正”，不得直接当作真值。"],
    ],
  } : isMinimapPlayer ? {
    title: "玩家小地图数据标注台",
    roiMode: "放大小地图",
    context: "先确认这是对局中画面，再标自己的小地图头像",
    temporal: "自己的绿色外圈头像会随位置移动；固定地图图标通常保持不动",
    guides: [
      ["只框自己的绿色外圈头像", "只标小地图上带绿色外圈的自己头像，框住头像和完整绿色外圈，尽量贴边。"],
      ["补齐自己的漏框", "发现自己的绿色外圈头像就逐个框出；机器已有框也要重新确认。"],
      ["排除敌方和地图符号", "敌方红色头像、队友头像、防御塔、路径、基地、技能特效和信号圈都不标。"],
      ["识别对局边界", "准备、选人、加载和结算画面选择“非对局界面”，不要当负样本。"],
      ["检查小地图裁剪边缘", "目标碰到裁剪框边缘时可能已被切掉；无法确认完整目标请选“无法判断”，不能当完整框或负样本。"],
      ["按图标中心判断范围", "只标中心位于标注范围内的自己头像；忽略安全边距里的队伍头像和地图外 HUD。紫红实线是中心范围，青色虚线是地图/方向参考；两者都不限制画框。"],
      ["机器结果只作弱建议", "机器框只是弱建议；机器空框不等于负样本，仍须人工确认。有自己的绿色外圈头像就画框，没有才按“负样本”保存。"],
    ],
  } : {
    title: "小地图数据标注台",
    roiMode: "放大小地图",
    context: "先确认这是对局中画面，再标小地图",
    temporal: "移动头像会改变位置；固定图标通常保持不动",
    guides: [
      [multiclass ? "逐框确认目标类别" : "只框红方英雄头像",
        multiclass ? "敌方头像与自己的头像都要框准，并在画布下方选择对应类别。" :
          "框住头像和红色阵营环，尽量贴边。"],
      ["补齐所有漏框", "一张图里有多个敌人时必须全部框出。"],
      ["排除地图符号", "防御塔、路径、基地、技能特效和信号圈都不标。"],
      ["识别对局边界", "准备、选人、加载和结算画面选择“非对局界面”，不要当负样本。"],
      ["检查小地图裁剪边缘", "目标碰到裁剪框边缘时可能已被切掉；无法确认完整目标请选“无法判断”，不能当完整框或负样本。"],
      ["按图标中心判断范围", "只标中心位于标注范围内的敌方英雄；忽略安全边距里的队伍头像和地图外 HUD。紫红实线是中心范围，青色虚线是地图/方向参考；两者都不限制画框。"],
      ["旧标注只作建议", "历史框可能在旧 ROI 右侧被截断；请人工检查、拖右侧中点扩宽或重新画框，再按“保存修正”。建议框不会自动成为真值。"],
    ],
  };
  document.title = copy.title;
  $("#workbenchTitle").textContent = copy.title;
  $("#roiModeLabel").textContent = copy.roiMode;
  $("#contextGuide").textContent = copy.context;
  $("#temporalGuide").textContent = copy.temporal;
  const names = ["primary", "second", "third", "fourth", "fifth", "sixth", "seventh"];
  names.forEach((name, index) => {
    $(`#${name}GuideTitle`).textContent = copy.guides[index][0];
    $(`#${name}GuideDetail`).textContent = copy.guides[index][1];
  });
  $("#annotationCanvas").setAttribute(
    "aria-label", isMainEnemy ? "主画面边缘标注画布" : "小地图标注画布",
  );
  $("#contextBeforeCanvas").setAttribute(
    "aria-label", isMainEnemy ? "当前帧之前 0.5 秒的主画面" : "当前帧之前 0.5 秒的小地图",
  );
  $("#contextAfterCanvas").setAttribute(
    "aria-label", isMainEnemy ? "当前帧之后 0.5 秒的主画面" : "当前帧之后 0.5 秒的小地图",
  );
  const negative = $("#negativeButton");
  negative.querySelector("strong").textContent = isMinimapPlayer ?
    "没有自己头像" : isMainEnemy ? "没有边缘敌人" : "没有敌人";
  negative.querySelector("small").textContent = isMinimapPlayer ?
    "确认本帧没有玩家头像" : isMainEnemy ? "确认左右边缘无敌人" : "标记为负样本";
}

async function bootstrap() {
  state.bootstrap = await api("/api/bootstrap");
  state.dataset = state.bootstrap.current_dataset;
  localStorage.setItem("mapassist.dataset", state.dataset);
  const currentUrl = new URL(window.location.href);
  currentUrl.searchParams.set("dataset", state.dataset);
  window.history.replaceState(null, "", `${currentUrl.pathname}${currentUrl.search}`);
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
  const mode = assistedManual ? "建议框辅助人工标注" :
    ({blind: "盲标测试", manual: "人工框标注"})[
      state.bootstrap.review_mode
    ] || "建议框复核";
  $("#datasetLabel").textContent = `${state.bootstrap.kind} · ${mode} · ${state.bootstrap.stats.total} 张图片`;
  const assistanceNotice = $("#labelAssistanceNotice");
  const noticeText = typeof state.bootstrap.label_assistance?.notice === "string" ?
    state.bootstrap.label_assistance.notice.trim() : "";
  assistanceNotice.textContent = noticeText;
  assistanceNotice.classList.toggle("hidden", !noticeText);
  const hasLabelRoi = state.bootstrap.label_roi ||
    state.bootstrap.matches.some((match) => match.label_roi);
  const hasWidgetRoi = state.bootstrap.widget_roi ||
    state.bootstrap.matches.some((match) => match.widget_roi);
  $("#labelRoiLegend").classList.toggle("hidden", !hasLabelRoi);
  $("#widgetRoiLegend").classList.toggle("hidden", !hasWidgetRoi);
  $("#roiLegend").classList.toggle("hidden", !hasLabelRoi && !hasWidgetRoi);
  const classes = state.bootstrap.classes || [state.bootstrap.kind];
  const categorySelect = $("#boxCategorySelect");
  categorySelect.innerHTML = "";
  classes.forEach((category) => {
    const option = document.createElement("option");
    option.value = category;
    option.textContent = categoryLabel(category);
    categorySelect.appendChild(option);
  });
  const multiclass = classes.length > 1;
  $("#categoryEditor").classList.toggle("hidden", !multiclass);
  configureWorkbenchCopy(state.bootstrap.kind, multiclass);
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
      <span><strong>${escapeHtml(task.match_id)} · ${seconds(task.at_ms)}</strong><small>${selectionLabel(task.selection)} · ${task.split} · ${window.MapassistSuggestionHint.formatSuggestionHint(task.suggested_boxes)}</small></span>
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

function defaultCategory() {
  return state.bootstrap?.classes?.[0] || state.bootstrap?.kind || "minimap_enemy";
}

function cloneCategories(categories, boxes) {
  const result = Array.isArray(categories) ? categories.slice(0, boxes.length) : [];
  while (result.length < boxes.length) result.push(defaultCategory());
  return result;
}

function taskRoi(task = state.task) {
  return task?.roi || state.bootstrap?.roi || [0, 0, 1, 1];
}

function taskLabelRoi(task = state.task) {
  return task ? task.label_roi : (state.bootstrap?.label_roi || null);
}

function initialBoxes(task) {
  if (task.review_status === "corrected" && task.reviewed_boxes) return cloneBoxes(task.reviewed_boxes);
  if (["negative", "skip", "excluded"].includes(task.review_status)) return [];
  return cloneBoxes(task.suggested_boxes);
}

function initialCategories(task, boxes) {
  if (task.review_status === "corrected" && task.reviewed_categories) {
    return cloneCategories(task.reviewed_categories, boxes);
  }
  if (["negative", "skip", "excluded"].includes(task.review_status)) return [];
  return cloneCategories(task.suggested_categories, boxes);
}

async function showTask(task) {
  state.task = task;
  state.image = null;
  state.boxes = initialBoxes(task);
  state.categories = initialCategories(task, state.boxes);
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
  state.categories = [];
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
  $("#boxCategorySelect").disabled = !enabled || state.selected < 0;
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
  const labelRoi = state.bootstrap?.kind === "minimap_enemy" ? taskLabelRoi() : null;
  drawRoiBoundary(labelRoi, "#ff65d4", false);
  const widgetRoi = state.task ? state.task.widget_roi : state.bootstrap?.widget_roi;
  drawRoiBoundary(widgetRoi || null,
    "#4de1e8", true);
  state.boxes.forEach((box, index) => {
    drawBox(box, index === state.selected, state.categories[index]);
  });
}

function drawRoiBoundary(roi, color, dashed) {
  if (!roi) return;
  const view = viewRect();
  const [x, y, width, height] = roi;
  const scale = canvas.width / Math.max(1, canvas.clientWidth);
  context.save();
  context.strokeStyle = color;
  context.lineWidth = 2 * scale;
  context.setLineDash(dashed ? [6 * scale, 4 * scale] : []);
  context.strokeRect(
    (x - view.x) / view.w * canvas.width,
    (y - view.y) / view.h * canvas.height,
    width / view.w * canvas.width,
    height / view.h * canvas.height,
  );
  context.restore();
}

function drawBox(box, selected, category = null) {
  const view = viewRect();
  const x = (box[0] - view.x) / view.w * canvas.width;
  const y = (box[1] - view.y) / view.h * canvas.height;
  const w = box[2] / view.w * canvas.width;
  const h = box[3] / view.h * canvas.height;
  if (x + w < 0 || y + h < 0 || x > canvas.width || y > canvas.height) return;
  const scale = canvas.width / Math.max(1, canvas.clientWidth);
  context.lineWidth = (selected ? 3 : 2) * scale;
  const color = category === "minimap_player" ? "#51d9f3" : "#35e1a2";
  context.strokeStyle = selected ? "#f6c85f" : color;
  context.fillStyle = selected ? "rgba(246,200,95,.13)" :
    (category === "minimap_player" ? "rgba(81,217,243,.10)" : "rgba(53,225,162,.10)");
  context.fillRect(x, y, w, h);
  context.strokeRect(x, y, w, h);
  if ((state.bootstrap?.classes?.length || 0) > 1 && category) {
    const label = categoryLabel(category);
    context.font = `${11 * scale}px sans-serif`;
    const labelWidth = context.measureText(label).width + 8 * scale;
    const labelHeight = 17 * scale;
    const labelY = Math.max(0, y - labelHeight);
    context.fillStyle = selected ? "#f6c85f" : color;
    context.fillRect(x, labelY, labelWidth, labelHeight);
    context.fillStyle = "#061016";
    context.fillText(label, x + 4 * scale, labelY + 12 * scale);
  }
  if (selected) {
    const radius = 6 * scale;
    context.fillStyle = "#f6c85f";
    for (const [handleX, handleY] of [[x + w, y + h], [x + w, y + h / 2]]) {
      context.beginPath();
      context.arc(handleX, handleY, radius, 0, Math.PI * 2);
      context.fill();
    }
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

function roiBoundaryContacts(boxes) {
  const labelCenters = state.task && state.bootstrap?.kind === "minimap_enemy" ?
    window.MapassistLabelRoiValidation.centersOutsideRoi(boxes, taskLabelRoi()) : [];
  if (!state.task || !state.image || state.bootstrap?.kind === "minimap_region") {
    return {crop: [], physical: [], labelCenters};
  }
  const [x, y, width, height] = taskRoi();
  const frameWidth = state.image.naturalWidth || state.image.width;
  const frameHeight = state.image.naturalHeight || state.image.height;
  const cropLeft = Math.floor(x * frameWidth);
  const cropTop = Math.floor(y * frameHeight);
  const cropRight = Math.ceil((x + width) * frameWidth);
  const cropBottom = Math.ceil((y + height) * frameHeight);
  const crop = [];
  const physical = [];
  boxes.forEach((box, index) => {
    const sides = {crop: [], physical: []};
    const boxLeft = box[0] * frameWidth;
    const boxTop = box[1] * frameHeight;
    const boxRight = (box[0] + box[2]) * frameWidth;
    const boxBottom = (box[1] + box[3]) * frameHeight;
    if (boxLeft <= cropLeft + 1 && boxRight >= cropLeft - 1e-9) {
      (cropLeft <= 0 ? sides.physical : sides.crop).push("左");
    }
    if (boxRight >= cropRight - 1 && boxLeft <= cropRight + 1e-9) {
      (cropRight >= frameWidth ? sides.physical : sides.crop).push("右");
    }
    if (boxTop <= cropTop + 1 && boxBottom >= cropTop - 1e-9) {
      (cropTop <= 0 ? sides.physical : sides.crop).push("上");
    }
    if (boxBottom >= cropBottom - 1 && boxTop <= cropBottom + 1e-9) {
      (cropBottom >= frameHeight ? sides.physical : sides.crop).push("下");
    }
    if (sides.crop.length) crop.push({index: index + 1, sides: sides.crop});
    if (sides.physical.length) physical.push({index: index + 1, sides: sides.physical});
  });
  return {crop, physical, labelCenters};
}

function hitBox(point) {
  const view = viewRect();
  const handle = 11 * canvas.width / Math.max(1, canvas.clientWidth);
  for (let index = state.boxes.length - 1; index >= 0; index--) {
    const box = state.boxes[index];
    const right = (box[0] + box[2] - view.x) / view.w * canvas.width;
    const bottom = (box[1] + box[3] - view.y) / view.h * canvas.height;
    if (Math.hypot(point.xCanvas - right, point.yCanvas - bottom) <= handle) return {index, mode: "resize"};
    const middle = (box[1] + box[3] / 2 - view.y) / view.h * canvas.height;
    if (Math.hypot(point.xCanvas - right, point.yCanvas - middle) <= handle) return {index, mode: "resize-x"};
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
  renderBoxCount();
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
    drawBox(
      [x, y, Math.abs(point.x - state.drag.start.x), Math.abs(point.y - state.drag.start.y)],
      true, defaultCategory(),
    );
    return;
  }
  const box = [...state.drag.original];
  if (state.drag.mode === "move") {
    box[0] = clamp(box[0] + point.x - state.drag.start.x, bounds.x, bounds.x + bounds.w - box[2]);
    box[1] = clamp(box[1] + point.y - state.drag.start.y, bounds.y, bounds.y + bounds.h - box[3]);
  } else if (state.drag.mode === "resize-x") {
    box[2] = clamp(box[2] + point.x - state.drag.start.x, .002, bounds.x + bounds.w - box[0]);
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
      state.categories.push(defaultCategory());
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
  const categorySelect = $("#boxCategorySelect");
  categorySelect.disabled = !state.task || state.selected < 0;
  if (state.selected >= 0) {
    categorySelect.value = state.categories[state.selected] || defaultCategory();
  }
  const contacts = roiBoundaryContacts(state.boxes);
  const warning = $("#roiBoundaryWarning");
  const messages = [];
  if (contacts.crop.length) {
    messages.push(`${contacts.crop.length} 个框距可扩展的小地图裁剪边缘不足 1 像素，可能目标已被切断。请扩大标注数据 ROI 后重标；无法确认完整目标时选“无法判断”。`);
  }
  if (contacts.physical.length) {
    messages.push(`${contacts.physical.length} 个框碰到原始画面边缘；无法确认完整目标时选“无法判断”。`);
  }
  if (contacts.labelCenters.length) {
    messages.push(`${contacts.labelCenters.length} 个框的中心超出标注范围（紫红实线）；请调整框后再保存。`);
  }
  warning.classList.toggle("hidden", messages.length === 0);
  warning.textContent = messages.join(" ");
}

function deleteSelected() {
  if (state.selected < 0) return;
  state.boxes.splice(state.selected, 1);
  state.categories.splice(state.selected, 1);
  state.selected = -1;
  state.dirty = true;
  renderBoxCount();
  draw();
}

function resetBoxes() {
  if (!state.task) return;
  state.boxes = cloneBoxes(state.task.suggested_boxes);
  state.categories = cloneCategories(state.task.suggested_categories, state.boxes);
  state.selected = -1;
  state.dirty = false;
  renderBoxCount();
  draw();
}

async function save(status) {
  if (!state.task || !requireAnnotator()) return;
  if (state.busy) return;
  if (status === "corrected" && !state.boxes.length) {
    const negativeLabel = state.bootstrap?.kind === "minimap_player" ?
      "没有自己头像" : state.bootstrap?.kind === "main_enemy" ?
      "没有边缘敌人" : "没有敌人";
    toast(`没有框时请使用“${negativeLabel}”`, true);
    return;
  }
  const boxesToCheck = status === "accepted" ?
    state.task.suggested_boxes : state.boxes;
  const contacts = roiBoundaryContacts(boxesToCheck);
  if (["corrected", "accepted"].includes(status) &&
      state.bootstrap?.kind === "minimap_enemy" && contacts.labelCenters.length) {
    toast("目标框中心需位于标注范围（紫红实线）内；请调整框后再保存", true);
    return;
  }
  if (["corrected", "accepted"].includes(status) &&
      state.bootstrap?.kind !== "minimap_region" &&
      contacts.crop.length) {
    toast("目标框碰到可扩展的小地图裁剪边缘，可能不完整；请选“无法判断”或扩大数据 ROI 后重标", true);
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
        categories: status === "corrected" ? state.categories : undefined,
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
$("#boxCategorySelect").addEventListener("change", (event) => {
  if (state.selected < 0 || !state.boxes[state.selected]) return;
  state.categories[state.selected] = event.target.value;
  state.dirty = true;
  draw();
});
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
