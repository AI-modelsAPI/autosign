# AutoSign 全量代码审计报告

- **审计对象**：v1.1.25（versionCode 133），tag `v1.1.25`（与 `main@2db6b64` 同源）
- **范围**：全部 Java 源码（39 个 `main` 类 + 测试）、Gradle/AndroidManifest/CI 配置、tools 测试脚本、GitHub Releases 实际状态
- **方法**：人工逐文件通读 + 交叉引用 grep + CI/Releases 实测核验（只读，未改任何代码）
- **审计日期**：2026-09-26

## 总体结论

工程质量整体较高：操作按账号串行（`Engine.opLock`）、刷新 Cookie 一次性消费锁、凭据写回前有身份锚与快照栅栏（`SessionFence`）、日志全程脱敏（只记长度/布尔/SHA-256 前 8 位指纹）、`SiteProtocol`/`ReadFallback` 的 host/方法白名单严格。尚未发现会导致用户资金直接损失的漏洞，但有 **1 个严重供应链风险、4 个高危问题**（其中 2 个影响全体用户的实际使用），另有若干中低危与 1 处政策灰色地带。README 的"不绕交互验证码、不批量注册"声明与代码基本一致，例外见政策一节。

---

## 🔴 严重（Critical）

### C1 · 签名密钥入库且口令硬编码，debug/release 共用同一密钥 → 供应链可完整接管

- `android/app/justsign-debug.jks`（二进制密钥库，随仓库公开）
- `android/app/build.gradle:22-27`：`storePassword 'justsign'` / `keyAlias 'justsign'` / `keyPassword 'justsign'` 全部硬编码
- 同一 `signingConfigs.shared` 同时签 debug 与 release（build.gradle:33,40）

**后果链**：任何人都能用该密钥签发"同签名"APK，Android 会接受其为**合法升级**覆盖安装 → 攻击包接管同包名，可读 SharedPreferences 里的全部站点会话凭据（见 H2），且 Android Keystore 密钥按包名+签名放行，**攻击包升级后可解密已存密码和 2FA 种子**。`UpdateChecker.java:131` 的"APK 签名一致性校验"在此前提下形同虚设——攻击者的恶意包同样能通过校验。

**建议**：
1. 立即将 `justsign-debug.jks` 从仓库历史移除（视为已泄露，折损已不可逆），换一把新密钥；
2. debug/release 分离：调试用各自机器的 debug 签名，release 签名密码走 `local.properties`/CI Secrets（`gradle-ini` 或环境变量注入）；
3. GitHub 上对男人（release）启用 signed artifacts 前的最小动作：永远不要再用仓库里的密钥签发行量版。已发生的损失无法挽回，应在下个版本提示老用户卸载重装以切断旧签名信任。

---

## 🟠 高危（High）

### H1 · 分发与 App 内更新链路断裂：v1.1.x 无 GitHub Release，更新停在 v1.0.7

- `UpdateChecker.java:84,169-171` 只看 GitHub Releases 上名为 `AutoSign-<ver>-release.apk` 的 asset；
- 实测（2026-09-26）：GitHub Releases **Latest = v1.0.7**，v1.1.20/v1.1.24/v1.1.25 **均无 Release**；
- CI `.github/workflows/android-apk.yml:38` 只 `assembleDebug` 并上传工作流 artifact（`justsign-android-apk`），**从不创建 Release** → artifact 命名 `app-*.apk` 与 `<ver>-release.apk` 也对不上。

**后果**：全体用户的"检查新版本"永远停在 v1.0.7，无法发现 1.1.x 的 18 个版本的修复（包括多轮会话安全修复）；且最后一个有 Release 的包是 debug 构建（`debuggable=true`、无 R8 混淆压缩），发布形态与 `release` buildType 的安全配置（minify/proguard）从来未被 CI 实际执行过。

**建议**：CI 增加 release 构建 + `softprops/action-gh-release` 步骤，按 tag 自动发布 `AutoSign-<ver>-release.apk`；发布的应是 `assembleRelease` 产物（配合 C1 的密钥方案）。

