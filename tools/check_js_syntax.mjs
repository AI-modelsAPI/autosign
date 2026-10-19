/**
 * 脚本模式语法校验（vm.Script），专抓「顶层 return」这类致命但静默的缺陷。
 *
 * 为什么不能只靠 new Function / node --check：
 *   · new Function 会把源码包进函数体，顶层 return 变成合法 → 缺陷被掩盖；
 *   · Android WebView 的 evaluateJavascript 对编译失败是**静默丢弃**的，
 *     不抛异常、不走结果回调，真机表现只是「永远没有结果」。
 * 真机教训（SeekAI 2026-10-19）：`if(x)return;` 让整段签到脚本编译失败，
 * 白改数轮才用 vm.Script 定位到 "Illegal return statement"。
 *
 * 用法：node tools/check_js_syntax.mjs <file.js>
 */
import fs from 'node:fs';
import vm from 'node:vm';

const file = process.argv[2];
if (!file) { console.error('用法: node tools/check_js_syntax.mjs <file.js>'); process.exit(2); }
const src = fs.readFileSync(file, 'utf8');
try {
  new vm.Script(src, { filename: file });
  console.log(`PASS 脚本模式编译通过：${file} (${src.length} chars)`);
} catch (e) {
  console.error(`FAIL 脚本模式编译失败：${e.message}`);
  process.exit(1);
}
