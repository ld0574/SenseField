const fs = require('fs');
const p = 'C:/Users/22812/SenseField/android/app/src/main/java/com/openkhub/sensefield/Match3AssistActivity.java';
let s = fs.readFileSync(p, 'utf8');
const report = [];

function apply(name, oldStr, newStr) {
  if (!s.includes(oldStr)) { report.push('MISS: ' + name); return; }
  s = s.replace(oldStr, newStr);
  report.push('OK: ' + name);
}

/* 1) 门控配置字段与方法（onCreate 调用 loadGating） */
apply('gate fields',
  '    private boolean exploreOn;\n    private int kbIndex;',
  '    private boolean exploreOn;\n    private int kbIndex;\n    private float gateHigh = 0.85f;\n    private float gateMid = 0.60f;\n\n    private void loadGating() {\n        try (InputStream in = getAssets().open("jev/gating.json")) {\n            byte[] buf = new byte[4096];\n            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();\n            int n;\n            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);\n            org.json.JSONObject cfg = new org.json.JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));\n            gateHigh = (float) cfg.getJSONObject("screen_type").optDouble("high", 0.85);\n            gateMid = (float) cfg.getJSONObject("screen_type").optDouble("mid", 0.60);\n        } catch (Exception e) {\n            // 配置缺失用默认阈值，不阻断\n        }\n    }');

apply('loadGating call',
  '        loadCalibration();',
  '        loadCalibration();\n        loadGating();');

/* 2) judgeScreen：本地敏感闸门（发送前拦截） */
apply('sensitive gate',
  '        final String state = SenseFieldReaderService.latestState();',
  '        final String state = SenseFieldReaderService.latestState();\n        final String sensitive = Match3Gate.sensitiveHit(state);\n        if (sensitive != null) {\n            String block = "本地敏感闸门命中：当前为「" + sensitive + "」相关页面，内容不上云、不播报细节，建议手动处理。";\n            output.setText(block);\n            announce(block);\n            return;\n        }');

/* 3) screen_type 候选加 ABSTAIN */
apply('abstain candidate',
  '                types.put("其他", "以上都不是");',
  '                types.put("其他", "以上都不是");\n                types.put("ABSTAIN", "信息不足以判断");');

/* 4) screen_type 门控走配置 + ABSTAIN 强制拒答 */
apply('abstain handling',
  '                    double c = st.surety();\n                    String speech;\n                    if (c >= 0.85) speech = "当前是「" + st.choice + "」。";\n                    else if (c >= 0.60) speech = "可能是「" + st.choice + "」，这个我不太确定。";\n                    else speech = "这一屏我没看清楚，要我从上往下逐条读吗？";',
  '                    double c = st.surety();\n                    String speech;\n                    if ("ABSTAIN".equals(st.choice)) speech = "这一屏我没看清楚，要我从上往下逐条读吗？";\n                    else if (c >= gateHigh) speech = "当前是「" + st.choice + "」。";\n                    else if (c >= gateMid) speech = "可能是「" + st.choice + "」，这个我不太确定。";\n                    else speech = "这一屏我没看清楚，要我从上往下逐条读吗？";');

/* 5) 读屏卡片加三个按钮（图标消歧/优先级/操作前标记） */
apply('reader card buttons',
  '        a11yGo.setOnClickListener(v -> judgeScreen());\n        readerCard.addView(a11yGo);',
  '        a11yGo.setOnClickListener(v -> judgeScreen());\n        readerCard.addView(a11yGo);\n        Button iconGo = UiKit.button(this, "图标消歧判定", false);\n        iconGo.setOnClickListener(v -> judgeIcon());\n        readerCard.addView(iconGo);\n        Button priGo = UiKit.button(this, "播报优先级排序（前 5 候选）", false);\n        priGo.setOnClickListener(v -> judgePriority());\n        readerCard.addView(priGo);\n        Button markBtn = UiKit.button(this, "标记操作前状态（结果确认用）", false);\n        markBtn.setOnClickListener(v -> { SenseFieldReaderService.markBefore(); toast("已标记操作前状态"); });\n        readerCard.addView(markBtn);');

