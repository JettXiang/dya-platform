<script setup>
import { computed, onActivated, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, getToken, getUser } from '../api'

/* 采集工作区 · 一站式
 * 平台后端通过 CDP 接管「采集终端」（一台电脑上的专用 Chrome）：
 *   - Page.startScreencast 实时画面回传
 *   - 平台下发滚动/搜索/刷新/采集开关，由那个 Chrome 执行
 *   - 页面加载到的 /aweme/ 接口数据由后端直接解析入库
 * 不再需要浏览器插件。扩展链路（心跳/上报）作为兼容保留。 */

const me = getUser()
const props = defineProps({
  /** 采集槽位：feed = 精选频道，search = 搜索结果页，两者各绑一个抖音标签，互不抢页面 */
  slot: { type: String, default: '' }
})
const workspaceTitle = computed(() => (props.slot === 'feed' ? '精选采集' : props.slot === 'search' ? '搜索采集' : '采集工作区'))
const isAdmin = computed(() => String(me.role || '').toLowerCase() === 'admin')
const roleLabel = computed(() => {
  const r = String(me.role || '').toLowerCase()
  return r === 'admin' ? '管理员' : r === 'collector' ? '采集员' : r === 'uploader' ? '上传员' : '成员'
})

/* ============ 采集终端 ============ */
const terminals = ref([])
const terminalId = ref(localStorage.getItem('dya_terminal') || '')
const current = computed(() => terminals.value.find(t => t.code === terminalId.value) || null)
const online = computed(() => !!(current.value && current.value.online))

/* ============ WebSocket 画面 ============ */
const wsState = ref('idle') // idle | connecting | ready | error
const wsError = ref('')
let ws = null
let loopTimer = null
const loopRunning = ref(false)
const frame = ref('')
/* 最近一次收到画面的时间：后端通道静默失效时用它触发前端重连 */
let lastFrameAt = 0
const pageUrl = ref('')
const ingested = ref(0)
/* 采集口径计数：扫到多少、合集命中、AI 命中、被丢弃 */
const scanned = ref(0)
const mixHits = ref(0)
const aiHits = ref(0)
const droppedCount = ref(0)
const listening = ref(false)
const events = ref([])
const startedAt = ref(0)
const uptime = ref('')

/* ============ 动作参数 ============ */
const keyword = ref('')

/* ============ 推荐词库（10 分钟一批，按人独立） ============ */
const kwOptions = ref([])
const kwRefreshAt = ref(0)
const kwCountdown = ref('')
const kwLoading = ref(false)

async function loadKeywordOptions() {
  if (props.slot !== 'search') return
  kwLoading.value = true
  try {
    const r = await api.randomKeywords(10)
    const d = (r && r.data) || {}
    kwOptions.value = (d.items || []).map((it) => (typeof it === 'string'
      ? { keyword: it, produced: 0 }
      : { keyword: it.keyword, produced: Number(it.produced || 0) }))
    kwRefreshAt.value = Number(d.refreshAt || 0)
  } catch (e) {
    /* 词库暂不可用时仍可手动输入 */
  } finally {
    kwLoading.value = false
  }
}

function useKeyword(word) {
  keyword.value = word
  doSearch()
}

/** 出货量四色：绿 ≥50、蓝 20-49、黄 5-19、红 <5（近 7 天去重入库量） */
function keywordLevel(produced) {
  if (produced >= 50) return 'lv-green'
  if (produced >= 20) return 'lv-blue'
  if (produced >= 5) return 'lv-yellow'
  return 'lv-red'
}

function tickKeywords() {
  if (props.slot !== 'search' || !kwRefreshAt.value) return
  const left = kwRefreshAt.value - Date.now()
  if (left <= 0) {
    kwCountdown.value = '刷新中…'
    kwRefreshAt.value = Date.now() + 60000
    loadKeywordOptions()
    return
  }
  const m = String(Math.floor(left / 60000)).padStart(2, '0')
  const s = String(Math.floor((left % 60000) / 1000)).padStart(2, '0')
  kwCountdown.value = `${m}:${s}`
}

/* ============ 搜索采集下滑上限 ============ */
const SEARCH_SCROLL_LIMIT_MS = 5 * 60 * 1000

/* ============ 终端管理 ============ */
const manageVisible = ref(false)
const formVisible = ref(false)
const editRow = ref(null)
const form = ref({ code: '', name: '', host: '', port: 9222, ownerCode: '', douyinAccount: '', note: '' })
const launcher = ref(null)

/* ============ 抖音登录（一次扫码，长期复用） ============ */
const douyin = ref({ available: false, loggedIn: false })
const douyinBusy = ref(false)
let douyinFastTimer = null
const douyinExpiry = computed(() => {
  if (!douyin.value.expiresAt) return ''
  return new Date(douyin.value.expiresAt).toLocaleDateString('zh-CN')
})

async function loadDouyinStatus() {
  if (!terminalId.value) {
    douyin.value = { available: false, loggedIn: false }
    return
  }
  try {
    const r = await api.cdpDouyinStatus(terminalId.value)
    douyin.value = (r && r.data) || {}
  } catch (e) {
    douyin.value = { available: false, loggedIn: false, message: e.message }
  }
}

async function openDouyinLogin() {
  if (!terminalId.value) {
    ElMessage.warning('先选择采集终端')
    return
  }
  douyinBusy.value = true
  try {
    const r = await api.cdpDouyinLogin(terminalId.value)
    douyin.value = (r && r.data) || douyin.value
    if (douyin.value.message) ElMessage.info(douyin.value.message)
    pushEvent('已唤起抖音登录页，等待扫码')
    if (douyinFastTimer) clearInterval(douyinFastTimer)
    let tries = 0
    douyinFastTimer = setInterval(async () => {
      tries += 1
      await loadDouyinStatus()
      if (douyin.value.loggedIn || tries >= 60) {
        clearInterval(douyinFastTimer)
        douyinFastTimer = null
        if (douyin.value.loggedIn) {
          ElMessage.success('抖音已登录，登录态已写入这台终端')
          pushEvent('抖音登录成功，登录态已持久化')
        }
      }
    }, 5000)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    douyinBusy.value = false
  }
}

/* ============ 每日采集任务 ============ */
function ymd(d = new Date()) {
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}
const today = ymd()
const tasks = ref([])
const taskDialog = ref(false)
const taskForm = ref({ collectorCode: '', targetCount: 30, note: '' })
const collectors = ref([])
let taskRefreshAt = 0

async function loadTasks() {
  try {
    const r = await api.dailyTasks(today)
    tasks.value = (r && r.data && r.data.items) || []
  } catch (e) {
    /* 任务接口异常不影响采集 */
  }
}

function touchTasks() {
  if (Date.now() - taskRefreshAt > 5000) {
    taskRefreshAt = Date.now()
    loadTasks()
  }
}

