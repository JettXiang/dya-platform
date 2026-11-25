/* 1.3.9 平台上报（登录/配置、心跳、分片、日终、投递台账、任务状态机） */
const PF_BASE_DEFAULT = "http://10.1.2.60:19203";
const PF_ALARM = "platform-tick";
const PF_TICK_MIN = 1;
const PF_CONFIG_EVERY_MS = 30 * 60 * 1000;
const PF_FLUSH_IDLE_MS = 60 * 1000;
const PF_BACKOFF_MS = [1000, 2000, 4000, 8000, 16000, 32000];
const PF_MAX_ATTEMPTS = 6;
const PF_PENDING_STATES = ["queued", "retrying", "blocked", "conflict"];

const PF_KEYS = {
  settings: "platformSettings",
  auth: "platformAuth",
  cfg: "platformCfg",
  run: "platformRun",
  stats: "platformStats",
  tasks: "platformTasks",
  ledger: "platformLedger",
  dayFinal: "platformDayFinal",
  dayRuns: "platformDayRuns",
  recovery: "platformRecovery",
  deferred: "platformDeferred"
};

function pfPad2(n) { return n < 10 ? "0" + n : String(n); }

async function pfGet(keys) { return await chrome.storage.local.get(keys); }
async function pfSet(obj) { return await chrome.storage.local.set(obj); }

function pfBusinessDate() {
  // 业务日统一 Asia/Shanghai
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit"
  }).format(new Date());
}

function pfCanonical(value) {
  if (value === null || typeof value !== "object") return JSON.stringify(value);
  if (Array.isArray(value)) return "[" + value.map(pfCanonical).join(",") + "]";
  const keys = Object.keys(value).sort();
  return "{" + keys.map((k) => JSON.stringify(k) + ":" + pfCanonical(value[k])).join(",") + "}";
}

async function pfSha256(text) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(buf)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

function pfJwtExpMs(token) {
  try {
    const seg = String(token || "").split(".")[1];
    const json = JSON.parse(atob(seg.replace(/-/g, "+").replace(/_/g, "/")));
    return json && json.exp ? json.exp * 1000 : 0;
  } catch (e) {
    return 0;
  }
}

async function pfSettings() {
  const d = await pfGet([PF_KEYS.settings]);
  return Object.assign({ enabled: false, accountCode: "", baseUrl: PF_BASE_DEFAULT }, d[PF_KEYS.settings] || {});
}

async function pfAuth() {
  const d = await pfGet([PF_KEYS.auth]);
  return d[PF_KEYS.auth] || null;
}

async function pfCfg() {
  const d = await pfGet([PF_KEYS.cfg]);
  return Object.assign({ heartbeatSec: 90, chunkMaxItems: 50, at: 0 }, d[PF_KEYS.cfg] || {});
}

async function pfStats() {
  const d = await pfGet([PF_KEYS.stats]);
  return Object.assign({ lastFlushAt: 0, lastHeartbeatAt: 0, sentChunks: 0, pendingItems: 0, lastError: "" }, d[PF_KEYS.stats] || {});
}

async function pfPatchStats(patch) {
  const stats = await pfStats();
  await pfSet({ [PF_KEYS.stats]: Object.assign(stats, patch) });
}

async function pfRun() {
  const d = await pfGet([PF_KEYS.run]);
  return d[PF_KEYS.run] || null;
}

async function pfLogin(code, password) {
  const s = await pfSettings();
  const r = await fetch(s.baseUrl + "/api/v1/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code, password })
  });
  const body = await r.json().catch(() => null);
  if (!r.ok || !body || body.success !== true) {
    return { ok: false, message: (body && body.message) || ("登录失败 HTTP " + r.status) };
  }
  const data = body.data || {};
  const expMs = pfJwtExpMs(data.accessToken) || Date.now() + 8 * 3600 * 1000;
  await pfSet({
    [PF_KEYS.auth]: {
      accessToken: data.accessToken,
      refreshToken: data.refreshToken,
      expiresAt: expMs,
      accountCode: code
    },
    [PF_KEYS.settings]: Object.assign(s, {
      userCode: code,
      accountCode: s.accountCode || (code + "-A1")
    })
  });
  await pfRefreshConfig(true);
  return { ok: true, accountCode: code };
}

