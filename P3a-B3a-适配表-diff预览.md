# P3-a（B3-a 适配表常量收拢）· diff 预览 v2

> 状态：**只读扫描完成，源码一行未改**。
> 本稿按评审意见 v1 全文重写，逐条对应评审第二节（阻塞项1）、第三节（阻塞项2）、第四节（清单补全）、第五节（形态 / 断言）、第七节（计数纠错）。
> 与 v1 的差异集中在：键数 29 → **36**、新增「字面量出现点表」、撤回 `PLAYER_ROOT_*`、把 `PLAYER_URL_RES` 拆两键、给出改后代码、给全量「旧名 → 新键」映射、纠正四处计数。

## 0. 校验基准

扫描对象为桌面目录 `dyaccount_v1.3.3`（与 P2a 包内同版），与评审手上四份逐一相符：

| 文件 | 字节 | md5 |
|---|---|---|
| content/content.js | 91,418 | `c2774b020b1eff156b13a0bd87228b8b` |
| content/page-hook.js | 3,181 | `099e3b20f45fc079d40679ec2e00833b` |
| content/parse.js | 8,448 | `10ebf8d1554f12f4fc1b868408c2b861` |
| shared.js | 7,251 | `0ccc70f889eac7bca75e1517bb54b724` |

行号全部以本表 content.js 为准。

### 0.1 键数变化账（v1 的 29 行 → v2 的 36 键）

| 动作 | 明细 | 增减 |
|---|---|---|
| v1 基数 | 29 行，其中第 29 行实为 2 个键（`RELATED_SCOPE_SEL`、`RELATED_ITEM_SEL`） | 30 键 |
| 撤回 | `PLAYER_ROOT_IDS`、`PLAYER_ROOT_SELS`（理由见 §4.4：跨 API 的 `\|\|` 链改数组遍历是逻辑重写，且 v1 漏了链尾 `document.body`） | −2 |
| 补收文本锚点 | `MORE_TEXT`、`SORT_BY_TEXT`、`CONTENT_FORM_TEXT`（评审 §四.1） | +3 |
| 补收标签型选择器 | `IMG_SEL`（398）、`CLICKABLE_SCAN_SEL`（1372）（评审 §四.2） | +2 |
| 补收属性名 | `ARIA_LABEL_ATTR`（396、1311）（评审 §四.4：与 `aria-selected` 同类，全收） | +1 |
| 补收 tab 判定词 | `CHANNEL_ALL_TEXT`（2131 `"全部"`，与 `CHANNELS[0]` 同串不同义，单列） | +1 |
| 拆分 | 原 `PLAYER_URL_RES` 拆 `MODAL_ID_RE` + `VIDEO_PATH_RE`（1081 行两条正则分别测 `location.href` 与 `location.pathname`，合成数组会改行为） | +1 |
| **合计** | | **36 键** |

## 1. 结论摘要

| 项 | 数量 |
|---|---|
| 入表键 | **36** |
| content.js 内被替换的字面量出现点 | **53 处 / 分布在 49 行** |
| content.js 内被删除的局部常量定义行 | **24 行**（6 个常量定义，见 §8.1） |
| 值发生变化的 | **0**（全部为原样搬运；唯一例外是把 `AUTO_CHANNELS` 改名 `CHANNELS`，值不变） |
| 涉及文件 | 新增 `content/adapter.js`；改 `content/content.js`；改 `manifest.json` 1 行；同批改 `tests/shared.bydev.test.js` 1 行；新增 `tests/adapter.test.js` |
| `AI_DECLARE_TEXTS` | **不入表**（已定，理由见 §6.1） |

本批不动：`background.js`、`content/page-hook.js`、`content/parse.js`、`popup/*`、`guard.js`、`shared.js`。

## 2. 入表清单（36 键）

新表形态：`var DyAdapter = Object.freeze({ ... });`，文件 `content/adapter.js`，ISOLATED world，插在 `shared.js` 之后、`content/parse.js` 之前。

### 2.1 DOM 选择器（18 键）

