# AutoSign

AutoSign 是面向公益 AI 中转站的 Android 自动签到与额度管理工具，支持多站点、多 GitHub 账号、后台定时签到、额度看板和脱敏日志。

> 当前基准版本：**1.0.0**。本仓库现阶段只维护 Android 端；macOS 与浏览器扩展后续将按 Android 1.0.0 的功能、认证生命周期和数据模型重新实现。

## 功能
- 多站点、多账号独立管理，站点卡片可拖拽或用上下按钮排序。
- 每日、工作日或自定义间隔后台签到。
- 展示可用余额、累计消耗、今日消耗和今日签到奖励。
- GitHub OAuth 首次授权和必要时的可见授权；普通刷新、签到及短期 Token 续期不重新 OAuth。
- New API Refresh Cookie 续期、轮换 Cookie 与 Token 原子保存、同账号并发续期合并。
- 密码与 TOTP 通过 Android Keystore AES-256-GCM 加密，Keystore 不可用时拒绝明文降级。
- SOCKS5 代理、API Key 管理、邀请额度自动划转和 `auth-v2` 脱敏日志。
- 统一圆角弹窗、列表化资产总览、品牌图标和站点顺序联动。

## 内置站点
| 站点 | 类型 | 邀请链接 |
|---|---|---|
| AgentRouter | 登录即得 | [注册](https://agentrouter.org/register?aff=nc7C) |
| JustDoWork | New API | [注册](https://api.justwoker.icu/sign-up?aff=wFQu) |
| GoRouter | 登录型 | [注册](https://gorouter.app/sign-up?aff=Dr35) |
| SeekAI | New API | [注册](https://seekai.cc/sign-up?aff=sxto) |
| KKtoken AI | New API | [注册](https://kktoken.cc/sign-up?aff=BpDr) |

内置邀请链接是站主推广链接，固定随应用分发；自定义站点链接由用户配置。站点接口、奖励和可用性由各站点运营方决定，AutoSign 不作保证。

## 下载与更新
从 [GitHub Releases](../../releases/latest) 下载正式 APK。应用每天后台检查一次公开 Release：没有新版本时不显示下载按钮；发现更高版本后，设置页显示“发现新版本”按钮。用户点击后，应用自动下载名称严格匹配的官方 APK，校验 HTTPS 来源、文件大小、包名、版本号与签名，再交给 Android 系统安装器。首次使用需允许 AutoSign“安装未知应用”，最终安装仍由用户在系统界面确认。

QQ 交流群：**1060200469**。

## 授权生命周期
AutoSign 区分 GitHub 登录会话、站点长期会话、短期 Access Token 和 API Key：

1. 首次授权或用户明确重新授权时才运行 GitHub OAuth。
2. 点击重新授权时先调用 `/api/user/self` 预检：凭据有效则提示无需授权；明确 401 才进入授权；网络异常不创建新会话。
3. New API 站点短期 Token 到期后，通过已有 Refresh Cookie 调用 `/api/user/auth/refresh`，不创建新 OAuth 会话。
4. Cookie 会话型站点直接复用现有会话。
5. 刷新、签到、定时任务、API Key 管理和邀请额度划转不得隐式进入 OAuth。

只有首次登录、GitHub 会话失效、身份不符、需要 2FA/设备验证，或站点长期会话明确失效时，才可能打开可见授权页。

## 签到与人机验证
AutoSign 优先使用站点公开业务接口，不解析额度页面、不伪造验证结果。需要 Turnstile 等验证时只使用正常 WebView/浏览器环境：无感验证可正常通过；要求交互时必须由用户完成，不绕过、破解或代答验证码。

刷新和签到始终后台执行，失败只提示原因并刷新额度核对，不弹站点页面；授权流程允许打开可见页面。

## 隐私与安全
- 凭据只保存在用户设备中，不上传至维护者服务器。
- 密码和 TOTP 使用 Android Keystore 加密；卸载后密钥销毁，加密数据无法恢复。
- 日志不记录 OAuth code、state、Token、Cookie、密码或 TOTP 原值。
- OAuth 回调必须满足站点同域、携带 code 且 state 与本轮严格一致。
- 不提供批量注册、验证码绕过、账号共享或未授权访问功能。

## 本地构建
要求 JDK 17、Gradle 8.7 和 Android SDK 34：

```bash
cd android
gradle testDebugUnitTest assembleDebug assembleRelease --no-daemon
```

ARM64 Linux/proot 使用本机 AAPT2：

```bash
gradle -Pandroid.aapt2FromMavenOverride=/usr/bin/aapt2 \
  testDebugUnitTest assembleDebug assembleRelease --no-daemon
```

目录：`android/` 为应用源码，`tools/` 为辅助测试脚本，`.github/workflows/android-apk.yml` 为 Android CI。

## 使用边界与免责声明
使用者必须遵守所在地法律、GitHub 条款及各站点服务条款，只操作本人所有或已获明确授权的账号。站点可能随时调整接口、风控、奖励或封禁政策；因使用本软件产生的账号限制、额度变化、数据丢失或其他损失由使用者自行承担。

本项目与 GitHub、Cloudflare 及各内置站点无隶属、代理或担保关系。

## 开源许可证
本项目采用 **GNU Affero General Public License v3.0 or later（AGPL-3.0-or-later）**，完整且具有法律效力的条款见 [LICENSE](LICENSE)。分发、修改及通过网络提供修改版本时，请履行 AGPL-3.0-or-later 要求的版权声明、许可证和对应源代码义务。

`AutoSign` 名称、图标和内置邀请关系不因软件许可证而构成官方背书；衍生版本应清楚标明修改者，避免被误认为本项目官方发行版。许可证也不改变使用者遵守法律及第三方服务条款的责任。