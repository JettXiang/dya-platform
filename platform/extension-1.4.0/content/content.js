const MONITOR_MODE = false;

/* DEFAULT_SETTINGS / REFRESH_MIN_LIMIT / REFRESH_MAX_LIMIT 统一来自 shared.js
 * （manifest content_scripts 里 shared.js 已前置注入，顺序见该文件头注释） */

const MSG_HOOK = "dyaccount-hook";
const MSG_CTL = "dyaccount-ctl";
const PANEL_ID = "dyaccount-panel";
/* 页面适配常量统一来自 content/adapter.js（DyAdapter），本文件不再定义 */
const SWITCH_AFTER_SCROLLS = 40;
const SEARCH_CATEGORY = ["AI漫剧", "AI短剧", "AIGC漫剧", "AIGC短剧", "AI动画", "AIGC动画", "AI动漫", "AI动态漫", "AI仿真人剧"];
// v1.1.4：题材词按 3517 位已采作者语料的实际命中率重排
// 删除命中 0 的死词（赘婿/甜宠/女婿/打脸/都市/玄幻/悬疑/神医/龙王/赛博），保留数据反推的高产词
const SEARCH_TOPIC = [
  "逆袭", "重生", "穿越", "系统", "校花", "修仙", "末世", "王妃",
  "战神", "豪门", "复仇", "末日", "山海", "西游", "宇宙", "古代",
  "志怪", "神话", "大明", "传奇", "聊斋", "斗罗", "开局", "异世界"
];
const SEARCH_TOOLS = ["即梦", "可灵", "Vidu", "海螺", "Runway", "Pika", "剪映AI", "Sora", "Veo", "PixVerse", "Seedance", "可灵2.0", "通义万相"];
// v1.6：新增 45 条扩展搜索词（255 -> 300 条）
// 来源：3128 位已采作者的合集名/昵称高频语料 + 平台新内容形态
// 约束：每条都锚定 TYPE_WHITELIST 词（短剧/漫剧/动画/剧场/剧集/AIGC），
//      确保能过 isTargetType 准入；刻意避开 TYPE_BLACKLIST 词
//      （科普/解说/人生副本/民间故事 等，搜到也会被判定条件过滤，等于空转）
const SEARCH_EXTRA = [
  // C 组 游戏/IP 二创（12）：锚定「动画」
  "三角洲行动 动画", "燕云十六声 动画", "黑神话悟空 动画", "我的世界 动画", "原神 动画", "植物大战僵尸 动画",
  "和平精英 动画", "火影忍者 动画", "宝可梦 动画", "蛋仔派对 动画", "元梦之星 动画", "第五人格 动画",
  // D 组 新形态/格式（14）
  "AI微短剧", "AI竖屏短剧", "AI系列短剧", "AI连续短剧", "AIGC短片", "AI短片", "AI小剧场",
  "AI剧集", "AIGC剧集", "AI国风短剧", "AI都市短剧", "AI悬疑短剧", "AI玄幻短剧", "AI古风短剧",
  // E 组 连载/季标签（10）
  "AI短剧 第一季", "AI短剧 第二季", "AI漫剧 第一季", "AI漫剧 第二季", "AI动画 第一季",
  "AI短剧 全集", "AI漫剧 全集", "AI短剧 连载", "AI漫剧 连载", "AI短剧 持续更新",
  // F 组 工具补充（9）：3 个工具 x 3 形式
  "混元 短剧", "混元 漫剧", "混元 动画",
  "Luma 短剧", "Luma 漫剧", "Luma 动画",
  "海螺AI 短剧", "海螺AI 漫剧", "海螺AI 动画"
];

const SEARCH_WORDS = (function () {
  const out = [];
  for (let i = 0; i < SEARCH_CATEGORY.length; i++) {
    for (let j = 0; j < SEARCH_TOPIC.length; j++) {
      out.push(SEARCH_CATEGORY[i] + " " + SEARCH_TOPIC[j]);
    }
  }
  const forms = ["短剧", "漫剧", "动画"];
  for (let i = 0; i < SEARCH_TOOLS.length; i++) {
    for (let j = 0; j < forms.length; j++) {
      out.push(SEARCH_TOOLS[i] + " " + forms[j]);
    }
  }
  // v1.6：并入第三张网（游戏IP / 新形态 / 连载标签 / 工具补充）
  for (let i = 0; i < SEARCH_EXTRA.length; i++) out.push(SEARCH_EXTRA[i]);
  return out;
})();
// v1.7：动态词池——从搜索页右侧「相关搜索」板块被动读取（页面自身已渲染，不额外发请求）
// 仅本次会话有效，不落盘；用于补充平台实时推荐的关联词，扩大未被覆盖的内容池
const DYNAMIC_WORDS_MAX = 300;
let dynamicWords = [];
let lastHarvestAt = 0;
function allSearchWords() {
  return dynamicWords.length ? SEARCH_WORDS.concat(dynamicWords) : SEARCH_WORDS;
}
const SWITCH_SEARCH_AFTER = 30;
const SWITCH_SEARCH_MIN_MS = 300000;
const IDLE_PROMPT_MS = 5 * 60 * 1000;
const NO_RESPONSE_MS = 90 * 1000;
const SWITCH_GRACE_MS = 30 * 1000;
const WARMUP_MS = 60 * 1000; // 开启采集/自动下滑后的预热期：期内不判「连续无响应」、也不换词
const EMPTY_SCAN_SWITCH_MS = 120 * 1000; // 搜索页扫不到数据时按此间隔换词（快于正常5分钟）
/* 方案 B：非搜索页「连续无响应」不再静默停死，改为按退避序列原地自动刷新（刷新后 boot()
 * 见 collecting 仍为 true 会自动续采），全程无需人工；序列用尽仍无响应才停下转人工。 */
const RELOAD_BACKOFF_MS = [30 * 1000, 60 * 1000, 2 * 60 * 1000, 5 * 60 * 1000];
const RELOAD_TRY_KEY = "dyaccount_nr_try";   // sessionStorage：跨刷新记住本轮已自动刷新次数
const RECOVERY_STABLE_MS = 60 * 1000;        // 连续有数据满该时长才算「真恢复」，可清零重试计数

/* v1.8「时段压缩」实验：采样每条视频的发布时刻(create_time)，用于分析「上新高峰时段」。
 * 独立于 CSV 的 8 列，只落 chrome.storage.local 的 publishLog，由 popup 单独导出。 */
const PUBLISH_LOG_KEY = "publishLog";
const PUBLISH_LOG_MAX = 8000; // 采样上限，超出丢最旧

// ----- v1.1.2：导出时「有效」过滤 -----
const VALID_FILTER_ENABLED = false;  // v1.1.3：导出全量，有效性判定挪到后台分级标记 + LLM/人工复核
const VALID_MIN_VIDEO = 2;           // 视频数阈值：该作者被扫到的视频条数 ≥ 该值才算有效
const TYPE_WHITELIST = ["漫剧", "短剧", "AIGC", "动漫", "动画", "次元", "穿越", "重生", "逆袭", "赘婿", "战神", "系统", "王妃", "豪门", "漫画", "番剧", "小剧场", "剧场", "剧集", "末世", "自制动画"];
const TYPE_BLACKLIST = ["汪汪队", "立大功", "民间故事", "寓言", "动物世界", "怀旧", "童年", "军旅", "科普", "双语", "跟读", "后室", "未解之谜", "领养", "咕嘎", "人生副本", "阅读", "育儿", "儿童", "宝宝", "儿歌", "纪录片", "解说", "混剪", "搬运", "影视解说"];

let settings = { ...DyShared.DEFAULT_SETTINGS };
let authors = {};
let stats = { scanned: 0, matched: 0, lastUrl: "", lastHint: "" };
let recent = [];
let autoTimer = null;
let scannedAweme = new Set();
let matchedAweme = new Set();
let persistTimer = null;
let cachedScrollRoot = null;
let stuckTicks = 0;
let reloading = false;
let lastSwitchAt = 0;
let captchaHandled = false;
let fatal = false;
let lastNewAuthorAt = Date.now();
let lastDataAt = Date.now();
let lastNavAt = Date.now();
let warmUpUntil = 0; // 预热期截止时间戳；期内 detectNoResponse 直接返回
let noResponseReloadTimer = null; // 已排定的「连续无响应」自动刷新定时器
let noResponseReloadAt = 0;       // 已排定的刷新时间戳（0=未排定）
let refreshTimer = null;          // 随机刷新的主定时器
let refreshTickTimer = null;      // 随机刷新倒计时（每秒刷状态文案）
let refreshAt = 0;                // 下次随机刷新时间戳（0=未排定）
let refreshCount = 0;             // 已自动刷新次数：独立存储键，由 background 单点记账（整包写 settings 不会抹掉它）
let refreshSkips = 0;             // 本轮因不在采集页而跳过的次数（不持久化）
let refreshPaused = false;        // 让路：页面故障自动刷新期间暂停随机刷新
let healthySince = 0;             // 连续有数据流的起点（判定「真恢复」用）
let publishLog = [];              // 发布时刻采样：[[awemeId, createTimeSec, secUid, mixId, hasMix, hasAiDeclare, seenAtSec], ...]
let publishSeen = new Set();      // awemeId 去重，避免同一条视频重复采样
let publishWarned = false;        // 「取不到 create_time」只提示一次
let sessionStartAt = Date.now();
let searchOrder = [];
let searchOrderPos = 0;
let authorListCache = null;
let authorListDirty = true;

function $(sel, root) {
  return (root || document).querySelector(sel);
}

function sendHookCollecting() {
  window.postMessage({ source: MSG_CTL, collecting: !!settings.collecting }, "*");
}

function currentAwemeId() {
  try {
    const u = new URL(location.href);
    return (
      u.searchParams.get("modal_id") ||
      (location.pathname.match(/\/video\/(\d+)/) || [])[1] ||
      ""
    );
  } catch (e) {
    return "";
  }
}