| # | 现位置 | 现值（原文） | 新键名 | 形态 |
|---|---|---|---|---|
| 1 | 365 | `getElementById("RENDER_DATA") \|\| getElementById("__NEXT_DATA__")` | `RENDER_DATA_IDS` | 数组 2 条 |
| 2 | 390、1284 | `'a[href*="/user/"]'` | `USER_LINK_SEL` | 字符串 |
| 3 | 412 | `'a[href*="/collection/"], a[href*="/mix/"]'` | `MIX_LINK_SEL` | 字符串（含逗号空格原样） |
| 4 | 1147–1155 | `hints` 7 条：`[id*="search-result"]`、`[class*="search-result"]`、`[class*="SearchResult"]`、`[class*="waterfall"]`、`#douyin-right-container`、`main`、`#root` | `SCROLL_ROOT_HINTS` | 数组 7 条，顺序不变 |
| 5 | 1186 | `["加载更多", "查看更多", "点击加载"]` | `LOAD_MORE_TEXTS` | 数组 3 条 |
| 6 | 1187 | `"button, div, span, p"` | `LOAD_MORE_SCAN_SEL` | 字符串 |
| 7 | 1202–1212 | `selectors` 9 条（`[data-e2e="video-switch-next"]` … `div[aria-label*="下一条"]`） | `NEXT_PLAYER_SELS` | 数组 9 条，顺序不变 |
| 8 | 1220 | `"video"` | `VIDEO_SEL` | 字符串 |
| 9 | 1292–1296 | `closest` 链 5 条：`li`、`article`、`[class*="card"]`、`[class*="Card"]`、`[class*="item"]`（末位 `parentElement` 留在调用处） | `CARD_CLOSEST_SELS` | 数组 5 条，顺序不变 |
| 10 | 1305 | `'a[href*="/video/"]'` | `VIDEO_LINK_SEL` | 字符串 |
| 11 | 2125 | `'[role="tablist"], [class*="semi-tabs-tab-list"], [class*="semi-tabs-bar"]'` | `TABLIST_SELS` | 数组 3 条，`join(", ")` 后逐字等于原串 |
| 12 | 2129、2140、2154 | `'[role="tab"], [class*="semi-tabs-tab"]'` | `TAB_SELS` | 数组 2 条，`join(", ")` 后逐字等于原串 |
| 13 | 2161 | `"span,div,li,button"` | `TAB_FALLBACK_SCAN_SEL` | 字符串 |
| 14 | 2167 | `'[role="tab"], [class*="tab"]'` | `TAB_CLOSEST_SEL` | 字符串（**与 #12 是另一个串，不并键**） |
| 15 | 1326 | `"div,span,h2,h3,p"` | `RELATED_SCOPE_SEL` | 字符串 |
| 16 | 1335 | `"div,span,a,li"` | `RELATED_ITEM_SEL` | 字符串 |
| 17 | 398 | `a.querySelector("img")`（头像兜底取 alt） | `IMG_SEL` | 字符串 |
| 18 | 1372 | `"div,span,a,li,button,p"`（`clickableByText` 扫描域） | `CLICKABLE_SCAN_SEL` | 字符串 |

### 2.2 页面文本锚点（9 键）

| # | 现位置 | 现值 | 新键名 | 形态 |
|---|---|---|---|---|
| 19 | 68 | `["请选择所有符合", "拖拽到", "请完成验证", "拖动滑块", "向右滑动", "安全验证"]` | `CAPTCHA_TEXTS` | 数组 6 条 |
| 20 | 69 | `["登录已过期", "请重新登录", "扫码登录", "验证码登录", "账号已退出"]` | `LOGOUT_TEXTS` | 数组 5 条 |
| 21 | 411、1062、1303 | `"合集"` | `MIX_TEXT` | 字符串 |
| 22 | 1332、1343 | `"相关搜索"` | `RELATED_TITLE_TEXT` | 字符串 |
| 23 | 1343 | `"更多"` | `MORE_TEXT` | 字符串 |
| 24 | 1437 | `"排序依据"` | `SORT_BY_TEXT` | 字符串 |
| 25 | 1437 | `"内容形式"` | `CONTENT_FORM_TEXT` | 字符串 |
| 26 | 9 | `["全部", "二次元", "小剧场", "影视"]` | `CHANNELS` | 数组 4 条，顺序即换台顺序 |
| 27 | 2131 | `"全部"`（`tabMatches(..., "全部")`） | `CHANNEL_ALL_TEXT` | 字符串 |

### 2.3 URL 形态与正则（6 键）

| # | 现位置 | 现值 | 新键名 | 形态 |
|---|---|---|---|---|
| 28 | 1081 前半 | `/modal_id=/`（测 `location.href`） | `MODAL_ID_RE` | 正则 |
| 29 | 1081 后半 | `/\/video\//`（测 `location.pathname`） | `VIDEO_PATH_RE` | 正则 |
| 30 | 1085 | `/\/search\//`（测 `location.pathname`） | `SEARCH_PATH_RE` | 正则 |
| 31 | 1539 | `/\/jingxuan/`（测 `location.pathname`） | `JINGXUAN_PATH_RE` | 正则 |
| 32 | 607 | `"jingxuan"`（`openViaBackground` 参数） | `JINGXUAN_KEY` | 字符串 |
| 33 | 2100 | `"https://www.douyin.com/search/"` | `SEARCH_URL_BASE` | 字符串 |

排他声明（评审 §五第 3 行）：`141` `/\/video\/(\d+)/`、`152` `/\/search\/([^/?#]+)/`、`1307` `/\/video\/(\d+)/` **均不在本批范围**，保持原样。前两条是我 v1 未列出、评审补上的，行号经复核与评审一致（141 / 152 / 1307）。

