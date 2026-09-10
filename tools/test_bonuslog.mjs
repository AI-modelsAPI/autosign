/* v0.2.6：日志 type=4 判定 / 金额取自文案 / 多账号身份校验
 * 样本取自真实响应（curl 实测 api.justwoker.icu 与 kktoken.cc）。
 */
let pass = 0, fail = 0;
const ok = (c, m) => { if (c) { pass++; console.log('  PASS ' + m); } else { fail++; console.log('  FAIL ' + m); } };

/* ---------- 1. 签到文案识别（排除注册/邀请赠送） ---------- */
const isCheckinText = t => {
  if (!t) return false;
  if (!/签到|check.?in/i.test(t)) return false;
  return !/注册|邀请|兑换/.test(t);
};
console.log('[1] 签到文案识别（type=4 里混有非签到条目）');
ok(isCheckinText('用户签到，获得额度 ＄20.642880 额度'), 'just 真实签到文案 → 命中');
ok(isCheckinText('每日签到成功'), '无金额的签到文案 → 命中');
ok(!isCheckinText('新用户注册赠送 ＄75.000000 额度'), 'kk 真实数据：注册赠送 → 不算签到');
ok(!isCheckinText('使用邀请码赠送 ＄25.000000 额度'), 'kk 真实数据：邀请赠送 → 不算签到');
ok(!isCheckinText('Logged in successfully via oauth:github'), '登录日志 → 不算签到');
ok(!isCheckinText(''), '空文案 → 不命中');
ok(isCheckinText('Daily check-in reward'), '英文 check-in → 命中');

/* ---------- 2. 金额解析（quota 恒为 0，只能取文案） ---------- */
const parseUsd = t => {
  if (!t) return -1;
  const m = t.match(/[＄$]\s*([0-9]+(?:\.[0-9]+)?)/);
  return m ? Math.round(Number(m[1]) * 100) / 100 : -1;
};
console.log('\n[2] 金额取自 content 文案（全角 ＄）');
ok(parseUsd('用户签到，获得额度 ＄20.642880 额度') === 20.64, '全角 ＄20.642880 → 20.64');
ok(parseUsd('用户签到，获得额度 $28.068886 额度') === 28.07, '半角 $28.068886 → 28.07（四舍五入）');
ok(parseUsd('每日签到成功') === -1, '无金额 → -1（表示未知，不编造 0）');
ok(parseUsd('签到成功 ＄0.000000') === 0, '明确写 0 → 0（本站不发奖励）');

/* 落库语义 */
const bonusOf = t => { const u = parseUsd(t); return { rewardUSD: u >= 0 ? u : 0, rewardKnown: u >= 0 }; };
console.log('\n[3] 奖励三态');
let b = bonusOf('用户签到，获得额度 ＄20.642880 额度');
ok(b.rewardKnown && b.rewardUSD === 20.64, '有奖励：known=true, 20.64');
b = bonusOf('签到成功 ＄0.000000');
ok(b.rewardKnown && b.rewardUSD === 0, '本站不发奖励：known=true, 0（显示「无奖励」）');
b = bonusOf('每日签到成功');
ok(!b.rewardKnown && b.rewardUSD === 0, '金额未知：known=false（UI 不显示金额）');

/* ---------- 4. 请求参数形态 ---------- */
console.log('\n[4] 日志接口参数（实测 category=系统 无效）');
const buildPath = (cat, limit) => '/api/log/self?'
  + (cat === '系统' ? 'type=4' : ('category=' + encodeURIComponent(cat)))
  + '&limit=' + limit + '&page=1';
ok(buildPath('系统', 30) === '/api/log/self?type=4&limit=30&page=1', '系统类 → type=4');
ok(buildPath('消费', 10).includes('category=') , '其它类别 → 仍用 category');
ok(!buildPath('系统', 30).includes('category'), '不再发送无效的中文 category');

/* ---------- 5. 多账号身份校验 ---------- */
console.log('\n[5] 多账号 token 串号防护');
const sameUser = (a, b) => !!a && !!b && a.trim().toLowerCase() === b.trim().toLowerCase();
function silentAuthSave({ expect, actual }) {
  if (expect && actual && !sameUser(expect, actual)) {
    return { saved: false, needUi: true, reason: '身份不符' };
  }
  return { saved: true, needUi: false };
}
let s = silentAuthSave({ expect: 'zhangguojun1981-cmd', actual: 'AI-modelsAPI' });
ok(!s.saved, '实测故障场景：期望 zhangguojun1981-cmd 实到 AI-modelsAPI → 拒绝落库');
ok(s.needUi, '拒绝后要求手动授权切换账号');
s = silentAuthSave({ expect: 'AI-modelsAPI', actual: 'AI-modelsAPI' });
ok(s.saved, '身份一致 → 正常落库');
s = silentAuthSave({ expect: 'AI-modelsAPI', actual: 'ai-modelsapi' });
ok(s.saved, '大小写差异视为同一账号');
s = silentAuthSave({ expect: '', actual: 'AI-modelsAPI' });
ok(s.saved, '未登记期望用户名 → 不校验（不阻塞老数据）');
s = silentAuthSave({ expect: 'AI-modelsAPI', actual: '' });
ok(s.saved, '站点未返回用户名 → 不校验（无从判断，不误拒）');

/* 期望用户名取值优先级 */
const expectUser = (cred, acc) => (cred && cred.githubUser) || (acc && acc.alias) || '';
console.log('\n[6] 期望用户名取值');
ok(expectUser({ githubUser: 'gh-a' }, { alias: 'nick' }) === 'gh-a', '凭据 githubUser 优先');
ok(expectUser({ githubUser: '' }, { alias: 'nick' }) === 'nick', '回退账号别名');
ok(expectUser(null, null) === '', '都没有 → 空（不校验）');

/* ---------- 7. 串号后果说明 ---------- */
console.log('\n[7] 串号识别（同 JWT sub 即同一站点用户）');
const jwtSub = t => t.sub;
const a1 = { sub: '10213' }, a2 = { sub: '10213' }, a3 = { sub: '1061' };
ok(jwtSub(a1) === jwtSub(a2), '实测：两账号 JWT sub 均为 10213 → 确认串号');
ok(jwtSub(a1) !== jwtSub(a3), '不同站点用户 sub 不同');

console.log('\n结果: ' + pass + ' 通过 / ' + fail + ' 失败');
process.exit(fail ? 1 : 0);