async function pfRefreshToken() {
  const auth = await pfAuth();
  if (!auth || !auth.refreshToken) return false;
  const s = await pfSettings();
  const r = await fetch(s.baseUrl + "/api/v1/auth/refresh", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken: auth.refreshToken })
  });
  const body = await r.json().catch(() => null);
  if (!r.ok || !body || body.success !== true) return false;
  const data = body.data || {};
  const expMs = pfJwtExpMs(data.accessToken) || Date.now() + 8 * 3600 * 1000;
  await pfSet({
    [PF_KEYS.auth]: Object.assign(auth, {
      accessToken: data.accessToken,
      refreshToken: data.refreshToken || auth.refreshToken,
      expiresAt: expMs
    })
  });
  return true;
}

async function pfCall(path, options, allowRefresh) {
  const auth = await pfAuth();
  if (!auth || !auth.accessToken) return { ok: false, message: "未登录" };
  const s = await pfSettings();
  const headers = Object.assign({}, (options && options.headers) || {});
  headers.Authorization = "Bearer " + auth.accessToken;
  let r;
  try {
    r = await fetch(s.baseUrl + path, Object.assign({}, options, { headers }));
  } catch (e) {
    return { ok: false, status: 0, message: "网络异常：" + ((e && e.message) || String(e)) };
  }
  if (r.status === 401 && allowRefresh !== false) {
    if (await pfRefreshToken()) return await pfCall(path, options, false);
    return { ok: false, message: "登录已失效，请重新登录" };
  }
  const body = await r.json().catch(() => null);
  if (!r.ok) {
    return { ok: false, status: r.status, message: (body && (body.message || body.error)) || ("HTTP " + r.status), body };
  }
  if (!body || body.success !== true) {
    return { ok: false, status: r.status, message: (body && body.message) || "服务端返回失败" };
  }
  return { ok: true, data: body.data, status: r.status };
}

async function pfRefreshConfig(force) {
  const cfg = await pfCfg();
  if (!force && Date.now() - cfg.at < PF_CONFIG_EVERY_MS) return cfg;
  const r = await pfCall("/api/v1/config/extension", { method: "GET" });
  if (!r.ok) return cfg;
  const next = Object.assign(cfg, {
    heartbeatSec: Number(r.data.heartbeat_sec) || 90,
    chunkMaxItems: Number(r.data.chunk_max_items) || 50,
    retryHint: r.data.retry_hint || "",
    at: Date.now()
  });
  await pfSet({ [PF_KEYS.cfg]: next });
  return next;
}

async function pfHeartbeat() {
  const s = await pfSettings();
  if (!s.enabled || !s.accountCode) return;
  const cfg = await pfCfg();
  const stats = await pfStats();
  if (Date.now() - stats.lastHeartbeatAt < cfg.heartbeatSec * 1000) return;
  const run = await pfRun();
  const r = await pfCall("/api/v1/ingest/heartbeat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      accountCode: s.accountCode,
      state: run ? "collecting" : "idle",
      runId: run ? run.runId : "",
      keyword: run ? (run.keyword || "") : "",
      scanned: Number(stats.scanned) || 0,
      matched: Number(stats.matched) || 0
    })
  });
  if (r.ok) {
    await pfPatchStats({ lastHeartbeatAt: Date.now(), lastError: "" });
  } else {
    await pfPatchStats({ lastError: r.message });
  }
}

async function pfEnqueueMatch(item) {
  const s = await pfSettings();
  if (!s.enabled || !s.accountCode || !item || !item.secUid) return;
  const run = await pfRun();
  const now = Date.now();
  const next = run && run.businessDate === pfBusinessDate() && !run.finalSent
    ? run
    : {
        runId: "r-" + now + "-" + Math.random().toString(16).slice(2, 6),
        businessDate: pfBusinessDate(),
        seq: 0,
        totalItems: 0,
        startedAt: now,
        keyword: "",
        finalSent: false,
        items: []
      };
  if (!next.items) next.items = [];
  if (!next.items.some((x) => x.secUid === item.secUid)) {
    next.items.push({ secUid: item.secUid, hasMix: !!item.hasMix, hasAi: !!item.hasAi });
    next.totalItems = (Number(next.totalItems) || 0) + 1;
  }
  await pfSet({ [PF_KEYS.run]: next, [PF_KEYS.stats]: Object.assign(await pfStats(), { pendingItems: next.items.length }) });
}

