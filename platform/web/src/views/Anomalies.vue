<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'

function today() {
  const d = new Date()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return d.getFullYear() + '-' + m + '-' + day
}

const date = ref(today())
const type = ref('all')
const loading = ref(false)
const data = ref({})

async function load() {
  loading.value = true
  try {
    const res = await api.anomalies({ date: date.value, type: type.value })
    if (!res.success) {
      ElMessage.error(res.message || '查询失败')
      return
    }
    data.value = res.data || {}
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="dya-card">
      <div class="card-head">
        <div>
          <div class="card-eyebrow">ANOMALIES</div>
          <div class="card-title">异常与阻断</div>
        </div>
        <div class="card-actions">
          <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" style="width: 150px" />
          <el-select v-model="type" style="width: 150px">
            <el-option label="全部" value="all" />
            <el-option label="unknown 冻结" value="unknown" />
            <el-option label="超时" value="timeout" />
            <el-option label="告警未读" value="alert_unread" />
            <el-option label="离线账号" value="offline" />
          </el-select>
          <el-button type="primary" :loading="loading" @click="load">查询</el-button>
        </div>
      </div>
      <div class="dya-note">
        <b>处理顺序：</b>unknown 冻结批次先对账 → 超时尝试重试 → 未读告警确认 → 离线账号排查心跳。
      </div>
    </div>

    <div v-if="data.unknown_batches" class="dya-card">
      <div class="card-head">
        <div>
          <div class="card-eyebrow">UNKNOWN FROZEN</div>
          <div class="card-title">unknown 冻结批次</div>
        </div>
        <span class="count-chip">{{ data.unknown_batches.length }}</span>
      </div>
      <el-table :data="data.unknown_batches" size="small" class="dya-table">
        <el-table-column prop="batch_no" label="批次号" width="180" />
        <el-table-column prop="state" label="状态" width="120" />
        <el-table-column prop="attempt_count" label="尝试" width="90" />
        <el-table-column prop="created_at" label="建批时间" width="180" />
      </el-table>
    </div>

    <div v-if="data.timeout_attempts" class="dya-card">
      <div class="card-head">
        <div>
          <div class="card-eyebrow">TIMEOUT</div>
          <div class="card-title">超时尝试</div>
        </div>
        <span class="count-chip">{{ data.timeout_attempts.length }}</span>
      </div>
      <el-table :data="data.timeout_attempts" size="small" class="dya-table">
        <el-table-column prop="batch_no" label="批次号" width="180" />
        <el-table-column prop="attempt_no" label="#" width="60" />
        <el-table-column prop="started_at" label="开始" width="180" />
        <el-table-column prop="duration_ms" label="耗时(ms)" width="100" />
      </el-table>
    </div>

    <div v-if="data.unread_alerts" class="dya-card">
      <div class="card-head">
        <div>
          <div class="card-eyebrow">UNREAD ALERTS</div>
          <div class="card-title">未读告警</div>
        </div>
        <span class="count-chip">{{ data.unread_alerts.length }}</span>
      </div>
      <el-table :data="data.unread_alerts" size="small" class="dya-table">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="title" label="标题" min-width="220" />
        <el-table-column prop="created_at" label="时间" width="180" />
      </el-table>
    </div>

    <div v-if="data.offline_accounts" class="dya-card">
      <div class="card-head">
        <div>
          <div class="card-eyebrow">OFFLINE</div>
          <div class="card-title">离线账号</div>
        </div>
        <span class="count-chip">{{ data.offline_accounts.length }}</span>
      </div>
      <el-table :data="data.offline_accounts" size="small" class="dya-table">
        <el-table-column prop="code" label="账号" width="160" />
        <el-table-column prop="state" label="状态" width="120" />
        <el-table-column prop="last_heartbeat_at" label="最后心跳" width="180" />
      </el-table>
    </div>

    <div v-if="!loading && Object.keys(data).length === 0" class="dya-card">
      <el-empty description="没有异常记录" :image-size="72" />
    </div>
  </div>
</template>
