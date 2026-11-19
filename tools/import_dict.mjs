// ============================================================
// import_dict.mjs
// 把 rime-ice 的 Rime 词库转成本项目的 chars.txt / phrases.txt。
//
//   rime-ice/cn_dicts/*.dict.yaml  ->  android/engine/src/main/resources/ime/{chars,phrases}.txt
//
// 词库来源：https://github.com/iDvel/rime-ice  （GPL-3.0）
// 合并顺序与 rime_ice.dict.yaml 的 import_tables 一致，靠前的词库权重生效。
//
// 用法：
//   node tools/import_dict.mjs --rime "D:\tmp\rime-ice-dict\full"
// ============================================================
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');

const args = process.argv.slice(2);
const argOf = (name, fallback) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};

const RIME_DIR = argOf('rime', 'D:\\tmp\\rime-ice-dict\\full');
const OUT_DIR = path.join(ROOT, 'android', 'engine', 'src', 'main', 'resources', 'ime');
const DICT_DIR = path.join(RIME_DIR, 'cn_dicts');
const MAX_CHARS_PER_SYLLABLE = Number(argOf('max-chars', 300));
const MAX_PHRASES_PER_KEY = Number(argOf('max-phrases', 12));
// 腾讯词向量有 98 万条、没有拼音列（只能逐字反推），权重全是 100，
// 而且绝大多数是长尾短语——它单独就能把拼音串数量顶到 170 万。
// 手机输入法的词库用不到这些，默认不导入；需要时加 --include-tencent。
const INCLUDE_TENCENT = args.includes('--include-tencent');
// 拼音串最多多少个音节（超长短语交给语言模型造句，不进词库）
const MAX_SYLLABLES = Number(argOf('max-syllables', 8));

if (!fs.existsSync(DICT_DIR)) {
  console.error(`找不到词库目录：${DICT_DIR}`);
  console.error('请先下载 rime-ice：https://github.com/iDvel/rime-ice/releases');
  process.exit(1);
}
fs.mkdirSync(OUT_DIR, { recursive: true });

// 与 rime_ice.dict.yaml 的 import_tables 保持一致
const SOURCES = [
  { file: '8105.dict.yaml', kind: 'char' },   // 《通用规范汉字表》带字频
  { file: '41448.dict.yaml', kind: 'char' },   // Unihan 大字表，无字频
  { file: 'base.dict.yaml', kind: 'word' },   // 基础词库
  { file: 'ext.dict.yaml', kind: 'word' },   // 扩展词库
  { file: 'tencent.dict.yaml', kind: 'word' },   // 腾讯词向量，无拼音列
  { file: 'others.dict.yaml', kind: 'word' },
];

/**
 * 解析一个 Rime 词库文件。
 *
 * 两种数据行：
 *   词/字 <TAB> 拼音 <TAB> 权重
 *   词     <TAB> 权重            （无拼音列，腾讯词向量就是这种）
 * 返回 [texts, pinyins, weights] 三个平行数组。
 */
function readDict(file) {
  const raw = fs.readFileSync(file, 'utf8');
  const lines = raw.split('\n');
  const texts = [];
  const pinyins = [];
  const weights = [];
  let inData = false;
  for (const line of lines) {
    if (!inData) {
      // YAML 头部以单独一行的 ... 结束
      if (line.trim() === '...') inData = true;
      continue;
    }
    if (line === '' || line.charCodeAt(0) === 35 /* # */) continue;
    const p = line.split('\t');
    if (p.length < 2) continue;
    if (p.length >= 3) {
      texts.push(p[0]);
      pinyins.push(p[1]);
      weights.push(Number(p[2]) || 0);
    } else {
      texts.push(p[0]);
      pinyins.push(null);
      weights.push(Number(p[1]) || 0);
    }
  }
  return { texts, pinyins, weights };
}

// 音节表（来自 index.html 抽取）决定了「哪些音节能被双拼打出来」。
// 字表里超出这个范围的音节是不可达的（比如 lo：l+o 同时对应 lo 和 luo，
// 双拼方案本身无法无歧义表达），导入时直接过滤掉，保持两份数据自洽。
const SYLLABLE_FILE = argOf(
  'syllables',
  path.join(ROOT, 'android', 'engine', 'src', 'main', 'resources', 'ime', 'syllables.txt'),
);

