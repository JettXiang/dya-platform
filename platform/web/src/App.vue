<script setup>
import { computed, markRaw, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api, setSession, clearSession, getToken, getUser } from './api'
import Daily from './views/Daily.vue'
import FeedCollect from './views/FeedCollect.vue'
import SearchCollect from './views/SearchCollect.vue'
import VideoData from './views/VideoData.vue'
import AiDramaAuthors from './views/AiDramaAuthors.vue'
import AuthorUpload from './views/AuthorUpload.vue'
import Schedule from './views/Schedule.vue'
import Notifications from './views/Notifications.vue'
import Batches from './views/Batches.vue'
import Chain from './views/Chain.vue'
import Anomalies from './views/Anomalies.vue'
import KeywordInsights from './views/KeywordInsights.vue'
import Developer from './views/Developer.vue'
import Fallback from './views/Fallback.vue'
import Admin from './views/Admin.vue'
import logoUrl from './assets/dataeye-logo.png'

const token = ref(getToken())
const user = ref(getUser())
const code = ref('')
const password = ref('')
const loading = ref(false)

const views = {
  daily: markRaw(Daily),
  feed: markRaw(FeedCollect),
  search: markRaw(SearchCollect),
  videos: markRaw(VideoData),
  aidrama: markRaw(AiDramaAuthors),
  uploads: markRaw(AuthorUpload),
  schedule: markRaw(Schedule),
  notifications: markRaw(Notifications),
  batches: markRaw(Batches),
  chain: markRaw(Chain),
  anomalies: markRaw(Anomalies),
  keyword: markRaw(KeywordInsights),
  developer: markRaw(Developer),
  fallback: markRaw(Fallback),
  admin: markRaw(Admin)
}
// 支持 ?view=feed 之类直达；默认首屏是精选采集
const initialView = new URLSearchParams(location.search).get('view')
const active = ref(initialView && views[initialView] ? initialView : 'feed')
const currentView = computed(() => views[active.value] || views.daily)

const now = ref(new Date())
setInterval(() => { now.value = new Date() }, 60 * 1000)

const navGroups = computed(() => {
  // 采集员只保留采集动作相关的入口；管理员始终全量
  const role = String(user.value.role || '').toLowerCase()
  const allowed = role === 'collector'
    ? new Set(['daily', 'schedule', 'notifications', 'feed', 'search', 'videos', 'aidrama', 'keyword'])
    : role === 'uploader'
      ? new Set(['daily', 'notifications', 'videos', 'uploads', 'batches', 'chain'])
      : role === 'reviewer'
        ? new Set(['daily', 'notifications', 'videos', 'batches', 'chain', 'anomalies'])
        : null

  const groups = [
    {
      eyebrow: 'DAILY OPS',
      label: '日常工作区',
      items: [
        { key: 'daily', label: '今日总览' },
        { key: 'schedule', label: '排班' },
        { key: 'notifications', label: '通知中心' }
      ]
    },
    {
      eyebrow: 'LIVE COLLECTION',
      label: '实时采集区',
      items: [
        { key: 'feed', label: '精选采集' },
        { key: 'search', label: '搜索采集' },
        { key: 'videos', label: '视频数据' },
        { key: 'aidrama', label: 'AI 短剧达人' },
        { key: 'uploads', label: '汇总上传' }
      ]
    },
    {
      eyebrow: 'DATA REVIEW',
      label: '数据审查区',
      items: [
        { key: 'batches', label: '批次中心' },
        { key: 'chain', label: '批次链路' },
        { key: 'anomalies', label: '异常' },
        { key: 'keyword', label: '关键词分析' }
      ]
    },
    {
      eyebrow: 'DEVELOPER',
      label: '开发者模式',
      items: [{ key: 'developer', label: '传输参数' }]
    },
    {
      eyebrow: 'EMERGENCY',
      label: '紧急预案区',
      items: [{ key: 'fallback', label: '应急补传' }]
    }
  ]
  if (allowed) {
    return groups
      .map(g => ({ ...g, items: g.items.filter(i => allowed.has(i.key)) }))
      .filter(g => g.items.length > 0)
  }
  groups.push({
    eyebrow: 'ACCESS',
    label: '账号登录',
    items: [{ key: 'admin', label: '用户与账号' }]
  })
  return groups
})

