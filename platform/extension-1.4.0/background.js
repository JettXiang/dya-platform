/* =========================================================================
 * 抖音AI合集作者采集 · background（service worker）
 * v1.2.0 · 守卫机制 background 侧
 * 依据：《抖音采集任务-守卫机制改造方案 v1.3》附录 A.3 / F.1
 * ========================================================================= */

/* 1.3.9 平台上报模块（登录/配置、心跳、分片队列） */
importScripts("platform.js");
/* 1.4.0 采集遥控模块（心跳/画面回传/平台指令轮询） */
importScripts("collect-remote.js");

/* ================= B1 常量（F.1） ================= */
const GUARD_ALIVE_TIMEOUT_MS = 15000;   // 导航完成 → 等待 content guardAlive 的超时
const GUARD_PROBE_PERIOD_MIN = 0.5;     // alarms 探活周期（Chrome 120+；低版本自动降级为 1 分钟）
const GUARD_INDEX_LIMIT = 2000;         // guardIndex 保留上限
const GUARD_DEBOUNCE_MS = 1500;         // 常规更新防抖
const GUARD_DEDUPE_WINDOW_MS = 30 * 60 * 1000;
const GUARD_PING_REPLY_TIMEOUT_MS = 5000;
const GUARD_BACKOFF_MINUTES = [1, 2, 5, 10, 10];  // 退避序列（见 4.3）
const FULLPAGE_STABLE_MS = 60000;                 // 整页 502：页面恢复正常后需稳定观察多久才清零退避计数

const ALARM_PROBE = "guard-probe";
const ALARM_NAV_PREFIX = "guard-nav:";
const ALARM_FP_STABLE_PREFIX = "guard-fp-ok:";   // 整页 502 的「恢复稳定观察」闹钟
const DOUYIN_RE = /^https?:\/\/([^/]*\.)?douyin\.com\//i;

const STATE_NORMAL = "正常";
const STATE_OBSERVE = "暂停观察";
const STATE_BACKOFF = "退避重试";
const STATE_MANUAL = "待人工确认";

const NAV_FALLBACK_SOURCE = "nav-fallback";
const FULLPAGE_PREFIX = "guardFullPage:";   // local: 整页 502 的进行中记录 { eventId, attempt, faultAt, loadedAt }

/* ================= 当日累计 / 上传（P0）常量 ================= */
const ALARM_DAILY_REMIND = "daily-upload-remind";
const UPLOAD_ENDPOINT = "http://43.165.183.142/api/file";
const UPLOAD_MAX_LINES = 10000;             // 服务端硬上限
const UPLOAD_TIMEOUT_MS = 60000;
const REMIND_HOUR = 18;                     // 每天 18:00 提醒上传
const REMIND_MINUTE = 0;
const NOTIF_DAILY_REMIND = "daily-remind";
/* 1.3.5 起 18:10 的清空提醒并入 18:00 单条；常量保留仅为在 initGuard 里注销老设备已注册的 alarm */
const LEGACY_ALARM_CLEAR_REMIND = "daily-clear-remind";
const DAY_START_KEY = "dayStart";           // local: { ts, date, type, count } 当日起点
const DAY_START_HISTORY_KEY = "dayStartHistory";  // local: [{ ts, type, count }]，最多 5 条，只含当天
const DAY_START_HISTORY_MAX = 5;

/* ================= 内存登记 =================
 * SW 随时可能休眠重启，以下内存表在每次唤醒时按实际情况重建；
 * 真正需要跨重启保留的是 leader 状态（storage.session）与兜底窗口（storage.session）。
 */
const registeredTabs = new Set();   // 已登记（发过 guardAlive / applyLeader）的 tabId
let aliveTabs = new Set();          // 最近一次探活有回应的 tabId
const missCount = new Map();        // tabId -> 连续无回应次数
const navTimers = new Map();        // tabId -> setTimeout 句柄

/* ================= 通用小工具 ================= */
function pad2(n) { return n < 10 ? "0" + n : String(n); }

function formatTime(ts) {
  const d = new Date(ts);
  return d.getFullYear() + "-" + pad2(d.getMonth() + 1) + "-" + pad2(d.getDate()) +
    " " + pad2(d.getHours()) + ":" + pad2(d.getMinutes()) + ":" + pad2(d.getSeconds());
}

const URL_KEEP_PARAMS = ["kw"];
function sanitizeUrl(raw) {
  let s = String(raw || "");
  try {
    const u = new URL(s);
    const parts = [];
    u.searchParams.forEach((v, k) => {
      parts.push(encodeURIComponent(k) + "=" + (URL_KEEP_PARAMS.indexOf(k) !== -1 ? encodeURIComponent(v) : "***"));
    });
    s = u.origin + u.pathname + (parts.length ? "?" + parts.join("&") : "");
  } catch (e) {
    s = s.split("#")[0];
  }
  return s.slice(0, 500);
}

function setBadge(count) {
  const text = count > 0 ? String(count > 999 ? "999+" : count) : "";
  chrome.action.setBadgeBackgroundColor({ color: "#fe2c55" });
  chrome.action.setBadgeText({ text });
}

/* ================= 写入串行化（A.3 串行写入） =================
 * guardState / guardEvent / guardIndex 的读-改-写全部在这里排队，天然串行。
 */
let writeChain = Promise.resolve();
function enqueue(fn) {
  writeChain = writeChain.then(() => fn()).catch(() => {});
  return writeChain;
}

const debounceTimers = new Map();
function debouncedUpdate(key, fn) {
  if (debounceTimers.has(key)) clearTimeout(debounceTimers.get(key));
  debounceTimers.set(key, setTimeout(() => {
    debounceTimers.delete(key);
    enqueue(fn);
  }, GUARD_DEBOUNCE_MS));
}

/* ================= 事件记录（第五章：七字段 + 原始证据块） ================= */
function findDupEvent(index, fingerprint, ts) {
  const ids = Object.keys(index || {});
  for (let i = 0; i < ids.length; i++) {
    const it = index[ids[i]];
    if (!it || it.closed) continue;
    if (it.fingerprint === fingerprint && Math.abs(ts - (it.ts || 0)) <= GUARD_DEDUPE_WINDOW_MS) return ids[i];
  }
  return null;
}