### 2.4 属性名与类名判定（3 键）

| # | 现位置 | 现值 | 新键名 | 形态 |
|---|---|---|---|---|
| 34 | 2146 | `"aria-selected"` | `TAB_SELECTED_ATTR` | 字符串 |
| 35 | 396、1311 | `"aria-label"` | `ARIA_LABEL_ATTR` | 字符串 |
| 36 | 2148 | `/(active\|selected)/i` | `TAB_ACTIVE_CLASS_RE` | 正则（带 `i`） |

说明 #35：评审 §四.4 的两种选择，这里选「都收」。理由：`aria-selected` 读的是状态、`aria-label` 读的是取值，但两者同属「平台给 DOM 的属性名约定」，改版时一起变。`1209–1211` 里 `[aria-label*="下一个"]` 是选择器串的一部分，已随 `NEXT_PLAYER_SELS` 原样搬入，不另建键。

## 3. 字面量出现点表（评审阻塞项 1）

> 规则：**只有本表标「替换」的行才动**；标「保持」的行逐字不动。替换全部逐点手工完成，不使用任何按字符串的批替换。

### 3.1 `"合集"` —— 全量 15 个出现点，只动 3 个

| 行 | 原文片段 | 语义 | 处理 |
|---|---|---|---|
| 21 | 注释「3128 位已采作者的合集名/昵称高频语料」 | 注释 | 保持 |
| **411** | `visibleTextHas("合集")` | 页面可见文本锚点 | **替换 → `MIX_TEXT`** |
| 421 | `mixName: hasMix ? "合集" : ""` | **写进采集记录 → 导出 CSV → 上传服务端的字段值** | **保持（硬条件）** |
| 442 | 面板标题「AI合集作者采集」 | 面板文案 | 保持 |
| 466 | `<label …>必须挂载合集</label>` | 面板文案 | 保持 |
| 534 | 面板提示「是否取消「必须挂载合集」…」 | 面板文案 | 保持 |
| 536 | `取消合集条件` | 面板按钮 | 保持 |
| 627 | `"已放宽：取消「必须挂载合集」"` | 面板状态提示 | 保持 |
| 783 | `a.mixName \|\| "合集"` | **自家面板渲染兜底文案** | **保持（硬条件）** |
| 792 | 面板空态「目标：挂载合集 + …」 | 面板文案 | 保持 |
| 836 | CSV 表头 `"合集名"` | 导出表头 | 保持 |
| **1062** | `visibleTextHas("合集")` | 页面可见文本锚点（v1 漏收） | **替换 → `MIX_TEXT`** |
| 1069 | `" \| 合集:"` | 面板状态提示 | 保持 |
| **1303** | `text.indexOf("合集") !== -1` | 页面卡片文本锚点 | **替换 → `MIX_TEXT`** |
| 1313 | `mixName: hasMix ? "合集" : ""` | **写进采集记录 → 导出 CSV → 上传服务端** | **保持（硬条件）** |

### 3.2 `"加载更多"`

| 行 | 原文片段 | 语义 | 处理 |
|---|---|---|---|
| 1093 | `"采集中，请在搜索结果里下滑加载更多"` | 自家面板提示语（含子串） | 保持 |
| 1186 | `const texts = ["加载更多", "查看更多", "点击加载"]` | 定义行，整行移入 `LOAD_MORE_TEXTS` | 删除定义行，值原样搬运 |
| 1193 | `texts.indexOf(t)` | 引用点 | 替换 → `DyAdapter.LOAD_MORE_TEXTS.indexOf(t)` |

### 3.3 `"请重新登录"` / `"登录已过期"`

| 行 | 原文片段 | 语义 | 处理 |
|---|---|---|---|
| 69 | `LOGOUT_TEXTS` 数组内两项 | 定义行，整行移入适配表 | 删除定义行，值原样搬运 |
| 1789 | `LOGOUT_TEXTS.some(...)` | 引用点 | 替换 → `DyAdapter.LOGOUT_TEXTS.some(...)` |
| 1797 | `"检测到账号被登出，已停止采集，请重新登录后刷新页面再开始"` | 自家提示语（含子串） | 保持 |
| 1800 | `fingerprint: "限流 / 登录已过期"` | **守卫去重键**，改则重复报警 | 保持 |
| 1803 | `"检测到需要重新登录，…请重新登录后刷新页面再开始。"` | 告警正文（含子串） | 保持 |

### 3.4 `"相关搜索"` / `"更多"`

| 行 | 原文片段 | 语义 | 处理 |
|---|---|---|---|
| 58 | 注释 | 注释 | 保持 |
| 1319 | 注释 | 注释 | 保持 |
| **1332** | `t === "相关搜索"` | 页面板块标题锚点 | **替换 → `RELATED_TITLE_TEXT`** |
| **1343** | `t === "相关搜索" \|\| t.indexOf("更多") !== -1` | 页面文本过滤锚点（同一行两个键） | **替换 → `RELATED_TITLE_TEXT` / `MORE_TEXT`** |
| 1350 | `"已并入 " + added + " 个「相关搜索」词…"` | 面板状态提示 | 保持 |

