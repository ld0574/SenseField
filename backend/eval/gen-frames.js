'use strict';

/* P2 评测集生成器：50 帧 state（不含标注——标注由领导确认）。
 * 构成：20 消消乐（10 有已知可消除 / 10 无）+ 10 象棋 + 15 中文无障碍树 + 5 边界帧。
 * 输出 backend/eval/frames.json。
 *
 * match3 的「有可消除」帧里 potential_matches 是 state 自身字段（机器可推导参考），
 * 但最终标注以领导确认为准（见 annotation-template.csv）。 */

const fs = require('fs');
const path = require('path');

const COLORS = ['R', 'Y', 'B', 'G', 'O', 'P'];
let seed = 20261002;
function rand(n) {
  seed = (seed * 1103515245 + 12345) % 2147483648;
  return seed % n;
}

function emptyBoard() {
  return Array.from({ length: 8 }, () => Array.from({ length: 8 }, () => COLORS[rand(COLORS.length)]));
}

function findRuns(rows) {
  const runs = [];
  for (let r = 0; r < 8; r++) {
    for (let c = 0; c < 6; c++) {
      if (rows[r][c] === rows[r][c + 1] && rows[r][c] === rows[r][c + 2]) runs.push([r, c, 'h']);
    }
  }
  for (let c = 0; c < 8; c++) {
    for (let r = 0; r < 6; r++) {
      if (rows[r][c] === rows[r + 1][c] && rows[r][c] === rows[r + 2][c]) runs.push([r, c, 'v']);
    }
  }
  return runs;
}

function boardWithoutRuns() {
  /* 构造法保证无三连：color(r,c)=π[(r+2c)%6]，相邻格颜色必不同（纵向差1、横向差2） */
  const perm = COLORS.slice();
  for (let i = perm.length - 1; i > 0; i--) {
    const j = rand(i + 1);
    [perm[i], perm[j]] = [perm[j], perm[i]];
  }
  return Array.from({ length: 8 }, (_, r) =>
    Array.from({ length: 8 }, (_, c) => perm[(r + 2 * c) % 6]));
}

function boardWithRun() {
  const rows = boardWithoutRuns();
  const r = rand(8), c = rand(5);
  const color = COLORS[rand(COLORS.length)];
  rows[r][c] = color; rows[r][c + 1] = color; rows[r][c + 2] = color;
  return { rows, match: { from: [r, c], to: [r, c + 1], run: 3, reward: rand(2) ? 'low' : 'medium' } };
}

function match3Frames() {
  const frames = [];
  for (let i = 0; i < 10; i++) {
    const { rows, match } = boardWithRun();
    frames.push({
      id: `m3-with-${String(i + 1).padStart(2, '0')}`,
      lang: 'zh',
      note: '棋盘含已知可消除组合（state 内 potential_matches 可机器推导参考）',
      state: { game: 'match3', scene: 'playing', board: { size: [8, 8], rows }, potential_matches: [match], moves_left: 3 + rand(20), score: rand(9000), time_limit_sec: rand(2) ? 0 : 15 + rand(40) }
    });
  }
  for (let i = 0; i < 10; i++) {
    frames.push({
      id: `m3-none-${String(i + 1).padStart(2, '0')}`,
      lang: 'zh',
      note: '棋盘无可消除组合（正确操作需人工判断：扫描/等待）',
      state: { game: 'match3', scene: 'playing', board: { size: [8, 8], rows: boardWithoutRuns() }, potential_matches: [], moves_left: 2 + rand(22), score: rand(9000), time_limit_sec: rand(2) ? 0 : 10 + rand(50) }
    });
  }
  return frames;
}

function chessFrame(i) {
  const base = JSON.parse(fs.readFileSync(path.join(__dirname, '../../sensefield-integration/app/src/main/assets/jev/chess.json'), 'utf8')).state;
  const board = base.board.map(row => row.slice());
  const plies = 1 + rand(3);
  for (let p = 0; p < plies; p++) {
    const from = [rand(10), rand(9)], to = [rand(10), rand(9)];
    if (!board[from[0]][from[1]] || board[to[0]][to[1]]) continue;
    board[to[0]][to[1]] = board[from[0]][from[1]];
    board[from[0]][from[1]] = null;
  }
  const moves = ['炮二平五', '马八进七', '车一进一', '兵三进一', '仕四进五', '炮八平九'];
  return {
    id: `ch-${String(i + 1).padStart(2, '0')}`,
    lang: 'zh',
    note: '象棋中局变体（正确操作与播报需人工标注）',
    state: { game: 'chess', turn: rand(2) ? 'red' : 'black', board, last_move: moves[rand(moves.length)], in_check: rand(4) === 0, captured_recently: rand(3) === 0 ? '兵' : null }
  };
}

