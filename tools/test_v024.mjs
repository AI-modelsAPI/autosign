/* v0.2.4 新逻辑等价复刻测试：
 *   1) AuthActivity 取 oauth state 的 POST → GET 回退（AgentRouter 型站 POST 返回 404）
 *   2) Store.migrateLocked v3：内置站 checkinType 跟随 Catalog 校正
 *   3) AuthActivity.maybeFill 的密码缺失判定与 autoSubmit 抑制
 * 与 Java 实现保持同一判定顺序；只验证控制流，不联网。
 */
let pass = 0, fail = 0;
const ok = (c, m) => { if (c) { pass++; console.log('  PASS ' + m); } else { fail++; console.log('  FAIL ' + m); } };

/* ---------- 1. state POST→GET 回退 ---------- */
// server: 'post-only' 传统 New API 站；'get-only' AgentRouter 型
function fetchState(server, metaMethod) {
  const calls = [];
  let stateUseGet = metaMethod === 'get';
  let state = null, err = null;
  for (let attempt = 0; attempt < 3 && state === null; attempt++) {
    const useGet = stateUseGet || attempt > 0;
    calls.push(useGet ? 'GET' : 'POST');
    let code, body;
    if (server === 'get-only') {
      if (useGet) { code = 200; body = '{"data":"tok_get","success":true}'; }
      else { code = 404; body = '{"error":{"message":"Invalid URL (POST /api/oauth/state)"}}'; }
    } else {
      if (useGet) { code = 404; body = '{"error":{"message":"not found"}}'; }
      else { code = 200; body = '{"data":{"flow_token":"tok_post"},"success":true}'; }
    }
    if (code === 429) continue;
    if (code === 404 && !useGet) { stateUseGet = true; err = 'POST 不支持，改用 GET'; continue; }
    if (!body.trim()) { continue; }
    let j;
    try { j = JSON.parse(body); }
    catch (e) { if (!useGet) { stateUseGet = true; continue; } break; }
    if (code === 200 && j.success) {
      const d = j.data;
      state = typeof d === 'string' ? d : (d && d.flow_token) || null;
    } else if (code === 404 && useGet) { err = 'GET 也 404'; break; }
  }
  return { state, calls, stateUseGet, err };
}

console.log('[1] oauth state 方法回退');
let r = fetchState('post-only', '');
ok(r.state === 'tok_post', '传统站：POST 一次拿到 (' + r.calls.join(',') + ')');
ok(r.calls.length === 1, '传统站不做多余请求');

r = fetchState('get-only', '');
ok(r.state === 'tok_get', 'AgentRouter：POST 404 后回退 GET 成功 (' + r.calls.join(',') + ')');
ok(r.calls[0] === 'POST' && r.calls[1] === 'GET', '顺序正确：先 POST 再 GET');
ok(r.stateUseGet === true, '探测结果记为 useGet（会写入站点 meta）');

r = fetchState('get-only', 'get');
ok(r.state === 'tok_get' && r.calls.length === 1 && r.calls[0] === 'GET',
   '已记 meta=get：直接 GET，跳过注定 404 的 POST');

/* ---------- 2. checkinType 迁移 ---------- */
console.log('\n[2] 内置站 checkinType 校正（migrated_v3_kind）');
const CATALOG = {
  'agentrouter-org': 'login',
  'api-justwoker-icu': 'newapi',
  'kktoken-cc': 'newapi',
  'tabitoken-com': 'web',
};
function migrateKind(sites, alreadyDone) {
  if (alreadyDone) return { sites, changed: 0 };
  let changed = 0;
  for (const s of sites) {
    const want = CATALOG[s.key];
    if (want === undefined) continue;            // 自定义站不动
    if (want && want !== s.checkinType) { s.checkinType = want; changed++; }
  }
  return { sites, changed };
}
let sites = [
  { key: 'agentrouter-org', checkinType: 'manual', accounts: [{ key: 'a1', token: 't' }] },
  { key: 'api-justwoker-icu', checkinType: 'manual', accounts: [{ key: 'a2', token: 't' }] },
  { key: 'my-private-site', checkinType: 'manual', accounts: [] },
];
const m = migrateKind(JSON.parse(JSON.stringify(sites)), false);
const ar = m.sites.find(s => s.key === 'agentrouter-org');
ok(ar.checkinType === 'login', 'AgentRouter: manual → login（不再误走 POST /checkin）');
ok(m.sites.find(s => s.key === 'api-justwoker-icu').checkinType === 'newapi',
   'justworker: manual → newapi（语义等价，显式化）');