async function createGuardEvent(ev) {
  const now = Date.now();
  const seqData = await chrome.storage.local.get("guardEventSeq");
  const seq = typeof seqData.guardEventSeq === "number" ? seqData.guardEventSeq : 0;
  const idxData = await chrome.storage.local.get("guardIndex");
  const index = idxData.guardIndex || {};

  const dup = findDupEvent(index, ev.fingerprint, now);
  if (dup) return dup;

  const id = String(seq + 1);
  const record = {
    id: id,
    ts: now,
    time: formatTime(now),
    url: sanitizeUrl(ev.url || ""),
    fingerprint: ev.fingerprint || "",
    task: ev.task || "",
    retries: typeof ev.retries === "number" ? ev.retries : 0,
    result: ev.result || "",
    line: ev.line || "",
    evidence: {
      status: typeof ev.status === "number" ? ev.status : 0,
      bodyHead: (ev.bodyHead || "").slice(0, 512),
      headers: ev.headers || "",
      extVersion: chrome.runtime.getManifest().version,
      manualVersion: "v1.1",
      history: [{ ts: now, to: ev.state || STATE_OBSERVE }],
      source: ev.source || ""
    }
  };
  index[id] = {
    id: id,
    ts: now,
    fingerprint: record.fingerprint,
    state: ev.state || STATE_OBSERVE,
    result: record.result,
    closed: false
  };
  await chrome.storage.local.set({
    ["guardEvent:" + id]: record,
    guardIndex: index,
    guardEventSeq: seq + 1
  });
  return id;
}

async function updateGuardEvent(id, patch, historyEntry) {
  const key = "guardEvent:" + id;
  const d = await chrome.storage.local.get(key);
  const rec = d[key];
  if (!rec) return;
  if (typeof patch.retries === "number") rec.retries = patch.retries;
  if (patch.result) rec.result = patch.result;
  if (patch.line) rec.line = patch.line;
  rec.evidence = rec.evidence || {};
  if (historyEntry) rec.evidence.history = (rec.evidence.history || []).concat([historyEntry]);
  const idxData = await chrome.storage.local.get("guardIndex");
  const index = idxData.guardIndex || {};
  if (index[id]) {
    if (patch.result) index[id].result = patch.result;
    if (historyEntry && historyEntry.to) index[id].state = historyEntry.to;
  }
  await chrome.storage.local.set({ [key]: rec, guardIndex: index });
}

async function settleGuardEvent(id, result, extra) {
  const key = "guardEvent:" + id;
  const d = await chrome.storage.local.get(key);
  const rec = d[key];
  if (!rec) return;
  rec.result = result;
  rec.closedAt = Date.now();
  if (extra) Object.assign(rec, extra);
  rec.evidence = rec.evidence || {};
  rec.evidence.history = (rec.evidence.history || []).concat([{ ts: Date.now(), result: result }]);
  const idxData = await chrome.storage.local.get("guardIndex");
  const index = idxData.guardIndex || {};
  if (index[id]) {
    index[id].result = result;
    index[id].closed = true;
    if (extra && extra.revoked) index[id].state = STATE_NORMAL;
  }
  await chrome.storage.local.set({ [key]: rec, guardIndex: index });
}

async function setGuardState(state, eventId) {
  const prevData = await chrome.storage.local.get("guardState");
  const prev = prevData.guardState || {};
  await chrome.storage.local.set({
    guardState: {
      state: state,
      eventId: eventId || prev.eventId || null,
      updatedAt: Date.now()
    }
  });
}

/* ================= B3 索引裁剪 ================= */
function trimGuardIndex(index) {
  if (!index) return;
  const ids = Object.keys(index);
  if (ids.length <= GUARD_INDEX_LIMIT) return;
  ids.sort((a, b) => (index[a].ts || 0) - (index[b].ts || 0));
  const drop = ids.slice(0, ids.length - GUARD_INDEX_LIMIT);
  const removeKeys = drop.map((id) => "guardEvent:" + id);
  const next = {};
  Object.keys(index).forEach((id) => { if (drop.indexOf(id) === -1) next[id] = index[id]; });
  chrome.storage.local.remove(removeKeys);
  chrome.storage.local.set({ guardIndex: next });
}

/* ================= B5 系统通知（带按钮） ================= */
/* 通知 id 固定为 guard:<tabId>，同一标签页的新提示会替换旧提示，便于按 tabId 清理 */
function notifIdFor(tabId) { return "guard:" + tabId; }

function notifyGuard(tabId, title, message, isRed) {
  if (typeof tabId !== "number") return;
  const buttons = isRed ? [{ title: "知道了" }, { title: "打开页面处理" }] : [{ title: "知道了" }];
  try {
    chrome.notifications.create(notifIdFor(tabId), {
      type: "basic",
      iconUrl: "icons/icon128.png",
      title: String(title || "抖音AI合集作者采集"),
      message: String(message || ""),
      priority: 2,
      buttons: buttons,
      requireInteraction: !!isRed
    });
  } catch (e) {}
}

async function focusTab(tabId) {
  try {
    const tab = await chrome.tabs.get(tabId);
    if (!tab) return;
    await chrome.tabs.update(tabId, { active: true });
    if (tab.windowId != null) await chrome.windows.update(tab.windowId, { focused: true });
  } catch (e) {}
}

chrome.notifications.onButtonClicked.addListener((notifId, btnIdx) => {
  if (!notifId) return;

  if (notifId === NOTIF_DAILY_REMIND) {
    /* 「打开面板」：优先直接开 popup，开不了就聚焦一个抖音标签页 */
    openPopupOrDouyinTab();
    chrome.notifications.clear(NOTIF_DAILY_REMIND);
    return;
  }

  if (notifId.indexOf("guard:") !== 0) return;
  const tabId = Number(notifId.slice("guard:".length));
  if (btnIdx === 1) {
    /* 「打开页面处理」：聚焦发起该故障的标签页，不直接改设置 */
    focusTab(tabId);
  } else {
    /* 「知道了」：仅签收，收起通知；状态与采集均不变 */
    chrome.notifications.clear(notifId);
  }
});

chrome.notifications.onClicked.addListener((notifId) => {
  if (notifId === NOTIF_DAILY_REMIND) {
    openPopupOrDouyinTab();
    chrome.notifications.clear(notifId);
  }
});

/* ================= B6 导航兜底（tabs.onUpdated + guardAlive 配对） ================= */
async function readNavMap() {
  const d = await chrome.storage.session.get("guardNav");
  return d.guardNav || {};
}
async function writeNavMap(map) {
  await chrome.storage.session.set({ guardNav: map });
}

function armNavFallback(tabId, url) {
  enqueue(async () => {
    const map = await readNavMap();
    map[tabId] = { url: sanitizeUrl(url || ""), ts: Date.now(), eventId: null, notified: false };
    await writeNavMap(map);
  });
  if (navTimers.has(tabId)) clearTimeout(navTimers.get(tabId));
  navTimers.set(tabId, setTimeout(() => {
    navTimers.delete(tabId);
    evaluateNavFallback(tabId);
  }, GUARD_ALIVE_TIMEOUT_MS));
}

