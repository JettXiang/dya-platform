/* 1.4.0 采集遥控：心跳 / 画面回传 / 平台指令轮询
 * 平台「采集工作区」下发指令 → 后端队列 → 本模块每 2.5 秒取走 → 在抖音页执行 → ack 回报。
 * 依赖 platform.js 提供的平台上报函数（同一 Service Worker 作用域）。 */

const CR_ALARM = "collect-remote-tick";
const CR_DEFAULTS = { hbSec: 30, frameSec: 3, pollSec: 2.5, collectKey: "dya-collect-2026" };
const CR_DEVICE_KEY = "crDeviceId";
const CR_SETTINGS_KEY = "crSettings";

let crLoopRunning = false;
let crLastHb = 0;
let crLastFrame = 0;
let crBusyCommand = null;

async function crSleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

async function crSettings() {
  const d = await chrome.storage.local.get([CR_SETTINGS_KEY]);
  return Object.assign({}, CR_DEFAULTS, d[CR_SETTINGS_KEY] || {});
}

async function crBaseUrl() {
  const s = await pfSettings();
  if (s && s.baseUrl) return String(s.baseUrl).replace(/\/+$/, "");
  return PF_BASE_DEFAULT;
}

async function crDeviceId() {
  const d = await chrome.storage.local.get([CR_DEVICE_KEY]);
  if (d[CR_DEVICE_KEY]) return d[CR_DEVICE_KEY];
  const id = "PC-" + Math.random().toString(16).slice(2, 6).toUpperCase();
  await chrome.storage.local.set({ [CR_DEVICE_KEY]: id });
  return id;
}

async function crHeaders() {
  const s = await crSettings();
  return { "Content-Type": "application/json", "X-Collect-Key": s.collectKey };
}

async function crTab() {
  const tabs = await chrome.tabs.query({ url: ["*://*.douyin.com/*"] });
  if (!tabs.length) return null;
  const active = tabs.find((t) => t.active) || null;
  return { tab: active || tabs[0], active: !!active };
}

async function crContentState(tabId) {
  try {
    return await chrome.tabs.sendMessage(tabId, { type: "getState" });
  } catch (e) {
    return null;
  }
}

async function crPlatformSnapshot() {
  try {
    const [st, auth, breakdown, dayFinal] = await Promise.all([
      pfSettings(), pfAuth(), pfPendingBreakdown(), pfDayFinalState()
    ]);
    const user = (auth && auth.user) || null;
    return {
      enabled: !!st.enabled,
      baseUrl: st.baseUrl || "",
      accountCode: st.accountCode || "",
      loggedIn: !!(auth && auth.accessToken),
      loginUser: user ? user.code || user.name || "" : "",
      queued: breakdown.queued,
      retrying: breakdown.retrying,
      blocked: breakdown.blocked,
      conflict: breakdown.conflict,
      total: breakdown.total,
      dayFinalDate: dayFinal ? dayFinal.date || "" : "",
      dayFinalRevision: dayFinal ? dayFinal.revision || 0 : 0
    };
  } catch (e) {
    return {};
  }
}

/** 心跳：30 秒一次，带上平台上报状态，工作区据此渲染「平台上报」面板 */
async function crHeartbeat() {
  const now = Date.now();
  const s = await crSettings();
  if (now - crLastHb < s.hbSec * 1000) return;
  crLastHb = now;

  const base = await crBaseUrl();
  const deviceId = await crDeviceId();
  const found = await crTab();
  const st = found ? await crContentState(found.tab.id) : null;
  const settings = (st && st.settings) || {};
  const stats = (st && st.stats) || {};
  const authors = (st && st.authors) || [];

  const body = {
    collector: "扩展直传",
    deviceId: deviceId,
    collecting: !!settings.collecting,
    autoBrowse: !!settings.autoBrowse,
    scanned: Number(stats.scanned || 0),
    matched: Number(stats.matched || 0),
    authors: authors.length,
    pageUrl: found ? found.tab.url || "" : "",
    channel: settings.channel || "",
    lastHint: stats.lastHint || "",
    fatal: !!stats.fatal,
    platform: await crPlatformSnapshot(),
    at: now
  };
  try {
    await fetch(base + "/api/v1/collect/heartbeat", {
      method: "POST",
      headers: await crHeaders(),
      body: JSON.stringify(body)
    });
  } catch (e) {}
}

