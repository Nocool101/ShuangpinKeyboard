// ============================================================
// segment_eval.mjs
// 分词+组句的离线评估器：拿真实句子试不同打分策略，选出最好的再落到 Kotlin。
//
// 用法：
//   node tools/segment_eval.mjs
// ============================================================
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const DATA = path.join(ROOT, 'android', 'engine', 'src', 'main', 'resources', 'ime');

// ---- 读词库 ----
const phrases = new Map(); // key -> [{w, s}]
for (const line of fs.readFileSync(path.join(DATA, 'phrases.txt'), 'utf8').split('\n')) {
  const t = line.trim();
  if (!t || t.startsWith('#')) continue;
  const i = t.indexOf('\t');
  if (i < 0) continue;
  const items = t.slice(i + 1).split(',').map((x) => {
    const j = x.lastIndexOf(':');
    return { w: x.slice(0, j), s: Number(x.slice(j + 1)) };
  });
  phrases.set(t.slice(0, i), items);
}
const chars = new Map(); // syl -> [{w, s}]
for (const line of fs.readFileSync(path.join(DATA, 'chars.txt'), 'utf8').split('\n')) {
  const t = line.trim();
  if (!t || t.startsWith('#')) continue;
  const i = t.indexOf('\t');
  if (i < 0) continue;
  const items = t.slice(i + 1).split(',').map((x) => {
    const j = x.lastIndexOf(':');
    return { w: x.slice(0, j), s: Number(x.slice(j + 1)) };
  });
  chars.set(t.slice(0, i), items);
}
console.log(`词库 ${phrases.size} 拼音串，字表 ${chars.size} 音节\n`);

// ---- 音节序列上的 DP 分词（beam search，可展开同音词的多个候选）----
// longBonus: 覆盖音节越多的词额外加分（鼓励长词）
// penalty:   每个词的罚分（ discourage 过度切分）
// beamSize:  每个位置保留的候选假设数
// topN:      每个拼音串展开的候选词个数（同音词）
function segment(sylls, penalty, longBonus, beamSize = 1, topN = 1) {
  const n = sylls.length;
  // beam[i] = [{score, path}] 按 score 降序
  const beam = new Array(n + 1).fill(null);
  beam[0] = [{ score: 0, path: [] }];

  for (let i = 0; i < n; i++) {
    if (!beam[i]) continue;
    for (const hyp of beam[i]) {
      for (let j = i + 1; j <= n; j++) {
        const key = sylls.slice(i, j).join('');
        const cands = [];
        const ws = phrases.get(key);
        if (ws && ws.length) {
          for (let t = 0; t < Math.min(topN, ws.length); t++) {
            cands.push({ text: ws[t].w, score: ws[t].s - t * 4 });
          }
        } else if (j === i + 1) {
          const cs = chars.get(key);
          if (cs && cs.length) cands.push({ text: cs[0].w, score: cs[0].s - 20 });
        }
        if (!cands.length) continue;
        const k = j - i;
        for (const cand of cands) {
          const score = hyp.score + cand.score + longBonus * (k - 1) - penalty;
          if (!beam[j]) beam[j] = [];
          beam[j].push({ score, path: [...hyp.path, cand.text] });
        }
      }
    }
    if (beam[i]) {
      beam[i].sort((a, b) => b.score - a.score);
      beam[i] = beam[i].slice(0, beamSize);
    }
  }
  if (!beam[n]) return null;
  // 注意：beam[n] 不会被主循环处理到（循环只到 n-1），所以这里必须自己排序，
  // 否则取到的是最先入队的候选而不是最优解。
  beam[n].sort((a, b) => b.score - a.score);
  return beam[n][0];
}

// ---- 测试集：音节序列 + 期望结果 ----
const CASES = [
  { syl: 'wo xiang chi fan'.split(' '), want: '我想吃饭' },
  { syl: 'wo shi zhong guo ren'.split(' '), want: '我是中国人' },
  { syl: 'jin tian tian qi zhen hao a'.split(' '), want: '今天天气真好啊' },
  { syl: 'wo xi huan wo de bi ji ben'.split(' '), want: '我喜欢我的笔记本' },
  { syl: 'qing wen ni zhi dao ma'.split(' '), want: '请问你知道吗' },
  { syl: 'wo men shi yi ge tuan dui'.split(' '), want: '我们是一个团队' },
  { syl: 'ni hao'.split(' '), want: '你好' },
  { syl: 'zhong guo ren min'.split(' '), want: '中国人民' },
  { syl: 'zhe ge wen ti hen zhong yao'.split(' '), want: '这个问题很重要' },
  { syl: 'wo yao qu chao shi mai shui guo'.split(' '), want: '我要去超市买水果' },
  { syl: 'ming tian hui xia yu'.split(' '), want: '明天下雨' },
  { syl: 'zhe ge wen ti hen zhong yao ma'.split(' '), want: '这个问题很重要吗' },
  { syl: 'wo men yi jing dao le'.split(' '), want: '我们已经到了' },
  { syl: 'ta zai kan shu'.split(' '), want: '他在看书' },
  { syl: 'qu ba'.split(' '), want: '去吧' },
  { syl: 'zai jia li'.split(' '), want: '在家里' },
  { syl: 'xue xiao men yi jing fang xue le'.split(' '), want: '学生们已经放学了' },
  { syl: 'qing gei wo yi bei shui'.split(' '), want: '请给我一杯水' },
  { syl: 'zhe ge xiang mu hen you yi si'.split(' '), want: '这个项目很有意思' },
  { syl: 'wo ming tian hui qu bei jing'.split(' '), want: '我明天会去北京' },
  { syl: 'zhe shi yi ge hen hao de yong hu'.split(' '), want: '这是一个很好用的用户' },
  { syl: 'wo men yi qi qu chi fan ba'.split(' '), want: '我们一起去吃饭吧' },
  { syl: 'qing bang wo kan kan zhe ge wen ti'.split(' '), want: '请帮我看看这个问题' },
];

console.log('=== 策略对比（beam × topN × penalty）===');
let bestCombo = null;
for (const beamSize of [1, 8]) {
  for (const topN of [1, 3, 5]) {
    for (const penalty of [80, 100, 120, 140]) {
      let ok = 0;
      const detail = [];
      for (const c of CASES) {
        const r = segment(c.syl, penalty, 10, beamSize, topN);
        const got = r ? r.path.join('') : '(无)';
        if (got === c.want) ok++;
        else detail.push(`${c.want}=>${got}`);
      }
      if (!bestCombo || ok > bestCombo.ok) {
        bestCombo = { beamSize, topN, penalty, ok };
      }
      console.log(
        `beam=${beamSize} topN=${topN} penalty=${String(penalty).padStart(3)}  命中 ${ok}/${CASES.length}${detail.length ? ' | ' + detail.slice(0, 2).join(' ; ') : ''}`,
      );
    }
  }
}
console.log(
  `\n最佳: beam=${bestCombo.beamSize} topN=${bestCombo.topN} penalty=${bestCombo.penalty} 命中 ${bestCombo.ok}/${CASES.length}`,
);

console.log('\n=== 逐句结果 ===');
for (const c of CASES) {
  const r = segment(c.syl, bestCombo.penalty, 10, bestCombo.beamSize, bestCombo.topN);
  const got = r ? r.path.join('|') : '(无)';
  const mark = r && r.path.join('') === c.want ? 'ok ' : 'XX ';
  console.log(`  ${mark}${c.want.padEnd(14)} => ${got}`);
}