function axNode(id, text, desc, clickable, bounds) {
  return { id, text, content_desc: desc, clickable, bounds };
}

function axFrames() {
  const defs = [
    ['主菜单', [axNode('lbl_level', '第 3 关', null, false, [420, 200, 660, 260]), axNode('btn_start', '开始游戏', '开始一局新的消消乐', true, [360, 900, 720, 1020]), axNode('btn_settings', '设置', null, true, [360, 1260, 720, 1380]), axNode('btn_shop', '商店', null, true, [100, 1500, 300, 1620])]],
    ['设置页', [axNode('lbl_title', '设置', null, false, [400, 150, 680, 220]), axNode('row_speed', '语音语速 180%', '调整播报速度', true, [100, 400, 980, 520]), axNode('row_volume', '音量 80%', null, true, [100, 560, 980, 680]), axNode('sw_haptic', '震动反馈 已开启', null, true, [100, 720, 980, 840]), axNode('btn_back', '返回', null, true, [40, 60, 160, 140])]],
    ['商店弹窗', [axNode('dlg_title', '金币不足', null, false, [240, 700, 840, 780]), axNode('lbl_body', '需要 500 金币复活，当前 320', null, false, [200, 820, 880, 900]), axNode('btn_buy', '花费 6 元购买', '触发付费', true, [280, 980, 800, 1100]), axNode('btn_cancel', '放弃', null, true, [280, 1140, 800, 1240])]],
    ['暂停菜单', [axNode('lbl_paused', '已暂停', null, false, [420, 600, 660, 680]), axNode('btn_resume', '继续游戏', null, true, [300, 800, 780, 920]), axNode('btn_restart', '重新开始', null, true, [300, 980, 780, 1100]), axNode('btn_quit', '退出关卡', null, true, [300, 1160, 780, 1280])]],
    ['结算页', [axNode('lbl_result', '过关！三星', null, false, [300, 500, 780, 600]), axNode('lbl_score', '得分 12800', null, false, [360, 660, 720, 730]), axNode('btn_next', '下一关', null, true, [300, 1000, 780, 1120]), axNode('btn_home', '回主页', null, true, [300, 1180, 780, 1300])]],
    ['对局内浮层', [axNode('lbl_combo', '连击 x3！', null, false, [400, 300, 680, 380]), axNode('lbl_moves', '剩余步数 2', null, false, [60, 200, 300, 270]), axNode('btn_hint', '提示', null, true, [60, 1900, 260, 2020])]],
    ['权限弹窗', [axNode('dlg_title', '需要录屏权限', null, false, [200, 800, 880, 880]), axNode('lbl_body', '用于识别棋盘并语音播报，画面不会保存或上传', null, false, [180, 920, 900, 1030]), axNode('btn_ok', '立即授权', null, true, [280, 1120, 800, 1230]), axNode('btn_deny', '暂不', null, true, [280, 1280, 800, 1380])]],
    ['新手引导', [axNode('lbl_tip', '点击两个相邻宝石交换位置', null, false, [200, 1000, 880, 1090]), axNode('btn_skip', '跳过引导', null, true, [760, 120, 1000, 200])]],
    ['商店主页面', [axNode('lbl_coins', '金币 320', null, false, [60, 200, 300, 260]), axNode('item_1', '复活币 ×1 · 500 金币', '购买复活道具', true, [100, 500, 980, 650]), axNode('item_2', '提示卡 ×3 · 800 金币', null, true, [100, 700, 980, 850]), axNode('btn_back', '返回', null, true, [40, 60, 160, 140])]],
    ['登录页', [axNode('lbl_title', '欢迎来到听野', null, false, [300, 500, 780, 580]), axNode('btn_login', '微信登录', null, true, [280, 900, 800, 1020]), axNode('btn_guest', '游客试玩', null, true, [280, 1080, 800, 1200])]],
    ['失败弹窗', [axNode('dlg_title', '本关失败', null, false, [300, 600, 780, 680]), axNode('lbl_body', '还差 2 步就能过关，要试试吗？', null, false, [220, 740, 860, 820]), axNode('btn_retry', '再试一次', null, true, [280, 940, 800, 1060]), axNode('btn_giveup', '返回主页', null, true, [280, 1120, 800, 1240])]],
    ['对局倒计时', [axNode('lbl_time', '剩余 8 秒', null, false, [420, 160, 660, 230]), axNode('lbl_goal', '还需消除 5 个蓝色', null, false, [240, 280, 840, 350])]],
    ['音量警告', [axNode('dlg_title', '音量过低', null, false, [280, 820, 800, 900]), axNode('lbl_body', '语音播报可能听不清', null, false, [260, 940, 820, 1010]), axNode('btn_raise', '调高音量', null, true, [300, 1100, 780, 1210])]],
    ['成就弹窗', [axNode('lbl_achv', '达成成就：百战不殆', null, false, [240, 700, 840, 790]), axNode('btn_share', '分享', null, true, [300, 950, 780, 1060]), axNode('btn_close', '关闭', null, true, [300, 1110, 780, 1210])]],
    ['更新弹窗', [axNode('dlg_title', '发现新版本 0.4.0', null, false, [240, 760, 840, 840]), axNode('lbl_body', '修复了播报延迟问题', null, false, [240, 880, 840, 950]), axNode('btn_update', '立即更新', '跳转应用商店', true, [280, 1040, 800, 1150]), axNode('btn_later', '下次再说', null, true, [280, 1200, 800, 1300])]]
  ];
  return defs.map((d, i) => ({
    id: `ax-${String(i + 1).padStart(2, '0')}`,
    lang: 'zh',
    note: `无障碍树：${d[0]}（正确操作需人工标注）`,
    state: { package: 'com.example.match3game', activity: '.MainActivity', screen: d[0], nodes: d[1] }
  }));
}

