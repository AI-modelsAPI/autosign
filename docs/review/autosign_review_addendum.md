# AutoSign 审查补充：提交核对、最新提交精读、站点只读探测

前一份报告 `autosign_review_report.md` 是静态分析。本补充修正其证据等级，并加入两类实际证据：最新提交的精读，以及对各站点的只读探测。

## A. 提交核对

- 本地仓库已拉取完整历史（`git fetch --unshallow`）。
- `main` = `51a73cba66783073ae2c32587f1d26e68d99382c`（2026-10-20 18:13 +0800），与远端 `refs/heads/main` 一致。
- 仓库另有分支未审计：`fix/waf-bounded-solve-1.1.25`、`redesign-ui`、`arena/01a0da78-autosign`。
- 前一份报告中的行号基于 `51a73cb` 的当前文件，结论有效；但我没有对比各分支。

## B. 最新提交 `51a73cb`（SeekAI DOM 驱动签到）精读

提交声称：在 `/profile` 页面自动点击「立即签到」，通过 Turnstile 官方 DOM 流程过人机，并用 `fetch` 钩子捕获回执；挂载到 Activity 窗口（1080x2400）以保证 Turnstile 渲染。

### B1【已确认的代码缺陷】Activity 窗口挂载分支不可达
- `OffscreenCheckin.run(Context ctx0, …)` 第 57 行执行 `final Context app = ctx0.getApplicationContext();`，并把 `app` 传给 `Runner`（第 107 行）。
- `Runner.ctx` 因此永远是 `Application`，而挂载代码的判断是 `if (ctx instanceof Activity)`（第 184 行）。该条件**恒为假**。
- 结果：提交的主要功能（把 WebView 挂到 decorView 底层）在手动签到、定时签到等所有路径中都不会执行。`wv.layout(...)`、`onResume()`、`resumeTimers()` 仍会对一个未挂载的 WebView 执行。
- 影响：提交说明中“确保 Turnstile 动画帧与视口计算正常运行”这一前提未被满足。这是代码层面可确定的问题；Turnstile 是否因此失败，需要真机验证。

### B2【已确认】日志与诊断被删减，与前期提交的经验相悖
- 前一版 `onPageFinished` 注入探针 `probe-js-alive`，并为正式脚本挂了返回值回调，注释明确写着“真机白改数轮”。
- 本提交删除了探针与回调日志，只保留 `fired` 单次注入。之后若脚本静默失败，日志将无法区分“注入失败”与“脚本卡住”。

### B3【代码层面的风险，需真机验证】DOM 驱动签到的判定过宽
- `tryDomCheckin` 扫描页面上所有 `button, [role=button], a`，只要文本匹配 `/Checked\s*in|已签到|今日已签/i` 就直接判定 `already=true` 并调用 `finish(true, true, …)`，随后 `markChecked`，卡片标记为已签。
- 页面上任何包含“已签到”字样的按钮或链接（如统计文案、导航项）都会误判为已签。误判后，当日签到将被跳过，且本地徽章显示已签。
- `stateOf()` 仅在 `already` 分支中被调用，并未验证服务端的签到状态，这一点需要在真实 `/profile` 页面上核对。

### B4【代码层面的风险】fetch 钩子与页面脚本的时序
- 钩子在 `js()` 注入后安装。若站点页面的脚本已经缓存了原始 `fetch` 引用，钩子不会生效。`/api/user/checkin` 的回执捕获因此可能失效，只能依赖后面的 `stateOf()` 兜底。需真机验证。

### B5【仍然成立】签到路径与 Turnstile 要求不一致
- `SiteProfile.checkinNeedsTurnstile(Kind)` 只对 WELFARE 返回 true。但 `/api/status` 实测显示 `api.justwoker.icu`、`seekai.cc`、`kktoken.cc` 的 `turnstile_check` 均为 `true`。
- 也就是说，这些站点的 BUTTON 路径依赖运行时的 `NEEDCAP` 文案判定，而不是协议声明。这属于流程层面的不确定性，需要真实签到请求来确认。

## C. 站点只读探测（无账号、无鉴权、无签到动作）

