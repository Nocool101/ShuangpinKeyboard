#!/usr/bin/env node
/**
 * 「联想服务」：把输入法要的联想交给大模型。
 *
 * ## 为什么要有这一层
 *
 * 输入法**只跟这个服务说话**，不直接调模型 API：
 * - API key 留在服务端，绝不进 APK（打进 APK 等于把 key 送出去）
 * - 换模型 / 换供应商只改服务端，输入法不动
 * - 用户可以自建这个服务（msime 就是这个路子：host-api + 自建后端）
 *
 * ## 协议
 *
 * 请求  POST /lexeme
 *   {
 *     "text":       "智慧",              // 已上屏的中文上文
 *     "pinyin":     "zhihui",            // 用户当前正在输入的拼音
 *     "candidates": ["智慧","只会","指挥"], // 引擎这一轮的候选池
 *     "schema": 2
 *   }
 *
 * 响应  200
 *   { "next": ["智慧", "指挥"] }
 *
 * ## 三种模式（服务端按请求里有什么来定）
 *
 * | 请求里有 | 模式 | 问的问题 |
 * |---|---|---|
 * | candidates | **消歧** | 这堆候选里，按上文哪个最可能？ |
 * | pinyin | **补全** | 这个拼音最可能是什么词？ |
 * | 都没有 | **下一词** | 接下来该打什么词？ |
 *
 * ## 后端
 *
 *   LEXEME_BACKEND=minimax   默认。MiniMax（Anthropic 兼容协议），
 *                             key 取自 MINIMAX_API_KEY 或工作区的 "MiniMax key.txt"
 *   LEXEME_BACKEND=stepfun   阶跃星辰（OpenAI 兼容），key 取自 STEPFUN_API_KEY
 *   LEXEME_BACKEND=openai    任意 OpenAI 兼容端点：
 *                             OPENAI_BASE_URL / OPENAI_API_KEY / OPENAI_MODEL
 *   LEXEME_BACKEND=mock      不联网，用内置的小表（离线开发 / 没有 key 时）
 *
 * ## 跑起来
 *
 *   node tools/lexeme_server.mjs [--port 8787]
 */

import { createServer } from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WORKSPACE = dirname(HERE);

const args = process.argv.slice(2);
const portArg = args.indexOf('--port');
const PORT = portArg >= 0 ? Number(args[portArg + 1]) : 8787;
const BACKEND = process.env.LEXEME_BACKEND || 'minimax';

// ---------------------------------------------------------------- 提示词

/** 消歧模式：给了候选池，让模型按上文重新排序。 */
const SYSTEM_DISAMBIGUATE = [
  '你是一个中文输入法的候选消歧引擎。',
  '用户已经输入了一段上文，现在正在输入一个拼音串，候选栏给出了若干个候选。',
  '请根据**上文**判断用户最可能想输入的是哪个候选。',
  '规则：',
  '1. **只能从给定的候选里挑**，绝对不要发明新词、不要改字；',
  '2. 给出最多 3 个候选，按可能性从高到低；',
  '3. 看不出哪个更可能就返回空数组 []；',
  '4. 只输出 JSON，形如 {"next":["词1","词2"]}，不要任何多余文字。',
].join('\n');

/** 补全模式：给了拼音，让模型猜这个词。 */
const SYSTEM_COMPLETE = [
  '你是一个中文输入法的词预测引擎。',
  '用户已经输入了一段上文，现在正在输入一个拼音串。',
  '请根据上文和这个拼音串，预测他最可能想输入的词。',
  '规则：',
  '1. 给最多 3 个候选，按可能性从高到低；',
  '2. 每个候选必须是 1~4 个汉字组成的**词**，不要标点、不要单字、不要解释；',
  '3. 候选必须和这个拼音对得上（zhihui → 智慧/指挥/知会 这种）；',
  '4. 结合上文判断哪个更可能；',
  '5. 只输出 JSON，形如 {"next":["词1","词2"]}，不要任何多余文字。',
].join('\n');

