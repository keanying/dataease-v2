'use strict'
/**
 * DataEase 截图服务（自研开源实现，兼容 dataease.playwright-server 接口）。
 *
 * POST /screenshot  JSON（字段均为字符串）：
 *   url           要截图的页面地址
 *   type          jpeg | pdf
 *   width/height  视口尺寸（像素）
 *   waitTime      页面加载完成后额外等待的秒数
 *   renderTime    图表渲染等待秒数
 *   timeout       整体超时秒数
 *   localStorage  需要预置到页面 localStorage 的键值（JSON 字符串）
 * 响应：multipart/form-data，name="metadata" 为 JSON 元数据（失败时含 error），name="image" 为文件内容。
 * GET /health 健康检查。
 *
 * 等待策略：页面 load 后等待网络请求全部结束且安静 QUIET_MS（默认 2000ms），再等待 waitTime + renderTime 秒后截图。
 */
const http = require('http')
const crypto = require('crypto')
const { chromium } = require('playwright')

const PORT = parseInt(process.env.PORT || '3000', 10)
const CONCURRENCY = Math.max(1, parseInt(process.env.CONCURRENCY || '2', 10))
const MAX_BODY = 1024 * 1024

let browserPromise = null
let running = 0
const queue = []

function log(...args) {
  console.log(new Date().toISOString(), ...args)
}

async function browser() {
  if (browserPromise) {
    const b = await browserPromise.catch(() => null)
    if (b && b.isConnected()) {
      return b
    }
  }
  browserPromise = chromium.launch({
    executablePath: process.env.CHROMIUM_PATH || undefined,
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--font-render-hinting=none']
  })
  return browserPromise
}

function acquire() {
  if (running < CONCURRENCY) {
    running++
    return Promise.resolve()
  }
  return new Promise(resolve => queue.push(resolve))
}

function release() {
  const next = queue.shift()
  if (next) {
    next()
  } else {
    running--
  }
}

