<script setup>
import { reactive, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const filters = reactive({
  actor: '',
  action: '',
  range: [],
  success: ''
})
const items = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(50)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const params = {
      actor: filters.actor,
      action: filters.action,
      from: filters.range && filters.range[0] ? filters.range[0] : undefined,
      to: filters.range && filters.range[1] ? filters.range[1] : undefined,
      success: filters.success === '' ? undefined : filters.success,
      page: page.value,
      size: size.value
    }
    const res = await api.timeline(params)
    if (!res.success) {
      ElMessage.error(res.message || '查询失败')
      return
    }
    items.value = res.data.items || []
    total.value = res.data.total || 0
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function reset() {
  filters.actor = ''
  filters.action = ''
  filters.range = []
  filters.success = ''
  page.value = 1
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <el-form inline class="filters">
      <el-form-item label="操作人">
        <el-input v-model="filters.actor" placeholder="工号" clearable style="width: 140px" />
      </el-form-item>
      <el-form-item label="动作">
        <el-input v-model="filters.action" placeholder="如 建批" clearable style="width: 160px" />
      </el-form-item>
      <el-form-item label="时间">
        <el-date-picker
          v-model="filters.range"
          type="datetimerange"
          value-format="YYYY-MM-DD HH:mm:ss"
          start-placeholder="开始"
          end-placeholder="结束"
          style="width: 360px"
        />
      </el-form-item>
      <el-form-item label="结果">
        <el-select v-model="filters.success" style="width: 110px">
          <el-option label="全部" value="" />
          <el-option label="成功" :value="true" />
          <el-option label="失败" :value="false" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="loading" @click="page = 1; load()">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="items" v-loading="loading" size="small" border>
      <el-table-column prop="created_at" label="时间" width="170" />
      <el-table-column prop="operator" label="操作人" width="110" />
      <el-table-column prop="operator_type" label="类型" width="90" />
      <el-table-column prop="action" label="动作" width="120" />
      <el-table-column prop="method" label="方法" min-width="180" show-overflow-tooltip />
      <el-table-column prop="uri" label="URI" min-width="160" show-overflow-tooltip />
      <el-table-column label="结果" width="80">
        <template #default="{ row }">
          <el-tag :type="row.success ? 'success' : 'danger'" size="small">
            {{ row.success ? '成功' : '失败' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="duration_ms" label="耗时(ms)" width="90" />
      <el-table-column prop="error_message" label="错误" min-width="160" show-overflow-tooltip />
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        @current-change="load"
        @size-change="page = 1; load()"
      />
    </div>
  </div>
</template>

<style scoped>
.filters { margin-top: 12px; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
</style>
