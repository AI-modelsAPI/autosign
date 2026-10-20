# AutoSign 代码审查报告（签到 / 刷新 / 重登 / 安全）

审查对象：`https://github.com/AI-modelsAPI/autosign`（main，克隆于 2026-10-20）
审查方式：静态通读 + 关键路径精读 + 仓库自带脚本测试。**未对任何真实站点发起请求，未使用任何账号。**

## 0. 覆盖范围（如实说明）

| 范围 | 状态 |
|---|---|
| `SiteProtocol.java`、`SiteProfile.java`、`Crypto.java`、`Catalog.java` | 全文精读 |
| `Engine.java`（2808 行） | 精读约 1300 行：HTTP/鉴权/续期/登出(1–960)、签到与重登(1887–2360)、定时调度(2565–2745) |
| `AuthActivity`、`Store`、`SilentAuth`、`MainActivity`、`Offscreen*`、`UpdateChecker`、`OAuthCallback` | 仅 grep 扫描 + 关键点抽查，**未逐行精读** |
| `AndroidManifest.xml`、`build.gradle`、CI workflow | 全文 |
| `tools/test_*.mjs` | 实际运行（见第 5 节） |
| Java 单元测试（`android/app/src/test`） | **未运行**（沙箱无 JDK/Gradle） |

因此本报告**不是“全量精读”的完整结论**，结论范围限于上表已读部分。其余模块需要后续继续审。

---

## 1. 重复执行的分支（冗余 / 易分叉）

| # | 位置 | 问题 | 风险 |
|---|---|---|---|
| D1 | `Engine.reloginCheckin`（~2034）与 `Engine.anyRouterReloginLocked`（~2177） | 两份几乎相同的“时间门禁→回执→签到前余额→登出→重登→余额增量判据→写回执”流程。前者按 provider 分流，后者只走 OffscreenReauth。 | 两处修一处漏一处，已经出现行为分叉（见 B1）。 |
| D2 | `runScheduled` 的重登分支（~2640–2700） | 第三份“登出→重登→核验”实现，核验依据是 `status().todayChecked`，而不是余额增量。 | 同一站点手动与定时的“到账”判据不一致。 |
| D3 | `checkinLocked` | “已签”分支写了两次（~36–46 与 ~48–62），`checkinStatus()` 被调用两次（~37 与 ~108）。 | 多余网络请求；维护时易漏改。 |
| D4 | `runScheduled` 中构造 `lastCheckin` 补丁的代码（三处）+ `MainActivity.buildStatusPatch` | 同一套“受保护的已签落库”逻辑被复制了 4 次。 | 规则漂移，比如 AnyRouter 回执保护只在其中一处生效。 |
| D5 | HTTP 头构造：`refreshAccessToken`、`httpGetJson`、`attempt` | 三份浏览器请求头模板。 | 修改 UA/头部时需改三处。 |
| D6 | `SiteProfile.mainSiteUrl` 第 218–219 行 | `else if` 分支条件与上一行完全相同，第二个分支是**不可达死代码**。 | 可读性差，且暴露了拼写问题（见 C7）。 |
| D7 | `SiteProfile.checkinActionPath(JSONObject)` 与 `checkinActionPath(Kind)` | 前者对 agentrouter 返回 `""`，后者对 NEWAPI 一律返回 `/api/user/checkin`。 | 调用方若误用 `Kind` 重载，会对 agentrouter 发出注定 404 的 POST。 |
| D8 | `Engine.callInternal` 第 110–114 行 | `try { siteUserId = accSiteUserId } catch` 无实际异常来源，属空转代码。 | 低。 |

---

## 2. 逻辑缺陷（按影响排序）