/** 下一词模式：只给了上文，预测接下来打什么。 */
const SYSTEM_NEXT = [
  '你是一个中文输入法的「下一词预测」引擎。',
  '根据用户已经输入的中文上文，预测他接下来最可能继续输入的词。',
  '规则：',
  '1. 给 3 个候选，按可能性从高到低；',
  '2. 每个候选必须是 1~3 个汉字组成的**词**，不要标点、不要单字、不要解释；',
  '3. 候选要能直接接在上文后面，读起来自然；',
  '4. 不要重复上文里已经出现的词；',
  '5. 只输出 JSON，形如 {"next":["词1","词2","词3"]}，不要任何多余文字。',
].join('\n');

/** 纠错模式：把刚上屏的文本里的错别字改对。 */
const SYSTEM_CORRECT = [
  '你是一个中文输入法的错别字校对器。',
  '用户刚输入了一段中文，里面可能有选错的字（同音字、形近字）。',
  '请找出**明显的错别字**并改正。',
  '规则：',
  '1. 只改**确实是错别字**的地方，比如同音字选错（"我系广东人"→"我是广东人"）；',
  '2. 专有名词、缩写、拼音串、英文、数字**一律不要动**；',
  '3. 不要改写用户的表达方式，不要加词删词，只换字；',
  '4. 拿不准就原样返回；',
  '5. 只输出 JSON，形如 {"next":["改正后的整段文字"]}；没有错别字就输出 {"next":[]}。',
].join('\n');

/** mock 后端：离线也能把流程跑通。 */
const MOCK_TABLE = {
  你好: ['世界', '啊', '吗'],
  我: ['是', '想', '要'],
  你: ['们', '好', '的'],
  在: ['家', '学校', '公司'],
  吃: ['饭', '面', '菜'],
  想: ['吃', '去', '要'],
  今天: ['天气', '晚上', '很'],
  明天: ['见', '去', '要'],
  智慧: ['城市', '课堂', '医疗'],
};

function mockPredict(text) {
  const tail = text.slice(-3);
  if (MOCK_TABLE[tail]) return MOCK_TABLE[tail].slice();
  if (MOCK_TABLE[text.slice(-2)]) return MOCK_TABLE[text.slice(-2)].slice();
  const hit = Object.keys(MOCK_TABLE).find((k) => text.endsWith(k));
  if (hit) return MOCK_TABLE[hit].slice();
  return ['的', '了', '是'];
}

/** mock 的消歧：在候选里挑，挑不出来就按原样回前几个。 */
function mockDisambiguate(text, candidates) {
  if (!candidates.length) return [];
  const hint = mockPredict(text);
  const preferred = candidates.filter((c) => hint.includes(c));
  const rest = candidates.filter((c) => !hint.includes(c));
  return [...preferred, ...rest].slice(0, 3);
}

/** mock 的纠错：只认一个常见的同音错字。 */
const MOCK_TYPOS = {
  '我系': '我是',
  '的話': '的话',
  '一在': '一直在',
};

function mockCorrect(input) {
  for (const [wrong, right] of Object.entries(MOCK_TYPOS)) {
    if (input.includes(wrong)) return [input.split(wrong).join(right)];
  }
  return [];
}

// ---------------------------------------------------------------- 模型后端

/** MiniMax 的凭据：环境变量优先，其次工作区的 "MiniMax key.txt"。 */
function loadMiniMax() {
  let apiKey = process.env.MINIMAX_API_KEY || '';
  let baseURL = process.env.MINIMAX_BASE_URL || 'https://api.minimax.cn/anthropic';

  const keyFile = process.env.MINIMAX_KEY_FILE || join(WORKSPACE, 'MiniMax key.txt');
  if (!apiKey && existsSync(keyFile)) {
    for (const line of readFileSync(keyFile, 'utf8').split(/\r?\n/)) {
      const t = line.trim();
      if (!t) continue;
      if (t.startsWith('sk-')) apiKey = t;
      else if (t.includes('BASE_URL=')) baseURL = t.split('=').slice(1).join('=').trim();
    }
  }
  if (!apiKey) throw new Error('没有 MiniMax key（MINIMAX_API_KEY 或 MiniMax key.txt）');
  return { apiKey, baseURL: baseURL.replace(/\/+$/, '') };
}

