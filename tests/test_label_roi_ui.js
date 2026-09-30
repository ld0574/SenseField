"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const {centersOutsideRoi} = require(
  "../python/mapassist/annotation_web/label_roi_validation.js",
);
const {formatSuggestionHint} = require(
  "../python/mapassist/annotation_web/suggestion_hint.js",
);
const app = fs.readFileSync(
  path.join(__dirname, "../python/mapassist/annotation_web/app.js"), "utf8",
);
const html = fs.readFileSync(
  path.join(__dirname, "../python/mapassist/annotation_web/index.html"), "utf8",
);

test("label ROI checker accepts a center inside the boundary", () => {
  assert.deepEqual(centersOutsideRoi([[0.18, 0.1, 0.04, 0.06]], [0.02, 0.02, 0.2, 0.3]), []);
});

test("label ROI checker reports each outside center by box index", () => {
  assert.deepEqual(centersOutsideRoi([
    [0.18, 0.1, 0.04, 0.06],
    [0.3, 0.1, 0.04, 0.06],
    [0.01, 0.1, 0.04, 0.06],
  ], [0.04, 0.02, 0.18, 0.3]), [1, 2]);
});

test("missing label ROI keeps legacy drawing unconstrained", () => {
  assert.deepEqual(centersOutsideRoi([[0.8, 0.8, 0.1, 0.1]], null), []);
});

test("annotation UI warns and blocks both accepted and corrected boxes", () => {
  assert.match(app, /centersOutsideRoi\(boxes, taskLabelRoi\(\)\)/);
  assert.match(app, /contacts\.labelCenters\.length/);
  assert.match(app, /标注范围（紫红实线）/);
  assert.match(app, /\["corrected", "accepted"\]\.includes\(status\)[\s\S]{0,180}contacts\.labelCenters\.length/);
  assert.match(app, /function annotationBounds\(\)[\s\S]{0,160}taskRoi\(\)/);
});

test("queue shows suggestion box counts without presenting them as reviewed labels", () => {
  assert.equal(formatSuggestionHint([[0, 0, 1, 1], [0, 0, 1, 1]]), "2 个建议框");
  assert.equal(formatSuggestionHint([]), "无建议框");
  assert.equal(formatSuggestionHint(undefined), "无建议框");
  assert.match(app, /formatSuggestionHint\(task\.suggested_boxes\)/);
  assert.match(app, /diagnostic_hard_case: "困难案例"/);
  assert.match(app, /machine_empty_negative_coverage: "机器空框抽样"/);
});

test("queue makes the pending review batch and machine-only guidance explicit", () => {
  assert.match(html, /id="queuePendingCount"/);
  assert.match(html, /id="queueCompletedCount"/);
  assert.match(html, /id="queueShortcutHint"/);
  assert.match(app, /function queueScopeStats\(stats\)/);
  assert.match(app, /本批还需复核 \$\{scoped\.counts\.pending \|\| 0\} 张/);
  assert.match(app, /机器建议仅供参考；每张图片仍需人工确认/);
});

test("reviewer can mark difficult frames and use the existing save-next shortcuts", () => {
  assert.match(html, /标记困难 \/ 跳过/);
  assert.match(app, /parts\.push\("困难项按 S"/);
  assert.match(app, /event\.key\.toLowerCase\(\) === "n"\) save\("negative"\)/);
  assert.match(app, /event\.key\.toLowerCase\(\) === "s"\) save\("skip"\)/);
  assert.match(app, /toast\(`\$\{statusLabel\(status\)\}已保存`\)/);
  assert.match(app, /state\.busy = false;\s*await nextTask\(\)/);
});

test("multiclass review keeps one editable category beside every box", () => {
  assert.match(html, /id="boxCategorySelect"/);
  assert.match(app, /state\.categories = initialCategories\(task, state\.boxes\)/);
  assert.match(app, /state\.categories\.push\(defaultCategory\(\)\)/);
  assert.match(app, /state\.categories\.splice\(state\.selected, 1\)/);
  assert.match(app, /categories: status === "corrected" \? state\.categories/);
  assert.match(app, /state\.task\.suggested_categories/);
});

test("main-screen review replaces minimap guidance with edge semantics", () => {
  assert.match(html, /id="fourthGuideTitle"/);
  assert.match(html, /id="fourthGuideDetail"/);
  assert.match(app, /kind === "main_enemy"/);
  assert.match(app, /主画面边缘数据标注台/);
  assert.match(app, /只框真实敌方威胁/);
  assert.match(app, /技能区、商店、系统 UI、友方血条/);
  assert.match(app, /每个建议框和机器空框都要人工检查/);
  assert.match(app, /configureWorkbenchCopy\(state\.bootstrap\.kind, multiclass\)/);
});

test("player minimap review explains the green-ring target and weak suggestions", () => {
  assert.match(app, /kind === "minimap_player"/);
  assert.match(app, /玩家小地图数据标注台/);
  assert.match(app, /自己的绿色外圈头像/);
  assert.match(app, /一帧最多一个自己头像/);
  assert.match(app, /绿色方形标记/);
  assert.match(app, /机器框只是弱建议/);
  assert.match(app, /机器空框不等于负样本/);
  assert.match(app, /green_ring_suggestion: "绿色外圈建议"/);
  assert.match(app, /empty: "机器空框"/);
  assert.match(app, /"没有自己头像"/);
  assert.match(app, /"确认本帧没有玩家头像"/);
});

test("drawing a player correction replaces the weak proposal", () => {
  assert.match(app, /state\.bootstrap\?\.kind === "minimap_player"/);
  assert.match(app, /state\.boxes = \[newBox\]/);
  assert.match(app, /state\.categories = \[defaultCategory\(\)\]/);
});

test("dataset query links override stale local selection and remain shareable", () => {
  assert.match(app, /new URLSearchParams\(window\.location\.search\)\.get\("dataset"\)/);
  assert.match(app, /window\.history\.replaceState/);
  assert.match(app, /currentUrl\.searchParams\.set\("dataset", state\.dataset\)/);
});