const pageDesc = {
  daily: '当日账号采集完成度、批次与异常概览',
  feed: '抖音精选频道工作区，下滑即开始入库',
  search: '抖音搜索结果工作区，输关键词后开始采集',
  videos: '采集到的视频明细、采集人与上传状态',
  aidrama: '合集 + AI 声明双命中作品聚合出的 AI 短剧达人',
  uploads: '按作者汇总待推清单，勾选后推送并永久去重',
  schedule: '今日在岗、请假与调休安排',
  notifications: '站内通知、催办与已读状态',
  batches: '采集批次、成员确认与作废对账',
  chain: '分片上传、服务端回执与状态流转',
  anomalies: '采集异常、阻断项与处理建议',
  keyword: '采集词周报：产量、去重、衍生建议与词库维护',
  developer: '只读传输参数，供扩展与联调比对',
  fallback: '人工兜底补传入口（当前未开放）',
  admin: '账号、角色、密码与启用状态'
}

const activeLabel = computed(() => {
  for (const g of navGroups.value) {
    const item = g.items.find((x) => x.key === active.value)
    if (item) return item.label
  }
  return '今日总览'
})

const activeDesc = computed(() => pageDesc[active.value] || '')
const todayText = computed(() =>
  now.value.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }))
const greeting = computed(() => {
  const h = now.value.getHours()
  if (h < 6) return '夜深了'
  if (h < 11) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})
const avatarChar = computed(() => (user.value.name || user.value.code || 'D').slice(0, 1).toUpperCase())