### H2 · 站点会话凭据（token / siteCookie / siteUserId）明文存储

- `Store.java:68,82`：整个 `config` JSON（含每账号 `token`、`siteCookie`——即各站接盘的登录会话）明文放入 `SharedPreferences`；只有"凭据库"的**密码与 2FA** 走 Android Keystore AES-256-GCM（`Crypto.java`）。

**后果**：root 设备、备份导出（`allowBackup` 未禁用）、以及 C1 的同签名升级包，都能直接取走这些等价于"已登录浏览器"的凭据，无需破解任何加密。**建议**：config 落盘前对敏感字段整体用 Keystore 包裹加密（如 `Crypto.encrypt` 已具备的能力），密钥不可用时拒绝写敏感字段（与凭据库同款策略）。

### H3 · 自动填充密码 + 实时 TOTP 无站点白名单

- `AuthActivity.java:1036-1051 (maybeFill)`：参与判定的"登录页"特征仅靠 URL **子串**（含 `github.com/login`、`/sessions`、`two-factor`、`/signin`、`/register`、`linux.do/login` 等），**不校验 host**；
- 授权 WebView 对非回调导航不拦截，且 `addJavascriptInterface`(:501) 对一切加载页生效。

**后果**：授权流程中任何一个能诱导 WebView 跳转到 `https://<任意域>/signin` 的环节（GitHub 页内链接、站点重定向）都会让 App 把**明文密码与实时计算的 TOTP** 自动填入攻击者页面的表单。这是特洛伊级风险，尤其 TOTP 的有效期短于 30s、正好同屏利用。**建议**：`maybeFill` 增加 host allowlist（`github.com` / `linux.do` / `connect.linux.do` / 站点 `baseUrl` host 之四者）；外来导航全部弹系统浏览器（`shouldOverrideUrlLoading` 加白名单检查）。

### H4 · 签到 WebView 未绑定账号 Profile → 同站多账号串号

- `OffscreenCheckin.java:305-306` 与 `CheckinActivity`（onResult 内同款 `newTk.length()>20 && !equals(snapToken)` 分支）直接把 JS 返回的 **token 写回账号**，只验长度、不验身份；
- `CheckinJs.java:100-111 (pageToken)`：执行前会把 `localStorage["new-api:auth-session"]` 里的 token 采纳为本轮交易 token，**不做任何身份核对**；
- 两条签到链路（离屏与可见兜底）均用共享 **Default WebView 分区**（全文无任何 `WebViewProfileUtil` 调用），而 Default 分区的 localStorage 按 origin 共享 → 在本机若先跑 B 账号（或 B 曾登录过），A 账号签到时 `pageToken()` 会捡到 B 的 token，`checkin?month` 用 B 的身份返回，Java 再将 B 的 token 写进 A。

**后果**：同站多账号用户签到归属串号、token 互相覆盖，且最终用户端是静默的（只会发现额度/已签状态错乱）。对照 `AnyRouterCheckin`（绑定失败即中止）才是安全形态。**建议**：签到链路复用 `WebViewProfileUtil` 的 per-account Profile；`pageToken()` 采纳前先用 `/api/user/self` 核对 `id == siteUserId` 再写回，写回路径再过 `SessionFence.sameSecret` 快照对照。

---

## 🟡 中危（Medium）

### M1 · JS 桥回调缺同源校验（会话注入面）

- `AuthActivity.java:236 (onExchangeDone)` / `:305 (onOAuthBootstrap)` 只校验 `exchanging`/`bootstrapPending`，**不校验** `wv.getUrl()` 是否还在站点同源；对照 `:270`/`:300` 的 `onSelfResult/onSelfFailed` 是查的。
- 在 `exchanging` 期间被注入的页面可以用 `JustSign.onExchangeDone(200, fakeJson)` 把伪造 bundle 交给 `handleBundle`。