/**
 * 把请求拼成给模型看的一段话。
 *
 * 四个字段各管一件事：**上文**给语境，**拼音**给读音，**候选池**给边界，
 * **待纠错文本**给要改的那段话。
 */
function buildUserMessage(req) {
  const lines = [];
  if (req.text) lines.push(`上文：${req.text}`);
  if (req.pinyin) lines.push(`正在输入：${req.pinyin}`);
  if (req.candidates.length) lines.push(`候选：${req.candidates.join('、')}`);
  if (req.input) lines.push(`待纠错：${req.input}`);
  return lines.join('\n');
}

/**
 * 模型名：请求里指定的优先，否则用服务端配的。
 *
 * 这样用户在设置页里填的模型名能生效，
 * 而自建服务的默认模型还是由服务端的环境变量控制。
 */
function modelOf(req, fallback) {
  return req.model || process.env.LEXEME_MODEL || fallback;
}

/** 采样温度：请求里指定的优先。 */
function temperatureOf(req, fallback) {
  return Number.isFinite(req.temperature) ? req.temperature : fallback;
}

async function minimaxPredict(req, system) {
  const { apiKey, baseURL } = loadMiniMax();
  const model = modelOf(req, 'MiniMax-M2.5-highspeed');
  const res = await fetch(`${baseURL}/v1/messages`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-api-key': apiKey,
      'anthropic-version': '2023-06-01',
    },
    body: JSON.stringify({
      model,
      max_tokens: Number(process.env.LEXEME_MAX_TOKENS || 512),
      temperature: temperatureOf(req, 0.3),
      system,
      messages: [{ role: 'user', content: buildUserMessage(req) }],
    }),
  });
  if (!res.ok) throw upstreamError(res.status, await res.text());
  const data = await res.json();
  const raw = (data.content || [])
    .map((c) => (c.type === 'text' ? c.text : ''))
    .join('');
  return parseNext(raw, pickMode(req) === 'correct');
}

/**
 * 上游报错：把状态码挂在错误对象上。
 *
 * 为什么要单独记：**限流（429）和真故障（500/401）要分开**。
 * 之前一律转成 502，客户端只看到「服务坏了」，
 * 手机上表现为「云端功能时好时坏」，实际只是额度用完了；
 * 测试也只能把限流当成失败来报。
 */
function upstreamError(status, text) {
  const e = new Error(`模型返回 ${status}: ${String(text || '').slice(0, 200)}`);
  e.upstreamStatus = status;
  return e;
}