function loadSyllableSet(file) {
  if (!fs.existsSync(file)) {
    console.warn(`找不到音节表 ${file}，跳过音节过滤`);
    return null;
  }
  const set = new Set();
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    const t = line.trim();
    if (!t || t.startsWith('#')) continue;
    const idx = t.indexOf(':');
    if (idx < 0) continue;
    for (const s of t.slice(idx + 1).trim().split(/\s+/)) {
      if (s) set.add(s);
    }
  }
  return set;
}

const allowedSyllables = loadSyllableSet(SYLLABLE_FILE);
if (allowedSyllables) {
  console.log(`音节表：${allowedSyllables.size} 个可打出的音节`);
}

/**
 * 权重 -> 打分。
 *
 * 直接用 log2(权重)*4：不同词库的权重跨度好几个数量级，
 * 取对数后才能加和（log 概率可加）。ext 词库权重恒为 100，
 * 打分自然很低，正好把长尾词排到后面。
 */
function scoreOf(weight) {
  const w = weight > 0 ? weight : 1;
  return Math.round(Math.log2(w) * 4);
}

// ---------- 字表 ----------
console.log('读取字表...');
const charPinyin = new Map();   // 字 -> Map<拼音, 权重>
const syllableChars = new Map(); // 音节 -> Map<字, 权重>
const droppedSyllables = new Set();

for (const src of SOURCES.filter((s) => s.kind === 'char')) {
  const file = path.join(DICT_DIR, src.file);
  if (!fs.existsSync(file)) {
    console.log(`  跳过（不存在）：${src.file}`);
    continue;
  }
  const { texts, pinyins, weights } = readDict(file);
  console.log(`  ${src.file.padEnd(20)} ${texts.length.toLocaleString().padStart(9)} 条`);
  for (let i = 0; i < texts.length; i++) {
    const py = pinyins[i];
    if (!py) continue;
    const syl = py.replace(/\s+/g, '');
    if (!syl) continue;
    if (allowedSyllables && !allowedSyllables.has(syl)) {
      droppedSyllables.add(syl);
      continue;
    }

    let byPy = charPinyin.get(texts[i]);
    if (!byPy) { byPy = new Map(); charPinyin.set(texts[i], byPy); }
    const prev = byPy.get(syl);
    if (prev === undefined || weights[i] > prev) byPy.set(syl, weights[i]);

    let byCh = syllableChars.get(syl);
    if (!byCh) { byCh = new Map(); syllableChars.set(syl, byCh); }
    const prevCh = byCh.get(texts[i]);
    if (prevCh === undefined || weights[i] > prevCh) byCh.set(texts[i], weights[i]);
  }
}

// 字 -> 最佳拼音（权重最高的读音），用于给无拼音列的词补注音
const bestPinyin = new Map();
for (const [ch, byPy] of charPinyin) {
  let best = null;
  let bestW = -Infinity;
  for (const [py, w] of byPy) {
    if (w > bestW) { bestW = w; best = py; }
  }
  if (best) bestPinyin.set(ch, best);
}
console.log(`  字 -> 拼音映射：${bestPinyin.size.toLocaleString()} 个字`);

// 写单字表
// 格式：音节 <TAB> 字1:分数,字2:分数,...
// 分数 = round(log2(权重) * 4)，越大越常用。带分数是为了让后面的
// 「分词+组句」能按词频打分（词 vs 拆字的选择）。
{
  const out = ['# 音节\t候选字（字:分数，逗号分隔，按词频降序）'];
  for (const syl of [...syllableChars.keys()].sort()) {
    const byCh = syllableChars.get(syl);
    const chars = [...byCh.entries()]
      .sort((a, b) => b[1] - a[1])
      .slice(0, MAX_CHARS_PER_SYLLABLE)
      .map(([ch, w]) => `${ch}:${scoreOf(w)}`);
    out.push(`${syl}\t${chars.join(',')}`);
  }
  const file = path.join(OUT_DIR, 'chars.txt');
  fs.writeFileSync(file, out.join('\n') + '\n', 'utf8');
  console.log(
    `单字表 -> ${file}  (${(fs.statSync(file).size / 1024).toFixed(1)} KB, ${syllableChars.size.toLocaleString()} 个音节)`,
  );
  if (droppedSyllables.size) {
    console.log(
      `  过滤掉 ${droppedSyllables.size} 个音节表覆盖不到的音节：${[...droppedSyllables].sort().join(' ')}`,
    );
  }
}

