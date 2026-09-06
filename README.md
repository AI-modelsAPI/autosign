# AutoSign — 公益 AI 中转站自动签到

安卓端每日自动签到 + 额度看板，多站点 × 多 GitHub 账号。
凭据本地加密存储，签到走站点官方接口，不伪造任何验证。

- **额度走接口读取**（`Bearer access_token`），不解析 UI。
- **登录态由内嵌 WebView 完成 GitHub OAuth**，授权一次后全程后台。
- 定时调度（每天 / 工作日 / 自定义间隔）+ SOCKS5 代理 + 操作日志浮窗。

> 项目仓库名沿用 `justsign`，应用名为 **AutoSign**（包名 `icu.justwoker.justsign`）。

## 下载

[Releases](../../releases) 里的 `justsign-vX.Y.Z.apk`，或 Actions 构建产物。
v0.2.1 起使用仓库内固定签名，可直接覆盖安装；**从 v0.2.0 及更早版本升级需要先卸载**
（可先用 `tools/migrate_prefs.py` 备份并迁移配置）。

## 三种站点形态

15 个内置站点实测后发现它们并不是一类东西，App 按形态分别处理：

| 形态 | 判据 | App 行为 | 实测站点 |
| :--- | :--- | :--- | :--- |
| `newapi` | 有 `GET/POST /api/user/checkin` | 全自动签到，可拿到确切奖励金额 | JustDoWork、SeekAI、KKtoken、幻城网安 |
| `login` | New API 变体，`POST /api/user/checkin` 返回 404 | 登录保活刷额度，查当日奖励记录 | AgentRouter、维云、DoCode |
| `web` | 非 New API 或接口被 Cloudflare 拦截 | 点「去网页」打开站点，人工处理 | RawChat、Matrix、TaBiAI、GoRouter、肖恩Ai、NOFX、TrueSOTA、Vyce |

站点管理页里三种形态可手动切换，内置站与自定义站完全平权（都能增删改）。

## 签到判定与凭据

**凭据不做续期，直接后台换新。** 站点续期接口 `/api/user/auth/refresh` 依赖 httpOnly 会话
Cookie，实测在 App 侧无法稳定维持（只带 `X-Auth-Session` + `Bearer` 一律 401）。
v0.2.3 起彻底删掉这条路，改为：**凡是需要 token 的操作，先确保凭据可用再执行**。

判定与交换在 `SilentAuth` 里完成，全程离屏 WebView、零弹窗：

1. 解析 JWT `exp`，剩余不足 1 分钟（或本地没有 token）就先换新的，不等 401
2. 请求真拿到 401（服务端提前作废）再换一次并重试
3. 换新 = 复用 WebView 里已有的 GitHub 登录态跑一次 OAuth，秒级完成
4. 只有 GitHub 自身要求登录 / 2FA / 设备验证时，才拉起可见授权页

三重防风暴（一次刷新要打 4 个鉴权接口，不能各换一次）：

| 保护 | 作用 |
| :--- | :--- |
| 同账号串行 + 单轮 token 缓存 | 一轮刷新只交换 1 次 |
| 成功后 8 秒内复用 | 连点刷新不重复交换 |
| 失败后 90 秒冷却 | GitHub 会话真失效时不反复打站点（手动点授权可立即重试） |

**签到状态**：先查只读接口 `GET /api/user/checkin?month=YYYY-MM`（不需要人机验证），
`stats.checked_in_today` 或当月 `records` 里有今天的记录 ⇒ 判定已签。
只有确认未签才 `POST` 签到，只有 POST 真被拦时才挂 Turnstile
（`appearance:interaction-only`，无需交互时静默通过）。

**签到后自动刷额度**：单账号签完立即刷该账号，批量签完统一串行刷一遍 —— 可用余额、
累计已用、今日消耗、签到奖励一次到位，不用再手点刷新。

奖励分三态，服务端给什么就显示什么，不猜：

- 有今日记录且 `quota_awarded > 0` → 显示 `+$28.07 今日签到`
- 有今日记录但 `quota_awarded == 0` → 显示「本站无签到奖励」
- 查不到记录（只能确认已签）→ 只显示「已签」，不显示金额

## 凭据与自动填充

凭据库存「站点账号 / 密码 / GitHub 用户名 / TOTP 密钥」，密码与 TOTP 经
Android Keystore（AES-256-GCM）加密后落盘；Keystore 不可用时拒绝保存，绝不退回明文。

授权页出现账号密码框时自动填充并可自动点登录（等价于密码管理器 + 用户点一下）：

- 页面存在未通过的人机验证挂件时**只填不点**，交给真实浏览器环境或用户完成
- 配了 TOTP 的账号，若 GitHub 2FA 页默认走「GitHub Mobile 推送」，
  会点页面上原有的「Use authenticator app」切到验证器输入框再填 6 位码
- 没配 TOTP 的账号完全跳过全部 2FA 逻辑

自动提交可在「设置 → 界面与日志 → 授权时自动点登录」关闭。

## 构建

CI（推 `v*` 标签触发）产出 debug APK，或本地：

```bash
cd android
gradle assembleDebug            # 需要 JDK17 + Gradle 8.7 + Android SDK 34
```

arm64 Linux（如手机上的 proot Ubuntu）需要覆盖 aapt2，
因为 Gradle 从 Maven 拉的是 x86-64 二进制：

```bash
gradle -Pandroid.aapt2FromMavenOverride=/usr/bin/aapt2 assembleDebug
```

### 校验脚本（tools/）

| 脚本 | 用途 |
| :--- | :--- |
| `extract_js.py` | 把 Java 里拼接的 JS 模板抽成 `.js`，配合 `node --check` 查语法 |
| `test_authfill.mjs` | AuthFillJs 行为自测（最小 DOM 桩，覆盖自动提交 / 人机验证 / 2FA 切换） |
| `test_silentauth.mjs` | 凭据交换防风暴自测（串行、8s 复用、90s 冷却、JWT exp 判定） |
| `make_icon.py` | 生成应用图标（纯 Python 光栅化，无需 PIL） |
| `migrate_prefs.py` | 换签名重装时迁移配置，自动清空已失效的加密字段 |

```bash
python3 tools/extract_js.py android/app/src/main/java/icu/justwoker/justsign/AuthFillJs.java /tmp/f.js
node --check /tmp/f.js && node tools/test_authfill.mjs /tmp/f.js
node tools/test_silentauth.mjs
```

## 目录

- `android/` 安卓端（纯 Java 构建 View，无 XML 布局/无 Compose/无第三方 UI 库）
- `src/` Node 引擎（跨平台通用，桌面端复用）
- `electron/` macOS 桌面端
- `tools/` 构建与校验脚本
- `data/` 站点清单快照

## 声明

本项目只做「把用户自己的账号按站点提供的接口签到」这件事：不绕过人机验证、
不伪造凭据、不做批量注册。人机验证由真实浏览器环境完成，验证不通过就报失败。
