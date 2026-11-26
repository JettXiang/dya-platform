<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const today = () => new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' })
const date = ref(today())
const data = ref({})
const loading = ref(false)

/* 实时采集口径：数据直接来自 videos 表，和排班/批次无关 */
const directVideos = computed(() => Number(data.value.direct_videos || 0))
const directAuthors = computed(() => Number(data.value.direct_authors || 0))
const lastAtText = computed(() => {
  const ms = Number(data.value.direct_last_at || 0)
  if (!ms) return '—'
  return new Date(ms).toLocaleTimeString('zh-CN', { hour12: false })
})

const scheduled = computed(() => Number(data.value.scheduled_work || 0))
const reported = computed(() => Number(data.value.reported_accounts || 0))
const complete = computed(() => Number(data.value.complete_accounts || 0))
const authors = computed(() => Number(data.value.authors_collected || 0))
const runs = computed(() => Number(data.value.runs || 0))
const batchCount = computed(() => (data.value.batches || []).length)
const pendingChunks = computed(() =>
  (data.value.accounts || []).reduce((sum, a) => sum + Number(a.pending_chunks || 0), 0))
const incomplete = computed(() => Math.max(0, scheduled.value - complete.value))
const percent = computed(() =>
  scheduled.value === 0 ? 0 : Math.min(100, Math.round((complete.value / scheduled.value) * 100)))
const dashOffset = computed(() => 327 * (1 - percent.value / 100))

const mains = computed(() => [
  { label: '在岗账号', value: scheduled.value, hint: '今日排班为在岗' },
  { label: '已报日终', value: reported.value, hint: '收到日终上报' },
  { label: '作者数', value: authors.value, hint: '当日去重作者' }
])

const minors = computed(() => [
  { label: 'Run 数', value: runs.value },
  { label: '批次', value: batchCount.value },
  { label: '待传分片', value: pendingChunks.value },
  { label: '未完整账号', value: incomplete.value }
])

