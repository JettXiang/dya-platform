# dyaccount v1.3.3 代码复核质询 —— 答复

> 质询来源：另一台机器复核代码的 AI 同事《代码复核质询清单-发开发AI.md》
> 答复依据：对 `C:\Users\dataeye\Desktop\dyaccount_v1.3.3` 实际代码逐条核对（不凭记忆，凡判定都给了代码位置）
> 日期：2026-10-20

---

## 一、结论速览

| 条目 | 判定 | 说明 | 处理 |
|---|---|---|---|
| A1 随机刷新「全员互刷」 | **主论点误读**；附带 bug 成立 | 扩展只刷自己那一页，不误伤别人；计数会丢属实，已用独立存储键根治 | ✅ 已修计数 |
| A2 `intervalMs: 2800` 与文档矛盾 | **成立** | background 默认值与 content/popup/文档都不一致 | ✅ 已改 4000 |
| A3 `extVersion` 硬编码 | **成立** | 每次发版都要手改 | ✅ 已改动态取 |
| A4 上传无补偿 / `\|\| 0` 静默 | **成立** | 确实没有待发队列，缺字段不报错 | ⏸ 待议 |
| A5 popup 与 content 重复实现 | **成立，且比原清单更多** | popup 缺的不止 `autoSortLatest` | ⏸ 待议 |
| B1 HTTP 裸 IP 无鉴权 | **事实** | 扩展侧单方面改不了 | ⏸ 需服务端配合 |
| B2「纯被动不主动」口径站不住 | **成立** | 确有点击/滚动模拟 | ⏸ 属文档措辞 |
| B3 对抖音 DOM 硬耦合 | **成立** | 真实工程债 | ⏸ 长期 |
| C1~C4 工程债 | **成立** | 未逐条核 | ⏸ 长期 |

**一句话总结**：这份清单整体质量不错，B/C 段基本是事实。但被标为「最重要」的 A1，主论点是误读——若据此去改（leader 独占刷新），只会引入不必要的复杂度。真正该修的是它附带提到的那个计数问题。

---

## 二、A 段逐条

### A1 随机刷新会「全员互刷」 —— 主论点误读，附带 bug 成立

**（1）主论点不成立。** 它把「避免误伤别人」读成了「只能刷一个页面」。

- 扩展实现走 `background.js` 的 `reloadTab` 分支：

  ```js
  chrome.tabs.reload(sender.tab.id, { bypassCache: true });
  ```

  **只刷新发起计时的那一个标签页自己**，语义等价对该页 Ctrl+F5，页面的刷新逻辑本身也不带页面浏览器对焦。

- 同事那个 exe 的问题是：对**当前前台窗口**盲发 Ctrl+F5，会刷到当时激活的任意窗口（微信、别的浏览器窗口）。

- 所以扩展实现解决的正是「误伤其它窗口」这个核心痛点，`目标已达成`。「多个采集页各自刷新自己」是「每个采集页都需要周期性刷新」的正常结果，不是"互刷"。

**（2）附带 bug 成立。** 原实现里 `refreshCount` 存在 `settings` 里，而 `persist()` 是整包写回：

```js
chrome.storage.local.set({ settings, authors, stats, [PUBLISH_LOG_KEY]: publishLog });
```

多标签页并发 `refreshCount + 1` 再整包写，会 last-writer-wins，「已刷 N 次」会丢。（只影响展示，不影响采集。）

**（3）「leader 独占 + background 记账」这个建议——记账采纳，独占不采纳。**
- 实际使用是单采集页，多页并发只影响一个展示用数字；
- 引入 leader 仲裁（leader 挂了怎么办、切换页面时怎么迁移）的复杂度远大于收益。

**本轮处理**：保留「每页各自计时」，把计数收敛成 background 单点记账，并**改用独立存储键 `refreshCount`**。

> **为什么最后用独立键**：第一版修法是「让 `persist()` 写 settings 前剥离 `refreshCount` 字段」，实机实测发现它不成立——`storage.local.set({ settings })` 是**整体替换**，「剥离」等于把字段从存储里直接抹掉。只要采集流程触发一次 `persist()`（1.5 秒防抖，采集期间很频繁），计数就归零；手动「立即刷新一次」之后也会归零。改成 `refreshCount` 独立键后，`settings` 怎么整包写都碰不到它，background 是唯一写方，问题根治。

---

### A2 `onInstalled` 默认 `intervalMs: 2800` —— 成立

- `background.js`（原第 902 行）写入 2800，与 content.js / popup.js 的 `DEFAULT_SETTINGS`（4000）以及 README、交接文档里「4 秒起步」互相打架。
- 靠 content 侧「小于 4000 钳到 4000」兜底才没出事故，但配置源与文档冲突属实。
- **本轮处理**：改为 4000。

---

### A3 `extVersion` 手工同步 —— 成立

- `background.js` 事件台账里的 `extVersion` 是硬编码字符串，1.3.0 → 1.3.3 每次发版都要手改（本次升级时就刚手工改过一次）。
- **本轮处理**：改为 `chrome.runtime.getManifest().version`，一行根治版本号漂移。

---

### A4 上传无失败补偿、服务端字段静默容错 —— 成立

- `Number(rec.inserted_count) || 0`、`Number(rec.remote_duplicate_count) || 0`：服务端字段一旦改名，统计会无声归零，污染「上传记录表.xlsx」。
- `uploadSecUids()` 失败路径只返回提示文案，**没有本地待发队列 / 自动补传**，操作员忘了补就丢掉一批。
- **待议**：① 服务端响应 schema 有没有文档？② 缺字段时该显式报错还是保持 `|| 0`？③ 要不要做失败批次暂存 + 下次打开提示补传？

---

