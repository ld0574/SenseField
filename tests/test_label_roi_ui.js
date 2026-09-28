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
