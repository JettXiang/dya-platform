# P3-b（B3-b 解析字段别名收拢 + page-hook 正则评估）· diff 预览 v1

> 状态：**只读扫描完成，源码一行未改、目录未复制**。
> 扫描对象：`C:\Users\dataeye\Desktop\dyaccount_v1.3.3-p3a`（P3-a 已合入、复核端判通过的那份）。
> 本稿范围按已确认口径：①`pick()` 的 9 组别名并入 `adapter.js`；②page-hook 正则**只评估不改**；③约 17 处「双键直读」**不纳入**；④动工时复制 `dyaccount_v1.3.3-p3b`。

## 0. 校验基准

| 文件 | 字节 | md5 | 本批是否改 |
|---|---|---|---|
| `content/adapter.js` | 4,521 | `2b8583ef8cda64e2895dac32c6737389` | **改**（新增 1 键） |
| `content/parse.js` | 8,448 | `10ebf8d1554f12f4fc1b868408c2b861` | **改**（9 处替换 + 新增 4 行） |
| `content/content.js` | 90,978 | `042fe5ed749b4cfa020faaa835d2a2e0` | 不动 |
| `content/page-hook.js` | 3,181 | `099e3b20f45fc079d40679ec2e00833b` | 不动（只评估） |
| `manifest.json` | 1,355 | `7fb0d3ee0fe2e52dc44217a83664b9e4` | 不动 |
| `shared.js` | 7,251 | `0ccc70f889eac7bca75e1517bb54b724` | 不动（P2a 验收锚点，不许碰） |
| `tests/adapter.test.js` | 14,085 | `6def4f56879fb5a8c7cabf9a9f772b59` | 改（随包外） |
| `tests/parse.test.js` | 10,205 | `fecbf55a901039a45913092caf5e9177` | 改（随包外） |
| `tests/shared.bydev.test.js` | 12,108 | `fe69b29a9b796f8d1cfb41b3274f58b8` | 不动 |

行号一律以本表为准，全部为**改前**行号；`parse.js` 改后行号 = 改前行号 **+4**（见 §3.1）。

## 1. 结论摘要

| 项 | 数量 |
|---|---|
| `pick()` 别名组 | **9 组**，分布在 `parse.js` 9 行 |
| `adapter.js` 键数 | 36 → **37**（新增 `PICK_ALIAS`） |
| 顶层数组键 | 仍 **10**（`PICK_ALIAS` 是对象，内含 9 个各再冻结的子数组） |
| `parse.js` 变动 | 替换 **9 处 / 9 行**；新增 **4 行**（3 行注释 + 1 行赋值） |
| 包内文件变动 | **2 个**（`adapter.js`、`parse.js`）；manifest / content.js / page-hook / shared 全不动 |
| page-hook 正则 | **4 条 / 3 行**，本批**只评估不改**（§7） |
| 「双键直读」 | **17 处**（§5.1 表内 15 个条目，47–53 判定链含 3 处），明确**不纳入** |
| 行为变化 | **预期 0**（纯字面量搬家；fixtures 回归全绿即证） |

## 2. 入表清单（PICK_ALIAS · 9 组）

新增位置：`adapter.js` 末尾新增第 5 段 —— `TAB_ACTIVE_CLASS_RE` 之后、闭合 `});` 之前，作为**第 37 键**（键序追加在末尾，不打乱既有 36 键顺序）。

| # | 子键 | 值（逐字搬，不改顺序） | 原出现行 |
|---|---|---|---|
| 1 | `MIX_ID` | `["mix_id", "mixId", "id", "ids"]` | 85 |
| 2 | `MIX_NAME` | `["mix_name", "mixName", "name", "title"]` | 86 |
| 3 | `AIGC_LABEL_TYPE` | `["aigc_label_type", "aigcLabelType", "label_type", "labelType"]` | 119 |
| 4 | `AUTHOR_SEC_UID` | `["sec_uid", "secUid", "sec_user_id", "secUserId"]` | 150 |
| 5 | `AWEME_SEC_UID` | `["sec_uid", "secUid"]` | 151 |
| 6 | `AUTHOR_UID` | `["uid", "id", "user_id", "userId"]` | 163 |
| 7 | `AUTHOR_NICKNAME` | `["nickname", "nickName", "remarkName"]` | 164 |
| 8 | `AUTHOR_UNIQUE_ID` | `["unique_id", "uniqueId", "short_id", "shortId", "display_id"]` | 165 |
| 9 | `CREATE_TIME` | `["create_time", "createTime"]` | 178 |