function edgeFrames() {
  const manyNodes = Array.from({ length: 40 }, (_, i) => axNode(`node_${i}`, `列表项 ${i + 1}：内容描述文字较长用于撑大输入`, null, i % 3 === 0, [100, 300 + i * 40, 980, 330 + i * 40]));
  return [
    { id: 'edge-01', lang: 'zh', note: '近似空 state（加载中）', state: { game: 'unknown', scene: 'loading' } },
    { id: 'edge-02', lang: 'zh', note: '超长 state（本地后端 512 token 截断行为观测点）', state: { package: 'com.example', screen: '长列表', nodes: manyNodes } },
    { id: 'edge-03', lang: 'en', note: '英文菜单（对照组）', state: { package: 'com.example.en', screen: 'Main Menu', nodes: [axNode('btn_play', 'Play', 'Start a new game', true, [360, 900, 720, 1020]), axNode('btn_settings', 'Settings', null, true, [360, 1100, 720, 1220]), axNode('btn_quit', 'Quit', null, true, [360, 1300, 720, 1420])] } },
    { id: 'edge-04', lang: 'mixed', note: '棋盘＋聊天混排', state: { game: 'match3', scene: 'playing', board: { size: [8, 8], rows: boardWithRun().rows }, potential_matches: [], chat: ['队友：快点', '队友：左边有个四连'], moves_left: 5 } },
    { id: 'edge-05', lang: 'zh', note: '高干扰弹窗叠加', state: { package: 'com.example.match3game', screen: '对局中＋系统弹窗', nodes: [axNode('lbl_combo', '连击 x2', null, false, [400, 300, 680, 380]), axNode('dlg_sys', '存储空间不足', null, false, [240, 900, 840, 980]), axNode('btn_clean', '立即清理', null, true, [280, 1060, 800, 1170]), axNode('btn_ignore', '忽略', null, true, [280, 1220, 800, 1320])] } }
  ];
}

const frames = [...match3Frames(), ...Array.from({ length: 10 }, (_, i) => chessFrame(i)), ...axFrames(), ...edgeFrames()];
const out = path.join(__dirname, 'frames.json');
fs.writeFileSync(out, JSON.stringify({ generated_at: new Date().toISOString(), count: frames.length, frames }, null, 2));
console.log(`已生成 ${frames.length} 帧评测 state → ${out}`);
