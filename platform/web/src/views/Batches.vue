<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, getUser } from '../api'

const today = () => new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' })
const date = ref(today())
const stateFilter = ref('')
const list = ref([])
const loading = ref(false)
const detail = ref(null)
const visible = ref(false)
const role = getUser().role || ''
const canOperate = role === 'admin' || role === 'uploader'

const filtered = computed(() =>
  list.value.filter((b) => !stateFilter.value || b.state === stateFilter.value))

const summary = computed(() => {
  const b = detail.value && detail.value.batch
  if (!b) return []
  return [
    { k: '类型', v: b.type },
    { k: '状态', v: b.state },
    { k: '成员', v: b.total },
    { k: '去重后', v: b.dedup_total },
    { k: '尝试次数', v: b.attempt_count },
    { k: '备注', v: b.note || '—' }
  ]
})

async function load() {
  loading.value = true
  try {
    const r = await api.batchList(date.value)
    list.value = r.data || []
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  try {
    const r = await api.batchDetail(row.batch_no)
    detail.value = r.data || {}
    visible.value = true
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function refreshDetail() {
  if (!detail.value) return
  const r = await api.batchDetail(detail.value.batch.batch_no)
  detail.value = r.data || {}
}

async function doVoid() {
  const b = detail.value.batch
  const reason = await ElMessageBox.prompt('作废原因', '作废批次').then((x) => x.value).catch(() => null)
  if (!reason) return
  try {
    await api.voidBatch(b.batch_no, { reason })
    ElMessage.success('已作废')
    await refreshDetail()
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function doReconcile(result) {
  const b = detail.value.batch
  const reason = await ElMessageBox.prompt('对账原因', result === 'received' ? '确认已接收' : '确认未收到')
    .then((x) => x.value).catch(() => null)
  if (!reason) return
  try {
    await api.reconcileBatch(b.batch_no, { reason })
    ElMessage.success('对账已记录')
    await refreshDetail()
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function requestUpload() {
  const reason = await ElMessageBox.prompt('补传原因', '请求补传 ' + date.value)
    .then((x) => x.value).catch(() => null)
  if (!reason) return
  try {
    const r = await api.uploadRequest({ date: date.value, reason })
    ElMessage.success(r.message === 'exists' ? '该日期已有补传请求' : '补传请求已提交')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function stateType(s) {
  if (s === 'success') return 'success'
  if (s === 'failed') return 'danger'
  if (s === 'unknown') return 'warning'
  if (s === 'cancelled' || s === 'rolled_back') return 'info'
  return ''
}

function hhmm(s) {
  return s ? String(s).slice(11, 16) : '—'
}

watch(date, load)
onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="dya-card">
      <div class="card-head">
        <div>
          <div class="card-eyebrow">BATCHES</div>
          <div class="card-title">批次中心</div>
        </div>
        <div class="card-actions">
          <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" style="width: 150px" />
          <el-select v-model="stateFilter" placeholder="全部状态" clearable style="width: 140px">
            <el-option v-for="s in ['pending','uploading','unknown','success','failed','cancelled','rolled_back']"
                       :key="s" :label="s" :value="s" />
          </el-select>
          <el-button @click="load">刷新</el-button>
          <el-button v-if="canOperate" type="primary" @click="requestUpload">请求补传</el-button>
        </div>
      </div>

      <el-table :data="filtered" size="small" class="dya-table">
        <el-table-column prop="batch_no" label="批次号" min-width="150" />
        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <el-tag :type="stateType(row.state)" effect="light" round size="small">{{ row.state }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="成员/尝试" width="100">
          <template #default="{ row }">{{ row.total }} / {{ row.attempt_count }}</template>
        </el-table-column>
        <el-table-column label="创建" width="76">
          <template #default="{ row }">{{ hhmm(row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="64">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="dya-actions">
        <span class="dya-hint">{{ date }} 共 {{ filtered.length }} 个批次</span>
      </div>
    </div>

    <el-drawer v-model="visible" size="62%" :with-header="false">
      <template v-if="detail">
        <div class="drawer-head">
          <div>
            <div class="card-eyebrow">BATCH DETAIL</div>
            <div class="card-title">{{ detail.batch.batch_no }}</div>
          </div>
          <el-button text @click="visible = false">关闭</el-button>
        </div>

        <div class="kv">
          <div v-for="item in summary" :key="item.k" class="kv-item">
            <div class="kv-k">{{ item.k }}</div>
            <div class="kv-v">{{ item.v }}</div>
          </div>
        </div>

        <div v-if="canOperate" class="dya-actions">
          <el-button size="small" type="danger" plain @click="doVoid">作废</el-button>
          <el-button size="small" type="success" plain @click="doReconcile('received')">对账已接收</el-button>
          <el-button size="small" type="warning" plain @click="doReconcile('not_received')">对账未收到</el-button>
        </div>

        <div class="section-head">
          <div class="card-eyebrow">UPLOAD ATTEMPTS</div>
          <div class="card-title">上传尝试</div>
        </div>
        <el-table :data="detail.attempts || []" size="small" class="dya-table">
          <el-table-column prop="attempt_no" label="#" width="50" />
          <el-table-column prop="started_at" label="开始" width="180" />
          <el-table-column prop="transport_result" label="传输" width="100" />
          <el-table-column prop="business_result" label="业务" width="100" />
          <el-table-column prop="http_status" label="HTTP" width="70" />
          <el-table-column prop="error" label="错误" />
        </el-table>

        <div class="section-head">
          <div class="card-eyebrow">MEMBER STATES</div>
          <div class="card-title">成员确认态</div>
        </div>
        <el-table :data="detail.items || []" size="small" class="dya-table" height="300">
          <el-table-column prop="seq" label="#" width="50" />
          <el-table-column prop="sec_uid" label="sec_uid" />
          <el-table-column prop="confirm_state" label="确认态" width="110" />
          <el-table-column prop="confirm_source" label="来源" width="90" />
          <el-table-column prop="confirm_evidence" label="证据" />
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.drawer-head { display: flex; align-items: flex-end; justify-content: space-between; padding-bottom: 12px; border-bottom: 1px solid var(--dya-line); }
.section-head { margin: 20px 0 8px; padding-top: 14px; border-top: 1px solid var(--dya-line); }
</style>