// v1.1.4 埋点：当前页面所属的搜索词（从 URL 反推，刷新/跨页都准）
function currentSearchWord() {
  try {
    const m = location.pathname.match(/\/search\/([^/?#]+)/);
    if (m) return decodeURIComponent(m[1]).trim();
  } catch (e) {}
  return "";
}

function bumpWord(rec, word) {
  if (!word) return;
  if (!rec.words) rec.words = {};
  rec.words[word] = (rec.words[word] || 0) + 1;
}

function topWords(a, n) {
  const w = a && a.words ? a.words : {};
  return Object.keys(w)
    .sort((x, y) => w[y] - w[x])
    .slice(0, n || 3)
    .join(" | ");
}

function loadState() {
  return new Promise((resolve) => {
    chrome.storage.local.get(["settings", "authors", "stats", PUBLISH_LOG_KEY, "refreshCount"], (data) => {
      settings = { ...DyShared.DEFAULT_SETTINGS, ...(data.settings || {}) };
      if (!settings.intervalMs || Number(settings.intervalMs) < 4000) settings.intervalMs = 4000;
      refreshCount = Number(data.refreshCount) || 0;
      authors = data.authors || {};
      stats = { scanned: 0, matched: 0, lastUrl: "", lastHint: "", ...(data.stats || {}) };
      stats.matched = Object.keys(authors).length;
      publishLog = Array.isArray(data[PUBLISH_LOG_KEY]) ? data[PUBLISH_LOG_KEY] : [];
      publishSeen = new Set();
      for (const e of publishLog) if (e && e[0]) publishSeen.add(e[0]);
      resolve();
    });
  });
}

function isExtAlive() {
  try {
    return !!(chrome.runtime && chrome.runtime.id);
  } catch (e) {
    return false;
  }
}

function schedulePersist() {
  if (!isExtAlive()) return;
  if (persistTimer) clearTimeout(persistTimer);
  persistTimer = setTimeout(persist, 1500);
}

function persist() {
  if (!isExtAlive()) return;
  stats.matched = Object.keys(authors).length;
  /* 「已刷 N 次」是独立键 refreshCount（不在 settings 里），由 background 单点记账；
     这里整包写 settings 不会再把它抹掉 */
  chrome.storage.local.set({ settings, authors, stats, [PUBLISH_LOG_KEY]: publishLog });
  try {
    chrome.runtime.sendMessage({ type: "badge", count: stats.matched });
  } catch (e) {}
}

function upsertAuthor(record) {
  const secUid = record.author && record.author.secUid;
  if (!secUid) return false;
  const now = Date.now();
  const word = currentSearchWord();
  const prev = authors[secUid];
  if (!prev) {
    authors[secUid] = {
      secUid,
      uid: record.author.uid || "",
      nickname: record.author.nickname || "",
      uniqueId: record.author.uniqueId || "",
      avatar: record.author.avatar || "",
      mixId: (record.mix && record.mix.mixId) || "",
      mixName: (record.mix && record.mix.mixName) || "",
      sampleAwemeId: record.awemeId || "",
      sampleUrl: record.url || "",
      sampleDesc: record.desc || "",
      aigcReason: (record.aigc && record.aigc.reason) || "",
      videoCount: 1,
      words: {},
      firstSeen: now,
      lastSeen: now
    };
    bumpWord(authors[secUid], word);
    authorListDirty = true;
    return true;
  }
  prev.lastSeen = now;
  prev.videoCount = (prev.videoCount || 1) + 1;
  bumpWord(prev, word);
  if (!prev.nickname && record.author.nickname) prev.nickname = record.author.nickname;
  if (!prev.mixName && record.mix && record.mix.mixName) {
    prev.mixName = record.mix.mixName;
    prev.mixId = record.mix.mixId;
  }
  if (!prev.avatar && record.author.avatar) prev.avatar = record.author.avatar;
  authorListDirty = true;
  return false;
}

/* 采样一条视频的发布时刻。与 CSV 无关：只进 publishLog，用于事后统计「上新高峰小时」。
 * 去重按 awemeId；createTime < 1e9（秒级，2001 年前）视为无效，滤掉 DOM 兜底链路。 */
function logPublish(rec) {
  const t = Number(rec.createTime) || 0;
  if (!t || t < 1000000000) return;
  const id = rec.awemeId || "";
  if (id) {
    if (publishSeen.has(id)) return;
    publishSeen.add(id);
  }
  publishLog.push([
    id,
    t,
    (rec.author && rec.author.secUid) || "",
    (rec.mix && rec.mix.mixId) || "",
    rec.hasMix ? 1 : 0,
    rec.hasAiDeclare ? 1 : 0,
    Math.floor(Date.now() / 1000)
  ]);
  if (publishLog.length > PUBLISH_LOG_MAX) {
    publishLog.splice(0, publishLog.length - PUBLISH_LOG_MAX);
  }
}

function handleRecords(records, source) {
  if (!settings.collecting || !records || !records.length) return;
  lastDataAt = Date.now();
  noteDataFlow(); // 有数据 = 页面活着：撤销待刷新；连续有数据满 60s 才把重试计数清零
  let added = 0;
  const addedNames = [];
  for (const rec of records) {
    const id = rec.awemeId || "";
    if (id && matchedAweme.has(id)) continue;
    if (id && !scannedAweme.has(id)) {
      scannedAweme.add(id);
      stats.scanned += 1;
    } else if (!id) {
      stats.scanned += 1;
    }
    logPublish(rec); // 发布时刻采样：全量记录（含未命中过滤的），供时段分析
    if (!DyAccountParse.matchFilters(rec, settings.requireMix, settings.requireAi)) {
      pushRecent(rec, false, source);
      continue;
    }
    if (id) matchedAweme.add(id);
    const isNew = upsertAuthor(rec);
    if (rec.author && rec.author.secUid) {
      try {
        chrome.runtime.sendMessage({
          type: "platformMatch",
          item: { secUid: rec.author.secUid, hasMix: true, hasAi: true }
        });
      } catch (e) {}
    }
    if (isNew) {
      added += 1;
      lastNewAuthorAt = Date.now();
      if (rec.author && rec.author.nickname) addedNames.push(rec.author.nickname);
    }
    pushRecent(rec, true, source);
  }
  if (records.length) {
    stats.lastUrl = location.href;
    /* 采了足够多视频却一条发布时刻都没拿到 → 接口不带 create_time，提前提示，避免白跑一场 */
    if (!publishWarned && !publishLog.length && stats.scanned >= 100) {
      publishWarned = true;
      stats.lastHint = "提示：未取到发布时间(create_time)，时段样本为空";
    }
    if (added > 0) persist();   // 新增作者立即落盘，避免防抖窗口内关页/刷新丢失
    else schedulePersist();     // 仅 lastSeen/视频数更新，走防抖
    renderPanel();
    if (added) flashMatch(added, addedNames);
  }
}

function pushRecent(rec, matched, source) {
  recent.unshift({
    awemeId: rec.awemeId,
    nickname: rec.author && rec.author.nickname,
    hasMix: rec.hasMix,
    hasAiDeclare: rec.hasAiDeclare,
    mixName: rec.mix && rec.mix.mixName,
    matched,
    source
  });
  if (recent.length > 8) recent.length = 8;
}

function flashMatch(n, names) {
  const el = document.getElementById(PANEL_ID);
  if (!el) return;
  el.classList.add("dyaccount-hit");
  setTimeout(() => el.classList.remove("dyaccount-hit"), 600);
  let hint = "新命中 " + n + " 位作者";
  if (MONITOR_MODE && names && names.length) {
    hint += "：" + names.slice(0, 10).join("、");
  }
  stats.lastHint = hint;
}

function ingestJson(text, source) {
  try {
    let records = DyAccountParse.parsePayload(text);
    if (!records.length && text && text.indexOf("\n") !== -1) {
      const lines = String(text).split("\n");
      for (let i = 0; i < lines.length; i++) {
        const line = lines[i].trim();
        if (!line || (line.charAt(0) !== "{" && line.charAt(0) !== "[")) continue;
        records = records.concat(DyAccountParse.parsePayload(line));
      }
    }
    handleRecords(records, source);
  } catch (e) {
    stats.lastHint = "解析失败: " + (e && e.message);
  }
}

function scanRenderData() {
  let el = null;
  for (let i = 0; i < DyAdapter.RENDER_DATA_IDS.length; i++) {
    el = document.getElementById(DyAdapter.RENDER_DATA_IDS[i]);
    if (el) break;
  }
  if (el && el.textContent) {
    const records = DyAccountParse.parseRenderDataText(el.textContent);
    handleRecords(records, "render");
  }
  try {
    if (window.webkit && window.webkit.messageHandlers) return;
  } catch (e) {}
}

function contains(t, s) {
  return !!t && t.indexOf(s) !== -1;
}

function readVisibleText() {
  const root = document.getElementById("sliderVideo") || document.querySelector("#douyin-right-container") || document.body;
  if (!root) return "";
  return root.innerText || "";
}

function visibleTextHas(str) {
  return contains(readVisibleText(), str);
}

function findSecUidFromDom() {
  const links = document.querySelectorAll(DyAdapter.USER_LINK_SEL);
  for (const a of links) {
    const href = a.getAttribute("href") || "";
    const m = href.match(DyAccountParse.SEC_UID_RE);
    if (m) {
      const nick =
        a.getAttribute(DyAdapter.ARIA_LABEL_ATTR) ||
        a.innerText ||
        (a.querySelector(DyAdapter.IMG_SEL) && a.querySelector(DyAdapter.IMG_SEL).alt) ||
        "";
      return { secUid: m[0], nickname: String(nick).trim().slice(0, 40), href };
    }
  }
  return null;
}

function scanCurrentPlayerDom() {
  if (!settings.collecting) return;
  if (!isPlayerMode()) return;
  const hasAi = DyAccountParse.AI_DECLARE_TEXTS.some((t) => visibleTextHas(t));
  const hasMix =
    visibleTextHas(DyAdapter.MIX_TEXT) ||
    !!document.querySelector(DyAdapter.MIX_LINK_SEL);
  if (!hasAi || (settings.requireMix && !hasMix)) return;
  const author = findSecUidFromDom();
  if (!author) return;
  const rec = DyAccountParse.fromDomHints({
    awemeId: currentAwemeId(),
    secUid: author.secUid,
    nickname: author.nickname,
    hasMix,
    mixName: hasMix ? "合集" : "",
    desc: ""
  });
  if (rec) handleRecords([rec], "dom");
}

function ensurePanel() {
  if (document.getElementById(PANEL_ID)) return;
  if (!document.documentElement) return;
  const wrap = document.createElement("div");
  wrap.id = PANEL_ID;
  wrap.innerHTML = panelHtml();
  (document.body || document.documentElement).appendChild(wrap);
  bindPanel(wrap);
}

function panelHtml() {
  return `
    <div class="dyaccount-head">
      <div class="dyaccount-title">
        <span class="dyaccount-dot"></span>
        AI合集作者采集
      </div>
      <button class="dyaccount-iconbtn" data-act="collapse" title="折叠">–</button>
    </div>
    <div class="dyaccount-body">
      <div class="dyaccount-daystart">
        <div class="dyaccount-daystart-main">
          <span class="dyaccount-k">当日起点</span>
          <strong data-k="dayStartText">-</strong>
          <button class="dyaccount-btn dyaccount-btn-xs" data-act="end-today" title="重置起点 + 清空列表 + 停止采集">结束今天</button>
        </div>
        <div class="dyaccount-daystart-last">
          <button class="dyaccount-daystart-toggle" data-act="hist-toggle" aria-expanded="false" title="查看当日重置历史">▸</button>
          <span data-k="dayStartLast">上一次重置：—</span>
        </div>
        <div class="dyaccount-daystart-hist" data-k="dayStartHist" hidden></div>
      </div>
      <div class="dyaccount-stats">
        <div><b data-k="matched">0</b><span>本轮作者</span></div>
        <div><b data-k="scanned">0</b><span>已扫视频</span></div>
      </div>
      <div class="dyaccount-status">状态：<strong data-k="collectingText">未开始</strong></div>
      <div class="dyaccount-row">
        <button class="dyaccount-btn primary" data-act="toggle">开始采集</button>
        <button class="dyaccount-btn" data-act="auto">自动下滑</button>
      </div>
      <div class="dyaccount-row dyaccount-row-wrap">
        <button class="dyaccount-btn" data-act="open">打开精选页</button>
        <button class="dyaccount-btn" data-act="backend">打开后台</button>
        <button class="dyaccount-btn" data-act="guard">故障记录</button>
        <button class="dyaccount-btn" data-act="inspect">检测当前</button>
      </div>
      <label class="dyaccount-check"><input type="checkbox" data-k="requireMix" checked> 必须挂载合集</label>
      <label class="dyaccount-check"><input type="checkbox" data-k="requireAi" checked> 必须「内容由AI生成」</label>
      <label class="dyaccount-check dyaccount-interval">自动间隔
        <select class="dyaccount-select" data-k="intervalMs">
          <option value="4000">4 秒</option>
          <option value="6000">6 秒</option>
          <option value="8000">8 秒</option>
        </select>
      </label>
      <label class="dyaccount-check"><input type="checkbox" data-k="refreshEnabled"> 随机刷新（仅采集页生效）</label>
      <div class="dyaccount-row dyaccount-rf-row">
        <span class="dyaccount-k">刷新区间</span>
        <input class="dyaccount-rf-input" type="number" data-k="refreshMinSec" min="30" max="3600" step="10" value="180" autocomplete="off" />
        <span class="dyaccount-rf-sep">秒 ~</span>
        <input class="dyaccount-rf-input" type="number" data-k="refreshMaxSec" min="30" max="3600" step="10" value="480" autocomplete="off" />
        <span class="dyaccount-rf-sep">秒</span>
      </div>
      <div class="dyaccount-row">
        <button class="dyaccount-btn" data-act="refresh-start">开始计时</button>
        <button class="dyaccount-btn" data-act="refresh-now">立即刷新一次</button>
      </div>
      <div class="dyaccount-status dyaccount-rf-status" data-k="refreshText">随机刷新：未启用</div>
      <label class="dyaccount-check"><input type="checkbox" data-k="autoSwitchChannel"> 自动换栏目</label>
      <label class="dyaccount-check"><input type="checkbox" data-k="autoSwitchSearch"> 自动换搜索词</label>
      <label class="dyaccount-check"><input type="checkbox" data-k="autoSortLatest" checked> 搜索页切「最新发布」</label>
      <div class="dyaccount-export">
        <button class="dyaccount-btn" data-act="copy">复制 sec_uid</button>
        <button class="dyaccount-btn" data-act="txt">导出 TXT</button>
        <button class="dyaccount-btn" data-act="csv">导出 CSV</button>
        <button class="dyaccount-btn" data-act="json">导出 JSON</button>
        <button class="dyaccount-btn" data-act="plog">导出时段样本</button>
        <button class="dyaccount-btn danger" data-act="clear" title="只清空列表，不改当日起点；日常收工请用面板顶部的「结束今天」">仅清空列表</button>
      </div>
      <div class="dyaccount-picker" data-k="picker" hidden>
        <div class="dyaccount-picker-head">
          <strong>选择导出范围</strong>
          <button class="dyaccount-btn dyaccount-btn-xs" data-act="picker-close">关闭</button>
        </div>
        <label class="dyaccount-pk-label">上传人 ID</label>
        <input class="dyaccount-pk-input" data-k="uploaderId" maxlength="64" placeholder="请输入上传人 ID" autocomplete="off" spellcheck="false" />
        <span class="dyaccount-pk-label">日期</span>
        <div class="dyaccount-pk-row" data-k="pkRange">
          <button data-range="today" class="on">今天</button>
          <button data-range="7d">近 7 天</button>
          <button data-range="30d">一个月内</button>
          <button data-range="all">全部</button>
        </div>
        <div data-k="pkSlotWrap">
          <span class="dyaccount-pk-label">时段（今天内）</span>
          <div class="dyaccount-pk-row" data-k="pkSlot">
            <button data-slot="none" class="on">不限</button>
            <button data-slot="1h">近 1 小时</button>
            <button data-slot="3h">近 3 小时</button>
            <button data-slot="work">9:30–18:00</button>
            <button data-slot="custom">自定义</button>
          </div>
          <p class="dyaccount-pk-tip">「不限」= 当日起点 → 此刻；自定义精确到分钟，只筛今天内。</p>
          <div class="dyaccount-pk-custom" data-k="pkCustom" hidden>
            <select class="dyaccount-pk-time" data-k="pkFromH"></select><span>:</span><select class="dyaccount-pk-time" data-k="pkFromM"></select>
            <span class="dyaccount-pk-tilde">~</span>
            <select class="dyaccount-pk-time" data-k="pkToH"></select><span>:</span><select class="dyaccount-pk-time" data-k="pkToM"></select>
          </div>
        </div>
        <p class="dyaccount-pk-preview">命中 <strong data-k="pkCount">0</strong> 条</p>
        <div class="dyaccount-pk-gap" data-k="pkGap" hidden>
          <p data-k="pkGapText"></p>
          <div class="dyaccount-pk-row">
            <button data-gap="all">一起上传（含全部）</button>
            <button data-gap="cur">只传当前范围</button>
            <button data-gap="cancel">取消</button>
          </div>
        </div>
        <div class="dyaccount-pk-row">
          <button data-do="copy" class="on">复制 ID</button>
          <button data-do="txt">TXT</button>
          <button data-do="csv">CSV</button>
          <button data-do="json">JSON</button>
        </div>
        <button class="dyaccount-btn primary dyaccount-upload" data-do="upload">上传到 43.165.183.142</button>
        <p class="dyaccount-pk-msg" data-k="pkMsg"></p>
      </div>
      <div class="dyaccount-hint" data-k="hint">精选或搜索页登录后，点开始采集，再开自动下滑。</div>
      <div class="dyaccount-prompt" data-k="prompt" hidden>
        <div class="dyaccount-prompt-text">已 5 分钟无新作者，是否取消「必须挂载合集」以放宽采集？</div>
        <div class="dyaccount-prompt-actions">
          <button class="dyaccount-btn" data-act="relaxMix">取消合集条件</button>
          <button class="dyaccount-btn" data-act="keepMix">保持</button>
        </div>
      </div>
      ${MONITOR_MODE ? `
      <div class="dyaccount-viewbar">
        <button class="dyaccount-btn dyaccount-btn-sm" data-act="viewToggle">仅看新增</button>
      </div>` : ""}
      <div class="dyaccount-list" data-k="list"></div>
    </div>
  `;
}

function bindPanel(root) {
  root.addEventListener("click", (e) => {
    /* P8 比对提示块的三个按钮优先于通用 data-act 处理 */
    const gapBtn = e.target.closest("[data-gap]");
    if (gapBtn) {
      onGapChoice(gapBtn.getAttribute("data-gap"));
      return;
    }
    const pk = e.target.closest("[data-range], [data-slot], [data-do]");
    if (pk) {
      onPickerClick(pk);
      return;
    }
    const btn = e.target.closest("[data-act]");
    if (!btn) return;
    const act = btn.getAttribute("data-act");
    if (act === "collapse") {
      settings.collapsed = !settings.collapsed;
      persist();
      renderPanel();
    } else if (act === "toggle") {
      settings.collecting = !settings.collecting;
      if (settings.collecting) {
        resetCaptchaFlag();
        sendHookCollecting();
        scanRenderData();
        scanCurrentPlayerDom();
        /* 联动：勾了「随机刷新」时，点「开始采集」就自动开始计时 */
        if (settings.refreshEnabled) startRefresh(false);
      } else {
        settings.autoBrowse = false;
        stopAuto();
        sendHookCollecting();
        stopRefresh(); // 停止采集 → 停表
      }
      persist();
      renderPanel();
    } else if (act === "refresh-start") {
      if (settings.refreshArmed) {
        stopRefresh();
      } else if (!settings.refreshEnabled) {
        stats.lastHint = "请先勾选「随机刷新（仅采集页生效）」";
      } else {
        startRefresh(true);
      }
      persist();
      renderPanel();
    } else if (act === "refresh-now") {
      doRefreshNow();
    } else if (act === "auto") {
      if (!settings.collecting) {
        settings.collecting = true;
        sendHookCollecting();
      }
      settings.autoBrowse = !settings.autoBrowse;
      if (settings.autoBrowse) resetCaptchaFlag();
      cachedScrollRoot = null;
      stuckTicks = 0;
      persist();
      syncAuto();
      renderPanel();
    } else if (act === "inspect") {
      inspectCurrent();
    } else if (act === "open") {
      openViaBackground(DyAdapter.JINGXUAN_KEY);
    } else if (act === "backend") {
      openViaBackground("backend");
    } else if (act === "guard") {
      openViaBackground("guard");
    } else if (act === "copy" || act === "txt" || act === "csv" || act === "json") {
      openPicker(act);
    } else if (act === "plog") {
      downloadText("douyin-publish-times.csv", DyShared.toPublishCsv(publishLog), "text/csv");
    } else if (act === "picker-close") {
      pickerState.open = false;
      refreshPicker();
    } else if (act === "end-today") {
      onEndToday();
    } else if (act === "hist-toggle") {
      toggleDayStartHist();
    } else if (act === "relaxMix") {
      settings.requireMix = false;
      hideIdlePrompt();
      stats.lastHint = "已放宽：取消「必须挂载合集」";
      persist();
      renderPanel();
    } else if (act === "keepMix") {
      hideIdlePrompt();
      lastNewAuthorAt = Date.now();
      stats.lastHint = "保持当前条件";
      persist();
      renderPanel();
    } else if (act === "viewToggle") {
      settings.viewNewOnly = !settings.viewNewOnly;
      persist();
      renderPanel();
    } else if (act === "clear") {
      if (confirm("清空已采集的作者列表？")) {
        authors = {};
        authorListCache = null;
        authorListDirty = true;
        stats.matched = 0;
        recent = [];
        /* 「已自动刷 N 次」与采集批次同口径：清空列表时一并清零（独立键，单点删除） */
        refreshCount = 0;
        try { chrome.storage.local.remove("refreshCount"); } catch (e) {}
        persist();
        renderPanel();
      }
    }
  });
  root.addEventListener("change", (e) => {
    const el = e.target;
    const key = el.getAttribute && el.getAttribute("data-k");
    if (!key) return;
    /* 自定义时段：四个下拉，时/分各两个。改开始后先修正结束再置灰。 */
    if (key === "pkFromH" || key === "pkFromM" || key === "pkToH" || key === "pkToM") {
      applyCustomTimeChange(key, el.value);
      return;
    }
    if (key === "uploaderId") {
      settings.uploaderId = String(el.value || "").trim();
      persist();
      return;
    }
    if (key === "intervalMs") {
      settings.intervalMs = Number(el.value) || 4000;
      persist();
      return;
    }
    if (key === "refreshEnabled") {
      settings.refreshEnabled = !!el.checked;
      if (el.checked) {
        /* 已在采集 → 勾选即起表；未采集 → 只记启用，等点「开始采集」再起表 */
        if (settings.collecting) startRefresh(false);
      } else {
        stopRefresh();
      }
      persist();
      renderPanel();
      return;
    }
    if (key === "refreshMinSec" || key === "refreshMaxSec") {
      const raw = Number(el.value);
      if (!isFinite(raw) || raw <= 0) {
        /* 填的不是有效数字：保留上一个有效值，不弹窗，回填输入框 */
        renderPanel();
        return;
      }
      const v = Math.min(DyShared.REFRESH_MAX_LIMIT, Math.max(DyShared.REFRESH_MIN_LIMIT, Math.round(raw)));
      settings[key] = v;
      /* 这里不做 min>max 交换：允许按任意顺序填两个框，
         交换与兜底交给 refreshRange()（调度时使用） */
      persist();
      renderPanel();
      updateRefreshText();
      return;
    }
    if (key === "requireMix" || key === "requireAi" || key === "autoSwitchChannel" || key === "autoSwitchSearch" || key === "autoSortLatest") {
      settings[key] = el.checked;
      if (key === "autoSortLatest" && el.checked) {
        sortAppliedFor = "";
        sortTries = 0;
        applyLatestSort();
      }
      persist();
    }
  });
}

function authorList() {
  if (authorListDirty) {
    authorListCache = Object.keys(authors)
      .map((k) => authors[k])
      .sort((a, b) => (b.lastSeen || 0) - (a.lastSeen || 0));
    authorListDirty = false;
  }
  return authorListCache;
}

function renderPanel() {
  ensurePanel();
  const root = document.getElementById(PANEL_ID);
  if (!root) return;
  root.classList.toggle("is-collapsed", !!settings.collapsed);
  root.classList.toggle("is-on", !!settings.collecting);
  root.classList.toggle("dyaccount-error", !!fatal);
  const toggle = root.querySelector('[data-act="toggle"]');
  const auto = root.querySelector('[data-act="auto"]');
  if (toggle) toggle.textContent = settings.collecting ? "停止采集" : "开始采集";
  if (auto) {
    auto.textContent = settings.autoBrowse ? "停止下滑" : "自动下滑";
    auto.classList.toggle("on", !!settings.autoBrowse);
  }
  const viewBtn = root.querySelector('[data-act="viewToggle"]');
  if (viewBtn) {
    viewBtn.textContent = settings.viewNewOnly ? "显示全部" : "仅看新增";
    viewBtn.classList.toggle("on", !!settings.viewNewOnly);
  }
  const matched = root.querySelector('[data-k="matched"]');
  const scanned = root.querySelector('[data-k="scanned"]');
  if (matched) matched.textContent = String(Object.keys(authors).length);
  if (scanned) scanned.textContent = String(stats.scanned || 0);
  const mix = root.querySelector('[data-k="requireMix"]');
  const ai = root.querySelector('[data-k="requireAi"]');
  const asw = root.querySelector('[data-k="autoSwitchChannel"]');
  const asw2 = root.querySelector('[data-k="autoSwitchSearch"]');
  const asl = root.querySelector('[data-k="autoSortLatest"]');
  if (mix) mix.checked = settings.requireMix !== false;
  if (ai) ai.checked = settings.requireAi !== false;
  if (asw) asw.checked = !!settings.autoSwitchChannel;
  if (asw2) asw2.checked = !!settings.autoSwitchSearch;
  if (asl) asl.checked = settings.autoSortLatest !== false;
  const collectingText = root.querySelector('[data-k="collectingText"]');
  if (collectingText) collectingText.textContent = settings.collecting ? "采集中" : "未开始";
  const intervalSel = root.querySelector('[data-k="intervalMs"]');
  if (intervalSel) intervalSel.value = String(settings.intervalMs || 4000);
  const rfOn = root.querySelector('[data-k="refreshEnabled"]');
  if (rfOn) rfOn.checked = !!settings.refreshEnabled;
  const rfMin = root.querySelector('[data-k="refreshMinSec"]');
  const rfMax = root.querySelector('[data-k="refreshMaxSec"]');
  /* 按存储原值回填（不排序），避免填第二个框时第一个被顶回去 */
  /* 正在输入的框不覆写，避免打字被吃掉 */
  if (rfMin && document.activeElement !== rfMin) rfMin.value = String(DyShared.clampRefreshSec(settings.refreshMinSec, 180));
  if (rfMax && document.activeElement !== rfMax) rfMax.value = String(DyShared.clampRefreshSec(settings.refreshMaxSec, 480));
  updateRefreshText();
  const hint = root.querySelector('[data-k="hint"]');
  if (hint) {
    hint.textContent = settings.collecting
      ? stats.lastHint || pageModeHint(true)
      : pageModeHint(false);
    hint.classList.toggle("dyaccount-hint-error", !!fatal);
  }
  const list = root.querySelector('[data-k="list"]');
  if (list) {
    let rows = authorList();
    if (MONITOR_MODE && settings.viewNewOnly) {
      rows = rows.filter((a) => (a.firstSeen || 0) >= sessionStartAt);
    }
    rows = rows.slice(0, 12);
    list.innerHTML = rows.length
      ? rows
          .map((a) => {
            const name = escapeHtml(a.nickname || "(无昵称)");
            const mixName = escapeHtml(a.mixName || "合集");
            const isNew = MONITOR_MODE && (a.firstSeen || 0) >= sessionStartAt;
            const badge = isNew ? `<span class="dyaccount-item-new">新</span>` : "";
            return `<div class="dyaccount-item" title="${escapeHtml(a.secUid)}">
              <div class="dyaccount-item-name">${badge}${name}</div>
              <div class="dyaccount-item-meta">${mixName}</div>
            </div>`;
          })
          .join("")
      : `<div class="dyaccount-empty">还没有命中。目标：挂载合集 + 作者声明「内容由AI生成」</div>`;
  }
  if (pickerState.open) refreshPicker();
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function fmtTime(ts) {
  if (!ts) return "";
  const d = new Date(ts);
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

function normTypeText(s) {
  return String(s == null ? "" : s).replace(/\s+/g, "").toUpperCase();
}

function isTargetType(mixName, nickname) {
  const text = normTypeText(mixName) + normTypeText(nickname);
  if (!text) return false;
  if (TYPE_BLACKLIST.some((k) => text.indexOf(k) !== -1)) return false;
  return TYPE_WHITELIST.some((k) => text.indexOf(k) !== -1);
}

function effectiveAuthors() {
  const rows = authorList();
  if (!VALID_FILTER_ENABLED) return rows;
  return rows.filter((a) => isTargetType(a.mixName, a.nickname) && (a.videoCount || 0) >= VALID_MIN_VIDEO);
}

function exportCsv() {
  const rows = effectiveAuthors();
  if (!rows.length) {
    stats.lastHint = VALID_FILTER_ENABLED ? "没有符合条件的有效作者，暂不导出" : "暂无采集数据，暂不导出";
    renderPanel();
    return;
  }
  const header = ["昵称", "sec_uid", "合集名", "搜索词", "首次发现时间", "最近发现时间", "视频数"];
  const esc = (v) => {
    const s = String(v == null ? "" : v);
    if (s.includes(",") || s.includes('"') || s.includes("\n")) {
      return '"' + s.replace(/"/g, '""') + '"';
    }
    return s;
  };
  const lines = [header.join(",")];
  for (const a of rows) {
    lines.push(
      [
        a.nickname || "",
        a.secUid || "",
        a.mixName || "",
        topWords(a, 3),
        fmtTime(a.firstSeen),
        fmtTime(a.lastSeen),
        a.videoCount || 0
      ]
        .map(esc)
        .join(",")
    );
  }
  const csv = String.fromCharCode(0xfeff) + lines.join("\r\n");
  const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = "douyin-ai-authors.csv";
  a.click();
  URL.revokeObjectURL(url);
  stats.lastHint =
    (VALID_FILTER_ENABLED ? "已导出有效 " : "已导出全量 ") +
    rows.length + " 位（共 " + authorList().length + " 位）作者 CSV";
  renderPanel();
}

/* 导出范围选择器（与 popup 功能一致）；DAY_MS / fmtTs / toCsv / toPublishCsv /
 * selectAuthorsBy / judgeUploadGap 统一来自 shared.js */
const pickerState = { open: false, act: "copy", range: "today", slot: "none", from: "", to: "" };
let dayStartTs = 0;
let dayStartHistOpen = false;

function pad2(n) { return n < 10 ? "0" + n : String(n); }

/* 本地日期键：与 background 的 ymdKey 同口径，只用于「是否已跨天」的显示层判断 */
function ymdOf(ts) {
  const d = new Date(ts);
  return d.getFullYear() + "-" + pad2(d.getMonth() + 1) + "-" + pad2(d.getDate());
}

/* 毫秒时间戳 → "HH:mm"（自定义时段下拉用） */
function hhmmOf(ts) {
  const d = new Date(ts);
  return pad2(d.getHours()) + ":" + pad2(d.getMinutes());
}

/* 本地零点。R2-9②：当日起点没取到时，「今日」的筛选边界仍按本地零点算（跨天回落），
 * 但这个值不写进 dayStartTs —— 那个变量只放真值，取不到就显示失效态。 */
function todayStartTs() {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

/* 当前 picker 范围命中的作者数组（三处调用统一走这里，避免漏传 custom） */
function pickerRows() {
  return DyShared.selectAuthorsBy(
    authorList(), pickerState.range, pickerState.slot, dayStartTs || todayStartTs(), Date.now(),
    { from: pickerState.from, to: pickerState.to }
  );
}

function panelRoot() {
  return document.getElementById(PANEL_ID);
}

function sendBg(msg) {
  return new Promise((resolve) => {
    try {
      chrome.runtime.sendMessage(msg, (r) => {
        void chrome.runtime.lastError;
        resolve(r);
      });
    } catch (e) {
      resolve(null);
    }
  });
}

function downloadText(filename, text, mime) {
  const blob = new Blob([text], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function pkMsg(text, cls) {
  const root = panelRoot();
  const el = root && root.querySelector('[data-k="pkMsg"]');
  if (!el) return;
  el.textContent = text || "";
  el.className = "dyaccount-pk-msg" + (cls ? " " + cls : "");
}

function refreshPicker() {
  const root = panelRoot();
  if (!root) return;
  const box = root.querySelector('[data-k="picker"]');
  if (!box) return;
  box.hidden = !pickerState.open;
  if (!pickerState.open) return;
  root.querySelectorAll('[data-k="pkRange"] button').forEach((b) => {
    b.classList.toggle("on", b.getAttribute("data-range") === pickerState.range);
  });
  root.querySelectorAll('[data-k="pkSlot"] button').forEach((b) => {
    b.classList.toggle("on", b.getAttribute("data-slot") === pickerState.slot);
  });
  const slotWrap = root.querySelector('[data-k="pkSlotWrap"]');
  if (slotWrap) slotWrap.hidden = pickerState.range !== "today";
  const custom = root.querySelector('[data-k="pkCustom"]');
  if (custom) {
    custom.hidden = !(pickerState.range === "today" && pickerState.slot === "custom");
    if (!custom.hidden) {
      fillTimeSelects();
      syncCustomLimits();
      syncCustomSelects();
    }
  }
  root.querySelectorAll('[data-do]:not(.dyaccount-upload)').forEach((b) => {
    b.classList.toggle("on", b.getAttribute("data-do") === pickerState.act);
  });
  const count = root.querySelector('[data-k="pkCount"]');
  if (count) count.textContent = String(pickerRows().length);
}

function openPicker(act) {
  pickerState.act = act;
  pickerState.open = true;
  const root = panelRoot();
  const input = root && root.querySelector('[data-k="uploaderId"]');
  if (input) input.value = settings.uploaderId || "";
  pkMsg("");
  refreshPicker();
  refreshDayStart().then(refreshPicker);
}

function runExport(act) {
  pickerState.act = act;
  const rows = pickerRows();
  const ids = rows.map((a) => a.secUid).filter(Boolean);
  if (!ids.length) {
    pkMsg("当前范围没有命中任何作者。", "err");
    refreshPicker();
    return;
  }
  /* R2-11：今日范围 + 起点未连接（dayStartTs 为 0，说明还没拿到真起点）时，
     导出照做、不阻断，只在成功文案里如实标注本次用的是本地零点边界。 */
  const startNote =
    pickerState.range === "today" && dayStartTs === 0 ? "（今日范围 = 本地零点起，起点未连接）" : "";
  if (act === "copy") {
    navigator.clipboard.writeText(ids.join("\n")).then(
      () => pkMsg("已复制 " + ids.length + " 条 sec_uid。", "ok"),
      () => pkMsg("复制失败，请重试。", "err")
    );
  } else if (act === "txt") {
    downloadText("douyin-ai-mix-authors.txt", ids.join("\n"), "text/plain");
    pkMsg("已导出 TXT（" + ids.length + " 条）。" + startNote, "ok");
  } else if (act === "csv") {
    downloadText("douyin-ai-mix-authors.csv", DyShared.toCsv(rows), "text/csv");
    pkMsg("已导出 CSV（" + rows.length + " 条）。" + startNote, "ok");
  } else if (act === "json") {
    downloadText(
      "douyin-ai-mix-authors.json",
      JSON.stringify({ exportedAt: new Date().toISOString(), count: rows.length, authors: rows }, null, 2),
      "application/json"
    );
    pkMsg("已导出 JSON（" + rows.length + " 条）。" + startNote, "ok");
  }
  refreshPicker();
}

async function doUpload() {
  const root = panelRoot();
  const input = root && root.querySelector('[data-k="uploaderId"]');
  const uploaderId = String((input && input.value) || "").trim();
  if (!uploaderId) {
    pkMsg("请先填写上传人 ID。", "err");
    return;
  }
  /* P8 比对：先取本时段总数（实时），再算当前范围命中。命中 0 时绝不上传、绝不重置。 */
  /* 取不到条数时不能当 0 —— 否则会误报「还没有采到任何作者」，
   * 也会静默跳过 P8 的部分上传提示。总数未知就不上传。
   * SW 刚唤醒/扩展刚重载时可能一次拿不到，重试 1 次再判死。 */
  let info = null;
  for (let i = 0; i < 2; i++) {
    info = await sendBg({ type: "dayStartInfo" });
    if (info && info.ok) break;
    if (i === 0) await new Promise((r) => setTimeout(r, 300));
  }
  if (!info || !info.ok) {
    hideGapBox();
    pkMsg(
      info && info.message
        ? info.message + "，无法核对本时段条数，请稍后重试。"
        : "未连接，取不到本时段条数，无法核对。请稍后重试。",
      "err"
    );
    return;
  }
  const total = Number(info.count) || 0;
  const ids = Array.from(new Set(pickerRows().map((a) => a.secUid).filter(Boolean)));
  const hit = ids.length;
  const gap = DyShared.judgeUploadGap(total, hit);
  if (gap.hit === 0) {
    hideGapBox();
    if (gap.total === 0) {
      pkMsg("本时段还没有采到任何作者，无可上传内容。", "err");
    } else {
      pkMsg("当前范围 0 条，本时段共 " + gap.total + " 条。请调整时段，或点「一起上传（含全部）」。", "err");
      showGapBox({
        text: "当前范围 0 条，本时段共 " + gap.total + " 条不在此范围内。",
        curButton: false
      });
    }
    return;
  }
  if (gap.needPrompt) {
    showGapBox({
      text: "本时段共 " + gap.total + " 条，本次范围只含 " + gap.hit + " 条，还有 " + gap.diff + " 条不在范围内。",
      curButton: true
    });
    return;
  }
  /* 差值未达阈值：直接提交，保持原来的手感，不额外确认 */
  await submitUpload(ids, uploaderId, false);
}

async function submitUpload(ids, uploaderId, needConfirm) {
  const finalIds = Array.from(new Set(ids.filter(Boolean)));
  if (!finalIds.length) { pkMsg("当前范围没有可上传的 sec_uid。", "err"); return; }
  if (needConfirm && !confirm("确认上传 " + finalIds.length + " 条到 43.165.183.142？")) {
    pkMsg("已取消上传。", "err");
    return;
  }
  if (settings.uploaderId !== uploaderId) {
    settings.uploaderId = uploaderId;
    persist();
  }
  pkMsg("上传中… 共 " + finalIds.length + " 条，请保持页面打开。");
  const r = await sendBg({ type: "uploadIds", ids: finalIds, uploaderId: uploaderId });
  if (r && r.ok) {
    pkMsg("上传成功：本时段共 " + (r.total != null ? r.total : finalIds.length) + " 条，本次提交 " + r.count + " 条（新增 " + r.inserted + "，服务端已存在 " + r.dup + "）。", "ok");
  } else {
    /* R2-10③：兜底复用 854 语义。sendBg 通道断时只回 undefined，
     * 旧文案「上传失败。」会让「没入库」和「收下了但回执丢了」共用一句话。 */
    pkMsg((r && r.message) || "提交结果尚未确认，请保持原上传人 ID 和文件内容重试。", "err");
  }
  refreshPicker();
}

function showGapBox(o) {
  const root = panelRoot();
  const box = root && root.querySelector('[data-k="pkGap"]');
  if (!box) return;
  const text = box.querySelector('[data-k="pkGapText"]');
  if (text) text.textContent = o.text || "";
  const cur = box.querySelector('[data-gap="cur"]');
  if (cur) cur.hidden = o.curButton === false;
  box.hidden = false;
}

function hideGapBox() {
  const root = panelRoot();
  const box = root && root.querySelector('[data-k="pkGap"]');
  if (box) box.hidden = true;
}

function onGapChoice(choice) {
  const root = panelRoot();
  if (choice === "cancel") {
    hideGapBox();
    pkMsg("已取消上传。", "err");
    return;
  }
  const uploaderId = String(((root && root.querySelector('[data-k="uploaderId"]')) || {}).value || "").trim();
  if (choice === "all") {
    /* 一起上传：临时把范围切到「今天 + 不限」，命中 = 本时段全部 */
    pickerState.range = "today";
    pickerState.slot = "none";
    hideGapBox();
    refreshPicker();
  } else {
    hideGapBox();
  }
  const ids = Array.from(new Set(pickerRows().map((a) => a.secUid).filter(Boolean)));
  /* 从提示块进到这里的，一律再过一次最终确认 */
  submitUpload(ids, uploaderId, true);
}

function onPickerClick(el) {
  const range = el.getAttribute("data-range");
  const slot = el.getAttribute("data-slot");
  const doAct = el.getAttribute("data-do");
  if (range) {
    pickerState.range = range;
    if (range !== "today") pickerState.slot = "none";
    hideGapBox();
    pkMsg("");
    refreshPicker();
    return;
  }
  if (slot) {
    pickerState.slot = slot;
    /* 切到自定义时初始化默认值：当日起点 → 此刻 */
    if (slot === "custom" && !pickerState.from) {
      pickerState.from = hhmmOf(dayStartTs || todayStartTs());
      pickerState.to = hhmmOf(Date.now());
    }
    hideGapBox();
    pkMsg("");
    refreshPicker();
    return;
  }
  if (doAct === "upload") {
    doUpload();
    return;
  }
  if (doAct) runExport(doAct);
}

function updateDayStartText(start, count, last) {
  dayStartTs = start;
  const root = panelRoot();
  const el = root && root.querySelector('[data-k="dayStartText"]');
  if (!el) return;
  el.textContent = DyShared.fmtTs(start / 1000) + (typeof count === "number" ? "（今日 " + count + " 个）" : "");
  const lastEl = root.querySelector('[data-k="dayStartLast"]');
  if (lastEl) {
    lastEl.textContent = last && last.ts
      ? "上一次重置：" + DyShared.fmtTs(last.ts / 1000) + "（共 " + (Number(last.count) || 0) + " 个）"
      : "上一次重置：—";
  }
}

/* 历史最多 5 条，一次拼完即可，不需要虚拟滚动 */
function renderDayStartHistory(hist) {
  const root = panelRoot();
  const box = root && root.querySelector('[data-k="dayStartHist"]');
  if (!box) return;
  const rows = Array.isArray(hist) ? hist : [];
  const LABEL = { manual: "手动", end: "结束今天", upload: "上传" };
  box.innerHTML = rows.length
    ? rows.map((h) => {
        const d = new Date(h.ts);
        const t = pad2(d.getHours()) + ":" + pad2(d.getMinutes()) + ":" + pad2(d.getSeconds());
        return "<div>" + t + " · 共 " + (Number(h.count) || 0) + " 个 · " + (LABEL[h.type] || "手动") + "</div>";
      }).join("")
    : "<div>今天还没有重置记录。</div>";
  box.hidden = !dayStartHistOpen;
  const toggle = root.querySelector('[data-act="hist-toggle"]');
  if (toggle) toggle.setAttribute("aria-expanded", dayStartHistOpen ? "true" : "false");
}

function toggleDayStartHist() {
  dayStartHistOpen = !dayStartHistOpen;
  const root = panelRoot();
  const box = root && root.querySelector('[data-k="dayStartHist"]');
  const toggle = root && root.querySelector('[data-act="hist-toggle"]');
  if (box) box.hidden = !dayStartHistOpen;
  if (toggle) toggle.setAttribute("aria-expanded", dayStartHistOpen ? "true" : "false");
}

async function refreshDayStart() {
  const r = await sendBg({ type: "dayStartInfo" });
  if (r && r.ok) {
    updateDayStartText(r.start, r.count, r.last);
    renderDayStartHistory(r.history);
    return;
  }
  /* R2-9①：取数失败给明确失效标记，不再渲染裸 00:00:00 冒充真实起点。
   * R2-9②：也不把本地零点写进 dayStartTs —— 那个变量只放真值；
   *         「今日」的筛选边界由 pickerRows 用 todayStartTs() 兜底。 */
  dayStartTs = 0;
  const root = panelRoot();
  const el = root && root.querySelector('[data-k="dayStartText"]');
  if (el) el.textContent = "未连接";
  const lastEl = root && root.querySelector('[data-k="dayStartLast"]');
  if (lastEl) lastEl.textContent = "上一次重置：—";
  renderDayStartHistory([]);
}

/* 合并高频 authors 变更：1.5 秒窗口内只问一次「今日 N 个」 */
let dayStartRefreshTimer = 0;
function scheduleDayStartRefresh() {
  if (dayStartRefreshTimer) return;
  dayStartRefreshTimer = setTimeout(() => {
    dayStartRefreshTimer = 0;
    refreshDayStart();
  }, 1500);
}

/* 结束今天：清历史 → 重置起点 → 清列表 → 停采集，一次点击、四步写（全部在 background，不是事务） */
async function onEndToday() {
  if (!confirm("结束今天？\n\n将同时：① 清空重置历史 ② 起点重置为此刻 ③ 清空已采集列表 ④ 停止采集。\n（已上传的数据不受影响）")) return;
  pkMsg("");
  await sendBg({ type: "endToday" });
  await refreshDayStart();
  refreshPicker();
}

/* ================= 自定义时段下拉（P6） ================= */

/* 时 00–23、分 00–59；只填一次，避免每次刷新重建 DOM */
function fillTimeSelects() {
  const root = panelRoot();
  const r = root && root.querySelector('[data-k="pkCustom"]');
  if (!r) return;
  const hours = [];
  for (let i = 0; i < 24; i++) hours.push(pad2(i));
  const mins = [];
  for (let i = 0; i < 60; i++) mins.push(pad2(i));
  const pairs = [["pkFromH", hours], ["pkToH", hours], ["pkFromM", mins], ["pkToM", mins]];
  pairs.forEach((pair) => {
    const el = r.querySelector('[data-k="' + pair[0] + '"]');
    if (!el || el.options.length) return;
    pair[1].forEach((v) => {
      const o = document.createElement("option");
      o.value = v;
      o.textContent = v;
      el.appendChild(o);
    });
  });
}

/* 结束不得早于开始：
 *   结束的「时」< 开始的「时」                      → 该时选项置灰
 *   结束的「时」= 开始的「时」且「分」< 开始的「分」  → 该分选项置灰
 * 可选范围靠 disabled 收窄，非法值根本产生不了。 */
function syncCustomLimits() {
  const root = panelRoot();
  const r = root && root.querySelector('[data-k="pkCustom"]');
  if (!r || !pickerState.from || !pickerState.to) return;
  const fh = Number(pickerState.from.slice(0, 2));
  const fm = Number(pickerState.from.slice(3, 5));
  const th = Number(pickerState.to.slice(0, 2));
  const hourSel = r.querySelector('[data-k="pkToH"]');
  const minSel = r.querySelector('[data-k="pkToM"]');
  if (hourSel) {
    Array.from(hourSel.options).forEach((o) => { o.disabled = Number(o.value) < fh; });
  }
  if (minSel) {
    Array.from(minSel.options).forEach((o) => { o.disabled = th === fh && Number(o.value) < fm; });
  }
}

/* 把 pickerState 的值回填到四个下拉 */
function syncCustomSelects() {
  const root = panelRoot();
  const r = root && root.querySelector('[data-k="pkCustom"]');
  if (!r || !pickerState.from || !pickerState.to) return;
  const set = (k, v) => {
    const el = r.querySelector('[data-k="' + k + '"]');
    if (el && el.value !== v) el.value = v;
  };
  set("pkFromH", pickerState.from.slice(0, 2));
  set("pkFromM", pickerState.from.slice(3, 5));
  set("pkToH", pickerState.to.slice(0, 2));
  set("pkToM", pickerState.to.slice(3, 5));
}

/* 改开始时若结束变非法，自动抬到「开始 + 1 分钟」（不弹错，用户感知是结束值往后跳） */
function clampCustomEnd() {
  const f = pickerState.from;
  const t = pickerState.to;
  if (!f || !t) return;
  if (t >= f) return;                 /* "HH:mm" 等长字符串可直接比较 */
  const h = Number(f.slice(0, 2));
  const m = Number(f.slice(3, 5));
  const total = Math.min(h * 60 + m + 1, 23 * 60 + 59);
  pickerState.to = pad2(Math.floor(total / 60)) + ":" + pad2(total % 60);
}

function applyCustomTimeChange(key, value) {
  const v = String(value || "");
  if (key === "pkFromH") pickerState.from = v + ":" + (pickerState.from || "00:00").slice(3, 5);
  else if (key === "pkFromM") pickerState.from = (pickerState.from || "00:00").slice(0, 2) + ":" + v;
  else if (key === "pkToH") pickerState.to = v + ":" + (pickerState.to || "23:59").slice(3, 5);
  else pickerState.to = (pickerState.to || "23:59").slice(0, 2) + ":" + v;
  clampCustomEnd();
  syncCustomLimits();
  syncCustomSelects();
  hideGapBox();
  const root = panelRoot();
  const count = root && root.querySelector('[data-k="pkCount"]');
  if (count) count.textContent = String(pickerRows().length);
}

function openViaBackground(target) {
  sendBg({ type: "openTab", target: target }).then((r) => {
    if (!r || !r.ok) {
      stats.lastHint = "无法打开页面";
      persist();
      renderPanel();
    }
  });
}

function inspectCurrent() {
  const id = currentAwemeId();
  const hasAi = DyAccountParse.AI_DECLARE_TEXTS.some((t) => visibleTextHas(t));
  const hasMixText = visibleTextHas(DyAdapter.MIX_TEXT);
  const author = findSecUidFromDom();
  stats.lastHint =
    "当前 " +
    (id || "无modal_id") +
    " | 页面AI声明:" +
    (hasAi ? "有" : "无") +
    " | 合集:" +
    (hasMixText ? "有" : "无") +
    " | 作者:" +
    (author ? author.nickname || author.secUid.slice(0, 12) : "未找到");
  scanRenderData();
  scanCurrentPlayerDom();
  scanSearchDom();
  persist();
  renderPanel();
}

function isPlayerMode() {
  return DyAdapter.MODAL_ID_RE.test(location.href) || DyAdapter.VIDEO_PATH_RE.test(location.pathname);
}

function isSearchPage() {
  return DyAdapter.SEARCH_PATH_RE.test(location.pathname);
}

function pageModeHint(running) {
  if (isSearchPage()) {
    return running
      ? settings.autoBrowse
        ? "搜索页自动下滑中，正在拦截搜索结果…"
        : "采集中，请在搜索结果里下滑加载更多"
      : "当前是搜索页。点开始采集，再开自动下滑。";
  }
  if (isPlayerMode()) {
    return running
      ? settings.autoBrowse
        ? "播放页自动切下一条…"
        : "采集中，请切下一条或开自动下滑"
      : "打开精选/搜索后开始采集。播放中可自动切下一条。";
  }
  return running
    ? settings.autoBrowse
      ? "自动下滑中，正在拦截推荐接口…"
      : "采集中，请下滑或开自动下滑"
    : "精选或搜索页登录后，点开始采集，再开自动下滑。";
}

function isOurPanel(el) {
  return !!(el && (el.id === PANEL_ID || (el.closest && el.closest("#" + PANEL_ID))));
}

function isScrollableBox(el) {
  if (!el || isOurPanel(el)) return false;
  if (el === document.documentElement || el === document.body || el === document.scrollingElement) {
    const se = document.scrollingElement || document.documentElement;
    return se.scrollHeight > se.clientHeight + 60;
  }
  let style;
  try {
    style = window.getComputedStyle(el);
  } catch (e) {
    return false;
  }
  const oy = style.overflowY || style.overflow;
  const canScroll = oy === "auto" || oy === "scroll" || oy === "overlay";
  return canScroll && el.scrollHeight > el.clientHeight + 60;
}

function findScrollRoot() {
  if (cachedScrollRoot && document.contains(cachedScrollRoot) && isScrollableBox(cachedScrollRoot)) {
    return cachedScrollRoot;
  }
  const vw = window.innerWidth || 1200;
  const vh = window.innerHeight || 800;
  const x = Math.floor(vw * 0.38);
  const y = Math.floor(vh * 0.58);
  let el = document.elementFromPoint(x, y);
  while (el && el !== document.documentElement) {
    if (isScrollableBox(el)) {
      cachedScrollRoot = el;
      return el;
    }
    el = el.parentElement;
  }
  for (let i = 0; i < DyAdapter.SCROLL_ROOT_HINTS.length; i++) {
    const nodes = document.querySelectorAll(DyAdapter.SCROLL_ROOT_HINTS[i]);
    for (let j = 0; j < nodes.length; j++) {
      if (isScrollableBox(nodes[j])) {
        cachedScrollRoot = nodes[j];
        return nodes[j];
      }
    }
  }
  const fallback = document.scrollingElement || document.documentElement;
  cachedScrollRoot = fallback;
  return fallback;
}

function fireWheel(target, delta) {
  if (!target) return;
  const opts = {
    deltaY: delta,
    deltaMode: 0,
    bubbles: true,
    cancelable: true,
    composed: true,
    view: window
  };
  try {
    target.dispatchEvent(new WheelEvent("wheel", opts));
  } catch (e) {}
}

function clickLoadMore() {
  const nodes = document.querySelectorAll(DyAdapter.LOAD_MORE_SCAN_SEL);
  for (let i = 0; i < nodes.length && i < 400; i++) {
    const el = nodes[i];
    if (isOurPanel(el) || el.childElementCount > 2) continue;
    const t = (el.innerText || el.textContent || "").trim();
    if (t.length > 8) continue;
    if (DyAdapter.LOAD_MORE_TEXTS.indexOf(t) !== -1) {
      el.click();
      return true;
    }
  }
  return false;
}

function goNextPlayer() {
  for (let i = 0; i < DyAdapter.NEXT_PLAYER_SELS.length; i++) {
    const el = document.querySelector(DyAdapter.NEXT_PLAYER_SELS[i]);
    if (el && !isOurPanel(el)) {
      el.click();
      return;
    }
  }
  const video = document.querySelector(DyAdapter.VIDEO_SEL);
  let node = video && video.parentElement;
  while (node && node !== document.body) {
    if (node.scrollHeight > node.clientHeight + 40) {
      node.scrollTop += node.clientHeight;
      fireWheel(node, node.clientHeight);
      return;
    }
    node = node.parentElement;
  }
  const evInit = {
    key: "ArrowDown",
    code: "ArrowDown",
    keyCode: 40,
    which: 40,
    bubbles: true,
    cancelable: true
  };
  document.dispatchEvent(new KeyboardEvent("keydown", evInit));
  fireWheel(video || document.elementFromPoint(window.innerWidth * 0.4, window.innerHeight * 0.5), 900);
}

function autoScrollDown() {
  const root = findScrollRoot();
  const step = Math.max(520, Math.floor((root.clientHeight || window.innerHeight) * 0.8));
  const beforeTop = root.scrollTop;
  const beforeH = root.scrollHeight;
  const nearBottom = beforeTop + (root.clientHeight || 0) >= beforeH - 120;

  root.scrollTop = nearBottom ? beforeH : beforeTop + step;
  try {
    root.scrollBy(0, step);
  } catch (e) {}

  const mid = document.elementFromPoint(window.innerWidth * 0.38, window.innerHeight * 0.55);
  fireWheel(root, step);
  if (mid && mid !== root) fireWheel(mid, step);
  window.scrollBy(0, step);

  const moved = root.scrollTop !== beforeTop || root.scrollHeight !== beforeH;
  if (!moved) {
    stuckTicks += 1;
    cachedScrollRoot = null;
    if (stuckTicks >= 2) clickLoadMore();
    if (stuckTicks >= 3) {
      const se = document.scrollingElement || document.documentElement;
      se.scrollTop += step;
      fireWheel(document.body, step);
    }
  } else {
    stuckTicks = 0;
  }
}

function goNext() {
  if (!isPlayerMode() && !isSearchPage()) maybeSwitchChannel();
  if (isSearchPage()) maybeSwitchSearchWord();
  if (isPlayerMode()) goNextPlayer();
  else autoScrollDown();
  if (isSearchPage()) scanSearchDom();
}

function scanSearchDom() {
  if (!settings.collecting || !isSearchPage()) return;
  const links = document.querySelectorAll(DyAdapter.USER_LINK_SEL);
  const seen = new Set();
  for (let i = 0; i < links.length; i++) {
    const a = links[i];
    const href = a.getAttribute("href") || "";
    const m = href.match(DyAccountParse.SEC_UID_RE);
    if (!m) continue;
    let card = null;
    for (let k = 0; k < DyAdapter.CARD_CLOSEST_SELS.length; k++) {
      card = a.closest(DyAdapter.CARD_CLOSEST_SELS[k]);
      if (card) break;
    }
    card = card || a.parentElement;
    if (!card || seen.has(card) || isOurPanel(card)) continue;
    seen.add(card);
    const text = card.innerText || "";
    if (!text || text.length > 5000) continue;
    if (!DyAccountParse.blobHasDeclare(text)) continue;
    const hasMix = text.indexOf(DyAdapter.MIX_TEXT) !== -1;
    if (settings.requireMix && !hasMix) continue;
    const videoA = card.querySelector(DyAdapter.VIDEO_LINK_SEL);
    const videoUrl = videoA ? videoA.getAttribute("href") || "" : "";
    const awemeId = (videoUrl.match(/\/video\/(\d+)/) || [])[1] || currentAwemeId();
    const rec = DyAccountParse.fromDomHints({
      awemeId: awemeId,
      secUid: m[0],
      nickname: String(a.innerText || a.getAttribute(DyAdapter.ARIA_LABEL_ATTR) || "").trim().slice(0, 40),
      hasMix: hasMix,
      mixName: hasMix ? "合集" : ""
    });
    if (rec) handleRecords([rec], "search-dom");
  }
}

// v1.7：被动读取搜索页右侧「相关搜索」板块，把平台实时关联词并入本次会话词池
function harvestRelatedSearches() {
  if (!settings.collecting || !isSearchPage()) return;
  if (dynamicWords.length >= DYNAMIC_WORDS_MAX) return;
  const now = Date.now();
  if (now - lastHarvestAt < 8000) return;
  lastHarvestAt = now;
  const scope = document.querySelectorAll(DyAdapter.RELATED_SCOPE_SEL);
  let head = null;
  for (let i = 0; i < scope.length; i++) {
    const el = scope[i];
    if (isOurPanel(el) || el.childElementCount > 0) continue;
    const t = (el.innerText || el.textContent || "").trim();
    if (t === DyAdapter.RELATED_TITLE_TEXT) { head = el; break; }
  }
  if (!head || !head.parentElement) return;
  const items = head.parentElement.querySelectorAll(DyAdapter.RELATED_ITEM_SEL);
  const known = allSearchWords().slice();
  let added = 0;
  for (let i = 0; i < items.length && added < 12; i++) {
    const el = items[i];
    if (isOurPanel(el) || el.childElementCount > 0) continue;
    const t = (el.innerText || el.textContent || "").trim();
    if (!t || t.length < 2 || t.length > 30) continue;
    if (t === DyAdapter.RELATED_TITLE_TEXT || t.indexOf(DyAdapter.MORE_TEXT) !== -1 || /…/.test(t) || /\.{2,}/.test(t)) continue;
    if (known.indexOf(t) !== -1) continue;
    known.push(t);
    dynamicWords.push(t);
    added++;
  }
  if (added) {
    stats.lastHint = "已并入 " + added + " 个「相关搜索」词（本次会话共 " + dynamicWords.length + " 个）";
    persist();
    renderPanel();
  }
}

/* ② v1.7：搜索页自动切「最新发布」排序。
 * 原则：只被动点击页面自身控件（派发真实鼠标事件序列），不额外发请求。
 * 门禁：采集中 + 开关开 + 搜索页 + 非播放页。
 * 去重：以 location.pathname 作为「已应用」键，避免排序改写 query 后反复触发；
 *       每个 URL 最多尝试 3 次，3s 节流。 */
let sortAppliedFor = "";
let sortKey = "";
let sortTries = 0;
let sortBusy = false;
let lastSortTryAt = 0;

/* 在 root 范围内（默认整页）找文本精确等于 text 的最佳可点元素。
 * 关键：用 elementFromPoint 校验「中心点确实能被该元素或其子元素命中」，
 * 避免点到隐藏的 / 被遮挡的重复节点（抖音页面常有多个同名文字）。 */
function clickableByText(text, root) {
  const scope = root || document;
  const all = scope.querySelectorAll(DyAdapter.CLICKABLE_SCAN_SEL);
  let best = null;
  let bestScore = -Infinity;
  for (let i = 0; i < all.length; i++) {
    const el = all[i];
    if (isOurPanel(el)) continue;
    if ((el.innerText || el.textContent || "").trim() !== text) continue;
    const rect = el.getBoundingClientRect();
    if (!rect || rect.width < 2 || rect.height < 2) continue;
    if (rect.bottom < 0 || rect.top > window.innerHeight || rect.right < 0 || rect.left > window.innerWidth) continue;
    const cx = Math.round(rect.left + rect.width / 2);
    const cy = Math.round(rect.top + rect.height / 2);
    let top = null;
    try { top = document.elementFromPoint(cx, cy); } catch (e) {}
    const hitSelf = !!(top && (top === el || el.contains(top)));
    /* 命中中心点者优先；同等条件下取子元素最少的（最接近真实点击目标） */
    const score = (hitSelf ? 100000 : 0) - el.childElementCount;
    if (score > bestScore) {
      bestScore = score;
      best = el;
    }
  }
  return best;
}

/* 派发完整鼠标事件序列（pointerdown→mousedown→pointerup→mouseup→click）。
 * 若中心点被某个后代接住，就把事件打在该后代上，最接近真人点击。 */
function realClick(el) {
  if (!el) return false;
  const rect = el.getBoundingClientRect();
  const x = Math.round(rect.left + rect.width / 2);
  const y = Math.round(rect.top + rect.height / 2);
  let target = el;
  try {
    const top = document.elementFromPoint(x, y);
    if (top && top !== el && el.contains(top)) target = top;
  } catch (e) {}
  const base = { bubbles: true, cancelable: true, composed: true, clientX: x, clientY: y, view: window };
  try {
    target.dispatchEvent(new PointerEvent("pointerdown", { ...base, pointerId: 1, pointerType: "mouse", isPrimary: true, button: 0, buttons: 1 }));
    target.dispatchEvent(new MouseEvent("mousedown", { ...base, button: 0, buttons: 1 }));
    target.dispatchEvent(new PointerEvent("pointerup", { ...base, pointerId: 1, pointerType: "mouse", isPrimary: true, button: 0, buttons: 0 }));
    target.dispatchEvent(new MouseEvent("mouseup", { ...base, button: 0, buttons: 0 }));
    target.dispatchEvent(new MouseEvent("click", { ...base, button: 0, buttons: 0 }));
  } catch (e) {
    try { target.click(); } catch (e2) { return false; }
  }
  return true;
}

/* 收起筛选面板：派发 Escape，双保险（document + body） */
function closeFilterPanel() {
  try {
    const esc = { key: "Escape", keyCode: 27, which: 27, bubbles: true };
    document.dispatchEvent(new KeyboardEvent("keydown", esc));
    if (document.body) document.body.dispatchEvent(new KeyboardEvent("keydown", esc));
  } catch (e) {}
}

/* 从某元素向上找「筛选面板」根节点：最近的同时含「排序依据」或「内容形式」的祖先 */
function filterPanelRoot(el) {
  let p = el;
  for (let i = 0; i < 8 && p && p.parentElement; i++) {
    p = p.parentElement;
    const t = p.innerText || "";
    if (t.indexOf(DyAdapter.SORT_BY_TEXT) !== -1 || t.indexOf(DyAdapter.CONTENT_FORM_TEXT) !== -1) return p;
  }
  return null;
}

function applyLatestSort() {
  if (sortBusy) return;
  if (!settings.collecting) return;
  if (settings.autoSortLatest === false) return;
  if (!isSearchPage() || isPlayerMode()) return;
  const key = location.pathname;
  if (sortAppliedFor === key) return;
  const now = Date.now();
  if (now - lastSortTryAt < 3000) return;
  if (sortKey !== key) {
    sortKey = key;
    sortTries = 0;
  }
  if (sortTries >= 3) return;
  sortTries++;
  lastSortTryAt = now;

  /* 触发按钮候选：优先「筛选」，兼容显示当前排序的按钮 */
  const trigger = clickableByText("筛选") || clickableByText("综合排序");
  if (!trigger) return;

  sortBusy = true;
  realClick(trigger);

  /* 轮询等面板出现（最长 ~1.8s），比固定 400ms 稳 */
  let waited = 0;
  const poll = setInterval(function () {
    waited += 150;
    const opt = clickableByText("最新发布");
    if (opt) {
      clearInterval(poll);
      const panel = filterPanelRoot(opt); /* 先取面板根，防止点完后被关闭 */
      realClick(opt);
      setTimeout(function () {
        /* 内容形式→视频：只在面板范围内找，避免误点页面顶部的「视频」标签页 */
        const vid = panel ? clickableByText("视频", panel) : null;
        if (vid) realClick(vid);
        closeFilterPanel();
        sortAppliedFor = key;
        stats.lastHint = "搜索页已切「最新发布」" + (vid ? "，内容形式→视频" : "");
        persist();
        renderPanel();
      }, 350);
      return;
    }
    if (waited >= 1800) {
      clearInterval(poll);
      stats.lastHint = "点了「筛选」但面板没出现（第 " + sortTries + " 次尝试）";
      renderPanel();
      closeFilterPanel();
      sortBusy = false;
    }
  }, 150);
}

function stopAuto() {
  if (autoTimer) {
    clearTimeout(autoTimer);
    autoTimer = null;
  }
}

function jitterDelay() {
  const base = Math.max(1000, Number(settings.intervalMs) || 4000);
  if (Math.random() < 0.15) return base * 3 + Math.random() * base * 3;
  return base * 0.6 + Math.random() * base * 0.8;
}

function scheduleNext() {
  if (!isExtAlive()) return;
  if (autoTimer) clearTimeout(autoTimer);
  if (settings.collecting && settings.autoBrowse) {
    autoTimer = setTimeout(function () {
      if (!isExtAlive()) return;
      goNext();
      scheduleNext();
    }, jitterDelay());
  }
}

function syncAuto() {
  stopAuto();
  if (settings.collecting && settings.autoBrowse) {
    goNext();
    scheduleNext();
  }
}

/* ================= 随机区间定时刷新（v1.3.2） =================
 * 替代同事的「随机刷新控制器.exe」：那个 exe 是对前台窗口盲发 Ctrl+F5，会打到
 * 浏览器之外的窗口/应用。这里只刷「发起刷新的这个标签页」自己，且只在采集页生效，
 * 走 background 的 chrome.tabs.reload(tabId, { bypassCache:true })，语义等价对当前页 Ctrl+F5。
 * 注：多个采集页会各自独立计时、各自刷自己，互不干扰——这正是期望行为。
 */
function isRefreshAllowedPage() {
  /* 白名单：精选页 / 搜索页 / 播放页 / 四个采集栏目页 */
  const p = location.pathname;
  if (DyAdapter.JINGXUAN_PATH_RE.test(p)) return true;
  if (isSearchPage() || isPlayerMode()) return true;
  const t = currentChannelTab();
  return !!(t && DyAdapter.CHANNELS.some((c) => tabMatches(t, c)));
}

/* clampRefreshSec 来自 shared.js（与 popup 同口径）；本文件只保留调度用的 refreshRange() */

/* 区间取值：非法一律回落默认；越界夹到 30~3600；min>max 自动交换 */
function refreshRange() {
  let mn = Math.round(Number(settings.refreshMinSec));
  let mx = Math.round(Number(settings.refreshMaxSec));
  if (!isFinite(mn) || mn <= 0) mn = 180;
  if (!isFinite(mx) || mx <= 0) mx = 480;
  mn = Math.min(DyShared.REFRESH_MAX_LIMIT, Math.max(DyShared.REFRESH_MIN_LIMIT, mn));
  mx = Math.min(DyShared.REFRESH_MAX_LIMIT, Math.max(DyShared.REFRESH_MIN_LIMIT, mx));
  if (mn > mx) {
    const t = mn;
    mn = mx;
    mx = t;
  }
  return { min: mn, max: mx };
}

function nextRefreshDelay() {
  const r = refreshRange();
  return (r.min + Math.random() * (r.max - r.min)) * 1000;
}

function clearRefreshTimers() {
  if (refreshTimer) {
    clearTimeout(refreshTimer);
    refreshTimer = null;
  }
  if (refreshTickTimer) {
    clearInterval(refreshTickTimer);
    refreshTickTimer = null;
  }
}

function updateRefreshText() {
  const root = document.getElementById(PANEL_ID);
  if (!root) return;
  const btn = root.querySelector('[data-act="refresh-start"]');
  if (btn) btn.textContent = settings.refreshArmed ? "停止计时" : "开始计时";
  const el = root.querySelector('[data-k="refreshText"]');
  if (!el) return;
  let txt;
  if (!settings.refreshEnabled) txt = "随机刷新：未启用";
  else if (refreshPaused) txt = "随机刷新：已暂停（页面故障处理中）";
  else if (!settings.refreshArmed) txt = "随机刷新：已停止";
  else if (!refreshAt) txt = "随机刷新：运行中";
  else {
    const left = Math.max(0, (refreshAt - Date.now()) / 1000).toFixed(1);
    txt =
      "随机刷新：运行中 · 下次刷新 " + left + " 秒 · 已自动刷 " + refreshCount +
      " 次 · 跳过 " + refreshSkips + " 次";
    if (!settings.collecting) txt += " · 当前未采集（刷新后不会自动续采）";
  }
  el.textContent = txt;
}

function startRefreshTick() {
  updateRefreshText();
  if (refreshTickTimer) return;
  refreshTickTimer = setInterval(() => {
    if (!isExtAlive()) {
      clearRefreshTimers();
      return;
    }
    updateRefreshText();
  }, 1000);
}

function scheduleRefresh() {
  if (refreshTimer) {
    clearTimeout(refreshTimer);
    refreshTimer = null;
  }
  if (!settings.refreshEnabled || !settings.refreshArmed || refreshPaused) {
    refreshAt = 0;
    clearRefreshTimers();
    updateRefreshText();
    return;
  }
  const delay = nextRefreshDelay();
  refreshAt = Date.now() + delay;
  refreshTimer = setTimeout(onRefreshDue, delay);
  startRefreshTick();
  updateRefreshText();
}

function startRefresh(fromUser) {
  settings.refreshArmed = true;
  refreshPaused = false;
  refreshSkips = 0;
  scheduleRefresh();
  if (fromUser) {
    const r = refreshRange();
    stats.lastHint = settings.collecting
      ? "已开始随机刷新计时（" + r.min + "~" + r.max + " 秒）"
      : "已开始随机刷新计时；当前未采集，刷新后不会自动续采";
    persist();
    renderPanel();
  }
}

function stopRefresh() {
  clearRefreshTimers();
  refreshAt = 0;
  refreshPaused = false;
  settings.refreshArmed = false;
  updateRefreshText();
}

/* 让路：页面自己检测到故障、要去 location.reload() 时先把随机刷新让开，
 * 避免两边同时刷；故障处理结束（或刷新后 boot）再恢复。 */
function pauseRefresh() {
  if (!settings.refreshArmed || refreshPaused) return;
  refreshPaused = true;
  clearRefreshTimers();
  refreshAt = 0;
  updateRefreshText();
}

function resumeRefresh() {
  if (!refreshPaused) return;
  refreshPaused = false;
  scheduleRefresh();
}

/* 遥控端（popup 或其它标签页）改了「启用 / 计时」态：跟着起表或停表。
 * 只改区间时不重排 —— 运行中改区间只影响下一次。 */
function applyRefreshToggle(prevEnabled, prevArmed) {
  if (!!settings.refreshEnabled === !!prevEnabled && !!settings.refreshArmed === !!prevArmed) return;
  if (settings.refreshEnabled && settings.refreshArmed) {
    refreshPaused = false;
    refreshSkips = 0;
    scheduleRefresh();
  } else {
    clearRefreshTimers();
    refreshAt = 0;
    updateRefreshText();
  }
}

/* 真正发起刷新：发给 background，用 sender.tab.id 刷本标签页 */
function reloadCurrentTab(countIt) {
  refreshSkips = 0;
  reloading = true;
  persist(); // 刷新后 boot() 见 collecting/refreshArmed 仍在，自动续采 + 续表
  renderPanel();
  /* 「已刷 N 次」由 background 单点累加（count=true 才记），本页值经 storage.onChanged 同步回来 */
  sendBg({ type: "reloadTab", count: !!countIt }).then((r) => {
    if (!r || !r.ok) {
      /* background 没接住：不能让 reloading 卡死，解除并排下一轮 */
      reloading = false;
      stats.lastHint = "随机刷新失败：无法刷新当前页";
      renderPanel();
      scheduleRefresh();
    }
  });
}

function doRefreshNow() {
  if (!isRefreshAllowedPage()) {
    stats.lastHint = "当前页不在采集范围，未刷新";
    renderPanel();
    return;
  }
  if (reloading) return;
  stats.lastHint = settings.collecting
    ? "已手动刷新当前页，等待自动续采…"
    : "已手动刷新当前页（当前未采集，不会自动续采）";
  reloadCurrentTab(false);
}

function onRefreshDue() {
  refreshTimer = null;
  refreshAt = 0;
  if (!isExtAlive()) return;
  if (!settings.refreshEnabled || !settings.refreshArmed || refreshPaused) {
    clearRefreshTimers();
    updateRefreshText();
    return;
  }
  /* 让路：页面正在自动刷新 / 处于故障或验证码态 → 本轮不刷，直接排下一轮 */
  if (reloading || fatal || captchaHandled) {
    scheduleRefresh();
    return;
  }
  /* 只刷采集页：不在白名单就跳过本轮（跳过次数不持久化） */
  if (!isRefreshAllowedPage()) {
    refreshSkips++;
    scheduleRefresh();
    return;
  }
  stats.lastHint = "随机刷新：已刷新页面，等待自动续采…";
  reloadCurrentTab(true);
}

function detectServiceError(text) {
  if (!settings.collecting || reloading) return false;
  if (!contains(text, "服务异常")) return false;
  pauseRefresh(); // 让路：这次刷新由故障恢复接管
  reloading = true;
  settings.autoBrowse = false;
  stopAuto();
  stats.lastHint = "检测到服务异常，已停止下滑，即将刷新…";
  /* 抖动类（黄灯）：A.2 要求改为暂停观察 + 退避重试；
   * 退避恢复动作属 A.2「重试动作（新增）」项，本次未做，暂保留原有的刷新恢复，先补台账。 */
  guardReportFault({
    level: "yellow",
    fingerprint: "抖动 / 服务异常",
    source: "content: detectServiceError",
    message: "页面出现「服务异常」，已停止下滑并刷新一次。"
  });
  persist();
  renderPanel();
  setTimeout(function () {
    location.reload();
  }, 1500);
  return true;
}

function detectSearchLimit(text) {
  if (!settings.collecting || reloading) return false;
  if (!contains(text, "搜索过于频繁")) return false;
  fatal = true;
  settings.collecting = false;
  settings.autoBrowse = false;
  settings.autoSwitchSearch = false;
  settings.autoSwitchChannel = false;
  stopAuto();
  stopRefresh(); // 自动停采集 → 停表
  stats.lastHint = "检测到搜索过于频繁，已停止采集与换词，请稍后重试";
  guardReportFault({
    level: "red",
    fingerprint: "限流 / 搜索过于频繁",
    source: "content: detectSearchLimit",
    title: "搜索被限流",
    message: "检测到「搜索过于频繁」，已停止采集和自动换词，请等待一段时间后重试。"
  });
  persist();
  renderPanel();
  return true;
}

function detectLogout(text) {
  if (!settings.collecting || fatal || reloading) return false;
  if (!DyAdapter.LOGOUT_TEXTS.some((t) => contains(text, t))) return false;
  fatal = true;
  settings.collecting = false;
  settings.autoBrowse = false;
  settings.autoSwitchSearch = false;
  settings.autoSwitchChannel = false;
  stopAuto();
  stopRefresh(); // 自动停采集 → 停表
  stats.lastHint = "检测到账号被登出，已停止采集，请重新登录后刷新页面再开始";
  guardReportFault({
    level: "red",
    fingerprint: "限流 / 登录已过期",
    source: "content: detectLogout",
    title: "账号已登出",
    message: "检测到需要重新登录，采集已自动停止。请重新登录后刷新页面再开始。"
  });
  persist();
  renderPanel();
  return true;
}

function resetCaptchaFlag() {
  captchaHandled = false;
  fatal = false;
  lastNewAuthorAt = Date.now();
  lastDataAt = Date.now();
  lastNavAt = Date.now();
  warmUpUntil = Date.now() + WARMUP_MS; // 每次开启采集/自动下滑都重新起算预热期
  sessionStartAt = Date.now();
  stuckTicks = 0;
  healthySince = 0;
  clearNoResponseRetry(); // 人工重新开始 = 自动刷新重试计数归零
}

function detectCaptcha(text) {
  if (!settings.collecting || captchaHandled || reloading) return false;
  if (!DyAdapter.CAPTCHA_TEXTS.some((t) => contains(text, t))) return false;
  captchaHandled = true;
  fatal = true;
  settings.autoBrowse = false;
  settings.autoSwitchChannel = false;
  settings.autoSwitchSearch = false;
  stopAuto();
  stats.lastHint = "检测到人机验证，已暂停，请手动验证后点「自动下滑」恢复";
  guardReportFault({
    level: "red",
    fingerprint: "限流 / 人机验证",
    source: "content: detectCaptcha",
    title: "检测到人机验证",
    message: "采集已自动暂停，请手动完成页面验证后点「自动下滑」恢复。"
  });
  persist();
  renderPanel();
  return true;
}

function notifySystem(title, message) {
  try {
    chrome.runtime.sendMessage({ type: "notify", title, message });
  } catch (e) {}
}

function detectNonCollectChannel() {
  if (!settings.collecting || reloading || captchaHandled || fatal) return;
  if (isSearchPage() || isPlayerMode()) return;
  const t = currentChannelTab();
  if (!t) return;
  if (DyAdapter.CHANNELS.some((c) => tabMatches(t, c))) return;
  fatal = true;
  settings.collecting = false;
  settings.autoBrowse = false;
  stopAuto();
  stats.lastHint = "非采集栏目「" + t + "」，已停止，请切换到 全部/二次元/小剧场/影视";
  stopRefresh(); // 自动停采集 → 停表
  persist();
  renderPanel();
  notifySystem("非采集栏目", "当前栏目「" + t + "」不在采集范围，已自动停止。请切换到 全部/二次元/小剧场/影视 后重新开始。");
}

function showIdlePrompt() {
  const el = document.getElementById(PANEL_ID);
  if (!el) return;
  const p = el.querySelector('[data-k="prompt"]');
  if (p) p.hidden = false;
}

function hideIdlePrompt() {
  const el = document.getElementById(PANEL_ID);
  if (!el) return;
  const p = el.querySelector('[data-k="prompt"]');
  if (p) p.hidden = true;
}

function detectIdlePrompt() {
  if (!settings.collecting || !settings.autoBrowse) return;
  if (captchaHandled || reloading || fatal) return;
  if (!settings.requireMix) return;
  if (Date.now() - lastNewAuthorAt < IDLE_PROMPT_MS) return;
  lastNewAuthorAt = Date.now();
  showIdlePrompt();
  stats.lastHint = "已 5 分钟无新作者，可在下方选择是否放宽条件";
  persist();
  renderPanel();
}

function noResponseTries() {
  try {
    return parseInt(sessionStorage.getItem(RELOAD_TRY_KEY), 10) || 0;
  } catch (e) {
    return 0;
  }
}

function setNoResponseTries(n) {
  try {
    if (n > 0) sessionStorage.setItem(RELOAD_TRY_KEY, String(n));
    else sessionStorage.removeItem(RELOAD_TRY_KEY);
  } catch (e) {}
}

/* 撤销「已排定的自动刷新」（页面在等待期自己恢复了） */
function cancelNoResponseReload() {
  if (noResponseReloadTimer) {
    clearTimeout(noResponseReloadTimer);
    noResponseReloadTimer = null;
  }
  noResponseReloadAt = 0;
  resumeRefresh(); // 页面自己恢复了，把让开的随机刷新还回来
}

function clearNoResponseRetry() {
  cancelNoResponseReload();
  setNoResponseTries(0);
}

/* 每次收到数据调用：先撤掉待刷新；连续有数据满 RECOVERY_STABLE_MS 才算真恢复，
 * 才清零重试计数（避免「刷新→吐一条→又卡」被当成恢复，导致退避永远不升级）。 */
function noteDataFlow() {
  if (noResponseReloadAt) cancelNoResponseReload();
  if (!healthySince) {
    healthySince = Date.now();
    return;
  }
  if (Date.now() - healthySince >= RECOVERY_STABLE_MS) clearNoResponseRetry();
}

function detectNoResponse() {
  if (!settings.collecting || !settings.autoBrowse) return false;
  if (captchaHandled || reloading || fatal) return false;
  if (Date.now() < warmUpUntil) {
    // 预热期内：既不判无响应、也不换词；同时把基线推后，
    // 让预热结束后的判定从零重新起算（否则启动时已把 lastDataAt 重置，
    // 预热期会被 90s 窗口直接吃掉，等于没生效）。
    lastDataAt = Date.now();
    lastNavAt = Date.now();
    return false;
  }
  if (Date.now() - lastNavAt < SWITCH_GRACE_MS) return false;
  if (Date.now() - lastDataAt < NO_RESPONSE_MS) return false;
  if (isSearchPage()) {
    switchSearchWord(EMPTY_SCAN_SWITCH_MS); // 扫不到数据就换词，不停止
    return false; // 搜索页豁免：不算本地故障，不建账（A.2 / 3.3）
  }
  /* 本地类（红灯）：方案 B —— 不再静默停死。
   * 命中后先按退避序列等一段时间（给页面自愈机会），到点原地刷新；刷新后 boot() 见
   * collecting 仍为 true 会自动续采，全程无需人工。用尽重试次数仍无响应才停下转人工。 */
  const tries = noResponseTries();
  if (tries >= RELOAD_BACKOFF_MS.length) {
    cancelNoResponseReload();
    healthySince = 0;
    settings.collecting = false;
    settings.autoBrowse = false;
    stopAuto();
    stopRefresh(); // 自动停采集 → 停表
    stats.lastHint = "连续无响应，自动刷新 " + tries + " 次仍未恢复，已停止，请检查网络或页面状态";
    guardReportFault({
      level: "red",
      fingerprint: "本地 / 连续无响应",
      source: "content: detectNoResponse",
      title: "采集已自动停止",
      message: "连续无响应，已自动刷新 " + tries + " 次仍未恢复，请检查网络或页面状态后重新开始。"
    });
    persist();
    renderPanel();
    return true;
  }
  if (noResponseReloadAt) return false; // 已排定，等定时器执行
  const wait = RELOAD_BACKOFF_MS[tries];
  noResponseReloadAt = Date.now() + wait;
  lastDataAt = Date.now(); // 推后基线，避免 2.5s 轮询重复排定
  healthySince = 0;
  stats.lastHint =
    "连续无响应，" + Math.round(wait / 1000) + "s 后自动刷新（第 " + (tries + 1) + "/" + RELOAD_BACKOFF_MS.length + " 次）";
  renderPanel();
  noResponseReloadTimer = setTimeout(function () {
    noResponseReloadTimer = null;
    noResponseReloadAt = 0;
    if (!isExtAlive() || !settings.collecting) return;
    setNoResponseTries(tries + 1);
    pauseRefresh(); // 让路：这次刷新由故障恢复接管
    reloading = true;
    stats.lastHint = "连续无响应，正在自动刷新并继续采集…";
    persist(); // 确保刷新后 collecting 仍为 true，boot() 才会自动续采
    location.reload();
  }, wait);
  return true;
}

function channelState() {
  try {
    const idxRaw = sessionStorage.getItem("dyaccount_ci");
    const cntRaw = sessionStorage.getItem("dyaccount_cc");
    return {
      index: idxRaw === null ? -1 : (parseInt(idxRaw, 10) || 0),
      count: cntRaw === null ? 0 : (parseInt(cntRaw, 10) || 0)
    };
  } catch (e) {
    return { index: -1, count: 0 };
  }
}

function saveChannelState(idx, cnt) {
  try {
    sessionStorage.setItem("dyaccount_ci", String(idx));
    sessionStorage.setItem("dyaccount_cc", String(cnt));
  } catch (e) {}
}

function searchState() {
  try {
    const idxRaw = sessionStorage.getItem("dyaccount_si");
    const cntRaw = sessionStorage.getItem("dyaccount_sc");
    return {
      index: idxRaw === null ? -1 : (parseInt(idxRaw, 10) || 0),
      count: cntRaw === null ? 0 : (parseInt(cntRaw, 10) || 0)
    };
  } catch (e) {
    return { index: -1, count: 0 };
  }
}

function saveSearchState(idx, cnt) {
  try {
    sessionStorage.setItem("dyaccount_si", String(idx));
    sessionStorage.setItem("dyaccount_sc", String(cnt));
  } catch (e) {}
}

function loadSwitchLock() {
  return new Promise((resolve) => {
    chrome.storage.local.get("lastSwitchSearchAt", (data) => {
      lastSwitchAt = Number(data.lastSwitchSearchAt) || 0;
      resolve();
    });
  });
}

function setSwitchLock() {
  lastSwitchAt = Date.now();
  try {
    chrome.storage.local.set({ lastSwitchSearchAt: lastSwitchAt });
  } catch (e) {}
}

function makeSearchOrder(currentIdx) {
  const idx = [];
  for (let i = 0; i < allSearchWords().length; i++) idx.push(i);
  for (let i = idx.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    const t = idx[i];
    idx[i] = idx[j];
    idx[j] = t;
  }
  if (idx.length > 1 && idx[0] === currentIdx) {
    const j = 1 + Math.floor(Math.random() * (idx.length - 1));
    const t = idx[0];
    idx[0] = idx[j];
    idx[j] = t;
  }
  searchOrder = idx;
  searchOrderPos = 0;
}

function nextSearchWordIndex(currentIdx) {
  if (!searchOrder.length || searchOrderPos >= searchOrder.length) {
    makeSearchOrder(currentIdx);
  }
  let idx = searchOrder[searchOrderPos++];
  if (idx === currentIdx && searchOrderPos < searchOrder.length) {
    idx = searchOrder[searchOrderPos++];
  }
  return idx;
}

function switchSearchWord(minMs) {
  const now = Date.now();
  const floor = Number(minMs) > 0 ? Number(minMs) : (settings.switchSearchMinMs || SWITCH_SEARCH_MIN_MS);
  if (now - lastSwitchAt < floor) return;
  setSwitchLock();
  const st = searchState();
  const currentIdx = st.index >= 0 ? st.index : 0;
  const next = nextSearchWordIndex(currentIdx);
  const word = allSearchWords()[next];
  saveSearchState(next, 0);
  cachedScrollRoot = null;
  stuckTicks = 0;
  stats.lastHint = "换搜索词 → " + word;
  persist();
  renderPanel();
  lastNavAt = Date.now();
  setTimeout(function () {
    location.href = DyAdapter.SEARCH_URL_BASE + encodeURIComponent(word);
  }, 500);
}

function maybeSwitchSearchWord() {
  if (!settings.autoSwitchSearch) return;
  const st = searchState();
  st.count += 1;
  saveSearchState(st.index, st.count);
  if (st.count >= SWITCH_SEARCH_AFTER || stuckTicks >= 3) {
    switchSearchWord();
  }
}

function normTab(t) {
  return String(t || "").replace(/\s+/g, "");
}

function tabMatches(t, name) {
  const a = normTab(t);
  const b = normTab(name);
  return a === b || (b !== "" && a.indexOf(b) === 0);
}

function findChannelTablist() {
  const lists = document.querySelectorAll(DyAdapter.TABLIST_SELS.join(", "));
  for (let i = 0; i < lists.length; i++) {
    const list = lists[i];
    if (isOurPanel(list)) continue;
    const tabs = list.querySelectorAll(DyAdapter.TAB_SELS.join(", "));
    for (let j = 0; j < tabs.length; j++) {
      if (tabMatches((tabs[j].innerText || tabs[j].textContent || ""), DyAdapter.CHANNEL_ALL_TEXT)) return list;
    }
  }
  return null;
}

function currentChannelTab() {
  const list = findChannelTablist();
  if (!list) return null;
  const tabs = list.querySelectorAll(DyAdapter.TAB_SELS.join(", "));
  for (let i = 0; i < tabs.length; i++) {
    const el = tabs[i];
    if (isOurPanel(el)) continue;
    const t = (el.innerText || el.textContent || "").trim();
    if (!t) continue;
    const sel = el.getAttribute(DyAdapter.TAB_SELECTED_ATTR);
    const cls = el.className || "";
    if (sel === "true" || DyAdapter.TAB_ACTIVE_CLASS_RE.test(cls)) return normTab(t);
  }
  return null;
}

function findChannelTab(name) {
  const precise = document.querySelectorAll(DyAdapter.TAB_SELS.join(", "));
  for (let i = 0; i < precise.length; i++) {
    const el = precise[i];
    if (isOurPanel(el)) continue;
    const t = (el.innerText || el.textContent || "").trim();
    if (tabMatches(t, name)) return el;
  }
  const nodes = document.querySelectorAll(DyAdapter.TAB_FALLBACK_SCAN_SEL);
  for (let i = 0; i < nodes.length; i++) {
    const el = nodes[i];
    if (isOurPanel(el)) continue;
    const t = (el.innerText || el.textContent || "").trim();
    if (tabMatches(t, name) && el.childElementCount <= 1) {
      const c = el.closest(DyAdapter.TAB_CLOSEST_SEL);
      if (c && !isOurPanel(c)) return c;
    }
  }
  return null;
}

function switchChannel() {
  const st = channelState();
  const next = (st.index + 1) % DyAdapter.CHANNELS.length;
  const name = DyAdapter.CHANNELS[next];
  saveChannelState(next, 0);
  const tab = findChannelTab(name);
  if (!tab) {
    stats.lastHint = "自动换栏目：未找到「" + name + "」，保持当前";
    renderPanel();
    return;
  }
  tab.click();
  lastNavAt = Date.now();
  cachedScrollRoot = null;
  stuckTicks = 0;
  stats.lastHint = "自动换栏目 → " + name;
  renderPanel();
}

function maybeSwitchChannel() {
  if (!settings.autoSwitchChannel) return;
  const st = channelState();
  st.count += 1;
  saveChannelState(st.index, st.count);
  if (st.count >= SWITCH_AFTER_SCROLLS || stuckTicks >= 3) {
    switchChannel();
  }
}

window.addEventListener("message", (ev) => {
  if (ev.source !== window) return;
  const data = ev.data;
  if (!data || data.source !== MSG_HOOK) return;
  const payload = data.payload;
  if (!payload) return;
  if (payload.type === "ready") {
    sendHookCollecting();
    return;
  }
  if (payload.type === "api" && payload.body) {
    ingestJson(payload.body, "api");
  }
});

/* ================= 守卫接线（v1.2.0） =================
 * 依据：《抖音采集任务-守卫机制改造方案 v1.3》附录 A.2
 * guardAlive 上报、applyLeader 申请、探活应答、整页 502 上报，
 * 以及五个故障检测函数向统一守卫入口（guardReportFault）的收口。
 * 未收口：detectNonCollectChannel（A.2 明确「不改」）、detectIdlePrompt（业务提示，不在故障表内）。
 */
let guardIsLeader = false;
let guardAliveSent = false;
let guardFullPageReported = false;

function guardSend(payload) {
  if (!isExtAlive()) return;
  try { chrome.runtime.sendMessage(payload); } catch (e) {}
}

/* ================= 守卫收口：故障上报统一入口（v1.2.0 · P0） =================
 * 依据：方案 v1.3 附录 A.2 与第三章
 * 五个故障检测函数命中后一律走这里上报，状态与记录由 background 决定，content 只上报、不自建账。
 * 指纹分类沿用第 3.1–3.3 节：抖动（黄灯）/ 限流（红灯）/ 本地（红灯）。
 */
function currentTaskLabel() {
  if (isSearchPage()) {
    const w = currentSearchWord();
    return w ? "搜索页「" + w + "」" : "搜索页";
  }
  if (isPlayerMode()) return "播放页";
  return "精选页";
}

function guardReportFault(ev) {
  if (!isExtAlive()) return;
  const hidden = !!document.hidden;
  try {
    chrome.runtime.sendMessage({
      type: "guardReport",
      hidden: hidden,
      event: {
        kind: "",
        fingerprint: ev.fingerprint || "",
        level: ev.level || "yellow",
        status: 0,
        url: location.href,
        task: currentTaskLabel(),
        source: ev.source || "",
        message: ev.message || ""
      }
    }, () => { void chrome.runtime.lastError; });
  } catch (e) {}
  /* 浮层未实现前：页面可见时仍走旧系统通知通道，保证不静默；
   * 页面不可见时由 background 在 hidden 分支统一发通知，避免重复。
   * 不传 title 的故障（如服务异常）保持原有「页面可见时不打扰」的行为。 */
  if (!hidden && ev.title) notifySystem(ev.title, ev.message || "");
}

/* 页面加载后只发一次，不做周期心跳；tabId 由 background 从 sender.tab.id 补齐 */
function guardAlive() {
  if (guardAliveSent) return;
  guardAliveSent = true;
  guardSend({ type: "guardAlive" });
}

/* 申请 leader：不自行抢占、不报心跳，只等 background 回执 */
function guardApplyLeader() {
  if (!isExtAlive()) return;
  try {
    chrome.runtime.sendMessage({ type: "applyLeader" }, (resp) => {
      if (chrome.runtime.lastError) return;
      guardIsLeader = !!(resp && resp.leader);
    });
  } catch (e) {}
}

/* 整页 502：读顶层文档导航的 responseStatus，非 2xx 判定整页失败 */
function detectFullPageFailure() {
  try {
    const entries = performance.getEntriesByType("navigation");
    const nav = entries && entries[0];
    const status = nav && typeof nav.responseStatus === "number" ? nav.responseStatus : 0;
    return status >= 400 ? status : 0;
  } catch (e) {
    return 0;
  }
}

function guardReportFullPageFailure() {
  if (guardFullPageReported) return;
  const status = detectFullPageFailure();
  if (!status) return;
  guardFullPageReported = true;
  guardSend({
    type: "guardReport",
    hidden: !!document.hidden,
    event: {
      kind: "整页502",
      fingerprint: "抖动 / 整页 502",
      level: "yellow",
      status: status,
      url: location.href,
      attempt: 0,
      source: "content: responseStatus"
    }
  });
}

function guardBoot() {
  guardAlive();
  guardApplyLeader();
  guardReportFullPageFailure();
}

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (!msg) return;
  if (msg.type === "crAction") {
    /* 1.4.0 平台「采集工作区」遥控动作 */
    const p = msg.payload || {};
    try {
      if (msg.action === "scrollOnce") {
        window.scrollBy({ top: Math.round(window.innerHeight * 0.9), behavior: "smooth" });
        sendResponse({ ok: true, message: "已下滑一屏" });
        return;
      }
      if (msg.action === "top") {
        window.scrollTo({ top: 0, behavior: "smooth" });
        sendResponse({ ok: true, message: "已回到顶部" });
        return;
      }
      if (msg.action === "scrollLoop") {
        const seconds = Math.max(1, Math.min(600, Number(p.seconds) || 15));
        settings = { ...settings, autoBrowse: true, collecting: true };
        sendHookCollecting();
        syncAuto();
        persist();
        renderPanel();
        setTimeout(() => {
          settings = { ...settings, autoBrowse: false };
          syncAuto();
          persist();
          renderPanel();
        }, seconds * 1000);
        sendResponse({ ok: true, message: "持续下滑 " + seconds + " 秒" });
        return;
      }
      if (msg.action === "scrollStop") {
        settings = { ...settings, autoBrowse: false };
        syncAuto();
        persist();
        renderPanel();
        sendResponse({ ok: true, message: "已停止自动下滑" });
        return;
      }
      if (msg.action === "toggleCollect") {
        settings = { ...settings, collecting: !!p.on };
        if (p.on) resetCaptchaFlag();
        sendHookCollecting();
        syncAuto();
        persist();
        renderPanel();
        sendResponse({ ok: true, message: p.on ? "已开启采集" : "已暂停采集" });
        return;
      }
      if (msg.action === "search") {
        const word = String(p.keyword || "").trim();
        if (!word) {
          sendResponse({ ok: false, reason: "关键词为空" });
          return;
        }
        location.href = "https://www.douyin.com/search/" + encodeURIComponent(word);
        sendResponse({ ok: true, message: "已跳转搜索：" + word });
        return;
      }
      if (msg.action === "refresh") {
        doRefreshNow();
        sendResponse({ ok: true, message: "已刷新页面" });
        return;
      }
      if (msg.action === "clearCache") {
        authors = {};
        authorListCache = null;
        authorListDirty = true;
        stats.matched = 0;
        recent = [];
        publishLog = [];
        publishSeen = new Set();
        persist();
        renderPanel();
        sendResponse({ ok: true, message: "已清空本地缓存" });
        return;
      }
      sendResponse({ ok: false, reason: "未知动作 " + msg.action });
    } catch (e) {
      sendResponse({ ok: false, reason: String((e && e.message) || e) });
    }
    return;
  }
  if (msg.type === "guardPing") {
    /* 探活应答：background 主动探活，content 只回执，不设定时器 */
    sendResponse({ type: "guardProbeReply", ts: Date.now() });
    return;
  }
  if (msg.type === "guardLeaderChanged") {
    guardIsLeader = !!msg.leader;
    return;
  }
  if (msg.type === "getState") {
    sendResponse({ settings, authors: authorList(), stats });
    return;
  }
  if (msg.type === "setSettings") {
    const prevEnabled = settings.refreshEnabled;
    const prevArmed = settings.refreshArmed;
    settings = { ...settings, ...msg.settings };
    if (msg.settings && (msg.settings.collecting || msg.settings.autoBrowse)) resetCaptchaFlag();
    sendHookCollecting();
    syncAuto();
    applyRefreshToggle(prevEnabled, prevArmed);
    persist();
    renderPanel();
    sendResponse({ ok: true });
    return;
  }
  if (msg.type === "refreshNow") {
    /* 遥控端（popup）的「立即刷新一次」：只有当前页在采集白名单里才刷 */
    if (!isRefreshAllowedPage()) {
      sendResponse({ ok: false, reason: "当前页不在采集范围" });
      return;
    }
    doRefreshNow();
    sendResponse({ ok: true });
    return;
  }
  if (msg.type === "clear") {
    authors = {};
    authorListCache = null;
    authorListDirty = true;
    stats.matched = 0;
    recent = [];
    publishLog = [];
    publishSeen = new Set();
    persist();
    renderPanel();
    sendResponse({ ok: true });
  }
});

chrome.storage.onChanged.addListener((changes, area) => {
  if (area !== "local") return;
  /* 起点在任意入口被重置（结束今天 / 上传自动重置 / 另一端手动）：本页收不到消息，只能靠 storage 感知 */
  if (changes.dayStart) refreshDayStart();
  /* 历史单独一个键，展开状态保持不变，只重绘内容 */
  if (changes.dayStartHistory) refreshDayStart();
  /* 结束今天会在 background 写 stats={scanned:0,matched:0}；
     浮层的内存 stats 不走这条就会停在旧值，直到整页刷新。
     必须排在 authors 分支之前：endToday 是同一次 set() 里写 authors+stats 的，
     先更 stats 再 renderPanel，「已扫视频」才会跟着归零。 */
  if (changes.stats) {
    const s = changes.stats.newValue || {};
    stats.scanned = Number(s.scanned) || 0;
    stats.matched = Number(s.matched) || 0;
    if (!changes.authors) renderPanel();   /* authors 也变了就交给下面的分支去重绘，避免一次写完绘两遍 */
  }
  if (changes.authors) {
    authors = changes.authors.newValue || {};
    authorListCache = null;
    authorListDirty = true;
    renderPanel();
    /* 「今日 N 个」不在 renderPanel 里算：每采到一个作者都问一次 background 太重，
     * 这里按 1.5 秒合并，停手后补最后一次。 */
    scheduleDayStartRefresh();
  }
  if (changes.settings) {
    const prevEnabled = settings.refreshEnabled;
    const prevArmed = settings.refreshArmed;
    settings = { ...DyShared.DEFAULT_SETTINGS, ...(changes.settings.newValue || {}) };
    sendHookCollecting();
    syncAuto();
    applyRefreshToggle(prevEnabled, prevArmed);
    renderPanel();
  }
  /* 「已刷 N 次」是独立存储键：background 单点累加后写回，这里同步显示 */
  if (changes.refreshCount) {
    refreshCount = Number(changes.refreshCount.newValue) || 0;
    updateRefreshText();
  }
});

let lastHref = location.href;
let lastDayYmd = ymdOf(Date.now());
const spaTimer = setInterval(() => {
  if (!isExtAlive()) {
    clearInterval(spaTimer);
    return;
  }
  /* 页面若开着过夜：只做一次日期比较，跨零点就问 background 重算，
   * 不新增定时器。跨天后历史按当天过滤，主行自动回到「上一次重置：—」。 */
  const todayYmd = ymdOf(Date.now());
  if (todayYmd !== lastDayYmd) {
    lastDayYmd = todayYmd;
    refreshDayStart();
  }
  if (location.href === lastHref) return;
  lastHref = location.href;
  cachedScrollRoot = null;
  stuckTicks = 0;
  if (!document.getElementById(PANEL_ID)) ensurePanel();
  if (settings.collecting) {
    scanRenderData();
    scanCurrentPlayerDom();
    scanSearchDom();
    harvestRelatedSearches();
    applyLatestSort();
  }
}, 800);

function boot() {
  loadState().then(() => loadSwitchLock()).then(() => {
    const mount = () => {
      ensurePanel();
      renderPanel();
      refreshDayStart();
      sendHookCollecting();
      syncAuto();
      /* 随机刷新：刷新后续采 + 续表（把刷新前存下的计时状态接着跑） */
      if (settings.refreshEnabled && settings.refreshArmed) startRefresh(false);
      guardBoot();
      if (settings.collecting) {
        scanRenderData();
        scanCurrentPlayerDom();
        scanSearchDom();
        harvestRelatedSearches();
        applyLatestSort();
      }
    };
    if (document.body) mount();
    else document.addEventListener("DOMContentLoaded", mount, { once: true });
    /* 整页 502 的导航计时条目可能在 mount 之后才就绪，加载完成再补一次判定 */
    window.addEventListener("load", () => { guardReportFullPageFailure(); }, { once: true });
    const patrolTimer = setInterval(() => {
      if (!isExtAlive()) {
        clearInterval(patrolTimer);
        return;
      }
      if (!settings.collecting) return;
      const visibleText = readVisibleText();
      scanCurrentPlayerDom();
      scanSearchDom();
      harvestRelatedSearches();
      applyLatestSort();
      /* 判定优先级（方案 v1.3 第 3.4 节）：限流 > 抖动 > 本地。
       * 同一轮只让第一个命中的检测上报，命中即收口，避免一次故障记成两笔。 */
      if (detectSearchLimit(visibleText)) return;
      if (detectCaptcha(visibleText)) return;
      if (detectLogout(visibleText)) return;
      if (detectServiceError(visibleText)) return;
      detectNonCollectChannel();
      detectIdlePrompt();
      detectNoResponse();
    }, 2500);
  });
}

boot();
