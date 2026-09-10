/**
 * SilentAuth 防风暴逻辑自测（node 模拟，不依赖 Android）。
 *
 * 为什么要测：v0.2.3 删掉续期后，凡是 401 都会触发完整 OAuth。
 * 一次 status() 刷新连打 4 个接口，如果每个 401 各跑一次 OAuth，
 * 就是 4 次 flow_token 申请 —— 站点直接 429，用户看到「刷新卡住然后全失败」。
 * 这里把 Java 侧三重保护（同账号串行 / 成功 8s 复用 / 失败 90s 冷却）
 * 与 Engine 的 tokenCache 用等价 JS 复刻，验证同一轮刷新只会换一次凭据。
 *
 * 用法：node tools/test_silentauth.mjs
 */
import process from 'node:process';

/* ---------- 复刻 SilentAuth 的守卫（与 Java 逻辑一一对应） ---------- */
const REUSE_MS = 8000;
const FAIL_COOLDOWN_MS = 90000;

class Clock {
  constructor() { this.t = 1_000_000; }
  now() { return this.t; }
  advance(ms) { this.t += ms; }
}

class SilentAuthSim {
  /**
   * @param clock 假时钟
   * @param impl  实际交换实现：返回 token 字符串，空串=失败
   */
  constructor(clock, impl) {
    this.clock = clock;
    this.impl = impl;
    this.lastOk = new Map();      // accountKey -> ts
    this.failUntil = new Map();   // accountKey -> ts
    this.store = new Map();       // accountKey -> token
    this.exchangeCalls = 0;       // 真正打到站点的次数
    this.chain = Promise.resolve(); // 同账号串行（等价 synchronized(lockOf)）
  }

  inCooldown(key) {
    const until = this.failUntil.get(key);
    return until !== undefined && this.clock.now() < until;
  }

  clearCooldown(key) { this.failUntil.delete(key); }

  /** 等价 exchangeSync：串行 + 复用 + 冷却 */
  exchangeSync(key) {
    const run = async () => {
      /* 刚成功过 → 直接复用库里的 token，不打站点 */
      const okAt = this.lastOk.get(key);
      if (okAt !== undefined && this.clock.now() - okAt < REUSE_MS) {
        return this.store.get(key) || '';
      }
      if (this.inCooldown(key)) return '';

      this.exchangeCalls++;
      const token = await this.impl(key);
      if (!token) {
        this.failUntil.set(key, this.clock.now() + FAIL_COOLDOWN_MS);
        return '';
      }
      this.store.set(key, token);
      this.lastOk.set(key, this.clock.now());
      this.clearCooldown(key);
      return token;
    };
    /* 串行化：后来的调用排在前一个之后 */
    const p = this.chain.then(run, run);
    this.chain = p.catch(() => {});
    return p;
  }

  /** 等价 needsExchange：解析 JWT exp，60s 提前量 */
  needsExchange(token) {
    if (!token) return true;
    const exp = expMs(token);
    if (exp <= 0) return false;
    return this.clock.now() + 60000 >= exp;
  }

  async ensureToken(key) {
    const cur = this.store.get(key) || '';
    if (!this.needsExchange(cur)) return cur;
    return await this.exchangeSync(key);
  }
}

/** 造一个 exp 在 now+minutes 的假 JWT */
function makeJwt(clock, minutes) {
  const payload = Buffer.from(JSON.stringify({
    exp: Math.floor((clock.now() + minutes * 60000) / 1000),
    sid: 'sid-test',
  })).toString('base64url');
  return `hdr.${payload}.sig`;
}

function expMs(jwt) {
  try {
    const parts = String(jwt).split('.');
    if (parts.length < 2) return 0;
    const o = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'));
    return o.exp > 0 ? o.exp * 1000 : 0;
  } catch { return 0; }
}

/* ---------- 复刻 Engine.callWithAuth + tokenCache ---------- */
class EngineSim {
  constructor(auth, server) {
    this.auth = auth;
    this.server = server;      // (token) => http code
    this.tokenCache = new Map();
    this.httpCalls = 0;
  }
  resetTokenCache() { this.tokenCache.clear(); }

  async callWithAuth(key, path) {
    let token = this.tokenCache.get(key) || '';
    if (!token) {
      token = await this.auth.ensureToken(key);
      if (token) this.tokenCache.set(key, token);
    }
    this.httpCalls++;
    let code = this.server(token, path);
    if (code !== 401) return code;

    const fresh = await this.auth.exchangeSync(key);
    if (fresh && fresh !== token) {
      this.tokenCache.set(key, fresh);
      this.httpCalls++;
      return this.server(fresh, path);
    }
    return code;
  }

  /** 等价 status()：一轮刷新连打 4 个需鉴权接口 */
  async status(key) {
    this.resetTokenCache();
    const paths = ['/api/user/self', '/api/user/checkin?month', '/api/data/self', '/api/log/self'];
    const codes = [];
    for (const p of paths) codes.push(await this.callWithAuth(key, p));
    return codes;
  }
}

let pass = 0, fail = 0;
function check(name, cond, extra) {
  if (cond) { pass++; console.log('  PASS ' + name); }
  else { fail++; console.log('  FAIL ' + name + (extra !== undefined ? '  ' + JSON.stringify(extra) : '')); }
}

/* ---------- 用例 1：token 过期，一轮刷新只换一次凭据 ---------- */
console.log('用例1 token 过期 → 一轮刷新（4 接口）只交换 1 次');
{
  const clock = new Clock();
  let issued = 0;
  const auth = new SilentAuthSim(clock, async () => makeJwt(clock, 15 + (++issued)));
  auth.store.set('acc1', makeJwt(clock, -5));           // 已过期 5 分钟
  const eng = new EngineSim(auth, token => (auth.needsExchange(token) ? 401 : 200));
  const codes = await eng.status('acc1');
  check('4 个接口全部 200', codes.every(c => c === 200), codes);
  check('只交换 1 次凭据', auth.exchangeCalls === 1, auth.exchangeCalls);
}

