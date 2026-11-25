/* DEFAULT_SETTINGS / REFRESH_MIN_LIMIT / REFRESH_MAX_LIMIT / clampRefreshSec /
 * refreshStatusText 统一来自 shared.js（popup.html 里 shared.js 排在 popup.js 之前加载） */

function rfMsg(text, cls) {
  const el = document.getElementById("rfMsg");
  if (!el) return;
  el.textContent = text || "";
  el.className = "rf-msg" + (cls ? " " + cls : "");
}

function authorList(map) {
  return Object.keys(map || {})
    .map((k) => map[k])
    .sort((a, b) => (b.lastSeen || 0) - (a.lastSeen || 0));
}

async function load() {
  const data = await chrome.storage.local.get(["settings", "authors", "stats", "refreshCount"]);
  return {
    settings: { ...DyShared.DEFAULT_SETTINGS, ...(data.settings || {}) },
    authors: authorList(data.authors || {}),
    stats: data.stats || { scanned: 0, matched: 0 },
    refreshCount: Number(data.refreshCount) || 0
  };
}

function render(state) {
  document.getElementById("count").textContent = String(state.authors.length);
  document.getElementById("scanned").textContent = String(state.stats.scanned || 0);
  document.getElementById("collecting").textContent = state.settings.collecting ? "采集中" : "未开始";
  document.getElementById("toggle").textContent = state.settings.collecting ? "停止采集" : "开始采集";
  document.getElementById("auto").textContent = state.settings.autoBrowse ? "停止自动下滑" : "自动下滑";
  document.getElementById("requireMix").checked = state.settings.requireMix !== false;
  document.getElementById("requireAi").checked = state.settings.requireAi !== false;
  document.getElementById("intervalMs").value = String(state.settings.intervalMs || 4000);
  const rfOn = document.getElementById("refreshEnabled");
  if (rfOn) rfOn.checked = !!state.settings.refreshEnabled;
  const rfMin = document.getElementById("refreshMinSec");
  const rfMax = document.getElementById("refreshMaxSec");
  /* 按存储原值回填（不排序），避免填第二个框时第一个被顶回去 */
  /* 正在输入的框不覆写，避免打字被吃掉 */
  if (rfMin && document.activeElement !== rfMin) rfMin.value = String(DyShared.clampRefreshSec(state.settings.refreshMinSec, 180));
  if (rfMax && document.activeElement !== rfMax) rfMax.value = String(DyShared.clampRefreshSec(state.settings.refreshMaxSec, 480));
  document.getElementById("refreshStart").textContent = state.settings.refreshArmed ? "停止计时" : "开始计时";
  document.getElementById("refreshText").textContent = DyShared.refreshStatusText(state.settings, state.refreshCount);
  document.getElementById("autoSwitchChannel").checked = !!state.settings.autoSwitchChannel;
  document.getElementById("autoSwitchSearch").checked = !!state.settings.autoSwitchSearch;

  const list = document.getElementById("list");
  if (!state.authors.length) {
    list.innerHTML = '<div class="empty">还没有作者。打开精选或搜索页后开始采集。</div>';
    return;
  }
  list.innerHTML = state.authors
    .map((a) => {
      const name = escapeHtml(a.nickname || "(无昵称)");
      const mix = escapeHtml(a.mixName || "合集");
      const id = escapeHtml(a.secUid);
      const href = "https://www.douyin.com/user/" + encodeURIComponent(a.secUid);
      return `<div class="item">
        <div class="name">${name}</div>
        <div class="meta">${mix} · ${a.videoCount || 1} 条</div>
        <a class="id" href="${href}" target="_blank" rel="noreferrer">${id}</a>
      </div>`;
    })
    .join("");
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

async function patchSettings(partial) {
  const data = await chrome.storage.local.get("settings");
  const settings = { ...DyShared.DEFAULT_SETTINGS, ...(data.settings || {}), ...partial };
  await chrome.storage.local.set({ settings });
  await pingTabs({ type: "setSettings", settings });
}

async function pingTabs(message) {
  const tabs = await chrome.tabs.query({ url: ["*://*.douyin.com/*"] });
  await Promise.all(
    tabs.map((tab) =>
      chrome.tabs.sendMessage(tab.id, message).catch(() => null)
    )
  );
}

function download(filename, text, mime) {
  /* 另存为：优先走 chrome.downloads（会弹系统「另存为」对话框）。
   * 用 data: URL 而不是 blob: —— blob URL 绑在 popup 文档上，
   * 保存对话框一抢焦点 popup 就关，blob 随即失效。 */
  if (chrome.downloads && chrome.downloads.download) {
    try {
      chrome.downloads.download({
        url: "data:" + mime + ";charset=utf-8," + encodeURIComponent(text),
        filename: filename,
        saveAs: true
      });
      return;
    } catch (e) {}
  }
  const blob = new Blob([text], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

/* toCsv / fmtTs / toPublishCsv 来自 shared.js
 * （本文件的 download 与 content 的 downloadText 实现不同，刻意各自保留，勿合并） */

/* ================= 导出范围选择器 ================= */
const picker = { open: false, act: "copy", range: "today", slot: "none", from: "", to: "" };
let dayStartTs = 0;
let dayStartHistOpen = false;

function pad2(n) { return n < 10 ? "0" + n : String(n); }

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

/* 按当前选择器状态筛作者：逻辑在 shared.js 的 selectAuthorsBy（纯函数，now 由调用方传入） */
function selectAuthors(authors) {
  return DyShared.selectAuthorsBy(authors, picker.range, picker.slot, dayStartTs || todayStartTs(), Date.now(),
    { from: picker.from, to: picker.to });
}

function pkMsg(text, cls) {
  const el = document.getElementById("pkMsg");
  el.textContent = text || "";
  el.className = "pk-msg" + (cls ? " " + cls : "");
}

async function refreshPicker() {
  const box = document.getElementById("picker");
  box.hidden = !picker.open;
  if (!picker.open) return;
  const st = await load();
  document.querySelectorAll("#pkRange button").forEach((b) => {
    b.classList.toggle("on", b.getAttribute("data-range") === picker.range);
  });
  document.querySelectorAll("#pkSlot button").forEach((b) => {
    b.classList.toggle("on", b.getAttribute("data-slot") === picker.slot);
  });
  document.getElementById("pkSlotWrap").hidden = picker.range !== "today";
  const custom = document.getElementById("pkCustom");
  if (custom) {
    custom.hidden = !(picker.range === "today" && picker.slot === "custom");
    if (!custom.hidden) {
      fillTimeSelects();
      syncCustomLimits();
      syncCustomSelects();
    }
  }
  document.querySelectorAll(".pk-actions button").forEach((b) => {
    b.classList.toggle("on", b.getAttribute("data-do") === picker.act);
  });
  document.getElementById("pkCount").textContent = String(selectAuthors(st.authors).length);
}

/* 时 00–23、分 00–59；只填一次，避免每次刷新重建 DOM */
function fillTimeSelects() {
  const hours = [];
  for (let i = 0; i < 24; i++) hours.push(pad2(i));
  const mins = [];
  for (let i = 0; i < 60; i++) mins.push(pad2(i));
  [["pkFromH", hours], ["pkToH", hours], ["pkFromM", mins], ["pkToM", mins]].forEach((pair) => {
    const el = document.getElementById(pair[0]);
    if (!el || el.options.length) return;
    pair[1].forEach((v) => {
      const o = document.createElement("option");
      o.value = v;
      o.textContent = v;
      el.appendChild(o);
    });
  });
}

/* 结束不得早于开始：结束的「时」「分」按开始值置灰，非法值根本产生不了 */
function syncCustomLimits() {
  if (!picker.from || !picker.to) return;
  const fh = Number(picker.from.slice(0, 2));
  const fm = Number(picker.from.slice(3, 5));
  const th = Number(picker.to.slice(0, 2));
  const hourSel = document.getElementById("pkToH");
  const minSel = document.getElementById("pkToM");
  if (hourSel) Array.from(hourSel.options).forEach((o) => { o.disabled = Number(o.value) < fh; });
  if (minSel) Array.from(minSel.options).forEach((o) => { o.disabled = th === fh && Number(o.value) < fm; });
}

function syncCustomSelects() {
  if (!picker.from || !picker.to) return;
  const set = (id, v) => {
    const el = document.getElementById(id);
    if (el && el.value !== v) el.value = v;
  };
  set("pkFromH", picker.from.slice(0, 2));
  set("pkFromM", picker.from.slice(3, 5));
  set("pkToH", picker.to.slice(0, 2));
  set("pkToM", picker.to.slice(3, 5));
}

/* 改开始时若结束变非法，自动抬到「开始 + 1 分钟」 */
function clampCustomEnd() {
  const f = picker.from;
  const t = picker.to;
  if (!f || !t) return;
  if (t >= f) return;
  const h = Number(f.slice(0, 2));
  const m = Number(f.slice(3, 5));
  const total = Math.min(h * 60 + m + 1, 23 * 60 + 59);
  picker.to = pad2(Math.floor(total / 60)) + ":" + pad2(total % 60);
}

function applyCustomTimeChange(id, value) {
  const v = String(value || "");
  if (id === "pkFromH") picker.from = v + ":" + (picker.from || "00:00").slice(3, 5);
  else if (id === "pkFromM") picker.from = (picker.from || "00:00").slice(0, 2) + ":" + v;
  else if (id === "pkToH") picker.to = v + ":" + (picker.to || "23:59").slice(3, 5);
  else picker.to = (picker.to || "23:59").slice(0, 2) + ":" + v;
  clampCustomEnd();
  syncCustomLimits();
  syncCustomSelects();
  hideGapBox();
}

async function openPicker(act) {
  picker.act = act;
  picker.open = true;
  const st = await load();
  document.getElementById("uploaderId").value = st.settings.uploaderId || "";
  pkMsg("");
  refreshPicker();
  refreshDayStart().then(refreshPicker);
}

async function runExport(act) {
  picker.act = act;
  const st = await load();
  const rows = selectAuthors(st.authors);
  const ids = rows.map((a) => a.secUid).filter(Boolean);
  if (!ids.length) {
    pkMsg("当前范围没有命中任何作者。", "err");
    await refreshPicker();
    return;
  }
  /* R2-11：今日范围 + 起点未连接（dayStartTs 为 0，说明还没拿到真起点）时，
     导出照做、不阻断，只在成功文案里如实标注本次用的是本地零点边界。 */
  const startNote =
    picker.range === "today" && dayStartTs === 0 ? "（今日范围 = 本地零点起，起点未连接）" : "";
  if (act === "copy") {
    try {
      await navigator.clipboard.writeText(ids.join("\n"));
      pkMsg("已复制 " + ids.length + " 条 sec_uid。", "ok");
    } catch (e) {
      pkMsg("复制失败，请重试。", "err");
    }
  } else if (act === "txt") {
    download("douyin-ai-mix-authors.txt", ids.join("\n"), "text/plain");
    pkMsg("已导出 TXT（" + ids.length + " 条）。" + startNote, "ok");
  } else if (act === "csv") {
    download("douyin-ai-mix-authors.csv", DyShared.toCsv(rows), "text/csv");
    pkMsg("已导出 CSV（" + rows.length + " 条）。" + startNote, "ok");
  } else if (act === "json") {
    download(
      "douyin-ai-mix-authors.json",
      JSON.stringify({ exportedAt: new Date().toISOString(), count: rows.length, authors: rows }, null, 2),
      "application/json"
    );
    pkMsg("已导出 JSON（" + rows.length + " 条）。" + startNote, "ok");
  }
  await refreshPicker();
}

async function doUpload() {
  const st = await load();
  const uploaderId = String(document.getElementById("uploaderId").value || "").trim();
  if (!uploaderId) {
    pkMsg("请先填写上传人 ID。", "err");
    return false;
  }
  /* P8 比对：本时段总数（实时）vs 当前范围命中。命中 0 时绝不上传、绝不重置。 */
  /* 取不到条数时不能当 0 —— 否则会误报「还没有采到任何作者」，
   * 也会静默跳过 P8 的部分上传提示。总数未知就不上传。
   * SW 刚唤醒/扩展刚重载时 sendMessage 可能 reject，重试 1 次再判死。 */
  let info = null;
  for (let i = 0; i < 2; i++) {
    try {
      info = await chrome.runtime.sendMessage({ type: "dayStartInfo" });
    } catch (e) {
      info = null;
    }
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
    return false;
  }
  const total = Number(info.count) || 0;
  const rows = selectAuthors(st.authors);
  const ids = Array.from(new Set(rows.map((a) => a.secUid).filter(Boolean)));
  const gap = DyShared.judgeUploadGap(total, ids.length);
  if (gap.hit === 0) {
    hideGapBox();
    if (gap.total === 0) {
      pkMsg("本时段还没有采到任何作者，无可上传内容。", "err");
    } else {
      pkMsg("当前范围 0 条，本时段共 " + gap.total + " 条。请调整时段，或点「一起上传（含全部）」。", "err");
      showGapBox("当前范围 0 条，本时段共 " + gap.total + " 条不在此范围内。", false);
    }
    return false;
  }
  if (gap.needPrompt) {
    showGapBox("本时段共 " + gap.total + " 条，本次范围只含 " + gap.hit + " 条，还有 " + gap.diff + " 条不在范围内。", true);
    return false;
  }
  /* 差值未达阈值：直接提交，保持原来的手感 */
  return submitUpload(ids, uploaderId, false);
}

async function submitUpload(ids, uploaderId, needConfirm) {
  const finalIds = Array.from(new Set(ids.filter(Boolean)));
  if (!finalIds.length) {
    pkMsg("当前范围没有可上传的 sec_uid。", "err");
    return false;
  }
  if (needConfirm && !confirm("确认上传 " + finalIds.length + " 条到 43.165.183.142？")) {
    pkMsg("已取消上传。", "err");
    return false;
  }
  await patchSettings({ uploaderId: uploaderId });
  pkMsg("上传中… 共 " + finalIds.length + " 条，请保持浏览器打开。");
  /* R2-10②：327 是裸 await，SW 未就绪/通道断时直接 reject。
   * 这里必须自己接住，否则 326 写的「上传中…」会一直挂着。 */
  try {
    const r = await chrome.runtime.sendMessage({ type: "uploadIds", ids: finalIds, uploaderId: uploaderId });
    if (r && r.ok) {
      pkMsg("上传成功：本时段共 " + (r.total != null ? r.total : finalIds.length) + " 条，本次提交 " + r.count + " 条（新增 " + r.inserted + "，服务端已存在 " + r.dup + "）。", "ok");
      return true;
    }
    /* R2-10③：兜底复用 854 语义，不再用「上传失败。」冒充「没入库」 */
    pkMsg((r && r.message) || "提交结果尚未确认，请保持原上传人 ID 和文件内容重试。", "err");
    return false;
  } catch (e) {
    pkMsg("提交结果尚未确认，请保持原上传人 ID 和文件内容重试。", "err");
    return false;
  }
}

function showGapBox(text, curButton) {
  const box = document.getElementById("pkGap");
  if (!box) return;
  const t = document.getElementById("pkGapText");
  if (t) t.textContent = text || "";
  const cur = box.querySelector('[data-gap="cur"]');
  if (cur) cur.hidden = !curButton;
  box.hidden = false;
}

function hideGapBox() {
  const box = document.getElementById("pkGap");
  if (box) box.hidden = true;
}

async function onGapChoice(choice) {
  if (choice === "cancel") {
    hideGapBox();
    pkMsg("已取消上传。", "err");
    return;
  }
  const uploaderId = String(document.getElementById("uploaderId").value || "").trim();
  if (choice === "all") {
    picker.range = "today";
    picker.slot = "none";
    hideGapBox();
    await refreshPicker();
  } else {
    hideGapBox();
  }
  const st = await load();
  const ids = Array.from(new Set(selectAuthors(st.authors).map((a) => a.secUid).filter(Boolean)));
  await submitUpload(ids, uploaderId, true);
}

async function onPickerClick(el) {
  if (el.id === "picker-close") {
    picker.open = false;
    await refreshPicker();
    return;
  }
  const range = el.getAttribute("data-range");
  const slot = el.getAttribute("data-slot");
  const doAct = el.getAttribute("data-do");
  if (range) {
    picker.range = range;
    if (range !== "today") picker.slot = "none";
    hideGapBox();
    pkMsg("");
    await refreshPicker();
    return;
  }
  if (slot) {
    picker.slot = slot;
    /* 切到自定义时初始化默认值：当日起点 → 此刻 */
    if (slot === "custom" && !picker.from) {
      picker.from = hhmmOf(dayStartTs || todayStartTs());
      picker.to = hhmmOf(Date.now());
    }
    hideGapBox();
    pkMsg("");
    await refreshPicker();
    return;
  }
  if (doAct === "upload") {
    await doUpload();
    return;
  }
  if (doAct) await runExport(doAct);
}

async function refreshDayStart() {
  try {
    const r = await chrome.runtime.sendMessage({ type: "dayStartInfo" });
    if (r && r.ok) {
      dayStartTs = r.start;
      document.getElementById("dayStartText").textContent =
        DyShared.fmtTs(r.start / 1000) + "（今日 " + r.count + " 个）";
      const lastEl = document.getElementById("dayStartLast");
      if (lastEl) {
        lastEl.textContent = r.last && r.last.ts
          ? "上一次重置：" + DyShared.fmtTs(r.last.ts / 1000) + "（共 " + (Number(r.last.count) || 0) + " 个）"
          : "上一次重置：—";
      }
      renderDayStartHistory(r.history);
      return;
    }
  } catch (e) {}
  /* R2-9①：取数失败给明确失效标记，不再渲染裸 00:00:00 冒充真实起点。
   * R2-9②：也不把本地零点写进 dayStartTs —— 那个变量只放真值；
   *         「今日」的筛选边界由 selectAuthors 用 todayStartTs() 兜底。 */
  dayStartTs = 0;
  document.getElementById("dayStartText").textContent = "未连接";
  const lastEl = document.getElementById("dayStartLast");
  if (lastEl) lastEl.textContent = "上一次重置：—";
  renderDayStartHistory([]);
}

function renderDayStartHistory(hist) {
  const box = document.getElementById("dayStartHist");
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
  const toggle = document.querySelector('[data-act="hist-toggle"]');
  if (toggle) toggle.setAttribute("aria-expanded", dayStartHistOpen ? "true" : "false");
}

function toggleDayStartHist() {
  dayStartHistOpen = !dayStartHistOpen;
  const box = document.getElementById("dayStartHist");
  const toggle = document.querySelector('[data-act="hist-toggle"]');
  if (box) box.hidden = !dayStartHistOpen;
  if (toggle) toggle.setAttribute("aria-expanded", dayStartHistOpen ? "true" : "false");
}

document.addEventListener("click", async (e) => {
  /* P8 比对提示块的三个按钮优先于通用 data-act 处理 */
  const gapBtn = e.target.closest("[data-gap]");
  if (gapBtn) {
    await onGapChoice(gapBtn.getAttribute("data-gap"));
    return;
  }
  const pkb = e.target.closest("[data-range], [data-slot], [data-do], #picker-close");
  if (pkb) {
    await onPickerClick(pkb);
    return;
  }

  const btn = e.target.closest("[data-act]");
  if (!btn) return;
  const act = btn.getAttribute("data-act");
  const state = await load();
  if (act === "toggle") {
    const next = !state.settings.collecting;
    const patch = {
      collecting: next,
      autoBrowse: next ? state.settings.autoBrowse : false
    };
    /* 与浮层联动一致：开采集且已启用随机刷新 → 起表；停采集 → 停表 */
    if (next && state.settings.refreshEnabled) patch.refreshArmed = true;
    if (!next) patch.refreshArmed = false;
    await patchSettings(patch);
  } else if (act === "refresh-start") {
    if (state.settings.refreshArmed) {
      await patchSettings({ refreshArmed: false });
      rfMsg("已停止随机刷新计时。");
    } else if (!state.settings.refreshEnabled) {
      rfMsg("请先勾选「随机刷新（仅采集页生效）」。", "err");
    } else {
      await patchSettings({ refreshArmed: true });
      rfMsg(
        state.settings.collecting
          ? "已开始随机刷新计时。"
          : "已开始计时；当前未采集，刷新后不会自动续采。",
        "ok"
      );
    }
  } else if (act === "refresh-now") {
    /* 遥控端只能对「当前活动标签页」下手，不会打到其它窗口/应用 */
    const tabs = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
    const tab = tabs && tabs[0];
    if (!tab || !/^https?:\/\/([^/]*\.)?douyin\.com\//i.test(tab.url || "")) {
      rfMsg("当前标签页不是抖音页，未刷新。", "err");
    } else {
      const r = await chrome.tabs.sendMessage(tab.id, { type: "refreshNow" }).catch(() => null);
      if (r && r.ok) rfMsg("已刷新当前页，等待自动续采。", "ok");
      else rfMsg((r && r.reason) || "刷新失败（页面未注入采集脚本？）。", "err");
    }
  } else if (act === "auto") {
    const next = !state.settings.autoBrowse;
    await patchSettings({ autoBrowse: next, collecting: next ? true : state.settings.collecting });
  } else if (act === "open") {
    await chrome.tabs.create({ url: "https://www.douyin.com/jingxuan" });
  } else if (act === "backend") {
    await chrome.tabs.create({ url: chrome.runtime.getURL("dyaccount_1.1.2plus.html") });
  } else if (act === "guard") {
    await chrome.tabs.create({ url: chrome.runtime.getURL("guard.html") });
  } else if (act === "copy" || act === "txt" || act === "csv" || act === "json") {
    /* 导出统一先选范围，再在浮层里执行 */
    await openPicker(act);
    return;
  } else if (act === "plog") {
    const data = await chrome.storage.local.get("publishLog");
    const rows = Array.isArray(data.publishLog) ? data.publishLog : [];
    download("douyin-publish-times.csv", DyShared.toPublishCsv(rows), "text/csv");
  } else if (act === "clear") {
    if (!confirm("清空已采集作者？")) return;
    await chrome.storage.local.set({ authors: {}, stats: { scanned: state.stats.scanned || 0, matched: 0 } });
    /* 「已自动刷 N 次」与采集批次同口径：清空列表时一并清零 */
    await chrome.storage.local.remove("refreshCount");
    await pingTabs({ type: "clear" });
  } else if (act === "end-today") {
    if (!confirm("结束今天？\n\n将同时：① 清空重置历史 ② 起点重置为此刻 ③ 清空已采集列表 ④ 停止采集。\n（已上传的数据不受影响）")) return;
    await chrome.runtime.sendMessage({ type: "endToday" });
    await refreshDayStart();
    await refreshPicker();
    return;
  } else if (act === "hist-toggle") {
    toggleDayStartHist();
    return;
  }
  render(await load());
});

document.addEventListener("change", async (e) => {
  const el = e.target;
  /* 自定义时段的四个下拉：改完重算预览，不写 settings */
  if (el.id === "pkFromH" || el.id === "pkFromM" || el.id === "pkToH" || el.id === "pkToM") {
    applyCustomTimeChange(el.id, el.value);
    await refreshPicker();
    return;
  }
  if (el.id === "requireMix") await patchSettings({ requireMix: el.checked });
  if (el.id === "requireAi") await patchSettings({ requireAi: el.checked });
  if (el.id === "intervalMs") await patchSettings({ intervalMs: Number(el.value) || 4000 });
  if (el.id === "autoSwitchChannel") await patchSettings({ autoSwitchChannel: el.checked });
  if (el.id === "autoSwitchSearch") await patchSettings({ autoSwitchSearch: el.checked });
  if (el.id === "refreshEnabled") {
    const st = await load();
    if (el.checked) {
      /* 已在采集才起表；未采集只记启用，等点「开始采集」再起表 */
      await patchSettings({ refreshEnabled: true, refreshArmed: !!st.settings.collecting });
    } else {
      await patchSettings({ refreshEnabled: false, refreshArmed: false });
    }
  }
  if (el.id === "refreshMinSec" || el.id === "refreshMaxSec") {
    const raw = Math.round(Number(el.value));
    if (!isFinite(raw) || raw <= 0) {
      /* 填的不是有效数字：保留上一个有效值（由 render 回填） */
      render(await load());
      return;
    }
    const v = Math.min(DyShared.REFRESH_MAX_LIMIT, Math.max(DyShared.REFRESH_MIN_LIMIT, raw));
    /* 只存单值，不做 min>max 交换：让用户按任意顺序填完两个框 */
    await patchSettings(el.id === "refreshMinSec" ? { refreshMinSec: v } : { refreshMaxSec: v });
  }
  if (el.id === "uploaderId") await patchSettings({ uploaderId: String(el.value || "").trim() });
  render(await load());
});

/* 只对本面板用到的键重渲染。guardEvent / guardIndex / lastUpload 与面板无关，
 * 每来一次都 load() 全量作者并排序，长列表时会明显卡顿。
 * dayStart 单独处理：只刷起点与历史，不必重排列表。 */
const POPUP_RENDER_KEYS = ["settings", "authors", "stats", "refreshCount"];
/* 与 content.js 同口径：authors 写入很密，1.5 秒内只问一次「今日 N」。
 * 不合并的话，popup 开着采集时「今日 N」会冻结（render() 只重排列表，不碰起点行）。 */
let dayStartTimer = 0;
function scheduleDayStartRefresh() {
  if (dayStartTimer) return;
  dayStartTimer = setTimeout(() => {
    dayStartTimer = 0;
    refreshDayStart();
  }, 1500);
}
chrome.storage.onChanged.addListener(async (changes, area) => {
  if (area !== "local") return;
  if (changes.dayStart || changes.dayStartHistory) {
    await refreshDayStart();
    if (picker.open) await refreshPicker();
    return;
  }
  if (!POPUP_RENDER_KEYS.some((k) => !!changes[k])) return;
  render(await load());
  scheduleDayStartRefresh();
  if (picker.open) await refreshPicker();
});

(async () => {
  render(await load());
  await refreshDayStart();
  await refreshPicker();
})();

/* ================= 平台上报（1.3.9+，留作备用入口） ================= */
const pEl = (id) => document.getElementById(id);

function pMsg(text, bad) {
  const el = pEl("pMsg");
  if (!el) return;
  el.textContent = text || "";
  el.style.color = bad ? "#c0392b" : "";
}

function pSend(message) {
  return new Promise((resolve) => {
    chrome.runtime.sendMessage(message, (r) => {
      void chrome.runtime.lastError;
      resolve(r || { ok: false, message: "后台无响应" });
    });
  });
}

async function pRefresh() {
  const r = await pSend({ type: "platformStatus" });
  if (!r || r.ok === false) {
    pMsg((r && r.message) || "平台状态读取失败", true);
    return;
  }
  pEl("pEnabled").checked = !!r.enabled;
  if (!pEl("pAccount").value) pEl("pAccount").value = r.accountCode || "";
  const df = r.dayFinal ? `${r.dayFinal.date} ${r.dayFinal.complete ? "已完成" : r.dayFinal.state || ""}` : "未日终";
  const parts = [
    r.loggedIn ? "已登录" + (r.userCode ? " " + r.userCode : "") : "未登录",
    "待发 " + (r.pendingChunks || 0),
    "重试 " + (r.retrying || 0),
    "冲突 " + (r.conflict || 0),
    "阻塞 " + (r.blocked || 0),
    "已发 " + (r.sentChunks || 0),
    "日终 " + df
  ];
  pEl("pState").textContent = parts.join(" · ");
  if (r.lastError) pMsg(r.lastError, true);
}

async function pBindOnce() {
  if (pBindOnce.done) return;
  pBindOnce.done = true;

  pEl("pLogin").addEventListener("click", async () => {
    const code = pEl("pCode").value.trim();
    const password = pEl("pPass").value;
    if (!code || !password) {
      pMsg("先填工号和密码", true);
      return;
    }
    pMsg("登录中…");
    const r = await pSend({ type: "platformLogin", code, password });
    if (r && r.ok !== false) {
      const acc = pEl("pAccount").value.trim();
      if (acc) await pSend({ type: "platformSetAccount", accountCode: acc });
      pMsg("登录成功");
    } else {
      pMsg((r && r.message) || "登录失败", true);
    }
    pRefresh();
  });

  pEl("pEnabled").addEventListener("change", async () => {
    const on = pEl("pEnabled").checked;
    await pSend({ type: "platformSetEnabled", enabled: on });
    pRefresh();
  });

  pEl("pAccount").addEventListener("change", async () => {
    const accountCode = pEl("pAccount").value.trim();
    await pSend({ type: "platformSetAccount", accountCode });
    pRefresh();
  });

  const actions = [
    ["pFlush", { type: "platformFlush" }, "已上报"],
    ["pFinal", { type: "platformFinal" }, "已补发终片"],
    ["pDay", { type: "platformDayFinal" }, "日终完成"],
    ["pRetry", { type: "platformRetry" }, "已重试失败任务"],
    ["pVoid", { type: "platformRunVoid", reason: "扩展面板手动作废" }, "已作废本轮 run"],
    ["pDefer", { type: "platformDefer" }, "已暂缓"],
    ["pExport", { type: "platformExportTasks" }, "已导出失败任务"]
  ];
  actions.forEach(([id, message, okText]) => {
    pEl(id).addEventListener("click", async () => {
      pMsg("执行中…");
      const r = await pSend(message);
      if (r && r.ok !== false) {
        if (message.type === "platformExportTasks" && r.json) {
          download("platform-failed-tasks.json", r.json, "application/json");
        }
        pMsg(okText + (r.count !== undefined ? "（" + r.count + "）" : ""));
      } else {
        pMsg((r && r.message) || "执行失败", true);
      }
      pRefresh();
    });
  });

  pRefresh();
}

try {
  pBindOnce();
} catch (e) {}
