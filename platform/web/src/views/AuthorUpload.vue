<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'

/* 汇总上传：按作者 sec_uid 汇总待推清单，勾选后整批推到上游旧通道。
 * 去重口径是作者级永久台账（author_uploads），推成功过的作者不会再出现。 */

const today = () => {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
const nextDay = (text) => {
  const d = new Date(text + 'T00:00:00')
  d.setDate(d.getDate() + 1)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const range = ref([today(), today()])
const collector = ref('')
const rows = ref([])
const history = ref([])
const stats = ref({ pendingAuthors: 0, pendingVideos: 0, pushedAuthors: 0 })
const selected = ref([])
const loading = ref(false)
const pushing = ref(false)

const collectorOptions = computed(() => {
  const set = new Set()
  rows.value.forEach((r) => String(r.collectors || '').split(',').forEach((c) => c && set.add(c)))
  return Array.from(set)
})

const params = () => ({
  from: range.value && range.value[0] ? range.value[0] : '',
  to: range.value && range.value[1] ? nextDay(range.value[1]) : '',
  collector: collector.value
})

async function load() {
  loading.value = true
  try {
    const [p, s] = await Promise.all([
      api.authorUploadPending({ ...params(), limit: 2000 }),
      api.authorUploadStats(params())
    ])
    rows.value = ((p && p.data) || []).map((r) => ({
      secUid: r.secUid || r.sec_uid,
      nickname: r.nickname || '-',
      videoCount: Number(r.videoCount || 0),
      collectors: r.collectors || '',
      lastCollectedAt: r.lastCollectedAt || ''
    }))
    stats.value = (s && s.data) || stats.value
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
  loadHistory()
}

async function loadHistory() {
  try {
    const r = await api.authorUploadHistory(50)
    history.value = ((r && r.data) || []).map((x) => ({
      ...x,
      secUid: x.secUid || x.sec_uid
    }))
  } catch (e) {
    /* 台账接口单独失败不打扰主流程 */
  }
}

function onSelectionChange(list) {
  selected.value = list.map((r) => r.secUid)
}

function selectAll() {
  selected.value = rows.value.map((r) => r.secUid)
  ElMessage.success(`已选 ${selected.value.length} 个作者`)
}

async function push() {
  if (!selected.value.length) {
    ElMessage.warning('先勾选要推送的作者')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将 ${selected.value.length} 个作者（sec_uid）推送到上游，成功后这批作者不会再出现在待推列表。继续？`,
      '确认推送',
      { type: 'warning', confirmButtonText: '推送', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  pushing.value = true
  try {
    const r = await api.authorUploadPush(selected.value)
    const d = (r && r.data) || {}
    const text = [
      `提交 ${d.requestedCount || 0} 个，实推 ${d.pushedCount || 0} 个`,
      `影响视频 ${d.videoCount || 0} 条`,
      d.insertedCount === undefined ? '' : `上游新增 ${d.insertedCount}`,
      d.duplicateCount === undefined ? '' : `上游重复 ${d.duplicateCount}`,
      `耗时 ${Math.round((d.durationMs || 0) / 100) / 10}s`
    ].filter(Boolean).join(' · ')
    if (d.success) {
      ElMessage.success(text)
    } else {
      ElMessage.error((d.message || '推送失败') + ' · ' + text)
    }
    selected.value = []
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    pushing.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="upload-page">
    <div class="dya-card head">
      <div class="head-left">
        <div class="card-eyebrow">AUTHOR UPLOAD</div>
        <div class="card-title">汇总上传</div>
        <div class="hint">按作者 sec_uid 汇总，推成功过的作者永久去重；同一批最多 10000 条。</div>
      </div>
      <div class="head-right">
        <span class="chip">待推作者 <b>{{ stats.pendingAuthors }}</b></span>
        <span class="chip">待推视频 <b>{{ stats.pendingVideos }}</b></span>
        <span class="chip done">已推作者 <b>{{ stats.pushedAuthors }}</b></span>
      </div>
    </div>

    <div class="dya-card">
      <div class="card-head">
        <div>
          <div class="card-eyebrow">PENDING AUTHORS</div>
          <div class="card-title">待推清单</div>
        </div>
        <div class="card-actions">
          <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD"
                          start-placeholder="开始日期" end-placeholder="结束日期"
                          :clearable="false" style="width: 250px" @change="load" />
          <el-select v-model="collector" placeholder="采集人（全部）" clearable style="width: 150px" @change="load">
            <el-option v-for="c in collectorOptions" :key="c" :label="c" :value="c" />
          </el-select>
          <el-button @click="load" :loading="loading">刷新</el-button>
          <el-button @click="selectAll" :disabled="!rows.length">全选</el-button>
          <el-button type="primary" :loading="pushing" :disabled="!selected.length" @click="push">
            推送所选（{{ selected.length }}）
          </el-button>
        </div>
      </div>

      <el-table :data="rows" size="small" class="dya-table" max-height="440" empty-text="这段时间没有待推作者"
                @selection-change="onSelectionChange">
        <el-table-column type="selection" width="42" />
        <el-table-column prop="secUid" label="作者 sec_uid" min-width="220" show-overflow-tooltip />
        <el-table-column prop="nickname" label="昵称" min-width="120" show-overflow-tooltip />
        <el-table-column prop="videoCount" label="视频数" width="80" align="right" />
        <el-table-column prop="collectors" label="采集人" width="150" show-overflow-tooltip />
        <el-table-column label="最近采集" width="160">
          <template #default="{ row }">{{ row.lastCollectedAt || '-' }}</template>
        </el-table-column>
      </el-table>
      <div class="foot">
        <span>当前列出 {{ rows.length }} 个作者（最多 2000 条，超出请缩小日期范围）</span>
        <span>勾选后点「推送所选」，结果 2 秒内返回</span>
      </div>
    </div>

    <div class="dya-card">
      <div class="card-head">
        <div>
          <div class="card-eyebrow">LEDGER</div>
          <div class="card-title">推送台账（最近 50 条）</div>
        </div>
        <el-button size="small" @click="loadHistory">刷新</el-button>
      </div>
      <el-table :data="history" size="small" class="dya-table" max-height="360" empty-text="还没有推送记录">
        <el-table-column prop="secUid" label="作者 sec_uid" min-width="220" show-overflow-tooltip />
        <el-table-column label="结果" width="80">
          <template #default="{ row }">
            <el-tag :type="row.success ? 'success' : 'danger'" effect="light" round>
              {{ row.success ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="pushCount" label="次数" width="64" align="right" />
        <el-table-column label="首次推送" width="160">
          <template #default="{ row }">{{ row.firstPushedAt || '-' }}</template>
        </el-table-column>
        <el-table-column label="最近推送" width="160">
          <template #default="{ row }">{{ row.lastPushedAt || '-' }}</template>
        </el-table-column>
        <el-table-column prop="uploaderId" label="操作人" width="100" />
        <el-table-column prop="httpStatus" label="HTTP" width="70" align="right" />
        <el-table-column label="上游新增/重复" width="110" align="right">
          <template #default="{ row }">{{ row.remoteInserted ?? '-' }} / {{ row.remoteDuplicate ?? '-' }}</template>
        </el-table-column>
        <el-table-column prop="lastResult" label="说明" min-width="180" show-overflow-tooltip />
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.upload-page { display: flex; flex-direction: column; gap: 14px; }
.head { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.head-right { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.chip { font-size: 11px; padding: 5px 11px; border-radius: 999px; background: var(--dya-accent-soft); color: var(--dya-accent); }
.chip b { font-size: 13px; }
.chip.done { background: rgba(34, 176, 125, .12); color: #1c8f66; }
.hint { font-size: 11px; color: var(--dya-muted); margin-top: 6px; line-height: 1.6; }
.foot { display: flex; justify-content: space-between; gap: 12px; margin-top: 10px; font-size: 11px; color: var(--dya-muted); }
</style>