/** 画面回传：只截当前前台标签，抖音页不在前台就跳过（避免截错页面） */
async function crFrame() {
  const now = Date.now();
  const s = await crSettings();
  if (now - crLastFrame < s.frameSec * 1000) return;
  crLastFrame = now;

  const found = await crTab();
  if (!found || !found.active) return;
  let dataUrl = "";
  try {
    dataUrl = await chrome.tabs.captureVisibleTab(found.tab.windowId, { format: "jpeg", quality: 55 });
  } catch (e) {
    return;
  }
  if (!dataUrl) return;
  const base = await crBaseUrl();
  try {
    await fetch(base + "/api/v1/collect/frame", {
      method: "POST",
      headers: await crHeaders(),
      body: JSON.stringify({
        collector: "扩展直传",
        deviceId: await crDeviceId(),
        image: dataUrl,
        pageUrl: found.tab.url || "",
        ts: now
      })
    });
  } catch (e) {}
}

/** 执行一条页面动作：交给抖音页的 content script */
async function crDomAction(action, payload) {
  const found = await crTab();
  if (!found) return { ok: false, message: "没有打开的抖音页" };
  try {
    const r = await chrome.tabs.sendMessage(found.tab.id, { type: "crAction", action, payload: payload || {} });
    if (r && r.ok === false) return { ok: false, message: r.reason || "执行失败" };
    return { ok: true, message: (r && r.message) || "已执行" };
  } catch (e) {
    return { ok: false, message: "内容脚本无响应，刷新抖音页后重试" };
  }
}

