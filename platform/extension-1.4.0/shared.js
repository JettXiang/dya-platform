/* =========================================================================
 * shared.js —— P2：content / popup 共用的纯值与纯函数（唯一来源）
 * 批次 ① 起对外仅暴露 DyShared.*（顶层不再挂任何符号）。
 *
 * 本批只做「抽重复定义」，不含任何逻辑/文案改动。
 *
 * 加载顺序必须在前：
 *   - manifest.json content_scripts[1].js:
 *       ["shared.js", "content/adapter.js", "content/parse.js", "content/content.js"]（该条 world = ISOLATED）
 *   - popup/popup.html: <script src="../shared.js"></script> 在 popup.js 之前
 *
 * 护栏：本文件不注入 MAIN world。
 *   content_scripts[0] 的 world = MAIN，其 js 只列 content/page-hook.js；
 *   shared.js 仅出现在 content_scripts[1]（ISOLATED），
 *   因此这里的 DyShared 不会落到页面主世界，也不会与页面脚本互相覆盖。
 *
 * background.js 本批不引用本文件，理由是量化的：
 *   - 该 Service Worker 内 importScripts 现为 0 处，本批不新增；
 *   - onInstalled 现在只写 6 个字段
 *     （collecting / autoBrowse / intervalMs / requireMix / requireAi / collapsed）；
 *   - 而本文件的 DEFAULT_SETTINGS 有 15 个字段，改用它会多写 9 个键，
 *     新装用户的 storage 形状随之变化 = 行为变化，故留到后续批次处理。
 *
 * 不在本批迁移（理由内联，不引用外部文档）：
 *   - downloadText：content 延迟 1 秒释放 ObjectURL，popup 立即释放；
 *     两实现行为不同，强行合并会改变一端行为
 *   - content 的 updateRefreshText / refreshRange / nextRefreshDelay：
 *     仅 content 使用，且涉及文案，属行为变化
 *   - SEC_UID_RE / AI_DECLARE_TEXTS：仍留在 content/parse.js。
 *     测试口径分开记：parse 回归（78 条）加载 content/parse.js；
 *     shared 回归（126 条；带 --base 做 S7 差分时 150 条）加载本文件。
 * ========================================================================= */