async function clearNavFallback(tabId) {
  if (navTimers.has(tabId)) { clearTimeout(navTimers.get(tabId)); navTimers.delete(tabId); }
  await enqueue(async () => {
    const map = await readNavMap();
    if (map[tabId]) { delete map[tabId]; await writeNavMap(map); }
  });
}

async function evaluateNavFallback(tabId) {
  const map = await readNavMap();
  const entry = map[tabId];
  if (!entry) return;
  /* 超时窗口内已经收到 guardAlive：不算兜底。
   * 内存登记表可能因 SW 重启 / onUpdated 清空而失真，这里补一次直连探活确认，
   * 避免「content 明明注入了却误报未注入」的红灯。 */
  const alive = registeredTabs.has(tabId) || await pingTab(tabId);
  if (alive) {
    registeredTabs.add(tabId);
    aliveTabs.add(tabId);
    missCount.delete(tabId);
    await clearNavFallback(tabId);
    return;
  }
  /* 兜底不读状态码：只记一笔「疑似导航失败」并转人工 */
  const id = await enqueue(() => createGuardEvent({
    fingerprint: "疑似导航失败（content 未注入）",
    state: STATE_MANUAL,
    result: "转人工",
    url: entry.url,
    status: 0,
    source: NAV_FALLBACK_SOURCE
  }));
  await enqueue(() => setGuardState(STATE_MANUAL, id));
  const m = await readNavMap();
  if (m[tabId]) { m[tabId].eventId = id; m[tabId].notified = true; await writeNavMap(m); }
  notifyGuard(tabId, "需要处理：页面疑似未正常加载",
    "页面导航已完成，但脚本没有注入（可能是浏览器错误页）。请打开页面确认；若仍打不开，请手动导航到搜索页。", true);
}

/* 收到同 tabId 的 guardAlive → 撤销兜底记录；红灯已弹出则同步更新为「误报已撤销」 */
async function revokeNavFallback(tabId) {
  const map = await readNavMap();
  const entry = map[tabId];
  if (!entry) return;
  if (entry.eventId) {
    await enqueue(() => settleGuardEvent(entry.eventId, "误报已撤销", { revoked: true }));
    await enqueue(() => setGuardState(STATE_NORMAL, entry.eventId));
  }
  if (entry.notified) {
    notifyGuard(tabId, "误报已撤销", "刚才提示的「页面疑似未正常加载」为误报，页面脚本已正常注入，无需处理。", false);
  }
  await clearNavFallback(tabId);
}

chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
  const url = changeInfo.url || (tab && tab.url) || "";
  if (changeInfo.status === "loading") {
    /* 新一轮导航开始：撤销该 tab 的存活登记，要求重新 guardAlive */
    if (!url || DOUYIN_RE.test(url)) {
      registeredTabs.delete(tabId);
      aliveTabs.delete(tabId);
      missCount.delete(tabId);
    }
    return;
  }
  if (changeInfo.status !== "complete") return;

  /* 错误页（chrome-error://）或 URL 读不到：退化为「该 tabId 是否已登记 guardAlive」判断 */
  const urlUnreadable = !url || url.indexOf("chrome-error://") === 0;
  if (!urlUnreadable && !DOUYIN_RE.test(url)) return;      // 确实导航到别的站点了，不管
  if (urlUnreadable && registeredTabs.has(tabId)) return;  // 已登记存活，不算导航失败
  armNavFallback(tabId, urlUnreadable ? "" : url);
});

chrome.tabs.onRemoved.addListener(async (tabId) => {
  registeredTabs.delete(tabId);
  aliveTabs.delete(tabId);
  missCount.delete(tabId);
  clearNavFallback(tabId);
  chrome.notifications.clear(notifIdFor(tabId));
  /* 关页即重选 leader，不必等下一个探活周期 */
  await updateLeader(await douyinTabIdSet());
});

/* ================= B8 leader 探活与仲裁 ================= */
async function ensureProbeAlarm() {
  try {
    const a = await chrome.alarms.get(ALARM_PROBE);
    if (!a) chrome.alarms.create(ALARM_PROBE, { periodInMinutes: GUARD_PROBE_PERIOD_MIN });
  } catch (e) {}
}

function pingTab(tabId) {
  return new Promise((resolve) => {
    let done = false;
    const t = setTimeout(() => { if (!done) { done = true; resolve(false); } }, GUARD_PING_REPLY_TIMEOUT_MS);
    try {
      chrome.tabs.sendMessage(tabId, { type: "guardPing", ts: Date.now() }, (resp) => {
        if (done) return;
        done = true;
        clearTimeout(t);
        resolve(!chrome.runtime.lastError && !!resp && resp.type === "guardProbeReply");
      });
    } catch (e) {
      if (!done) { done = true; clearTimeout(t); resolve(false); }
    }
  });
}

/* leader 归属只认「标签页是否还存在」（existingIds 来自 chrome.tabs.query）。
 * 不再用 content 探活结果判死：SPA 导航/重定向会让 content 短暂失联，
 * 据此判死会在导航瞬间被别的页抢走 leader，探活时又可能把 leader 置成 null。 */
async function updateLeader(existingIds) {
  const s = await chrome.storage.session.get("leaderTabId");
  const cur = s.leaderTabId;

  if (cur != null && existingIds.has(cur)) {
    await chrome.storage.session.set({ lastProbeAt: Date.now() });
    return;
  }
  const sorted = Array.from(existingIds).sort((a, b) => a - b);
  if (!sorted.length) {
    await chrome.storage.session.set({ leaderTabId: null, lastProbeAt: Date.now() });
    return;
  }
  const next = sorted[0];
  await chrome.storage.session.set({ leaderTabId: next, lastProbeAt: Date.now() });
  /* 必须用回调式：无回调的 sendMessage 返回 Promise，目标页无 content 脚本时会 reject，
   * 同步 try/catch 抓不到，会在扩展错误列表里累积「Could not establish connection」。 */
  try { chrome.tabs.sendMessage(next, { type: "guardLeaderChanged", leader: true }, () => { void chrome.runtime.lastError; }); } catch (e) {}
}

/* 当前存在的 douyin 标签页 id 集合（leader 归属的权威依据） */
async function douyinTabIdSet() {
  const ids = new Set();
  try {
    const tabs = await chrome.tabs.query({ url: ["*://*.douyin.com/*"] });
    tabs.forEach((t) => { if (typeof t.id === "number") ids.add(t.id); });
  } catch (e) {}
  return ids;
}

