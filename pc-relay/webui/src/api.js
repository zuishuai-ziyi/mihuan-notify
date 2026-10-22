// API 封装：401 时跳回登录（整页刷新，重走 checkAuth）
async function handle(r) {
  if (r.status === 401) {
    location.reload()
    throw new Error('unauthorized')
  }
  const data = await r.json().catch(() => ({}))
  if (!r.ok) throw new Error(data.error || `HTTP ${r.status}`)
  return data
}

export const api = {
  async status() {
    return handle(await fetch('/admin/api/status'))
  },
  async config() {
    return handle(await fetch('/admin/api/config'))
  },
  // 表单模式保存：values = {section: {key: value}}
  async saveValues(values) {
    return handle(await fetch('/admin/api/config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ values }),
    }))
  },
  async restart() {
    return handle(await fetch('/admin/api/restart', { method: 'POST' }))
  },
  async autostartStatus() {
    return handle(await fetch('/admin/api/autostart'))
  },
  async autostartSet(enable) {
    return handle(await fetch('/admin/api/autostart', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enable }),
    }))
  },
  async testSend(title, body, priority) {
    return handle(await fetch('/admin/api/test', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title, body, priority }),
    }))
  },
  async recent() {
    return handle(await fetch('/admin/api/recent'))
  },
  async clearRecent() {
    return handle(await fetch('/admin/api/recent/clear', { method: 'POST' }))
  },
  // Webhook 接入信息（无鉴权；不返回 token 明文）
  async webhookInfo() {
    return handle(await fetch('/admin/api/webhook-info'))
  },
  // Webhook Token 明文（需登录；接入指引模板填真实值用）
  async webhookToken() {
    return handle(await fetch('/admin/api/webhook-token'))
  },
  // 复制到剪贴板（非安全上下文 fallback：textarea+execCommand）
  async copyText(text) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      let ok = false
      try { ok = document.execCommand('copy') } catch { ok = false }
      document.body.removeChild(ta)
      return ok
    }
  },
  async logout() {
    await fetch('/admin/logout', { method: 'POST' }).catch(() => {})
    location.reload()
  },
}

export function fmtUptime(sec) {
  const d = Math.floor(sec / 86400)
  const h = Math.floor((sec % 86400) / 3600)
  const m = Math.floor((sec % 3600) / 60)
  return d > 0 ? `${d}天${h}时${m}分` : h > 0 ? `${h}时${m}分` : `${m}分${Math.floor(sec % 60)}秒`
}

export function randHex(bytes = 32) {
  return [...crypto.getRandomValues(new Uint8Array(bytes))].map(b => b.toString(16).padStart(2, '0')).join('')
}