### A5 popup 与 content 重复实现 —— 成立，且比原清单更多

- popup 的 `DEFAULT_SETTINGS` 不只缺 `autoSortLatest`，还缺 `viewNewOnly`、`switchSearchMinMs`（对比 content.js 的版本）。
- 本轮新加的 `clampRefreshSec()` 也确实在 popup.js 与 content.js 各写了一份。
- 导出选择器 / `toCsv` / `toPublishCsv` / `selectAuthors` 本来就是双份。
- **待议**：最低成本先抽三五个重复函数成 `shared.js`，还是保持双份（popup 定位是「遥控端只读写 storage」）？

---

## 三、B 段（安全 / 风险）

### B1 明文 HTTP + 裸 IP、无鉴权无防重放 —— 事实

- `manifest.json` 的 host_permissions 与 `background.js` 的 `UPLOAD_ENDPOINT` 同为 `http://43.165.183.142/...`，POST 无 token、无签名。
- `sec_uid` 清单和 `uploader_id` 在网络层是明文；任何知道地址的人都能伪造提交污染 Redis 池；服务端 IP 一变全组失效。
- **扩展侧改不了端点**（由服务端同事管）。扩展侧能做的准备：把端点抽成可配置项，便于将来切 HTTPS / 域名。
- **需带教/服务端确认**：能不能推动 HTTPS + token。

### B2「纯被动、不主动」的文档口径站不住 —— 成立

- 代码里确有：`realClick()`（派发完整 pointer/mouse 事件序列）、`clickableByText` + `applyLatestSort`（主动点「筛选 / 最新发布」）、`harvestRelatedSearches`（主动扫 DOM）、`autoScrollDown`（合成 WheelEvent）。
- 这些确实不发额外网络请求，但属于**模拟用户交互**。风控归因排查时，「不主动」的说法会误导。
- **建议**：文档措辞改为「不发额外网络请求，但会模拟页面点击/滚动」。

### B3 对抖音 DOM 的硬耦合面大、失效静默 —— 成立

- 硬编码 class/文案散落各处：`semi-tabs-tab`、「相关搜索」、「筛选」、「加载更多」、`xgplayer-playswitch-next`、栏目名等。
- 抖音前端一改版，下滑 / 换词 / 排序 / 收割词会全哑，而 guard 台账只能间接暴露（90 秒无数据），分不清「风控」还是「改版」。
- **建议**：短期把选择器/文案收进一张「适配表」常量；中期给关键 DOM 依赖加「控件连续 N 轮找不到」的守卫上报类型。

---

## 四、C 段（工程债，列出，不要求立刻动）

| # | 判定 | 备注 |
|---|---|---|
| C1 | 成立 | content.js 单文件过大，可拆 `ui / browse / guard / export` |
| C2 | 成立 | 发布目录混有 `.bak_*` / `.lnk` / exe（这也是打包要按清单过滤的由来） |
| C3 | 成立 | 零测试；`parse.js` 是纯函数，最适合用录制的真实接口 JSON 做 fixture 回归；`refreshRange()` 边界（min>max 交换、30~3600 夹取）值得断言 |
| C4 | 成立 | 巡检性能：每 2.5s 全页 `innerText`、`harvestRelatedSearches` / `clickableByText` 全量扫描 |

---

## 五、回答原清单的三个优先问题

1. **A1**：多标签页各自起表刷新**不是漏洞**（每页只刷自己，不误伤别的窗口）；真 bug 只有计数。**leader 独占不建议做。**
2. **A2 / A3**：同意改，各一行 —— **本轮已改**。
3. **B1**：不能单方面推动，需要服务端配合；扩展侧可先把 `UPLOAD_ENDPOINT` 抽成可配置项。

---

## 六、本轮实际改动（打在 v1.3.3 上的修补）

| 文件 | 位置 | 改动 |
|---|---|---|
| `background.js` | 事件台账 | `extVersion: "1.3.3"` → `chrome.runtime.getManifest().version` |
| `background.js` | `onInstalled` 默认设置 | `intervalMs: 2800` → `4000` |
| `background.js` | `reloadTab` 分支 | `count:true` 时在**独立键** `refreshCount` 上单点累加（不再写进 settings） |
| `content.js` | 新增全局变量 | `let refreshCount`，由 `loadState()` 从独立键读入，`storage.onChanged` 跟随更新 |
| `content.js` | `loadState()` | 读取键列表加入 `"refreshCount"` |
| `content.js` | `updateRefreshText()` | 显示改用独立键值（不再读 `settings.refreshCount`） |
| `content.js` | `persist()` | 恢复整包写 settings（独立键不受影响，无需再剥离字段） |
| `content.js` | `reloadCurrentTab()` | 不再本地自增，改发 `{type:"reloadTab", count:!!countIt}` |
| `popup.js` | `DEFAULT_SETTINGS` / `load()` / `render()` / `refreshStatusText()` | 移除 `refreshCount`，改为读独立键 |

**计数语义**：「已刷 N 次」由 background 单点维护（写独立键 `refreshCount`），值经 `storage.onChanged` 同步回页面浮层与弹窗；自动刷新才累加，手动「立即刷新一次」不计入。

**实机验证**：改独立键**之前**的一版实测——自动刷新 `已刷 0 → 1` 生效、手动刷新不计入（没 +1），但手动刷新后计数被清零，真因即上文的「整包替换抹字段」。改独立键后已重新打包 `dyaccount_v1.3.3.zip`（35 条目、manifest 1.3.3）。

---

## 七、仍未处理（交给你 / 带教决定）

A4（上传失败补偿 + 字段容错口径）、A5（抽 `shared.js`）、B1（服务端 HTTPS/token）、B2（改文档措辞）、B3（适配表 + 改版上报）、C1~C4（长期）。
