/**
 * OffscreenReadJs 行为回归：从 Java 源码抽取真实脚本并实际执行，验证「过盾判定 + 重试标记」。
 *
 * 为什么必须真跑而不是字符串断言：
 *   这段脚本的失败模式是「把 WAF 挑战页当成业务数据回传」和「没确认就当我过了」，
 *   两者都只在运行时才暴露，字符串包含断言抓不到。
 *
 * 用法：
 *   node tools/test_readjs_behavior.mjs [OffscreenReadJs.java]
 *   （默认 android/app/src/main/java/icu/justwoker/justsign/OffscreenReadJs.java）
 */
import fs from 'node:fs';
import process from 'node:process';

const JAVA = process.argv[2]
  || 'android/app/src/main/java/icu/justwoker/justsign/OffscreenReadJs.java';

/** 按 Java 字符串字面量转义规则，抽取 render() 里拼接出的真实 JS。 */
function extractJs(javaSrc) {
  const start = javaSrc.indexOf('return "(function(){');
  if (start < 0) throw new Error('未找到 render() 的脚本入口');
  const seg = javaSrc.slice(start);
  const out = [];
  let i = 0;
  while (i < seg.length) {
    /* Java 注释必须跳过：否则「块注释吞掉代码行」这类缺陷会被静默忽略 ——
     * 抽取器只认引号，被注释掉的字符串拼接照样被捞出来，测试反而全绿。
     * 真机教训：一次编辑漏写块注释结束符，把 run()/pollIdentity 尾部吞进注释，
     * 单测与行为测试都通过，但离屏只读兜底恒失败。 */
    if (seg[i] === '/' && seg[i + 1] === '*') {
      const end = seg.indexOf('*/', i + 2);
      if (end < 0) throw new Error('未闭合的块注释：其后的脚本代码已被注释吞掉');
      i = end + 2;
      continue;
    }
    if (seg[i] === '/' && seg[i + 1] === '/') {
      const nl = seg.indexOf('\n', i);
      if (nl < 0) break;
      i = nl + 1;
      continue;
    }
    if (seg[i] === '"') {
      let buf = '';
      i++;
      while (i < seg.length) {
        const ch = seg[i];
        if (ch === '\\') {
          const nx = seg[i + 1];
          const map = { n: '\n', t: '\t', r: '\r', '"': '"', "'": "'", '\\': '\\' };
          if (nx in map) { buf += map[nx]; i += 2; }
          else if (nx === 'u') { buf += String.fromCharCode(parseInt(seg.slice(i + 2, i + 6), 16)); i += 6; }
          else { buf += nx; i += 2; }
        } else if (ch === '"') { i++; break; }
        else { buf += ch; i++; }
      }
      out.push(buf);
      /* 用括号配平判断脚本真正结束：pollIdentity 内部也含 `})();`，
       * 按首个出现位置截断会切坏脚本（此处曾踩过）。 */
      const joined = out.join('');
      if (joined.includes('return "(function(){'.slice(7)) || joined.startsWith('(function(){')) {
        let depth = 0;
        for (const c of joined) {
          if (c === '{' || c === '(') depth++;
          else if (c === '}' || c === ')') depth--;
        }
        if (depth === 0 && joined.trimEnd().endsWith(';')) break;
      }
      continue;
    }
    i++;
  }
  /* 脚本里 B/P/N/ID 由 Java 变量注入（非字符串字面量），抽取后为空串；
   * 填入测试值，使其与真实运行时形态一致。 */
  return out.join('')
    .replace("B=''", "B='https://example.com'")
    .replace("P=''", "P='/api/token/?p=1&size=100'")
    .replace("N=''", "N='n1'")
    .replace("ID=''", "ID='12345'");
}

const js = extractJs(fs.readFileSync(JAVA, 'utf8'));

let pass = 0, fail = 0;
const ok = (c, m) => { if (c) { pass++; console.log('  PASS ' + m); } else { fail++; console.log('  FAIL ' + m); } };

