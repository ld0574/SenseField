'use strict';
/* 生成一张 8×8 合成消消乐棋盘 PNG（含两个三连），用于模拟器验证识别管线。
 * 零依赖 PNG 编码：签名+IHDR+IDAT(zlib)+IEND，CRC32 手写。 */
const zlib = require('zlib');
const fs = require('fs');

const W = 800, H = 800, CELL = 100;
const COLORS = { R: [220, 40, 40], O: [240, 140, 30], Y: [240, 220, 40], G: [60, 200, 70], B: [40, 120, 230], P: [150, 60, 220] };

/* 布局：行 2（0 基）列 1-3 三个 R；行 5 列 4-6 三个 G；其余用无三连构造法填 */
const board = [];
const perm = ['R', 'Y', 'B', 'G', 'O', 'P'];
for (let r = 0; r < 8; r++) {
  board.push(Array.from({ length: 8 }, (_, c) => perm[(r + 2 * c) % 6]));
}
for (let c = 1; c <= 3; c++) board[2][c] = 'R';
for (let c = 4; c <= 6; c++) board[5][c] = 'G';

/* 画布：白底 + 每格深蓝黑棋盘底（全格）+ 居中彩色棋子块（真实游戏样式，供自动适配检测） */
const TILE = [35, 45, 70];
const raw = Buffer.alloc(H * (W * 3 + 1));
for (let y = 0; y < H; y++) {
  const rowStart = y * (W * 3 + 1);
  raw[rowStart] = 0;
  for (let x = 0; x < W; x++) {
    const row = Math.floor(y / CELL), col = Math.floor(x / CELL);
    const color = COLORS[board[row] ? board[row][col] : '.'] || [245, 245, 245];
    const inCellX = x % CELL, inCellY = y % CELL;
    const isPiece = inCellX > 15 && inCellX < 85 && inCellY > 15 && inCellY < 85;
    const c = isPiece ? color : TILE;
    const off = rowStart + 1 + x * 3;
    raw[off] = c[0]; raw[off + 1] = c[1]; raw[off + 2] = c[2];
  }
}

function crc32(buf) {
  let table = crc32.table;
  if (!table) {
    table = crc32.table = [];
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
      table[n] = c >>> 0;
    }
  }
  let crc = 0xFFFFFFFF;
  for (const b of buf) crc = table[(crc ^ b) & 0xFF] ^ (crc >>> 8);
  return (crc ^ 0xFFFFFFFF) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

const ihdr = Buffer.alloc(13);
ihdr.writeUInt32BE(W, 0);
ihdr.writeUInt32BE(H, 4);
ihdr[8] = 8; ihdr[9] = 2; /* 8bit RGB */
const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),
  chunk('IHDR', ihdr),
  chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
  chunk('IEND', Buffer.alloc(0))
]);
fs.writeFileSync(process.argv[2] || 'match3_test.png', png);
console.log('PNG 已生成：' + (process.argv[2] || 'match3_test.png') + '，棋盘布局含 2 个三连（行3 RRR、行6 GGG）');
