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
// 只重建字表，不动词表（词表二十几 MB，重建慢且没必要）
const ONLY_CHARS = args.includes('--only-chars');
// 大字表（41448）没有字频，给一个比谁都小的权重：
// 排序时它天然排在《通用规范汉字表》的字后面——
// 这正是 rime-ice 的原意（常用字在前，生僻字殿后）。
const BIG_TABLE_WEIGHT = 0;

/**
 * 这个字/词里有没有「手机画不出来」的扩展字。
 *
 * **扩展 B 及以上（U+20000 起）在 UTF-16 里是代理对**，
 * 而绝大多数手机字体（Noto Sans CJK 只覆盖 BMP 和扩展 A）**没有这些字形**——
 * 画出来不是豆腐块，是**什么都没有**。
 *
 * 后果：
 * - 字表里：候选栏末尾几个 chip 是空白；面板里滑到底是一整片空白
 *   （用户反馈「滑到最后一行还能继续下滑，导致空白」）。
 * - 词表里更糟：一个词里夹一个这样的字，**整个词都显示不全**。
 *
 * 所以字表和词表都把 U+20000 以上的字滤掉——
 * 看不见的字等于打不出来，留着只是噪声。
 *
 * 扩展 A（U+3400–U+4DBF）在 BMP 里、常见字体都支持，**保留**。
 */
function hasUnsupportedChar(text) {
  for (const ch of text) {
    if (ch.codePointAt(0) >= 0x20000) return true;
  }
  return false;
}

// ---------- 繁体字过滤 ----------
//
// 大字表（41448）里有大量**繁体字**（鴛/鶻/鷀/鵴…），用户明确要求
// 「不要繁体文字，只要简体」。
//
// 「哪些是繁体」需要一张对照表——没有表只能靠猜（生僻简体字长得也很怪）。
// 表由 tools/make_t2s.ps1 生成（Unihan kSimplifiedVariant + OpenCC TSCharacters）。
const T2S_FILE = argOf('t2s', 'D:\\tmp\\rime-ice-dict\\t2s.txt');

/**
 * 读「繁 -> 简」对照表，返回**繁体字的集合**（键）。
 */
function loadTraditionalSet(file) {
  const set = new Set();
  if (!fs.existsSync(file)) {
    console.warn(`⚠ 找不到繁简表 ${file}——繁体字不会被过滤。`);
    console.warn('  生成：pwsh -File tools/make_t2s.ps1');
    return set;
  }
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    const t = line.trim();
    if (!t || t.startsWith('#')) continue;
    const i = t.indexOf('\t');
    if (i < 0) continue;
    const trad = t.slice(0, i).trim();
    if (trad) set.add(trad);
  }
  return set;
}

const traditionalSet = loadTraditionalSet(T2S_FILE);
if (traditionalSet.size) {
  console.log(`繁简表：${traditionalSet.size} 个繁体字将被过滤（${T2S_FILE}）`);
}

/** 这段文字里有没有繁体字。 */
function hasTraditionalChar(text) {
  for (const ch of text) {
    if (traditionalSet.has(ch)) return true;
  }
  return false;
}

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
 * 三种数据行：
 *   词/字 <TAB> 拼音 <TAB> 权重      （8105 字表、base/ext 等词库）
 *   词/字 <TAB> 拼音                 （41448 大字表：只有注音，没有字频）
 *   词     <TAB> 权重                （腾讯词向量：没有拼音列）
 * 返回 [texts, pinyins, weights] 三个平行数组。
 *
 * ⚠ 两列的行有两种含义，别一律当成「没有拼音列」：
 *   以前这里把两列一律当成权重，于是 **41448 大字表被整个丢掉了**——
 *   它的行是「字 + 拼音」，被当成权重之后拼音列是 null，
 *   上面的循环 `if (!py) continue` 就把所有生僻字跳过了。
 *   表现就是「打 duan 找不到蚖」：字表里压根没有这个字。
 *
 * 区分办法：第二列是纯数字 → 权重；不是 → 拼音。
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
      const second = p[1].trim();
      if (/^-?\d+(\.\d+)?$/.test(second)) {
        // 「词 + 权重」：没有拼音列（腾讯词向量）
        pinyins.push(null);
        weights.push(Number(second) || 0);
      } else {
        // 「字 + 拼音」：没有字频（41448 大字表）
        pinyins.push(second);
        weights.push(BIG_TABLE_WEIGHT);
      }
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
const astralOnly = new Map();    // 音节 -> Map<字, 权重>（全是画不出来的扩展字）
const droppedSyllables = new Set();
let skippedTraditional = 0;

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

    // 画不出来的扩展字：先单独收着
    if (hasUnsupportedChar(texts[i])) {
      let byCh = astralOnly.get(syl);
      if (!byCh) { byCh = new Map(); astralOnly.set(syl, byCh); }
      const prev = byCh.get(texts[i]);
      if (prev === undefined || weights[i] > prev) byCh.set(texts[i], weights[i]);
      continue;
    }

    // 繁体字：用户要简体，繁体一律不要
    if (hasTraditionalChar(texts[i])) { skippedTraditional++; continue; }

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

// 收尾：某个音节**只剩**画不出来的扩展字 → 把它们放回去。
//
// 音节表里有这个音节，字表里却一个字都没有的话，
// 这个音节就打不出任何东西（引擎单测「词库覆盖音节表里的每个音节」也会挂）。
// 宁可留一个看不见的字，也别让整个音节消失。
// （实测只有 cei 这一个音节：它唯一的字是扩展区的「𤭢」。）
let keptAstral = 0;
for (const [syl, byCh] of astralOnly) {
  if (syllableChars.has(syl)) continue;
  syllableChars.set(syl, byCh);
  keptAstral += byCh.size;
}
if (keptAstral) {
  console.log(`  ${keptAstral} 个扩展字留在字表里（所在音节没有别的字，滤掉音节就没了）`);
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
  if (skippedTraditional) {
    console.log(`  过滤掉 ${skippedTraditional.toLocaleString()} 个繁体字（用户要简体）`);
  }
  if (droppedSyllables.size) {
    console.log(
      `  过滤掉 ${droppedSyllables.size} 个音节表覆盖不到的音节：${[...droppedSyllables].sort().join(' ')}`,
    );
  }
}

// ---------- 词表 ----------
if (ONLY_CHARS) {
  console.log('--only-chars：词表保持原样，不重建');
  process.exit(0);
}
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
    // 含画不出来的扩展字 → 整个词丢掉：
    // 少一个字它就不是那个词了，而显示不全的词进了候选栏更糟。
    if (hasUnsupportedChar(texts[i])) { skippedUnknown++; continue; }
    // 含繁体字 → 整个词丢掉（用户要简体；简体版本本来就在词库里）
    if (hasTraditionalChar(texts[i])) { skippedTraditional++; continue; }
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