### 3.5 `"排序依据"` / `"内容形式"`

| 行 | 原文片段 | 语义 | 处理 |
|---|---|---|---|
| 1431 | 注释「最近的同时含「排序依据」或「内容形式」的祖先」 | 注释 | 保持 |
| **1437** | `t.indexOf("排序依据") !== -1 \|\| t.indexOf("内容形式") !== -1` | **搜索筛选面板锚点**（同一行两个键） | **替换 → `SORT_BY_TEXT` / `CONTENT_FORM_TEXT`** |
| 1476 | 注释「内容形式→视频」 | 注释 | 保持 |
| 1481 | `"搜索页已切「最新发布」" + … + "，内容形式→视频"` | 面板状态提示 | 保持 |

### 3.6 `"小剧场"` / `"全部"`

| 行 | 原文片段 | 语义 | 处理 |
|---|---|---|---|
| 9 | `AUTO_CHANNELS = ["全部", "二次元", "小剧场", "影视"]` | 定义行，整行移入 `CHANNELS` | 删除定义行，值原样搬运 |
| 30 | `"AI小剧场"`（词池内、另一个词） | 业务词表 | 保持 |
| 89 | `TYPE_WHITELIST` 内含 `"小剧场"` | 业务判定词表，同串不同义 | 保持 |
| 1542 | `AUTO_CHANNELS.some(...)` | 引用点 | 替换 → `DyAdapter.CHANNELS.some(...)` |
| 1856 | `AUTO_CHANNELS.some(...)` | 引用点 | 替换 → `DyAdapter.CHANNELS.some(...)` |
| 1861、1865 | 提示语 `全部/二次元/小剧场/影视` | 面板/系统提示 | 保持 |
| **2131** | `tabMatches(..., "全部")` | tab 名匹配锚点 | **替换 → `CHANNEL_ALL_TEXT`** |
| 2176 | `AUTO_CHANNELS.length` | 引用点 | 替换 → `DyAdapter.CHANNELS.length` |
| 2177 | `AUTO_CHANNELS[next]` | 引用点 | 替换 → `DyAdapter.CHANNELS[next]` |

### 3.7 带捕获组的正则（**本批一律不动**）

| 行 | 原文 | 与表内键的关系 |
|---|---|---|
| 141 | `/\/video\/(\d+)/`（`currentAwemeId`） | 与 `VIDEO_PATH_RE`（`/\/video\//`）不同，不得复用 |
| 152 | `/\/search\/([^/?#]+)/`（`currentSearchWord`） | 与 `SEARCH_PATH_RE` 不同，不得复用 |
| 1307 | `/\/video\/(\d+)/`（`scanSearchDom` 取 awemeId） | 同上 |

### 3.8 其余字符串的落点（逐键，只列会被替换的行）

| 键 | 替换行 |
|---|---|
| `RENDER_DATA_IDS` | 365 |
| `USER_LINK_SEL` | 390、1284 |
| `MIX_LINK_SEL` | 412 |
| `SCROLL_ROOT_HINTS` | 1156（`hints.length`）、1157（`hints[i]`）；定义行 1147–1155 删除 |
| `LOAD_MORE_TEXTS` | 1193 |
| `LOAD_MORE_SCAN_SEL` | 1187 |
| `NEXT_PLAYER_SELS` | 1213、1214；定义行 1202–1212 删除 |
| `VIDEO_SEL` | 1220 |
| `CARD_CLOSEST_SELS` | 1292、1293、1294、1295、1296 |
| `VIDEO_LINK_SEL` | 1305 |
| `TABLIST_SELS` | 2125 |
| `TAB_SELS` | 2129、2140、2154 |
| `TAB_FALLBACK_SCAN_SEL` | 2161 |
| `TAB_CLOSEST_SEL` | 2167 |
| `RELATED_SCOPE_SEL` | 1326 |
| `RELATED_ITEM_SEL` | 1335 |
| `IMG_SEL` | 398（同一行两处：`querySelector("img")` 与 `.alt` 前置的 `querySelector("img")`） |
| `CLICKABLE_SCAN_SEL` | 1372 |
| `CAPTCHA_TEXTS` | 1825；定义行 68 删除 |
| `LOGOUT_TEXTS` | 1789；定义行 69 删除 |
| `MIX_TEXT` | 411、1062、1303 |
| `RELATED_TITLE_TEXT` | 1332、1343 |
| `MORE_TEXT` | 1343 |
| `SORT_BY_TEXT` | 1437 |
| `CONTENT_FORM_TEXT` | 1437 |
| `CHANNELS` | 1542、1856、2176、2177；定义行 9 删除 |
| `CHANNEL_ALL_TEXT` | 2131 |
| `MODAL_ID_RE` | 1081（前半） |
| `VIDEO_PATH_RE` | 1081（后半） |
| `SEARCH_PATH_RE` | 1085 |
| `JINGXUAN_PATH_RE` | 1539 |
| `JINGXUAN_KEY` | 607 |
| `SEARCH_URL_BASE` | 2100 |
| `TAB_SELECTED_ATTR` | 2146 |
| `ARIA_LABEL_ATTR` | 396、1311 |
| `TAB_ACTIVE_CLASS_RE` | 2148 |