/* 判断某个 tab 现在是否「还算存活」：存在，且 URL 为空（导航中）或仍是 douyin */
async function tabAlive(tabId) {
  try {
    const t = await chrome.tabs.get(tabId);
    if (!t) return false;
    const u = t.url || t.pendingUrl || "";
    return !u || DOUYIN_RE.test(u);
  } catch (e) {
    return false;
  }
}

async function probeTabs() {
  /* 权威候选 = 当前存在的 douyin 标签页（leader 归属依据）；
   * 内存登记表只作补充，可能因 SW 重启 / onUpdated 清空而失真 */
  const existing = await douyinTabIdSet();

  /* 已不存在 / 已不是 douyin 的 tab：从登记表移除（leader 死亡只认这一条） */
  Array.from(registeredTabs).forEach((id) => {
    if (!existing.has(id)) {
      registeredTabs.delete(id);
      aliveTabs.delete(id);
      missCount.delete(id);
    }
  });

  /* 探活：只用于记录 content 注入状态（服务导航兜底），不参与 leader 归属 */
  const alive = new Set();
  for (const id of Array.from(existing)) {
    const ok = await pingTab(id);
    if (ok) {
      alive.add(id);
      registeredTabs.add(id);
      missCount.delete(id);
    } else {
      missCount.set(id, (missCount.get(id) || 0) + 1);
    }
  }
  aliveTabs = alive;
  await updateLeader(existing);
  await sweepNavFallback();
}

/* SW 重启后可能错过 15 秒窗口，探活时顺带扫一遍超时未配对的兜底窗口 */
async function sweepNavFallback() {
  const map = await readNavMap();
  const now = Date.now();
  const ids = Object.keys(map);
  for (let i = 0; i < ids.length; i++) {
    const tabId = Number(ids[i]);
    const entry = map[ids[i]];
    if (!entry) continue;
    if (registeredTabs.has(tabId)) { await clearNavFallback(tabId); continue; }
    if (now - (entry.ts || 0) >= GUARD_ALIVE_TIMEOUT_MS) await evaluateNavFallback(tabId);
  }
}

/* ================= B7 整页 502 的受控重新导航 ================= */
/* 退避计数存 chrome.storage.local，且只在「页面加载后稳定 FULLPAGE_STABLE_MS 无新故障」时清零。
 * 旧实现存 session 并被 guardAlive 直接 remove，导致每轮页面加载都把计数清零，
 * 退避永不递进、每 1 分钟整页重载一次。 */
async function readFullPage(tabId) {
  const key = FULLPAGE_PREFIX + tabId;
  const d = await chrome.storage.local.get(key);
  return d[key] || null;
}

async function writeFullPage(tabId, rec) {
  await chrome.storage.local.set({ [FULLPAGE_PREFIX + tabId]: rec });
}

async function clearFullPage(tabId) {
  try { await chrome.alarms.clear(ALARM_FP_STABLE_PREFIX + tabId); } catch (e) {}
  await chrome.storage.local.remove(FULLPAGE_PREFIX + tabId);
}

/* 页面重新加载成功：不清零，只把「清零」推迟到稳定观察期满 */
async function armFullPageStableCheck(tabId) {
  const rec = await readFullPage(tabId);
  if (!rec || !rec.eventId) return;
  rec.loadedAt = Date.now();
  await writeFullPage(tabId, rec);
  try {
    await chrome.alarms.clear(ALARM_FP_STABLE_PREFIX + tabId);
    chrome.alarms.create(ALARM_FP_STABLE_PREFIX + tabId, { when: Date.now() + FULLPAGE_STABLE_MS });
  } catch (e) {}
}

/* 稳定观察期满：期间无新故障才判定「已恢复」，清零退避计数并结案 */
async function onFullPageStableAlarm(tabId) {
  const rec = await readFullPage(tabId);
  if (!rec) return;
  const now = Date.now();
  if (!rec.loadedAt || now - rec.loadedAt < FULLPAGE_STABLE_MS - 1000) return;
  if (rec.faultAt && rec.faultAt > rec.loadedAt) return;
  const id = rec.eventId;
  await clearFullPage(tabId);
  if (!id) return;
  await enqueue(() => settleGuardEvent(id, "已恢复"));
  const d = await chrome.storage.local.get("guardState");
  if (d.guardState && d.guardState.eventId === id) {
    await enqueue(() => setGuardState(STATE_NORMAL, id));
  }
}

function scheduleNavReload(tabId, attempt) {
  const idx = Math.min(attempt, GUARD_BACKOFF_MINUTES.length - 1);
  const minutes = GUARD_BACKOFF_MINUTES[idx];
  try {
    chrome.alarms.create(ALARM_NAV_PREFIX + tabId, { when: Date.now() + minutes * 60000 });
  } catch (e) {}
}

async function onNavReloadAlarm(tabId) {
  /* 顺序硬要求（F.1 #3）：先写 guardState = 待人工确认并落记录，再 reload */
  const rec = await readFullPage(tabId);
  let id = rec && rec.eventId ? rec.eventId : null;
  if (id) {
    /* 复用上报时那条记录，只推进状态，不新开一笔 */
    await enqueue(() => updateGuardEvent(id, {}, { ts: Date.now(), to: STATE_MANUAL }));
  } else {
    id = await enqueue(() => createGuardEvent({
      fingerprint: "抖动 / 整页 502",
      state: STATE_MANUAL,
      status: 0,
      source: "整页 502 受控重新导航"
    }));
  }
  await enqueue(() => setGuardState(STATE_MANUAL, id));
  let tab = null;
  try { tab = await chrome.tabs.get(tabId); } catch (e) {}
  if (!tab) {
    /* tab 已关闭：不再导航，直接结案转人工 */
    await enqueue(() => settleGuardEvent(id, "人工处理"));
    await clearFullPage(tabId);
    return;
  }
  try { await chrome.tabs.reload(tabId); } catch (e) {}
  notifyGuard(tabId, "需要处理：页面已重新加载",
    "刚才页面打不开，脚本已自动重新加载过一次。请确认页面正常后点「已按手册处理，继续采集」。若页面仍打不开，请手动导航到搜索页。", true);
}

chrome.alarms.onAlarm.addListener((alarm) => {
  if (!alarm || !alarm.name) return;
  if (alarm.name === ALARM_PROBE) { probeTabs(); return; }
  if (alarm.name.indexOf(ALARM_NAV_PREFIX) === 0) {
    onNavReloadAlarm(Number(alarm.name.slice(ALARM_NAV_PREFIX.length)));
    return;
  }
  if (alarm.name.indexOf(ALARM_FP_STABLE_PREFIX) === 0) {
    onFullPageStableAlarm(Number(alarm.name.slice(ALARM_FP_STABLE_PREFIX.length)));
    return;
  }
  if (alarm.name === ALARM_DAILY_REMIND) {
    onDailyRemind();
  }
});