async function crPlatformAction(type, p) {
  switch (type) {
    case "platformLogin": {
      if (!p.code || !p.password) return { ok: false, message: "缺少工号或密码" };
      const r = await pfLogin(p.code, p.password);
      if (!r || r.ok === false) return { ok: false, message: (r && r.message) || "登录失败" };
      if (p.accountCode) await pfSet({ platformSettings: Object.assign(await pfSettings(), { accountCode: p.accountCode }) });
      return { ok: true, message: "平台登录成功" };
    }
    case "platformSetEnabled": {
      const s = await pfSettings();
      await pfSet({ platformSettings: Object.assign(s, { enabled: !!p.on }) });
      return { ok: true, message: p.on ? "平台上报已开启" : "平台上报已关闭" };
    }
    case "platformSetAccount": {
      const s = await pfSettings();
      await pfSet({ platformSettings: Object.assign(s, { accountCode: String(p.accountCode || "") }) });
      return { ok: true, message: "账号编码已更新为 " + (p.accountCode || "(空)") };
    }
    case "platformSetBaseUrl": {
      const url = String(p.baseUrl || "").trim().replace(/\/+$/, "");
      if (!/^https?:\/\//.test(url)) return { ok: false, message: "平台地址需以 http:// 开头" };
      const s = await pfSettings();
      await pfSet({ platformSettings: Object.assign(s, { baseUrl: url }) });
      return { ok: true, message: "平台地址已切到 " + url };
    }
    case "platformFlush": {
      const r = await pfFlush();
      return { ok: !r || r.ok !== false, message: (r && r.message) || "已上报" , extra: r };
    }
    case "platformFinal": {
      const r = await pfFinalChunk();
      return { ok: !r || r.ok !== false, message: (r && r.message) || "已补发终片", extra: r };
    }
    case "platformDayFinal": {
      const r = await pfDayFinal();
      return { ok: !r || r.ok !== false, message: (r && r.message) || "日终完成", extra: r };
    }
    case "platformRetry": {
      const r = await pfRetryTasks(true);
      return { ok: true, message: (r && r.message) || "已重试失败任务", extra: r };
    }
    case "platformRunVoid": {
      const r = await pfRunVoid(p.reason || "平台遥控作废");
      return { ok: !r || r.ok !== false, message: (r && r.message) || "本次 run 已作废", extra: r };
    }
    case "platformDefer": {
      const r = await pfDeferRun();
      return { ok: !r || r.ok !== false, message: (r && r.message) || "已暂缓", extra: r };
    }
    case "platformExportTasks": {
      const r = await pfExportTasks();
      const json = (r && r.json) || "[]";
      try {
        await chrome.downloads.download({
          url: "data:application/json;charset=utf-8," + encodeURIComponent(json),
          filename: "platform-failed-tasks-" + Date.now() + ".json",
          saveAs: true
        });
      } catch (e) {}
      return { ok: true, message: "已导出 " + ((r && r.count) || 0) + " 条失败任务" };
    }
    default:
      return { ok: false, message: "未知指令 " + type };
  }
}

async function crExec(cmd) {
  const type = String(cmd.type || "");
  const payload = cmd.payload || {};
  if (type.startsWith("platform")) return await crPlatformAction(type, payload);

  if (type === "scrollLoop") {
    const seconds = Math.max(1, Math.min(600, Number(payload.seconds || 15)));
    return await crDomAction("scrollLoop", { seconds });
  }
  const map = {
    scrollOnce: "scrollOnce",
    scrollStop: "scrollStop",
    top: "top",
    search: "search",
    refresh: "refresh",
    toggleCollect: "toggleCollect",
    clearCache: "clearCache"
  };
  if (map[type]) return await crDomAction(map[type], payload);
  return { ok: false, message: "未知指令 " + type };
}

async function crPoll() {
  const base = await crBaseUrl();
  const deviceId = await crDeviceId();
  let list = [];
  try {
    const res = await fetch(base + "/api/v1/collect/command/pending?deviceId=" + encodeURIComponent(deviceId), {
      headers: await crHeaders()
    });
    const j = await res.json();
    list = (j && j.data) || [];
  } catch (e) {
    return;
  }
  for (const cmd of list) {
    if (crBusyCommand === cmd.id) continue;
    crBusyCommand = cmd.id;
    let r;
    try {
      r = await crExec(cmd);
    } catch (e) {
      r = { ok: false, message: String((e && e.message) || e) };
    }
    try {
      await fetch(base + "/api/v1/collect/command/ack", {
        method: "POST",
        headers: await crHeaders(),
        body: JSON.stringify({
          id: cmd.id,
          deviceId: deviceId,
          ok: !!(r && r.ok),
          message: (r && r.message) || ""
        })
      });
    } catch (e) {}
    crBusyCommand = null;
  }
}

async function crLoop() {
  if (crLoopRunning) return;
  crLoopRunning = true;
  while (true) {
    try {
      await crPoll();
      await crHeartbeat();
      await crFrame();
    } catch (e) {}
    const s = await crSettings();
    await crSleep(Math.max(1.5, Number(s.pollSec) || 2.5) * 1000);
  }
}

function crEnsureAlarm() {
  try {
    chrome.alarms.create(CR_ALARM, { periodInMinutes: 0.5 });
  } catch (e) {}
}

chrome.alarms.onAlarm.addListener((a) => {
  if (a && a.name === CR_ALARM) crLoop();
});
chrome.runtime.onInstalled.addListener(() => {
  crEnsureAlarm();
  crLoop();
});
chrome.runtime.onStartup.addListener(() => {
  crEnsureAlarm();
  crLoop();
});

/* Service Worker 每次被唤醒都会重新执行顶层代码，这里直接拉起循环 */
crEnsureAlarm();
crLoop();
