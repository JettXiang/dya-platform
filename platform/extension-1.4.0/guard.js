/* 故障记录页 · 数据来自 chrome.storage.local 的 guardState / guardIndex / guardEvent:*
 * 支持单条事件「结案」（经 background 消息队列写库）。
 */
const STATE_CLASS = {
  "正常": "s-normal",
  "暂停观察": "s-observe",
  "退避重试": "s-backoff",
  "待人工确认": "s-manual"
};

function esc(s) {
  return String(s == null ? "" : s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function pad2(n) { return n < 10 ? "0" + n : String(n); }

function fmt(ts) {
  if (!ts) return "-";
  const d = new Date(ts);
  return d.getFullYear() + "-" + pad2(d.getMonth() + 1) + "-" + pad2(d.getDate()) +
    " " + pad2(d.getHours()) + ":" + pad2(d.getMinutes()) + ":" + pad2(d.getSeconds());
}

function dayKey(ts) {
  const d = new Date(ts);
  return d.getFullYear() + "-" + pad2(d.getMonth() + 1) + "-" + pad2(d.getDate());
}

async function readAll() {
  const d = await chrome.storage.local.get(["guardState", "guardIndex"]);
  const index = d.guardIndex || {};
  const ids = Object.keys(index);
  const keys = ids.map((id) => "guardEvent:" + id);
  const evData = keys.length ? await chrome.storage.local.get(keys) : {};
  const events = ids
    .map((id) => evData["guardEvent:" + id])
    .filter(Boolean)
    .sort((a, b) => (b.ts || 0) - (a.ts || 0));
  return { guardState: d.guardState || null, index: index, events: events };
}

function renderState(gs) {
  const box = document.getElementById("stateBox");
  if (!gs) { box.innerHTML = '<span class="meta">暂无守卫状态记录</span>'; return; }
  const cls = STATE_CLASS[gs.state] || "";
  box.innerHTML =
    '<span class="badge ' + cls + '">' + esc(gs.state || "未知") + '</span>' +
    '<span class="meta">当前事件 #' + esc(gs.eventId == null ? "-" : gs.eventId) +
    ' · 更新于 ' + esc(fmt(gs.updatedAt)) + '</span>';
}

function renderKpis(events) {
  let closed = 0;
  events.forEach((e) => { if (e.closedAt) closed++; });
  const last = events.length ? events[0].ts : 0;
  document.getElementById("kpis").innerHTML =
    '<div class="kpi"><b>' + events.length + '</b><span>事件总数</span></div>' +
    '<div class="kpi"><b>' + closed + '</b><span>已结案</span></div>' +
    '<div class="kpi"><b>' + (events.length - closed) + '</b><span>未结案</span></div>' +
    '<div class="kpi"><b>' + esc(last ? fmt(last) : "-") + '</b><span>最近一次</span></div>';
}

/* 故障时间线：按天的故障起数分布（取最近 14 个有记录的日期） */
function renderDaily(events) {
  const counts = new Map();
  events.forEach((e) => {
    const k = dayKey(e.ts);
    counts.set(k, (counts.get(k) || 0) + 1);
  });
  const days = Array.from(counts.keys()).sort().slice(-14);
  const host = document.getElementById("daily");
  if (!days.length) { host.innerHTML = '<div class="empty">暂无故障记录</div>'; return; }
  const max = Math.max.apply(null, days.map((d) => counts.get(d)));
  host.innerHTML = days.map((d) => {
    const n = counts.get(d);
    const w = Math.max(6, Math.round(n / max * 100));
    return '<div style="display:flex;align-items:center;gap:10px;margin:5px 0">' +
      '<span class="meta" style="width:86px">' + esc(d) + '</span>' +
      '<div class="bar" style="width:' + w + '%;max-width:420px"></div>' +
      '<span class="meta">' + n + ' 起</span></div>';
  }).join("");
}

function renderEvents(events, index) {
  const host = document.getElementById("events");
  if (!events.length) { host.innerHTML = '<div class="empty">暂无故障记录</div>'; return; }
  host.innerHTML = events.map((ev) => {
    const it = index[ev.id] || {};
    const st = it.state || "-";
    const cls = STATE_CLASS[st] || "";
    const e = ev.evidence || {};
    const status = e.status || 0;
    const hist = e.history || [];

    const head =
      '<div class="row1">' +
      '<span class="badge ' + cls + '">' + esc(st) + '</span>' +
      '<b>#' + esc(ev.id) + '</b>' +
      '<span class="meta">' + esc(fmt(ev.ts)) + '</span>' +
      '<span>' + esc(ev.fingerprint || "-") + '</span>' +
      '<span class="chip">HTTP ' + esc(status || "-") + '</span>' +
      '<span class="chip">重试 ' + esc(ev.retries || 0) + '</span>' +
      (ev.result ? '<span class="chip">' + esc(ev.result) + '</span>' : '') +
      (ev.closedAt ? '<span class="meta">结案 ' + esc(fmt(ev.closedAt)) + '</span>' : '<span class="meta">未结案</span>') +
      (ev.closedAt ? '' : '<button class="mini" data-close="' + esc(ev.id) + '">结案</button>') +
      '</div>' +
      '<div class="meta url">' + esc(ev.url || "-") + '</div>';

    const tl = hist.length
      ? '<ul class="timeline">' + hist.map((h) =>
          '<li><span class="meta">' + esc(fmt(h.ts)) + '</span>' +
          (h.to ? '<span class="chip">' + esc(h.to) + '</span>' : '') +
          (h.result ? '<span class="chip">' + esc(h.result) + '</span>' : '') +
          '</li>').join("") + '</ul>'
      : '<div class="empty">无状态变更记录</div>';

    const detail = {
      source: e.source || "",
      task: ev.task || "",
      status: status,
      bodyHead: e.bodyHead || "",
      headers: e.headers || "",
      url: ev.url || "",
      extVersion: e.extVersion || "",
      manualVersion: e.manualVersion || "",
      result: ev.result || "",
      closedAt: ev.closedAt || null
    };

    return '<details class="ev">' +
      '<summary>' + head + '</summary>' +
      '<div style="margin-top:10px">' + tl +
      '<pre>' + esc(JSON.stringify(detail, null, 2)) + '</pre></div>' +
      '</details>';
  }).join("");
}

async function render() {
  const all = await readAll();
  renderState(all.guardState);
  renderKpis(all.events);
  renderDaily(all.events);
  renderEvents(all.events, all.index);
  document.getElementById("readAt").textContent = "读取于 " + fmt(Date.now());
}

async function exportJson() {
  const all = await readAll();
  const payload = {
    exportedAt: new Date().toISOString(),
    guardState: all.guardState,
    index: all.index,
    events: all.events
  };
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = "guard-events-" + Date.now() + ".json";
  a.click();
  URL.revokeObjectURL(url);
}

document.getElementById("refresh").addEventListener("click", render);
document.getElementById("export").addEventListener("click", exportJson);

/* 事件行「结案」按钮：委托监听（按钮在 <summary> 内，需阻止 details 展开/收起） */
document.getElementById("events").addEventListener("click", async (ev) => {
  const btn = ev.target.closest("button[data-close]");
  if (!btn) return;
  ev.preventDefault();
  ev.stopPropagation();
  const id = btn.getAttribute("data-close");
  if (!id) return;
  if (!confirm("确认把事件 #" + id + " 结案吗？")) return;
  btn.disabled = true;
  try {
    const res = await chrome.runtime.sendMessage({ type: "guardClose", id: id });
    if (res && res.ok) {
      await render();
    } else {
      btn.disabled = false;
      alert("结案失败：" + ((res && res.error) || "未知错误"));
    }
  } catch (err) {
    btn.disabled = false;
    alert("结案失败：" + (err && err.message ? err.message : String(err)));
  }
});

chrome.storage.onChanged.addListener((changes, area) => {
  if (area === "local" && (changes.guardState || changes.guardIndex)) render();
});
render();
