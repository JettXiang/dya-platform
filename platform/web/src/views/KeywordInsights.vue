<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, getUser } from '../api'

/* 关键词周分析 + 词库维护
 * 采集员：可看周分析（含去重后的数据）、可提交新词等审批
 * 管理员：额外可维护词库、删除、导出、审批新词 */

const me = getUser()
const isAdmin = computed(() => String(me.role || '').toLowerCase() === 'admin')

const loading = ref(false)
const weekDate = ref(new Date())
const week = ref(isoWeek(new Date()))
const data = ref({ summary: {}, items: [], overlapAuthors: [] })
const tab = ref('analysis')

const summary = computed(() => data.value.summary || {})
const items = computed(() => data.value.items || [])
const overlap = computed(() => data.value.overlapAuthors || [])

function isoWeek(d) {
  const t = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()))
  const day = t.getUTCDay() || 7
  t.setUTCDate(t.getUTCDate() + 4 - day)
  const y = t.getUTCFullYear()
  const w = Math.ceil(((t - Date.UTC(y, 0, 1)) / 86400000 + 1) / 7)
  return `${y}-W${String(w).padStart(2, '0')}`
}

function fmt(v) {
  if (!v) return '—'
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return String(v)
  return d.toLocaleString('zh-CN', { hour12: false, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

async function loadAnalysis() {
  loading.value = true
  try {
    const r = await api.keywordAnalysis(week.value)
    data.value = (r && r.data) || { summary: {}, items: [], overlapAuthors: [] }
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function onWeekChange(v) {
  if (!v) return
  weekDate.value = v
  week.value = isoWeek(v)
  loadAnalysis()
}

function calcWeek() {
  weekDate.value = new Date()
  week.value = isoWeek(new Date())
  loadAnalysis()
}

async function exportCsv() {
  try {
    await api.exportKeywordAnalysis(week.value)
    ElMessage.success('已导出')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/* ============ 词库维护 ============ */
const keywords = ref([])
const newWord = ref('')
const keywordLoading = ref(false)

async function loadKeywords() {
  keywordLoading.value = true
  try {
    const r = await api.searchKeywords()
    keywords.value = (r && r.data) || []
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    keywordLoading.value = false
  }
}

async function addWord() {
  const w = newWord.value.trim()
  if (!w) {
    ElMessage.warning('先输入关键词')
    return
  }
  try {
    await api.addSearchKeyword(w)
    ElMessage.success('已加入词库')
    newWord.value = ''
    await loadKeywords()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function toggleWord(row, enabled) {
  try {
    await api.updateSearchKeyword(row.id, { enabled })
    ElMessage.success(enabled ? '已启用' : '已停用')
    await loadKeywords()
  } catch (e) {
    ElMessage.error(e.message)
    await loadKeywords()
  }
}

async function removeWord(row) {
  try {
    await ElMessageBox.confirm(`从词库删除「${row.keyword}」？已采集的数据不受影响。`, '确认删除', { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await api.deleteSearchKeyword(row.id)
    ElMessage.success('已删除')
    await loadKeywords()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function trendType(v) {
  if (!v) return 'info'
  if (v === '新增' || v === '明显上涨') return 'success'
  if (v === '明显下滑' || v.startsWith('-')) return 'danger'
  return 'success'
}

/* ============ 新词提交与审批 ============ */
const suggestions = ref([])
const suggestLoading = ref(false)

async function loadSuggestions() {
  suggestLoading.value = true
  try {
    const r = await api.keywordSuggestions()
    suggestions.value = (r && r.data) || []
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    suggestLoading.value = false
  }
}

/** 采集员：提交新词，等管理员审批 */
async function submitWord() {
  const w = newWord.value.trim()
  if (!w) {
    ElMessage.warning('先输入关键词')
    return
  }
  try {
    await api.suggestKeyword(w)
    ElMessage.success('已提交，等管理员审批')
    newWord.value = ''
    await loadSuggestions()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function approveWord(row) {
  try {
    await api.reviewKeywordSuggestion(row.id, true, '')
    ElMessage.success('已通过，词已进词库')
    await loadSuggestions()
    await loadKeywords()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function rejectWord(row) {
  let reason = ''
  try {
    const r = await ElMessageBox.prompt(`驳回「${row.keyword}」，写个原因：`, '驳回', {
      inputPlaceholder: '如：与现有词重复 / 产出太低',
      confirmButtonText: '驳回',
      cancelButtonText: '取消'
    })
    reason = (r && r.value) || ''
  } catch (e) {
    return
  }
  try {
    await api.reviewKeywordSuggestion(row.id, false, reason)
    ElMessage.success('已驳回')
    await loadSuggestions()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

const suggestionStatusText = (s) => (s === 'approved' ? '已通过' : s === 'rejected' ? '已驳回' : '待审批')
const suggestionStatusType = (s) => (s === 'approved' ? 'success' : s === 'rejected' ? 'danger' : 'warning')

onMounted(() => {
  loadAnalysis()
  loadKeywords()
  loadSuggestions()
})
</script>

<template>
  <div>
    <el-tabs v-model="tab">
      <el-tab-pane label="周分析" name="analysis">
        <div class="dya-card">
          <div class="card-head">
            <div>
              <div class="card-eyebrow">WEEKLY KEYWORD ANALYSIS</div>
              <div class="card-title">采集词产出周报 · {{ week }}</div>
            </div>
            <div class="card-actions">
              <el-date-picker v-model="weekDate" type="week" format="YYYY 第 ww 周"
                              placeholder="选一周" :clearable="false" style="width: 170px"
                              @change="onWeekChange" />
              <el-button plain @click="calcWeek">本周</el-button>
              <el-button plain :loading="loading" @click="loadAnalysis">刷新</el-button>
              <el-button v-if="isAdmin" type="primary" @click="exportCsv">导出 CSV</el-button>
            </div>
          </div>
          <div class="stat-row">
            <div class="stat"><div class="stat-k">本周采集量</div><div class="stat-v">{{ summary.videos || 0 }}</div></div>
            <div class="stat"><div class="stat-k">上周</div><div class="stat-v">{{ summary.prevVideos || 0 }}</div></div>
            <div class="stat"><div class="stat-k">在采关键词</div><div class="stat-v">{{ summary.keywords || 0 }}</div></div>
            <div class="stat"><div class="stat-k">覆盖作者</div><div class="stat-v">{{ summary.authors || 0 }}</div></div>
            <div class="stat"><div class="stat-k">跨词重复作者</div><div class="stat-v">{{ summary.multiKeywordAuthors || 0 }}</div></div>
            <div class="stat"><div class="stat-k">已上传</div><div class="stat-v">{{ summary.uploaded || 0 }}</div></div>
          </div>
          <div class="dya-note" style="margin-top: 12px">
            采集量按 aweme_id 去重统计，同一条视频只归首个命中的词；「跨词重复作者」指同一作者在本周被两个以上关键词采到，数字偏大说明词库存在重叠，可以合并或停用弱词。
          </div>
        </div>

        <div class="dya-card">
          <div class="card-head">
            <div>
              <div class="card-eyebrow">PER KEYWORD</div>
              <div class="card-title">每个词能采多少、能衍生什么</div>
            </div>
            <span class="count-chip">{{ items.length }} 个词</span>
          </div>
          <el-table :data="items" v-loading="loading" class="dya-table" size="small" max-height="520">
            <el-table-column prop="keyword" label="关键词" min-width="150">
              <template #default="{ row }">
                <span>{{ row.keyword }}</span>
                <el-tag v-if="Number(row.enabled) !== 1" size="small" type="info" class="tag">已停用</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="collected" label="本周采集" width="90" sortable />
            <el-table-column prop="prevCollected" label="上周" width="80" />
            <el-table-column label="环比" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="trendType(row.trend)">{{ row.trend }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="authors" label="作者数" width="80" />
            <el-table-column prop="collectors" label="采集人数" width="90" />
            <el-table-column prop="uploaded" label="已上传" width="80" />
            <el-table-column prop="uploadRate" label="上传转化" width="90" />
            <el-table-column prop="hitCount" label="累计搜过" width="90" />
            <el-table-column label="最近使用" width="130">
              <template #default="{ row }">{{ fmt(row.lastUsedAt) }}</template>
            </el-table-column>
            <el-table-column prop="suggestion" label="衍生建议" min-width="320" />
          </el-table>
        </div>

        <div class="dya-card">
          <div class="card-head">
            <div>
              <div class="card-eyebrow">DEDUPE SIGNAL</div>
              <div class="card-title">跨词重复作者 TOP10</div>
            </div>
            <span class="dya-hint">同一作者被多个词采到，提示词义重叠</span>
          </div>
          <el-table :data="overlap" class="dya-table" size="small">
            <el-table-column prop="nickname" label="作者" min-width="160" />
            <el-table-column prop="keywords" label="命中的关键词" min-width="260" />
            <el-table-column prop="videos" label="本周采集条数" width="120" />
          </el-table>
          <div v-if="!overlap.length" class="dya-hint" style="padding: 12px 0">本周没有跨词重复的作者。</div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="词库维护" name="library">
        <div class="dya-card">
          <div class="card-head">
            <div>
              <div class="card-eyebrow">KEYWORD LIBRARY</div>
              <div class="card-title">采集词库</div>
            </div>
            <div class="card-actions">
              <el-input v-model="newWord" :placeholder="isAdmin ? '手动补一个词，如 AI漫剧' : '想搜的词，提交给管理员审批'"
                        style="width: 240px" @keyup.enter="isAdmin ? addWord() : submitWord()" />
              <el-button v-if="isAdmin" type="primary" @click="addWord">加入词库</el-button>
              <el-button v-else type="primary" @click="submitWord">提交新词</el-button>
              <el-button plain :loading="keywordLoading" @click="loadKeywords">刷新</el-button>
            </div>
          </div>
          <div class="dya-note" style="margin-bottom: 12px">
            <template v-if="isAdmin">
              采集端每次点「搜索」都会自动把词记进来；这里可以手动补充、停用或删除。停用的词不会出现在随机推荐里，但历史统计仍保留。随机推荐每 5 分钟按人刷新一批，一次 10 个词，按近 7 天去重产出量分四色。
            </template>
            <template v-else>
              词库由管理员维护，你可以提交新词，管理员审批通过后会自动进词库。随机推荐每 5 分钟刷新一批，一次 10 个词；绿框产出最高，蓝框次之，黄框偏少，红框基本没量。
            </template>
          </div>
          <el-table :data="keywords" v-loading="keywordLoading" class="dya-table" size="small" max-height="560">
            <el-table-column prop="keyword" label="关键词" min-width="200" />
            <el-table-column label="来源" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.source === 'manual' ? 'warning' : 'info'">
                  {{ row.source === 'manual' ? '手动' : '自动' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column v-if="isAdmin" label="启用" width="90">
              <template #default="{ row }">
                <el-switch :model-value="Number(row.enabled) === 1" @change="(v) => toggleWord(row, v)" />
              </template>
            </el-table-column>
            <el-table-column prop="hit_count" label="累计搜过" width="100" />
            <el-table-column prop="collected_total" label="累计采集" width="100" />
            <el-table-column label="最近使用" width="140">
              <template #default="{ row }">{{ fmt(row.last_used_at) }}</template>
            </el-table-column>
            <el-table-column label="加入时间" width="140">
              <template #default="{ row }">{{ fmt(row.created_at) }}</template>
            </el-table-column>
            <el-table-column v-if="isAdmin" label="操作" width="90">
              <template #default="{ row }">
                <el-button link type="danger" @click="removeWord(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>

      <el-tab-pane :label="isAdmin ? '待审批新词' : '我的提交'" name="approval">
        <div class="dya-card">
          <div class="card-head">
            <div>
              <div class="card-eyebrow">KEYWORD APPROVAL</div>
              <div class="card-title">{{ isAdmin ? '新搜索词审批' : '我提交的词' }}</div>
            </div>
            <div class="card-actions">
              <span class="count-chip">{{ suggestions.length }} 条</span>
              <el-button plain :loading="suggestLoading" @click="loadSuggestions">刷新</el-button>
            </div>
          </div>
          <div class="dya-note" style="margin-bottom: 12px">
            <template v-if="isAdmin">通过后自动写进词库并参与随机推荐；驳回请填原因，提交人能看到。</template>
            <template v-else>提交后由管理员审批，通过后会出现在推荐词库里。</template>
          </div>
          <el-table :data="suggestions" v-loading="suggestLoading" class="dya-table" size="small" max-height="520">
            <el-table-column prop="keyword" label="关键词" min-width="180" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="suggestionStatusType(row.status)">{{ suggestionStatusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column v-if="isAdmin" prop="submitted_by" label="提交人" width="130" />
            <el-table-column label="提交时间" width="150">
              <template #default="{ row }">{{ fmt(row.submitted_at) }}</template>
            </el-table-column>
            <el-table-column prop="reason" label="驳回原因" min-width="180" />
            <el-table-column v-if="isAdmin" label="操作" width="150">
              <template #default="{ row }">
                <template v-if="row.status === 'pending'">
                  <el-button link type="success" @click="approveWord(row)">通过</el-button>
                  <el-button link type="danger" @click="rejectWord(row)">驳回</el-button>
                </template>
                <span v-else class="dya-hint">已处理</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.stat-row { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 12px; }
.stat { background: #faf8f4; border: 1px solid #f1ece5; border-radius: 10px; padding: 12px 14px; }
.stat-k { font-size: 11px; color: var(--dya-muted); margin-bottom: 6px; }
.stat-v { font-size: 22px; font-weight: 700; color: var(--dya-ink); }
.tag { margin-left: 6px; }
@media (max-width: 1100px) {
  .stat-row { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
</style>
