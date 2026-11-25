<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, getUser } from '../api'

const role = getUser().role || ''
const canUpload = role === 'admin' || role === 'uploader'
const canFilter = role === 'admin' || role === 'uploader'

const items = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const status = ref('')
const keyword = ref('')
const collector = ref('')
const terminal = ref('')
const collectorOptions = ref([])
const terminalOptions = ref([])
const range = ref([])
const stats = ref({})
const loading = ref(false)
const uploading = ref(false)
const selected = ref([])
const logs = ref([])
const logsVisible = ref(false)

const statusMap = {
  pending: { label: '待上传', type: 'warning' },
  uploaded: { label: '已上传', type: 'success' },
  failed: { label: '失败', type: 'danger' }
}

const cards = computed(() => [
  { key: 'total', label: '视频总数', value: stats.value.total || 0 },
  { key: 'pending', label: '待上传', value: stats.value.pending || 0 },
  { key: 'uploaded', label: '已上传', value: stats.value.uploaded || 0 },
  { key: 'failed', label: '上传失败', value: stats.value.failed || 0 },
  { key: 'today', label: '今日采集', value: stats.value.today || 0 }
])

function params(extra) {
  return {
    status: status.value,
    keyword: keyword.value,
    collector: canFilter ? collector.value : '',
    terminal: canFilter ? terminal.value : '',
    from: range.value && range.value[0] ? range.value[0] + ' 00:00:00' : '',
    to: range.value && range.value[1] ? range.value[1] + ' 23:59:59' : '',
    page: page.value,
    size: size.value,
    ...(extra || {})
  }
}

