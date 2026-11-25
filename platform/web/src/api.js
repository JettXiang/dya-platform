const TOKEN_KEY = 'collect_token'
const USER_KEY = 'collect_user'
const REFRESH_KEY = 'collect_refresh'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function getRefreshToken() {
  return localStorage.getItem(REFRESH_KEY) || ''
}

export function setSession(token, user, refreshToken) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USER_KEY, JSON.stringify(user || {}))
  if (refreshToken) {
    localStorage.setItem(REFRESH_KEY, refreshToken)
  }
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  localStorage.removeItem(REFRESH_KEY)
}

export function getUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || '{}')
  } catch (e) {
    return {}
  }
}

function qs(params) {
  const parts = []
  Object.keys(params || {}).forEach((k) => {
    const v = params[k]
    if (v === undefined || v === null || v === '') return
    parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(v))
  })
  return parts.join('&')
}

let sessionExpired = false

// token 失效：清掉本地会话并回到登录页（App 依据 token 是否存在渲染登录卡）
function expireSession() {
  clearSession()
  if (sessionExpired) return
  sessionExpired = true
  window.location.reload()
}

/* 静默续期：access token 8 小时到期后，用 refresh token 换新的，
 * 7 天内打开页面都不用重新登录。并发请求共用同一次续期。 */
let refreshPromise = null

async function refreshAccessToken() {
  const refreshToken = getRefreshToken()
  if (!refreshToken) return false
  if (!refreshPromise) {
    refreshPromise = (async () => {
      try {
        const res = await fetch('/api/v1/auth/refresh', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken })
        })
        if (!res.ok) return false
        const body = await res.json()
        if (!body || !body.success || !body.data || !body.data.accessToken) return false
        setSession(body.data.accessToken, getUser(), body.data.refreshToken || refreshToken)
        return true
      } catch (e) {
        return false
      } finally {
        refreshPromise = null
      }
    })()
  }
  return refreshPromise
}

function isAuthRefreshPath(path) {
  return typeof path === 'string' && path.indexOf('/api/v1/auth/refresh') === 0
}

async function request(path, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) }
  const token = getToken()
  if (token) headers.Authorization = 'Bearer ' + token
  const res = await fetch(path, { ...options, headers })
  const text = await res.text()
  let body = {}
  try {
    body = text ? JSON.parse(text) : {}
  } catch (e) {
    body = { message: text }
  }
  if (res.status === 401) {
    // 先尝试续期再重放一次；续不上才退回登录页
    if (!options._retried && !isAuthRefreshPath(path) && await refreshAccessToken()) {
      return request(path, { ...options, _retried: true })
    }
    expireSession()
    throw new Error(body.message || '登录已失效，请重新登录')
  }
  // 兼容旧后端：未认证被当作 403 返回时同样按登录失效处理
  if (res.status === 403 && body.error === 'Forbidden' && !body.message) {
    if (!options._retried && !isAuthRefreshPath(path) && await refreshAccessToken()) {
      return request(path, { ...options, _retried: true })
    }
    expireSession()
    throw new Error('登录已失效，请重新登录')
  }
  if (!res.ok) {
    const msg = body.error ? body.error + ': ' + (body.message || '') : body.message || 'HTTP ' + res.status
    throw new Error(msg)
  }
  return body
}

async function downloadFile(path, fallbackName) {
  const token = getToken()
  let res = await fetch(path, { headers: token ? { Authorization: 'Bearer ' + token } : {} })
  if (res.status === 401) {
    if (await refreshAccessToken()) {
      const fresh = getToken()
      res = await fetch(path, { headers: fresh ? { Authorization: 'Bearer ' + fresh } : {} })
    }
  }
  if (res.status === 401) {
    expireSession()
    throw new Error('登录已失效，请重新登录')
  }
  if (!res.ok) {
    let msg = '下载失败 HTTP ' + res.status
    try {
      const body = await res.json()
      msg = body.message || msg
    } catch (e) {
      /* 非 JSON 错误体 */
    }
    throw new Error(msg)
  }
  const blob = await res.blob()
  let name = fallbackName
  const disposition = res.headers.get('Content-Disposition') || ''
  const matched = /filename="?([^";]+)"?/i.exec(disposition)
  if (matched) name = matched[1]
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = name
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(a.href), 2000)
}