### B1【高】重登在“登出未确认”时仍继续建立新会话
- 位置：`reloginCheckin`（~2060–2070）与 `anyRouterReloginLocked`（~2145–2152）。
- 代码注释写着“登出必须确认，否则不建新会话，防会话数超限”，但两处只把 `loggedOut` 记入日志，**没有调用 `ReauthManager.mayProceed(lo)` 做门禁**，直接进入 `OffscreenReauth.attempt` / `SilentAuth.run`。
- 对照：`runScheduled`（~2653）与 `MainActivity`（~961、~1743）**正确地**使用了 `mayProceed`。
- 影响：登出为 `UNCONFIRMED`（网络超时/429/5xx）或 `DENIED` 时，仍会登录，可能触发站点会话数上限（代码中已存在 `AUTH_SESSION_LIMIT` 409 处理，说明这是实际会遇到的情况）。
- 修复：两处统一加 `if (!ReauthManager.mayProceed(lo)) return 未确认结果;`。

### B2【高】“登出确认”对部分站点语义不成立
- `decideLogout` 对 `success:true` 直接返回 `CONFIRMED`（~181）。但代码注释自己承认：AgentRouter 的 logout 返回 `success:true` 时**既不作废 token 也不作废 session**。
- 即门禁 B1 的“确认”含金量取决于站点；在这类站点上它只是“站点没报错”。
- 修复方向：对 New API 系用“登出后 `/api/user/self` 应返回 401”做二次核验（`logoutSessionLocked` 原来有这一步，因 AgentRouter 而被删除）。应按站点配置选择核验方式，而不是一刀切。

### B3【高】重登到账判据的“余额基线”可能是陈旧缓存
- `anyRouterBalance(key, false)`（~2255）优先读卡片缓存 `lastStatus.availableUSD`，它可能是几小时前的值。
- 基线错误会导致两种后果：
  - 基线偏低 → 增量被高估 → **误判为到账 → 调用 `anyRouterWriteReceipt`**，当周期其余重试全部被 `hasSignInReceipt` 跳过（见 B5）。
  - 基线偏高（期间有消费）→ 增量被低估 → 误判未到账，用户会重复重登。
- 修复：重登前的基线必须 `fresh=true` 读取（多一次请求，成本可接受）。

### B4【中】到账阈值 `delta > 15` 是经验常数
- 余额增量受同期消费影响。阈值本身用于抵消消费，但在 AnyRouter 奖励 $25、消费变化大时会误判。
- 修复：优先用站点的**明文流水**作证据（AgentRouter 路径已有 `type=4` 系统流水解析，AnyRouter 可考虑同样做，若站点提供）；余额增量只作兜底，并记录阈值依据。

### B5【中】本周期回执的判据依赖“任何一次 08:00 后的成功登录都会发奖”
- `SiteProtocol.hasSignInReceipt`：`lastLogin ∈ [北京 08:00, now]` 即视为本周期已签（~164）。
- 而 `lastLogin` 会被多个路径写入：`OffscreenReauth.java:335`、`AuthActivity.java:1512`、`anyRouterWriteReceipt`。也就是说，**任何一次 08:00 后的重新授权（包括因 503/WAF 触发的静默重授权，见 B6）都会让定时重登被跳过**。
- 只有在“站点确实每次登录都发奖”时这才正确，这一点需要用站点实测确认（代码注释里有“登录即发”的说法，但没有区分“授权”与“重登”）。
- 修复：`lastLogin` 与“到账回执”分开存；回执只在余额增量或流水证实到账时写。

### B6【高】普通刷新会因 WAF 503 触发静默重授权（违反文档承诺）
- `callWithAuth`（~104–126）：`sessionLikelyDead` 把 `401 / 403 / 503 / WAF 页` 都视为“会话失效”，对 linuxdo 账号调用 `OffscreenReauth.attempt(...)`，即**在后台再走一次登录页**。
- 但 `sessionLikelyDead` 在 `callWithAuth` 的中间层仍然会在 WAF 503 时命中，而 WAF 503 本身并不说明会话失效（代码其他位置也这样写：“瞬时 WAF-503…会话明明活着”）。
- 与 README 承诺“刷新、签到、定时任务绝不触发 GitHub OAuth”不一致；至少对 linuxdo 站点，**刷新会隐式登录**。
- 另外 `rewardSite` 变量（~106）被计算、写日志，但**未参与决策**，注释中“今日未签时绝不保活”的保护已经失效（代码注释的前提已被删除，却保留了注释）。
- 修复：`sessionLikelyDead` 只认 401/403；WAF/503 只走 WAF 求解与缓存放行路径；重写注释使其与行为一致。