async function load() {
  loading.value = true
  try {
    const r = await api.daily(date.value)
    data.value = r.data || {}
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function stateType(s) {
  if (s === 'success') return 'success'
  if (s === 'cancelled' || s === 'rolled_back') return 'info'
  if (s === 'failed') return 'danger'
  if (s === 'unknown') return 'warning'
  return ''
}

watch(date, load)
onMounted(load)
</script>

<template>
  <div v-loading="loading" class="daily">
    <div class="overview">
      <div class="dya-card ring-card">
        <div class="card-eyebrow">COMPLETION</div>
        <div class="card-title">今日完成度</div>
        <div class="ring-wrap">
          <svg viewBox="0 0 120 120" class="ring">
            <circle cx="60" cy="60" r="52" class="ring-bg" />
            <circle cx="60" cy="60" r="52" class="ring-fg"
                    :stroke-dasharray="327" :stroke-dashoffset="dashOffset" />
          </svg>
          <div class="ring-center">
            <div class="ring-value">{{ percent }}<span>%</span></div>
            <div class="ring-label">{{ complete }} / {{ scheduled }} 完整</div>
          </div>
        </div>
        <div class="ring-foot">
          <span class="dot ok"></span>完整 {{ complete }}
          <span class="dot warn"></span>未完整 {{ incomplete }}
        </div>
      </div>

      <div v-for="m in mains" :key="m.label" class="dya-card metric">
        <div class="card-eyebrow">TODAY</div>
        <div class="metric-value">{{ m.value }}</div>
        <div class="metric-label">{{ m.label }}</div>
        <div class="metric-hint">{{ m.hint }}</div>
      </div>
    </div>

    <div class="direct-strip dya-card">
      <div class="direct-head">
        <div>
          <div class="card-eyebrow">LIVE INGEST · 实时采集</div>
          <div class="card-title">当日实时采集</div>
        </div>
      </div>
      <div class="direct-grid">
        <div class="minor">
          <div class="minor-value">{{ directVideos }}</div>
          <div class="minor-label">今日入库视频</div>
        </div>
        <div class="minor">
          <div class="minor-value">{{ directAuthors }}</div>
          <div class="minor-label">今日去重作者</div>
        </div>
        <div class="minor">
          <div class="minor-value sm">{{ lastAtText }}</div>
          <div class="minor-label">最近入库时间</div>
        </div>
      </div>
      <p class="direct-hint">
        这排数字只统计 videos 表，和排班/批次无关；下方「在岗账号 / 当日批次」属于批次流程，未走批次时显示 0 是正常的。
      </p>
    </div>

    <div class="minor-strip dya-card">
      <div v-for="m in minors" :key="m.label" class="minor">
        <div class="minor-value">{{ m.value }}</div>
        <div class="minor-label">{{ m.label }}</div>
      </div>
    </div>

    <div class="dya-card">
      <div class="card-head">
        <div>
          <div class="card-eyebrow">ON-DUTY ACCOUNTS</div>
          <div class="card-title">在岗账号</div>
        </div>
        <div class="card-actions">
          <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" style="width: 150px" />
          <el-button @click="load">刷新</el-button>
        </div>
      </div>
      <el-table :data="data.accounts || []" size="small" class="dya-table" empty-text="暂无排班数据">
        <el-table-column prop="account_code" label="账号" width="150" />
        <el-table-column prop="name" label="姓名" width="120" />
        <el-table-column label="日终" width="100">
          <template #default="{ row }">
            <el-tag :type="Number(row.complete) === 1 ? 'success' : 'warning'" effect="light" round>
              {{ Number(row.complete) === 1 ? '完整' : '未完整' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="pending_chunks" label="待传分片" width="100" />
        <el-table-column prop="runs" label="Run" width="80" />
        <el-table-column prop="authors" label="作者" width="80" />
        <el-table-column prop="revision" label="Revision" width="90" />
        <el-table-column prop="last_heartbeat_at" label="最近心跳" width="190" />
        <el-table-column prop="evaluated_at" label="日终评估" width="190" />
      </el-table>
    </div>

    <div class="dya-card">
      <div class="card-head">
        <div>
          <div class="card-eyebrow">TODAY BATCHES</div>
          <div class="card-title">当日批次</div>
        </div>
        <span class="count-chip">{{ batchCount }} 个批次</span>
      </div>
      <el-table :data="data.batches || []" size="small" class="dya-table" empty-text="暂无批次数据">
        <el-table-column prop="batch_no" label="批次号" width="200" />
        <el-table-column prop="type" label="类型" width="110" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="stateType(row.state)" effect="light" round>{{ row.state }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="total" label="成员" width="90" />
        <el-table-column prop="dedup_total" label="去重" width="90" />
        <el-table-column prop="attempt_count" label="尝试" width="90" />
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.overview { display: grid; grid-template-columns: 1.2fr 1fr 1fr 1fr; gap: 14px; }
.ring-card { display: flex; flex-direction: column; padding: 16px 18px; }
.ring-wrap { position: relative; width: 106px; height: 106px; margin: 6px auto 4px; }
.ring { width: 106px; height: 106px; transform: rotate(-90deg); }
.ring-bg { fill: none; stroke: #e9ecf8; stroke-width: 10; }
.ring-fg {
  fill: none; stroke: var(--dya-accent); stroke-width: 10; stroke-linecap: round;
  transition: stroke-dashoffset .9s cubic-bezier(.22,.8,.3,1);
}
.ring-center { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; }
.ring-value { font-size: 26px; font-weight: 650; color: var(--dya-ink); letter-spacing: -1px; }
.ring-value span { font-size: 13px; margin-left: 1px; color: var(--dya-muted); }
.ring-label { font-size: 10px; color: var(--dya-muted); margin-top: 1px; }
.ring-foot { display: flex; align-items: center; justify-content: center; gap: 6px; font-size: 11px; color: var(--dya-muted); }
.dot { width: 6px; height: 6px; border-radius: 50%; display: inline-block; }
.dot.ok { background: var(--dya-accent); }
.dot.warn { background: #e4b34a; margin-left: 10px; }

.metric { display: flex; flex-direction: column; justify-content: center; padding: 16px 18px; transition: transform .24s cubic-bezier(.22,.8,.3,1), box-shadow .24s ease; }
.metric:hover { transform: translateY(-2px); box-shadow: 0 10px 22px rgba(120,70,40,.08); }
.metric-value { font-size: 27px; font-weight: 650; letter-spacing: -1px; margin-top: 4px; line-height: 1.15; }
.metric-label { font-size: 13px; color: var(--dya-ink); margin-top: 2px; }
.metric-hint { font-size: 11px; color: var(--dya-muted); margin-top: 4px; }

.minor-strip { margin-top: 14px; display: grid; grid-template-columns: repeat(4, 1fr); padding: 12px 8px; }
.direct-strip { margin-top: 14px; padding: 16px 18px; }
.direct-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.direct-grid { display: grid; grid-template-columns: repeat(3, 1fr); }
.direct-hint { margin: 10px 0 0; font-size: 11px; line-height: 1.6; color: var(--dya-muted); }
.minor-value.sm { font-size: 15px; }
.minor { text-align: center; border-right: 1px solid var(--dya-line); }
.minor:last-child { border-right: 0; }
.minor-value { font-size: 19px; font-weight: 600; }
.minor-label { font-size: 11px; color: var(--dya-muted); margin-top: 2px; }
</style>