/* ================= B2 初始化 ================= */
async function initGuard() {
  const d = await chrome.storage.local.get(["guardState", "guardIndex", "guardEventSeq"]);
  const init = {};
  if (!d.guardState) init.guardState = { state: STATE_NORMAL, eventId: null, updatedAt: Date.now() };
  if (!d.guardIndex) init.guardIndex = {};
  if (typeof d.guardEventSeq !== "number") init.guardEventSeq = 0;
  if (Object.keys(init).length) await chrome.storage.local.set(init);
  await ensureProbeAlarm();
  await ensureDailyRemindAlarm();
  /* 1.3.5 起 18:10 清空提醒并入 18:00 单条。删代码不会注销老设备上已注册的 alarm，
   * 这里显式清一次，否则升级后仍会在 18:10 弹「该清空采集列表了」。 */
  try { await chrome.alarms.clear(LEGACY_ALARM_CLEAR_REMIND); } catch (e) {}
}

/* SW 唤醒时重建登记表与兜底窗口 */
async function restoreGuard() {
  try {
    const tabs = await chrome.tabs.query({ url: ["*://*.douyin.com/*"] });
    tabs.forEach((t) => {
      if (typeof t.id !== "number") return;
      registeredTabs.add(t.id);
      /* 先视为存活，下一次探活会纠正 */
      aliveTabs.add(t.id);
    });
  } catch (e) {}
  const map = await readNavMap();
  const now = Date.now();
  Object.keys(map).forEach((key) => {
    const tabId = Number(key);
    const entry = map[key];
    if (!entry) return;
    const left = GUARD_ALIVE_TIMEOUT_MS - (now - (entry.ts || 0));
    if (left <= 0) { evaluateNavFallback(tabId); return; }
    if (navTimers.has(tabId)) clearTimeout(navTimers.get(tabId));
    navTimers.set(tabId, setTimeout(() => { navTimers.delete(tabId); evaluateNavFallback(tabId); }, left));
  });
  /* 任何一次 SW 唤醒都保证 guardState / guardIndex / guardEventSeq 存在 */
  await initGuard();
}

/* ================= 当日累计口径（P0，甲方案：非破坏性） =================
 * 不删任何数据，只维护一个「当日起点」。
 * 起点优先级：
 *   1) 手动重置过、且重置日期就是今天 → 用重置那一刻；
 *   2) 否则回落到「今天第一条作者的 firstSeen」；
 *   3) 今天还没有作者 → 今天 00:00。
 * 终点固定是「此刻」。跨天后旧的重置记录自动失效（比对 date），不需要额外的清理任务。
 */
function ymdKey(ts) {
  const d = new Date(ts);
  return d.getFullYear() + "-" + pad2(d.getMonth() + 1) + "-" + pad2(d.getDate());
}