function int(value, def, min, max) {
  const n = parseInt(value, 10)
  if (Number.isNaN(n)) {
    return def
  }
  return Math.min(max, Math.max(min, n))
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

const QUIET_MS = int(process.env.QUIET_MS, 2000, 200, 60000)

/**
 * 统计页面进行中的请求（忽略 websocket、长连接与媒体流）
 */
function trackRequests(page) {
  const state = { pending: new Set(), last: Date.now() }
  const ignore = r => ['websocket', 'eventsource', 'media'].includes(r.resourceType())
  page.on('request', r => {
    if (!ignore(r)) {
      state.pending.add(r)
      state.last = Date.now()
    }
  })
  const done = r => {
    if (state.pending.delete(r)) {
      state.last = Date.now()
    }
  }
  page.on('requestfinished', done)
  page.on('requestfailed', done)
  return state
}

/**
 * 等待没有进行中的请求且持续 quietMs，最晚到 deadline
 */
async function waitQuiet(state, quietMs, deadline) {
  while (Date.now() < deadline) {
    if (state.pending.size === 0 && Date.now() - state.last >= quietMs) {
      return
    }
    await sleep(100)
  }
}

async function capture(req) {
  if (!req.url || !/^https?:\/\//i.test(req.url)) {
    throw new Error('invalid url')
  }
  const width = int(req.width, 1920, 100, 10000)
  const height = int(req.height, 1080, 100, 20000)
  const waitTime = int(req.waitTime, 0, 0, 600)
  const renderTime = int(req.renderTime, 2, 0, 600)
  const timeout = int(req.timeout, 120, 10, 3600) * 1000
  const pdf = String(req.type).toLowerCase() === 'pdf'
  let storage = {}
  if (req.localStorage) {
    storage = typeof req.localStorage === 'string' ? JSON.parse(req.localStorage) : req.localStorage
  }
  const origin = new URL(req.url).origin

  const context = await (await browser()).newContext({
    viewport: { width, height },
    deviceScaleFactor: 1,
    ignoreHTTPSErrors: true
  })
  try {
    // 在页面脚本执行前写入登录信息（只写入目标站点）
    await context.addInitScript(({ origin, storage }) => {
      if (window.location.origin !== origin) {
        return
      }
      for (const [key, value] of Object.entries(storage)) {
        window.localStorage.setItem(key, value)
      }
    }, { origin, storage })
    const page = await context.newPage()
    page.setDefaultTimeout(timeout)
    const started = Date.now()
    const inflight = trackRequests(page)
    await page.goto(req.url, { waitUntil: 'load', timeout })
    // 图表数据在前端初始化后才开始请求，等待请求全部结束并保持安静一段时间
    await waitQuiet(inflight, QUIET_MS, started + timeout - Math.max(5000, (waitTime + renderTime) * 1000))
    await sleep((waitTime + renderTime) * 1000)
    if (pdf) {
      await page.emulateMedia({ media: 'screen' })
      return {
        data: await page.pdf({ width: width + 'px', height: height + 'px', printBackground: true, pageRanges: '1' }),
        contentType: 'application/pdf',
        width,
        height
      }
    }
    return {
      data: await page.screenshot({ type: 'jpeg', quality: 90, fullPage: true }),
      contentType: 'image/jpeg',
      width,
      height
    }
  } finally {
    await context.close().catch(() => {})
  }
}

function multipart(res, metadata, file) {
  const boundary = 'de' + crypto.randomBytes(12).toString('hex')
  const parts = [
    Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="metadata"\r\nContent-Type: application/json\r\n\r\n`),
    Buffer.from(JSON.stringify(metadata)),
    Buffer.from('\r\n')
  ]
  if (file) {
    const name = file.contentType === 'application/pdf' ? 'screenshot.pdf' : 'screenshot.jpeg'
    parts.push(Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="image"; filename="${name}"\r\nContent-Type: ${file.contentType}\r\n\r\n`))
    parts.push(file.data)
    parts.push(Buffer.from('\r\n'))
  }
  parts.push(Buffer.from(`--${boundary}--\r\n`))
  const body = Buffer.concat(parts)
  res.writeHead(200, { 'Content-Type': `multipart/form-data; boundary=${boundary}`, 'Content-Length': body.length })
  res.end(body)
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = []
    let size = 0
    req.on('data', chunk => {
      size += chunk.length
      if (size > MAX_BODY) {
        reject(new Error('request too large'))
        req.destroy()
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
    req.on('error', reject)
  })
}

const server = http.createServer(async (req, res) => {
  const path = req.url.split('?')[0]
  if (req.method === 'GET' && path === '/health') {
    res.writeHead(200, { 'Content-Type': 'text/plain' })
    res.end('ok')
    return
  }
  if (req.method !== 'POST' || path !== '/screenshot') {
    res.writeHead(404, { 'Content-Type': 'text/plain' })
    res.end('not found')
    return
  }
  const started = Date.now()
  let target = ''
  try {
    const payload = JSON.parse(await readBody(req))
    target = payload.url || ''
    await acquire()
    try {
      const file = await capture(payload)
      const elapsed = Date.now() - started
      log('ok', target, file.contentType, file.data.length, elapsed + 'ms')
      multipart(res, { type: file.contentType, width: file.width, height: file.height, size: file.data.length, elapsed }, file)
    } finally {
      release()
    }
  } catch (e) {
    log('fail', target, e && e.message)
    multipart(res, { error: (e && e.message) || String(e), elapsed: Date.now() - started }, null)
  }
})

server.requestTimeout = 0
server.listen(PORT, () => log(`de-screenshot listening on ${PORT}, concurrency ${CONCURRENCY}`))

async function shutdown() {
  server.close()
  const b = browserPromise && (await browserPromise.catch(() => null))
  if (b) {
    await b.close().catch(() => {})
  }
  process.exit(0)
}

process.on('SIGTERM', shutdown)
process.on('SIGINT', shutdown)
