# P3-b 交付说明（dyaccount_v1.3.3-p3b）

状态：**已达到 P3-a 同口径交付标准，待评审**。

依据：`P3b-B3b-适配表-diff预览.md`（按稿实施，未改口径）。

## 1. 交付物

| 项 | 位置 |
| --- | --- |
| 交付文件夹 | `C:\Users\dataeye\Desktop\P3b-交付\`（zip + SUMS + 评审版说明 + diff 预览稿 + `tests\` 6 文件，共 10 文件） |
| 发布包 | `P3b-交付\dyaccount_v1.3.3-P3b.zip`（25 条目，20,413,641 B，sha256 `E013A1CBAF0B84A9589F96F12E4EB4DF700F27E2D70873FE5A57754E0E86BCF0`，MD5 `62AA4A6A84192C9C2B90788BD38C38B3`） |
| 逐文件清单 | `P3b-交付\dyaccount_v1.3.3-P3b-SHA256SUMS.txt`（25 条 sha256 + mtime、2 个变动文件新旧 sha256/md5、测试结果、包外测试 6 文件 sha256）；摘要同项内嵌 §3.1 |
| diff 预览稿 | `P3b-交付\P3b-B3b-适配表-diff预览.md`（本批实施依据，随交付补发） |
| 源码目录 | `C:\Users\dataeye\Desktop\dyaccount_v1.3.3-p3b\` |
| 包外测试（不进包） | `tests\adapter.test.js`、`tests\parse.test.js`、`tests\shared.test.js`、`tests\shared.bydev.test.js`、`tests\README.md`、`tests\fixtures\search-response.json` |

包内条目与 P3-a 白名单一致（无 `.bak`、无 `tests\`、无 `.lnk`、无 `fixtures\`），解包后顶层目录名 `dyaccount_v1.3.3-p3b`。

解包回验：25/25 文件 sha256 与源码目录逐字节一致，0 mismatch；条目数与 P3-a 同为 25，无增删。

### 1.1 本批仅 2 个文件变动（相对 P3-a 包实测）

P3-a 包解包后与 P3-b 包逐文件比 md5，只有 2 项不同、无新增/删除：

| 文件 | P3-a md5 → P3-b md5 | P3-a sha256 → P3-b sha256 |
| --- | --- | --- |
| `content/adapter.js` | `2B8583EF8CDA64E2895DAC32C6737389` → `CEFCB4E0DC40EAF83DBEF869B3085780` | `4904f9ed…f4239d` → `5e1a4165…74bf25` |
| `content/parse.js` | `10EBF8D1554F12F4FC1B868408C2B861` → `0434016A1B41D560E0AA9486947CFB32` | `2fc5e505…0bca8a` → `a86d5d6d…26ddc4` |

其余 23 项（含 `manifest.json`、`content/content.js`、`shared.js`、`content/page-hook.js`、popup 三件、图标、文档）与 P3-a 包逐字节相同；解包后 25/25 文件已逐一回验，摘要见 §3.1。

## 2. 改了什么

| 文件 | 改动 | 改后 md5 |
| --- | --- | --- |
| `content/adapter.js` | 新增 §5 `PICK_ALIAS`（9 组，表 + 9 个子数组各 `Object.freeze`）；第 112 行 `TAB_ACTIVE_CLASS_RE` 补尾逗号；头注释同步（37 键 / P3-a + P3-b） | `CEFCB4E0DC40EAF83DBEF869B3085780` |
| `content/parse.js` | 顶部 +4 行（`var PK = root.DyAdapter.PICK_ALIAS;` + 3 行注释）；9 处 `pick()` 的字面量数组换成 `PK.*` | `0434016A1B41D560E0AA9486947CFB32` |
| `tests/adapter.test.js` | EXPECTED 追加 `PICK_ALIAS`；键数 36→37；`PARSE` 常量；新增 §3.1 冻结组与 §7 parse.js 引用组；`--base` 分支跳过 `PICK_ALIAS` | — |
| `tests/parse.test.js` | eval adapter.js 前置 + `PICK_ALIAS` 存在性守卫；新增 T0b / T0c 两条断言 | — |
| `tests/README.md` | 76→78；补「parse.js 依赖同目录 adapter.js」 | — |

未动（已与 P3-a 包逐字比对，md5 全同）：`manifest.json` `7FB0D3EE0FE2E52DC44217A83664B9E4`、`shared.js` `0CCC70F889EAC7BCA75E1517BB54B724`、`content/content.js` `042FE5ED749B4CFA020FAAA835D2A2E0`、`content/page-hook.js` `099E3B20F45FC079D40679EC2E00833B`。

`parse.js` 中 17 处双键直读（含第 168 行 `pick()` 的 `aweme.author_user_id || aweme.authorUserId` fallback）按稿保留，未并入别名表。

## 3. 测试结果（实跑）

```
node --check content/adapter.js ; node --check content/parse.js   # 均通过
node tests/adapter.test.js                                        # 209 passed, 0 failed
node tests/adapter.test.js --base=<BASELINE 解包>                  # 211 passed, 0 failed
node tests/parse.test.js                                          # 78 passed, 0 failed
node tests/shared.test.js --base=<BASELINE 解包>                   # 129 passed, 0 failed
node tests/shared.bydev.test.js                                   # 78 passed, 0 failed
```

`--base` 用的是基线真源 `Desktop\BASELINE-dyaccount-v1.3.3.zip` 解包后的目录（不是 `Desktop\dyaccount_v1.3.3\`——那个目录已是 P2 重构后状态，`content.js` 里 `selectAuthors` 已迁走，拿它跑 S7 会假失败 7 条）。

运行环境 `node v24.20.0`；`node --check` 两文件均 0 error。上表计数为交付前复跑结果，与 §3 首跑一致。

测试文件本体（包外，随源码交付）：

| 文件 | 大小 | sha256 |
| --- | --- | --- |
| `tests/adapter.test.js` | 16,250 B | `c32bfc8eb0001a914dd81f0745530f77f54aee501aea158033d0eca6242572de` |
| `tests/parse.test.js` | 11,007 B | `8044a09ac50a5c494685da5d49a9a7db8cbad3c28b4554a388b724ba0166bf5c` |
| `tests/shared.test.js` | 20,542 B | `fd11652949942e18d92aa356d530a7add20da2cd84bc4257a063fb720bff0114` |
| `tests/shared.bydev.test.js` | 12,108 B | `dfcafbc27a8775b0bcf1a4bda63b6a0c4d26efea0426cfc4ae02b8a0be4503a6` |
| `tests/README.md` | 4,522 B | `9f80f52eb9cf783a279d665b1aa965f65c4c121af8bbd6a3a15cfb3b3b580c2f` |
| `tests/fixtures/search-response.json` | 3,001 B | `b091bb24ceb8b0a7a2da70ba9c5893a453a197c00a05c528357bb8936ba654e8`（md5 `1aba206912fa98d4d228a2dfaab074e5`） |

跑法（只用交付文件即可复现 78）：把 `P3b-交付\tests\` 整个拷进解包后的 `dyaccount_v1.3.3-p3b\` 根目录下，在该根目录执行 `node tests\parse.test.js`。实测输出 `parse.js fixture 回归: 78 passed, 0 failed`。`tests\` 必须落在包含 `content\adapter.js`、`content\parse.js` 的根目录（测试按 `tests/../` 取默认路径），单独放桌面跑会因找不到 `content\adapter.js` 报 ENOENT。

`shared.test.js` 计数有两档：不带 `--base` 为 **105**（评审端口径），带 `--base=<P2 重构前基线>` 为 **129**（本文与预览稿口径，含 S7 差分）。两档均全绿，不是差异。

与稿内预估的唯一偏差：`adapter.test.js --base` 实跑 211 而非 210。原因是基线分支本身带 2 条断言（`基线 content.js 存在` + `其余 36 键…全找到`），原估少算 1 条。计数口径已在预览稿 §9 更正。

### 3.1 校验摘要（与 SUMS 文件同项，双份）

```
PACKAGE      dyaccount_v1.3.3-P3b.zip
ZIP_SHA256   e013a1cbaf0b84a9589f96f12e4eb4df700f27e2d70873fe5a57754e0e86bcf0
ZIP_MD5      62aa4a6a84192c9c2b90788bd38c38b3
ZIP_SIZE     20413641
ZIP_ENTRIES  25
BASELINE     BASELINE-dyaccount-v1.3.3.zip / b25e4cd9a793bab924f305d9a09eba0d62d1e93a674a854fcec582962df3152d / 20424662 B / 23 entries
PREDECESSOR  dyaccount_v1.3.3-P3a.zip / 96d7ca609d93a9b6114024bfd6e1cd2cde75fd4cb98884a4fd053fbf323a0b3c / 20413240 B / 25 entries
BATCH        P3-b only (adapter.js PICK_ALIAS 9 groups + parse.js 9 sites -> PK.*)
TESTED_WITH  node v24.20.0