async function login() {
  if (!code.value || !password.value) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  loading.value = true
  try {
    const res = await api.login(code.value, password.value)
    if (!res.success) {
      ElMessage.error(res.message || '登录失败')
      return
    }
    setSession(res.data.accessToken, res.data.user, res.data.refreshToken)
    localStorage.setItem('dya_last_login', new Date().toLocaleString('zh-CN', { hour12: false }))
    token.value = res.data.accessToken
    user.value = res.data.user
    active.value = views[initialView] ? initialView : 'feed'
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function logout() {
  try {
    await api.logout()
  } catch (e) {
    /* ignore */
  }
  clearSession()
  token.value = ''
  user.value = {}
}

/* ===== 侧边栏：手风琴 + 窄屏抽屉 ===== */
const drawerOpen = ref(false)
/** 已展开的分组（按 eyebrow 记），可多开、可单开，也可以全部收起 */
const openGroups = ref([])

function isGroupOpen(group) {
  return openGroups.value.includes(group.eyebrow)
}

function toggleGroup(group) {
  if (isGroupOpen(group)) {
    openGroups.value = openGroups.value.filter((k) => k !== group.eyebrow)
  } else {
    openGroups.value = [...openGroups.value, group.eyebrow]
  }
}

function selectNav(key) {
  active.value = key
  if (window.innerWidth <= 1080) {
    drawerOpen.value = false
  }
}

/** 切换页面时把它所在的分组展开，不收起其他已展开的分组 */
watch([navGroups, active], () => {
  const group = navGroups.value.find((g) => g.items.some((i) => i.key === active.value))
  if (group && !openGroups.value.includes(group.eyebrow)) {
    openGroups.value = [...openGroups.value, group.eyebrow]
  }
}, { immediate: true })

const ICON_PATHS = {
  daily: '<rect x="2" y="2" width="5" height="5" rx="1"/><rect x="9" y="2" width="5" height="5" rx="1"/><rect x="2" y="9" width="5" height="5" rx="1"/><rect x="9" y="9" width="5" height="5" rx="1"/>',
  schedule: '<rect x="2.5" y="3.5" width="11" height="10" rx="1.5"/><path d="M2.5 6.5h11M5.5 2v2.5M10.5 2v2.5"/>',
  notifications: '<path d="M8 2.5a3 3 0 0 1 3 3v2l1.2 2.2H3.8L5 7.5v-2a3 3 0 0 1 3-3Z"/><path d="M6.6 12.2a1.5 1.5 0 0 0 2.8 0"/>',
  feed: '<path d="M2 4.5h12M2 8h12M2 11.5h8"/>',
  search: '<circle cx="7" cy="7" r="4.2"/><path d="M10.2 10.2 13.5 13.5"/>',
  videos: '<rect x="2" y="3.5" width="12" height="9" rx="1.6"/><path d="M6.8 6.4l3 1.6-3 1.6z"/>',
  aidrama: '<circle cx="8" cy="5.6" r="2.4"/><path d="M3.4 13c.6-2.3 2.4-3.5 4.6-3.5s4 1.2 4.6 3.5"/>',
  uploads: '<path d="M8 10.5V2.8M5.2 5.4 8 2.6l2.8 2.8"/><path d="M2.6 9.5v3a1.5 1.5 0 0 0 1.5 1.5h7.8a1.5 1.5 0 0 0 1.5-1.5v-3"/>',
  batches: '<path d="M2 4.2 8 2l6 2.2V12L8 14l-6-2z"/><path d="M2 4.2 8 6.4l6-2.2M8 6.4V14"/>',
  chain: '<circle cx="3.5" cy="8" r="1.6"/><circle cx="12.5" cy="8" r="1.6"/><path d="M5.1 8h5.8"/>',
  anomalies: '<path d="M8 2.6 14 13H2z"/><path d="M8 6.5v3M8 11.3v.2"/>',
  keyword: '<path d="M2.4 13.2V9M6.2 13.2V5.4M10 13.2V7.6M13.8 13.2V3"/>',
  developer: '<path d="M5.4 5 2.6 8l2.8 3M10.6 5l2.8 3-2.8 3"/>',
  fallback: '<path d="M8 2.4 13.4 5v4.2c0 2.3-2.2 3.9-5.4 4.4-3.2-.5-5.4-2.1-5.4-4.4V5z"/>',
  admin: '<circle cx="8" cy="5.6" r="2.4"/><path d="M3.4 13c.6-2.3 2.4-3.5 4.6-3.5s4 1.2 4.6 3.5"/>'
}

function iconFor(key) {
  const path = ICON_PATHS[key] || '<circle cx="8" cy="8" r="3"/>'
  return '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round">' + path + '</svg>'
}
</script>

<template>
  <div v-if="!token" class="login-wrap">
    <div class="login-card">
      <aside class="login-side">
        <div class="ls-brand"><span class="ls-eye"></span> DataEye / DYA</div>
        <div class="ls-body">
          <div class="ls-eyebrow">DOUYIN ACCOUNT OPERATIONS</div>
          <h2 class="ls-title">让每一条采集数据<br />都有迹可循。</h2>
          <p class="ls-desc">抖音账号采集与批次回执的内部工作台。</p>
        </div>
        <div class="ls-foot">DATA, MADE TRACEABLE</div>
      </aside>

      <section class="login-form">
        <img class="lf-logo" :src="logoUrl" alt="DataEye" />
        <div class="lf-eyebrow">SECURE ACCESS</div>
        <h1 class="lf-title">抖音账号采集平台</h1>
        <p class="lf-sub">内部工作台 · DYA Platform</p>
        <div class="lf-field">
          <label>账号</label>
          <el-input v-model="code" placeholder="请输入账号" size="large" @keyup.enter="login" />
        </div>
        <div class="lf-field">
          <label>密码</label>
          <el-input v-model="password" type="password" show-password size="large"
                    placeholder="请输入密码" @keyup.enter="login" />
        </div>
        <button class="lf-submit" :disabled="loading" @click="login">
          {{ loading ? '登录中…' : '进入系统' }}<span class="lf-arrow">→</span>
        </button>
        <p class="lf-note">仅限内部授权使用 · 请勿外传</p>
      </section>
    </div>
  </div>

  <div v-else class="shell">
    <button class="sidebar-toggle" aria-label="展开侧边栏" @click="drawerOpen = true">&gt;</button>
    <div class="sidebar-backdrop" :class="{ on: drawerOpen }" @click="drawerOpen = false"></div>
    <aside class="sidebar" :class="{ 'drawer-open': drawerOpen }">
      <div class="brand">
        <div class="brand-badge">DYA</div>
        <div class="brand-text">
          <div class="brand-name">DataEye</div>
          <div class="brand-sub">DYA PLATFORM</div>
        </div>
      </div>
      <nav class="nav">
        <div v-for="g in navGroups" :key="g.eyebrow" class="nav-group" :class="{ open: isGroupOpen(g) }">
          <button class="nav-head" @click="toggleGroup(g)">
            <span class="nav-eye">{{ g.eyebrow }}</span>
            <span class="nav-glabel">{{ g.label }}</span>
            <i class="nav-chev"></i>
          </button>
          <div class="nav-body">
            <button v-for="item in g.items" :key="item.key" class="nav-item"
                    :class="{ active: active === item.key }"
                    @click="selectNav(item.key)">
              <span class="nav-ico" v-html="iconFor(item.key)"></span>
              <span>{{ item.label }}</span>
            </button>
          </div>
        </div>
      </nav>
      <div class="sidebar-user">
        <div class="user-avatar">{{ avatarChar }}</div>
        <div class="user-meta">
          <div class="user-name">{{ user.name || user.code }}</div>
          <div class="user-role">{{ user.role === 'admin' ? 'ADMIN' : 'USER' }}</div>
        </div>
        <button class="logout" @click="logout">退出</button>
      </div>
    </aside>

    <main class="main">
      <header class="topbar">
        <div class="hero">
          <div class="eyebrow">DOUYIN ACCOUNT OPERATIONS</div>
          <h1 :key="active" class="page-title">{{ activeLabel }}</h1>
          <p class="hero-sub">{{ activeDesc }}</p>
        </div>
        <div class="hero-right">
          <span class="greet">{{ greeting }}，{{ user.name || user.code }}</span>
          <span class="env-chip">PROD</span>
          <span class="today">{{ todayText }}</span>
        </div>
      </header>
      <section class="content">
        <Transition name="page" mode="out-in">
          <KeepAlive :include="['FeedCollect', 'SearchCollect']">
            <component :is="currentView" :key="active" />
          </KeepAlive>
        </Transition>
      </section>
    </main>
  </div>
</template>

<style>
:root {
  --dya-accent: #2b45d9;
  --dya-accent-soft: #eceffd;
  --dya-accent-dim: #1f33ad;
  --dya-side: #16204d;
  --dya-side-hover: rgba(255, 255, 255, .10);
  --dya-side-active: rgba(255, 255, 255, .16);
  --dya-ink: #1e2436;
  --dya-muted: #8b8b9a;
  --dya-line: #ebe6df;
  --dya-bg: #faf7f3;
  --dya-card: #fffdfa;
}
html, body, #app { height: 100%; margin: 0; }
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft YaHei', sans-serif;
  background:
    radial-gradient(900px 600px at 12% 8%, #dfe6ff 0%, transparent 60%),
    radial-gradient(800px 700px at 88% 18%, #ffe9d6 0%, transparent 62%),
    radial-gradient(700px 600px at 62% 96%, #d9f2ea 0%, transparent 60%),
    var(--dya-bg);
  background-attachment: fixed;
  min-height: 100vh;
  color: var(--dya-ink);
  -webkit-font-smoothing: antialiased;
}
.el-button--primary { --el-button-bg-color: var(--dya-accent); --el-button-border-color: var(--dya-accent); --el-button-hover-bg-color: var(--dya-accent-dim); --el-button-hover-border-color: var(--dya-accent-dim); }
.el-button { --el-button-bg-color: var(--dya-accent); --el-button-border-color: var(--dya-accent); --el-button-text-color: #fff; --el-button-hover-bg-color: var(--dya-accent-dim); --el-button-hover-border-color: var(--dya-accent-dim); --el-button-hover-text-color: #fff; --el-button-active-bg-color: var(--dya-accent-dim); --el-button-active-border-color: var(--dya-accent-dim); }
.el-button.is-disabled, .el-button.is-disabled:hover {
  --el-button-disabled-bg-color: #e8eaf2; --el-button-disabled-border-color: #e8eaf2; --el-button-disabled-text-color: #a3a7b8;
}
.el-button.is-link, .el-button.is-text { background: transparent; border-color: transparent; color: var(--dya-accent); }
.el-card { --el-card-border-color: var(--dya-line); border-radius: 12px; }

/* 登录页 */
.login-wrap {
  min-height: 100vh; display: flex; align-items: center; justify-content: center; padding: 40px 24px;
  background:
    radial-gradient(820px 520px at 10% 6%, #fdf1e4 0%, rgba(253, 241, 228, 0) 62%),
    radial-gradient(760px 520px at 94% 94%, #eef1fd 0%, rgba(238, 241, 253, 0) 60%),
    #faf7f3;
  position: relative; overflow: hidden;
}
.login-card {
  width: min(940px, 100%); display: grid; grid-template-columns: 1.02fr 1fr;
  border-radius: 28px; overflow: hidden; background: #fff;
  box-shadow: 0 32px 80px rgba(24, 32, 70, .10), 0 2px 0 rgba(255, 255, 255, .9) inset;
  animation: rise .7s cubic-bezier(.22,.8,.3,1) both;
}
@keyframes rise { from { opacity: 0; transform: translateY(18px) scale(.99); } to { opacity: 1; transform: translateY(0) scale(1); } }

.login-side {
  position: relative; background: var(--dya-side); color: #fff;
  padding: 40px 44px 34px; display: flex; flex-direction: column; min-height: 540px; overflow: hidden;
}
.login-side::before, .login-side::after {
  content: ''; position: absolute; border-radius: 50%; border: 1px solid rgba(255,255,255,.10);
  animation: breathe 11s ease-in-out infinite;
}
.login-side::before { width: 560px; height: 560px; right: -230px; top: 40px; }
.login-side::after { width: 360px; height: 360px; right: -110px; top: 150px; animation-delay: 1.4s; }
@keyframes breathe {
  0%, 100% { transform: scale(1); opacity: .55; }
  50% { transform: scale(1.05); opacity: .9; }
}
.ls-brand { display: flex; align-items: center; gap: 9px; font-size: 12px; letter-spacing: .5px; opacity: .92; position: relative; }
.ls-eye { width: 16px; height: 10px; border: 1.4px solid #fff; border-radius: 50%; position: relative; display: inline-block; }
.ls-eye::after { content: ''; position: absolute; left: 50%; top: 50%; width: 4px; height: 4px; margin: -2px 0 0 -2px; background: #fff; border-radius: 50%; }
.ls-body { flex: 1; display: flex; flex-direction: column; justify-content: center; position: relative; }
.ls-eyebrow { font-size: 10px; letter-spacing: 3px; color: rgba(255,255,255,.62); margin-bottom: 18px; }
.ls-title { margin: 0; font-size: 34px; line-height: 1.35; font-weight: 700; letter-spacing: -.5px; }
.ls-desc { margin: 16px 0 0; font-size: 13px; line-height: 1.9; color: rgba(255,255,255,.72); max-width: 300px; }
.ls-foot { font-size: 10px; letter-spacing: 2.4px; color: rgba(255,255,255,.42); position: relative; }

.login-form { padding: 44px 52px 36px; display: flex; flex-direction: column; }
.lf-logo {
  display: block; width: 122px; height: auto; margin: 0 auto 22px;
  animation: fade-up .6s .1s both;
}
.lf-eyebrow { font-size: 9px; letter-spacing: 2.6px; color: var(--dya-accent); font-weight: 700; margin-bottom: 8px; animation: fade-up .6s .16s both; }
.lf-title { margin: 0; font-size: 25px; font-weight: 700; letter-spacing: -.4px; animation: fade-up .6s .2s both; }
.lf-sub { margin: 7px 0 26px; font-size: 12px; color: var(--dya-muted); animation: fade-up .6s .24s both; }
@keyframes fade-up { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }
.lf-field { margin-bottom: 16px; animation: fade-up .6s .3s both; }
.lf-field label { display: block; font-size: 12px; color: #6c7080; margin-bottom: 7px; }
.lf-field .el-input__wrapper {
  border-radius: 12px; padding: 4px 14px; background: #fffdfa;
  box-shadow: 0 0 0 1px #e8e4de inset;
  transition: box-shadow .22s ease;
}
.lf-field .el-input__wrapper.is-focus { box-shadow: 0 0 0 1px var(--dya-accent) inset; }
.lf-submit {
  margin-top: 12px; height: 46px; border: 0; border-radius: 12px; cursor: pointer;
  background: var(--dya-accent); color: #fff; font-size: 14px; font-weight: 600; letter-spacing: .5px;
  display: flex; align-items: center; justify-content: center; gap: 8px;
  transition: transform .22s cubic-bezier(.22,.8,.3,1), background .22s ease, box-shadow .22s ease;
  box-shadow: 0 10px 24px rgba(43, 69, 217, .22);
  animation: fade-up .6s .36s both;
}
.lf-submit:hover { background: var(--dya-accent-dim); transform: translateY(-1px); box-shadow: 0 14px 28px rgba(43, 69, 217, .28); }
.lf-submit:disabled { opacity: .7; cursor: default; transform: none; }
.lf-arrow { transition: transform .22s ease; }
.lf-submit:hover .lf-arrow { transform: translateX(4px); }
.lf-note { margin: 18px 0 0; text-align: center; font-size: 11px; color: #a7a7b4; letter-spacing: .4px; }

@media (max-width: 820px) {
  .login-card { grid-template-columns: 1fr; }
  .login-side { display: none; }
  .login-form { padding: 36px 28px 30px; }
}

/* 外壳 */
.shell { display: flex; gap: 14px; padding: 14px; min-height: 100vh; align-items: flex-start; }
.sidebar {
  width: 236px; color: #fff; display: flex; flex-direction: column; gap: 6px;
  position: sticky; top: 14px; height: calc(100vh - 28px);
  padding: 14px 10px; border-radius: 22px;
  background: linear-gradient(170deg, rgba(22, 34, 66, .88), rgba(13, 21, 44, .82));
  border: 1px solid rgba(255, 255, 255, .14);
  box-shadow: 0 20px 50px rgba(10, 18, 40, .26), inset 0 1px 0 rgba(255, 255, 255, .18);
  backdrop-filter: blur(22px) saturate(150%);
  -webkit-backdrop-filter: blur(22px) saturate(150%);
  z-index: 50;
}
.brand { display: flex; align-items: center; gap: 10px; padding: 2px 8px 14px; }
.brand-badge {
  width: 34px; height: 34px; line-height: 34px; text-align: center; border-radius: 10px;
  background: #fff; color: var(--dya-accent); font-size: 11px; font-weight: 800; letter-spacing: .5px;
  box-shadow: 0 6px 16px rgba(6, 12, 30, .35), inset 0 -1px 0 rgba(43, 69, 217, .18);
}
.brand-name { font-size: 14px; font-weight: 700; letter-spacing: .3px; }
.brand-sub { font-size: 9px; opacity: .5; letter-spacing: 1.6px; margin-top: 2px; }

.nav { flex: 1; display: flex; flex-direction: column; gap: 8px; padding: 0 2px; overflow-y: auto; }
.nav::-webkit-scrollbar { width: 4px; }
.nav::-webkit-scrollbar-thumb { background: rgba(255,255,255,.16); border-radius: 4px; }
.nav-group { border-radius: 12px; }
.nav-head {
  width: 100%; display: flex; align-items: center; gap: 8px;
  padding: 11px 10px; border: 0; border-radius: 11px; background: transparent;
  cursor: pointer; text-align: left; font-family: inherit; color: rgba(255,255,255,.55);
  transition: background .2s ease, color .2s ease;
}
.nav-head:hover { background: rgba(255,255,255,.06); color: rgba(255,255,255,.85); }
.nav-eye { font-size: 9px; letter-spacing: 1.5px; opacity: .8; white-space: nowrap; }
.nav-glabel { font-size: 11.5px; font-weight: 600; color: rgba(255,255,255,.72); white-space: nowrap; }
.nav-chev {
  margin-left: auto; width: 7px; height: 7px; flex: none;
  border-right: 1.5px solid currentColor; border-bottom: 1.5px solid currentColor;
  transform: rotate(-45deg); transition: transform .28s ease; opacity: .75;
}
.nav-group.open .nav-head { color: #fff; background: rgba(255,255,255,.07); }
.nav-group.open .nav-head .nav-glabel { color: #fff; }
.nav-group.open .nav-chev { transform: rotate(45deg); }

.nav-body { max-height: 0; overflow: hidden; opacity: .2; padding: 0 6px; transition: max-height .34s cubic-bezier(.4,0,.2,1), opacity .24s ease; }
.nav-group.open .nav-body { max-height: 620px; opacity: 1; padding: 6px 6px 12px; }

.nav-item {
  position: relative; width: 100%; display: flex; align-items: center; gap: 9px;
  border: 0; background: transparent; color: rgba(255,255,255,.68);
  padding: 9px 10px; margin-bottom: 3px; border-radius: 10px; cursor: pointer;
  font-size: 12.5px; text-align: left; font-family: inherit;
  transition: background .18s ease, color .18s ease;
}
.nav-ico { width: 14px; height: 14px; flex: none; display: inline-flex; opacity: .75; }
.nav-ico svg { width: 14px; height: 14px; display: block; }
.nav-item:hover { background: rgba(255,255,255,.07); color: #fff; }
.nav-item:hover .nav-ico { opacity: 1; }
.nav-item.active {
  color: #fff; font-weight: 600;
  background: linear-gradient(90deg, rgba(106,139,255,.34), rgba(106,139,255,.10));
  box-shadow: inset 0 0 0 1px rgba(160, 185, 255, .32);
}
.nav-item.active::before {
  content: ''; position: absolute; left: 0; top: 7px; bottom: 7px; width: 3px;
  border-radius: 3px; background: #9db4ff; box-shadow: 0 0 10px rgba(157,180,255,.9);
}
.nav-item.active .nav-ico { opacity: 1; }

.sidebar-user {
  margin-top: 8px; display: flex; align-items: center; gap: 10px;
  padding: 9px 10px; border-radius: 14px; border: 1px solid rgba(255,255,255,.12);
  background: rgba(255,255,255,.07);
}
.user-avatar { width: 28px; height: 28px; line-height: 28px; text-align: center; border-radius: 9px; background: linear-gradient(135deg,#2b45d9,#6a8bff); font-size: 12px; font-weight: 700; }
.user-meta { min-width: 0; }
.user-name { font-size: 12px; font-weight: 600; }
.user-role { font-size: 10px; opacity: .55; letter-spacing: 1.2px; margin-top: 1px; }
.logout { margin-left: auto; border: 0; background: transparent; color: rgba(255,255,255,.55); font-size: 10.5px; cursor: pointer; padding: 2px; }
.logout:hover { color: #fff; }

/* 窄屏：侧边栏收成抽屉，不再顶到内容上面 */
.sidebar-toggle {
  display: none; position: fixed; left: 0; top: 50%; transform: translateY(-50%);
  width: 28px; height: 58px; border: 0; border-radius: 0 14px 14px 0; cursor: pointer;
  background: var(--dya-accent); color: #fff; font-size: 15px; font-weight: 700;
  place-items: center; z-index: 60; box-shadow: 4px 6px 20px rgba(43,69,217,.38);
  transition: background .2s ease, width .2s ease;
}
.sidebar-toggle:hover { background: var(--dya-accent-dim); width: 32px; }
.sidebar-backdrop {
  position: fixed; inset: 0; z-index: 40; background: rgba(14,22,48,.32);
  backdrop-filter: blur(2px); opacity: 0; pointer-events: none; transition: opacity .26s ease;
}
.sidebar-backdrop.on { opacity: 1; pointer-events: auto; }

@media (max-width: 1080px) {
  .shell { padding: 12px; }
  .sidebar {
    position: fixed; left: 12px; top: 12px; bottom: 12px; height: auto; width: 244px;
    transform: translateX(calc(-100% - 24px)); transition: transform .3s cubic-bezier(.4,0,.2,1);
  }
  .sidebar.drawer-open { transform: translateX(0); }
  .sidebar-toggle { display: grid; }
}

.main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.topbar { background: transparent; padding: 4px 6px 12px; border-bottom: 0; display: flex; align-items: flex-start; justify-content: space-between; }
.eyebrow {
  display: inline-block; font-size: 10px; letter-spacing: 3px; font-weight: 700; margin-bottom: 6px;
  background: linear-gradient(90deg, #1c2a86 0%, var(--dya-accent) 22%, #7b8cff 38%, var(--dya-accent) 55%, #1c2a86 78%, #1c2a86 100%);
  background-size: 260% 100%;
  -webkit-background-clip: text; background-clip: text; color: transparent;
  animation: eyebrow-flow 7s linear infinite;
}
@keyframes eyebrow-flow { from { background-position: 0% 0; } to { background-position: -260% 0; } }
.page-title {
  position: relative; margin: 0; font-size: 26px; letter-spacing: -.5px; padding-bottom: 9px;
  background: linear-gradient(100deg, var(--dya-ink) 0%, var(--dya-ink) 34%, #3f57e8 46%, #8f9dff 50%, #3f57e8 54%, var(--dya-ink) 66%, var(--dya-ink) 100%);
  background-size: 220% 100%;
  -webkit-background-clip: text; background-clip: text; color: transparent;
  animation: title-sheen 6.5s ease-in-out infinite, title-in .5s cubic-bezier(.22,.8,.3,1) both;
}
.page-title::after {
  content: ''; position: absolute; left: 0; bottom: 0; height: 2px; width: 40px;
  border-radius: 2px; background: var(--dya-accent);
  animation: rule-grow .62s .08s cubic-bezier(.22,.8,.3,1) both;
}
@keyframes title-sheen {
  0% { background-position: 128% 0; }
  55%, 100% { background-position: -28% 0; }
}
@keyframes title-in { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: translateY(0); } }
@keyframes rule-grow { from { width: 0; opacity: 0; } to { width: 40px; opacity: 1; } }
.hero-sub { margin: 6px 0 0; font-size: 12px; color: var(--dya-muted); }
.hero-right { display: flex; align-items: center; gap: 10px; }
.greet { font-size: 12px; color: var(--dya-muted); }
.env-chip { font-size: 10px; letter-spacing: 1.5px; color: var(--dya-accent); border: 1px solid #dfe3f7; background: var(--dya-accent-soft); border-radius: 20px; padding: 4px 12px; }
.today { font-size: 12px; color: var(--dya-muted); }
.content { padding: 0 4px 24px; }

/* 页面切换 */
.page-enter-active, .page-leave-active { transition: opacity .22s ease, transform .22s cubic-bezier(.22,.8,.3,1); }
.page-enter-from { opacity: 0; transform: translateY(10px); }
.page-leave-to { opacity: 0; transform: translateY(-6px); }

/* 通用卡片 */
.dya-card {
  background: rgba(255, 255, 255, .56);
  border: 1px solid rgba(255, 255, 255, .78);
  border-radius: 18px;
  padding: 18px 20px;
  box-shadow: 0 10px 34px rgba(24, 36, 74, .10), inset 0 1px 0 rgba(255, 255, 255, .85);
  backdrop-filter: blur(20px) saturate(160%);
  -webkit-backdrop-filter: blur(20px) saturate(160%);
}
.dya-card + .dya-card { margin-top: 16px; }
.card-head { display: flex; align-items: flex-end; justify-content: space-between; margin-bottom: 12px; }
.card-eyebrow { font-size: 9px; letter-spacing: 2.5px; color: var(--dya-muted); margin-bottom: 4px; }
.card-title { font-size: 15px; font-weight: 600; color: var(--dya-ink); }
.card-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.count-chip { font-size: 11px; font-weight: 600; color: var(--dya-accent); background: var(--dya-accent-soft); border-radius: 20px; padding: 3px 10px; }
.dya-actions { display: flex; align-items: center; gap: 8px; margin-top: 14px; flex-wrap: wrap; }
.dya-hint { font-size: 12px; color: var(--dya-muted); }
.dya-rules { margin: 0; padding-left: 18px; color: #5f6478; font-size: 13px; line-height: 1.9; }
.dya-note { background: #f4f5f9; border-left: 3px solid var(--dya-accent); border-radius: 8px; padding: 12px 14px; font-size: 12px; color: #666b7d; line-height: 1.7; }
.dya-note b { color: var(--dya-ink); }
.kv { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px 18px; padding: 4px 0 6px; }
.kv-k { font-size: 10px; letter-spacing: 1.2px; color: var(--dya-muted); margin-bottom: 3px; }
.kv-v { font-size: 13px; color: var(--dya-ink); word-break: break-all; }

/* 表格去掉全边框，改细分隔线 */
.dya-table .el-table__inner-wrapper::before { display: none; }
.dya-table { --el-table-border-color: transparent; --el-table-bg-color: transparent; --el-table-tr-bg-color: transparent; }
.dya-table th.el-table__cell { background: #faf8f4 !important; color: #8f93a3 !important; font-size: 11px; font-weight: 600; letter-spacing: .04em; }
.dya-table td.el-table__cell { border-bottom: 1px solid #f1ece5 !important; }
.dya-table .el-table__row { transition: background .2s ease; }
</style>