### B7【高】续期时轮换后的 Refresh Cookie 在失败响应中被丢弃
- `refreshAccessToken`：`rotated = mergeCookies(cookie, Set-Cookie)` 已经算出，但只有当 `http == 200 && success && newToken 非空` 时才 `patchAccount`（~449–453）。
- 若服务端**已经轮换了 `new_api_refresh`** 却返回非 200 或 `success:false`，新 Cookie 被丢弃，旧 Cookie 已失效 → 账号只能重登。
- 修复：只要响应含 `Set-Cookie: new_api_refresh=…`，无论业务成功与否都应先落库轮换后的 Cookie。

### B8【中】POST 写操作遇到 401 不会刷新令牌
- `callWithAuthNative`（~179）：401 重试续期的条件含 `ReadFallback.allowed(method, path)`，即**只对安全 GET 续期**。
- 写操作（签到 POST、划转、Key 创建/删除）只依赖事前的 `SilentAuth.needsExchange`（JWT 过期估算）。估算偏差时，写请求直接 401 → 进入重登链。
- 修复：幂等写（如 `/api/user/checkin`）在 401 时也可续期后重试一次；非幂等写保持原样但需记录。

### B9【中】`handleBundle` 可能对 cookie 型站点的 `data:null` 空指针
- `AuthActivity.java:1038` 允许 `d == null`（条件 `cookieProvider && !setCookie.isEmpty()`），但 `1043` 行直接 `d.has(k)`。
- 该异常被外层 `catch (Exception)`（~1084）吞掉，结果是**凭据没有保存且无明确报错**。需用真实响应复核是否可达。

### B10【中】定时重登统一用 SilentAuth，与手动路径按 provider 分流不一致
- `runScheduled`（~2650–2668）对所有重登站一律调 `SilentAuth.run`；`reloginCheckin` 则 linuxdo 走 `OffscreenReauth`。
- 若 `SilentAuth` 不处理 linuxdo，定时任务会在 linuxdo 账号上失败；需核对 `SilentAuth` 内部是否按 provider 分支（未逐行确认）。

### B11【低】金额单位启发式
- `raw >= 1000 ? raw / unit : raw`（`checkinLocked` ~103、`quotaToUSD` ~79）：把小于 1000 的 quota 当作美元。这只是展示层误差，但应改为基于站点 `quotaPerUnit` 的确定换算，或明确标注“未知单位”。

### B12【低】`anyRouterPostSignIn` 的成功判据默认为真
- `d.optBoolean("success", true)`：响应无 `success` 字段时仍判成功。只影响日志，但会污染“sign_in 已发送”的统计。

### B13【低】`checkinLocked` 的“已签”文本匹配可能误标
- 当 `alower.contains("已签到")` 时，即使 `success=false` 也会 `markChecked`。应要求 `success` 或结构化字段，而不是仅靠文案。

---

## 3. 安全问题

### S1【高】签名密钥库与口令提交在公开仓库，且 release 用同一把密钥
- `android/app/justsign-debug.jks`，`storePassword/keyPassword 'justsign'` 直接写在 `build.gradle`。
- release 构建也使用 `signingConfigs.shared`（同一把公开密钥）。
- 影响：任何人都能用该密钥签出“同包名、可通过签名校验”的 APK。`UpdateChecker.verifyApk` 的签名比对在这种情况下失去防伪意义。
- 修复：立即轮换签名密钥；密钥与口令放入 CI Secrets；从仓库历史中移除（历史中仍可见，因此必须轮换而不是只删文件）。

