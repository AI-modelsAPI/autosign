/* v0.2.5：登录即签到型（just / kk）判定测试
 * 复刻 CheckinJs.todayBonusLog 与主流程的判定顺序，以及 Java 侧超时时序约束。
 */
let pass = 0, fail = 0;
const ok = (c, m) => { if (c) { pass++; console.log('  PASS ' + m); } else { fail++; console.log('  FAIL ' + m); } };

const UNIT = 500000;
const usd = q => { q = Number(q || 0); if (!isFinite(q) || q <= 0) return 0; return q >= 1000 ? Math.round(q / UNIT * 100) / 100 : q; };
const pad2 = n => n < 10 ? '0' + n : '' + n;
const dayStr = d => d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());

/* ---------- todayBonusLog 复刻 ---------- */
function todayBonusLog(logResponse) {
  const t = dayStr(new Date());
  const s0 = Math.floor(new Date(t + 'T00:00:00').getTime() / 1000);
  const e0 = s0 + 86400;
  const j = logResponse;
  if (!j || !j.success) return { found: false, reward: 0 };
  const d = j.data;
  let items = [];
  if (Array.isArray(d)) items = d;
  else if (d && Array.isArray(d.items)) items = d.items;
  else if (d && Array.isArray(d.data)) items = d.data;
  else if (d && Array.isArray(d.logs)) items = d.logs;
  for (const o of items) {
    const txt = String(o.content || o.remark || o.message || o.detail || '');
    if (!/签到|checkin|check-in|签到奖励/i.test(txt)) continue;
    let ts = Number(o.created_at || o.createdAt || o.timestamp || o.created_time || 0);
    if (ts > 0) { if (ts > 1e12) ts = Math.floor(ts / 1000); if (ts < s0 || ts >= e0) continue; }
    let q = o.quota;
    if (q === undefined || q === null) { const mm = txt.match(/([0-9]+(?:\.[0-9]+)?)/); q = mm ? Number(mm[1]) * UNIT : 0; }
    return { found: true, reward: usd(q) };
  }
  return { found: false, reward: 0 };
}

const nowSec = Math.floor(Date.now() / 1000);
const ySec = nowSec - 86400 * 2;

console.log('[1] todayBonusLog：识别今日签到日志');
let r = todayBonusLog({ success: true, data: { items: [{ content: '签到奖励 $20.64', quota: 10320000, created_at: nowSec }] } });
ok(r.found && r.reward === 20.64, '有奖励：识别并折算 $20.64');

r = todayBonusLog({ success: true, data: { items: [{ content: '每日签到成功', quota: 0, created_at: nowSec }] } });
ok(r.found && r.reward === 0, '奖励为 0 的站：仍判为已签，reward=0（kk 场景）');

r = todayBonusLog({ success: true, data: { items: [{ content: '签到奖励', quota: 10320000, created_at: ySec }] } });
ok(!r.found, '前天的签到记录不算今日');

r = todayBonusLog({ success: true, data: { items: [{ content: '模型调用 gpt-4', quota: 5000, created_at: nowSec }] } });
ok(!r.found, '非签到类日志不误判');

r = todayBonusLog({ success: true, data: { items: [{ content: '签到奖励', quota: 10320000, created_at: nowSec * 1000 }] } });
ok(r.found, '毫秒时间戳自动归一');

/* 多种包裹结构 */
ok(todayBonusLog({ success: true, data: [{ content: '签到', quota: 0, created_at: nowSec }] }).found, 'data 为数组');
ok(todayBonusLog({ success: true, data: { data: [{ content: '签到', quota: 0, created_at: nowSec }] } }).found, 'data.data 包裹');
ok(todayBonusLog({ success: true, data: { logs: [{ content: '签到', quota: 0, created_at: nowSec }] } }).found, 'data.logs 包裹');
ok(!todayBonusLog({ success: false }).found, 'success=false 不判定');
ok(!todayBonusLog(null).found, '响应为空不抛异常');
r = todayBonusLog({ success: true, data: { items: [{ content: '签到奖励 20.64 美元', created_at: nowSec }] } });
ok(r.found && r.reward === 20.64, '无 quota 字段时从文案提取金额');

