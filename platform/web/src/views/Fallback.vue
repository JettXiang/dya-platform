<script setup>
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const today = () => new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' })
const date = ref(today())
const batches = ref([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const r = await api.batchList(date.value)
    batches.value = (r.data || []).filter((b) => ['pending', 'failed', 'unknown'].includes(b.state))
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

watch(date, load)
onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="dya-card">
      <div class="card-head">
        <div>
          <div class="card-eyebrow">EMERGENCY FALLBACK</div>
          <div class="card-title">应急补传</div>
        </div>
        <span class="count-chip">未开放</span>
      </div>
      <div class="dya-note">
        <b>紧急预案当前只展示，不执行上传。</b>上传适配器被 G04（HTTPS/凭证/批准路径）与 X01（传输契约）阻断，入口保留，执行按钮禁用。
      </div>
      <ul class="dya-rules">
        <li>后台组接口故障，正常统一上传走不通</li>
        <li>负责人不在，需要临时授权人处理</li>
        <li>必须复用同一 worker.jar 的上传库与状态规则，不另写上传器</li>
        <li>批次标记 fallback，本地留痕，恢复后补同步</li>
      </ul>
    </div>

    <div class="dya-card">
      <div class="card-head">
        <div>
          <div class="card-eyebrow">CANDIDATES</div>
          <div class="card-title">候选批次</div>
        </div>
        <div class="card-actions">
          <span class="count-chip">{{ batches.length }}</span>
          <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" style="width: 150px" />
          <el-button @click="load">刷新</el-button>
        </div>
      </div>
      <el-table :data="batches" size="small" class="dya-table">
        <el-table-column prop="batch_no" label="批次号" width="180" />
        <el-table-column prop="type" label="类型" width="100" />
        <el-table-column prop="state" label="状态" width="110" />
        <el-table-column prop="total" label="成员" width="80" />
        <el-table-column prop="dedup_total" label="去重" width="80" />
        <el-table-column prop="attempt_count" label="尝试" width="80" />
      </el-table>
      <div class="dya-actions">
        <el-button type="warning" disabled>创建 fallback 请求</el-button>
        <el-button type="danger" disabled>执行 fallback 上传</el-button>
        <span class="dya-hint">解除 G04/X01 后开放</span>
      </div>
    </div>
  </div>
</template>
