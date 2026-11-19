#!/usr/bin/env node
/**
 * 假的语音识别服务——**只给测试用**。
 *
 * 用来在真机上验证「按住空格说话 → 录音 → 上传 → 解析 → 上屏」整条链路，
 * 不需要真的 ASR 账号。
 *
 * 它做的事：
 * - 收 multipart（OpenAI 风格）或 JSON（base64）两种上传
 * - 记下音频大小和时长（诊断用）
 * - 返回一段固定的中文（可以用环境变量改）
 *
 * 跑起来：
 * ```
 *   node tools/mock_asr.mjs --port 8790
 *   # 输入法设置里填：http://192.168.x.x:8790/v1/audio/transcriptions
 * ```
 *
 * 真要用真服务，把设置里的地址换成厂商的接口就行，协议是一样的。
 */

import { createServer } from 'node:http';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));

const args = process.argv.slice(2);
const portArg = args.indexOf('--port');
const PORT = portArg >= 0 ? Number(args[portArg + 1]) : 8790;

/** 返回的假识别结果。 */
const TEXT = process.env.MOCK_ASR_TEXT || '今天天气不错';

const server = createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: true, backend: 'mock-asr' }));
    return;
  }

  let size = 0;
  const chunks = [];
  req.on('data', (c) => {
    size += c.length;
    chunks.push(c);
    if (size > 50e6) req.destroy();
  });
  req.on('end', () => {
    const body = Buffer.concat(chunks);
    // wav 的第 44 字节往后是 PCM，16 kHz 单声道 16 bit → 字节数 / 2 / 16000 = 秒
    const seconds = Math.round((Math.max(0, body.length - 44) / 2 / 16000) * 10) / 10;
    console.log(
      `[${new Date().toISOString()}] 收到音频 ${body.length} 字节（约 ${seconds} 秒）` +
        ` content-type=${req.headers['content-type']}`,
    );
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ text: TEXT }));
  });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`假 ASR 服务已启动：http://0.0.0.0:${PORT}`);
  console.log(`  返回文本：${TEXT}`);
  console.log(`  接口：POST /v1/audio/transcriptions（multipart）或任意路径（JSON）`);
});