/* ---------- 主流程判定顺序 ---------- */
console.log('\n[2] 签到主流程：登录即签到优先，不碰人机验证');
function flow({ stateChecked, stateOk = true, logHit, logReward = 0, tsToken, TS_ON = true }) {
  const steps = [];
  steps.push('stateOf');
  if (stateOk && stateChecked) return { steps, ok: true, already: true, via: 'checkin-stats' };
  steps.push('todayBonusLog');
  if (logHit) return { steps, ok: true, already: true, reward: logReward, via: 'log' };
  steps.push('post-checkin');
  if (TS_ON) {
    steps.push('turnstile');
    if (!tsToken) {
      steps.push('stateOf-2');
      steps.push('todayBonusLog-2');
      if (logHit) return { steps, ok: true, already: true, via: 'log-fallback' };
      return { steps, ok: false, via: 'captcha-fail' };
    }
  }
  return { steps, ok: true, via: 'posted' };
}

let f = flow({ stateChecked: true });
ok(f.ok && !f.steps.includes('turnstile'), 'stats 已签：直接成功，不进人机验证');

f = flow({ stateChecked: false, logHit: true, logReward: 0 });
ok(f.ok && f.via === 'log' && !f.steps.includes('turnstile'),
   'kk 场景（stats 未回写但日志有记录、奖励 0）：判成功且不进人机验证');

f = flow({ stateChecked: false, logHit: true, logReward: 20.64 });
ok(f.ok && f.reward === 20.64, 'just 场景：日志命中并带出奖励');

f = flow({ stateChecked: false, logHit: false, tsToken: null });
ok(!f.ok && f.steps.filter(s => s.startsWith('todayBonusLog')).length === 2,
   '真未签且验证失败：日志兜底查过两次后才报失败');

f = flow({ stateChecked: false, logHit: false, tsToken: 'tk' });
ok(f.ok && f.via === 'posted', '验证通过：正常 POST 签到');

/* ---------- 超时时序 ---------- */
console.log('\n[3] 超时时序约束（Java 宽限必须长于 JS 等待）');
const JS_TS_WAIT = 25000;        // CheckinJs turnstile 等待上限
const JAVA_GRACE = 30000;        // OffscreenCheckin needUi 宽限
const NEED_UI_AT = 6000;         // JS 发出 NEED_UI 信号的时刻
const JS_TOTAL = NEED_UI_AT + JS_TS_WAIT;
ok(JAVA_GRACE > JS_TS_WAIT, 'Java 宽限(30s) > JS 验证等待(25s)：JS 有机会走日志兜底');
ok(NEED_UI_AT + JAVA_GRACE > JS_TOTAL,
   '信号后宽限窗口覆盖 JS 自行收尾时间（旧值 4s 会在 10s 掐掉，日志兜底永不执行）');
ok(NEED_UI_AT + JAVA_GRACE < 100000, '总时长仍在 OffscreenCheckin 100s 超时内');

/* ---------- 签到后刷新 ---------- */
console.log('\n[4] 签到后一律刷新三个额度');
function afterCheckin(result, batchMode) {
  return { refreshed: !batchMode };   // v0.2.5：不再依赖 ok/already
}
ok(afterCheckin({ ok: true, already: false }, false).refreshed, '成功 → 刷新');
ok(afterCheckin({ ok: true, already: true }, false).refreshed, '已签 → 刷新');
ok(afterCheckin({ ok: false }, false).refreshed, '失败也刷新（登录动作可能已改变额度）');
ok(afterCheckin({ ok: false, auth: true }, false).refreshed, '授权失败同样刷新');
ok(!afterCheckin({ ok: true }, true).refreshed, '批量模式抑制逐个刷新（收尾统一刷）');

/* ---------- 人机验证不再跳可见页 ---------- */
console.log('\n[5] 人机验证失败不再跳转可见页');
function isEnvFail(m) {
  return m.startsWith('net::') || m.includes('超时') || m.includes('HTTP')
      || m.includes('加载失败') || m.includes('注入失败');
}
ok(!isEnvFail('人机验证需要手动确认'), '人机验证类 → 不跳转（避免再白等一轮）');
ok(!isEnvFail('人机验证未通过（wait-timeout）'), 'wait-timeout 文案也不跳转');
ok(isEnvFail('net::ERR_CONNECTION_ABORTED'), '网络类 → 仍转可见兜底');
ok(isEnvFail('注入失败'), '注入失败 → 仍转可见兜底');

console.log('\n结果: ' + pass + ' 通过 / ' + fail + ' 失败');
process.exit(fail ? 1 : 0);