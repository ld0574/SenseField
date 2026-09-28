"use strict";

(function exposeLabelRoiValidation(root) {
  function centersOutsideRoi(boxes, roi, epsilon = 1e-6) {
    if (!Array.isArray(boxes) || !Array.isArray(roi) || roi.length !== 4) return [];
    const [x, y, width, height] = roi.map(Number);
    const right = x + width;
    const bottom = y + height;
    return boxes.flatMap((box, index) => {
      if (!Array.isArray(box) || box.length !== 4) return [index];
      const values = box.map(Number);
      if (!values.every(Number.isFinite)) return [index];
      const [boxX, boxY, boxWidth, boxHeight] = values;
      const centerX = boxX + boxWidth / 2;
      const centerY = boxY + boxHeight / 2;
      return centerX < x - epsilon || centerY < y - epsilon ||
        centerX > right + epsilon || centerY > bottom + epsilon ? [index] : [];
    });
  }

  const api = {centersOutsideRoi};
  root.MapassistLabelRoiValidation = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(globalThis);