var DyShared = (function () {

/* ================= 设置默认值（唯一来源，content 超集） ================= */

/* content-only: popup 不渲染、不生成控件，也不得单独写入或补默认值。
 * 依据：popup 整包写回 settings 时读侧是 {...DEFAULT_SETTINGS, ...(data.settings||{})},
 * loaded 的 spread 在后，不会覆盖 content 已写入的真值；但若 popup 的默认表缺这几项，
 * 新装时 popup 先写一次就会把不完整的 settings 落盘（本版修正的正是这个隐患）。 */
var DEFAULT_SETTINGS = {
  collecting: false,
  autoBrowse: false,
  intervalMs: 4000,
  requireMix: true,
  requireAi: true,
  autoSwitchChannel: false,
  autoSwitchSearch: false,
  autoSortLatest: true,          /* content-only */
  collapsed: false,
  viewNewOnly: false,            /* content-only */
  switchSearchMinMs: 300000,     /* content-only */
  /* v1.3.2 随机区间定时刷新 */
  refreshEnabled: false,   // 勾选「随机刷新（仅采集页生效）」
  refreshMinSec: 180,      // 区间下限（秒）
  refreshMaxSec: 480,      // 区间上限（秒）
  refreshArmed: false      // 计时是否在跑（持久化：刷新后续采 + 续表）
  /* 注：「已刷 N 次」不在 settings 里，用独立存储键 refreshCount */
};

/* v1.3.2 随机刷新区间的输入限值（秒） */
var REFRESH_MIN_LIMIT = 30;
var REFRESH_MAX_LIMIT = 3600;

/* 单值规范化：非法回落默认、越界夹到 30~3600（不做 min/max 交换，交换由 refreshRange() 兜底） */
function clampRefreshSec(raw, fallback) {
  const n = Math.round(Number(raw));
  if (!isFinite(n) || n <= 0) return fallback;
  return Math.min(REFRESH_MAX_LIMIT, Math.max(REFRESH_MIN_LIMIT, n));
}

/* 随机刷新状态文案（popup 版原样搬入；content 的 updateRefreshText 有额外分支，本批不动） */
function refreshStatusText(s, count) {
  if (!s.refreshEnabled) return "随机刷新：未启用";
  if (!s.refreshArmed) return "随机刷新：已停止";
  return "随机刷新：运行中 · 已自动刷 " + (count || 0) + " 次（倒计时见页面浮层）";
}

/* ================= 导出（CSV / 标题脱敏） ================= */

var DAY_MS = 24 * 3600 * 1000;

function fmtTs(sec) {
  if (!sec) return "";
  const d = new Date(sec * 1000);
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

function toCsv(rows) {
  const header = ["昵称", "sec_uid", "抖音号", "uid", "合集名", "合集ID", "示例链接", "视频数"];
  const lines = [header.join(",")];
  for (const a of rows) {
    const row = [
      a.nickname,
      a.secUid,
      a.uniqueId,
      a.uid,
      a.mixName,
      a.mixId,
      a.sampleUrl,
      a.videoCount || 1
    ].map((v) => `"${String(v == null ? "" : v).replace(/"/g, '""')}"`);
    lines.push(row.join(","));
  }
  return "\uFEFF" + lines.join("\r\n");
}

/* 「上新高峰时段」采样导出。行结构：
 * [awemeId, createTimeSec, secUid, mixId, hasMix, hasAiDeclare, seenAtSec]
 * 与 CSV 的 8 列完全无关，只用于时段分析。 */
function toPublishCsv(rows) {
  const header = ["采集时刻", "发布时刻", "发布小时", "发布星期", "sec_uid", "合集ID", "有合集", "有AI声明", "awemeId"];
  const lines = [header.join(",")];
  for (const r of rows) {
    if (!r || !r[1]) continue;
    const t = Number(r[1]);
    const d = new Date(t * 1000);
    const cells = [
      Number(r[6]) ? fmtTs(Number(r[6])) : "",
      fmtTs(t),
      String(d.getHours()),
      String(d.getDay()),
      r[2] || "",
      r[3] || "",
      r[4] ? "1" : "0",
      r[5] ? "1" : "0",
      r[0] || ""
    ].map((v) => `"${String(v == null ? "" : v).replace(/"/g, '""')}"`);
    lines.push(cells.join(","));
  }
  return "\uFEFF" + lines.join("\r\n");
}

/* ================= 导出范围选择器（纯筛选，不读全局状态） =================
 * list：作者数组（必须是数组，不接受 map —— content 的 authorList() 有记忆化，
 *       这里若收 map 再做 Object.keys 会让缓存失效）
 * range：today / 7d / 30d / all
 * slot：none / 1h / 3h / work / custom（仅 range=today 时生效，与原实现 else-if 链一致）
 * dayStartTs：当日起点（毫秒）
 * now：当前时间戳（由调用方传入，保证纯函数可测）
 * custom：slot=custom 时使用，{ from: "HH:mm", to: "HH:mm" }；可选，不传时行为与旧版完全一致
 */
function selectAuthorsBy(list, range, slot, dayStartTs, now, custom) {
  if (range === "all") return list;
  let start = dayStartTs;
  let end = Infinity;
  if (range === "7d") {
    start = now - 7 * DAY_MS;
  } else if (range === "30d") {
    start = now - 30 * DAY_MS;
  } else if (slot === "1h") {
    start = Math.max(start, now - 3600 * 1000);
  } else if (slot === "3h") {
    start = Math.max(start, now - 3 * 3600 * 1000);
  } else if (slot === "work") {
    const s = new Date(now);
    s.setHours(9, 30, 0, 0);
    const e = new Date(now);
    e.setHours(18, 0, 0, 0);
    start = Math.max(start, s.getTime());
    end = Math.min(e.getTime(), now);
  } else if (slot === "custom") {
    /* custom = { from: "HH:mm", to: "HH:mm" }
     * UI 已用 disabled 保证 to >= from，这里是防御：万一 DOM 被改或参数被伪造，
     * 直接按「不限」算而不是抛错 —— 纯函数保持不抛。 */
    const P = /^(\d{2}):(\d{2})$/;
    const mf = P.exec(String((custom && custom.from) || ""));
    const mt = P.exec(String((custom && custom.to) || ""));
    if (mf && mt) {
      const s = new Date(now);
      s.setHours(Number(mf[1]), Number(mf[2]), 0, 0);
      const e = new Date(now);
      e.setHours(Number(mt[1]), Number(mt[2]), 0, 0);
      if (s.getTime() <= e.getTime()) {
        start = Math.max(start, s.getTime());
        end = Math.min(e.getTime(), now);
      }
    }
  }
  return list.filter((a) => {
    const ts = Number(a.firstSeen);
    return ts && ts >= start && ts <= end;
  });
}

/* ================= 上传前比对（P8，纯函数，不读全局状态） =================
 * 本时段总数 T 与本次范围命中数 M 的差值达标时才需要弹窗提示，避免每次上传都打扰。
 * total/hit 任意非法值按 0 处理；hit > total 时 diff 记 0（不出现负数）。
 */
var UPLOAD_GAP_THRESHOLD = 10;

function judgeUploadGap(total, hit, threshold) {
  const t = Math.max(0, Number(total) || 0);
  const m = Math.max(0, Number(hit) || 0);
  const th = typeof threshold === "number" && isFinite(threshold) ? threshold : UPLOAD_GAP_THRESHOLD;
  const d = t > m ? t - m : 0;
  return { total: t, hit: m, diff: d, needPrompt: d >= th };
}

  return {
    DEFAULT_SETTINGS: DEFAULT_SETTINGS,
    REFRESH_MIN_LIMIT: REFRESH_MIN_LIMIT,
    REFRESH_MAX_LIMIT: REFRESH_MAX_LIMIT,
    clampRefreshSec: clampRefreshSec,
    refreshStatusText: refreshStatusText,
    DAY_MS: DAY_MS,
    fmtTs: fmtTs,
    toCsv: toCsv,
    toPublishCsv: toPublishCsv,
    selectAuthorsBy: selectAuthorsBy,
    UPLOAD_GAP_THRESHOLD: UPLOAD_GAP_THRESHOLD,
    judgeUploadGap: judgeUploadGap
  };
})();