CHANGED VS P3-a (2 files only)
content/adapter.js  4904f9ed478f361b6a0fcf6c8be32f1c8f21016eb76181d7c6c6092462f4239d
                 -> 5e1a416579a6bfa87d5f414a5534fae734a9034dd4258336c042b9c33774bf25   md5 cefcb4e0dc40eaf83dbef869b3085780
content/parse.js    2fc5e5055bb128169da3b6e1991375c6441dc73ba203e1e6d364011e4b0bca8a
                 -> a86d5d6ddf7318407c06bf64d3bf010537bc773fc73856c1d9e79e677426ddc4   md5 0434016a1b41d560e0aa9486947cfb32
```

解包回验：zip 内 25/25 条目 sha256 与源码目录 `dyaccount_v1.3.3-p3b\` 同路径文件逐字节一致，0 mismatch。

## 4. 变异自检（3/3 被抓）

| 变异 | 结果 |
| --- | --- |
| `parse.js` 的 `PK.AUTHOR_NICKNAME` 换成 `[]` | `parse.test.js` 77/1 → `FAIL T2.3 昵称` |
| `adapter.js` 的 `MIX_ID` 删掉 `"ids"` | `adapter.test.js` 208/1（无 base）、210/1（带 base）→ `FAIL PICK_ALIAS 值` |
| `parse.js` 的一处 `PK.MIX_ID` 改回字面量 | `adapter.test.js` 207/2 → `FAIL PK. 引用 = 9 处`、`FAIL 无别名字面量副本` |

三次变异后均已从备份还原，还原后复跑 209 / 78 全绿。

## 5. 实机四步（已回填 · 2026-10-21）

环境：桌面 Chrome（已登录「锦灏」），扩展 1.3.3 未打包加载。

**扩展 ID = `adhebijeohhlafhhkgkicfgifaelbkgc`**。校验：`SHA256("C:\Users\dataeye\Desktop\dyaccount_v1.3.3-p3b" 的 UTF-16LE 编码)` 取前 16 字节、按 `0-9a-f → a-p` 映射，结果与该 ID 逐字符相同；同法算 P3-a 目录得 `ohdbpmbokpkpdpjaapgmbjkeemkmmbph`，与当前 ID 不同 → 确认是从 P3-b 目录加载、`chrome.storage` 已重置（面板「当日起点 2026-10-21 17:55:15」）。

当时目录三 md5（`Get-FileHash -Algorithm MD5`，与 §2 完全一致）：

| 文件 | md5 |
| --- | --- |
| `manifest.json` | `7FB0D3EE0FE2E52DC44217A83664B9E4` |
| `content/adapter.js` | `CEFCB4E0DC40EAF83DBEF869B3085780` |
| `shared.js` | `0CCC70F889EAC7BCA75E1517BB54B724` |

### 5.1 精选页（第 2 步）— 通过

面板正常挂载，`/jingxuan` 采集至 **45 作者 / 505 视频**，导出 `Downloads\douyin-ai-mix-authors (7).json`（45 条）：

| 字段 | 非空 | 说明 |
| --- | --- | --- |
| `mixName`（`MIX_NAME`） | 45/45 | 如「东问西问」 |
| `mixId`（`MIX_ID`） | 45/45 | 如 `7584450113158809643` |
| `nickname`（`AUTHOR_NICKNAME`） | 45/45 | 如「角头内心戏」 |
| `secUid`（`AUTHOR_SEC_UID`） | 45/45 | 45/45 符合 `^MS4wLjABAAAA[A-Za-z0-9_-]{20,}$` |
| `uid`（`AUTHOR_UID`） | 45/45 | — |
| `uniqueId`（`AUTHOR_UNIQUE_ID`） | 42/45 | 未暴露的 3 条为空，属上游字段缺失 |
| `aigcReason` | 45/45 | 全部 `text:内容由AI生成` |

### 5.2 搜索页（第 3 步）— 通过

切到 `/search/内容由AI生成?type=video`，点「开始采集」+「自动下滑」（两个勾选项「必须挂载合集」「必须「内容由AI生成」」均开启），计数从 **45→52 作者、505→550 视频**；导出 `douyin-ai-mix-authors (8).json`（52 条）：

- 新增 7 条全部来自搜索页：骆驼传媒.啊伟骆🐫/螺学培训班、刀侠客/斗罗之罐子商人、宗主-石影/全民筑基计划、陈野Neo/【天禄卦所】、菀柠剧场/这里的短剧太精彩了 根本停不下来、块块-AIGC/丘$丘《瓦龙拾取》$、拾光剧场——芩首/热门短剧点合集观看后续
- 52 条字段：`mixName` / `mixId` / `nickname` / `secUid` / `uid` / `avatar` / `aigcReason` 均 52/52 非空，`secUid` 52/52 合规
- `aigcReason` 52/52 全为 `text:内容由AI生成`；页面视频详情同时显示「作品声明：内容由AI生成」
- 差异项：`uniqueId` 42/52 —— 新增的 7 条全空（搜索页卡片 DOM 不带 `unique_id`），非 P3-b 改动引入

### 5.3 播放页（第 4 步）— 通过

打开 `https://www.douyin.com/video/7698361308593286586`，浮层面板正常（提示语切为「播放中可自动切下一条」），点「开始采集」后计数 **550→570**；导出 `Downloads\douyin-publish-times (2).csv`（524 行，「发布时刻」524/524 非空）。该视频行：`发布时刻 2026-10-19 20:53:39 / 有合集 1 / 有AI声明 1 / 合集ID 7686372445539797038`。