function startOfToday(now) {
  const d = new Date(now);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

async function effectiveDayStart(now) {
  const t = typeof now === "number" ? now : Date.now();
  const d = await chrome.storage.local.get([DAY_START_KEY, "authors"]);
  const rec = d[DAY_START_KEY];
  if (rec && typeof rec.ts === "number" && rec.date === ymdKey(t) && rec.ts <= t) return rec.ts;

  const day0 = startOfToday(t);
  const authors = d.authors || {};
  let min = null;
  Object.keys(authors).forEach((k) => {
    const ts = Number(authors[k] && authors[k].firstSeen); /* firstSeen 是毫秒 */
    if (!ts || ts < day0) return;
    if (min === null || ts < min) min = ts;
  });
  return min === null ? day0 : min;
}

/* ================= 重置历史（1.3.5） =================
 * 只认当天：读取时按 ymdKey 过滤，写入时把旧日条目丢掉。
 * 不靠定时清理 —— 没有定时任务就没有漏跑。
 */
function histOfToday(hist, now) {
  const d0 = ymdKey(now);
  const arr = Array.isArray(hist) ? hist : [];
  return arr.filter((h) => h && typeof h.ts === "number" && ymdKey(h.ts) === d0);
}

/* reason: manual（手动重置）/ upload（上传自动重置）/ end（结束今天）
 * quiet=true        调用方自己弹通知，这里不重复弹
 * withHistory=false 不写历史（上传自动重置走这条：那是系统副作用，不是用户主动动作）
 */
async function resetDayStart(reason, quiet, withHistory) {
  const now = Date.now();
  const prev = await effectiveDayStart(now);   /* 本时段真正的起点（手动重置或首个作者） */
  const count = await countSince(prev);        /* 本时段采到多少个 = 历史里的「共 N 个」 */
  const type = reason || "manual";
  const set = {
    [DAY_START_KEY]: { ts: now, date: ymdKey(now), type: type, count: count }
  };
  if (withHistory) {
    const d = await chrome.storage.local.get([DAY_START_HISTORY_KEY]);
    const hist = histOfToday(d[DAY_START_HISTORY_KEY], now);
    hist.unshift({ ts: now, type: type, count: count });
    set[DAY_START_HISTORY_KEY] = hist.slice(0, DAY_START_HISTORY_MAX);   /* 只留最近 5 条 */
  }
  await chrome.storage.local.set(set);
  if (!quiet) {
    notifySimple("当日起点已重置", "本时段共 " + count + " 个；起点已切到 " + formatTime(now) + "。");
  }
  return { ts: now, count: count };
}

/* 结束今天：清历史 → 重置起点（回写本条）→ 清列表 → 停采集，一次点击、四步写（不是事务）。
 * 放在 background 是刻意的：popup 与 content 各自写 authors/stats 会互相覆盖。
 */
async function endToday() {
  /* 1.3.9 平台侧顺序：终片 → 重试到期任务 → day-final → 平台日台账；失败不阻塞本地收工 */
  try { await pfEndToday(); } catch (e) {}
  /* 先清空历史：清列表后旧记录的「共 N 个」已无可核对对象，留着是噪音；
   * 清完再写本条，让主行显示「上一次重置 = 今天这次收工」。 */
  await chrome.storage.local.remove(DAY_START_HISTORY_KEY);
  const r = await resetDayStart("end", true, true);
  const d = await chrome.storage.local.get("settings");
  const s = { ...(d.settings || {}), collecting: false, autoBrowse: false, refreshArmed: false };
  await chrome.storage.local.set({
    authors: {},
    stats: { scanned: 0, matched: 0 },
    settings: s
  });
  await chrome.storage.local.remove("refreshCount");
  notifySimple("已结束今天", "起点已重置，列表已清空，采集已停止。本时段共 " + r.count + " 个。");
  return r;
}

async function countSince(ts) {
  const d = await chrome.storage.local.get("authors");
  const authors = d.authors || {};
  let n = 0;
  Object.keys(authors).forEach((k) => {
    const s = Number(authors[k] && authors[k].firstSeen); /* firstSeen 是毫秒 */
    if (s && s >= ts) n++;
  });
  return n;
}

function notifySimple(title, message) {
  try {
    chrome.notifications.create({
      type: "basic",
      iconUrl: "icons/icon128.png",
      title: String(title || "抖音AI合集作者采集"),
      message: String(message || ""),
      priority: 2
    });
  } catch (e) {}
}

async function openPopupOrDouyinTab() {
  try {
    if (chrome.action && chrome.action.openPopup) { await chrome.action.openPopup(); return; }
  } catch (e) {}
  await focusDouyinTabOrCreate();
}

/* openPopupOrDouyinTab 的兜底：开不了 popup 就聚焦一个抖音标签页 */
async function focusDouyinTabOrCreate() {
  try {
    const tabs = await chrome.tabs.query({ url: ["*://*.douyin.com/*"] });
    if (tabs.length && typeof tabs[0].id === "number") { await focusTab(tabs[0].id); return; }
    await chrome.tabs.create({ url: "https://www.douyin.com/jingxuan" });
  } catch (e) {}
}

/* ================= 每天 18:00 提醒上传 =================
 * 用一次性 alarm，触发后再排下一次；不用 periodInMinutes 是因为
 * 开机补触发会把周期起点挪到早上，之后每天都误报。
 */
function nextRemindTs(now, hour, minute) {
  const d = new Date(now);
  d.setHours(hour, minute, 0, 0);
  let ts = d.getTime();
  if (ts <= now + 1000) ts += 24 * 3600 * 1000;
  return ts;
}

async function ensureDailyRemindAlarm() {
  try {
    const a = await chrome.alarms.get(ALARM_DAILY_REMIND);
    if (a) return;
    await chrome.alarms.create(ALARM_DAILY_REMIND, { when: nextRemindTs(Date.now(), REMIND_HOUR, REMIND_MINUTE) });
  } catch (e) {}
}

async function rescheduleDailyRemindAlarm() {
  try {
    await chrome.alarms.clear(ALARM_DAILY_REMIND);
    await chrome.alarms.create(ALARM_DAILY_REMIND, { when: nextRemindTs(Date.now(), REMIND_HOUR, REMIND_MINUTE) });
  } catch (e) {}
}

async function onDailyRemind() {
  const now = Date.now();
  const d = new Date(now);
  const reached = d.getHours() > REMIND_HOUR ||
    (d.getHours() === REMIND_HOUR && d.getMinutes() >= REMIND_MINUTE);

  /* 关机期间错过 18:00，第二天开机才补触发：不提醒，直接排到今天的 18:00 */
  if (!reached) { await rescheduleDailyRemindAlarm(); return; }

  const today = ymdKey(now);
  const st = await chrome.storage.local.get("lastRemindDate");
  if (st.lastRemindDate !== today) {
    await chrome.storage.local.set({ lastRemindDate: today });
    const n = await countSince(await effectiveDayStart(now));
    try {
      chrome.notifications.create(NOTIF_DAILY_REMIND, {
        type: "basic",
        iconUrl: "icons/icon128.png",
        title: "该上传今天的数据了",
        message: "今天累计采集 " + n + " 个作者。打开面板 → 上传到 43.165.183.142；"
          + "上传成功后起点会自动重置。下班前记得点「结束今天」清空列表与历史。",
        priority: 2,
        buttons: [{ title: "打开面板" }],
        requireInteraction: true
      });
    } catch (e) {}
  }
  await rescheduleDailyRemindAlarm();
}

/* ================= 上传（POST /api/file，multipart/form-data） ================= */
async function uploadSecUids(ids, uploaderId) {
  const list = Array.isArray(ids) ? ids.filter((x) => typeof x === "string" && x.trim()) : [];
  if (!list.length) return { ok: false, message: "没有可上传的 sec_uid。" };
  if (list.length > UPLOAD_MAX_LINES) {
    return { ok: false, message: "本次 " + list.length + " 条，超过单次上限 " + UPLOAD_MAX_LINES + " 条，请缩小范围。" };
  }
  const name = String(uploaderId || "").trim();
  if (!name) return { ok: false, message: "请先填写上传人 ID。" };
  if (Array.from(name).length > 64) return { ok: false, message: "上传人 ID 不能超过 64 个字符。" };

  const form = new FormData();
  form.append("uploader_id", name);
  form.append("file", new File([list.join("\n")], "douyin-ai-mix-authors.txt", { type: "text/plain" }));

  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), UPLOAD_TIMEOUT_MS);
  let resp;
  try {
    resp = await fetch(UPLOAD_ENDPOINT, { method: "POST", body: form, signal: ctrl.signal });
  } catch (e) {
    clearTimeout(timer);
    return { ok: false, message: "连接失败，提交结果尚未确认。请保持原上传人 ID 和文件内容重试。" };
  }
  clearTimeout(timer);

  let data = null;
  try { data = await resp.json(); } catch (e) {}

  if (!resp.ok) {
    const msg = (data && data.message) || ("提交失败（HTTP " + resp.status + "）");
    return { ok: false, status: resp.status, message: msg, errors: (data && data.errors) || [] };
  }

  const rec = (data && data.record) || {};
  const count = Number(rec.count) || list.length;
  const inserted = Number(rec.inserted_count) || 0;
  const dup = Number(rec.remote_duplicate_count) || 0;

  /* R2-10①：以下是 2xx 之后的本地记账。POST 已经发出且服务端已收下，
   * 这里任何一步失败都不能再报成「上传失败」——必须带 posted:true 单独回。 */
  try {
    /* 先切起点、再记账（评审 J1(b)）：防重复提交靠的是「切起点」。
     * 先切后记，记账挂了只丢一条历史记录；先记后切，记账一挂就留下
     * 「已入库 + 起点没动」的窗口，操作者一重试就是整批重复。 */
    /* 上传成功 = 本时段已交付。直接重置起点，不再询问。
     * withHistory=false：这是系统副作用，不是你主动动作，写进历史会挤掉真实操作。 */
    const rs = await resetDayStart("upload", true, false);

    await chrome.storage.local.set({
      lastUpload: { at: Date.now(), uploaderId: name, count: count, inserted: inserted, dup: dup }
    });

    /* 通知里同时给出「本时段共 T」与「本次提交 M」：
     * 差值 1~9 时 P8 不拦，用户仍要能从通知判断有没有漏掉一截。
     * 量词统一用「条」，与面板/弹窗提示行一致（R2-4）。 */
    notifySimple(
      "上传成功，起点已重置",
      "本时段共 " + rs.count + " 条，本次提交 " + count + " 条（新增 " + inserted + "，服务端已存在 " + dup + "）。起点已切到此刻。"
    );

    /* 1.3.9 双通道台账：旧通道只有「解析到 record 且 2xx」才记 legacy_batch */
    if (data && data.record) {
      try { await pfLegacyDelivered({ count: count, inserted: inserted, dup: dup }); } catch (e) {}
    }

    return { ok: true, count: count, inserted: inserted, dup: dup, total: rs.count };
  } catch (e) {
    return {
      ok: false,
      posted: true,
      status: resp.status,
      message: "服务端已收下（HTTP " + resp.status + "），但本地记账失败：" + ((e && e.message) || String(e)) + "。请先到上传记录表核对当日行数，不要连点重试。"
    };
  }
}

