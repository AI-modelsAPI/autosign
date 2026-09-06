/**
 * AuthFillJs 行为自测（node，无 jsdom —— 手写最小 DOM 桩）。
 *
 * 覆盖关键路径：
 *   1) 普通登录页：填账号密码 → 自动点 Sign in
 *   2) 页面有未通过的 Turnstile：只填不点（action=waitCaptcha）
 *   3) Turnstile 已产出 token：允许提交
 *   4) GitHub 2FA 页默认是 Mobile 推送：点「Use authenticator app」切出输入框
 *   5) 没配 2FA 的账号：完全不碰 2FA 元素
 *
 * 用法：node tools/test_authfill.mjs <抽出的 AuthFillJs.js>
 *   （先用 tools/extract_js.py 把 Java 模板抽成 .js）
 */
import fs from 'node:fs';
import process from 'node:process';

const tplPath = process.argv[2] || '/tmp/jsverify/AuthFillJs.js';
const TPL = fs.readFileSync(tplPath, 'utf8');

function render(acc, pwd, otp, auto) {
  return TPL
    .replace('/*__ACC__*/null', JSON.stringify(acc))
    .replace('/*__PWD__*/null', JSON.stringify(pwd))
    .replace('/*__OTP__*/null', JSON.stringify(otp))
    .replace('/*__AUTO__*/false', auto ? 'true' : 'false');
}

/* ---------- 最小 DOM 桩 ---------- */
class El {
  constructor(tag, attrs = {}, text = '') {
    this.tagName = tag.toUpperCase();
    this.attrs = attrs;
    this.innerText = text;
    this.textContent = text;
    this.style = { outline: '', cssText: '' };
    this._value = attrs.value || '';
    this.disabled = !!attrs.disabled;
    this.readOnly = !!attrs.readOnly;
    this.clicked = 0;
    this.form = null;
    this.children = [];
  }
  get value() { return this._value; }
  set value(v) { this._value = v; }
  getAttribute(n) { return this.attrs[n] === undefined ? null : this.attrs[n]; }
  getBoundingClientRect() { return { width: this.attrs.hidden ? 0 : 100, height: this.attrs.hidden ? 0 : 20 }; }
  dispatchEvent() { return true; }
  click() { this.clicked++; if (this.onclick) this.onclick(); }
  scrollIntoView() {}
  focus() { this.focused = true; }
  querySelector(sel) { return matchIn(this.children, sel)[0] || null; }
  querySelectorAll(sel) { return matchIn(this.children, sel); }
  appendChild(c) { this.children.push(c); return c; }
}

