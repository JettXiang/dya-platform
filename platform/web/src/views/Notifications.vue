<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, getToken, getUser } from '../api'

const list = ref([])
const loading = ref(false)
const role = getUser().role || ''
const form = ref({ kind: 'daily', title: '', body: '', targetUserId: null })
const controller = new AbortController()

const unread = () => list.value.filter((n) => !n.read_flag).length

async function load() {
  loading.value = true
  try {
    const r = await api.notifications()
    list.value = r.data || []
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function markRead(row) {
  try {
    await api.readNotification(row.id)
    row.read_flag = 1
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function send() {
  if (!form.value.title) {
    ElMessage.warning('请填写标题')
    return
  }
  try {
    await api.sendNotification({
      kind: form.value.kind, title: form.value.title, body: form.value.body,
      targetUserId: form.value.targetUserId || null
    })
    ElMessage.success('已发送')
    form.value.title = ''
    form.value.body = ''
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function startStream() {
  try {
    const res = await fetch('/api/v1/events', {
      headers: { Authorization: 'Bearer ' + getToken() },
      signal: controller.signal
    })
    if (!res.ok || !res.body) return
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buf = ''
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      if (buf.indexOf('\n\n') >= 0) {
        if (buf.indexOf('data:') >= 0) load()
        buf = ''
      }
    }
  } catch (e) {
    /* 断线后由用户手动刷新 */
  }
}

onMounted(() => {
  load()
  startStream()
})
onBeforeUnmount(() => controller.abort())
</script>

<template>
  <div v-loading="loading">
    <div v-if="role === 'admin'" class="dya-card send-card">
      <div class="card-head">
        <div>
          <div class="card-eyebrow">BROADCAST</div>
          <div class="card-title">发送通知</div>
        </div>
      </div>
      <el-form inline>
        <el-form-item label="类型">
          <el-select v-model="form.kind" style="width: 100px">
            <el-option label="日常" value="daily" />
            <el-option label="告警" value="alert" />
            <el-option label="发版" value="release" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="form.title" style="width: 220px" maxlength="128" />
        </el-form-item>
        <el-form-item label="正文">
          <el-input v-model="form.body" style="width: 300px" />
        </el-form-item>
        <el-form-item label="用户ID">
          <el-input v-model="form.targetUserId" style="width: 90px" placeholder="留空=全员" />
        </el-form-item>
        <el-button type="primary" @click="send">发送</el-button>
      </el-form>
    </div>

    <div class="dya-card">
      <div class="card-head">
        <div>
          <div class="card-eyebrow">NOTIFICATIONS</div>
          <div class="card-title">通知中心</div>
        </div>
        <div class="card-actions">
          <span class="count-chip">未读 {{ unread() }}</span>
          <el-button @click="load">刷新</el-button>
        </div>
      </div>
      <el-table :data="list" size="small" class="dya-table">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="kind" label="类型" width="90" />
        <el-table-column prop="title" label="标题" width="260" />
        <el-table-column prop="body" label="正文" />
        <el-table-column prop="created_at" label="时间" width="190" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.read_flag ? 'info' : 'warning'" effect="light" round>
              {{ row.read_flag ? '已读' : '未读' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="!!row.read_flag" @click="markRead(row)">标为已读</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.send-card { margin-bottom: 16px; }
</style>