**缓解**：`handleBundle` 落库前有身份锚（站点返回 login 与期望凭据比对，见 `tools/test_identity_check.mjs` 复刻语义），伪造的成功包里只要 login 对不上就会被拒，可压低危害，但不能消弭（无二因子等待或 cookie 型路径锚点更弱）。**建议**：所有 JS 桥回调统一加 `sameOrigin(wv.getUrl(), baseUrl)` 闸。

### M2 · `ReauthManager.acquire` CAS 竞态

- `ReauthManager.java:37`：判定为"旧持有者不存在或已被标 null"才放入的做法，两线程同时看到 `null` 时会**都**返回 `true`——授权/重登的唯一性保证在并发下有窄窗口失效（如用户连点两个按钮且批量任务同时触发）。

**建议**：改为 `computeIfAbsent` + 原子比较，或用 `ConcurrentHashMap.putIfAbsent` 结果判定。

---

## 🟢 低危 / 规范问题（Low）

| # | 位置 | 问题 |
|---|------|------|
| L1 | `CheckinJs.java:83-84` vs `Engine.java:1437-1456` | "今日/本月"判定一边用设备本地时区（JS `new Date()`），一边用 `Asia/Shanghai`（Java）。设备时区≠北京时间时，凌晨时段会出现"A 边已签、B 边未签"的边界漂移。 |
| L2a | `Engine.java:88-110 switchToBackupProxy` | **死代码**：无任何 Java 调用方；`tools/test_429.mjs` 在复刻测试它——测试还活在，特性其实断了线，误导后续维护。 |
| L2b | `SilentAuth.java:85 githubLoggedIn()` | 同上死代码，且它查的是 **Default 分区**的 github.com Cookie，不是账号 Profile——若有人误调用会得到错误语义。建议一并删除或修复。 |
| L3 | `OffscreenLogout.java:148`、`OffscreenReauth.java:181` | `MIXED_CONTENT_COMPATIBILITY_MODE`：会话重建类 WebView 里放宽了 HTTPS，建议改 `MIXED_CONTENT_NEVER_ALLOW`。 |
| L4 | `AuthFillJs` | (a) 提交前会强制 `disabled=false` 再点击——站点有意禁用的按钮被绕过；(b) `dismissNotices` 会替用户自动点击"确认/确定/agree/accept"类按钮，属于未经明示的同意。建议显式日志记录每次自动点击。 |
| L5 | `KeyPanel.java`（复制 Key）、`SettingsView`（"测试 2FA" toast 出动态码） | 敏感值经剪贴板/toast 暴露给读屏与旁视。功能可用性权衡记录即可，建议在设置加"已读风险"开关。 |
| L6 | `MainActivity.java:1494-1502 accountHasCred` | 变量命名颠倒（`hasToken = ...isEmpty()`），逻辑结果正确，纯可读性问题，建议改名 `noToken`/`noCookie`。 |
| L7 | `OffscreenReadJs` body 截 12000 | 大 token 列表（>100 个 API Key）会截断使 JSON 不可解析 → 静默 unusable。功能上限，建议截断后显式标记并补一次分页。 |

---

## ⚖️ 政策一致性（README 声明 vs 实现）

| README 声明 | 代码核验 | 结论 |
|---|---|---|
| 不绕过人机验证码 | `CheckinActivity` 明示 Turnstile 交给用户手动完成，不伪造、不答题 ✓ | **一致** |
| 不批量注册账号 | 全文无注册自动化代码 ✓ | **一致** |
| —（未承诺） | `WafChallenge.java`：自动逆向求解阿里云 `acw_sc__v2` 反爬质询（固定置换表 + XOR，最多试 2 次）——这**是**对机器人检测的自动规避，只是不属"交互验证码"范畴 | **灰区，需明示** |

**建议**：README 加一句"自动处理站点前置的 JS 质询（WAF 透明挑战），以维持 API 可用性；用户人脸识别/交互验证码一律交人工"，并把该行为做成可关闭开关。另外 `acw_sc__v2` 算法硬编码，阿里云改版即失效，应加失败后的本地禁用降级。

## ✅ 验证后确认稳健的实现（节选）

