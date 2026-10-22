// api.js — relay-server WebUI API 封装
// 约定：登录后的 API 调用遇 401 → 跳登录（location.reload）；初始鉴权探测（status）静默处理 401，
// 否则未登录访问会在 App.vue 挂载时触发「401 → reload → 再 401」的整页刷新死循环。后端返回 {ok:bool,...}

async function handle(r, { on401 = 'reload' } = {}) {
  if (r.status === 401) {
    if (on401 === 'reload') location.reload()
    throw new Error('unauthorized')
  }
  const data = await r.json().catch(() => ({}))
  return data
}

async function req(method, url, body, opt) {
  const o = { method, headers: {} }
  if (body !== undefined) {
    o.headers['Content-Type'] = 'application/json'
    o.body = JSON.stringify(body)
  }
  return handle(await fetch(url, o), opt)
}

export const api = {
  // 初始鉴权探测：401（未登录）静默抛错，由 App.vue 捕获后显示登录页，不触发 reload
  status: () => req('GET', '/admin/api/status', undefined, { on401: 'silent' }),
  channels: () => req('GET', '/admin/api/channels'),
  createChannel: (name) => req('POST', '/admin/api/channels', { name }),
  renameChannel: (id, name) => req('PATCH', `/admin/api/channels/${id}`, { name }),
  deleteChannel: (id) => req('DELETE', `/admin/api/channels/${id}`),
  rotateToken: (id, which) => req('POST', `/admin/api/channels/${id}/rotate/${which}`),
  recent: (channel = '') => req('GET', `/admin/api/recent${channel ? '?channel=' + encodeURIComponent(channel) : ''}`),
  clearRecent: (channelId = '') => req('POST', '/admin/api/recent/clear', { channel_id: channelId }),
  testSend: (channelId, title, body) => req('POST', '/admin/api/test', { channel_id: channelId, title, body }),
  config: () => req('GET', '/admin/api/config'),
  saveValues: (values) => req('POST', '/admin/api/config', { values }),
  login: (password) => req('POST', '/admin/login', { password }),
  logout: () => req('POST', '/admin/logout'),
  copyText: async (text) => {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      const ta = document.createElement('textarea')
      ta.value = text
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
      return true
    }
  },
}

export function fmtUptime(sec) {
  if (sec < 60) return `${sec}s`
  if (sec < 3600) return `${Math.floor(sec / 60)}m ${sec % 60}s`
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  return `${h}h ${m}m`
}

export function fmtTime(iso) {
  if (!iso) return '-'
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

export function randHex(bytes = 32) {
  const a = new Uint8Array(bytes)
  crypto.getRandomValues(a)
  return Array.from(a).map(b => b.toString(16).padStart(2, '0')).join('')
}
