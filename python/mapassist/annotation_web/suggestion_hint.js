"use strict";

(function exposeSuggestionHint(root) {
  function formatSuggestionHint(boxes) {
    const count = Array.isArray(boxes) ? boxes.length : 0;
    return count ? `${count} 个建议框` : "无建议框";
  }

  if (typeof module === "object" && module.exports) {
    module.exports = {formatSuggestionHint};
  }
  if (root) root.MapassistSuggestionHint = {formatSuggestionHint};
})(typeof globalThis === "undefined" ? this : globalThis);