const V2PAGE = '<!doctype html><meta name="aliyun_waf_aa" content="x"><script>var __webpack_modules__={};//aliyun_waf</script>';
const SELF_OK = JSON.stringify({ success: true, data: { id: 12345, quota: 100 } });
const TARGET_OK = JSON.stringify({ success: true, data: { items: [{ id: 1 }] } });

/**
 * 用假时钟推进 Date.now()，让脚本的轮询上限能在有限轮数内到达 ——
 * 这样「超时后如何判定」是被真实验证的，而不是靠等真实时间。
 */
async function runScenario({ selfResponses, captchaVisible }) {
  const results = [];
  const timers = [];
  let callIdx = 0;
  let fakeNow = 0;
  class FakeDate extends Date {
    constructor(...a) { super(...(a.length ? a : [fakeNow])); }
    static now() { return fakeNow; }
  }
  const doc = {
    readyState: 'complete',
    documentElement: { innerHTML: captchaVisible ? V2PAGE : '<html>ready</html>' },
    getElementById: (id) => (id === 'captcha-element' && captchaVisible)
      ? { getBoundingClientRect: () => ({ width: 100, height: 40 }) } : null,
    cookie: '',
  };
  /* 脚本内用的是裸标识符 JustSign.onReadResult(...)，必须挂到真正的全局，
   * 否则 ReferenceError 会被脚本自身的 try/catch 静默吞掉。 */
  globalThis.JustSign = { onReadResult: (_n, p) => results.push(JSON.parse(p)) };
  const win = {
    document: doc,
    getComputedStyle: () => ({ display: 'block', visibility: 'visible', opacity: '1' }),
    setTimeout: (fn) => { timers.push(fn); return timers.length; },
  };
  win.window = win;
  const fetchStub = (url) => {
    const isSelf = String(url).includes('/api/user/self');
    const body = isSelf ? selfResponses[Math.min(callIdx++, selfResponses.length - 1)] : TARGET_OK;
    return Promise.resolve({ status: 200, text: () => Promise.resolve(body) });
  };
  const fn = new Function('window', 'document', 'fetch', 'setTimeout', 'getComputedStyle', 'Date', js);
  fn(win, doc, fetchStub, win.setTimeout, win.getComputedStyle, FakeDate);
  for (let i = 0; i < 20000 && results.length === 0; i++) {
    const t = timers.shift();
    if (t) { fakeNow += 200; try { t(); } catch (e) { /* 单轮异常不终止驱动 */ } }
    await new Promise(r => setImmediate(r));
  }
  return results[0];
}

console.log('A. 过盾成功 → 必须拿到真实业务数据');
{
  const r = await runScenario({ selfResponses: [SELF_OK], captchaVisible: false });
  ok(r && r.ok === true, '回传 ok:true');
  ok(r && String(r.body).includes('items'), '带回真实业务 body');
}

console.log('\nB. 一直卡在 v2 挑战页 → 判失败但标记「可重试」');
{
  const r = await runScenario({ selfResponses: [V2PAGE], captchaVisible: false });
  ok(r && r.ok === false, '回传 ok:false（绝不把挑战页当数据）');
  ok(r && r.reason === 'waf-not-cleared', 'reason=waf-not-cleared，实际=' + (r && r.reason));
  ok(r && r.retryable === true, '标记 retryable=true（未过盾值得再试）');
}

console.log('\nC. 挑战页未放行 → 一律可重试（一次没成 ≠ 永远不成）');
{
  const r = await runScenario({ selfResponses: [V2PAGE], captchaVisible: true });
  ok(r && r.ok === false, '回传 ok:false');
  ok(r && r.reason === 'waf-not-cleared', 'reason=waf-not-cleared，实际=' + (r && r.reason));
  ok(r && r.retryable === true, '标记 retryable=true');
}

console.log('\nD. 身份不符 → 硬停（安全属性）');
{
  const r = await runScenario({
    selfResponses: [JSON.stringify({ success: true, data: { id: 99999, quota: 1 } })],
    captchaVisible: false,
  });
  ok(r && r.ok === false && r.reason === 'identity-mismatch',
     'reason=identity-mismatch，实际=' + (r && r.reason));
}

console.log(`\n结果: ${pass} 通过 / ${fail} 失败`);
process.exit(fail ? 1 : 0);
