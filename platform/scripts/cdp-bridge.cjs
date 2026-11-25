/**
 * 本机 CDP 桥：把 Chrome 的 9222 调试端点包一层带 CORS 的 HTTP 接口。
 * 浏览器页面（http://127.0.0.1:19203）无法直接 fetch 9222（Chrome 不发 CORS 头），
 * 所以由这个小桥返回可直接连接的 webSocketDebuggerUrl。
 */
const http = require('http')

const PORT = Number(process.env.CDP_BRIDGE_PORT || 9333)
const CDP = 'http://127.0.0.1:' + (process.env.CDP_PORT || 9222)

function json(res, code, payload) {
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET,OPTIONS',
    'Access-Control-Allow-Headers': '*',
    'Cache-Control': 'no-store'
  })
  res.end(JSON.stringify(payload))
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'OPTIONS') {
    json(res, 204, {})
    return
  }
  const url = (req.url || '').split('?')[0]
  if (url === '/ping') {
    json(res, 200, { ok: true, cdp: CDP })
    return
  }
  if (url === '/targets') {
    try {
      const r = await fetch(CDP + '/json/list')
      const list = await r.json()
      const pages = list
        .filter((t) => t.type === 'page')
        .map((t) => ({ id: t.id, url: t.url, title: t.title, ws: t.webSocketDebuggerUrl }))
      json(res, 200, { ok: true, pages })
    } catch (e) {
      json(res, 200, { ok: false, error: '连不上 Chrome 9222：' + e.message })
    }
    return
  }
  json(res, 404, { ok: false, error: 'not found' })
})

server.listen(PORT, '127.0.0.1', () => {
  console.log('[cdp-bridge] listening on http://127.0.0.1:' + PORT + ' -> ' + CDP)
})