`发布时刻` 由 `createTime` 秒级时间戳换算，524/524 有值即 `CREATE_TIME` 在播放页链路取到。

### 5.4 一条非阻塞观察

窗口非前台时点「复制 sec_uid」，剪贴板未写入（读到的仍是旧内容）。原因是 `navigator.clipboard` 在失焦文档下写入被拒，与 P3-b 无关；窗口置于前台时应正常。

## 6. 回滚

两文件各自独立：`adapter.js` 删 §5 段（13 行）、去掉 112 行尾逗号、头注释回退；`parse.js` 删顶部 4 行、9 处 `PK.*` 换回字面量。不涉及 `manifest.json`，不涉及 `page-hook.js`。

## 7. 按 P3-a 收包判据 6 条自检

判据来源：`Desktop\P3a-交付说明-发评审AI.md` §1–§5 与 `P3a-B3a-适配表-diff预览.md` §9。P3-a 已按此 6 条验收。

| # | 判据 | 本批对应 | 结论 |
| --- | --- | --- | --- |
| 1 | 包内变动文件数符合本批声明 | P3-a 包 vs P3-b 包逐文件比 md5：仅 `adapter.js`、`parse.js` 不同，条目均 25，无增删（§1.1） | 通过（2 文件，与预览稿 §8.1 一致） |
| 2 | 适配表键逐字相同 | 原 36 键不变；新增第 37 键 `PICK_ALIAS`，9 组值与 `parse.js` 原 9 处字面量逐字一致 —— `adapter.test.js --base` 211 全绿、`parse.test.js` 78 全绿（§3、§4） | 通过 |
| 3 | 硬保留行逐字未变 | `content/content.js` md5 `042FE5ED749B4CFA020FAAA835D2A2E0` 与 P3-a 相同 → 421/783/1313、1093/1797/1800/1803/89/1861/1865、380 行必然未动；`adapter.test.js` 内对应断言同批全绿 | 通过 |
| 4 | 加载面未扩大 | `manifest.json` md5 `7FB0D3EE0FE2E52DC44217A83664B9E4` 与 P3-a 相同；实测 `content_scripts[0]`（MAIN）仅 `content/page-hook.js`，`content_scripts[1]`（ISOLATED）为 `shared.js → content/adapter.js → content/parse.js → content/content.js` | 通过 |
| 5 | 测试计数 + 测试文件本体交付 | 209 / 211 / 78 / 129 / 78，`node --check` 0 error；测试文件 5 个 + fixture 1 个，大小与 sha256 已列（§3），fixture 补齐后 78 可复核 | 通过 |
| 6 | 实机验证 + 当时目录三 md5 | 精选 / 搜索 / 播放三页实测记录 + 扩展 ID 校验 + `manifest.json` / `adapter.js` / `shared.js` 三 md5（§5） | 通过 |

包级校验：`dyaccount_v1.3.3-P3b.zip` sha256 `E013A1CB…0E86BCF0`、25 条目、20,413,641 B；SUMS 与 zip 同批（`P3b-交付\dyaccount_v1.3.3-P3b-SHA256SUMS.txt`），同项摘要内嵌 §3.1。

未闭环项：无阻塞项。§5.2 的搜索页 `uniqueId` 42/52 已注明为上游 DOM 字段缺失，非本批改动引入。