async function stepfunPredict(req, system) {
  const key = process.env.STEPFUN_API_KEY;
  if (!key) throw new Error('没有 STEPFUN_API_KEY');
  const base = process.env.STEPFUN_BASE_URL || 'https://api.stepfun.com/v1';
  const model = modelOf(req, 'step-1o-turbo-vision');
  const res = await fetch(`${base}/chat/completions`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${key}` },
    body: JSON.stringify({
      model,
      max_tokens: Number(process.env.LEXEME_MAX_TOKENS || 96),
      // 阶跃的推理模型会把 token 全烧在 reasoning 上，这里直接关掉
      enable_reason: false,
      temperature: temperatureOf(req, 0.3),
      messages: [
        { role: 'system', content: system },
        { role: 'user', content: buildUserMessage(req) },
      ],
    }),
  });
  if (!res.ok) throw upstreamError(res.status, await res.text());
  const data = await res.json();
  const raw = data?.choices?.[0]?.message?.content || '';
  return parseNext(raw, pickMode(req) === 'correct');
}

async function openaiPredict(req, system) {
  const key = process.env.OPENAI_API_KEY;
  const base = process.env.OPENAI_BASE_URL;
  // 模型名：请求里指定的优先（用户可以在设置页里换），否则用环境变量
  const model = req.model || process.env.OPENAI_MODEL;
  if (!key || !base || !model) throw new Error('OPENAI_BASE_URL / OPENAI_API_KEY / OPENAI_MODEL 缺一不可');
  const res = await fetch(`${base.replace(/\/+$/, '')}/chat/completions`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${key}` },
    body: JSON.stringify({
      model,
      max_tokens: Number(process.env.LEXEME_MAX_TOKENS || 96),
      temperature: temperatureOf(req, 0.3),
      messages: [
        { role: 'system', content: system },
        { role: 'user', content: buildUserMessage(req) },
      ],
    }),
  });
  if (!res.ok) throw upstreamError(res.status, await res.text());
  const data = await res.json();
  const raw = data?.choices?.[0]?.message?.content || '';
  return parseNext(raw, pickMode(req) === 'correct');
}

/**
 * 从模型回复里抠出 next 数组（模型偶尔会带解释或代码块）。
 *
 * @param allowPunct 纠错模式要允许标点——改正后的整句本来就带标点。
 */
function parseNext(raw, allowPunct = false) {
  let s = String(raw).trim();
  const fence = s.match(/```(?:json)?\s*([\s\S]*?)```/);
  if (fence) s = fence[1].trim();
  const start = s.indexOf('{');
  if (start < 0) throw new Error(`回复里没有 JSON：${s.slice(0, 120)}`);
  const end = s.lastIndexOf('}');
  const obj = JSON.parse(s.slice(start, end + 1));
  const next = Array.isArray(obj.next) ? obj.next : [];
  return next
    .filter((x) => typeof x === 'string')
    .map((x) => x.trim())
    // 只要 1~4 个汉字的词：标点、单字、句子都不要
    // （纠错模式例外——那里返回的是整句，本来就该带标点）
    .filter((x) => (allowPunct ? x.length >= 1 && x.length <= 200 : /^[一-鿿]{1,4}$/.test(x)))
    .filter((x, i, a) => a.indexOf(x) === i)
    .slice(0, 5);
}

/**
 * 按请求内容决定模式，并给出对应的提示词。
 *
 * 消歧模式有个额外约束：模型挑出来的词必须**在候选池里**，
 * 不在的一律丢掉——用户按不出来的东西不能进候选栏。
 */
function pickMode(req) {
  if (req.input) return 'correct';
  if (req.candidates.length) return 'disambiguate';
  if (req.pinyin) return 'complete';
  return 'next';
}

// ---------------------------------------------------------------- 短时缓存
//
// 同一个问题短时间内被重复问（客户端重发、用户来回退格又敲同样的拼音），
// 不该再花一次模型调用——模型又慢又贵。
const CACHE_TTL_MS = Number(process.env.LEXEME_CACHE_TTL_MS || 60_000);
const cache = new Map();

function cacheKey(req) {
  return `${req.text}\u0000${req.pinyin}\u0000${req.input}\u0000${req.model}\u0000${req.candidates.join('\u0001')}`;
}

function cacheGet(key) {
  const hit = cache.get(key);
  if (!hit) return null;
  if (Date.now() - hit.at > CACHE_TTL_MS) {
    cache.delete(key);
    return null;
  }
  return hit.value;
}

function cachePut(key, value) {
  cache.set(key, { at: Date.now(), value });
  if (cache.size > 200) {
    // 简单粗暴地扔掉最老的一半
    for (const k of [...cache.keys()].slice(0, 100)) cache.delete(k);
  }
}