合计：**53 处字面量 / 49 行**（398、1081、1343、1437 四行为一行多处）。

## 4. 形态变化处的改后代码（评审 §五）

### 4.1 文件骨架与深冻结

`Object.freeze` 是浅冻结，评审 §六.3 的提醒成立。这里对每个数组值再套一层 `Object.freeze`（10 个数组键），其余键保持普通值：

```js
// content/adapter.js —— 纯数据，无函数，无副作用
var DyAdapter = Object.freeze({
  RENDER_DATA_IDS: Object.freeze(["RENDER_DATA", "__NEXT_DATA__"]),
  SCROLL_ROOT_HINTS: Object.freeze([ /* 7 条，顺序不变 */ ]),
  NEXT_PLAYER_SELS: Object.freeze([ /* 9 条，顺序不变 */ ]),
  CARD_CLOSEST_SELS: Object.freeze(["li", "article", '[class*="card"]', '[class*="Card"]', '[class*="item"]']),
  TABLIST_SELS: Object.freeze(['[role="tablist"]', '[class*="semi-tabs-tab-list"]', '[class*="semi-tabs-bar"]']),
  TAB_SELS: Object.freeze(['[role="tab"]', '[class*="semi-tabs-tab"]']),
  CAPTCHA_TEXTS: Object.freeze(["请选择所有符合", "拖拽到", "请完成验证", "拖动滑块", "向右滑动", "安全验证"]),
  LOGOUT_TEXTS: Object.freeze(["登录已过期", "请重新登录", "扫码登录", "验证码登录", "账号已退出"]),
  CHANNELS: Object.freeze(["全部", "二次元", "小剧场", "影视"]),
  LOAD_MORE_TEXTS: Object.freeze(["加载更多", "查看更多", "点击加载"]),
  // 其余 26 个键为字符串 / 正则
  MODAL_ID_RE: /modal_id=/,
  VIDEO_PATH_RE: /\/video\//,
  SEARCH_PATH_RE: /\/search\//,
  JINGXUAN_PATH_RE: /\/jingxuan/,
  TAB_ACTIVE_CLASS_RE: /(active|selected)/i
  // …
});
```

正则说明：5 条正则均**无 `g` 标志**，`test()` 不推进 `lastIndex`，跨调用复用安全；`TAB_ACTIVE_CLASS_RE` 的 `i` 标志原样保留。

### 4.2 `RENDER_DATA_IDS`（365）

```js
function scanRenderData() {
  let el = null;
  for (let i = 0; i < DyAdapter.RENDER_DATA_IDS.length; i++) {
    el = document.getElementById(DyAdapter.RENDER_DATA_IDS[i]);
    if (el) break;
  }
  if (el && el.textContent) { /* 以下不变 */ }
```

断言：**按数组顺序取第一个非空元素，与 `||` 链的短路顺序一致**。

### 4.3 `TABLIST_SELS` / `TAB_SELS`（2125 / 2129、2140、2154）

```js
const lists = document.querySelectorAll(DyAdapter.TABLIST_SELS.join(", "));
const tabs  = list.querySelectorAll(DyAdapter.TAB_SELS.join(", "));
```

断言按评审要求写成**逐字比原串**，不比元素列表：

- `DyAdapter.TABLIST_SELS.join(", ") === '[role="tablist"], [class*="semi-tabs-tab-list"], [class*="semi-tabs-bar"]'`
- `DyAdapter.TAB_SELS.join(", ") === '[role="tab"], [class*="semi-tabs-tab"]'`

`2167` 的 `'[role="tab"], [class*="tab"]'` 走 `TAB_CLOSEST_SEL`，单串，不并键。

### 4.4 `380` 行播放器根节点 —— **本批不动**

评审 §五第 2/3 行意见接受，`PLAYER_ROOT_IDS`、`PLAYER_ROOT_SELS` 撤回。该行保持原文，包括链尾 `document.body`：

```js
const root = document.getElementById("sliderVideo") || document.querySelector("#douyin-right-container") || document.body;
```

### 4.5 `isPlayerMode()`（1080–1082）

```js
function isPlayerMode() {
  return DyAdapter.MODAL_ID_RE.test(location.href) || DyAdapter.VIDEO_PATH_RE.test(location.pathname);
}
```