**两组同值不同义，必须分开存**：

- `AUTHOR_SEC_UID`（4 项，查 `author`）与 `AWEME_SEC_UID`（2 项，查 `aweme`）——前者多 `sec_user_id`/`secUserId` 两个别名，合并会把 aweme 层多查两键，属行为变化。
- `MIX_ID` 里的 `"id"`、`AUTHOR_UID` 里的 `"id"` —— 同串不同对象，各自独立。

**键名前缀规则**：`AUTHOR_*` 走 `author` 对象，`AWEME_*` 走 `aweme` 顶层，`MIX_*` 走 `mix`，`AIGC_*` 走 `aigc`，`CREATE_TIME` 无前缀（走 `aweme` 顶层，与 178 行落点一致）。

## 3. 改后代码

### 3.1 `content/parse.js` 顶部新增 2 行（插在 13 行 `AI_SUSPECT_TEXTS` 之后）

```js
  /* 字段别名唯一来源：content/adapter.js 的 PICK_ALIAS；
   * 加载序由 manifest.json content_scripts[1] 保证（adapter 在 parse 之前）。
   * 本文件不自带副本，避免出现第二张真源。 */
  var PK = root.DyAdapter.PICK_ALIAS;
```

> 是 3 行注释 + 1 行赋值 = **+4 行**，非 +2。**改后行号统一 = 改前 +4**，§3.2 表内已按 +4 换算。此更正保留在稿内，便于你复核。

### 3.2 `parse.js` 9 处逐点替换（禁止全局字符串批替换）

| 改后行 | 改前 | 改后 |
|---|---|---|
| 89 | `var mixId = pick(mix, ["mix_id", "mixId", "id", "ids"], "");` | `var mixId = pick(mix, PK.MIX_ID, "");` |
| 90 | `var mixName = pick(mix, ["mix_name", "mixName", "name", "title"], "");` | `var mixName = pick(mix, PK.MIX_NAME, "");` |
| 123 | `pick(aigc, ["aigc_label_type", "aigcLabelType", "label_type", "labelType"], 0) \|\| 0` | `pick(aigc, PK.AIGC_LABEL_TYPE, 0) \|\| 0` |
| 154 | `pick(author, ["sec_uid", "secUid", "sec_user_id", "secUserId"], "") \|\|` | `pick(author, PK.AUTHOR_SEC_UID, "") \|\|` |
| 155 | `pick(aweme, ["sec_uid", "secUid"], "");` | `pick(aweme, PK.AWEME_SEC_UID, "");` |
| 167 | `uid: String(pick(author, ["uid", "id", "user_id", "userId"], aweme.author_user_id \|\| aweme.authorUserId \|\| "")),` | `uid: String(pick(author, PK.AUTHOR_UID, aweme.author_user_id \|\| aweme.authorUserId \|\| "")),` |
| 168 | `nickname: String(pick(author, ["nickname", "nickName", "remarkName"], "")),` | `nickname: String(pick(author, PK.AUTHOR_NICKNAME, "")),` |
| 169 | `uniqueId: String(pick(author, ["unique_id", "uniqueId", "short_id", "shortId", "display_id"], "")),` | `uniqueId: String(pick(author, PK.AUTHOR_UNIQUE_ID, "")),` |
| 182 | `createTime: Number(pick(aweme, ["create_time", "createTime"], 0) \|\| 0),` | `createTime: Number(pick(aweme, PK.CREATE_TIME, 0) \|\| 0),` |

**不动**：`pick()` 函数本体（26–32 行，改后 30–36 行）实现、`firstUrl`、`looksLikeAweme`、`fromDomHints`、`SEC_UID_RE`、`AI_DECLARE_TEXTS`、`AI_SUSPECT_TEXTS`、导出面 `root.DyAccountParse`（键集不变）。

### 3.3 `content/adapter.js` 新增段

