/**
 * auth.js — 用内嵌 headless 浏览器完成 GitHub OAuth 授权，自动抓取 token。
 *
 * 依赖: 本机需有 chromium 可执行文件（或设置 chromiumPath）。
 * 流程:
 *   1. 打开站点 OAuth 发起页 (/api/oauth/github)
 *   2. 浏览器自动跳到 GitHub 账号选择页（设备已登录 GitHub 则可自动完成）
 *   3. 授权成功后回到站点，前端把 access_token 写入 localStorage['new-api:auth-session']
 *   4. 本脚本从浏览器上下文读取该值，落盘到 data/tokens.json
 *
 * 用法: node src/auth.js <siteKey> <accountKey>
 *       node src/auth.js justworker gh1
 */
import { loadConfig } from './config.js';
import { load, save, appendLog } from './db.js';

const cfg = loadConfig();
const siteKey = process.argv[2];
const accountKey = process.argv[3];

if (!siteKey) {
  console.error('用法: node src/auth.js <siteKey> <accountKey>');
  process.exit(1);
}
const site = cfg.sites.find(s => s.key === siteKey);
if (!site) { console.error('站点不存在:', siteKey); process.exit(1); }

let browser = null;
try {
  const puppeteer = await import('puppeteer-core');
  if (!cfg.chromiumPath) {
    console.error('✖ 未配置 chromiumPath。');
    console.error('  请先安装 chromium，并在 config.json 设置 chromiumPath，例如:');
    console.error('  Chromium (Linux):  /usr/bin/chromium');
    console.error('  Chrome (macOS):    /Applications/Google Chrome.app/Contents/MacOS/Google Chrome');
    console.error('  Chrome (Windows):  C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe');
    console.error('  Electron 自带:     node_modules/electron/dist/electron');
    console.error('');
    console.error('替代方案: 直接用系统 Chrome 完成一次登录，再导出 token 写入 data/tokens.json。');
    console.error('  tokens.json 格式: [{"key":"gh1","siteKey":"justworker","githubAccount":"AI模型API","token":"...","cookie":"..."}]');
    process.exit(2);
  }
  browser = await puppeteer.launch({
    executablePath: cfg.chromiumPath,
    headless: 'new',
    args: ['--no-sandbox', '--disable-dev-shm-usage', ...(cfg.proxy?.enabled ? [
      `--proxy-server=socks5://${cfg.proxy.host}:${cfg.proxy.port}`
    ] : [])]
  });
  const page = await browser.newPage();
  await page.setExtraHTTPHeaders({ 'User-Agent': cfg.UA });

  const oauthUrl = `${site.baseUrl}/api/oauth/github`;
  console.log('▶ 打开 OAuth 授权:', oauthUrl);
  await page.goto(oauthUrl, { waitUntil: 'networkidle2', timeout: 60000 });

  console.log('▶ 等待授权回调...');
  // 等待跳转到 dashboard（授权成功标志）
  const target = new URL('/dashboard', site.baseUrl).href;
  await page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 120000 })
    .catch(() => {});
  const finalUrl = page.url();
  console.log('▶ 最终 URL:', finalUrl);

  const raw = await page.evaluate(() => window.localStorage.getItem('new-api:auth-session'));
  if (!raw) {
    console.error('✖ 授权后未拿到 token（localStorage[new-api:auth-session] 为空）');
    process.exit(3);
  }
  const session = JSON.parse(raw);
  const cookie = await page.evaluate(() => document.cookie);
  const token = session.access_token || session.accessToken;
  if (!token) { console.error('✖ session 中无 access_token'); process.exit(4); }

  const tokens = load('tokens.json') || [];
  const rec = tokens.find(t => t.key === (accountKey || 'default')) || { key: accountKey || 'default', siteKey, githubAccount: session.user?.login };
  Object.assign(rec, { siteKey, githubAccount: session.user?.login || rec.githubAccount, token, cookie });
  rec.updatedAt = new Date().toISOString();
  save('tokens.json', tokens.includes(rec) ? tokens : [...tokens, rec]);
  appendLog({ site: siteKey, account: rec.key, event: 'auth', detail: { user: session.user?.login } });
  console.log('✅ 授权成功，已保存 token ->', rec.key, '@', siteKey);
  console.log('   GitHub 用户:', session.user?.login);
  console.log('   token (截断):', token.slice(0, 8) + '...');
} catch (e) {
  console.error('✖ 授权失败:', e);
  process.exit(10);
} finally {
  if (browser) await browser.close().catch(() => {});
}