/* 6) 方法：图标消歧 */
apply('judgeIcon method',
  '    /* ---------- 播报（宽容策略真链路，与演示播报一致） ---------- */',
  '    /** 图标消歧：对状态中第一个无文本可点图标发起 Choice 判定（候选固定＋ABSTAIN）。 */\n    private void judgeIcon() {\n        final JevClient client = JevSettings.clientOrNull(this);\n        if (client == null) { output.setText("判定层未启用"); return; }\n        final String nodeLine = Match3Gate.firstIconOnlyNode(SenseFieldReaderService.latestState());\n        if (nodeLine == null) { output.setText("当前状态里没有「无文本可点图标」可消歧。"); return; }\n        output.setText("图标消歧中…\\n对象：" + nodeLine);\n        new Thread(() -> {\n            try {\n                Map<String, JevQuestion> qs = new java.util.LinkedHashMap<>();\n                Map<String, String> crit = new java.util.LinkedHashMap<>();\n                for (String c : Match3Gate.ICON_CANDIDATES) {\n                    crit.put(c, "ABSTAIN".equals(c) ? "信息不足，不要猜" : c + "功能");\n                }\n                qs.put("icon_meaning", new JevQuestion("icon_meaning", JevQuestion.TYPE_CHOICE,\n                        "这个无文字图标最可能是什么功能？位置与上下文见状态。", crit));\n                JevResult r = client.judge(SenseFieldReaderService.latestState(), qs);\n                JevAnswer a = r.answers.get("icon_meaning");\n                boolean abstain = "ABSTAIN".equals(a.choice);\n                String speech = abstain ? "这个图标我看不出来是什么。" : "这个图标可能是「" + a.choice + "」。";\n                String text = nodeLine + "\\n→ " + speech + " (conf=" + a.confidence + ")\\n";\n                runOnUiThread(() -> output.setText(text));\n                announce(speech);\n            } catch (Exception e) {\n                runOnUiThread(() -> output.setText("【消歧失败】" + e.getMessage()));\n            }\n        }, "icon-jev").start();\n    }\n\n    /** 播报优先级：对前 5 个有文本候选各打一票 Score（0-3 档量规），按分排序播报。 */\n    private void judgePriority() {\n        final JevClient client = JevSettings.clientOrNull(this);\n        if (client == null) { output.setText("判定层未启用"); return; }\n        new Thread(() -> {\n            try {\n                String st = SenseFieldReaderService.latestState();\n                List<String> cands = new ArrayList<>();\n                for (String line : st.split("\\n")) {\n                    if (line.contains("\\"") && cands.size() < 5) cands.add(line);\n                }\n                if (cands.isEmpty()) { runOnUiThread(() -> output.setText("无候选")); return; }\n                Map<String, JevQuestion> qs = new java.util.LinkedHashMap<>();\n                for (int i = 0; i < cands.size(); i++) {\n                    qs.put("pri_" + i, new JevQuestion("pri_" + i, JevQuestion.TYPE_SCORE,\n                            "这条信息现在应该优先播报给视障用户的程度（0=不必播，3=必须立即播）： " + cands.get(i),\n                            new String[]{"不必播报", "可播报", "应播报", "必须立即播报"}));\n                }\n                JevResult r = client.judge(st, qs);\n                List<int[]> ranked = new ArrayList<>();\n                for (int i = 0; i < cands.size(); i++) {\n                    JevAnswer a = r.answers.get("pri_" + i);\n                    ranked.add(new int[]{i, a == null ? 0 : (int) Math.round(a.score)});\n                }\n                java.util.Collections.sort(ranked, (x, y) -> y[1] - x[1]);\n                StringBuilder sb = new StringBuilder("播报优先级（高→低）：\\n");\n                List<String> order = new ArrayList<>();\n                for (int[] e : ranked) {\n                    if (e[1] < 3) continue;\n                    sb.append("  ").append(cands.get(e[0])).append(" → ").append(e[1]).append("\\n");\n                    order.add(cands.get(e[0]));\n                }\n                for (String line : order) announce(line);\n                String text = sb.toString();\n                runOnUiThread(() -> output.setText(text));\n            } catch (Exception e) {\n                runOnUiThread(() -> output.setText("【优先级失败】" + e.getMessage()));\n            }\n        }, "pri-jev").start();\n    }\n\n    /* ---------- 播报（宽容策略真链路，与演示播报一致） ---------- */');

fs.writeFileSync(p, s);
console.log(report.join('\n'));
