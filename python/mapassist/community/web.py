"""社区服务的网页界面。

单文件、无框架、无构建步骤：发布时整份源码直接上传即可。
设计取向沿用端侧：字大、对比强、每个输入项都有可见标签、错误就地说明而不是弹窗，
**任何失败都不清空用户已经写下的内容**。
"""

from __future__ import annotations

PAGE = """<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>听野社区</title>
<style>
  :root { color-scheme: light; }
  * { box-sizing: border-box; }
  body { margin: 0; background: #fbf7f1; color: #193139; font-family: system-ui, "Noto Sans CJK SC", sans-serif; font-size: 18px; line-height: 1.7; }
  .wrap { max-width: 760px; margin: 0 auto; padding: 24px 16px 64px; }
  h1 { font-size: 30px; margin: 8px 0 4px; }
  h2 { font-size: 23px; margin: 32px 0 8px; }
  .lead { color: #546b72; margin: 0 0 8px; }
  .tabs { display: flex; flex-wrap: wrap; gap: 10px; margin: 16px 0 4px; }
  button { font: inherit; border-radius: 10px; border: 2px solid #5a7c83; background: #fff; color: #193139; padding: 10px 16px; min-height: 48px; cursor: pointer; }
  button[aria-pressed="true"] { background: #236874; border-color: #236874; color: #fff; }
  button.primary { background: #236874; border-color: #236874; color: #fff; font-weight: 700; }
  button:disabled { background: #f0f4f3; border-color: #82969a; color: #546b72; cursor: default; }
  :focus-visible { outline: 3px solid #aa2d23; outline-offset: 2px; }
  input, select, textarea { font: inherit; width: 100%; color: #193139; background: #fff;
    border: 2px solid #dae5e4; border-radius: 10px; padding: 12px 14px; min-height: 52px; }
  textarea { min-height: 140px; resize: vertical; }
  input[aria-invalid="true"], textarea[aria-invalid="true"], select[aria-invalid="true"] { border-color: #aa2d23; }
  .field { margin: 0 0 18px; }
  label { display: block; font-weight: 700; margin-bottom: 6px; }
  .err { display: block; min-height: 1.2em; color: #aa2d23; font-size: 16px; margin-top: 4px; }
  .status { min-height: 1.6em; margin: 8px 0 0; font-size: 17px; }
  .status.bad { color: #aa2d23; }
  ol.posts { list-style: none; padding: 0; margin: 12px 0 0; }
  ol.posts li { background: #fff; border: 1px solid #dae5e4; border-radius: 12px; padding: 16px; margin-bottom: 12px; }
  ol.posts .meta { color: #546b72; font-size: 15px; margin: 0 0 6px; }
  ol.posts .title { font-size: 20px; font-weight: 700; margin: 0 0 6px; }
  ol.posts .text { margin: 0; white-space: pre-wrap; }
  .badge { display: inline-block; background: #e7f2f2; color: #236874; border-radius: 999px; padding: 2px 10px; font-size: 14px; margin-right: 8px; }
  footer { margin-top: 40px; color: #546b72; font-size: 16px; border-top: 1px solid #dae5e4; padding-top: 16px; }
  a { color: #236874; }
</style>
</head>
<body>
<div class="wrap">
  <h1>听野社区</h1>
  <p class="lead">七个板块。看别人的帖子，也可以自己发一条。</p>

  <h2 id="browse-heading">浏览</h2>
  <div class="tabs" id="tabs" role="group" aria-label="按板块筛选"></div>
  <p class="status" id="list-status" role="status"></p>
  <button type="button" id="refresh">刷新帖子</button>
  <ol class="posts" id="posts" aria-labelledby="browse-heading"></ol>

  <h2 id="compose-heading">发一条</h2>
  <form id="compose" novalidate>
    <p class="field">
      <label for="f-section">板块</label>
      <select id="f-section" aria-describedby="f-section-error"></select>
      <span class="err" id="f-section-error" role="alert"></span>
    </p>
    <p class="field">
      <label for="f-title">标题</label>
      <input id="f-title" type="text" autocomplete="off" aria-describedby="f-title-error">
      <span class="err" id="f-title-error" role="alert"></span>
    </p>
    <p class="field">
      <label for="f-body">正文</label>
      <textarea id="f-body" aria-describedby="f-body-error"></textarea>
      <span class="err" id="f-body-error" role="alert"></span>
    </p>
    <p class="field">
      <label for="f-author">署名</label>
      <input id="f-author" type="text" autocomplete="off" aria-describedby="f-author-error">
      <span class="err" id="f-author-error" role="alert"></span>
    </p>
    <button type="submit" class="primary" id="send">发布</button>
    <p class="status" id="form-status" role="status"></p>
  </form>

  <footer>
    <p>接口：<a href="/posts">/posts</a>（帖子列表）、<a href="/sections">/sections</a>（板块）、
      <a href="/health">/health</a>（存活）、<a href="/docs">/docs</a>（接口文档）。</p>
    <p>这个页面与听野 App 共用同一套服务端校验：字数越界会明确告诉你哪一项不合规，不会替你截断。</p>
  </footer>
</div>
<script>
const LIMITS = { title: 80, body: 2000, author: 40 };
const state = { sections: [], filter: "" };

function $(id) { return document.getElementById(id); }

function trimmed(value) { return (value || "").trim(); }

// 与服务端 store.py 同款的字数边界：越界只说明，不截断。
function problem(field, value, max) {
  const text = trimmed(value);
  if (!text) return field + "不能为空";
  if (text.length > max) return field + "最多 " + max + " 字，现在 " + text.length + " 字";
  return null;
}

function setError(inputId, errorId, message) {
  const input = $(inputId), slot = $(errorId);
  slot.textContent = message || "";
  if (message) input.setAttribute("aria-invalid", "true");
  else input.removeAttribute("aria-invalid");
}

function say(id, message, bad) {
  const node = $(id);
  node.textContent = message || "";
  node.className = bad ? "status bad" : "status";
}

function labelOf(key) {
  const found = state.sections.find((item) => item.key === key);
  return found ? found.label : key;
}

function buildTabs() {
  const tabs = $("tabs");
  tabs.textContent = "";
  const all = [{ key: "", label: "全部" }].concat(state.sections);
  all.forEach((item) => {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = item.label;
    button.setAttribute("aria-pressed", item.key === state.filter ? "true" : "false");
    button.addEventListener("click", () => {
      state.filter = item.key;
      buildTabs();
      loadPosts();
    });
    tabs.appendChild(button);
  });

  const select = $("f-section");
  if (select.options.length === 0) {
    state.sections.forEach((item) => {
      const option = document.createElement("option");
      option.value = item.key;
      option.textContent = item.label;
      select.appendChild(option);
    });
  }
}

async function loadPosts() {
  say("list-status", "正在读取帖子…");
  const query = state.filter ? "?section=" + encodeURIComponent(state.filter) : "";
  try {
    const response = await fetch("/posts" + query, { headers: { Accept: "application/json" } });
    if (!response.ok) throw new Error("读取失败（" + response.status + "）");
    const posts = await response.json();
    const list = $("posts");
    list.textContent = "";
    if (!posts.length) {
      say("list-status", state.filter ? "「" + labelOf(state.filter) + "」还没有帖子。" : "还没有帖子。");
      return;
    }
    say("list-status", "共 " + posts.length + " 条，按发布时间倒序。");
    posts.forEach((post) => {
      const item = document.createElement("li");
      const meta = document.createElement("p");
      meta.className = "meta";
      const badge = document.createElement("span");
      badge.className = "badge";
      badge.textContent = labelOf(post.section);
      meta.appendChild(badge);
      meta.appendChild(document.createTextNode(post.author));
      const title = document.createElement("p");
      title.className = "title";
      title.textContent = post.title;
      const text = document.createElement("p");
      text.className = "text";
      text.textContent = post.body;
      item.appendChild(meta);
      item.appendChild(title);
      item.appendChild(text);
      list.appendChild(item);
    });
  } catch (error) {
    say("list-status", error.message + "，稍后再试一次。", true);
  }
}

async function submit(event) {
  event.preventDefault();
  const title = $("f-title").value;
  const body = $("f-body").value;
  const author = $("f-author").value;

  const titleProblem = problem("标题", title, LIMITS.title);
  const bodyProblem = problem("正文", body, LIMITS.body);
  const authorProblem = problem("署名", author, LIMITS.author);
  setError("f-title", "f-title-error", titleProblem);
  setError("f-body", "f-body-error", bodyProblem);
  setError("f-author", "f-author-error", authorProblem);
  if (titleProblem || bodyProblem || authorProblem) {
    // 只说明哪一项不合规。已经写的内容一个字都不动。
    say("form-status", "还有没填好的地方，写好的内容都还在，改完可以接着发布。", true);
    return;
  }

  $("send").disabled = true;
  say("form-status", "正在发布…");
  try {
    const response = await fetch("/posts", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({
        section: $("f-section").value,
        title: trimmed(title),
        body: body,
        author: trimmed(author)
      })
    });
    if (response.status === 201) {
      $("f-title").value = "";
      $("f-body").value = "";
      say("form-status", "发布成功。");
      loadPosts();
      return;
    }
    let detail = "服务端没接受（" + response.status + "）";
    try {
      const payload = await response.json();
      if (payload && payload.detail) detail = String(payload.detail);
    } catch (ignored) { /* 错误正文不是 JSON，就用状态码说明 */ }
    say("form-status", "没能发出去：" + detail + "。写好的内容都还在。", true);
  } catch (error) {
    say("form-status", "连不上社区服务：" + error.message + "。写好的内容都还在。", true);
  } finally {
    $("send").disabled = false;
  }
}

async function start() {
  try {
    const response = await fetch("/sections", { headers: { Accept: "application/json" } });
    state.sections = await response.json();
  } catch (error) {
    say("list-status", "板块列表读取失败：" + error.message, true);
    return;
  }
  buildTabs();
  loadPosts();
}

$("refresh").addEventListener("click", loadPosts);
$("compose").addEventListener("submit", submit);
start();
</script>
</body>
</html>
"""