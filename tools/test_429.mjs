/* v0.2.7：429 换代理 + 站点冷却 + 账号级滑动回归 */
let pass = 0, fail = 0;
const ok = (c, m) => { if (c) { pass++; console.log('  PASS ' + m); } else { fail++; console.log('  FAIL ' + m); } };

/* ---------- 1. 429 → 换备用代理端口 ---------- */
console.log('[1] 429 自动换代理逻辑');
// 复刻 Engine.switchToBackupProxy 的判定（lastSwitch 初始为极小值，保证首次可切）
const PROXY_COOLDOWN = 60000;
let lastSwitch = -100000;
function switchToBackupProxy(curPort, alivePorts, now) {
  if (now - lastSwitch < PROXY_COOLDOWN) return null;
  lastSwitch = now;
  for (const p of alivePorts) if (p !== curPort) return { port: p };
  return null;
}
let r = switchToBackupProxy(10808, [10809, 7890], 1000);
ok(r && r.port === 10809, '多端口：切到第一个备用端口');
r = switchToBackupProxy(10809, [10809, 7890], 2000);
ok(r === null, '60s 冷却内不重复切（防横跳）');
r = switchToBackupProxy(10808, [10808], 70000);
ok(r === null, '无备用端口 → 不切（仅提示）');
/* 无备用也消耗了冷却时间（同真实实现：每次调用都刷新 lastSwitch） */
r = switchToBackupProxy(7890, [7891], 131000);
ok(r && r.port === 7891, '冷却结束后可再次切（131s 已远超 60s 冷却）');

/* ---------- 2. 429 后提示与额度 ---------- */
console.log('\n[2] 429 后额度获取失败的处理');
const httpHint = c => c === 429 ? '站点限流（429），当前代理节点被限，已尝试换节点' :
                 c === 401 ? '授权过期' : c >= 500 ? '服务异常' : 'HTTP ' + c;
ok(/429/.test(httpHint(429)), '429 提示带节点被限信息');
// 429 期间数据缺失 → 不覆盖旧 lastStatus
function mergeStatus(oldSt, newHttp, newData) {
  if (newHttp === 429) return oldSt || null;
  if (newHttp === 200) return Object.assign({}, newData, { ok: true });
  return null;
}
let old = { ok: true, availableUSD: 500.08, usedUSD: 428.92 };
ok(mergeStatus(old, 429, null) === old, '429：保留旧额度，不显示为 0');
let fresh = mergeStatus(old, 200, { availableUSD: 501, usedUSD: 429 });
ok(fresh && fresh.availableUSD === 501, '200：正常更新新额度');

/* ---------- 3. 站点 429 冷却 ---------- */
console.log('\n[3] 站点级 429 冷却');
const siteCoold = {};
function allowRequest(siteKey, now) {
  const until = siteCoold[siteKey] || 0;
  return now > until;
}
function mark429(siteKey, now) { siteCoold[siteKey] = now + 30000; }
ok(allowRequest('just', 1000) === true, '初始允许');
mark429('just', 1000);
ok(allowRequest('just', 15000) === false, '429 后 30s 内不连发');
ok(allowRequest('just', 31001) === true, '30s 冷却后恢复');

console.log('\n结果: ' + pass + ' 通过 / ' + fail + ' 失败');
process.exit(fail ? 1 : 0);