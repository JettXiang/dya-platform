<script setup>
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const today = () => new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' })
const date = ref(today())
const rows = ref([])
const loading = ref(false)
const saving = ref(false)

async function load() {
  loading.value = true
  try {
    const r = await api.schedules(date.value)
    rows.value = (r.data || []).map((x) => ({ ...x, note: x.note || '' }))
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function saveAll() {
  const todo = rows.value.filter((r) => r.status)
  if (!todo.length) {
    ElMessage.warning('没有可保存的排班')
    return
  }
  saving.value = true
  try {
    for (const r of todo) {
      await api.saveSchedule({ date: date.value, accountCode: r.account_code, status: r.status, note: r.note })
    }
    ElMessage.success('已保存 ' + todo.length + ' 条排班')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
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
          <div class="card-eyebrow">DUTY SCHEDULE</div>
          <div class="card-title">排班</div>
        </div>
        <div class="card-actions">
          <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" style="width: 150px" />
          <el-button @click="load">刷新</el-button>
          <el-button type="primary" :loading="saving" @click="saveAll">保存全部</el-button>
        </div>
      </div>
      <el-table :data="rows" size="small" class="dya-table">
        <el-table-column prop="account_code" label="账号" width="170" />
        <el-table-column prop="name" label="姓名" width="130" />
        <el-table-column label="排班" width="170">
          <template #default="{ row }">
            <el-select v-model="row.status" placeholder="未排班" clearable style="width: 130px">
              <el-option label="在岗 work" value="work" />
              <el-option label="请假 leave" value="leave" />
              <el-option label="休假 vacation" value="vacation" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="备注">
          <template #default="{ row }"><el-input v-model="row.note" maxlength="255" placeholder="可留空" /></template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>