两个键**不可合并**：前者测 `location.href`、后者测 `location.pathname`，合并成数组会改变被测字符串。

### 4.6 `CARD_CLOSEST_SELS`（1291–1297）

```js
let card = null;
for (let i = 0; i < DyAdapter.CARD_CLOSEST_SELS.length; i++) {
  card = a.closest(DyAdapter.CARD_CLOSEST_SELS[i]);
  if (card) break;
}
card = card || a.parentElement;
```

断言：返回值与 5 级 `||` 链一致，`parentElement` 兜底保留。

### 4.7 `SCROLL_ROOT_HINTS`（1156–1157）

```js
for (let i = 0; i < DyAdapter.SCROLL_ROOT_HINTS.length; i++) {
  const nodes = document.querySelectorAll(DyAdapter.SCROLL_ROOT_HINTS[i]);
```

## 5. 全量「旧名 → 新键」映射表（评审 §五第 5 行）

只有 `AUTO_CHANNELS → CHANNELS` 一处是改名；其余 35 键原本都是内联字面量，没有旧标识符。

| # | 旧标识符 / 旧形态 | 新键 |
|---|---|---|
| 1 | 内联（365 两个 `getElementById`） | `RENDER_DATA_IDS` |
| 2 | 内联（390、1284） | `USER_LINK_SEL` |
| 3 | 内联（412） | `MIX_LINK_SEL` |
| 4 | `hints`（1147） | `SCROLL_ROOT_HINTS` |
| 5 | `texts`（1186） | `LOAD_MORE_TEXTS` |
| 6 | 内联（1187） | `LOAD_MORE_SCAN_SEL` |
| 7 | `selectors`（1202） | `NEXT_PLAYER_SELS` |
| 8 | 内联（1220） | `VIDEO_SEL` |
| 9 | 内联 closest 链（1292–1296） | `CARD_CLOSEST_SELS` |
| 10 | 内联（1305） | `VIDEO_LINK_SEL` |
| 11 | 内联（2125） | `TABLIST_SELS` |
| 12 | 内联（2129/2140/2154） | `TAB_SELS` |
| 13 | 内联（2161） | `TAB_FALLBACK_SCAN_SEL` |
| 14 | 内联（2167） | `TAB_CLOSEST_SEL` |
| 15 | 内联（1326） | `RELATED_SCOPE_SEL` |
| 16 | 内联（1335） | `RELATED_ITEM_SEL` |
| 17 | 内联（398） | `IMG_SEL` |
| 18 | 内联（1372） | `CLICKABLE_SCAN_SEL` |
| 19 | `CAPTCHA_TEXTS`（68，同名） | `CAPTCHA_TEXTS` |
| 20 | `LOGOUT_TEXTS`（69，同名） | `LOGOUT_TEXTS` |
| 21 | 内联（411/1062/1303） | `MIX_TEXT` |
| 22 | 内联（1332/1343） | `RELATED_TITLE_TEXT` |
| 23 | 内联（1343） | `MORE_TEXT` |
| 24 | 内联（1437） | `SORT_BY_TEXT` |
| 25 | 内联（1437） | `CONTENT_FORM_TEXT` |
| 26 | **`AUTO_CHANNELS`（9）** | **`CHANNELS`（改名）** |
| 27 | 内联（2131） | `CHANNEL_ALL_TEXT` |
| 28 | 原 `PLAYER_URL_RES` 前半 | `MODAL_ID_RE` |
| 29 | 原 `PLAYER_URL_RES` 后半 | `VIDEO_PATH_RE` |
| 30 | 内联（1085） | `SEARCH_PATH_RE` |
| 31 | 内联（1539） | `JINGXUAN_PATH_RE` |
| 32 | 内联（607） | `JINGXUAN_KEY` |
| 33 | 内联（2100） | `SEARCH_URL_BASE` |
| 34 | 内联（2146） | `TAB_SELECTED_ATTR` |
| 35 | 内联（396/1311） | `ARIA_LABEL_ATTR` |
| 36 | 内联（2148） | `TAB_ACTIVE_CLASS_RE` |

## 6. 不入表清单（含新增排除项）

### 6.1 `AI_DECLARE_TEXTS`（parse.js:5–12）—— 明确不入表

1. 它是**判定口径**不是**页面结构**，与 `AI_SUSPECT_TEXTS`（parse.js:13）同组，拆开会让「什么算 AI 声明」散在两处。
2. parse.js 现有 76 条回归与导出面（`blobHasDeclare` / `blobHasSuspectOnly`）已锁；入表等于同时改判定模块与测试口径，超出 B3-a 边界。
3. 页面改版时声明文案若变，属「判定口径变更」，需人工确认，不应由适配表顺手改掉。

### 6.2 自家面板 DOM 选择器（30 处 + 1 处）—— 不入表