```js
    /* ---------- 5. 解析字段别名（P3-b：parse.js pick() 的唯一来源） ---------- */
    PICK_ALIAS: Object.freeze({
      MIX_ID: Object.freeze(["mix_id", "mixId", "id", "ids"]),
      MIX_NAME: Object.freeze(["mix_name", "mixName", "name", "title"]),
      AIGC_LABEL_TYPE: Object.freeze(["aigc_label_type", "aigcLabelType", "label_type", "labelType"]),
      AUTHOR_SEC_UID: Object.freeze(["sec_uid", "secUid", "sec_user_id", "secUserId"]),
      AWEME_SEC_UID: Object.freeze(["sec_uid", "secUid"]),
      AUTHOR_UID: Object.freeze(["uid", "id", "user_id", "userId"]),
      AUTHOR_NICKNAME: Object.freeze(["nickname", "nickName", "remarkName"]),
      AUTHOR_UNIQUE_ID: Object.freeze(["unique_id", "uniqueId", "short_id", "shortId", "display_id"]),
      CREATE_TIME: Object.freeze(["create_time", "createTime"])
    }),
```

- 顶层 `Object.freeze` 仍是浅冻结，所以 9 个子数组各再套一层（与 P3-a 的 10 个数组同规矩）；
- 既有第 112 行 `TAB_ACTIVE_CLASS_RE: /(active|selected)/i` 需补一个尾逗号；
- 新增 13 行（空行 + 段注释 + `PICK_ALIAS: Object.freeze({` + 9 子键 + `}),`），改后 `adapter.js` 127 行。

### 3.4 `adapter.js` 头注释必须同批改（否则自相矛盾）

第 9 行现写「不放：……解析字段别名（见交付说明 §6）」——本批正好把别名放进来，不改会被复核端直接抓。

```diff
- *   - 平台属性名、类名判定
- * 不放：业务词池、行为参数、消息协议、解析字段别名（见交付说明 §6）。
+ *   - 平台属性名、类名判定
+ *   - 接口字段别名（P3-b 起，parse.js pick() 查表用）
+ * 不放：业务词池、行为参数、消息协议。
```

同时第 2 行标题的批次说明改为「P3-a + P3-b」。

## 4. 装配与加载序

### 4.1 manifest 不动

`content_scripts[1]` 现为 `["shared.js", "content/adapter.js", "content/parse.js", "content/content.js"]`，adapter 已在 parse 之前，本批零改动。

### 4.2 新增硬依赖，必须写进测试

`parse.js` 现在**要求** `DyAdapter.PICK_ALIAS` 已存在，否则在读到 `var PK = root.DyAdapter.PICK_ALIAS;` 时抛 `TypeError: Cannot read properties of undefined`。

- 运行时：manifest 注入序保证安全；
- 测试时：`parse.test.js` 目前**只 eval parse.js**，必须同批改成先 eval 同目录 `adapter.js`（见 §6.2）；
- 明确**不做** fallback 副本：加一份内置别名表就等于两张真源，迟早漂移，正是本批要消灭的东西。

### 4.3 为什么放 adapter.js 而不是留在 parse.js

评审 v2 §6.4 把别名表定性为「接口结构适配」，只是当时判断"动它必须同批改 parse 测试"才推迟。现在既然要动，落点按「唯一来源」原则统一到 `adapter.js`，代价是 `adapter.js` 定位从「页面适配」扩为「页面 + 接口适配」，已在 §3.4 改头注释说明。

## 5. 不入表清单（明确不纳入本批）

### 5.1 双键直读 17 处（评审已确认本次不纳入）

| 行 | 写法 |
|---|---|
| 37 | `cover.url_list \|\| cover.urlList` |
| 44 | `node.aweme_id \|\| node.awemeId` |
| 47–53 | `looksLikeAweme` 判定链：`author\|\|authorInfo`、`author_user_id\|\|authorUserId`、`mix_info\|\|mixInfo`（3 处） |
| 69 | `data.aweme_id \|\| data.awemeId` |
| 83 | `aweme.mix_info \|\| aweme.mixInfo` |
| 94 | `aweme.mix_id \|\| aweme.mixId` |
| 96 | `aweme.mix_name \|\| aweme.mixName` |
| 117 | `aweme.aigc_info \|\| aweme.aigcInfo` |
| 121 | `aigc.created_by_ai \|\| aigc.createdByAi` |
| 148 | `aweme.author \|\| aweme.authorInfo` |
| 158 | `author.avatar_thumb \|\| author.avatarThumb` |
| 159 | `author.avatar_medium \|\| author.avatarMedium` |
| 160 | `author.avatar_uri \|\| author.avatarUri` |
| 174 | `aweme.aweme_id \|\| aweme.awemeId` |
| 179 | `aweme.desc \|\| aweme.description` |