/** 极简选择器匹配：够覆盖脚本里用到的写法 */
function matches(el, sel) {
  sel = sel.trim();
  const idm = sel.match(/^#([\w-]+)$/);
  if (idm) return el.attrs.id === idm[1];
  const clsm = sel.match(/^\.([\w-]+)$/);
  if (clsm) return (el.attrs.class || '').split(/\s+/).includes(clsm[1]);
  const attrOnly = sel.match(/^\[([\w-]+)(?:\s*([~*|^$]?=)\s*"([^"]*)")?\s*(i)?\]$/);
  if (attrOnly) return attrPred(el, attrOnly[1], attrOnly[2], attrOnly[3], !!attrOnly[4]);
  const m = sel.match(/^([\w-]+)((?:\[[^\]]*\])*)$/);
  if (m) {
    if (el.tagName !== m[1].toUpperCase()) return false;
    const conds = m[2].match(/\[[^\]]*\]/g) || [];
    return conds.every(c => {
      const cm = c.match(/^\[([\w-]+)(?:\s*([~*|^$]?=)\s*"([^"]*)")?\s*(i)?\]$/);
      return cm ? attrPred(el, cm[1], cm[2], cm[3], !!cm[4]) : false;
    });
  }
  return false;
}
function attrPred(el, name, op, want, ci) {
  let have = name === 'type' ? (el.attrs.type || (el.tagName === 'BUTTON' ? 'submit' : 'text')) : el.attrs[name];
  if (have === undefined || have === null) return false;
  if (!op) return true;
  if (ci) { have = String(have).toLowerCase(); want = String(want).toLowerCase(); }
  if (op === '=') return String(have) === want;
  if (op === '*=') return String(have).includes(want);
  return false;
}
function matchIn(list, sel) {
  const parts = sel.split(',').map(s => s.trim()).filter(Boolean);
  const out = [];
  for (const el of list) for (const p of parts) if (matches(el, p)) { out.push(el); break; }
  return out;
}

function makeEnv(els, href) {
  const all = els;
  const doc = {
    documentElement: { },
    head: { appendChild() {} },
    body: { appendChild() {} },
    querySelector: sel => matchIn(all, sel)[0] || null,
    querySelectorAll: sel => matchIn(all, sel),
    getElementById: id => all.find(e => e.attrs.id === id) || null,
    createElement: t => new El(t),
  };
  const results = [];
  const timers = [];
  const intervals = [];
  let ivId = 0;
  const g = {
    document: doc,
    location: { href },
    getComputedStyle: () => ({ visibility: 'visible' }),
    Event: function () {},
    MutationObserver: function (cb) { this.observe = () => {}; this.disconnect = () => {}; },
    setTimeout: (fn, ms) => { timers.push({ fn, ms }); return timers.length; },
    setInterval: (fn, ms) => { const id = ++ivId; intervals.push({ id, fn, ms, dead: false }); return id; },
    clearInterval: id => { const it = intervals.find(i => i.id === id); if (it) it.dead = true; },
    Object,
    JSON,
    String,
    Number,
    console,
    window: null,
    JustSign: { onFill: s => results.push(JSON.parse(s)) },
  };
  g.window = g;
  return { g, results, timers, intervals, doc };
}

function run(code, env) {
  const fn = new Function('window', 'document', 'location', 'getComputedStyle', 'Event',
    'MutationObserver', 'setTimeout', 'setInterval', 'clearInterval', code);
  fn(env.g, env.g.document, env.g.location, env.g.getComputedStyle, env.g.Event,
     env.g.MutationObserver, env.g.setTimeout, env.g.setInterval, env.g.clearInterval);
}

/** 驱动 stableClick 的 120ms 轮询；n 为最多推进多少个 tick */
function tick(env, n = 60) {
  for (let i = 0; i < n; i++) {
    /* 只跑 stableClick 的短周期定时器（120ms），主 tryFill 轮询（600ms）单独跑 */
    const live = env.intervals.filter(t => !t.dead);
    if (!live.length) return;
    live.forEach(t => { if (!t.dead) t.fn(); });
  }
}

let pass = 0, fail = 0;
function check(name, cond, extra) {
  if (cond) { pass++; console.log('  PASS ' + name); }
  else { fail++; console.log('  FAIL ' + name + (extra ? '  ' + JSON.stringify(extra) : '')); }
}

/* ---------- 用例 1：普通登录页，自动提交 ---------- */
console.log('用例1 普通登录页 → 填充并就绪即点');
{
  const acc = new El('input', { id: 'login_field', name: 'login' });
  const pwd = new El('input', { id: 'password', type: 'password' });
  const btn = new El('button', { type: 'submit' }, 'Sign in');
  const els = [acc, pwd, btn];
  const env = makeEnv(els, 'https://github.com/login');
  run(render('AI-modelsAPI', 'pw123', '', true), env);
  tick(env);
  check('账号已填', acc.value === 'AI-modelsAPI', acc.value);
  check('密码已填', pwd.value === 'pw123', pwd.value);
  check('已点登录按钮（仅一次）', btn.clicked === 1, btn.clicked);
  const r = env.results.find(x => x.action === 'submitLogin');
  check('回传 submitLogin', !!r, env.results);
  check('等待时间未固定 700ms', !!r && r.waitedMs <= 480, r && r.waitedMs);
}

/* ---------- 用例 2：有未通过的人机验证 → 只填不点 ---------- */
console.log('用例2 存在未通过 Turnstile → 只填不点');
{
  const acc = new El('input', { id: 'login_field' });
  const pwd = new El('input', { id: 'password', type: 'password' });
  const cap = new El('div', { class: 'cf-turnstile', 'data-sitekey': '0x4A' });
  const resp = new El('input', { name: 'cf-turnstile-response', value: '' });
  const btn = new El('button', { type: 'submit' }, '登录');
  const env = makeEnv([acc, pwd, cap, resp, btn], 'https://api.justwoker.icu/login');
  run(render('u', 'p', '', true), env);
  tick(env, 60);
  check('字段已填', acc.value === 'u' && pwd.value === 'p');
  check('未点击登录', btn.clicked === 0, btn.clicked);
  check('回传 submitAborted(captcha)',
    env.results.some(r => r.action === 'submitAborted' && r.reason === 'captcha'), env.results);
}

/* ---------- 用例 3：Turnstile 已产出 token → 允许提交 ---------- */
console.log('用例3 Turnstile 已通过 → 允许自动提交');
{
  const acc = new El('input', { id: 'login_field' });
  const pwd = new El('input', { id: 'password', type: 'password' });
  const cap = new El('div', { class: 'cf-turnstile' });
  const resp = new El('input', { name: 'cf-turnstile-response', value: 'x'.repeat(30) });
  const btn = new El('button', { type: 'submit' }, '登录');
  const env = makeEnv([acc, pwd, cap, resp, btn], 'https://api.justwoker.icu/login');
  run(render('u', 'p', '', true), env);
  tick(env);
  check('已点击登录', btn.clicked === 1, btn.clicked);
}

/* ---------- 用例 3b：按钮先 disabled，稍后放开 → 等到可点才点 ---------- */
console.log('用例3b 按钮先 disabled → 等可点了再点');
{
  const acc = new El('input', { id: 'login_field' });
  const pwd = new El('input', { id: 'password', type: 'password' });
  const btn = new El('button', { type: 'submit' }, '登录');
  btn.disabled = true;
  const env = makeEnv([acc, pwd, btn], 'https://api.justwoker.icu/login');
  run(render('u', 'p', '', true), env);
  tick(env, 5);
  check('disabled 期间没点', btn.clicked === 0, btn.clicked);
  btn.disabled = false;
  tick(env, 10);
  check('放开后自动点了', btn.clicked === 1, btn.clicked);
}

/* ---------- 用例 4：GitHub 2FA 默认走 Mobile 推送 → 切换到验证器 ---------- */
console.log('用例4 2FA 页默认 Mobile 推送 → 切到验证器 App 并填码提交');
{
  const otpBox = new El('input', { id: 'app_totp', hidden: true, autocomplete: 'one-time-code' });
  const link = new El('a', { href: '#' }, 'Use authenticator app instead');
  const btn = new El('button', { type: 'submit' }, 'Verify');
  /* 点链接后输入框才可见 */
  link.onclick = () => { otpBox.attrs.hidden = false; };
  const env = makeEnv([link, otpBox, btn], 'https://github.com/sessions/two-factor');
  run(render('', '', '123456', true), env);
  tick(env);
  check('已点切换链接', link.clicked === 1, link.clicked);
  check('回传 switch2fa', env.results.some(r => r.action === 'switch2fa'), env.results);
}

/* ---------- 用例 4b：2FA 输入框直接可见 → 填码并自动提交 ---------- */
console.log('用例4b 2FA 输入框可见 → 填 6 位码并自动提交');
{
  const otpBox = new El('input', { id: 'app_totp', autocomplete: 'one-time-code' });
  const btn = new El('button', { type: 'submit' }, 'Verify');
  const env = makeEnv([otpBox, btn], 'https://github.com/sessions/two-factor');
  run(render('', '', '654321', true), env);
  tick(env);
  check('OTP 已填', otpBox.value === '654321', otpBox.value);
  check('已自动提交 2FA', btn.clicked === 1, btn.clicked);
  check('回传 submit2fa', env.results.some(r => r.action === 'submit2fa'), env.results);
}

/* ---------- 用例 5：没有 2FA 的账号 → 不碰任何 2FA 元素 ---------- */
console.log('用例5 无 2FA 凭据 → 不点切换链接');
{
  const otpBox = new El('input', { id: 'app_totp', hidden: true });
  const link = new El('a', {}, 'Use authenticator app');
  const env = makeEnv([link, otpBox], 'https://github.com/sessions/two-factor');
  run(render('', '', '', true), env);
  tick(env, 10);
  check('未点任何切换', link.clicked === 0, link.clicked);
  check('未填 OTP', otpBox.value === '', otpBox.value);
}

/* ---------- 用例 6：autoSubmit 关闭 → 只填不点 ---------- */
console.log('用例6 关闭自动提交 → 只填不点');
{
  const acc = new El('input', { id: 'login_field' });
  const pwd = new El('input', { id: 'password', type: 'password' });
  const btn = new El('button', { type: 'submit' }, 'Sign in');
  const env = makeEnv([acc, pwd, btn], 'https://github.com/login');
  run(render('u', 'p', '', false), env);
  tick(env, 20);
  check('字段已填', acc.value === 'u' && pwd.value === 'p');
  check('未点击（开关已关）', btn.clicked === 0, btn.clicked);
}

console.log(`\n结果: ${pass} 通过 / ${fail} 失败`);
process.exit(fail ? 1 : 0);