/* ================= 既有逻辑（保持原行为） ================= */
chrome.runtime.onInstalled.addListener(async () => {
  const data = await chrome.storage.local.get(["settings", "authors", "stats"]);
  if (!data.settings) {
    await chrome.storage.local.set({
      settings: {
        collecting: false,
        autoBrowse: false,
        intervalMs: 4000,
        requireMix: true,
        requireAi: true,
        collapsed: false
      }
    });
  }
  if (!data.authors) await chrome.storage.local.set({ authors: {} });
  if (!data.stats) await chrome.storage.local.set({ stats: { scanned: 0, matched: 0 } });
  const n = Object.keys((data.authors || {})).length;
  setBadge(n);
  await initGuard();
});

chrome.runtime.onStartup.addListener(async () => {
  const data = await chrome.storage.local.get("authors");
  setBadge(Object.keys(data.authors || {}).length);
  await initGuard();
});

/* ================= B4 onMessage ================= */
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (!msg) return;
  const tabId = sender && sender.tab && typeof sender.tab.id === "number" ? sender.tab.id : undefined;

  if (msg.type === "badge") {
    setBadge(Number(msg.count) || 0);
    return;
  }

  if (msg.type === "notify") {
    /* 兼容既有调用：无 tabId / level 时退化为原来的纯文本通知 */
    if (typeof tabId === "number" && msg.level) {
      notifyGuard(tabId, msg.title, msg.message, msg.level === "red");
    } else {
      try {
        chrome.notifications.create({
          type: "basic",
          iconUrl: "icons/icon128.png",
          title: String(msg.title || "抖音AI合集作者采集"),
          message: String(msg.message || ""),
          priority: 2
        });
      } catch (e) {}
    }
    return;
  }

  if (msg.type === "guardAlive") {
    /* 只发一次，不做周期心跳；tabId 由 background 从 sender.tab.id 补齐 */
    if (typeof tabId === "number") {
      registeredTabs.add(tabId);
      aliveTabs.add(tabId);
      missCount.delete(tabId);
      revokeNavFallback(tabId);
      /* 页面加载起来了 → 安排稳定观察；稳定满 60 秒无新故障才清零退避计数 */
      armFullPageStableCheck(tabId);
      ensureProbeAlarm();
    }
    return;
  }

  if (msg.type === "applyLeader") {
    if (typeof tabId === "number") {
      registeredTabs.add(tabId);
      aliveTabs.add(tabId);
      missCount.delete(tabId);
      revokeNavFallback(tabId);
      armFullPageStableCheck(tabId);
      ensureProbeAlarm();
    }
    (async () => {
      let granted = false;
      if (typeof tabId === "number") {
        const s = await chrome.storage.session.get("leaderTabId");
        const cur = s.leaderTabId;
        let curDead = cur == null || cur === tabId;
        if (!curDead) {
          /* 现任 leader 是否失活只看「标签页还在不在」；
           * 导航中的 content 会短暂失联，用 ping 判定会误判成失活而被抢 */
          curDead = !(await tabAlive(cur));
          if (!curDead) {
            registeredTabs.add(cur);
            aliveTabs.add(cur);
            missCount.delete(cur);
          }
        }
        if (curDead) {
          await chrome.storage.session.set({ leaderTabId: tabId, lastProbeAt: Date.now() });
          granted = true;
        }
      }
      sendResponse({ ok: true, leader: granted });
    })();
    return true;   /* 异步分支：必须 return true 并 await，避免 SW 提前终止 */
  }

  if (msg.type === "guardReport") {
    (async () => {
      const ev = msg.event || {};
      const isRed = ev.level === "red";

      /* 整页 502：一笔故障只留一条记录，退避计数累加在同一 eventId 上 */
      if (ev.kind === "整页502" && typeof tabId === "number") {
        let rec = await readFullPage(tabId);
        /* 两次故障之间页面已稳定满 60 秒 → 视为恢复过，计数清零，本笔按全新故障处理 */
        if (rec && rec.loadedAt && Date.now() - rec.loadedAt >= FULLPAGE_STABLE_MS) {
          await clearFullPage(tabId);
          rec = null;
        }
        const attempt = rec && typeof rec.attempt === "number" ? rec.attempt + 1 : 0;
        let id = rec && rec.eventId ? rec.eventId : null;
        if (id) {
          await enqueue(() => updateGuardEvent(id, { retries: attempt }, { ts: Date.now(), to: STATE_BACKOFF }));
        } else {
          id = await enqueue(() => createGuardEvent({
            fingerprint: ev.fingerprint || "抖动 / 整页 502",
            state: STATE_BACKOFF,
            url: ev.url || "",
            status: typeof ev.status === "number" ? ev.status : 0,
            bodyHead: ev.bodyHead || "",
            headers: ev.headers || "",
            source: ev.source || ""
          }));
        }
        if (attempt >= GUARD_BACKOFF_MINUTES.length) {
          /* 退避序列 5 档（1/2/5/10/10 分钟）用尽：停止自动重载，结案转人工 */
          await enqueue(() => setGuardState(STATE_MANUAL, id));
          await enqueue(() => settleGuardEvent(id, "转人工"));
          await clearFullPage(tabId);
        } else {
          await enqueue(() => setGuardState(STATE_BACKOFF, id));
          /* 退避计数落 local；新故障到来即取消本次稳定观察 */
          try { await chrome.alarms.clear(ALARM_FP_STABLE_PREFIX + tabId); } catch (e) {}
          await writeFullPage(tabId, { eventId: id, attempt: attempt, faultAt: Date.now(), loadedAt: 0 });
          scheduleNavReload(tabId, attempt);
        }
        if (msg.hidden) notifyGuard(tabId, "平台抖动，任务已暂停", ev.message || "页面打不开，正在按退避序列自动重试。", false);
        sendResponse({ ok: true, id: id, attempt: attempt });
        return;
      }

      const id = await enqueue(() => createGuardEvent({
        fingerprint: ev.fingerprint || "",
        state: isRed ? STATE_MANUAL : STATE_OBSERVE,
        result: isRed ? "转人工" : "",
        url: ev.url || "",
        task: ev.task || "",
        status: typeof ev.status === "number" ? ev.status : 0,
        bodyHead: ev.bodyHead || "",
        headers: ev.headers || "",
        source: ev.source || ""
      }));

      if (isRed) {
        await enqueue(() => setGuardState(STATE_MANUAL, id));
      } else {
        await enqueue(() => setGuardState(STATE_OBSERVE, id));
      }

      /* 页面不可见时才走系统通知（页面可见时由浮层提示，浮层不在本次范围） */
      if (msg.hidden && typeof tabId === "number") {
        notifyGuard(tabId, isRed ? "需要处理" : "平台抖动，任务已暂停", ev.message || "", isRed);
      }
      sendResponse({ ok: true, id: id });
    })();
    return true;   /* 异步分支：必须 return true 并 await */
  }

  if (msg.type === "guardClose") {
    /* guard.html 点「结案」：把单条事件标记为已结案；若结的是当前事件，顶部状态一并回「正常」 */
    (async () => {
      const id = String(msg.id == null ? "" : msg.id).trim();
      if (!id) { sendResponse({ ok: false, error: "缺少事件编号" }); return; }
      const key = "guardEvent:" + id;
      const d = await chrome.storage.local.get(key);
      if (!d[key]) { sendResponse({ ok: false, error: "事件不存在" }); return; }
      await enqueue(() => settleGuardEvent(id, "人工结案"));
      const cur = await chrome.storage.local.get("guardState");
      if (cur.guardState && String(cur.guardState.eventId) === id) {
        await enqueue(() => setGuardState(STATE_NORMAL, id));
      }
      sendResponse({ ok: true, id: id });
    })();
    return true;   /* 异步分支：必须 return true 并 await */
  }

  if (msg.type === "guardAck") {
    /* 采集员在浮层点「知道了」：仅签收，不改状态、不恢复采集 */
    if (typeof tabId === "number") chrome.notifications.clear(notifIdFor(tabId));
    return;
  }

  if (msg.type === "openTab") {
    /* 浮层在 content script 里没有 chrome.tabs，打开扩展页/精选页交给 background */
    const target = msg.target;
    let url = "";
    if (target === "jingxuan") url = "https://www.douyin.com/jingxuan";
    else if (target === "backend") url = chrome.runtime.getURL("dyaccount_1.1.2plus.html");
    else if (target === "guard") url = chrome.runtime.getURL("guard.html");
    if (url) chrome.tabs.create({ url: url });
    sendResponse({ ok: !!url });
    return;
  }

  if (msg.type === "reloadTab") {
    /* 浮层没有 chrome.tabs：随机刷新的「刷新一次」交给 background。
     * 只刷「发起刷新的那个标签页」自己（sender.tab.id），语义等价对当前页 Ctrl+F5。
     * 与同事那个 exe 的区别：exe 是对前台窗口盲发按键，会打到浏览器之外的窗口/应用；
     * 其它采集页各自按自己的节奏刷新，本就不该被这一个页面的刷新影响。 */
    const tabId = sender && sender.tab ? sender.tab.id : null;
    if (typeof tabId !== "number") {
      sendResponse({ ok: false, reason: "no-tab" });
      return;
    }
    chrome.tabs.reload(tabId, { bypassCache: true });
    /* 「已刷 N 次」用独立存储键单点记账：
       放进 settings 会被各标签页的整包写回（persist）连带抹掉，故独立存 refreshCount。
       多个采集页可能几乎同时到点，读-改-写必须走 enqueue 串行，否则并发读改写会吞计数。
       只有自动刷新（msg.count）才累加，手动「立即刷新一次」不计。 */
    (async () => {
      if (msg.count) {
        await enqueue(async () => {
          const d = await chrome.storage.local.get("refreshCount");
          const n = (Number(d && d.refreshCount) || 0) + 1;
          await chrome.storage.local.set({ refreshCount: n });
        });
      }
      /* 异步应答 + return true：让 SW 在计数写完前保持活跃（不再 fire-and-forget） */
      sendResponse({ ok: true });
    })();
    return true;
  }

  if (msg.type === "dayStartInfo") {
    (async () => {
      /* R2-9③：只要执行到 sendResponse，ok:true 时 count 必为数字。
       * 读失败就明确回 ok:false，不让前端拿到半截数据后自己兜底成 00:00:00。 */
      try {
        const start = await effectiveDayStart(Date.now());
        const n = await countSince(start);
        const d = await chrome.storage.local.get([DAY_START_HISTORY_KEY]);
        const hist = histOfToday(d[DAY_START_HISTORY_KEY], Date.now());
        sendResponse({ ok: true, start: start, count: n, history: hist, last: hist[0] || null });
      } catch (e) {
        sendResponse({ ok: false, message: "读取今日起点失败：" + ((e && e.message) || String(e)) });
      }
    })();
    return true;
  }

  if (msg.type === "resetDayStart") {
    /* 必须 await 后再回包：resetDayStart 已是异步，先回包会让前端读到旧值 */
    (async () => {
      const r = await resetDayStart("manual", false, true);
      sendResponse({ ok: true, count: r.count });
    })();
    return true;
  }

  if (msg.type === "endToday") {
    (async () => {
      const r = await endToday();
      sendResponse({ ok: true, count: r.count });
    })();
    return true;
  }

  if (msg.type === "uploadIds") {
    /* 上传放在 background：popup 关掉也不会中断提交 */
    (async () => {
      /* R2-10①：兜底必回包。异常时无法判定 POST 是否已发出，一律按「尚未确认」处理（同 854 语义）。 */
      try {
        const r = await uploadSecUids(msg.ids, msg.uploaderId);
        sendResponse(r);
      } catch (e) {
        sendResponse({
          ok: false,
          posted: null,
          message: "提交结果尚未确认，请保持原上传人 ID 和文件内容重试。",
          detail: (e && e.message) || String(e)
        });
      }
    })();
    return true;
  }
});

chrome.storage.onChanged.addListener((changes, area) => {
  if (area !== "local") return;
  if (changes.authors) {
    setBadge(Object.keys(changes.authors.newValue || {}).length);
  }
  if (changes.guardIndex) {
    trimGuardIndex(changes.guardIndex.newValue);
  }
});

/* SW 每次唤醒都跑一次：重建登记表、补 alarm */
restoreGuard();