评审指出的遗漏，补一行留档。`[data-k=…]` / `[data-act=…]` / `[data-do]` 共 30 处：

`726, 727, 733, 738, 739, 742–746, 752, 754, 756, 758, 759, 765, 772, 908, 917, 921, 924, 927, 932, 940, 980, 1033, 1582, 1584, 1871, 1878`

外加 `1111` 的 `el.closest("#" + PANEL_ID)`。

**不入表理由**：这些选择器指向我们自己注入的浮层（`PANEL_ID` 下的 DOM），不随抖音改版变动；放进适配表会让人误以为它们是页面契约，且改动面板结构时会被误判成「适配变更」。

### 6.3 其余不入表项

| 项 | 现位置 | 理由 |
|---|---|---|
| `AI_SUSPECT_TEXTS` | parse.js:13 | 同 6.1，属判定口径 |
| `SEC_UID_RE` | parse.js:4 | 平台 ID 形态，非页面结构；已随 parse 导出面锁定 |
| `SEARCH_CATEGORY` / `SEARCH_TOPIC` / `SEARCH_TOOLS` / `SEARCH_EXTRA` / `SEARCH_WORDS` | content.js:11–57 | 业务词池（255→300 条），不是页面适配；单列「词库外置/可配置」批 |
| `TYPE_WHITELIST` / `TYPE_BLACKLIST` | content.js:89–90 | 业务判定词表，与 `CHANNELS` 同串不同义 |
| `IDLE_PROMPT_MS` / `NO_RESPONSE_MS` / `SWITCH_GRACE_MS` / `WARMUP_MS` / `EMPTY_SCAN_SWITCH_MS` / `RELOAD_BACKOFF_MS` / `RECOVERY_STABLE_MS` / `SWITCH_AFTER_SCROLLS` / `SWITCH_SEARCH_AFTER` / `SWITCH_SEARCH_MIN_MS` / `DYNAMIC_WORDS_MAX` | content.js:10、60、66–79 | 行为参数，改它等于改行为 |
| `MSG_HOOK` / `MSG_CTL` / `PANEL_ID` / `SOURCE` / `CONTROL` | content.js:6–8、page-hook.js:11–12 | 内部消息协议，两侧必须同时改 |
| `IGNORE` / `INTEREST`（URL 正则） | page-hook.js:14–17 | MAIN world，本批不扩大注入面 |
| `UPLOAD_ENDPOINT` | background.js:32 | 属 B1，本批不碰 |
| `DOUYIN_RE` / `STATE_*` / `REMIND_*` | background.js:20–42 | guard 状态机参数 |
| `DEFAULT_SETTINGS` 等 | shared.js | 已归 shared.js（P2） |
| 带捕获组的 3 条正则 | content.js:141、152、1307 | 见 §3.7 |

### 6.4 parse.js 字段别名表（推迟到 P3-b）

`pick()` 的字段别名数组（如 `["mix_id","mixId","id","ids"]`、`["nickname","nickName","remarkName"]`）本质也是「接口结构适配」，但：被 76 条回归直接锁定、与 DOM 层不同源、动它必须同批改 parse 测试。建议单列 **P3-b：解析字段别名表收拢**。

## 7. 落位与装配

### 7.1 文件位置与注入顺序

新增 `content/adapter.js`，`manifest.json` **第 40 行**改一处（v1 误写第 41 行，此处更正）：

```diff
- "js": ["shared.js", "content/parse.js", "content/content.js"],
+ "js": ["shared.js", "content/adapter.js", "content/parse.js", "content/content.js"],
```

- 仍在 `content_scripts[1]`（ISOLATED），**不进 MAIN world**；
- popup 不加载（popup 无页面选择器）；
- `content_scripts[0]` 不动，`page-hook.js` 不动。

备选方案（不推荐）：塞进 `shared.js`。缺点：popup 会连带加载一堆抖音 DOM 选择器，且会把「shared.js UMD 化」那批拖下水。

### 7.2 page-hook.js 为何本批不动

它运行在 MAIN world，把表注进主世界等于把一张可读的适配表暴露给页面脚本；且 page-hook 只有 2 条正则，收拢收益小于注入面扩大的风险。留作 P3-b 单独评估。

## 8. 改动面与验证

### 8.1 计数纠错（对应评审 §七）

| 项 | v1 写的 | 实际 |
|---|---|---|
| 局部常量个数 | 删 5 处 | **删 6 个定义**：`AUTO_CHANNELS`(9)、`CAPTCHA_TEXTS`(68)、`LOGOUT_TEXTS`(69)、`hints`(1147–1155)、`texts`(1186)、`selectors`(1202–1212) |
| 删除行数 | 未单列 | **24 行**：1 + 1 + 1 + 9 + 1 + 11 |
| 引用点 | 约 30 处 | **53 处字面量 / 49 行**（分解见 §3.8；评审实测的 23 处 DOM 调用点 + 文本锚点，本稿按字面量逐个计，含同一行的多次出现） |
| content.js 改动行数 | ~45 行 | **删 24 行 + 改 49 行**（交付时按实际 diff 报，不再用「约」） |
| manifest 行号 | 第 41 行 | **第 40 行** |

