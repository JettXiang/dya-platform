<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const batchNo = ref('')
const loading = ref(false)
const batch = ref(null)
const attempts = ref([])
const confirmStates = ref([])
const operations = ref([])

const summary = computed(() => {
  const b = batch.value
  if (!b) return []
  return [
    { k: '日期', v: b.date },
    { k: '类型', v: b.type },
    { k: '状态', v: b.state },
    { k: '总数', v: b.total },
    { k: '去重后', v: b.dedup_total },
    { k: '尝试次数', v: b.attempt_count },
    { k: '可重试', v: b.retryable ? '是' : '否' },
    { k: '建批时间', v: b.created_at },
    { k: '封账时间', v: b.closed_at || '—' },
    { k: '说明', v: b.note || '—' }
  ]
})

async function load() {
  if (!batchNo.value) {
    ElMessage.warning('请输入批次号')
    return
  }
  loading.value = true
  try {
    const res = await api.chain(batchNo.value.trim())
    if (!res.success) {
      ElMessage.error(res.message || '查询失败')
      batch.value = null
      return
    }
    batch.value = res.data.batch
    attempts.value = res.data.attempts || []
    confirmStates.value = res.data.confirm_states || []
    operations.value = res.data.operations || []
  } catch (e) {
    ElMessage.error(e.message)
    batch.value = null
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div v-loading="loading">
    <div class="dya-card">
      <div class="card-head">
        <div>
          <div class="card-eyebrow">BATCH CHAIN</div>
          <div class="card-title">批次链路</div>
        </div>
        <div class="card-actions">
          <el-input v-model="batchNo" placeholder="如 B20261106-P-01" style="width: 240px" @keyup.enter="load" />
          <el-button type="primary" :loading="loading" @click="load">查链路</el-button>
        </div>
      </div>
      <div v-if="!batch" class="dya-hint">输入批次号，查看分片上传、服务端回执与状态流转。</div>
    </div>

    <template v-if="batch">
      <div class="dya-card">
        <div class="card-head">
          <div>
            <div class="card-eyebrow">SUMMARY</div>
            <div class="card-title">{{ batch.batch_no }}</div>
          </div>
          <el-tag effect="light" round size="small">{{ batch.state }}</el-tag>
        </div>
        <div class="kv">
          <div v-for="item in summary" :key="item.k" class="kv-item">
            <div class="kv-k">{{ item.k }}</div>
            <div class="kv-v">{{ item.v }}</div>
          </div>
        </div>
      </div>

      <div class="dya-card">
        <div class="card-head">
          <div>
            <div class="card-eyebrow">UPLOAD ATTEMPTS</div>
            <div class="card-title">上传尝试</div>
          </div>
          <span class="count-chip">{{ attempts.length }} 次</span>
        </div>
        <el-table :data="attempts" size="small" class="dya-table">
          <el-table-column prop="attempt_no" label="#" width="60" />
          <el-table-column prop="started_at" label="开始" width="170" />
          <el-table-column prop="ended_at" label="结束" width="170" />
          <el-table-column prop="duration_ms" label="耗时(ms)" width="90" />
          <el-table-column prop="transport_result" label="传输" width="110" />
          <el-table-column prop="business_result" label="业务" width="110" />
          <el-table-column prop="http_status" label="HTTP" width="80" />
          <el-table-column prop="error" label="错误" min-width="160" show-overflow-tooltip />
        </el-table>
      </div>

      <div class="dya-card">
        <div class="card-head">
          <div>
            <div class="card-eyebrow">MEMBER CONFIRM</div>
            <div class="card-title">成员确认态</div>
          </div>
          <span class="count-chip">{{ confirmStates.length }} 组</span>
        </div>
        <el-table :data="confirmStates" size="small" class="dya-table">
          <el-table-column prop="confirm_state" label="状态" width="140" />
          <el-table-column prop="cnt" label="数量" width="100" />
        </el-table>
      </div>

      <div class="dya-card">
        <div class="card-head">
          <div>
            <div class="card-eyebrow">OPERATIONS</div>
            <div class="card-title">相关操作</div>
          </div>
          <span class="count-chip">{{ operations.length }} 条</span>
        </div>
        <el-table :data="operations" size="small" class="dya-table">
          <el-table-column prop="created_at" label="时间" width="170" />
          <el-table-column prop="operator" label="操作人" width="110" />
          <el-table-column prop="action" label="动作" width="120" />
          <el-table-column label="结果" width="80">
            <template #default="{ row }">
              <el-tag :type="row.success ? 'success' : 'danger'" effect="light" round size="small">
                {{ row.success ? '成功' : '失败' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="duration_ms" label="耗时(ms)" width="90" />
          <el-table-column prop="error_message" label="错误" min-width="160" show-overflow-tooltip />
        </el-table>
      </div>
    </template>
  </div>
</template>