export const api = {
  login(code, password) {
    return request('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ code, password })
    })
  },
  logout() {
    return request('/api/v1/auth/logout', { method: 'POST' })
  },
  timeline(params) {
    return request('/api/v1/monitor/timeline?' + qs(params))
  },
  chain(batchNo) {
    return request('/api/v1/monitor/batches/' + encodeURIComponent(batchNo) + '/chain')
  },
  anomalies(params) {
    return request('/api/v1/monitor/anomalies?' + qs(params))
  },
  users() {
    return request('/api/v1/users')
  },
  createUser(body) {
    return request('/api/v1/users', { method: 'POST', body: JSON.stringify(body) })
  },
  updateUser(id, body) {
    return request('/api/v1/users/' + id, { method: 'PUT', body: JSON.stringify(body) })
  },
  disableUser(id) {
    return request('/api/v1/users/' + id, { method: 'DELETE' })
  },
  recordKeyword(keyword) {
    return request('/api/v1/search/keywords', { method: 'POST', body: JSON.stringify({ keyword }) })
  },
  randomKeywords(n) {
    return request('/api/v1/search/keywords/random?' + qs({ n }))
  },
  searchKeywords() {
    return request('/api/v1/search/keywords')
  },
  addSearchKeyword(keyword) {
    return request('/api/v1/search/keywords/manual', { method: 'POST', body: JSON.stringify({ keyword }) })
  },
  updateSearchKeyword(id, body) {
    return request('/api/v1/search/keywords/' + id, { method: 'PUT', body: JSON.stringify(body) })
  },
  deleteSearchKeyword(id) {
    return request('/api/v1/search/keywords/' + id, { method: 'DELETE' })
  },
  keywordAnalysis(week) {
    return request('/api/v1/search/analysis?' + qs({ week }))
  },
  exportKeywordAnalysis(week) {
    return downloadFile('/api/v1/search/analysis/export?' + qs({ week }), '关键词周分析-' + (week || '') + '.csv')
  },
  suggestKeyword(keyword) {
    return request('/api/v1/search/keywords/suggest', { method: 'POST', body: JSON.stringify({ keyword }) })
  },
  keywordSuggestions(status) {
    return request('/api/v1/search/keywords/suggestions?' + qs({ status }))
  },
  reviewKeywordSuggestion(id, approve, reason) {
    return request('/api/v1/search/keywords/suggestions/' + id + '/review', {
      method: 'POST',
      body: JSON.stringify({ approve, reason })
    })
  },
  accounts() {
    return request('/api/v1/accounts')
  },
  createAccount(body) {
    return request('/api/v1/accounts', { method: 'POST', body: JSON.stringify(body) })
  },
  updateAccount(id, body) {
    return request('/api/v1/accounts/' + id, { method: 'PUT', body: JSON.stringify(body) })
  },
  daily(date) {
    return request('/api/v1/reports/daily?' + qs({ date }))
  },
  batchList(date) {
    return request('/api/v1/batches?' + qs({ date }))
  },
  batchDetail(batchNo) {
    return request('/api/v1/batches/' + encodeURIComponent(batchNo))
  },
  voidBatch(batchNo, body) {
    return request('/api/v1/batches/' + encodeURIComponent(batchNo) + '/void', { method: 'POST', body: JSON.stringify(body) })
  },
  reconcileBatch(batchNo, body) {
    return request('/api/v1/batches/' + encodeURIComponent(batchNo) + '/reconcile', { method: 'POST', body: JSON.stringify(body) })
  },
  uploadRequest(body) {
    return request('/api/v1/batches/upload-request', { method: 'POST', body: JSON.stringify(body) })
  },
  notifications() {
    return request('/api/v1/notifications')
  },
  readNotification(id) {
    return request('/api/v1/notifications/' + id + '/read', { method: 'POST' })
  },
  sendNotification(body) {
    return request('/api/v1/notifications', { method: 'POST', body: JSON.stringify(body) })
  },
  schedules(date) {
    return request('/api/v1/schedules?' + qs({ date }))
  },
  saveSchedule(body) {
    return request('/api/v1/schedules', { method: 'PUT', body: JSON.stringify(body) })
  },
  extensionConfig() {
    return request('/api/v1/config/extension')
  },
  videos(params) {
    return request('/api/v1/videos?' + qs(params))
  },
  videoStats(params) {
    return request('/api/v1/videos/stats?' + qs(params))
  },
  uploadVideos(body) {
    return request('/api/v1/videos/upload', { method: 'POST', body: JSON.stringify(body) })
  },
  videoUploadLogs() {
    return request('/api/v1/videos/uploads')
  },
  ingestVideos(body) {
    return request('/api/v1/videos/ingest', { method: 'POST', body: JSON.stringify(body) })
  },
  aiDramaAuthors(params) {
    return request('/api/v1/authors/ai-drama?' + qs(params))
  },
  exportAiDramaAuthors(params) {
    return downloadFile('/api/v1/authors/ai-drama/export?' + qs(params), 'AI短剧达人.csv')
  },
  collectorStatus() {
    return request('/api/v1/collect/status')
  },
  cdpTerminals() {
    return request('/api/v1/cdp/terminals')
  },
  cdpCreateTerminal(body) {
    return request('/api/v1/cdp/terminals', { method: 'POST', body: JSON.stringify(body) })
  },
  cdpUpdateTerminal(id, body) {
    return request('/api/v1/cdp/terminals/' + id, { method: 'PUT', body: JSON.stringify(body) })
  },
  cdpDeleteTerminal(id) {
    return request('/api/v1/cdp/terminals/' + id, { method: 'DELETE' })
  },
  cdpStatus(terminalId) {
    return request('/api/v1/cdp/status' + (terminalId ? '?terminalId=' + encodeURIComponent(terminalId) : ''))
  },
  cdpLauncher() {
    return request('/api/v1/cdp/launcher')
  },
  cdpDouyinStatus(terminalId) {
    return request('/api/v1/cdp/douyin/status?' + qs({ terminalId }))
  },
  cdpDouyinLogin(terminalId) {
    return request('/api/v1/cdp/douyin/login', {
      method: 'POST',
      body: JSON.stringify({ terminalId })
    })
  },
  dailyTasks(date) {
    return request('/api/v1/daily-tasks?' + qs({ date }))
  },
  saveDailyTask(body) {
    return request('/api/v1/daily-tasks', { method: 'POST', body: JSON.stringify(body) })
  },
  deleteDailyTask(id) {
    return request('/api/v1/daily-tasks/' + id, { method: 'DELETE' })
  },
  exportCollectionCsv(date) {
    return downloadFile('/api/v1/reports/collection-export?' + qs({ date }), '采集日报-' + date + '.csv')
  },
  collectorFrame(deviceId) {
    const q = deviceId ? '?deviceId=' + encodeURIComponent(deviceId) : ''
    return request('/api/v1/collect/frame' + q)
  },
  collectorCommand(body) {
    return request('/api/v1/collect/command', { method: 'POST', body: JSON.stringify(body) })
  },
  collectorCommandResults(deviceId) {
    return request('/api/v1/collect/command/results?deviceId=' + encodeURIComponent(deviceId || ''))
  },
  authorUploadPending(params) {
    return request('/api/v1/author-uploads/pending?' + qs(params))
  },
  authorUploadStats(params) {
    return request('/api/v1/author-uploads/stats?' + qs(params))
  },
  authorUploadPush(secUids) {
    return request('/api/v1/author-uploads/push', { method: 'POST', body: JSON.stringify({ secUids }) })
  },
  authorUploadHistory(limit) {
    return request('/api/v1/author-uploads/history?' + qs({ limit }))
  }
}