ok(m.sites.find(s => s.key === 'my-private-site').checkinType === 'manual',
   '自定义站不被改动');
ok(ar.accounts.length === 1 && ar.accounts[0].token === 't', '账号与 token 完整保留');
const m2 = migrateKind(JSON.parse(JSON.stringify(sites)), true);
ok(m2.changed === 0, '已迁移过则不重复执行');

/* siteKind 判定 */
const siteKind = t => (t === 'manual' || t === 'newapi') ? 'newapi' : (t === 'web' ? 'web' : 'login');
console.log('\n[3] siteKind 判定与签到分支');
ok(siteKind('login') === 'login', 'login → 登录即得分支（不 POST /api/user/checkin）');
ok(siteKind('manual') === 'newapi', '旧 manual 仍映射 newapi（兼容未迁移数据）');
ok(siteKind('web') === 'web', 'web → 只开网页');

/* ---------- 4. 密码缺失判定 ---------- */
console.log('\n[4] 授权页填充：密码缺失');
function maybeFill(credAccount, credPassword, hasOtp, autoSubmitPref) {
  if (!credAccount && !credPassword) return { skipped: true };
  const noPwd = !credPassword;
  const filled = [];
  if (credAccount) filled.push('account');
  if (credPassword) filled.push('password');
  if (hasOtp) filled.push('twofa');
  return {
    filled,
    autoSubmit: autoSubmitPref && !noPwd,
    tip: noPwd ? '仅填入账号，未存密码' : '已填入账号密码',
    loggedErr: noPwd,
  };
}
let f = maybeFill('AI-modelsAPI', '', false, true);
ok(f.filled.join() === 'account', '无密码：只填账号');
ok(f.autoSubmit === false, '无密码时不自动提交（否则提交空密码必失败）');
ok(/未存密码/.test(f.tip), '提示如实说明未存密码（不再谎报已填）');
ok(f.loggedErr === true, '写入 err 级操作日志，日志里可查');

f = maybeFill('AI-modelsAPI', 'pw', false, true);
ok(f.filled.join() === 'account,password' && f.autoSubmit === true, '有密码：正常填充并提交');
f = maybeFill('AI-modelsAPI', 'pw', true, true);
ok(f.filled.includes('twofa'), '有 2FA：一并填入');
ok(maybeFill('', '', false, true).skipped === true, '无任何凭据：整体跳过');

/* ---------- 5. GitHub 账号优先 ---------- */
console.log('\n[5] 登录名取值（GitHub 页面必须用 githubUser）');
const pickAcc = c => c.githubUser || c.siteAccount || '';
ok(pickAcc({ githubUser: 'gh-user', siteAccount: 'nickname' }) === 'gh-user',
   '两者不同名时取 githubUser');
ok(pickAcc({ githubUser: '', siteAccount: 'only-site' }) === 'only-site',
   '无 githubUser 时回退 siteAccount');

/* ---------- 6. 忙碌条引用计数 ---------- */
console.log('\n[6] 忙碌条 busyDepth 引用计数');
let depth = 0, visible = false, text = '';
const begin = w => { depth++; text = w; visible = true; };
const update = w => { if (depth > 0) text = w; };
const end = () => { if (depth > 0) depth--; if (depth === 0) visible = false; };
begin('签到中'); ok(visible === true, '开始任务 → 显示');
begin('刷新中'); end();
ok(visible === true, '嵌套任务：内层结束仍保持显示');
end(); ok(visible === false && depth === 0, '全部结束 → 隐藏');
end(); ok(depth === 0, '多余的 end 不会让计数变负');
begin('a'); update('一键签到 2/4：kktoken');
ok(text === '一键签到 2/4：kktoken', '进度文案可更新');
end();

console.log('\n结果: ' + pass + ' 通过 / ' + fail + ' 失败');
process.exit(fail ? 1 : 0);