function taskPercent(t) {
  const target = Number(t.target_count || 0)
  const actual = Number(t.actual_count || 0)
  if (target <= 0) return actual > 0 ? 100 : 0
  return Math.min(100, Math.round((actual * 100) / target))
}

async function openTaskForm() {
  taskDialog.value = true
  taskForm.value = { collectorCode: '', targetCount: 30, note: '' }
  if (collectors.value.length) return
  try {
    const r = await api.users()
    collectors.value = (r.data || []).filter((u) => String(u.role).toLowerCase() === 'collector')
  } catch (e) {
    ElMessage.error('读取采集员列表失败：' + e.message)
  }
}

async function saveTask() {
  const f = taskForm.value
  if (!f.collectorCode) {
    ElMessage.warning('先选采集员')
    return
  }
  try {
    await api.saveDailyTask({ date: today, collectorCode: f.collectorCode, targetCount: f.targetCount, note: f.note })
    ElMessage.success('任务已保存')
    taskDialog.value = false
    await loadTasks()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function removeTask(row) {
  try {
    await ElMessageBox.confirm(`删除「${row.collector_name || row.collector_code}」今日任务？`, '确认删除', { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await api.deleteDailyTask(row.id)
    ElMessage.success('已删除')
    await loadTasks()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function exportReport() {
  try {
    await api.exportCollectionCsv(today)
    ElMessage.success('采集日报已下载')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function onTerminalChange() {
  connect()
  loadDouyinStatus()
  loadTasks()
}

function pushEvent(text, kind = 'info') {
  events.value.unshift({ at: Date.now(), text, kind })
  if (events.value.length > 80) events.value.pop()
}

/* ============ 加载 ============ */
async function loadTerminals() {
  try {
    const r = await api.cdpTerminals()
    terminals.value = (r && r.data) || []
    if (!terminalId.value && terminals.value.length) {
      terminalId.value = terminals.value[0].code
    }
    if (terminalId.value && !terminals.value.some(t => t.code === terminalId.value)) {
      terminalId.value = terminals.value.length ? terminals.value[0].code : ''
    }
  } catch (e) {
    /* 无终端时保持空列表 */
  }
}

async function loadLauncher() {
  try {
    const r = await api.cdpLauncher()
    launcher.value = (r && r.data) || null
  } catch (e) {}
}

/* ============ WS ============ */
/* 断线自动重连：切页/网络抖动后不用手点「连接」。
 * 后端按「终端 + 槽位」常驻采集会话，重连只是换绑画面出口，采集计数不会归零。 */
const RETRY_MAX = 30000
let retryTimer = null
let retryDelay = 3000
let retryAllowed = true

function stopRetry() {
  if (retryTimer) {
    clearTimeout(retryTimer)
    retryTimer = null
  }
}

function scheduleRetry() {
  if (!retryAllowed || !terminalId.value || retryTimer) return
  const delay = retryDelay
  retryDelay = Math.min(RETRY_MAX, retryDelay * 2)
  retryTimer = setTimeout(() => {
    retryTimer = null
    if (retryAllowed && terminalId.value) connect()
  }, delay)
}

function disconnect() {
  stopRetry()
  if (loopTimer) {
    clearTimeout(loopTimer)
    loopTimer = null
  }
  loopRunning.value = false
  if (ws) {
    try {
      ws.onclose = null
      ws.close()
    } catch (e) {}
    ws = null
  }
  wsState.value = 'idle'
  frame.value = ''
  listening.value = false
  startedAt.value = 0
}

function connect() {
  if (!terminalId.value) {
    ElMessage.warning('先选择一个采集终端')
    return
  }
  disconnect()
  stopRetry()
  retryAllowed = true
  retryDelay = 3000
  localStorage.setItem('dya_terminal', terminalId.value)
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
  const url = `${proto}//${location.host}/ws/cdp?token=${encodeURIComponent(getToken())}&terminalId=${encodeURIComponent(terminalId.value)}&slot=${encodeURIComponent(props.slot)}`
  wsState.value = 'connecting'
  wsError.value = ''
  try {
    ws = new WebSocket(url)
  } catch (e) {
    wsState.value = 'error'
    wsError.value = e.message
    return
  }
  ws.onopen = () => {
    pushEvent('已连上采集终端 ' + terminalId.value)
  }
  ws.onmessage = (ev) => {
    let m = {}
    try {
      m = JSON.parse(ev.data)
    } catch (e) {
      return
    }
    if (m.type === 'ready') {
      wsState.value = 'ready'
      lastFrameAt = Date.now()
      pageUrl.value = m.url || ''
      // 后端常驻会话会把采集状态带回来，切页/重连后不会显示「未开始 / 0」
      listening.value = !!m.listening
      ingested.value = Number(m.ingested || 0)
      scanned.value = Number(m.scanned || 0)
      mixHits.value = Number(m.mixHits || 0)
      aiHits.value = Number(m.aiHits || 0)
      droppedCount.value = Number(m.dropped || 0)
      if (!startedAt.value) startedAt.value = Date.now()
      pushEvent('画面通道已建立 ' + (m.endpoint || '') + (m.listening ? '（采集恢复中）' : ''))
      // 下滑是由前端定时器驱动的：切页/重连回来后后端可能仍在采集，
      // 这里把下滑循环接上，否则界面显示「采集进行中」但页面其实不动。
      if (m.listening && !loopTimer) scrollLoop()
    } else if (m.type === 'frame') {
      lastFrameAt = Date.now()
      frame.value = 'data:image/jpeg;base64,' + (m.data || '')
    } else if (m.type === 'state') {
      pageUrl.value = m.url || pageUrl.value
      listening.value = !!m.listening
    } else if (m.type === 'ingested') {
      ingested.value = Number(m.total || 0)
      scanned.value = Number(m.scanned || scanned.value)
      mixHits.value = Number(m.mixHits || mixHits.value)
      aiHits.value = Number(m.aiHits || aiHits.value)
      droppedCount.value = Number(m.dropped || droppedCount.value)
      if (Number(m.inserted) > 0) {
        pushEvent(`入库 +${m.inserted}（命中 ${m.count}，扫描 ${m.scanned}）`)
        touchTasks()
      }
    } else if (m.type === 'eval') {
      if (m.value && String(m.value).startsWith('filter')) {
        pushEvent('搜索页筛选项：' + String(m.value).replace('filter:', ''))
      }
    } else if (m.type === 'toast') {
      ElMessage.info(m.message || '')
    } else if (m.type === 'error') {
      wsState.value = 'error'
      wsError.value = m.message || '采集终端异常'
      retryAllowed = false
      ElMessage.error(wsError.value)
      pushEvent(wsError.value, 'bad')
    }
  }
  ws.onerror = () => {
    wsState.value = 'error'
    wsError.value = 'WebSocket 连接失败'
  }
  ws.onclose = (ev) => {
    // 4001 = 该槽位被另一个窗口接管：停止自动重连，避免两个页面互相顶掉刷屏
    if (ev && ev.code === 4001) {
      retryAllowed = false
      wsState.value = 'idle'
      pushEvent('该采集终端已在另一个窗口打开，本页停止自动重连', 'bad')
      ElMessage.warning('这个终端已在另一个窗口打开，本页已停止自动重连；要用这里请点「连接」')
      return
    }
    if (wsState.value !== 'error') wsState.value = 'idle'
    listening.value = false
    scheduleRetry()
  }
}

function send(msg) {
  if (!ws || ws.readyState !== 1) {
    ElMessage.warning('还没连上采集终端')
    return false
  }
  ws.send(JSON.stringify(msg))
  return true
}

/* ============ 动作 ============ */
function scrollOnce() {
  send({ type: 'wheel', x: 640, y: 420, deltaY: 900 })
}

function scrollTop() {
  send({ type: 'eval', expression: 'window.scrollTo({top:0,behavior:"instant"}); "ok"' })
}

/* ============ 采集行为对齐 v1.4.0 ============ */
/* 搜索页：进页面先点「筛选 → 最新发布 → 视频」，每滚 30 次换一个词（5 分钟冷却）
 * 精选页：每滚 40 次在「全部/二次元/小剧场/影视」间切栏目 */
const CHANNELS = ['全部', '二次元', '小剧场', '影视']
const SWITCH_SEARCH_AFTER = 30
const SWITCH_FEED_AFTER = 40
const SWITCH_COOLDOWN_MS = 5 * 60 * 1000
let scrollCount = 0
let channelIndex = 0
let lastSwitchAt = 0
/* 搜索页两条自动规则：
 * 1. 停止下滑后 3 分钟没有任何搜索词操作 → 自动出词并开始下滑
 * 2. 下滑过程中连续 1 分钟没有新内容 → 自动换词 */
const AUTO_SEARCH_IDLE_MS = 3 * 60 * 1000
const NO_PROGRESS_ROTATE_MS = 60 * 1000
let lastKeywordActionAt = Date.now()
let lastProgressAt = Date.now()
const activeChannel = ref(CHANNELS[0])

/* 在页面里按可见文本找元素并点击（抖音改版时兜底用的 DOM 方案） */
function clickByTextFn() {
  return "var norm=function(s){return String(s||'').replace(/\\s+/g,'')};" +
    "var visible=function(el){return el&&el.offsetParent!==null&&el.getClientRects().length>0};" +
    "var find=function(list,root){var els=[].slice.call((root||document).querySelectorAll('div,span,button,a,li'));" +
    "for(var i=0;i<els.length;i++){var el=els[i];if(visible(el)&&list.indexOf(norm(el.textContent))>-1)return el}return null};"
}

/** 搜索页：筛选 → 最新发布 → 内容形式=视频 */
function applySearchFilters() {
  const expression = '(function(){' + clickByTextFn() +
    'var sleep=function(ms){return new Promise(function(r){setTimeout(r,ms)})};' +
    'return (async function(){var steps=[];try{' +
    'var filter=find(["筛选"]);if(filter){filter.click();steps.push("筛选");await sleep(800)}' +
    'var latest=find(["最新发布"]);if(latest){latest.click();steps.push("最新发布");await sleep(600)}' +
    'var panel=latest;for(var i=0;i<6&&panel&&panel.parentElement;i++){panel=panel.parentElement;' +
    'if(panel.textContent&&(panel.textContent.indexOf("内容形式")>-1||panel.textContent.indexOf("排序依据")>-1))break}' +
    'var video=find(["视频"],panel)||find(["视频"]);if(video){video.click();steps.push("视频");await sleep(600)}' +
    'if(filter){filter.click()}' +
    'return "filter:"+(steps.join(">")||"未找到筛选项")}catch(e){return "filter-error:"+e.message}})()' +
    '})()'
  send({ type: 'eval', expression })
}

/** 精选页：点栏目 tab */
function switchChannel(name) {
  const expression = '(function(){' + clickByTextFn() +
    'var el=find(["' + name + '"]);' +
    'if(!el)return "channel:未找到";el.click();' +
    'setTimeout(function(){window.scrollTo({top:0,behavior:"instant"})},600);' +
    'return "channel:' + name + '"})()'
  send({ type: 'eval', expression })
}

function nextKeyword() {
  const list = (kwOptions.value || []).map((o) => o && o.keyword).filter(Boolean)
  const current = keyword.value.trim()
  if (!list.length) return ''
  const start = Math.max(0, list.indexOf(current))
  for (let i = 1; i <= list.length; i++) {
    const cand = list[(start + i) % list.length]
    if (cand && cand !== current) return cand
  }
  return ''
}

function rotateKeyword(force) {
  if (!force && Date.now() - lastSwitchAt < SWITCH_COOLDOWN_MS) return
  const word = nextKeyword()
  if (!word) return
  lastSwitchAt = Date.now()
  pushEvent(force ? `连续 ${NO_PROGRESS_ROTATE_MS / 60000} 分钟没新内容，换词：${word}` : `已滚 ${SWITCH_SEARCH_AFTER} 屏，换词：${word}`)
  keyword.value = word
  doSearch()
}

function rotateChannel() {
  channelIndex = (channelIndex + 1) % CHANNELS.length
  const name = CHANNELS[channelIndex]
  activeChannel.value = name
  pushEvent(`已滚 ${SWITCH_FEED_AFTER} 屏，切栏目：${name}`)
  switchChannel(name)
}

let loopStartedAt = 0
const loopRemain = ref('')

/* 下滑节奏：搜索页基准 3.6 秒一屏（慢一倍，降低风控），精选页保持 1.8 秒。
 * 搜索页还带自适应：连续几次没扫到新数据就再放慢，恢复有数据后再提回来。 */
const SCROLL_BASE_FEED_MS = 1800
const SCROLL_BASE_SEARCH_MS = 3600
const SCROLL_MAX_MS = 8000
const IDLE_TICKS_BEFORE_SLOW = 4
const GOOD_TICKS_BEFORE_FAST = 5
let scrollDelay = SCROLL_BASE_FEED_MS
let lastScanned = -1
let idleTicks = 0
let goodTicks = 0
const scrollDelayText = computed(() => (scrollDelay / 1000).toFixed(1) + ' 秒/屏')

function baseDelay() {
  return props.slot === 'search' ? SCROLL_BASE_SEARCH_MS : SCROLL_BASE_FEED_MS
}

function isBlockedUrl(u) {
  return /verify|captcha|nocaptcha/i.test(u || '')
}

function adjustDelay() {
  if (props.slot !== 'search') return
  if (scanned.value > lastScanned) {
    lastScanned = scanned.value
    lastProgressAt = Date.now()
    idleTicks = 0
    goodTicks += 1
    if (goodTicks >= GOOD_TICKS_BEFORE_FAST && scrollDelay > baseDelay()) {
      scrollDelay = Math.max(baseDelay(), Math.round(scrollDelay / 1.5))
      goodTicks = 0
      pushEvent(`数据在恢复，提速到 ${scrollDelayText.value}`)
    }
    return
  }
  goodTicks = 0
  idleTicks += 1
  if (idleTicks >= IDLE_TICKS_BEFORE_SLOW && scrollDelay < SCROLL_MAX_MS) {
    scrollDelay = Math.min(SCROLL_MAX_MS, Math.round(scrollDelay * 1.5))
    idleTicks = 0
    pushEvent(`连续没有新数据，降速到 ${scrollDelayText.value}`, 'bad')
  }
}

function stopLoop(message) {
  if (loopTimer) {
    clearTimeout(loopTimer)
    loopTimer = null
  }
  loopRunning.value = false
  loopRemain.value = ''
  // 停止下滑的那一刻开始算「3 分钟无操作」
  lastKeywordActionAt = Date.now()
  pushEvent(message || '持续下滑已停止')
}

function scrollLoop() {
  if (loopTimer) {
    stopLoop('持续下滑已停止')
    return
  }
  // 搜索采集单次最多 5 分钟，精选频道不限时
  const capped = props.slot === 'search'
  loopStartedAt = Date.now()
  scrollDelay = baseDelay()
  lastScanned = scanned.value
  lastProgressAt = Date.now()
  idleTicks = 0
  goodTicks = 0
  pushEvent(capped
    ? `开始持续下滑（自适应 ${scrollDelayText.value} 起，单次上限 5 分钟）`
    : '开始持续下滑（不限时，点「停止下滑」结束）')
  loopRunning.value = true

  const tick = () => {
    if (!ws || ws.readyState !== 1) {
      stopLoop('连接已断开，下滑停止')
      return
    }
    if (capped && Date.now() - loopStartedAt >= SEARCH_SCROLL_LIMIT_MS) {
      stopLoop('搜索采集已到 5 分钟上限，自动停止下滑')
      ElMessage.info('搜索采集单次上限 5 分钟，已自动停止')
      return
    }
    // 撞上验证码页就立刻停，继续滑只会加重风控
    if (isBlockedUrl(pageUrl.value)) {
      stopLoop('页面出现人机验证，已停止下滑，请人工处理')
      ElMessage.error('抖音要求人机验证，下滑已停止，请先在画面里手动过验证')
      return
    }
    send({ type: 'wheel', x: 640, y: 420, deltaY: 900 })
    scrollCount += 1
    adjustDelay()
    // 规则二：连续 1 分钟没有新内容入库，强制换一个词（不受 5 分钟冷却限制）
    if (props.slot === 'search' && listening.value && Date.now() - lastProgressAt >= NO_PROGRESS_ROTATE_MS) {
      lastProgressAt = Date.now()
      rotateKeyword(true)
    }
    if (props.slot === 'search' && scrollCount % SWITCH_SEARCH_AFTER === 0) {
      rotateKeyword()
    } else if (props.slot === 'feed' && scrollCount % SWITCH_FEED_AFTER === 0) {
      rotateChannel()
    }
    loopTimer = setTimeout(tick, scrollDelay)
  }
  loopTimer = setTimeout(tick, scrollDelay)
}

function tickLoopRemain() {
  if (!loopRunning.value || props.slot !== 'search') {
    loopRemain.value = ''
    return
  }
  const left = Math.max(0, SEARCH_SCROLL_LIMIT_MS - (Date.now() - loopStartedAt))
  const m = String(Math.floor(left / 60000)).padStart(2, '0')
  const s = String(Math.floor((left % 60000) / 1000)).padStart(2, '0')
  loopRemain.value = `${m}:${s}`
}

/* 规则一：下滑停住后 3 分钟内没有任何换词/搜索操作 → 自动出词并开始下滑 */
function tickAutoSearch() {
  if (props.slot !== 'search') return
  if (loopRunning.value || wsState.value !== 'ready' || !listening.value) return
  if (Date.now() - lastKeywordActionAt < AUTO_SEARCH_IDLE_MS) return
  const pool = (kwOptions.value || []).map((o) => o && o.keyword).filter(Boolean)
  const word = pool.length ? pool[Math.floor(Math.random() * pool.length)] : keyword.value.trim()
  if (!word) return
  lastKeywordActionAt = Date.now()
  keyword.value = word
  pushEvent(`已停止下滑 ${AUTO_SEARCH_IDLE_MS / 60000} 分钟没有搜索操作，自动填充搜索词并开始下滑：${word}`)
  doSearch()
  setTimeout(() => {
    if (!loopRunning.value && wsState.value === 'ready') scrollLoop()
  }, 1500)
}

function doSearch() {
  const word = keyword.value.trim()
  if (!word) {
    ElMessage.warning('先填关键词')
    return
  }
  send({ type: 'navigate', url: 'https://www.douyin.com/search/' + encodeURIComponent(word) })
  pushEvent('搜索：' + word)
  scrollCount = 0
  lastKeywordActionAt = Date.now()
  lastProgressAt = Date.now()
  // 页面加载后自动切「最新发布 + 视频」，与插件 v1.4.0 一致
  setTimeout(() => {
    if (ws && ws.readyState === 1) applySearchFilters()
  }, 2600)
  // 搜过的词自动进词库（失败不影响采集）
  api.recordKeyword(word).catch(() => {})
}

function openHome() {
  const url = props.slot === 'feed' ? 'https://www.douyin.com/jingxuan' : 'https://www.douyin.com'
  send({ type: 'navigate', url })
  scrollCount = 0
  if (props.slot === 'feed') {
    channelIndex = 0
    activeChannel.value = CHANNELS[0]
  }
  pushEvent(props.slot === 'feed' ? '回到精选频道' : '回到抖音首页')
}

function reloadPage() {
  send({ type: 'reload' })
}

function toggleListening() {
  const on = !listening.value
  if (send({ type: 'listening', on })) {
    listening.value = on
  }
}

/* ============ 终端管理 ============ */
function openManage() {
  manageVisible.value = true
}

function openCreate() {
  editRow.value = null
  form.value = { code: '', name: '', host: '', port: 9222, ownerCode: me.code || '', douyinAccount: '', note: '' }
  formVisible.value = true
}

function openEdit(row) {
  editRow.value = row
  form.value = {
    code: row.code, name: row.name, host: row.host, port: row.port || 9222,
    ownerCode: row.owner_code || '', douyinAccount: row.douyin_account || '', note: row.note || ''
  }
  formVisible.value = true
}

async function saveTerminal() {
  const f = form.value
  if (!f.code || !f.name || !f.host) {
    ElMessage.warning('编码、名称、主机都要填')
    return
  }
  try {
    if (editRow.value) {
      await api.cdpUpdateTerminal(editRow.value.id, f)
      ElMessage.success('已更新')
    } else {
      await api.cdpCreateTerminal(f)
      ElMessage.success('已新增')
    }
    formVisible.value = false
    await loadTerminals()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function removeTerminal(row) {
  try {
    await ElMessageBox.confirm(`删除终端「${row.name}」？只删平台登记，不影响那台电脑上的 Chrome。`, '确认删除', { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await api.cdpDeleteTerminal(row.id)
    ElMessage.success('已删除')
    await loadTerminals()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function copyCommand() {
  const cmd = launcher.value && launcher.value.command
  if (!cmd) return
  navigator.clipboard.writeText(cmd).then(
    () => ElMessage.success('启动命令已复制'),
    () => ElMessage.warning('复制失败，请手动选中复制')
  )
}

/* ============ 生命周期 ============ */
let pollTimer = null
let uptimeTimer = null
let douyinTimer = null
let kwTimer = null
let autoSearchTimer = null

function tickUptime() {
  if (!startedAt.value) {
    uptime.value = ''
    return
  }
  const s = Math.floor((Date.now() - startedAt.value) / 1000)
  const h = String(Math.floor(s / 3600)).padStart(2, '0')
  const m = String(Math.floor((s % 3600) / 60)).padStart(2, '0')
  const ss = String(s % 60).padStart(2, '0')
  uptime.value = `${h}:${m}:${ss}`
}

/* 画面看门狗：后端通道静默断掉时界面会一直显示「已连接」，
 * 超过 15 秒没收到任何帧就主动重连，重连后后端会重建会话。 */
function tickFrameWatch() {
  if (wsState.value !== 'ready' || !lastFrameAt) return
  if (Date.now() - lastFrameAt <= 15000) return
  lastFrameAt = 0
  retryAllowed = true
  retryDelay = 3000
  pushEvent('15 秒没有收到画面，自动重连采集通道', 'bad')
  connect()
}

onMounted(async () => {
  await loadTerminals()
  loadLauncher()
  loadDouyinStatus()
  loadTasks()
  loadKeywordOptions()
  if (terminalId.value) connect()
  pollTimer = setInterval(() => {
    loadTerminals()
  }, 8000)
  uptimeTimer = setInterval(tickUptime, 1000)
  kwTimer = setInterval(() => {
    tickKeywords()
    tickLoopRemain()
    tickFrameWatch()
  }, 1000)
  douyinTimer = setInterval(loadDouyinStatus, 60000)
  autoSearchTimer = setInterval(tickAutoSearch, 5000)
})

/* 切回本页：连接还在就直接用；已断开就自动重连，后端会把 listening/计数带回来。
 * 切走时保持连接不断（KeepAlive 只是缓存页面），否则下滑定时器会被清掉，
 * 另一路采集就实质停住了；ready 消息到手时会把下滑循环接回来。 */
let firstActivate = true
onActivated(() => {
  if (firstActivate) {
    firstActivate = false
    return
  }
  retryAllowed = true
  retryDelay = 3000
  loadTerminals()
  loadDouyinStatus()
  if (terminalId.value && (!ws || ws.readyState === 2 || ws.readyState === 3)) {
    connect()
  }
})

onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer)
  if (uptimeTimer) clearInterval(uptimeTimer)
  if (kwTimer) clearInterval(kwTimer)
  if (douyinTimer) clearInterval(douyinTimer)
  if (autoSearchTimer) clearInterval(autoSearchTimer)
  if (douyinFastTimer) clearInterval(douyinFastTimer)
  stopRetry()
  disconnect()
})

const stateText = computed(() => ({
  idle: '未连接',
  connecting: '连接中',
  ready: '已连接',
  error: '连接失败'
}[wsState.value] || '未连接'))

const lastLogin = localStorage.getItem('dya_last_login') || ''
</script>

<template>
  <div class="console">
    <!-- 顶部：终端 + 状态 -->
    <div class="dya-card head">
      <div class="head-left">
        <div class="card-eyebrow">COLLECTION TERMINAL</div>
        <div class="card-title">{{ workspaceTitle }}</div>
      </div>
      <div class="head-right">
        <el-select v-model="terminalId" placeholder="选择采集终端" style="width: 240px" @change="onTerminalChange">
          <el-option v-for="t in terminals" :key="t.code" :label="t.name" :value="t.code">
            <span class="opt">
              <span class="dot" :class="t.online ? 'on' : 'off'"></span>
              <span>{{ t.name }}</span>
              <span class="opt-endpoint">{{ t.host }}:{{ t.port }}</span>
            </span>
          </el-option>
        </el-select>
        <span class="chip" :class="wsState === 'ready' ? 'on' : wsState === 'error' ? 'bad' : 'off'">{{ stateText }}</span>
        <el-button v-if="wsState === 'ready'" size="small" @click="disconnect">断开</el-button>
        <el-button v-else size="small" type="primary" @click="connect">连接</el-button>
        <el-button v-if="isAdmin" size="small" plain @click="openManage">终端管理</el-button>
      </div>
    </div>

    <!-- 记忆行：已登录身份 -->
    <div class="dya-card memory">
      <div class="avatar">{{ (me.name || me.code || 'U').slice(0, 1) }}</div>
      <div class="mem-main">
        <div class="mem-name">已登录 {{ me.name || me.code }} <span class="mem-role">{{ roleLabel }}</span></div>
        <div class="mem-sub">
          账号 {{ me.code || '—' }}
          <span v-if="lastLogin"> · 上次登录 {{ lastLogin }}</span>
        </div>
      </div>
      <div class="mem-right">
        <span v-if="current" class="mem-term">{{ current.name }} · {{ current.host }}:{{ current.port }}</span>
        <span v-if="uptime" class="mem-uptime">运行 {{ uptime }}</span>
      </div>
    </div>

    <!-- 抖音登录：一次扫码，后续免登录 -->
    <div class="dya-card douyin" :class="{ ok: douyin.loggedIn }">
      <div class="dy-main">
        <div class="dy-icon">抖</div>
        <div class="dy-text">
          <div class="card-eyebrow">DOUYIN ACCOUNT</div>
          <div class="card-title">
            抖音登录
            <span class="chip" :class="douyin.loggedIn ? 'on' : douyin.available === false ? 'off' : 'bad'">
              {{ douyin.loggedIn ? '已登录' : douyin.available === false ? '未检测到终端' : '未登录' }}
            </span>
          </div>
          <div class="dy-sub">
            <template v-if="douyin.loggedIn">
              扫码一次即可：登录态保存在专用 Chrome 配置
              <template v-if="douyinExpiry">（有效期至 {{ douyinExpiry }}）</template>，
              重启电脑、重新打开采集终端都不用再登录。
            </template>
            <template v-else>
              点右侧「扫码登录」，二维码会直接出现在 LIVE FRAME 画面里；扫完状态自动变绿，后续免登录。
            </template>
          </div>
          <div v-if="douyin.message && !douyin.loggedIn" class="dy-msg">{{ douyin.message }}</div>
        </div>
      </div>
      <div class="dy-right">
        <el-button size="small" :loading="douyinBusy" :disabled="!terminalId" @click="loadDouyinStatus">刷新状态</el-button>
        <el-button size="small" type="primary" :loading="douyinBusy" :disabled="!terminalId" @click="openDouyinLogin">
          {{ douyin.loggedIn ? '打开抖音页' : '扫码登录' }}
        </el-button>
      </div>
    </div>

    <!-- 每日采集任务 + 日报导出 -->
    <div class="dya-card tasks">
      <div class="card-head">
        <div>
          <div class="card-eyebrow">DAILY TASKS</div>
          <div class="card-title">今日采集任务 <span class="task-date">{{ today }}</span></div>
        </div>
        <div class="task-actions">
          <el-button size="small" @click="exportReport">导出采集日报 CSV</el-button>
          <el-button v-if="isAdmin" size="small" type="primary" plain @click="openTaskForm">设置任务</el-button>
        </div>
      </div>
      <div v-if="tasks.length" class="task-list">
        <div v-for="t in tasks" :key="t.id" class="task-item">
          <div class="task-top">
            <span class="task-name">{{ t.collector_name || t.collector_code }}</span>
            <span class="task-count">{{ t.actual_count }}<span class="task-target"> / {{ t.target_count || '—' }}</span></span>
            <el-button v-if="isAdmin" link type="danger" size="small" @click="removeTask(t)">删除</el-button>
          </div>
          <el-progress
            :percentage="taskPercent(t)"
            :stroke-width="8"
            :status="t.target_count > 0 && t.actual_count >= t.target_count ? 'success' : ''"
          />
          <div class="task-meta">作者 {{ t.author_count || 0 }} · {{ t.note || '无备注' }}</div>
        </div>
      </div>
      <div v-else class="hint">
        {{ isAdmin ? '今天还没给采集员设任务，点「设置任务」分配目标。' : '今天还没有分配给你的采集任务。' }}
      </div>
    </div>

    <div class="grid">
      <!-- 画面 -->
      <div class="dya-card panel">
        <div class="panel-head">
          <div>
            <div class="card-eyebrow">LIVE FRAME</div>
            <div class="card-title">实时画面</div>
          </div>
          <span class="status-pill" :class="{ on: listening }">
            <i class="pill-dot"></i>{{ listening ? '采集中' : '待机' }}
          </span>
        </div>
        <div class="screen">
          <img v-if="frame" :src="frame" alt="采集终端实时画面" />
          <div v-else class="empty">
            <template v-if="wsState === 'ready'">等待画面，页面加载后 1~2 秒出现</template>
            <template v-else-if="wsState === 'error'">{{ wsError }}</template>
            <template v-else-if="!terminals.length">
              {{ isAdmin ? '还没有登记采集终端，点右上角「终端管理」新增' : '你的账号还没有绑定采集终端，请联系管理员登记' }}
            </template>
            <template v-else>未连接采集终端，点右上角「连接」</template>
          </div>
        </div>
        <div class="stat-grid">
          <div class="stat-cell main">
            <span class="stat-num">{{ ingested }}</span>
            <span class="stat-label">入库</span>
          </div>
          <div class="stat-cell">
            <span class="stat-num">{{ scanned }}</span>
            <span class="stat-label">扫描</span>
          </div>
          <div class="stat-cell">
            <span class="stat-num">{{ mixHits }}</span>
            <span class="stat-label">合集命中</span>
          </div>
          <div class="stat-cell">
            <span class="stat-num">{{ aiHits }}</span>
            <span class="stat-label">AI 命中</span>
          </div>
          <div class="stat-cell">
            <span class="stat-num">{{ droppedCount }}</span>
            <span class="stat-label">已过滤</span>
          </div>
        </div>
        <div v-if="pageUrl" class="url">{{ pageUrl }}</div>
      </div>

      <!-- 控制台 -->
      <div class="dya-card panel">
        <div class="panel-head">
          <div>
            <div class="card-eyebrow">REMOTE ACTIONS</div>
            <div class="card-title">操作</div>
          </div>
          <span class="status-pill" :class="{ on: wsState === 'ready' }">
            <i class="pill-dot"></i>{{ wsState === 'ready' ? '已连接' : '未连接' }}
          </span>
        </div>

        <div class="actions-body">
        <div class="primary-block">
          <el-button class="big" :type="listening ? 'warning' : 'success'" :disabled="wsState !== 'ready'" @click="toggleListening">
            {{ listening ? '停止采集' : '开始采集' }}
          </el-button>
          <div class="tip">
            <i class="tip-dot"></i>
            <span>开始后页面加载到的作品实时入库；只收 AI 生成内容，解说、AI 图片、AI 舞蹈这类不收，其余全量入库。</span>
          </div>
        </div>

        <div class="action-group">
          <div class="group-label">页面动作</div>
          <div class="btn-grid">
            <el-button :disabled="wsState !== 'ready'" @click="scrollOnce">下滑一屏</el-button>
            <el-button :disabled="wsState !== 'ready'" @click="scrollTop">回顶部</el-button>
            <el-button :disabled="wsState !== 'ready'" @click="reloadPage">刷新页面</el-button>
            <el-button plain :disabled="wsState !== 'ready'" @click="openHome">{{ props.slot === 'feed' ? '回精选页' : '打开抖音首页' }}</el-button>
          </div>
        </div>

        <div class="action-group">
          <div class="group-label">
            持续下滑
            <span class="unit">
              {{ loopRunning
                ? (props.slot === 'search' ? '剩余 ' + loopRemain : '运行中，手动停止')
                : (props.slot === 'search' ? '单次上限 5 分钟' : '不限时') }}
            </span>
          </div>
          <el-button class="wide" :type="loopRunning ? 'warning' : 'default'" :disabled="wsState !== 'ready'" @click="scrollLoop">
            {{ loopRunning ? '停止下滑' : '开始下滑' }}
          </el-button>
        </div>

        <div class="action-group" v-if="props.slot !== 'feed'">
          <div class="group-label">关键词搜索</div>
          <div class="search-row">
            <el-input v-model="keyword" placeholder="搜索关键词，如 AI漫剧" :disabled="wsState !== 'ready'" @keyup.enter="doSearch" />
            <el-button type="primary" plain :disabled="wsState !== 'ready'" @click="doSearch">搜索</el-button>
          </div>
          <div class="kw-lib">
            <div class="kw-head">
              <span class="label">推荐词库</span>
              <span class="unit">{{ kwCountdown ? '下次刷新 ' + kwCountdown : '每 5 分钟刷新一批' }}</span>
              <el-button link type="primary" size="small" :loading="kwLoading" @click="loadKeywordOptions">换一批</el-button>
            </div>
            <div class="kw-chips">
              <button v-for="w in kwOptions" :key="w.keyword" class="kw-chip"
                      :class="keywordLevel(w.produced)"
                      :title="'近 7 天去重入库 ' + w.produced + ' 条'"
                      :disabled="wsState !== 'ready'" @click="useKeyword(w.keyword)">{{ w.keyword }}</button>
              <span v-if="!kwOptions.length" class="unit">词库为空，管理员可在「关键词分析」页补充</span>
            </div>
          </div>
        </div>

        <div class="launch" v-if="launcher">
          <div class="launch-title">终端没连上？在那台电脑上运行一次：</div>
          <code class="cmd">{{ launcher.command }}</code>
          <el-button size="small" @click="copyCommand">复制命令</el-button>
        </div>
        </div>
      </div>
    </div>

    <!-- 事件流 -->
    <div class="dya-card">
      <div class="card-head">
        <div>
          <div class="card-eyebrow">ACTIVITY</div>
          <div class="card-title">运行日志</div>
        </div>
        <el-button size="small" @click="events = []">清空</el-button>
      </div>
      <div class="log" v-if="events.length">
        <div v-for="(e, i) in events" :key="i" class="log-line" :class="e.kind">
          <span class="log-time">{{ new Date(e.at).toLocaleTimeString('zh-CN', { hour12: false }) }}</span>
          <span>{{ e.text }}</span>
        </div>
      </div>
      <div v-else class="log-empty">连接终端、开始采集后，这里会实时显示入库与异常</div>
    </div>

    <!-- 终端管理：列表 -->
    <el-dialog v-model="manageVisible" title="采集终端管理" width="92%" style="max-width: 720px">
      <el-table :data="terminals" size="small" empty-text="还没有登记采集终端">
        <el-table-column prop="code" label="编码" width="110" />
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column label="地址" min-width="150">
          <template #default="{ row }">{{ row.host }}:{{ row.port }}</template>
        </el-table-column>
        <el-table-column prop="owner_code" label="归属账号" width="130" />
        <el-table-column prop="douyin_account" label="抖音号" width="120" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <span class="chip" :class="row.online ? 'on' : 'off'">{{ row.online ? '在线' : '离线' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="removeTerminal(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="manageVisible = false">关闭</el-button>
        <el-button type="primary" @click="openCreate">新增终端</el-button>
      </template>
    </el-dialog>

    <!-- 终端管理：新增/编辑表单 -->
    <el-dialog v-model="formVisible" :title="editRow ? '编辑采集终端' : '新增采集终端'" width="460px">
      <el-form label-width="86px">
        <el-form-item label="终端编码">
          <el-input v-model="form.code" :disabled="!!editRow" placeholder="如 PC-XJH" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="如 向锦灏的采集机" />
        </el-form-item>
        <el-form-item label="主机地址">
          <el-input v-model="form.host" placeholder="那台电脑的局域网 IP，如 10.1.2.113" />
        </el-form-item>
        <el-form-item label="调试端口">
          <el-input-number v-model="form.port" :min="1024" :max="65535" />
        </el-form-item>
        <el-form-item label="归属账号">
          <el-input v-model="form.ownerCode" placeholder="如 INTERN01XJH" />
        </el-form-item>
        <el-form-item label="抖音号">
          <el-input v-model="form.douyinAccount" placeholder="选填，方便识别" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.note" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="saveTerminal">保存</el-button>
      </template>
    </el-dialog>

    <!-- 每日任务设置 -->
    <el-dialog v-model="taskDialog" title="设置今日采集任务" width="420px">
      <el-form label-width="80px">
        <el-form-item label="采集员">
          <el-select v-model="taskForm.collectorCode" filterable placeholder="选择采集员" style="width: 100%">
            <el-option
              v-for="u in collectors"
              :key="u.code"
              :label="`${u.name || u.code}（${u.code}）`"
              :value="u.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="目标条数">
          <el-input-number v-model="taskForm.targetCount" :min="1" :max="100000" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="taskForm.note" placeholder="选填，如：主攻 AI 漫剧" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="taskDialog = false">取消</el-button>
        <el-button type="primary" @click="saveTask">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.console { display: flex; flex-direction: column; gap: 14px; }

.head { display: flex; align-items: center; justify-content: space-between; padding: 16px 18px; }
.head-right { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.opt { display: flex; align-items: center; gap: 8px; width: 100%; }
.opt-endpoint { margin-left: auto; font-size: 11px; color: var(--dya-muted); }
.dot { width: 7px; height: 7px; border-radius: 50%; display: inline-block; }
.dot.on { background: #22b07d; box-shadow: 0 0 0 3px rgba(34, 176, 125, .15); }
.dot.off { background: #c9ced8; }
.chip { font-size: 11px; padding: 4px 10px; border-radius: 999px; }
.chip.on { background: rgba(34, 176, 125, .12); color: #1c8f66; }
.chip.off { background: rgba(160, 168, 180, .16); color: #6b7480; }
.chip.bad { background: rgba(220, 80, 80, .12); color: #c0392b; }

.memory { display: flex; align-items: center; gap: 12px; padding: 12px 18px; }
.avatar {
  width: 36px; height: 36px; border-radius: 10px; flex: none;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #2b4cff, #6a8bff); color: #fff; font-weight: 600;
}
.mem-name { font-size: 14px; font-weight: 600; color: var(--dya-ink); }
.mem-role { font-size: 11px; font-weight: 500; margin-left: 6px; padding: 1px 7px; border-radius: 999px; background: var(--dya-accent-soft); color: var(--dya-accent); }
.mem-sub { font-size: 11px; color: var(--dya-muted); margin-top: 3px; }
.mem-right { margin-left: auto; text-align: right; }
.mem-term { display: block; font-size: 11px; color: var(--dya-muted); }
.mem-uptime { display: block; font-size: 12px; color: var(--dya-ink); font-variant-numeric: tabular-nums; }

.douyin { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 14px 18px; }
.douyin.ok { box-shadow: inset 3px 0 0 #22b07d; }
.dy-main { display: flex; align-items: flex-start; gap: 12px; min-width: 0; }
.dy-icon {
  width: 36px; height: 36px; border-radius: 10px; flex: none;
  display: flex; align-items: center; justify-content: center;
  background: #161823; color: #fff; font-weight: 700; font-size: 15px;
}
.dy-text { min-width: 0; }
.dy-sub { font-size: 11px; color: var(--dya-muted); line-height: 1.7; margin-top: 4px; }
.dy-msg { font-size: 11px; color: #c0392b; margin-top: 4px; }
.dy-right { display: flex; gap: 8px; flex: none; }

.tasks { padding: 16px 18px; }
.task-date { font-size: 11px; font-weight: 500; color: var(--dya-muted); margin-left: 6px; }
.task-actions { display: flex; gap: 8px; }
.task-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 12px; margin-top: 12px; }
.task-item { border: 1px solid var(--dya-line, #e8ebf1); border-radius: 10px; padding: 10px 12px; }
.task-top { display: flex; align-items: baseline; gap: 8px; margin-bottom: 8px; }
.task-name { font-size: 13px; font-weight: 600; color: var(--dya-ink); }
.task-count { margin-left: auto; font-size: 15px; font-weight: 600; color: var(--dya-accent); font-variant-numeric: tabular-nums; }
.task-target { font-size: 11px; font-weight: 400; color: var(--dya-muted); }
.task-meta { font-size: 11px; color: var(--dya-muted); margin-top: 6px; }

@media (max-width: 900px) {
  .douyin { flex-direction: column; align-items: stretch; }
  .dy-right { justify-content: flex-end; }
  .head { flex-direction: column; align-items: flex-start; gap: 10px; }
  .head-right { flex-wrap: wrap; }
}

.grid { display: grid; grid-template-columns: minmax(0, 1.45fr) minmax(340px, 1fr); gap: 14px; align-items: stretch; }
.grid > .panel { display: flex; flex-direction: column; }
/* 操作栏撑到与左侧同高，内容垂直居中，避免右下角空一大块 */
.actions-body { flex: 1; display: flex; flex-direction: column; justify-content: center; gap: 12px; }
@media (max-width: 1080px) {
  .grid { grid-template-columns: minmax(0, 1fr); }
}
/* 全局 .dya-card + .dya-card 有 16px 上边距，会把右栏压低，这里清掉 */
.grid .dya-card + .dya-card { margin-top: 0; }
.panel { padding: 16px 18px; display: flex; flex-direction: column; gap: 12px; }
.panel-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.card-head { display: flex; align-items: center; justify-content: space-between; }

.status-pill {
  display: inline-flex; align-items: center; gap: 6px; flex: none;
  font-size: 11px; padding: 4px 10px; border-radius: 999px;
  background: rgba(160, 168, 180, .16); color: #6b7480;
}
.status-pill.on { background: rgba(34, 176, 125, .12); color: #1c8f66; }
.pill-dot { width: 6px; height: 6px; border-radius: 50%; background: currentColor; display: inline-block; }
.status-pill.on .pill-dot { animation: pulse 1.4s infinite; }
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: .35; } }

.screen {
  background: #0b0e13; border-radius: 12px; overflow: hidden; aspect-ratio: 16 / 9;
  display: flex; align-items: center; justify-content: center; position: relative;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, .04);
}
.screen img { width: 100%; height: 100%; object-fit: contain; }
.empty { color: #7d8794; font-size: 12px; line-height: 1.8; text-align: center; padding: 0 24px; }

.stat-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 8px; }
.stat-cell {
  display: flex; flex-direction: column; gap: 2px; padding: 9px 11px;
  border: 1px solid var(--dya-line); border-radius: 10px; background: #fbfaf8;
}
.stat-cell.main { background: var(--dya-accent-soft); border-color: rgba(43, 69, 217, .16); }
.stat-num { font-size: 17px; font-weight: 600; line-height: 1.25; color: var(--dya-ink); font-variant-numeric: tabular-nums; }
.stat-cell.main .stat-num { color: var(--dya-accent); }
.stat-label { font-size: 11px; color: var(--dya-muted); white-space: nowrap; }
.url { font-size: 11px; color: var(--dya-muted); word-break: break-all; }

/* 操作栏 */
.primary-block { display: flex; flex-direction: column; gap: 8px; }
.big { width: 100%; height: 44px; font-size: 15px; font-weight: 600; margin-left: 0; }
.wide { width: 100%; margin-left: 0; }
.tip {
  display: flex; align-items: flex-start; gap: 7px;
  font-size: 11px; line-height: 1.7; color: var(--dya-muted);
  background: #fbfaf8; border: 1px solid var(--dya-line); border-radius: 10px; padding: 8px 10px;
}
.tip-dot { width: 5px; height: 5px; border-radius: 50%; background: var(--dya-accent); flex: none; margin-top: 6px; }

.action-group { display: flex; flex-direction: column; gap: 8px; padding-top: 12px; border-top: 1px dashed var(--dya-line); }
.group-label {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  font-size: 12px; font-weight: 600; color: var(--dya-ink);
}
.group-label .unit { font-weight: 400; }
.btn-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.btn-grid :deep(.el-button) { width: 100%; margin-left: 0; }
.search-row { display: flex; gap: 8px; }
.search-row :deep(.el-input) { flex: 1; }
.search-row :deep(.el-button) { margin-left: 0; }

.unit { font-size: 12px; color: var(--dya-muted); }
.kw-lib { border: 1px dashed #e6ded2; border-radius: 10px; padding: 10px 12px; }
.kw-head { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.kw-head .label { font-size: 12px; font-weight: 600; color: var(--dya-ink); }
.kw-head :deep(.el-button) { margin-left: auto; }
.kw-chips { display: flex; flex-wrap: wrap; gap: 8px; }
.kw-chip {
  border: 1px solid #e3d9c9; background: #fbf8f2; color: var(--dya-ink);
  font-size: 12px; padding: 5px 11px; border-radius: 999px; cursor: pointer;
  transition: all .15s ease; font-family: inherit;
}
.kw-chip:hover:not(:disabled) { border-color: #d2a24c; background: #fdf3df; color: #8a5a12; }
.kw-chip:disabled { opacity: .5; cursor: not-allowed; }
/* 出货量四色：绿 ≥50 / 蓝 20-49 / 黄 5-19 / 红 <5 */
.kw-chip.lv-green { border-color: rgba(34,176,125,.55); background: rgba(34,176,125,.12); color: #1c8f66; }
.kw-chip.lv-blue { border-color: rgba(43,69,217,.45); background: rgba(43,69,217,.10); color: #2b45d9; }
.kw-chip.lv-yellow { border-color: rgba(210,162,76,.55); background: rgba(245,199,106,.20); color: #8a5a12; }
.kw-chip.lv-red { border-color: rgba(220,80,80,.45); background: rgba(220,80,80,.10); color: #c0392b; }
.hint { font-size: 11px; color: var(--dya-muted); line-height: 1.7; margin: 2px 0; }

.launch { padding: 10px 12px; border-radius: 10px; background: rgba(43, 76, 255, .05); }
.launch-title { font-size: 11px; color: var(--dya-muted); margin-bottom: 6px; }
.cmd { display: block; font-size: 10.5px; color: #2b4cff; word-break: break-all; line-height: 1.7; margin-bottom: 6px; }

.log { display: flex; flex-direction: column; gap: 4px; max-height: 220px; overflow: auto; }
.log-line { display: flex; gap: 10px; font-size: 12px; color: var(--dya-ink); }
.log-line.bad { color: #c0392b; }
.log-time { color: var(--dya-muted); flex: none; font-variant-numeric: tabular-nums; }
.log-empty { font-size: 12px; color: var(--dya-muted); padding: 8px 0 2px; }
</style>