async function predict(req) {
  const mode = pickMode(req);
  if (BACKEND === 'mock') {
    if (mode === 'disambiguate') return mockDisambiguate(req.text, req.candidates);
    if (mode === 'correct') return mockCorrect(req.input);
    if (mode === 'complete') return mockPredict(req.pinyin);
    return mockPredict(req.text);
  }
  const system =
    mode === 'disambiguate' ? SYSTEM_DISAMBIGUATE
    : mode === 'complete' ? SYSTEM_COMPLETE
    : mode === 'correct' ? SYSTEM_CORRECT
    : SYSTEM_NEXT;
  let next;
  if (BACKEND === 'openai') next = await openaiPredict(req, system);
  else if (BACKEND === 'stepfun') next = await stepfunPredict(req, system);
  else next = await minimaxPredict(req, system);
  if (mode === 'disambiguate') {
    // 模型偶尔会发明新词——那不是用户能按出来的，丢掉
    const pool = new Set(req.candidates);
    next = next.filter((x) => pool.has(x));
  }
  if (mode === 'correct') {
    // 纠错只认一个结果：整句改正版
    next = next.slice(0, 1);
  }
  return next;
}

// ---------------------------------------------------------------- 服务

const server = createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: true, backend: BACKEND }));
    return;
  }
  if (req.method !== 'POST' || !req.url.startsWith('/lexeme')) {
    res.writeHead(404).end('not found');
    return;
  }
  let body = '';
  req.on('data', (c) => {
    body += c;
    if (body.length > 1e6) req.destroy();
  });
  req.on('end', async () => {
    const started = Date.now();
    try {
      const payload = JSON.parse(body || '{}');
      const request = {
        text: String(payload.text || '').slice(0, 200),
        pinyin: String(payload.pinyin || '').slice(0, 60),
        // 待纠错的整段文字（纠错模式）
        input: String(payload.input || '').slice(0, 200),
        candidates: Array.isArray(payload.candidates)
          ? payload.candidates.filter((x) => typeof x === 'string').slice(0, 60)
          : [],
        // 用户在设置里指定的模型名；空 = 用服务端自己配的那个
        model: String(payload.model || '').slice(0, 80),
        temperature: Number.isFinite(payload.temperature) ? payload.temperature : undefined,
      };
      if (!request.text && !request.pinyin && !request.candidates.length && !request.input) {
        res.writeHead(400, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ error: 'text / pinyin / input / candidates 至少要有一个' }));
        return;
      }
      const mode = pickMode(request);
      const key = cacheKey(request);
      const cached = cacheGet(key);
      if (cached) {
        console.log(
          `[${new Date().toISOString()}] ${mode} ctx="${request.text}" pin="${request.pinyin}" ` +
            `候选=${request.candidates.length} -> ${JSON.stringify(cached)} (命中缓存, ${BACKEND})`,
        );
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ next: cached }));
        return;
      }
      const next = await predict(request);
      cachePut(key, next);
      console.log(
        `[${new Date().toISOString()}] ${mode} ctx="${request.text}" pin="${request.pinyin}" ` +
          `in="${request.input}" 候选=${request.candidates.length} -> ${JSON.stringify(next)} ` +
          `(${Date.now() - started} ms, ${BACKEND}${request.model ? ', model=' + request.model : ''})`,
      );
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ next }));
    } catch (e) {
      console.error('联想失败：', e.message);
      // 上游被限流（429）就把 429 传下去：
      // 客户端看到 429 知道「等一会儿再来」，看到 502 知道「服务真出问题了」。
      const code = e && e.upstreamStatus === 429 ? 429 : 502;
      res.writeHead(code, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: String(e.message || e) }));
    }
  });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`联想服务已启动：http://0.0.0.0:${PORT}  后端=${BACKEND}`);
  console.log(`  健康检查 GET /health`);
  console.log(`  联想接口 POST /lexeme  {"text":"智慧","pinyin":"zhihui","candidates":["智慧","指挥"]}`);
});