/* ---------- 任务、台账、业务日 run 索引 ---------- */
function pfRand4() { return Math.random().toString(16).slice(2, 6); }

async function pfTasks() {
  const d = await pfGet([PF_KEYS.tasks]);
  return Array.isArray(d[PF_KEYS.tasks]) ? d[PF_KEYS.tasks] : [];
}

async function pfSaveTasks(list) { await pfSet({ [PF_KEYS.tasks]: list }); }

async function pfPendingBreakdown() {
  const tasks = await pfTasks();
  const out = { queued: 0, retrying: 0, blocked: 0, conflict: 0, cancelled: 0, delivered: 0 };
  for (const t of tasks) if (out[t.state] !== undefined) out[t.state] += 1;
  out.total = out.queued + out.retrying + out.blocked + out.conflict;
  return out;
}

async function pfDayRunsAll() {
  const d = await pfGet([PF_KEYS.dayRuns]);
  return d[PF_KEYS.dayRuns] || {};
}

async function pfTouchDayRun(date, runId, patch, addChunk) {
  const all = await pfDayRunsAll();
  const day = all[date] || {};
  const cur = day[runId] || { run: runId, total: 0, finalSeq: null, chunks: 0 };
  Object.assign(cur, patch || {});
  if (addChunk) cur.chunks = (Number(cur.chunks) || 0) + 1;
  day[runId] = cur;
  all[date] = day;
  await pfSet({ [PF_KEYS.dayRuns]: all });
  return cur;
}

async function pfLedger() {
  const d = await pfGet([PF_KEYS.ledger]);
  return Array.isArray(d[PF_KEYS.ledger]) ? d[PF_KEYS.ledger] : [];
}

async function pfLedgerAppend(rec) {
  const list = await pfLedger();
  const item = Object.assign({ id: "dl-" + Date.now() + "-" + pfRand4(), at: Date.now(), state: "delivered" }, rec);
  list.push(item);
  await pfSet({ [PF_KEYS.ledger]: list });
  return item;
}

/* 旧通道 /api/file 业务成功后由 background 调用；只追加，不改旧记录 */
async function pfLegacyDelivered(info) {
  const s = await pfSettings();
  return await pfLedgerAppend({
    scope: "legacy_batch",
    date: pfBusinessDate(),
    accountCode: s.accountCode || "",
    batchCount: Number((info && info.count) || 0),
    inserted: Number((info && info.inserted) || 0),
    dup: Number((info && info.dup) || 0),
    source: "/api/file"
  });
}

async function pfLedgerDay(date) {
  const list = await pfLedger();
  const day = list.filter((x) => x.date === date && x.state === "delivered");
  const platformDay = day.some((x) => x.scope === "platform_day");
  const legacy = day.some((x) => x.scope === "legacy_batch");
  return {
    platform_chunks: day.filter((x) => x.scope === "platform_chunk").length,
    platform_day: platformDay,
    legacy_batch: legacy,
    legacy_missing: platformDay && !legacy
  };
}

/* 失败分类：只有 5xx/断网进 retrying，其余按契约落 blocked/conflict */
function pfErrorState(r) {
  const status = (r && r.status) || 0;
  const err = (r && r.body && r.body.error) || "";
  if (status === 409) {
    if (err === "chunk_payload_conflict" || err === "run_business_date_conflict" || err === "day_final_conflict") return "conflict";
    return "blocked";
  }
  if (status === 401 || status === 403 || status === 400) return "blocked";
  return "retrying";
}

async function pfPutTask(task) {
  const list = await pfTasks();
  const idx = list.findIndex((x) => x.id === task.id);
  if (idx >= 0) list[idx] = Object.assign(list[idx], task);
  else list.push(task);
  await pfSaveTasks(list);
  return task;
}

async function pfAfterChunkDelivered(date, runId, seq, count, total, finalSeq) {
  const s = await pfSettings();
  await pfLedgerAppend({
    scope: "platform_chunk",
    date: date,
    accountCode: s.accountCode || "",
    runId: runId,
    chunkSeq: seq,
    itemCount: count,
    finalChunk: finalSeq != null,
    finalSeq: finalSeq == null ? null : finalSeq
  });
  await pfTouchDayRun(date, runId, finalSeq != null ? { total: total, finalSeq: finalSeq } : { total: total }, true);
  const stats = await pfStats();
  await pfSet({
    [PF_KEYS.stats]: Object.assign(stats, {
      lastFlushAt: Date.now(), sentChunks: (Number(stats.sentChunks) || 0) + 1,
      lastError: ""
    })
  });
}