### S2【高】凭据明文落盘（仅密码/2FA 加密）
- `Crypto` 只用于 `password` 与 `twofa`（`Store.java` ~678–735）。
- 以下字段**明文**保存在 SharedPreferences：`token`、`siteCookie`（含长期 `new_api_refresh`，可用约一个月）、`systemToken`、`welfareToken`、WAF 放行 Cookie。
- CI 产出的是 **debug 包**（可 `run-as` 读取应用数据）。见 S3。
- 影响：拿到设备上的 prefs 即可直接接管站点会话，绕过全部“加密”承诺。
- 注意：README 写的是“密码和 TOTP 使用 Keystore 加密”，并未承诺令牌加密，但“凭据只保存在用户设备中”的表述容易让用户误以为全部凭据都受保护。
- 修复：同一 Keystore 方案加密 `token`、`siteCookie`、`systemToken`、`welfareToken`；并在文档中明确哪些字段受保护。

### S3【高】分发的是 debug 构建
- `.github/workflows/android-apk.yml` 只执行 `assembleDebug`，且 `build.gradle` 中 `debug` 构建是默认可调试的。
- 影响：可调试包允许 `run-as`，直接放大 S2。
- 修复：发布 `release` 构建（`minifyEnabled`/`shrinkResources` 已配置），并确认 `debuggable=false`。

### S4【中】JS 桥暴露给页面所有 frame
- `addJavascriptInterface(new Bridge(), "JustSign")` 挂在会加载第三方页面（GitHub、linux.do、站点页）的 WebView 上。
- `AuthActivity.Bridge.onSession(json)`、`onExchangeDone(...)` 等方法接受任意字符串，门禁只是 `isAuthInactive()` / `exchanging` 状态位，**没有校验调用来源 URL**。
- 注意：`@JavascriptInterface` 在 API ≥ 17 下只允许被注解的方法，但**任何被加载页面的脚本**都能调用它们，包括站点自身的第三方脚本。
- 修复：用 `WebViewCompat.addWebMessageListener` + `allowedOriginRules`（项目已依赖 `androidx.webkit:1.12.1`），只对白名单源开放；或在每个 Bridge 方法里校验 `view.getUrl()` 的 host，并使用一次性 nonce（`OffscreenReadRunner` 已有 nonce，可推广）。

### S5【中】用户输入的 `baseUrl` 决定 Bearer/Cookie 的发送目标
- 请求会把 `Authorization` 与 `Cookie` 发往用户配置的 `baseUrl`。未在本次阅读中确认是否强制 `https://`。若允许 `http://`，凭据会明文传输。
- 修复：统一校验为 `https`，并拒绝带 userinfo 的 URL。

### S6【低】正面发现（值得保留）
- `OAuthCallback` 对 `state` 做严格匹配、同域校验，并避免在日志中记录 code/state 值。
- `attempt`、`httpGetJson` 日志不记录 token/cookie 值。
- `UpdateChecker` 对 APK 做 SHA-256 签名比对，URL 白名单为 GitHub Releases 的固定格式。
- `Crypto` 的失败路径返回 `null`，调用方不回退明文（设计正确）。

---

## 4. 签到 / 刷新 / 重登：精准化建议

以下建议基于代码中已有的**实测事实**（`SiteProfile` 注释里的 POST 阴性对照、AgentRouter 的 logout 语义、AnyRouter 的 sign_in 行为），而不是新的推测。

### 4.1 统一为一个状态机，三处入口共用
把 `reloginCheckin`、`anyRouterReloginLocked`、`runScheduled` 的重登分支合并为一个 `ReloginFlow`：

