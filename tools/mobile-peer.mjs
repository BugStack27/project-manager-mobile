#!/usr/bin/env node
/**
 * 本地「桌面端」测试桩：按手机端协议订阅 ntfy、推送审核 / 提问、收回答案。
 * 仅用于联调，不参与 App 构建。
 *
 *   node tools/mobile-peer.mjs --name 工作电脑
 *   node tools/mobile-peer.mjs --topic pm-xxxx... --key xxxx... --name 家里电脑
 */
import crypto from 'node:crypto';

const ALPHABET = 'abcdefghijkmnpqrstuvwxyz23456789';
const argv = process.argv.slice(2);

function arg(name, fallback) {
  const i = argv.indexOf('--' + name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
}

const server = (arg('server', 'https://ntfy.sh')).replace(/\/+$/, '');
const topic = arg('topic', 'pm-' + rand(16));
const key = arg('key', rand(32));
const name = arg('name', '测试桌面端');
const intervalSec = Number(arg('interval', '15'));
const iterations = 100000;

function rand(len) {
  let out = '';
  for (let i = 0; i < len; i += 1) out += ALPHABET[crypto.randomInt(0, ALPHABET.length)];
  return out;
}

function derived() {
  return crypto.pbkdf2Sync(key, 'pm-mobile-v1:' + topic, iterations, 32, 'sha256');
}

function seal(message) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', derived(), iv);
  cipher.setAAD(Buffer.from(topic, 'utf8'));
  const body = Buffer.concat([cipher.update(JSON.stringify(message), 'utf8'), cipher.final()]);
  return JSON.stringify({
    v: 1,
    n: iv.toString('base64'),
    d: Buffer.concat([body, cipher.getAuthTag()]).toString('base64')
  });
}

function open(text) {
  const env = JSON.parse(text);
  const iv = Buffer.from(env.n, 'base64');
  const raw = Buffer.from(env.d, 'base64');
  const decipher = crypto.createDecipheriv('aes-256-gcm', derived(), iv);
  decipher.setAAD(Buffer.from(topic, 'utf8'));
  decipher.setAuthTag(raw.subarray(raw.length - 16));
  const plain = Buffer.concat([
    decipher.update(raw.subarray(0, raw.length - 16)),
    decipher.final()
  ]).toString('utf8');
  return JSON.parse(plain);
}

async function publish(message) {
  const body = seal(message);
  if (Buffer.byteLength(body) > 4000) throw new Error('消息超过 4KB');
  const res = await fetch(server + '/' + topic, {
    method: 'POST',
    headers: {
      'Content-Type': 'text/plain; charset=utf-8',
      'X-Ttl': '600',
      'X-Priority': '3'
    },
    body
  });
  if (!res.ok) throw new Error('发布失败 HTTP ' + res.status + ' ' + (await res.text()).slice(0, 120));
}

const connect = JSON.stringify({ v: 1, type: 'pm-mobile', server, topic, key });
console.log('CONNECT_JSON=' + connect);
console.log(`[${name}] topic=${topic} key=${key}`);
console.log(`[${name}] 在手机端「手动输入连接信息」粘贴上面的 JSON\n`);

const now = Date.now();
const completedAt = new Date(now - 60_000).toISOString();
const stamp = now.toString(36);
const rid = 'r_peer' + stamp;
const qid = 'q_peer' + stamp;
const review = {
  v: 1,
  id: 'review:' + rid + ':' + completedAt,
  type: 'review',
  from: 'server',
  at: completedAt,
  payload: {
    requirementId: rid,
    seq: 88,
    title: '后台保活验证 · ' + name,
    projectId: 'p_peer',
    projectName: '测试项目 · ' + name,
    agent: 'a_peer',
    agentName: 'PeerBot',
    note: '已完成通知点击、后台接收与答案回传验证；这是测试桩发出的交付说明。',
    completedAt
  }
};

const questionAt = new Date().toISOString();
const question = {
  v: 1,
  id: 'question:' + qid,
  type: 'question',
  from: 'server',
  at: questionAt,
  payload: {
    requirementId: rid,
    seq: 88,
    title: '后台保活验证 · ' + name,
    projectId: 'p_peer',
    projectName: '测试项目 · ' + name,
    questionId: qid,
    text: '后台接收与通知需要验证到什么程度？',
    options: ['能做到退到后台继续收通知', '只要打开 App 能收到即可', '通知点击能直达详情'],
    default: '能做到退到后台继续收通知',
    expiresAt: new Date(now + 60 * 60 * 1000).toISOString()
  }
};

const done = new Set();

async function pushAll() {
  if (!done.has('review')) {
    await publish(review).catch((e) => console.error('[review]', e.message));
  }
  if (!done.has('question')) {
    await publish(question).catch((e) => console.error('[question]', e.message));
  }
  await publish({
    v: 1,
    id: 'keepalive:server:' + Date.now(),
    type: 'keepalive',
    from: 'server',
    at: new Date().toISOString(),
    payload: { online: true, pending: done.size >= 2 ? 0 : 2 - done.size }
  }).catch(() => {});
}

async function loop() {
  const res = await fetch(server + '/' + topic + '/json?since=10m', {
    headers: { Accept: 'application/json' }
  });
  if (!res.ok || !res.body) throw new Error('订阅失败 HTTP ' + res.status);
  console.log(`[${name}] 已订阅，等待手机端答案…`);
  const decoder = new TextDecoder();
  let buffer = '';
  for await (const chunk of res.body) {
    buffer += decoder.decode(chunk, { stream: true });
    let idx;
    while ((idx = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, idx).trim();
      buffer = buffer.slice(idx + 1);
      if (!line) continue;
      let event;
      try {
        event = JSON.parse(line);
      } catch {
        continue;
      }
      if (event.event !== 'message' || typeof event.message !== 'string') continue;
      if (!event.message.trim().startsWith('{')) continue;
      let message;
      try {
        message = open(event.message);
      } catch {
        continue;
      }
      if (message.from !== 'client' || message.type !== 'answer') continue;
      const p = message.payload || {};
      // 重连会回放最近 10 分钟的旧答案：只认自己这轮需求的答案
      if (p.requirementId !== rid) continue;
      console.log(`\n[${name}] 收到答案 ${message.id}`);
      console.log(`  decision=${p.decision} requirement=${p.requirementId} question=${p.questionId || '-'}`);
      if (p.text) console.log(`  text=${p.text}`);
      if (p.decision === 'answer') done.add('question');
      else done.add('review');
      console.log(`  剩余待推送: ${2 - done.size}`);
    }
  }
}

pushAll();
setInterval(pushAll, Math.max(5, intervalSec) * 1000);
loop().catch((e) => {
  console.error('订阅失败:', e.message);
  process.exit(1);
});
