<script setup>
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'

const tab = ref('users')
const loading = ref(false)
const users = ref([])
const accounts = ref([])
const roles = ['admin', 'uploader', 'reviewer', 'collector']
const states = ['idle', 'collecting', 'stopped', 'offline']

const userDialog = reactive({
  visible: false, editId: 0, code: '', name: '', role: 'collector', status: true, password: ''
})
const accountDialog = reactive({
  visible: false, editId: 0, code: '', userId: null, machineLabel: '', state: 'idle'
})

function stateType(s) {
  return s === 'collecting' ? 'success' : s === 'offline' ? 'danger' : s === 'stopped' ? 'info' : ''
}

async function load() {
  loading.value = true
  try {
    const [u, a] = await Promise.all([api.users(), api.accounts()])
    users.value = u.data || []
    accounts.value = a.data || []
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function openCreateUser() {
  Object.assign(userDialog, { visible: true, editId: 0, code: '', name: '', role: 'collector', status: true, password: '' })
}

function openEditUser(row) {
  Object.assign(userDialog, {
    visible: true, editId: row.id, code: row.code, name: row.name, role: row.role,
    status: Number(row.status) === 1, password: ''
  })
}

async function saveUser() {
  try {
    if (userDialog.editId) {
      await api.updateUser(userDialog.editId, {
        name: userDialog.name, role: userDialog.role, status: userDialog.status ? 1 : 0,
        password: userDialog.password || undefined
      })
      ElMessage.success(userDialog.password ? '已保存，旧令牌已失效' : '已保存')
    } else {
      await api.createUser({
        code: userDialog.code, name: userDialog.name, role: userDialog.role, password: userDialog.password
      })
      ElMessage.success('用户已创建')
    }
    userDialog.visible = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/* 停用 = 逻辑删除：账号、终端台账、采集数据全保留，可随时再启用 */
async function toggleUser(row) {
  const enabled = Number(row.status) === 1
  if (enabled) {
    try {
      await ElMessageBox.confirm(
        `停用「${row.name || row.code}」？停用后立即踢下线，采集数据与终端台账全部保留，可随时重新启用。`,
        '确认停用', { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' })
    } catch (e) {
      return
    }
  }
  try {
    if (enabled) {
      await api.disableUser(row.id)
      ElMessage.success('已停用')
    } else {
      await api.updateUser(row.id, { name: row.name, role: row.role, status: 1 })
      ElMessage.success('已启用')
    }
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function openCreateAccount() {
  Object.assign(accountDialog, { visible: true, editId: 0, code: '', userId: users.value[0] ? users.value[0].id : null, machineLabel: '', state: 'idle' })
}

function openEditAccount(row) {
  Object.assign(accountDialog, {
    visible: true, editId: row.id, code: row.code, userId: row.user_id,
    machineLabel: row.machine_label || '', state: row.state
  })
}

async function saveAccount() {
  try {
    if (accountDialog.editId) {
      await api.updateAccount(accountDialog.editId, {
        userId: accountDialog.userId, machineLabel: accountDialog.machineLabel, state: accountDialog.state
      })
      ElMessage.success('账号已保存')
    } else {
      await api.createAccount({
        code: accountDialog.code, userId: accountDialog.userId, machineLabel: accountDialog.machineLabel
      })
      ElMessage.success('账号已创建')
    }
    accountDialog.visible = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="dya-card">
      <div class="card-head">
        <div>
          <div class="card-eyebrow">ACCESS CONTROL</div>
          <div class="card-title">用户与账号</div>
        </div>
        <span class="count-chip">{{ users.length }} 用户 / {{ accounts.length }} 账号</span>
      </div>
      <el-tabs v-model="tab" class="dya-tabs">
      <el-tab-pane label="用户" name="users">
        <div class="dya-actions">
          <el-button type="primary" @click="openCreateUser">新增用户</el-button>
          <el-button @click="load">刷新</el-button>
        </div>
        <el-table :data="users" size="small" class="dya-table">
          <el-table-column prop="code" label="账号" min-width="130" />
          <el-table-column prop="name" label="姓名" width="120" />
          <el-table-column prop="role" label="角色" width="110" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="Number(row.status) === 1 ? 'success' : 'info'" effect="light" round size="small">
                {{ Number(row.status) === 1 ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="账号">
            <template #default="{ row }">
              <el-tag v-for="a in row.accounts" :key="a.id" size="small" class="tag"
                      :type="stateType(a.state)">{{ a.code }} · {{ a.state }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="160">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEditUser(row)">编辑</el-button>
              <el-button link :type="Number(row.status) === 1 ? 'danger' : 'success'" @click="toggleUser(row)">
                {{ Number(row.status) === 1 ? '停用' : '启用' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="账号" name="accounts">
        <div class="dya-actions">
          <el-button type="primary" @click="openCreateAccount">新增账号</el-button>
          <el-button @click="load">刷新</el-button>
        </div>
        <el-table :data="accounts" size="small" class="dya-table">
          <el-table-column prop="code" label="账号编码" width="150" />
          <el-table-column prop="user_code" label="绑定用户" width="120" />
          <el-table-column prop="machine_label" label="机器" width="140" />
          <el-table-column prop="state" label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="stateType(row.state)" effect="light" round size="small">{{ row.state }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="last_heartbeat_at" label="最近心跳" width="190" />
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEditAccount(row)">编辑</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
      </el-tabs>
    </div>

    <el-dialog v-model="userDialog.visible" :title="userDialog.editId ? '编辑用户' : '新增用户'" width="460px">
      <el-form label-width="90px">
        <el-form-item label="账号">
          <el-input v-model="userDialog.code" :disabled="!!userDialog.editId" maxlength="32"
                    placeholder="如 INTERN01XJH" />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="userDialog.name" maxlength="64" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="userDialog.role" style="width: 100%">
            <el-option v-for="r in roles" :key="r" :label="r" :value="r" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="userDialog.editId" label="启用">
          <el-switch v-model="userDialog.status" />
        </el-form-item>
        <el-form-item :label="userDialog.editId ? '重置密码' : '初始密码'">
          <el-input v-model="userDialog.password" type="password" show-password
                    :placeholder="userDialog.editId ? '留空则不修改' : '至少 6 位'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="userDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="saveUser">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="accountDialog.visible" :title="accountDialog.editId ? '编辑账号' : '新增账号'" width="460px">
      <el-form label-width="90px">
        <el-form-item label="账号编码">
          <el-input v-model="accountDialog.code" :disabled="!!accountDialog.editId" maxlength="32" placeholder="如 INTERN01-A2" />
        </el-form-item>
        <el-form-item label="绑定用户">
          <el-select v-model="accountDialog.userId" style="width: 100%">
            <el-option v-for="u in users" :key="u.id" :label="u.code + ' · ' + u.name" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="机器">
          <el-input v-model="accountDialog.machineLabel" maxlength="64" placeholder="可留空" />
        </el-form-item>
        <el-form-item v-if="accountDialog.editId" label="状态">
          <el-select v-model="accountDialog.state" style="width: 100%">
            <el-option v-for="s in states" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="accountDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="saveAccount">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tag { margin-right: 6px; }
.dya-tabs :deep(.el-tabs__header) { margin-bottom: 12px; }
.dya-tabs :deep(.el-tabs__item) { font-size: 13px; }
.dya-tabs :deep(.el-tabs__active-bar) { background: var(--dya-accent); }
.dya-tabs :deep(.el-tabs__item.is-active) { color: var(--dya-accent); }
</style>