理由：这些是「取值 + 判空 + 兜底」混在表达式里的短路，改成查表会把一行拆成多行、动到判断逻辑本身，收益低、回归面大。留作后续批次单独评估。

**另需说明一处（不计入 17 处）**：第 163 行 `uid` 那行本批要替换 `pick()` 的第二个参数，但它的第三个参数 `aweme.author_user_id || aweme.authorUserId || ""` 仍保留原样 —— 这是 `pick()` 的 fallback，与 `AUTHOR_UID` 别名表不是一回事，替换后仍是一处双键直读，**本批刻意不动**。

### 5.2 其他不动项

| 项 | 位置 | 理由 |
|---|---|---|
| `SEC_UID_RE` / `AI_DECLARE_TEXTS` / `AI_SUSPECT_TEXTS` | parse.js 4–13 | P3-a 已定 `AI_DECLARE_TEXTS` 不入表；前两者与声明文本同源，一并不动 |
| `fromDomHints` 的 `info.*` 取值 | parse.js 220–245 | 走自家 DOM 面板对象，非接口结构 |
| `shared.js` 头注释里过时的注入序（第 8 行、28–29 行） | shared.js | md5 `0ccc70f8` 是跨批复核锚点，**哪怕只改注释也会破锚**，不动 |

## 6. 测试改动（随包外交付）

### 6.1 `tests/adapter.test.js`（177 → 预计 209，无 base）

| 改动 | 增减断言 |
|---|---|
| `EXPECTED` 末尾追加 `["PICK_ALIAS", {...9 组...}]` → 走现有 `eq` 深比对（JSON 序列化，键序也锁） | +1 |
| `eq("键数 = 36", ...)` → 37 | 0 |
| 新增组：`PICK_ALIAS` 子键数 = 9 | +1 |
| 新增组：9 个子数组 `Object.isFrozen === true` | +9 |
| 新增组：9 个子数组不能 `push` | +9 |
| 新增组：`parse.js` 里 `PK.<KEY>` 引用数 = 9 | +1 |
| 新增组：9 个被引用的子键在 `A.PICK_ALIAS` 里都存在 | +9 |
| 新增组：`parse.js` 内 9 组别名数组字面量已清零（fixed-string 扫） | +1 |
| 新增组：`content.js` 未引用的键**只有** `PICK_ALIAS`（引用的 36 个键保持不变） | +1 |
| **合计** | **+32 → 209** |

带 `--base` 时：现有 `--base` 循环会把 `PICK_ALIAS` 拿去基线 `content.js` 里找，必然找不到 → 必须单列分支改为**查基线 `parse.js`**（分支要放在 `const items = ...` 之前）：

```js
if (key === "PICK_ALIAS") {
  const baseParse = fs.readFileSync(path.join(baseDir, "content", "parse.js"), "utf8");
  const baseParseCompact = baseParse.replace(/\s+/g, "");
  /* 9 组逐项在基线 parse.js 里按 "x", "y" 形态查找，任一缺失记 miss */
  ...
  ok("PICK_ALIAS 9 组值全部能在基线 parse.js 中找到", miss.length === 0, miss.join("; "));
  continue;
}
```

预计 209 + 1 = **210**（带 base）。以上数字为按现有断言结构推算，**实跑后以真实输出回填**，不预设结论。

> 新增的三类静态断言（`PK` 引用数、字面量清零、未引用键）需要读 `content/parse.js`，得在文件顶部补一行 `const PARSE = path.join(ROOT, "content", "parse.js");`，现有测试只读过 `content.js` / `manifest.json` / `popup/*`。

### 6.2 `tests/parse.test.js`（76 → 预计 78）

在 eval parse.js 之前插 3 行：

```js
const adapterFile = path.join(path.dirname(parseFile), "adapter.js");
if (!fs.existsSync(adapterFile)) { console.error("FAIL: 需要同目录 adapter.js（P3-b 起 parse.js 依赖 DyAdapter.PICK_ALIAS）"); process.exit(1); }
(0, eval)(fs.readFileSync(adapterFile, "utf8"));
```

新增 2 条断言：

- `T0b DyAdapter.PICK_ALIAS 存在`（1 条）
- `T0c PICK_ALIAS 9 组键齐`（1 条，`Object.keys` 比对）