async function load() {
  loading.value = true
  try {
    const [list, st] = await Promise.all([api.videos(params()), api.videoStats(params())])
    items.value = (list.data && list.data.items) || []
    total.value = (list.data && list.data.total) || 0
    stats.value = st.data || {}
    // 筛选项取全量口径，避免选了采集人后下拉只剩一项
    if (!collector.value && !terminal.value) {
      collectorOptions.value = stats.value.collectors || []
      terminalOptions.value = stats.value.terminals || []
    }
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
}

function onSelection(rows) {
  selected.value = rows
}

function num(v) {
  const n = Number(v || 0)
  if (n >= 10000) return (n / 10000).toFixed(1) + 'w'
  return String(n)
}

function dt(s) {
  return s ? String(s).replace('T', ' ').slice(0, 19) : '—'
}

async function uploadSelected() {
  if (!selected.value.length) {
    ElMessage.warning('请先勾选视频')
    return
  }
  const secUids = new Set(selected.value.map((v) => v.author_sec_uid).filter(Boolean))
  try {
    await ElMessageBox.confirm(
      `将把 ${selected.value.length} 个视频对应的 ${secUids.size} 个作者 sec_uid 推送到上游生产接口，是否继续？`,
      '上传确认', { type: 'warning', confirmButtonText: '上传', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  doUpload({ ids: selected.value.map((v) => v.id) })
}

async function uploadAll() {
  const count = (stats.value.pending || 0) + (stats.value.failed || 0)
  try {
    await ElMessageBox.confirm(
      `将把全部未上传（待上传 + 失败，共 ${count} 个视频）的作者 sec_uid 推送到上游生产接口，是否继续？`,
      '一键全传', { type: 'warning', confirmButtonText: '全部上传', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  doUpload({ all: true })
}

async function doUpload(body) {
  uploading.value = true
  try {
    const res = await api.uploadVideos(body)
    const d = res.data || {}
    if (res.success) {
      ElMessage.success(
        `上传成功：提交 ${d.secUidCount} 个作者 / ${d.videoCount} 个视频，服务端新增 ${d.insertedCount ?? 0}，已存在 ${d.duplicateCount ?? 0}`
      )
    } else {
      ElMessage.error('上传失败：' + (d.error || res.message || '未知错误'))
    }
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    uploading.value = false
    selected.value = []
    await load()
  }
}

async function openLogs() {
  try {
    const res = await api.videoUploadLogs()
    logs.value = res.data || []
    logsVisible.value = true
  } catch (e) {
    ElMessage.error(e.message)
  }
}

let timer = null
onMounted(() => {
  load()
  timer = setInterval(load, 10000)
})
onUnmounted(() => timer && clearInterval(timer))
</script>

<template>
  <div class="video-page" v-loading="loading">
    <div class="stat-row">
      <div v-for="c in cards" :key="c.key" class="stat-card">
        <div class="stat-label">{{ c.label }}</div>
        <div class="stat-value">{{ c.value }}</div>
      </div>
    </div>

    <div class="dya-card">
      <div class="card-head">
        <div>
          <div class="card-eyebrow">VIDEOS</div>
          <div class="card-title">视频采集数据</div>
        </div>
        <div class="card-actions">
          <el-input v-model="keyword" placeholder="标题 / 作者 / aweme_id" clearable
                    style="width: 210px" @keyup.enter="search" @clear="search" />
          <el-select v-if="canFilter" v-model="collector" placeholder="全部采集人" clearable filterable
                     style="width: 150px" @change="search">
            <el-option v-for="c in collectorOptions" :key="c.collected_by"
                       :label="`${c.collected_by}（${c.cnt}）`" :value="c.collected_by" />
          </el-select>
          <el-select v-if="canFilter" v-model="terminal" placeholder="全部终端" clearable filterable
                     style="width: 140px" @change="search">
            <el-option v-for="t in terminalOptions" :key="t.terminal_code"
                       :label="`${t.terminal_code}（${t.cnt}）`" :value="t.terminal_code" />
          </el-select>
          <el-select v-model="status" placeholder="全部状态" clearable style="width: 130px" @change="search">
            <el-option label="待上传" value="pending" />
            <el-option label="已上传" value="uploaded" />
            <el-option label="失败" value="failed" />
          </el-select>
          <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD"
                          start-placeholder="开始" end-placeholder="结束" style="width: 240px"
                          @change="search" />
          <el-button @click="load">刷新</el-button>
          <el-button v-if="canUpload" :disabled="!selected.length" :loading="uploading" @click="uploadSelected">
            上传所选{{ selected.length ? '（' + selected.length + '）' : '' }}
          </el-button>
          <el-button v-if="canUpload" type="primary" :loading="uploading" @click="uploadAll">一键全传</el-button>
          <el-button link @click="openLogs">上传记录</el-button>
        </div>
      </div>

      <el-table :data="items" size="small" class="dya-table" @selection-change="onSelection">
        <el-table-column v-if="canUpload" type="selection" width="42" />
        <el-table-column label="封面" width="66">
          <template #default="{ row }">
            <a :href="row.url" target="_blank" rel="noreferrer">
              <img v-if="row.cover_url" :src="row.cover_url" class="thumb" alt="" referrerpolicy="no-referrer" />
              <span v-else class="thumb thumb-empty">视频</span>
            </a>
          </template>
        </el-table-column>
        <el-table-column label="视频" min-width="280">
          <template #default="{ row }">
            <a class="video-link" :href="row.url" target="_blank" rel="noreferrer">
              {{ row.title || '（无标题）' }}
            </a>
            <div class="video-sub">
              <span class="aweme">{{ row.aweme_id }}</span>
              <a class="url" :href="row.url" target="_blank" rel="noreferrer">{{ row.url }}</a>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="作者" width="170">
          <template #default="{ row }">
            <div class="author">{{ row.author_nickname || '—' }}</div>
            <div class="author-sec">{{ row.author_sec_uid || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="赞 / 评 / 藏" width="120">
          <template #default="{ row }">
            <span class="metrics">{{ num(row.digg_count) }} / {{ num(row.comment_count) }} / {{ num(row.collect_count) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="采集人" width="110">
          <template #default="{ row }">{{ row.collected_by || '—' }}</template>
        </el-table-column>
        <el-table-column label="终端" width="96">
          <template #default="{ row }">{{ row.terminal_code || '—' }}</template>
        </el-table-column>
        <el-table-column label="采集时间" width="160">
          <template #default="{ row }">{{ dt(row.collected_at) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <el-tooltip :content="row.upload_note || ''" :disabled="!row.upload_note" placement="top">
              <el-tag :type="(statusMap[row.status] || {}).type || 'info'" effect="light" round size="small">
                {{ (statusMap[row.status] || {}).label || row.status }}
              </el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="86" fixed="right">
          <template #default="{ row }">
            <a class="open-link" :href="row.url" target="_blank" rel="noreferrer">打开 ↗</a>
          </template>
        </el-table-column>
      </el-table>

      <div class="dya-actions">
        <span class="dya-hint">共 {{ total }} 条 · 每 10 秒自动刷新 · 最近采集 {{ dt(stats.last_collected_at) }}</span>
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="sizes, prev, pager, next"
          small
          style="margin-left: auto"
          @current-change="load"
          @size-change="search"
        />
      </div>
    </div>

    <el-dialog v-model="logsVisible" title="上传记录" width="820px">
      <el-table :data="logs" size="small" class="dya-table">
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ dt(row.started_at) }}</template>
        </el-table-column>
        <el-table-column prop="uploader_id" label="上传人" width="110" />
        <el-table-column label="视频/作者" width="100">
          <template #default="{ row }">{{ row.video_count }} / {{ row.sec_uid_count }}</template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag :type="row.success ? 'success' : 'danger'" size="small" round effect="light">
              {{ row.success ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="http_status" label="HTTP" width="70" />
        <el-table-column label="新增/重复" width="100">
          <template #default="{ row }">{{ row.inserted_count ?? '—' }} / {{ row.duplicate_count ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="error" label="错误" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </div>
</template>

<style scoped>
.video-page { max-width: 1280px; margin: 0 auto; padding-bottom: 8px; }
.stat-row { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 14px; margin-bottom: 22px; }
.stat-card {
  background: var(--dya-card); border: 1px solid var(--dya-line); border-radius: 12px;
  padding: 16px 18px; position: relative; overflow: hidden;
}
.stat-card::after {
  content: ''; position: absolute; right: -18px; bottom: -22px; width: 70px; height: 70px;
  border-radius: 50%; background: var(--dya-accent-soft);
}
.stat-label { font-size: 11px; letter-spacing: .08em; color: var(--dya-muted); }
.stat-value { font-size: 24px; font-weight: 650; margin-top: 4px; color: var(--dya-ink); position: relative; }
.thumb { width: 44px; height: 58px; border-radius: 6px; object-fit: cover; display: block; background: #f2eee8; }
.thumb-empty { display: flex; align-items: center; justify-content: center; font-size: 11px; color: var(--dya-muted); }
.video-link { color: var(--dya-ink); font-weight: 600; text-decoration: none; display: block; overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap; }
.video-link:hover { color: var(--dya-accent); }
.video-sub { display: flex; gap: 8px; align-items: center; margin-top: 3px; font-size: 11px; }
.aweme { color: var(--dya-muted); font-family: ui-monospace, monospace; }
.url { color: var(--dya-accent); text-decoration: none; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.url:hover { text-decoration: underline; }
.author { font-size: 12px; }
.author-sec { font-size: 10px; color: var(--dya-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.metrics { font-size: 12px; color: var(--dya-muted); }
.open-link { color: var(--dya-accent); text-decoration: none; font-size: 12px; }
</style>