- `Engine`：`opLock→refreshLock` 锁序一致、账号串行；刷新 cookie 一次性消费；`callWithAuth` 回退链（native → 2 次廉价 GET → 离屏读 → 仅 linuxdo 站离屏重授权）带 `allowReauth=false` 防回环；
- `SiteProtocol`：严格 host/port/userinfo 校验（读路径白名单仅 5 条 GET）、`[1-9][0-9]{0,14}` 数字 ID 校验、证书/TLS 错误永不重试；
- `OffscreenSelfProbe / OffscreenReadRunner / OffscreenReauth`：全程绑定账号 Profile；`expectId` 空即拒；写回 Cookie 前快照栅栏 + 身份匹配双闸；
- `AnyRouterSignInJs`：origin 钉死 `https://anyrouter.top`，自身份先核，单次 POST + Java 侧 `claimSignIn` CAS 防重复；
- `AuthFillJs` 参数注入走 `JSONObject.quote` 转义，无注入面；authorize 只点"允许"不点"拒绝"，且 `sessionStorage` 按 token 一次性；
- AnyRouter 奖励周期 `UTC 00:00 == 北京 08:00` 换算正确；`WorkManager` KEEP 策略 + 08:00 站内闸；
- 日志脱敏贯穿全栈（opLog 只落 host/path/布尔/状态码/指纹前 8 位）；
- `Crypto.available()==false` 时**拒绝明文落盘**（`SettingsView` 与 `Store.upsertCredential` 均已落实），取舍正确；
- `OAuthCallback.parse` 旧路径 + 跨路径 state 双重校验，`badState` 拒绝路径已测（`tools/OAuthCallbackTest.java`）。

---

## 修复优先级建议

1. **立刻**：C1（密钥轮换 + 历史清理）→ H1（CI 自动发布 release APK，先让 1.1.25 能推给老用户）；
2. **下一版本**：H3（填充白名单）→ H4（签到 Profile 绑定 + token 身份栅栏）→ H2（config 敏感字段加密，注意推出迁移路径）；
3. **顺手**：M1/M2/L2/L3（廉价修复）；
4. **文档**：政策灰区明示 + L5 风险提示。

## 真机测试计划（待用户授权与工具）

需要用户配合的工具与样本：
- `adb`（USB 或 `adb connect` 网络调试；需要全量 `shell`/`logcat`/安装权限）
- 一台能登录测试 GitHub/Linux DO 账号的 Android 8.0+ 手机
- **≥2 个 GitHub 小号** + 在同一个 New API 型站（如 AgentRouter）各建一个站点账号（验收 H4 串号修复）
- 一个"已启用站点"与"未启用站点"的对比会话，验证授权/补签链路

拟定用例（映射到本报告）：

| 用例 | 步骤 | 验收 | 关联 |
|---|---|---|---|
| T1 | 覆盖安装（老版本 → 新签名/旧签名） | 升级不丢数据；旧签名 APK 被拒绝（修复后） | C1 |
| T2 | 關联网络下点"检查新版本" | 能识别 v1.1.25 新 Release 并完成下载安装 | H1 |
| T3 | 授权流程中手动把 WebView 导航到 `https://<受害域>/signin` | maybeFill 不再注入密码/TOTP | H3 |
| T4 | 同站挂 A/B 两账号，依次签到 | A 的 token 不被 B 覆盖，账本各自独立 | H4 |
| T5 | 登出网络瞬时断开后重登 | 不落"半登出"新会话，不触发会话上限 | M2 / 会话上限 |
| T6 | 早晨 07:55–08:05 跨北京 8 点跨点签到 | 周期翻转一次且不重复扣签 | L1 |
| T7 | 有 2FA 账号登出→静默重登 | 全程零交互；TOTP 只进站点不回传 | 主链路 |
| T8 | 长时间闲置后批量刷新 5 个账号 | 令牌不复用/不爆破 429；日志无敏感信息 | 全链 |

审计未发现需要立刻做真机验证才能定级的项；是否进入真机测试阶段由用户决定。