探测方法：对每个站点请求 `/api/status`、`/api/user/self`，并对 `POST /api/user/checkin`、`POST /api/user/sign_in`、`POST /api/user/__nonexistent_probe__` 做阴性对照。UA 为代码中的 Chrome 155 Android UA。请求只能在未鉴权状态下发出，不会产生签到副作用。

| 站点 | `/api/status` | `GET /api/user/self` | `POST /api/user/checkin` | `POST /api/user/sign_in` | 不存在路径（对照） | 结论 |
|---|---|---|---|---|---|---|
| api.justwoker.icu | 200，`checkin_enabled=true`，`turnstile_check=true` | 401 | 401 | 404 | 404 | 端点存在，与代码“BUTTON”分类一致 |
| seekai.cc | 200，`checkin_enabled=true`，`turnstile_check=true` | 401 | 401 | 404 | 404 | 同上；Turnstile 要求与代码声明不一致（B5） |
| kktoken.cc | 200，`checkin_enabled=true`，`turnstile_check=true` | 401 | 401 | 404 | 404 | 同上 |
| gorouter.app | 502 | 502 | 502 | 502 | 502 | 上游不可用，无法验证 |
| agentrouter.org | 200 但是阿里云 WAF 挑战页 | 同左 | 同左 | 同左 | 同左 | **无法验证**：普通客户端被 WAF 拦截，所有路径返回相同挑战页 |
| anyrouter.top | 200 但是阿里云 WAF 挑战页（gzip 解压后为 `arg1` JS 挑战） | 同左 | 同左 | 同左 | 同左 | **无法验证**，同上 |

要点：
- 前三个站点的探测结果与代码中的协议表一致。
- AgentRouter 与 AnyRouter 的“POST /api/user/checkin 为 404、sign_in 才是入口”等结论，**我这里无法复现**。代码注释称它们是在真实浏览器上下文中实测的，我无法判断其正确性。
- 这意味着对 AgentRouter / AnyRouter 的流程判断，目前没有独立证据。

## D. 对前一份报告结论的证据等级重新标注

| 报告条目 | 证据等级 | 说明 |
|---|---|---|
| B1 登出未确认仍重登 | 代码事实 | 注释与代码矛盾，`mayProceed` 未被调用，已在源码中确认 |
| D1–D8 重复分支 | 代码事实 | 已读源码确认 |
| B6 WAF 503 触发静默重授权 | 代码事实 | 逻辑路径确定；实际出现频率需日志 |
| B7 轮换 Cookie 在失败响应中丢弃 | 代码事实 | 代码确定会丢弃；服务端是否在失败时轮换，需实测 |
| B2 AgentRouter 登出不销毁会话 | **未验证** | 仅来自代码注释；本次无法复现 |
| B3 陈旧余额基线导致误判 | 代码事实 + 假设 | 基线取缓存是确定的；误判幅度取决于同期消费 |
| B4 15 美元阈值 | 假设 | 需站点数据 |
| B5 每次登录都发奖 | **未验证** | 需站点实测 |
| B9 handleBundle NPE | 代码事实（可达性未知） | 需确认 `data:null` 响应是否出现 |
| B10 定时重登统一用 SilentAuth | 未验证 | 未确认 SilentAuth 是否处理 linuxdo |
| S1 签名密钥在仓库 | 事实 | 已确认仓库内有 `justsign-debug.jks` 及口令 |
| S2 凭据明文 | 代码事实 | 已确认仅 password/twofa 加密；攻击可行性未验证 |
| S4 JS 桥暴露 | 代码事实 | 可利用性未验证 |
| 测试失败 | 事实 | 已运行 |

## E. 仍需真机 / 账号才能判定的问题

1. SeekAI `/profile` 的 DOM 点击是否能过 Turnstile，以及窗口未挂载是否导致渲染失败（B1）。
2. `tryDomCheckin` 是否会在真实页面误判“已签到”（B3）。
3. AgentRouter / AnyRouter 的签到入口与登出语义（需要通过 WAF 的浏览器环境与授权账号）。
4. 登出后重登是否发奖、余额增量是否可靠（B3–B5）。

按你的授权范围，以上第 3、4 项只能用授权账号在本地执行，我可以提供脚本与日志清单；第 1、2 项需要在真机上观察日志。