async function pfHandleChunkError(run, seq, payload, r) {
  const state = pfErrorState(r);
  const task = {
    id: "chunk:" + run.runId + ":" + seq,
    kind: "chunk",
    runId: run.runId,
    date: run.businessDate,
    seq: seq,
    state: state,
    attempts: 0,
    nextAt: state === "retrying" ? Date.now() + PF_BACKOFF_MS[0] : 0,
    payload: payload,
    version: 0,
    lastError: (r && r.message) || "发送失败",
    createdAt: Date.now()
  };
  await pfPutTask(task);
  await pfSet({ [PF_KEYS.run]: Object.assign(run, {
    seq: seq, items: [], pendingSeq: seq,
    halted: state === "conflict" || state === "blocked"
  }) });
  await pfPatchStats({ lastError: task.lastError, pendingItems: 0 });
  return state;
}

/* 分片去重：同 secUid 只留一条，flag 取并集，按 secUid 升序 */
function pfChunkItems(rawItems) {
  const map = new Map();
  for (const item of (rawItems || [])) {
    if (!item || !item.secUid) continue;
    const cur = map.get(item.secUid);
    if (cur) {
      cur.hasMix = !!cur.hasMix || !!item.hasMix;
      cur.hasAi = !!cur.hasAi || !!item.hasAi;
    } else {
      map.set(item.secUid, { secUid: item.secUid, hasMix: !!item.hasMix, hasAi: !!item.hasAi });
    }
  }
  return Array.from(map.values()).sort((a, b) => (a.secUid < b.secUid ? -1 : 1));
}