### 8.2 改动面

| 文件 | 改动 |
|---|---|
| content/adapter.js | 新增（纯数据，36 键，10 个数组各套一层 `Object.freeze`） |
| content/content.js | 删 6 个局部常量定义（24 行）+ 53 处引用替换（49 行） |
| manifest.json | 第 40 行 1 行 |
| tests/shared.bydev.test.js | 第 211 行断言改写（见 8.3） |
| tests/adapter.test.js | 新增（包外源码资产） |

无逻辑分支增删、无文案改动、无 `shared.js` 变更。

### 8.3 阻塞项 2：同批改 `tests/shared.bydev.test.js` 第 211 行

现状是精确数组等值，插入 `adapter.js` 后必红：

```js
eq("manifest 注入顺序 shared 在最前", cs.js, ["shared.js", "content/parse.js", "content/content.js"]);
```

改为**相对序**，不再写新的精确数组：

```js
eq("manifest 注入顺序 shared 在最前", cs.js[0], "shared.js");
ok("adapter.js 在 parse.js 之前", cs.js.indexOf("content/adapter.js") !== -1 && cs.js.indexOf("content/adapter.js") < cs.js.indexOf("content/parse.js"));
ok("shared.js 早于 content.js", cs.js.indexOf("shared.js") < cs.js.indexOf("content/content.js"));
ok("adapter.js 只在 ISOLATED 组", cs.world === "ISOLATED");
```

（`ok(...)` 用该测试文件已有的断言风格，落地时按文件内既有 helper 名对齐。）

### 8.4 验证

1. `node --check`：`content/adapter.js`、`content/content.js`；`manifest.json` 走 `JSON.parse`。
2. 三口径全绿：
   - `node tests/shared.test.js --base=<基线解包目录>` → **129 passed**（含 S7 那 24 条新旧差分）；
   - `node tests/parse.test.js` → **76 passed**；
   - `node tests/shared.bydev.test.js` → **78 passed**（改完 211 行后）。
3. 新增 `tests/adapter.test.js`（随包外源码交付，不只报数字）：
   - 36 键的值与基线 `content/content.js` 逐字一致（差分法，基线从 `BASELINE-dyaccount-v1.3.3.zip` 解包）；
   - 正则比 `.source` + `.flags`；
   - `TABLIST_SELS.join(", ")`、`TAB_SELS.join(", ")` 逐字等于基线原串；
   - 每个数组值 `Object.isFrozen(DyAdapter.X) === true`；
   - `content/content.js` 内清单字面量已清零，且 421/1313/783 三行逐字未变、`1093/1797/1800/1803/89/1861/1865` 未变；
   - `manifest.json` 的 `content_scripts[1].js` 顺序为 `shared.js → adapter.js → parse.js → content.js`，且 `adapter.js` 不出现在 `content_scripts[0]` 与 `popup.html`。
4. 实机：重载扩展 → 采集页 / 搜索页 / 播放页各看一次面板状态与换台（无实机验证不写「已完成」）；记录写当时目录的 `manifest.json` + `content/adapter.js` + `shared.js` 三个 md5。

### 8.5 与三条遗留意见的关系

- 「删 shared.js 第 29–30 行测试条数」：本批不动 shared.js，随下次触及 shared.js 一并处理。
- 「SHA256SUMS 改逐文件 sha256 + 时间戳」：本批交付按新格式出。
- 「实机验证文档写三个 md5」：已并入 8.4 第 4 条。

## 9. 按评审第八节的收包判据自检

| # | 判据 | 本稿对应 |
|---|---|---|
| 1 | 包内仅 3 个文件变动 | §8.2（测试文件属包外源码资产，不进包） |
| 2 | 36 键逐字相同 | §2 + §8.4 第 3 条 |
| 3 | 421 / 1313 / 783 逐字未变 | §3.1 + §8.4 第 3 条 |
| 4 | 加载面未扩大 | §7.1 + §8.4 第 3 条 |
| 5 | 129 / 76 / 78 / adapter.test 交付文件 | §8.3 + §8.4 |
| 6 | 实机 + 三个 md5 | §8.4 第 4 条 |

## 10. 状态

- 评审 §六 三问：范围 ✅、落位 ✅、命名 ✅ + 深冻结 ⚠️（已按 §4.1 处理）。
- 评审 §二 / §三 两个阻塞项：已按 §3、§8.3 写入交付说明。
- 评审 §四 / §五 / §七：已按 §0.1、§2、§4、§5、§8.1 补全。

源码仍未改动。请回「通过」后再动第一行。
