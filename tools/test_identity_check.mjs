/**
 * B1 身份校验落库逻辑自测（node 模拟，不依赖 Android）。
 * 复刻 AuthActivity.finishOk 与 SilentAuth.saveTokenChecked 的校验语义：
 *   - 站点返回 login == 期望（忽略大小写）→ 落库
 *   - 不符 → 拒绝落库（防串号）
 *   - 期望为空（未绑定凭据/githubUser）→ 不校验，直接落库（兼容旧数据）
 *   - 站点未返回 user → 无锚点，不校验直接落库（B1 允许：无从判断不误拒）
 * 用法：node tools/test_identity_check.mjs
 */
import process from 'node:process';

let pass = 0, fail = 0;
function ok(cond, name) {
  if (cond) { pass++; console.log('  PASS ' + name); }
  else { fail++; console.log('  FAIL ' + name); }
}

/** 复刻 Java 校验语义 */
function identityCheck(expect, siteLogin) {
  // 期望为空 → 不校验
  if (!expect || expect.length === 0) return true;
  // 站点未返回 login → 无锚点不校验（不误拒）
  if (!siteLogin || siteLogin.length === 0) return true;
  return expect.trim().toLowerCase() === siteLogin.trim().toLowerCase();
}

/* [1] 相符落库 */
console.log('[1] 身份相符 → 落库');
ok(identityCheck('AI-modelsAPI', 'AI-modelsAPI') === true, '完全一致');
ok(identityCheck('ai-modelsapi', 'AI-modelsAPI') === true, '大小写无关');
ok(identityCheck(' AI-modelsAPI ', ' AI-modelsAPI ') === true, '首尾空格容忍');

/* [2] 不符拒绝（防串号核心） */
console.log('[2] 身份不符 → 拒绝落库');
ok(identityCheck('AI-modelsAPI', 'zgj19810121') === false, '期望A实际B → 拒绝');
ok(identityCheck('AI-modelsAPI', 'zhangguojun1981-cmd') === false, '期望A实际zh → 拒绝');

/* [3] 期望为空不校验（兼容旧数据/无绑定凭据） */
console.log('[3] 期望为空 → 不校验（不阻塞）');
ok(identityCheck('', 'zgj19810121') === true, '期望空 → 通过');
ok(identityCheck(null, 'zgj19810121') === true, '期望null → 通过');

/* [4] 站点未返回 user → 不校验（无锚点不误拒） */
console.log('[4] 站点未返回 login → 不校验');
ok(identityCheck('AI-modelsAPI', '') === true, 'login空 → 通过');
ok(identityCheck('AI-modelsAPI', null) === true, 'login null → 通过');

/* [5] 保存行为模拟：拒绝时不写库 */
console.log('[5] 拒绝时不写库');
{
  const store = new Map();
  function saveTokenChecked(expect, token, siteLogin) {
    if (!identityCheck(expect, siteLogin)) return false;
    store.set('acc1', { token, githubAccount: siteLogin });
    return true;
  }
  ok(saveTokenChecked('AI-modelsAPI', 'tokA', 'AI-modelsAPI') === true, '相符 → 写入');
  ok(store.get('acc1').token === 'tokA', '库中有正确 token');
  ok(saveTokenChecked('AI-modelsAPI', 'tokB_wrong', 'zgj19810121') === false, '不符 → 拒绝');
  ok(store.get('acc1').token === 'tokA', '库中 token 未被污染（仍是 tokA）');
  ok(saveTokenChecked('zgj19810121', 'tokZH', 'zgj19810121') === true, 'zh 账号本身授权自己 → 通过');
}

/* [6] 期望用户名取值链（凭据 githubUser 优先 → 账号别名回退） */
console.log('[6] 期望用户名取值链');
{
  function expectOf(credGithubUser, accountAlias) {
    if (credGithubUser && credGithubUser.length > 0) return credGithubUser;
    return accountAlias || '';
  }
  ok(expectOf('AI-modelsAPI', '随便什么') === 'AI-modelsAPI', 'githubUser 优先');
  ok(expectOf('', 'AI-modelsAPI') === 'AI-modelsAPI', '无 githubUser → 回退别名');
  ok(expectOf('', '') === '', '都无 → 空（不校验）');
}

console.log(`\n结果: ${pass} 通过 / ${fail} 失败`);
process.exit(fail ? 1 : 0);