async function pfFlush() {
  const s = await pfSettings();
  if (!s.enabled || !s.accountCode) return { ok: false, message: "未启用" };
  const run = await pfRun();
  if (!run || !run.items || !run.items.length) return { ok: true, skipped: true };
  if (run.finalSent) return { ok: true, skipped: true };
  const tasks = await pfTasks();
  if (tasks.some((t) => t.kind === "chunk" && t.runId === run.runId && PF_PENDING_STATES.indexOf(t.state) >= 0)) {
    return { ok: false, message: "存在未送达分片，先重试或处理该 run" };
  }
  const seq = (run.seq || 0) + 1;
  const items = pfChunkItems(run.items);
  const total = Number(run.totalItems) || items.length;
  /* 哈希字段严格按 v2：v/account/run/seq/final/finalSeq/total/items，不含业务日 */
  const payloadHash = await pfSha256(pfCanonical({
    v: 2, account: s.accountCode, run: run.runId, seq: seq,
    final: false, finalSeq: null, total: total, items: items
  }));
  const payload = {
    accountCode: s.accountCode,
    clientRunId: run.runId,
    businessDate: run.businessDate,
    chunkSeq: seq,
    finalChunk: false,
    finalSeq: null,
    totalCount: total,
    payloadHash: payloadHash,
    items: items
  };
  const r = await pfCall("/api/v1/ingest/chunk", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
  if (!r.ok) {
    const state = await pfHandleChunkError(run, seq, payload, r);
    return { ok: false, state: state, message: r.message };
  }
  await pfSet({ [PF_KEYS.run]: Object.assign(run, { seq: seq, items: [], pendingSeq: null, halted: false }) });
  await pfAfterChunkDelivered(run.businessDate, run.runId, seq, items.length, total, null);
  return { ok: true, seq: seq, count: items.length, duplicated: !!(r.data && r.data.duplicated) };
}

async function pfFinalChunk() {
  const s = await pfSettings();
  if (!s.enabled || !s.accountCode) return { ok: false, message: "未启用" };
  const run = await pfRun();
  if (!run) return { ok: true, skipped: true, message: "没有进行中的 run" };
  if (run.finalSent) return { ok: true, already: true, message: "该 run 已发终片" };
  const tasks = await pfTasks();
  if (tasks.some((t) => t.kind === "chunk" && t.runId === run.runId && PF_PENDING_STATES.indexOf(t.state) >= 0)) {
    return { ok: false, message: "存在未送达分片，先处理后再发终片" };
  }
  const seq = (run.seq || 0) + 1;
  const items = pfChunkItems(run.items);
  const total = Number(run.totalItems) || items.length;
  const payloadHash = await pfSha256(pfCanonical({
    v: 2, account: s.accountCode, run: run.runId, seq: seq,
    final: true, finalSeq: seq, total: total, items: items
  }));
  const payload = {
    accountCode: s.accountCode,
    clientRunId: run.runId,
    businessDate: run.businessDate,
    chunkSeq: seq,
    finalChunk: true,
    finalSeq: seq,
    totalCount: total,
    payloadHash: payloadHash,
    items: items
  };
  const r = await pfCall("/api/v1/ingest/chunk", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
  if (!r.ok) {
    const state = await pfHandleChunkError(run, seq, payload, r);
    return { ok: false, state: state, message: r.message };
  }
  await pfSet({ [PF_KEYS.run]: Object.assign(run, {
    seq: seq, items: [], finalSent: true, finalSeq: seq, pendingSeq: null, halted: false
  }) });
  await pfAfterChunkDelivered(run.businessDate, run.runId, seq, items.length, total, seq);
  return { ok: true, seq: seq, count: items.length, final: true };
}

async function pfRetryTasks(force) {
  const tasks = await pfTasks();
  let changed = false;
  for (const t of tasks) {
    if (t.kind !== "chunk") continue;
    if (t.state !== "retrying" && t.state !== "queued") continue;
    if (!force && (t.nextAt || 0) > Date.now()) continue;
    const r = await pfCall("/api/v1/ingest/chunk", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(t.payload)
    });
    if (r.ok) {
      t.state = "delivered";
      t.deliveredAt = Date.now();
      t.lastError = "";
      await pfAfterChunkDelivered(t.date, t.runId, t.seq, (t.payload.items || []).length,
        Number(t.payload.totalCount) || 0, t.payload.finalSeq == null ? null : t.payload.finalSeq);
      const run = await pfRun();
      if (run && run.runId === t.runId) {
        await pfSet({ [PF_KEYS.run]: Object.assign(run, {
          finalSent: !!t.payload.finalChunk, finalSeq: t.payload.finalChunk ? t.seq : run.finalSeq,
          pendingSeq: null, halted: false
        }) });
      }
      changed = true;
    } else {
      t.attempts = (Number(t.attempts) || 0) + 1;
      t.lastError = r.message || "";
      const st = pfErrorState(r);
      if (st !== "retrying") t.state = st;
      else if (t.attempts >= PF_MAX_ATTEMPTS) t.state = "blocked";
      else t.nextAt = Date.now() + PF_BACKOFF_MS[Math.min(t.attempts - 1, PF_BACKOFF_MS.length - 1)];
      changed = true;
    }
  }
  if (changed) await pfSaveTasks(tasks);
  return { ok: true };
}

/* ---------- 日终 ---------- */
async function pfDayFinalState() {
  const d = await pfGet([PF_KEYS.dayFinal]);
  return d[PF_KEYS.dayFinal] || null;
}

async function pfBuildDayFinal(date, revision) {
  const s = await pfSettings();
  const all = await pfDayRunsAll();
  const runs = Object.values(all[date] || {}).map((x) => ({
    run: x.run,
    total: Number(x.total) || 0,
    finalSeq: x.finalSeq == null ? null : Number(x.finalSeq),
    chunks: Number(x.chunks) || 0
  })).sort((a, b) => (a.run < b.run ? -1 : 1));
  const bd = await pfPendingBreakdown();
  const pending = { queued: bd.queued, retrying: bd.retrying, blocked: bd.blocked, conflict: bd.conflict };
  const contentHash = await pfSha256(pfCanonical({
    v: 2, account: s.accountCode, date: date, runs: runs,
    pending: { queued: pending.queued, retrying: pending.retrying, blocked: pending.blocked, conflict: pending.conflict }
  }));
  return {
    payload: {
      accountCode: s.accountCode, date: date,
      expectedRuns: runs.length, expectedRunIds: runs.map((x) => x.run),
      clientPendingChunks: bd.total, contentHash: contentHash, revision: revision
    },
    runs: runs, pending: pending, pendingTotal: bd.total
  };
}

async function pfDayFinal() {
  const s = await pfSettings();
  if (!s.enabled || !s.accountCode) return { ok: false, message: "未启用" };
  const date = pfBusinessDate();
  let revision = 1;
  for (let attempt = 0; attempt <= 2; attempt++) {
    const built = await pfBuildDayFinal(date, revision);
    const r = await pfCall("/api/v1/ingest/day-final", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(built.payload)
    });
    if (r.ok) {
      const data = r.data || {};
      const result = data.result === "accepted" ? "accepted" : "incomplete";
      const record = {
        date: date, revision: Number(data.revision) || revision, state: result,
        complete: !!data.complete,
        missing: data.missing_runs || [], unexpected: data.unexpected_runs || [],
        voided: data.voided_runs || [], pending: built.pending, at: Date.now()
      };
      if (result === "accepted" && record.complete && built.pendingTotal === 0) {
        await pfLedgerAppend({
          scope: "platform_day", date: date, accountCode: s.accountCode,
          runIds: built.runs.map((x) => x.run),
          chunks: built.runs.reduce((n, x) => n + x.chunks, 0),
          pending: built.pending
        });
        record.ledger = "platform_day";
      }
      await pfSet({ [PF_KEYS.dayFinal]: record });
      const led = await pfLedgerDay(date);
      return {
        ok: true, result: result, complete: record.complete, revision: record.revision,
        missing: record.missing, unexpected: record.unexpected, voided: record.voided,
        ledger: led
      };
    }
    const err = (r.body && r.body.error) || "";
    if (r.status === 409 && err === "day_final_conflict" && attempt < 2) {
      revision += 1;
      continue;
    }
    await pfSet({ [PF_KEYS.dayFinal]: {
      date: date, revision: revision, state: err || "blocked",
      complete: false, at: Date.now(), lastError: r.message || ""
    } });
    return { ok: false, status: r.status, message: r.message || "日终失败" };
  }
  return { ok: false, status: 409, message: "日终 revision 多次冲突，已置 blocked" };
}