### 6.3 `tests/README.md`

第 12、17 行的「76 passed」需同步为 78，并补一句「parse 测试需同目录 adapter.js」。

### 6.4 不动的测试

`tests/shared.test.js`（129 口径）、`tests/shared.bydev.test.js`（78 条，第 221 行注入序断言）——本批 manifest 未改、shared.js 未改，两条都不该动。

### 6.5 变异自检（证断言咬得住）

1. 把 `PK.AUTHOR_NICKNAME` 换成 `[]` → `parse.test.js` 应红（T2.3 昵称）。
2. 把 `PICK_ALIAS.MIX_ID` 里 `"ids"` 删掉 → `adapter.test.js` 应红（值比对），`--base` 那条也应红。
3. 把 `parse.js` 某处 `PK.MIX_ID` 改回字面量 → `adapter.test.js` 的「PK 引用数 = 9」应红。

## 7. page-hook.js 正则评估（只评估，不改）

### 7.1 现状

| 行 | 常量 / 写法 | 作用 |
|---|---|---|
| 14–15 | `IGNORE = /\/(comment\|danmaku\|im\/\|report\|log\/\|hotsoon\|webcast\|live\/\|poi\/\|city\/\|abtest\|web\/page)/i` | 黑名单，命中即丢 |
| 16–17 | `INTEREST = /\/(aweme\|module\/feed\|tab\/feed\|mix\/\|aweme\/detail\|aweme\/post\|general\/search\|search\/item\|search\/single\|search\/stream\|recommend\|jingxuan\|web\/module\|web\/search\|discover\/search)/i` | 白名单 |
| 23 | `/feed/.test(url)`、`/\/search\//.test(url)` | 行内补充白名单 |

共 **4 条 / 3 行**（第 23 行含 2 条）。

### 7.2 不能并入 DyAdapter 的硬约束

`page-hook.js` 在 `content_scripts[0]` 的 **MAIN world**，`adapter.js` 只在 `content_scripts[1]` 的 ISOLATED world。两个 world 的 `window` 不是同一个对象，MAIN 侧拿不到 `DyAdapter`。

要让它引用，只有两条路：

| 方案 | 做法 | 代价 |
|---|---|---|
| A. adapter 也注入 MAIN | manifest 加一行 | 把 37 键适配表（含全部 DOM 选择器、文本锚点）暴露给页面脚本，且 MAIN 侧还要再加载一遍；注入面扩大，P3-a 刻意避免的就是这个 |
| B. 主世界副本 | 在 page-hook 内部再写一份正则 | 两张真源，改版时必漂移，等于把本批要消灭的问题搬过去 |
| C. 由 ISOLATED 侧 postMessage 下发 | content.js 启动时把正则 source 发给 MAIN | 多一条消息协议，还要处理「hook 早于 content 就绪」的时序；为 4 条正则不值得 |

### 7.3 结论

**本批不动，维持现状**，理由：

1. 收益极低：4 条正则、3 行，且只在 URL 判定这一处用；
2. 风险不对称：任一方案都要么扩大注入面、要么制造第二真源、要么新增消息协议；
3. 真实同义重复只有 1 处：`/\/search\//`（page-hook 第 23 行）≈ `DyAdapter.SEARCH_PATH_RE`（adapter 第 104 行）——但跨 world，**保持重复是当前最低成本**。

### 7.4 留一个提醒（不阻塞）

若将来抖音改 URL 形态，需同改 **3 处**：`adapter.js` 的 `SEARCH_PATH_RE`/`VIDEO_PATH_RE`/`JINGXUAN_PATH_RE`、`page-hook.js` 的 `INTEREST`、`page-hook.js` 第 23 行两条行内正则。这条已写进本稿，建议一并记进交接说明。

## 8. 改动面与风险

### 8.1 改动面

| 文件 | 动作 | 预计变化 |
|---|---|---|
| `content/adapter.js` | 加 `PICK_ALIAS`（37 键）+ 补 112 行尾逗号 + 改头注释 | 4,521 B → 约 5,300 B |
| `content/parse.js` | 加 4 行（注释 3 + 赋值 1）+ 替换 9 处 | 8,448 B → 约 8,400 B（字面量换短名，净减约 50 B） |
| `manifest.json` | 不动 | 0 |
| `content/content.js` | 不动 | 0 |
| `content/page-hook.js` | 不动 | 0 |
| `shared.js` / `popup/*` / `background.js` | 不动 | 0 |

