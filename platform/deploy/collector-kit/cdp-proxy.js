// CDP 端口转发：0.0.0.0:9223 -> 127.0.0.1:9222
// Chrome 149+ 会校验 Host 头，纯 TCP 透传会被拒，所以这里重写 Host 再转发。
// 同时处理普通 HTTP（/json/version 等）和 WebSocket 升级。
//
// 另外每隔一分钟把自己的当前 IP + 端口上报给平台（终端 IP 是 DHCP 分配会变，
// 由本机主动上报，平台就不用每次人工改地址）。
// 终端编码写在本目录的 terminal-code.txt（一行，例如 PC-XJH）；
// 平台地址写在本目录的 platform.txt（一行，例如 http://10.1.2.60:19203）。
const http = require('http')
const net = require('net')
const fs = require('fs')
const os = require('os')
const path = require('path')
const dgram = require('dgram')

const LISTEN_PORT = 9223
const TARGET_HOST = '127.0.0.1'
const TARGET_PORT = 9222
const TARGET_ORIGIN = `${TARGET_HOST}:${TARGET_PORT}`
const ANNOUNCE_MS = 60 * 1000

function readConf(name) {
  try {
    return fs.readFileSync(path.join(__dirname, name), 'utf8').replace(/^\uFEFF/, '').trim()
  } catch (e) {
    return ''
  }
}

// 终端编码：优先环境变量，其次 terminal-code.txt；都没有就用电脑名。
// 用电脑名意味着同一个安装包可以直接发给任何人，不用一人一包。
const TERMINAL_CODE = process.env.DYA_TERMINAL || readConf('terminal-code.txt') || os.hostname().toUpperCase()
const PLATFORM = process.env.DYA_PLATFORM || readConf('platform.txt') || 'http://10.1.2.60:19203'

const server = http.createServer((req, res) => {
  const headers = { ...req.headers, host: TARGET_ORIGIN }
  const upstream = http.request(
    { host: TARGET_HOST, port: TARGET_PORT, path: req.url, method: req.method, headers },
    (r) => {
      res.writeHead(r.statusCode, r.headers)
      r.pipe(res)
    }
  )
  upstream.on('error', (err) => {
    if (!res.headersSent) res.writeHead(502, { 'Content-Type': 'text/plain' })
    res.end('cdp proxy error: ' + err.message)
  })
  req.pipe(upstream)
})

server.on('upgrade', (req, socket, head) => {
  const upstream = net.connect(TARGET_PORT, TARGET_HOST, () => {
    const headers = { ...req.headers, host: TARGET_ORIGIN }
    let raw = `${req.method} ${req.url} HTTP/1.1\r\n`
    for (const [k, v] of Object.entries(headers)) raw += `${k}: ${v}\r\n`
    raw += '\r\n'
    upstream.write(raw)
    if (head && head.length) upstream.write(head)
    socket.pipe(upstream)
    upstream.pipe(socket)
  })
  const close = () => {
    socket.destroy()
    upstream.destroy()
  }
  socket.on('error', close)
  upstream.on('error', close)
})

server.on('error', (err) => {
  console.error('[cdp-proxy] error:', err.message)
  process.exit(1)
})

server.listen(LISTEN_PORT, '0.0.0.0', () => {
  console.log(`[cdp-proxy] 0.0.0.0:${LISTEN_PORT} -> ${TARGET_ORIGIN}`)
  console.log(`[cdp-proxy] 终端编码：${TERMINAL_CODE}（来自 ${process.env.DYA_TERMINAL ? '环境变量' : readConf('terminal-code.txt') ? 'terminal-code.txt' : '电脑名'}）`)
  console.log(`[cdp-proxy] 地址上报已开启：${TERMINAL_CODE} -> ${PLATFORM}`)
  announce()
  setInterval(announce, ANNOUNCE_MS)
})

/** 选一块能通到平台网卡的地址，多网卡机器也不会报错 IP。 */
function pickLocalIp(host) {
  return new Promise((resolve) => {
    const sock = dgram.createSocket('udp4')
    const done = (ip) => {
      try { sock.close() } catch (e) { }
      resolve(ip)
    }
    sock.on('error', () => done(''))
    sock.connect(80, host, () => {
      let ip = ''
      try { ip = sock.address().address } catch (e) { }
      done(ip)
    })
  })
}

async function announce() {
  if (!TERMINAL_CODE) return
  let url
  try {
    url = new URL(PLATFORM)
  } catch (e) {
    console.log('[cdp-proxy] platform.txt 不是合法地址：' + PLATFORM)
    return
  }
  const ip = await pickLocalIp(url.hostname)
  if (!ip) {
    console.log('[cdp-proxy] 取不到本机 IP，跳过本次上报')
    return
  }
  const payload = JSON.stringify({
    code: TERMINAL_CODE,
    name: os.hostname(),
    host: ip,
    port: LISTEN_PORT
  })
  const req = http.request({
    host: url.hostname,
    port: url.port || 80,
    path: '/api/v1/cdp/announce',
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) },
    timeout: 5000
  }, (res) => {
    let body = ''
    res.on('data', (d) => { body += d })
    res.on('end', () => {
      const ok = res.statusCode >= 200 && res.statusCode < 300
      console.log(`[cdp-proxy] 上报 ${TERMINAL_CODE} -> ${ip}:${LISTEN_PORT} : ${ok ? '成功' : '失败 ' + res.statusCode + ' ' + body}`)
    })
  })
  req.on('timeout', () => req.destroy(new Error('timeout')))
  req.on('error', (e) => console.log('[cdp-proxy] 上报失败：' + e.message))
  req.write(payload)
  req.end()
}