/* endToday：终片 → 重试到期任务 → day-final/台账；清列表由 background 继续做 */
async function pfEndToday() {
  const s = await pfSettings();
  if (!s.enabled || !s.accountCode) return { ok: true, skipped: true };
  await pfRetryTasks(true);
  const run = await pfRun();
  const final = run && !run.finalSent ? await pfFinalChunk() : null;
  const day = await pfDayFinal();
  return { ok: true, final: final, day: day };
}

/* ---------- run 作废 / 暂缓 ---------- */
async function pfRunVoid(reason) {
  const s = await pfSettings();
  if (!s.enabled || !s.accountCode) return { ok: false, message: "未启用" };
  const run = await pfRun();
  if (!run) return { ok: false, message: "没有进行中的 run" };
  const payload = {
    accountCode: s.accountCode, clientRunId: run.runId, businessDate: run.businessDate,
    reason: String(reason || "").trim() || "人工作废",
    clientRequestId: "void-" + run.runId
  };
  const r = await pfCall("/api/v1/ingest/run-void", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
  if (!r.ok) return { ok: false, status: r.status, message: r.message };
  const tasks = await pfTasks();
  for (const t of tasks) {
    if (t.runId === run.runId && PF_PENDING_STATES.indexOf(t.state) >= 0) {
      t.state = "cancelled";
      t.cancelReason = "run_void";
      t.cancelledAt = Date.now();
    }
  }
  await pfSaveTasks(tasks);
  await pfSet({ [PF_KEYS.run]: null, [PF_KEYS.recovery]: null });
  await pfPatchStats({ lastError: "", pendingItems: 0 });
  return { ok: true, runId: run.runId, message: "已作废该 run，放弃剩余数据，已入库来源不删除" };
}

async function pfDeferRun() {
  const run = await pfRun();
  if (!run) return { ok: false, message: "没有进行中的 run" };
  const d = await pfGet([PF_KEYS.deferred]);
  const list = Array.isArray(d[PF_KEYS.deferred]) ? d[PF_KEYS.deferred] : [];
  list.push({
    runId: run.runId, date: run.businessDate, deferredAt: Date.now(),
    items: run.items || [], totalItems: Number(run.totalItems) || 0, seq: run.seq || 0
  });
  await pfSet({ [PF_KEYS.deferred]: list, [PF_KEYS.run]: null, [PF_KEYS.recovery]: null });
  return { ok: true, message: "已暂缓该 run，不阻塞新 run" };
}

async function pfExportTasks() {
  const tasks = await pfTasks();
  const list = tasks.filter((t) => t.state === "blocked" || t.state === "conflict");
  return { ok: true, count: list.length, tasks: list, json: JSON.stringify(list, null, 2) };
}

async function pfTick() {
  const s = await pfSettings();
  if (!s.enabled) return;
  await pfRefreshConfig(false);
  await pfHeartbeat();
  await pfRetryTasks(false);
  const cfg = await pfCfg();
  const run = await pfRun();
  const stats = await pfStats();
  const due = run && run.items && run.items.length >= cfg.chunkMaxItems
    || (run && run.items && run.items.length > 0 && Date.now() - (stats.lastFlushAt || 0) >= PF_FLUSH_IDLE_MS);
  if (due) await pfFlush();
}

async function pfEnsureAlarm() {
  const a = await chrome.alarms.get(PF_ALARM);
  if (!a) chrome.alarms.create(PF_ALARM, { periodInMinutes: PF_TICK_MIN });
}

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === PF_ALARM) {
    pfTick().catch(() => {});
  }
});