// ---------- 词表 ----------
console.log('读取词表...');
const wordKey = [];
const wordText = [];
const wordWeight = [];
const seen = new Set();
let skippedNoPinyin = 0;
let skippedUnknown = 0;

for (const src of SOURCES.filter((s) => s.kind === 'word')) {
  if (src.file === 'tencent.dict.yaml' && !INCLUDE_TENCENT) {
    console.log(`  ${src.file.padEnd(20)} 跳过（默认不导入长尾，加 --include-tencent 可启用）`);
    continue;
  }
  const file = path.join(DICT_DIR, src.file);
  if (!fs.existsSync(file)) {
    console.log(`  跳过（不存在）：${src.file}`);
    continue;
  }
  const { texts, pinyins, weights } = readDict(file);
  let added = 0;
  let skippedLong = 0;
  for (let i = 0; i < texts.length; i++) {
    let py = pinyins[i];
    let syllableCount = 0;
    if (!py) {
      // 无拼音列（腾讯词向量）：按字表逐字注音
      let ok = true;
      let buf = '';
      for (const ch of texts[i]) {
        const s1 = bestPinyin.get(ch);
        if (!s1) { ok = false; break; }
        buf += s1;
      }
      if (!ok) { skippedUnknown++; continue; }
      py = buf;
    }
    // 音节数要按空格分隔数，不能用拼接后的字符串长度——
    // "womenshibushi" 是 4 个音节 13 个字母，按长度算会误杀一大片。
    syllableCount = py.trim().split(/\s+/).filter(Boolean).length;
    const key = py.replace(/\s+/g, '');
    if (!key) { skippedNoPinyin++; continue; }
    if (syllableCount > MAX_SYLLABLES) { skippedLong++; continue; }
    // 靠前的词库权重生效：同一个 (key, word) 只保留第一次出现的
    const dedupe = `${key}\u001f${texts[i]}`;
    if (seen.has(dedupe)) continue;
    seen.add(dedupe);
    wordKey.push(key);
    wordText.push(texts[i]);
    wordWeight.push(weights[i]);
    added++;
  }
  console.log(
    `  ${src.file.padEnd(20)} ${texts.length.toLocaleString().padStart(9)} 条，写入 ${added.toLocaleString()}` +
    (skippedLong ? `，超长跳过 ${skippedLong.toLocaleString()}` : ''),
  );
}

console.log(`排序 ${wordText.length.toLocaleString()} 条 ...`);
const order = new Uint32Array(wordText.length);
for (let i = 0; i < order.length; i++) order[i] = i;
const orderArr = Array.from(order);
orderArr.sort((a, b) => {
  if (wordKey[a] !== wordKey[b]) return wordKey[a] < wordKey[b] ? -1 : 1;
  return wordWeight[b] - wordWeight[a];
});

const out = ['# 拼音串\t候选词（词:分数，逗号分隔，按词频降序）'];
let curKey = null;
let buf = [];
const flush = () => {
  if (curKey !== null) out.push(`${curKey}\t${buf.join(',')}`);
};
for (const idx of orderArr) {
  const k = wordKey[idx];
  if (k !== curKey) {
    flush();
    curKey = k;
    buf = [];
  }
  if (buf.length < MAX_PHRASES_PER_KEY) buf.push(`${wordText[idx]}:${scoreOf(wordWeight[idx])}`);
}
flush();

const phrasesFile = path.join(OUT_DIR, 'phrases.txt');
fs.writeFileSync(phrasesFile, out.join('\n') + '\n', 'utf8');
console.log(
  `词表 -> ${phrasesFile}  (${(fs.statSync(phrasesFile).size / 1024 / 1024).toFixed(1)} MB, ${(out.length - 1).toLocaleString()} 个拼音串)`,
);
console.log(`  跳过：无注音 ${skippedNoPinyin.toLocaleString()} 条，注音不全 ${skippedUnknown.toLocaleString()} 条`);