/* ---------- 用例 2：token 还新鲜，完全不触发交换 ---------- */
console.log('用例2 token 新鲜 → 零交换');
{
  const clock = new Clock();
  const auth = new SilentAuthSim(clock, async () => makeJwt(clock, 30));
  auth.store.set('acc1', makeJwt(clock, 14));           // 还有 14 分钟
  const eng = new EngineSim(auth, token => (auth.needsExchange(token) ? 401 : 200));
  const codes = await eng.status('acc1');
  check('全部 200', codes.every(c => c === 200), codes);
  check('零交换', auth.exchangeCalls === 0, auth.exchangeCalls);
}

/* ---------- 用例 3：多账号并发刷新，各自只换一次 ---------- */
console.log('用例3 3 个账号并发刷新 → 各换 1 次（共 3 次）');
{
  const clock = new Clock();
  let n = 0;
  const auth = new SilentAuthSim(clock, async () => makeJwt(clock, 20 + (++n)));
  ['a', 'b', 'c'].forEach(k => auth.store.set(k, makeJwt(clock, -1)));
  const mk = () => new EngineSim(auth, token => (auth.needsExchange(token) ? 401 : 200));
  const results = await Promise.all(['a', 'b', 'c'].map(k => mk().status(k)));
  check('全部账号全部 200', results.every(cs => cs.every(c => c === 200)), results);
  check('总交换 3 次', auth.exchangeCalls === 3, auth.exchangeCalls);
}

/* ---------- 用例 4：8s 内二次刷新复用，不重复交换 ---------- */
console.log('用例4 刚换过 8s 内再刷 → 复用不再交换');
{
  const clock = new Clock();
  let n = 0;
  /* 服务端固定 401（模拟 token 被提前作废），逼出交换路径 */
  const auth = new SilentAuthSim(clock, async () => 'tok-' + (++n));
  auth.store.set('acc1', 'stale');
  const eng = new EngineSim(auth, token => (token && token.startsWith('tok-') ? 200 : 401));
  await eng.status('acc1');
  const first = auth.exchangeCalls;
  clock.advance(3000);                                  // 3 秒后再刷
  await eng.status('acc1');
  check('首轮交换 1 次', first === 1, first);
  check('8s 内第二轮不再交换', auth.exchangeCalls === 1, auth.exchangeCalls);
}

/* ---------- 用例 5：交换失败进入 90s 冷却，不反复打站点 ---------- */
console.log('用例5 交换失败 → 90s 冷却期内不再自动交换');
{
  const clock = new Clock();
  const auth = new SilentAuthSim(clock, async () => '');   // 恒失败（GitHub 会话过期）
  auth.store.set('acc1', 'stale');
  const eng = new EngineSim(auth, () => 401);
  await eng.status('acc1');
  const firstCalls = auth.exchangeCalls;
  clock.advance(10000);
  await eng.status('acc1');
  const secondCalls = auth.exchangeCalls;
  check('首轮只尝试 1 次（不是 4 次）', firstCalls === 1, firstCalls);
  check('冷却期内零新增尝试', secondCalls === firstCalls, secondCalls);
  check('冷却状态可查询', auth.inCooldown('acc1') === true);
  clock.advance(FAIL_COOLDOWN_MS + 1000);
  check('冷却到期后解除', auth.inCooldown('acc1') === false);
}

/* ---------- 用例 6：手动清冷却后可立刻重试 ---------- */
console.log('用例6 用户手动点授权 → 清冷却立刻可重试');
{
  const clock = new Clock();
  let ok = false;
  const auth = new SilentAuthSim(clock, async () => (ok ? makeJwt(clock, 15) : ''));
  auth.store.set('acc1', makeJwt(clock, -5));           // 已过期 → 必然触发交换
  /* 服务端只认新签发的 token；过期票一律 401 */
  const eng = new EngineSim(auth, token => (!token || auth.needsExchange(token) ? 401 : 200));
  const codes = await eng.status('acc1');
  check('交换失败时接口返回 401', codes.every(c => c === 401), codes);
  check('失败后进入冷却', auth.inCooldown('acc1') === true);
  ok = true;
  auth.clearCooldown('acc1');
  const t = await auth.exchangeSync('acc1');
  check('清冷却后能立刻换到新 token', t.length > 10, t.slice(0, 12));
  check('冷却已解除', auth.inCooldown('acc1') === false);
}

/* ---------- 用例 7：exp 解析不出（非 JWT）→ 不主动换，交给 401 兜底 ---------- */
console.log('用例7 非 JWT token → 不主动换，401 才兜底');
{
  const clock = new Clock();
  let n = 0;
  const auth = new SilentAuthSim(clock, async () => 'tok-' + (++n));
  auth.store.set('acc1', 'opaque-token-not-jwt');
  check('needsExchange=false', auth.needsExchange('opaque-token-not-jwt') === false);
  /* 服务端认这个 opaque token → 不该有任何交换 */
  const eng = new EngineSim(auth, token => (token === 'opaque-token-not-jwt' ? 200 : 401));
  const codes = await eng.status('acc1');
  check('全部 200', codes.every(c => c === 200), codes);
  check('零交换', auth.exchangeCalls === 0, auth.exchangeCalls);
}

console.log(`\n结果: ${pass} 通过 / ${fail} 失败`);
process.exit(fail ? 1 : 0);