chrome.runtime.onMessage.addListener((msg, sender, respond) => {
  if (!msg || typeof msg.type !== "string" || !msg.type.startsWith("platform")) return false;
  (async () => {
    try {
      if (msg.type === "platformLogin") {
        respond(await pfLogin(msg.code, msg.password));
      } else if (msg.type === "platformSetAccount") {
        const s = await pfSettings();
        await pfSet({ [PF_KEYS.settings]: Object.assign(s, { accountCode: String(msg.accountCode || "").trim() }) });
        respond({ ok: true });
      } else if (msg.type === "platformSetEnabled") {
        const s = await pfSettings();
        await pfSet({ [PF_KEYS.settings]: Object.assign(s, { enabled: !!msg.enabled }) });
        respond({ ok: true });
      } else if (msg.type === "platformMatch") {
        await pfEnqueueMatch(msg.item);
        respond({ ok: true });
      } else if (msg.type === "platformFlush") {
        respond(await pfFlush());
      } else if (msg.type === "platformFinal") {
        respond(await pfFinalChunk());
      } else if (msg.type === "platformDayFinal") {
        respond(await pfDayFinal());
      } else if (msg.type === "platformEndToday") {
        respond(await pfEndToday());
      } else if (msg.type === "platformRetry") {
        respond(await pfRetryTasks(true));
      } else if (msg.type === "platformRunVoid") {
        respond(await pfRunVoid(msg.reason));
      } else if (msg.type === "platformDefer") {
        respond(await pfDeferRun());
      } else if (msg.type === "platformExportTasks") {
        respond(await pfExportTasks());
      } else if (msg.type === "platformStatus") {
        const s = await pfSettings();
        const cfg = await pfCfg();
        const run = await pfRun();
        const stats = await pfStats();
        const auth = await pfAuth();
        const bd = await pfPendingBreakdown();
        const dayFinal = await pfDayFinalState();
        const led = await pfLedgerDay(pfBusinessDate());
        const dd = await pfGet([PF_KEYS.deferred]);
        respond({
          ok: true,
          enabled: s.enabled,
          accountCode: s.accountCode,
          userCode: s.userCode || "",
          baseUrl: s.baseUrl,
          loggedIn: !!(auth && auth.accessToken),
          heartbeatSec: cfg.heartbeatSec,
          chunkMaxItems: cfg.chunkMaxItems,
          pendingItems: (run && run.items.length) || 0,
          pendingChunks: bd.total,
          retrying: bd.retrying,
          blocked: bd.blocked,
          conflict: bd.conflict,
          sentChunks: stats.sentChunks || 0,
          run: run ? {
            runId: run.runId, businessDate: run.businessDate,
            finalSent: !!run.finalSent, halted: !!run.halted
          } : null,
          dayFinal: dayFinal ? {
            date: dayFinal.date, state: dayFinal.state, complete: !!dayFinal.complete,
            revision: dayFinal.revision
          } : null,
          ledger: led,
          deferredRuns: Array.isArray(dd[PF_KEYS.deferred]) ? dd[PF_KEYS.deferred].length : 0,
          lastError: stats.lastError || ""
        });
      } else {
        respond({ ok: false, message: "unknown platform message" });
      }
    } catch (e) {
      respond({ ok: false, message: (e && e.message) || String(e) });
    }
  })();
  return true;
});

pfEnsureAlarm().catch(() => {});
