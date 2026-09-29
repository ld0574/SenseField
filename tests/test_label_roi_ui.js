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
  assert.match(app, /机器框只是弱建议/);
  assert.match(app, /机器空框不等于负样本/);
  assert.match(app, /green_ring_suggestion: "绿色外圈建议"/);
  assert.match(app, /empty: "机器空框"/);
});
