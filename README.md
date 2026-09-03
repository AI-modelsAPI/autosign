# justsign — 公益AI中转站自动签到

## 是什么
一个**纯后台、无界面弹窗**的自动签到调度引擎，先覆盖 justworker（小学生公益站），
后续可平移到 agentrouter / gorouter / kktoken 等同类 New API 站点。

- 额度**走接口读取**（`Bearer access_token`），不读 UI。
- 登录态由**内嵌浏览器**（headless）完成 GitHub OAuth，授权一次后全程后台。
- 多站点 × 多 GitHub 账号 × 代理 + 定时调度 + 日志。

## 当前阶段（justworker 单站点勘察已完成）
- 登录即签到（无需单独操作）。
- token 存 `localStorage['new-api:auth-session']`，字段 `access_token`。
- 关键接口：`/api/status`(免认证)、`/api/user/self`、`/api/user/status`、
  `/api/log/self`、`/api/user/checkin`(POST) — 均 `Bearer` 认证。

## 快速开始
```bash
npm install
node src/index.js setup            # 初始化 config.json / data/
node src/auth.js justworker        # 内嵌浏览器走 GitHub 授权（需系统有 chromium）
node src/index.js status <accountKey>   # 查额度
node src/index.js checkin <accountKey>  # 签到
node src/index.js logs <accountKey>     # 使用日志（系统类别=签到记录）
```

## 目录
- `src/` 引擎源码（跨平台通用）
- `data/` 持久化数据（accounts.json / tokens.json / logs.json）
- `config.json` 站点 / 账号 / 代理 / 调度配置

## 跨平台产物
- **macOS 英特尔 DMG**：Electron 打包（含桌面 UI + 后台引擎）。
- **Android APK**：Capacitor 打包（同一套页面 + 引擎）。
- **退化方案**：PWA（零编译，直接浏览器安装到桌面）。
