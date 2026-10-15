/**
 * AuthProbeJs 行为回归（node，手写最小 fetch/localStorage 桩）。
 *
 * 为什么必须真跑：这段脚本的失败模式是「登录明明成功却永远探测不到」——
 * 只有运行时才暴露，字符串包含断言抓不到。
 *
 * 覆盖：
 *   A. New API 站（/api/user/self 200）→ 不回退，不碰 sub2api
 *   B. fengwind 捐赠站（/api/user/self 404 + donation_token）→ 回退 /api/me
 *   C. 主站型（/api/me 404 → /api/v1/auth/me + auth_token）
 *   D. 无 token → 不发起无谓请求，如实回传 404
 *   E. 安全：token 明文绝不进回传/日志字段
 *
 * 用法：node tools/test_authprobe.mjs [AuthProbeJs.js]
 *   （先用 tools/extract_js.py 把 Java 模板抽成 .js）
 */
import fs from 'node:fs';
import process from 'node:process';

const TPL_PATH = process.argv[2] || '/tmp/jsverify/AuthProbeJs.js';
const TPL = fs.readFileSync(TPL_PATH, 'utf8');

const ORIGIN = 'https://api.fengwind.com';

function render() {
  return TPL
    .replaceAll('__ORIGIN__', ORIGIN)
    .replaceAll('__UID__', '12345')
    .replaceAll('__READY__', 'true');
}

/** 构造运行环境：fetch 桩按 url 决定状态码/响应体。 */
function makeEnv({ ls = {}, routes }) {
  const calls = [];
  const results = [];
  const g = {
    location: { origin: ORIGIN },
    localStorage: { getItem: k => (k in ls ? ls[k] : null) },
    AbortController: class { constructor() { this.signal = {}; } abort() {} },
    setTimeout: () => 0,
    clearTimeout: () => {},
    JustSign: {
      onSelfResult: (s, b) => results.push({ s, b }),
      onSelfFailed: e => results.push({ failed: e }),
    },
    fetch: (url, opt) => {
      calls.push({ url, auth: (opt && opt.headers && opt.headers.Authorization) || null });
      const r = routes[url] || { status: 404, body: 'not found' };
      return Promise.resolve({
        status: r.status,
        text: () => Promise.resolve(r.body == null ? '' : r.body),
      });
    },
  };
  g.window = g;
  return { g, calls, results };
}

function run(env) {
  const fn = new Function(
    'window', 'location', 'localStorage', 'AbortController', 'setTimeout',
    'clearTimeout', 'JustSign', 'fetch', render());
  return fn(env.g.window, env.g.location, env.g.localStorage, env.g.AbortController,
    env.g.setTimeout, env.g.clearTimeout, env.g.JustSign, env.g.fetch);
}

let pass = 0, fail = 0;
const ok = (c, m) => { c ? (pass++, console.log('  PASS', m)) : (fail++, console.log('  FAIL', m)); };
const settle = () => new Promise(r => setTimeout(r, 30));

console.log('\nA. New API 站（/api/user/self 200）→ 不回退');
{
  const e = makeEnv({
    ls: {},
    routes: { '/api/user/self': { status: 200, body: JSON.stringify({ success: true, data: { id: 12345, quota: 100 } }) } },
  });
  await run(e); await settle();
  ok(e.results.length === 1 && e.results[0].s === 200, '回传 200');
  ok(e.calls.length === 1, '只发 1 次请求（未回退），实际=' + e.calls.length);
}

console.log('\nB. fengwind 捐赠站（404 + donation_token）→ 回退 /api/me');
{
  const e = makeEnv({
    ls: { donation_token: 'TKN-abc' },
    routes: {
      '/api/user/self': { status: 404, body: '404 page not found' },
      '/api/me': { status: 200, body: JSON.stringify({ code: 0, data: { sub2api_user_id: 777, display_name: 'zgj' } }) },
    },
  });
  await run(e); await settle();
  ok(e.calls.length === 2, '先探 New API 再回退（2 次），实际=' + e.calls.length);
  ok(e.calls[1].url === '/api/me', '回退到 /api/me，实际=' + e.calls[1].url);
  ok(e.calls[1].auth === 'Bearer TKN-abc', '带上 Bearer token');
  const b = JSON.parse((e.results[0] || {}).b || '{}');
  ok(b.success === true && b.data && b.data.id === 777, '归一化为 success/data.id，实际=' + JSON.stringify(b));
}

console.log('\nC. 主站型（/api/me 404 → /api/v1/auth/me + auth_token）');
{
  const e = makeEnv({
    ls: { auth_token: 'TKN-xyz' },
    routes: {
      '/api/user/self': { status: 404, body: '404' },
      '/api/me': { status: 404, body: 'not found' },
      '/api/v1/auth/me': { status: 200, body: JSON.stringify({ code: 0, data: { id: 888, display_name: 'u2' } }) },
    },
  });
  await run(e); await settle();
  ok(e.calls.length === 3, '三次尝试（New API → /api/me → /api/v1/auth/me），实际=' + e.calls.length);
  const b = JSON.parse((e.results[0] || {}).b || '{}');
  ok(b.data && b.data.id === 888, '取到第二个候选身份，实际=' + JSON.stringify(b));
}

console.log('\nD. 无 token → 不回退，如实回传 404');
{
  const e = makeEnv({ ls: {}, routes: { '/api/user/self': { status: 404, body: '404' } } });
  await run(e); await settle();
  ok(e.calls.length === 1, '无 token 不发无谓请求，实际=' + e.calls.length);
  ok(e.results[0] && e.results[0].s === 404, '回传 404');
}

console.log('\nE. 安全：token 明文不进日志字段');
{
  const e = makeEnv({
    ls: { donation_token: 'SECRET-VALUE' },
    routes: { '/api/user/self': { status: 404, body: '404' } },
  });
  await run(e); await settle();
  ok(!JSON.stringify(e.results).includes('SECRET-VALUE'), '回传内容不含 token 明文');
}

console.log(`\n结果: ${pass} 通过 / ${fail} 失败`);
process.exit(fail ? 1 : 0);