1. **门禁**：北京 08:00 之后；本周期无“真实到账”回执（不是无 `lastLogin`）。
2. **基线**：`fresh=true` 读取余额（或站点流水）。
3. **登出**：`logoutSession`；仅 `ReauthManager.mayProceed(lo)` 为真时继续，否则返回 `UNCONFIRMED`，不建会话。
4. **登入**：按 provider 分流（linuxdo → OffscreenReauth，github → SilentAuth），并校验身份 ID（`OffscreenReauth` 已有 `expectId` 校验，应保留）。
5. **核验**：`fresh` 余额增量 > 阈值，或站点流水出现当日系统类记录。
6. **落库**：仅核验通过才写回执；未通过只记 `unverified`，**不写“已签”**。

### 4.2 刷新链的错误分级
- `401/403`：可续期（Refresh Cookie）一次。
- `503 / WAF`：只走 WAF 求解 + 缓存放行，**不进入重授权**。
- 网络异常：不做任何状态变更。
- `success:false` 但有 `Set-Cookie`：先落库轮换后的 Cookie（修 B7），再判定业务结果。

### 4.3 登出确认按站点区分
- New API 标准站：登出后 `/api/user/self` 应为 401 才算“已确认”。
- AgentRouter 类（logout 只返回 `success:true`）：仅作“站点已接受”信号，**不得当作会话已销毁**，需依赖 4.1 第 5 步的到账核验来兜底。

### 4.4 站点实测验证（需要你的账号与授权，我未执行）
我没有对真实站点发起请求，也不建议在未经你明确授权的情况下对你的账号做自动签到/登出/重登测试。若需要验证，可按以下清单执行，每个站点只用一个测试账号、在北京时间 08:00 之后单次执行，并保留日志：

- 记录：签到前 `fresh` 余额、登出响应（HTTP + body）、登出后 `/api/user/self` 状态码、重登后余额、流水中当日系统类记录。
- 对比：B2 中的“登出是否真的使会话失效”、B5 中的“每次登录是否都发奖”。

---

## 5. 仓库自带测试的实际运行结果

在沙箱中（Node v20.20.2）执行 `tools/test_*.mjs`：

| 测试 | 结果 | 说明 |
|---|---|---|
| test_429 / test_bonuslog / test_identity_check / test_loginsign / test_silentauth / test_v024 | 通过（6 个文件） | — |
| test_authfill.mjs | **失败 3 项** | 等待时间未固定 700ms；未点击登录；提交被 captcha 中止时的回传结构不符 |
| test_authprobe.mjs | **失败 3 项** | 测试期望“New API → /api/me”共 2 次尝试，实际探测顺序为 3 步（含 `/api/v1/auth/me`）。测试与代码至少有一方过期 |
| test_readjs_behavior.mjs | **失败 1 项** | 期望 `waf-not-cleared`，实际 `waf-need-navigate`。与 `OffscreenReadJs` 的行为不一致 |

说明：两个“ENOENT”类失败是因为缺少 `/tmp/jsverify` 中间产物，已用仓库的 `tools/extract_js.py` 生成后重跑，剩余失败为真实断言失败。

**Java 单元测试未运行**（需要 JDK 17 与 Gradle 8.7）。`ReauthManagerLockTest`、`CheckinJsTimeoutTest` 等应在具备环境时补跑。

---

## 6. 优先级建议

1. **立即**：轮换签名密钥并从仓库移除（S1）；发布 release 而非 debug（S3）。
2. **本周**：B1（登出门禁）、B7（轮换 Cookie 落库）、B6（WAF 不触发重授权）、B3（fresh 基线）。
3. **随后**：加密 token/cookie（S2）；JS 桥来源校验（S4）；合并三处重登实现（D1/D2）。
4. **测试**：修复 3 个失败的脚本测试，决定以代码还是测试为准；补跑 Java 单测。

如需继续，我可以：
- 把第 4.1 节的 `ReloginFlow` 写成具体的 Java 改动草案（不直接提交到你的仓库，除非你授权）；
- 继续逐行精读 `Store`、`SilentAuth`、`AuthActivity`、`MainActivity` 剩余部分，把 B9/B10 等“需复核”项确认下来。