打包口径会是 **2 文件变动**（对比 P3-a 的 3 文件），P3-a 已验收的 25 条目结构不变。

### 8.2 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 漏加载 adapter（单独 eval parse.js） | `TypeError`，采集全断 | §4.2 明确写进 parse.test.js；manifest 序已有测试锁 |
| 替换时误伤同名字面量 | 行为变化 | 逐点按行号改，禁止全局替换；`adapter.test.js` 加「parse.js 字面量清零」扫描 |
| 别名顺序被改 | 取值优先级变化，可能取到 `id` 而非 `mix_id` | 表内顺序逐字搬；`adapter.test.js` 值比对按数组顺序锁；`--base` 再比一遍 |
| 冻结只做浅层 | 子数组可被 push 污染 | 9 个子数组各 `Object.freeze`，测试逐条验证 |
| 复核端认为 adapter 定位被扩大 | 退回 | §4.3 已给理由；头注释同批改，不留矛盾 |

### 8.3 回滚

删 `adapter.js` 的 §5 段（13 行）+ 还原 112 行逗号 + 还原头注释；`parse.js` 删 4 行 + 9 处换回字面量。两文件独立可回滚，不涉及 manifest。

## 9. 验证计划（动工后按此执行）

```
node --check content/adapter.js
node --check content/parse.js
node tests/adapter.test.js                                  # 209（实跑 209）
node tests/adapter.test.js --base=<BASELINE 解包>            # 预计 210 → 实跑 211
node tests/parse.test.js                                    # 78（实跑 78）
node tests/shared.test.js --base=<BASELINE 解包>             # 129（应不变，实跑 129）
node tests/shared.bydev.test.js                             # 78（应不变，实跑 78）
```

`--base` 差额说明：基线分支实际新增 2 条断言（`基线 content.js 存在` + `其余 36 键的值全部能在基线 content.js 中找到`），原估 210 少算 1 条，实跑 211。

再跑 §6.5 三条变异自检，确认断言能咬住。

**实机四步**（新目录 `dyaccount_v1.3.3-p3b`，扩展 ID 会变、`chrome.storage` 重置）：

1. `chrome://extensions` → 加载已解压 → 选 `Desktop\dyaccount_v1.3.3-p3b`
2. 采集页：面板正常，采到的记录里**合集名、作者昵称、secUid** 三个字段非空（这三项正好走 `MIX_NAME`/`AUTHOR_NICKNAME`/`AUTHOR_SEC_UID`）
3. 搜索页：采一批，检查 AI 声明识别与字段完整度
4. 播放页：浮层采集一次，`createTime` 有值（走 `CREATE_TIME`）

回填当时目录三 md5（manifest / adapter.js / shared.js）——manifest 与 shared.js 本批未改，值应与 P3-a 相同。

## 10. 自检（按本稿复核用）

- [x] 9 组别名逐组给了值、子键名、原行号
- [x] 9 处替换逐行给了改前/改后，标注了行号位移（+4，含 §3.1 更正）
- [x] `adapter.js` 新增段给全代码，键序说明为"追加末尾"
- [x] 头注释矛盾点已列（第 9 行）
- [x] 加载序硬依赖 + 测试同批加载 + 拒绝 fallback 副本，三件事写清
- [x] 测试计数给了推算过程，并声明"以实跑为准、不预设结论"
- [x] page-hook 三方案对比 + 结论 + 跨 world 提醒
- [x] 不纳入清单给了 17 处逐行表，不让复核端替我数
- [x] 回滚路径、变异自检、实机步骤各一节
- [x] 交稿前自检已修 5 处笔误：新增行数统一为 +4（§3.1 注释已拆成 3 行以对齐）、§0/§1 的「+2」「2 行」改齐、「17 处 / 16 行」改为「17 处（15 条目）」并补 163 行 fallback 说明、adapter 新增段改 13 行/127 行、§1 交叉引用 §8 → §5.1

## 11. 状态

**已实施（2026-10-21）。** 按本稿完成：目录 `Desktop\dyaccount_v1.3.3-p3b`，改 `adapter.js` / `parse.js` + 3 个测试文件，7 项测试全绿（209 / 211 / 78 / 129 / 78），变异自检 3/3 被抓，包已打。见同目录 `P3b-交付说明.md`。
