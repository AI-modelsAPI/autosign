/**
 * auth.js — GitHub OAuth 授权（v0.1.2：新版 new-api 回调页直接交换方案）
 *
 * 新版 new-api 前端的 localStorage['new-api:auth-session'] 只是跨页签广播事件（写后即删），
 * 真正的 session bundle 在前端内存里；回调页（站点域 /oauth/github?code&state）加载后
 * 由前端 GET /api/oauth/github?code&state 直接换取 {success:true,data:{access_token,...}}。
 * 因此这里改为：监听回调页响应 + 回调页上下文主动 fetch 交换（双保险），不再轮询 localStorage。
 *
 * 用法: node src/auth.js <siteKey> <accountKey>
 *   1) 配 chromiumPath 后 headless 自动化授权；
 *   2) 或不配 chromiumPath，脚本会打印手动授权指引（站点 → 授权 → 站点回调页 → F12 抓响应）。
 */
import { loadConfig, saveConfig } from './config.js';

const cfg = loadConfig();
const siteKey = process.argv[2];
const accountKey = process.argv[3];

if (!siteKey || !accountKey) {
  console.error('用法: node src/auth.js <siteKey> <accountKey>');
  process.exit(1);
}
const site = (cfg.sites || []).find(s => s.key === siteKey);
if (!site) {
  console.error('站点不存在:', siteKey, '（先 node src/index.js sites add）');
  process.exit(1);
}
if (!Array.isArray(site.accounts)) site.accounts = [];
const acc = site.accounts.find(a => a.key === accountKey);
if (!acc) {
  console.error('账号不存在:', accountKey, '（先 node src/index.js accounts add', siteKey, '）');
  process.exit(1);
}
const base = site.baseUrl.replace(/\/+$/, '');

async function persist(login, token, cookie) {
  acc.githubAccount = login || acc.githubAccount || null;
  acc.token = token;
  if (cookie) acc.cookie = cookie;
  acc.updatedAt = new Date().toISOString();
  saveConfig(cfg);
}

/* ---------- 手动授权指引（无 chromium 时） ---------- */
if (!cfg.chromiumPath) {
  console.log('未配置 chromiumPath，使用手动授权流程：');
  console.log('  1) 系统浏览器打开: ' + base + '/login');
  console.log('  2) 点「使用 GitHub 继续」完成 GitHub 授权');
  console.log('  3) 回到站点任意页面后按 F12 → Network → 刷新，找 /api/user/auth/refresh 的响应');
  console.log('     或在 Console 执行: fetch("/api/user/auth/refresh",{method:"POST",credentials:"include"}).then(r=>r.json()).then(console.log)');
  console.log('     复制 data.access_token，然后写入配置（任选其一）:');
  console.log('       node src/index.js accounts 列出 key 后，用 API: POST /api/accounts/save');
  console.log('       {"siteKey":"' + siteKey + '","key":"' + accountKey + '","token":"<粘贴>"}');
  process.exit(2);
}

/* ---------- 自动化授权（chromiumPath 已配置） ---------- */
let browser = null;
try {
  const puppeteer = await import('puppeteer-core');

  /* 第 0 步：拿 flow_token（服务端 state） */
  console.log('▶ 获取 state (flow_token)…');
  const stResp = await fetch(base + '/api/oauth/state', {
    method: 'POST',
    headers: {
      'User-Agent': cfg.UA || 'Mozilla/5.0',
      'Content-Type': 'application/json',
      'Accept': 'application/json'
    },
    body: JSON.stringify({ provider: 'github', intent: 'login' })
  });
  const stJ = await stResp.json().catch(() => ({}));
  const flowToken = typeof stJ.data === 'string' ? stJ.data : stJ.data?.flow_token;
  if (!stJ.success || !flowToken) {
    console.error('✖ state 获取失败:', stJ.message || ('http ' + stResp.status));
    process.exit(3);
  }
  console.log('▶ flow_token 就绪');

  /* 第 1 步：clientId 从站点 /api/status 动态取 */
  let clientId = 'Ov23liBGecTYSePKpXQC';
  try {
    const sResp = await fetch(base + '/api/status', { headers: { 'User-Agent': cfg.UA || 'Mozilla/5.0' } });
    const sJ = await sResp.json().catch(() => ({}));
    if (sJ?.data?.github_client_id) clientId = sJ.data.github_client_id;
  } catch (_) {}

  /* 第 2 步：开 headless 浏览器走 GitHub 授权 */
  browser = await puppeteer.launch({
    executablePath: cfg.chromiumPath,
    headless: 'new',
    args: ['--no-sandbox', '--disable-dev-shm-usage', ...(cfg.proxy?.enabled ? [
      `--proxy-server=socks5://${cfg.proxy.host}:${cfg.proxy.port}`
    ] : [])]
  });
  const page = await browser.newPage();
  await page.setUserAgent(cfg.UA || 'Mozilla/5.0');
  const authUrl = 'https://github.com/login/oauth/authorize?client_id=' + encodeURIComponent(clientId)
    + '&state=' + encodeURIComponent(flowToken) + '&scope=user:email';
  console.log('▶ 打开 GitHub 授权:', authUrl);
  await page.goto(authUrl, { waitUntil: 'networkidle2', timeout: 60000 }).catch(() => {});

  /* 第 3 步：等回调页（站点域 /oauth/github?code&state），在页面上下文直接交换 */
  console.log('▶ 等待授权回调（请在 headless 会话内完成 GitHub 登录）…');

  let bundle = null;
  const deadline = Date.now() + 5 * 60 * 1000;
  while (Date.now() < deadline && !bundle) {
    const url = page.url();
    let host = '';
    try { host = new URL(url).host; } catch (_) {}
    if (host && host === new URL(base).host && url.includes('/oauth/')) {
      try {
        bundle = await page.evaluate(async () => {
          const q = new URLSearchParams(location.search);
          const provider = location.pathname.split('/').pop();
          const r = await fetch('/api/oauth/' + provider + '?' + q.toString(), { credentials: 'include' });
          return r.json();
        });
        if (bundle && bundle.success && bundle.data?.access_token) break;
        bundle = null;
      } catch (_) { /* 回调页跳转瞬间 evaluate 失败，下一轮再试 */ }
    }
    await new Promise(r => setTimeout(r, 1500));
  }
  if (!bundle || !bundle.success || !bundle.data?.access_token) {
    console.error('✖ 授权未完成（5 分钟超时或交换失败）。最终 URL:', page.url());
    process.exit(4);
  }

  const login = bundle.data.user?.username || bundle.data.user?.login || null;
  const token = bundle.data.access_token;
  const cookie = await page.evaluate(() => document.cookie).catch(() => '');
  await persist(login, token, cookie);
  console.log('✅ 授权成功，已保存 token →', accountKey, '@', siteKey);
  console.log('   GitHub 用户:', login);
  console.log('   token (截断):', token.slice(0, 8) + '...');
} catch (e) {
  console.error('✖ 授权失败:', e.message);
  process.exit(10);
} finally {
  if (browser) await browser.close().catch(() => {});
}