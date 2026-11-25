<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, getUser } from '../api'

/* AI 短剧达人：由「合集 + AI 声明」双命中作品聚合出的创作者。
 * 首见归因来自 authors.first_seen_collector，采集员只能看自己首见的人。 */

defineOptions({ name: 'AiDramaAuthors' })

const me = getUser()
const isAdmin = computed(() => ['admin', 'uploader'].includes(String(me.role || '').toLowerCase()))

const loading = ref(false)
const items = ref([])
const total = ref(0)
const summary = ref({ authors: 0, works: 0, mixes: 0 })
const page = ref(1)
const size = ref(20)
const keyword = ref('')

async function load() {
  loading.value = true
  try {
    const r = await api.aiDramaAuthors({ keyword: keyword.value.trim(), page: page.value, size: size.value })
    const d = (r && r.data) || {}
    items.value = d.items || []
    total.value = Number(d.total || 0)
    summary.value = d.summary || { authors: 0, works: 0, mixes: 0 }
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

function onPage(p) {
  page.value = p
  load()
}

async function exportCsv() {
  try {
    await api.exportAiDramaAuthors({ keyword: keyword.value.trim() })
    ElMessage.success('达人名单已下载')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function fmtTime(v) {
  if (!v) return '—'
  return new Date(v).toLocaleString('zh-CN', { hour12: false })
}

onMounted(load)
</script>

<template>
  <div>
    <div class="dya-card">
      <div class="card-head">
        <div>
          <div class="card-eyebrow">AI DRAMA CREATORS</div>
          <div class="card-title">
            AI 短剧达人
            <span class="count-chip">{{ summary.authors }} 人</span>
            <span class="count-chip">作品 {{ summary.works }}</span>
            <span class="count-chip">合集 {{ summary.mixes }}</span>
          </div>
        </div>
        <div class="card-actions">
          <el-input v-model="keyword" placeholder="昵称 / 抖音号 / sec_uid" clearable style="width: 240px"
                    @keyup.enter="search" @clear="search" />
          <el-button @click="search">查询</el-button>
          <el-button type="primary" plain @click="exportCsv">导出名单</el-button>
        </div>
      </div>
      <p class="dya-hint">
        只统计「挂载合集 + AI 短剧关键词命中（文案/合集名/作者资料）」双命中的作品；首见人 = 第一次采到该达人的采集账号。
      </p>

      <el-table :data="items" v-loading="loading" class="dya-table" size="small">
        <el-table-column label="达人" min-width="200">
          <template #default="{ row }">
            <div class="author">
              <img v-if="row.avatar_url" class="avatar" :src="row.avatar_url" alt="" />
              <div class="author-text">
                <div class="author-name">{{ row.nickname || '未命名' }}</div>
                <div class="author-id">{{ row.unique_id ? '@' + row.unique_id : row.sec_uid }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="works" label="作品数" width="90" sortable />
        <el-table-column prop="mixes" label="合集数" width="90" sortable />
        <el-table-column prop="digg_total" label="总点赞" width="110" sortable />
        <el-table-column prop="first_seen_collector" label="首见人" width="120">
          <template #default="{ row }">{{ row.first_seen_collector || '—' }}</template>
        </el-table-column>
        <el-table-column prop="first_seen_terminal" label="首见终端" width="150">
          <template #default="{ row }">{{ row.first_seen_terminal || '—' }}</template>
        </el-table-column>
        <el-table-column label="首见时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.first_collected_at) }}</template>
        </el-table-column>
        <el-table-column label="最近采集" width="170">
          <template #default="{ row }">{{ fmtTime(row.last_video_at) }}</template>
        </el-table-column>
        <el-table-column label="主页" width="90">
          <template #default="{ row }">
            <a class="link" :href="'https://www.douyin.com/user/' + row.sec_uid" target="_blank" rel="noreferrer">打开</a>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty">
            还没有双命中数据。去「搜索采集 / 精选采集」开始采集后，这里会按达人聚合。
          </div>
        </template>
      </el-table>

      <div class="pager">
        <el-pagination background layout="prev, pager, next, total" :total="total"
                       :page-size="size" :current-page="page" @current-change="onPage" />
      </div>
      <p v-if="!isAdmin" class="dya-hint">采集员账号仅显示自己首见的达人。</p>
    </div>
  </div>
</template>

<style scoped>
.author { display: flex; align-items: center; gap: 10px; }
.avatar { width: 34px; height: 34px; border-radius: 50%; object-fit: cover; background: #eee; }
.author-name { font-size: 13px; font-weight: 600; color: var(--dya-ink); }
.author-id { font-size: 11px; color: var(--dya-muted); margin-top: 2px; word-break: break-all; }
.link { color: var(--dya-accent); text-decoration: none; font-size: 12px; }
.pager { display: flex; justify-content: flex-end; margin-top: 14px; }
.empty { padding: 28px 0; color: var(--dya-muted); font-size: 12px; }
</style>
