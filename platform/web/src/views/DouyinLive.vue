<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { api } from '../api'

/* ===== 扩展直传监控：不经过 9222，抖音窗口最小化/挂后台也能看 ===== */
const ext = ref({
  online: false,
  known: false,
  collecting: false,
  autoBrowse: false,
  authors: 0,
  scanned: 0,
  todayInserted: 0,
  pageUrl: '',
  channel: '—',
  ageText: '等待扩展上报…'
})
let extTimer = null

function channelOf(url) {
  const u = String(url || '')
  if (!u) return '—'
  if (/\/search\//.test(u)) return '搜索页'
  if (/\/jingxuan\/theater/.test(u)) return '小剧场'
  const m = u.match(/\/jingxuan\/([a-z0-9_-]+)/i)
  if (m) return '精选·' + m[1]
  if (/\/jingxuan/.test(u)) return '全部'
  return '其他页面'
}

async function loadExtStatus() {
  try {
    const res = await api.collectorStatus()
    const list = (res && res.data) || []
    const item = list.slice().sort((a, b) => (b.receivedAt || 0) - (a.receivedAt || 0))[0]
    if (!item) {
      ext.value = { ...ext.value, online: false, known: false, ageText: '扩展还没有上报过状态' }
      return
    }
    const ageSec = Math.max(0, Math.round((item.ageMs || 0) / 1000))
    ext.value = {
      online: !!item.online,
      known: true,
      collecting: !!item.collecting,
      autoBrowse: !!item.autoBrowse,
      authors: Number(item.authors || 0),
      scanned: Number(item.scanned || 0),
      todayInserted: Number(item.todayInserted || 0),
      pageUrl: String(item.pageUrl || ''),
      channel: channelOf(item.pageUrl),
      ageText: item.online ? `最近心跳 ${ageSec} 秒前` : `已 ${ageSec} 秒无心跳`
    }
  } catch (e) {
    /* 接口未就绪或未登录时静默忽略 */
  }
}

/* ===== 扩展实时画面：不依赖 9222 ===== */
const frame = ref({ src: '', ageSec: 0, pageUrl: '', live: false, hint: '等待扩展推送画面…' })
let frameTimer = null
let frameFails = 0

async function loadFrame() {
  try {
    const res = await api.collectorFrame()
    const d = res && res.data
    if (!d || !d.image) {
      frameFails++
      if (frameFails > 3) {
        frame.value = {
          ...frame.value,
          src: '',
          live: false,
          hint: '扩展还没有推送画面：确认扩展 1.4.0 已加载，且抖音标签在当前窗口的前台'
        }
      }
      return
    }
    frameFails = 0
    const ageSec = Math.max(0, Math.round((d.ageMs || 0) / 1000))
    frame.value = {
      src: d.image.startsWith('data:') ? d.image : 'data:image/jpeg;base64,' + d.image,
      ageSec,
      pageUrl: String(d.pageUrl || ''),
      live: ageSec <= 5,
      hint: ''
    }
  } catch (e) {
    /* 未登录/接口未就绪时静默忽略 */
  }
}

onMounted(() => {
  loadExtStatus()
  extTimer = setInterval(loadExtStatus, 5000)
  loadFrame()
  frameTimer = setInterval(loadFrame, 2000)
})
onUnmounted(() => {
  if (extTimer) clearInterval(extTimer)
  if (frameTimer) clearInterval(frameTimer)
})
</script>

<template>
  <div class="dya-card ext-card">
    <div class="card-head">
      <div>
        <div class="card-eyebrow">EXTENSION MONITOR</div>
        <div class="card-title">扩展直传 · 实时状态</div>
      </div>
      <div class="card-actions">
        <span class="chip" :class="ext.online ? 'chip-on' : 'chip-off'">
          {{ ext.online ? '扩展在线' : '扩展离线' }}
        </span>
        <span class="chip" :class="ext.collecting ? 'chip-live' : 'chip-idle'">
          {{ ext.collecting ? '采集：开' : '采集：关' }}
        </span>
        <span class="chip" :class="ext.autoBrowse ? 'chip-live' : 'chip-idle'">
          {{ ext.autoBrowse ? '自动下滑：开' : '自动下滑：关' }}
        </span>
      </div>
    </div>

    <div class="ext-grid">
      <div class="ext-kpi"><strong>{{ ext.authors }}</strong><span>本轮作者</span></div>
      <div class="ext-kpi"><strong>{{ ext.scanned }}</strong><span>已扫视频</span></div>
      <div class="ext-kpi"><strong>{{ ext.todayInserted }}</strong><span>今日入库</span></div>
    </div>

    <div class="ext-meta">
      <span class="ext-lbl">当前页面</span><span class="ext-url">{{ ext.pageUrl || '—' }}</span>
      <span class="ext-lbl">栏目</span><span>{{ ext.channel }}</span>
      <span class="ext-lbl">心跳</span><span>{{ ext.ageText }}</span>
    </div>

    <div class="frame-head">
      <span class="card-eyebrow">LIVE FRAME · 扩展回传</span>
      <span class="chip" :class="frame.live ? 'chip-on' : 'chip-off'">
        {{ frame.live ? '画面实时' : '画面已停' }}
      </span>
      <span v-if="frame.live" class="ext-lbl">最近一帧 {{ frame.ageSec }} 秒前</span>
    </div>
    <div class="ext-screen">
      <img v-if="frame.src" :src="frame.src" alt="抖音精选实时画面" draggable="false" />
      <div v-else class="screen-placeholder">
        <div class="ph-title">{{ frame.hint || '等待画面…' }}</div>
        <div class="ph-sub">画面来自扩展截图，不需要 9222，也不要求抖音窗口全屏</div>
      </div>
    </div>

    <p class="hint-text">
      本页只保留扩展回传监控；遥控动作与平台上报已合并到「采集工作区」。
      数值来自扩展每 30 秒上报的心跳，画面每 2 秒截图回传；窗口最小化或抖音标签被顶到后台时画面会停在最后一帧。
    </p>
  </div>
</template>

<style scoped>
.ext-card { max-width: 1520px; margin: 0 auto; padding: 22px 24px 18px; }
.card-actions { display: flex; align-items: center; gap: 8px; }
.chip {
  font-size: 11px; padding: 3px 10px; border-radius: 999px; letter-spacing: .04em;
  background: #f1f3f5; color: #5c6670;
}
.chip-on { background: #e8f7ee; color: #1e8e4e; }
.chip-off { background: #f1f3f5; color: #7a838c; }
.chip-live { background: #eaf1ff; color: #2b5bd7; }
.chip-idle { background: #f1f3f5; color: #7a838c; }
.ext-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin: 14px 0 10px; }
.ext-kpi {
  border: 1px solid #eef0f2; border-radius: 12px; padding: 12px 14px; background: #fbfcfd;
  display: flex; flex-direction: column; gap: 2px;
}
.ext-kpi strong { font-size: 22px; font-weight: 600; color: #1d2129; }
.ext-kpi span { font-size: 11px; color: #86909c; }
.ext-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 10px; font-size: 12px; color: #4e5969; }
.ext-lbl { font-size: 11px; color: #86909c; }
.ext-url { max-width: 520px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.frame-head { display: flex; align-items: center; gap: 10px; margin: 16px 0 8px; }
.ext-screen {
  position: relative; width: 100%; aspect-ratio: 16 / 9; background: #0f1115;
  border-radius: 12px; overflow: hidden; display: flex; align-items: center; justify-content: center;
}
.ext-screen img { width: 100%; height: 100%; object-fit: contain; }
.screen-placeholder { text-align: center; color: #9aa3ad; }
.ph-title { font-size: 13px; margin-bottom: 4px; }
.ph-sub { font-size: 11px; color: #6b747e; }
.hint-text { font-size: 11px; color: #86909c; line-height: 1.7; margin: 10px 0 0; }
</style>
