// ============================================================
// prototype_check.mjs
// 免浏览器的原型自检：直接从 index.html 抽出 JS 引擎，跑 test_engine.py 的同一套断言。
//
// 好处：不需要装 Playwright / Chromium，秒级出结果，
// 改完 index.html 立刻能验证引擎逻辑。
//
// 用法：
//   node tools/prototype_check.mjs
// ============================================================
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const html = fs.readFileSync(path.join(ROOT, 'index.html'), 'utf8');

// ---- 抽出 <script> 并砍掉「8. 初始化」之后的部分（那部分会碰 DOM）----
const scriptMatch = html.match(/<script>([\s\S]*?)<\/script>/);
if (!scriptMatch) {
  console.error('index.html 里找不到 <script>');
  process.exit(1);
}
let js = scriptMatch[1];
const cut = js.indexOf('8. 初始化');
if (cut < 0) {
  console.error('找不到「8. 初始化」标记，无法安全截断');
  process.exit(1);
}
js = js.slice(0, js.lastIndexOf('/*', cut));

// ---- 在函数作用域里求值，把需要的绑定导出来 ----
const api = new Function(
  `${js}
   return { SCHEMES, resolveFinals, encode, parseSyl,
            SYL_BY_IF, FINALS_BY_INIT, ZERO_STAGE, CHECK_CASES, INITS,
            setScheme: (id) => { SCHEME = SCHEMES[id]; } };`,
)();

const { SCHEMES, resolveFinals, encode, SYL_BY_IF, CHECK_CASES } = api;

// ---- 断言 ----
const pass = [];
const fail = [];
const T = (name, cond, extra) => {
  (cond ? pass : fail).push(name + (extra !== undefined ? ` -> ${extra}` : ''));
};

const xh = SCHEMES.xiaohe;
const zr = SCHEMES.ziranma;

// 1. 键位表编码正确性
for (const [py, code] of [
  ['bai', 'bd'], ['zhong', 'vs'], ['xue', 'xt'], ['wen', 'wf'], ['ping', 'pk'],
  ['liang', 'll'], ['nv', 'nv'], ['ju', 'jv'], ['jue', 'jt'], ['chuang', 'il'],
]) {
  T(`编码 ${py}=${code}`, encode.call(null, py) === code, encode.call(null, py));
}

// 2. 零声母
for (const [py, code] of [
  ['a', 'aa'], ['ai', 'ai'], ['an', 'an'], ['ang', 'ah'],
  ['en', 'en'], ['eng', 'eg'], ['er', 'er'], ['ou', 'ou'],
]) {
  T(`零声母 ${py}=${code}`, encode.call(null, py) === code, encode.call(null, py));
}

// 3. 第一键：26 个键全部合法
const badFirst = [];
for (const k of 'abcdefghijklmnopqrstuvwxyz') {
  if (resolveFinals(xh, k).size === 0) badFirst.push(k);
}
T('26 个键都能作为第一键', badFirst.length === 0, badFirst.join(','));

// 4. b 的第二阶段
const fb = resolveFinals(xh, 'b');
T('b 的第二阶段有 16 个可用键', fb.size === 16, fb.size);
T('b+d = ai/bai', fb.get('d') && fb.get('d').fin === 'ai' && fb.get('d').syl === 'bai',
  JSON.stringify(fb.get('d')));
T('b+k = ing/bing', fb.get('k') && fb.get('k').fin === 'ing', JSON.stringify(fb.get('k')));
T('b+e 不是合法音节', !fb.get('e'));
T('b 的所有第二键都不带歧义', [...fb.values()].every((v) => !v.fin.includes('/')));

// 5. 9 个歧义键在任意声母下唯一
const AMBIG = ['k', 's', 'x', 'l', 'o', 'v', 'y', 't', 'r'];
const unresolved = [];
for (const first of 'abcdefghijklmnopqrstuvwxyz') {
  const finals = resolveFinals(xh, first);
  for (const k of AMBIG) {
    const hit = finals.get(k);
    if (hit && hit.fin.includes('/')) unresolved.push(`${first}+${k}=${hit.fin}`);
  }
}
T('9 个歧义键在任意声母下都唯一', unresolved.length === 0, unresolved.join(' | '));

// 6. 每个音节都有两键编码
const noCode = [];
for (const syl of Object.values(SYL_BY_IF)) {
  const c = encode.call(null, syl);
  if (!c || c.length !== 2) noCode.push(`${syl}=${c}`);
}
T('每个音节都有两键编码', noCode.length === 0, noCode.slice(0, 10).join(','));

// 7. 编码可逆
const dec = {};
for (const syl of Object.values(SYL_BY_IF)) dec[encode.call(null, syl)] = syl;
const unrev = [];
for (const [code, syl] of Object.entries(dec)) {
  const hit = resolveFinals(xh, code[0]).get(code[1]);
  if (!hit || hit.syl !== syl) unrev.push(`${code}=${syl} vs ${hit ? hit.syl : 'null'}`);
}
T('所有编码都可逆', unrev.length === 0, unrev.slice(0, 12).join(' | '));

// 8. 校验用例（两套方案）
for (const [sid, scheme] of [['xiaohe', xh], ['ziranma', zr]]) {
  const cases = CHECK_CASES[sid];
  api.setScheme(sid);
  const bad = cases
    .filter(([z, py, code]) => encode.call(null, py) !== code)
    .map(([z, py, code]) => `${z}:${encode.call(null, py)}≠${code}`);
  T(`${sid} 校验用例（${cases.length} 条）`, bad.length === 0, bad.join(','));
}
api.setScheme('xiaohe');

// 9. 词表覆盖
const missing = [];
for (const syl of Object.values(SYL_BY_IF)) {
  // 原型的 CHARS 表在文件里，这里只检查音节表自洽
  if (!syl) missing.push(syl);
}
T('音节表无空音节', missing.length === 0, missing.join(','));

// ---- 输出 ----
console.log(`PASS: ${pass.length}`);
for (const x of pass) console.log(`  ok   ${x}`);
console.log(`FAIL: ${fail.length}`);
for (const x of fail) console.log(`  XX   ${x}`);
console.log(`\n音节数: ${Object.keys(SYL_BY_IF).length}   零声母条目: ${Object.keys(api.ZERO_STAGE).length}`);
process.exit(fail.length === 0 ? 0 : 1);
