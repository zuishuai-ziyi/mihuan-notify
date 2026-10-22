<script setup>
// Webhook 接入指引：可复制的请求模板 + 接入地址（输入框 + 下拉候选）。
// 数据来自 /admin/api/webhook-info（无鉴权）：候选地址 + token 状态；
// token 明文经登录态调 /admin/api/webhook-token 拿到后直接填进模板，
// 未登录/未设置时回落为 <WEBHOOK_TOKEN> 占位符。
import { ref, onMounted, computed } from 'vue'
import { api } from '../api.js'

const PLACEHOLDER = '<WEBHOOK_TOKEN>'
const info = ref(null)          // {urls[], token_hint, token_set, needs_setup}
const token = ref('')           // 登录态下拿到的真实 token（空=未获取到，用占位符）
const tokenState = ref('loading') // loading | ok | unset | denied
const addrText = ref('')        // 接入地址输入框（可手改）
const addrIdx = ref(0)          // 下拉当前选中项
const copied = ref('')          // 最近复制成功的标识（模板 id）

const candidates = computed(() => info.value?.urls?.length ? info.value.urls : ['http://127.0.0.1:8787'])
const addr = computed(() => addrText.value.trim().replace(/\/+$/, '') || 'http://127.0.0.1:8787')

const templates = computed(() => {
  const a = addr.value
  const t = tokenState.value === 'ok' && token.value ? token.value : PLACEHOLDER
  return [
    {
      id: 'curl-json',
      label: 'curl · JSON（推荐，完整字段）',
      code: `curl -X POST ${a}/webhook \\
  -H "Authorization: Bearer ${t}" \\
  -H "Content-Type: application/json" \\
  -d '{"title":"服务器告警","body":"CPU 使用率超过 90%","source":"my-monitor","priority":"high"}'`,
    },
    {
      id: 'curl-query',
      label: 'curl · 查询参数（脚本速用）',
      code: `curl -X POST "${a}/webhook?title=备份完成&body=增量备份耗时 12 分钟" \\
  -H "Authorization: Bearer ${t}"`,
    },
    {
      id: 'js',
      label: 'JavaScript · fetch（网页/Node）',
      code: `await fetch('${a}/webhook', {
  method: 'POST',
  headers: {
    'Authorization': 'Bearer ${t}',
    'Content-Type': 'application/json',
  },
  body: JSON.stringify({ title: '构建完成', body: 'main 分支 #128 通过', source: 'ci' }),
})`,
    },
    {
      id: 'python',
      label: 'Python · requests',
      code: `import requests

requests.post(
    "${a}/webhook",
    headers={"Authorization": "Bearer ${t}"},
    json={"title": "任务完成", "body": "数据处理完毕", "priority": "normal"},
)`,
    },
  ]
})

onMounted(async () => {
  try {
    info.value = await api.webhookInfo()
  } catch { /* info 保持 null，模板展示占位地址 */ }
  addrText.value = candidates.value[0]
  // token 明文：需登录。401（未登录）时保持占位符模式
  try {
    const d = await api.webhookToken()
    if (d.token) {
      token.value = d.token
      tokenState.value = 'ok'
    } else {
      tokenState.value = 'unset'
    }
  } catch {
    tokenState.value = 'denied'
  }
})

function pickAddr(i) {
  addrIdx.value = i
  addrText.value = candidates.value[i] || ''
}

async function copy(id, text) {
  if (await api.copyText(text)) {
    copied.value = id
    setTimeout(() => { if (copied.value === id) copied.value = '' }, 1500)
  }
}
</script>

<template>
  <div class="panel">
    <h2>Webhook 接入指引</h2>
    <p class="desc">
      任何能发 HTTP 请求的程序（脚本、Grafana、CI、Home Assistant…）都可以通过本机
      <code>/webhook</code> 接口把通知推到手机/手环。
    </p>

    <!-- 接入地址 -->
    <div class="field">
      <div class="lbl">接入地址（下拉切换候选，或直接输入自定义地址；同一局域网内应至少一个可达）</div>
      <div class="addr-row">
        <select class="addr-select" :value="addrIdx" @change="pickAddr($event.target.selectedIndex)">
          <option v-for="(u, i) in candidates" :key="u" :value="i">{{ u }}</option>
        </select>
        <input class="addr-input" type="text" v-model="addrText" spellcheck="false" placeholder="http://192.168.1.10:8787">
      </div>
    </div>

    <!-- token -->
    <div class="field">
      <div class="lbl">鉴权 Token</div>
      <div class="row">
        <code class="token-box">
          Authorization: Bearer <b>{{ tokenState === 'ok' ? token : PLACEHOLDER }}</b>
        </code>
      </div>
      <div class="help">
        <template v-if="tokenState === 'ok'">✓ 已自动填入真实 Token（模板复制即可用）</template>
        <template v-else-if="tokenState === 'unset'">Token 未设置——到「设置 → Webhook」生成后再来复制模板。</template>
        <template v-else-if="tokenState === 'denied'">登录后模板自动填入真实 Token；未登录显示为占位符。</template>
        <template v-else>正在读取 Token…</template>
        明文也可在「设置 → Webhook（第三方应用接入）→ Webhook Token」查看，或直接看 config.toml 的 [webhook] token。
      </div>
    </div>

    <!-- 模板 -->
    <div v-for="t in templates" :key="t.id" class="tpl">
      <div class="tpl-head">
        <span>{{ t.label }}</span>
        <button class="btn small" @click="copy(t.id, t.code)">
          {{ copied === t.id ? '✓ 已复制' : '复制' }}
        </button>
      </div>
      <pre class="code">{{ t.code }}</pre>
    </div>

    <!-- 字段说明 -->
    <div class="field">
      <div class="lbl">JSON 字段</div>
      <table class="fields">
        <tr><td><code>title</code></td><td>必填，通知标题（≤64 字）</td></tr>
        <tr><td><code>body</code></td><td>必填，通知正文（≤4096 字）</td></tr>
        <tr><td><code>source</code></td><td>可选，来源标识（≤64 字），App 上显示</td></tr>
        <tr><td><code>priority</code></td><td>可选，<code>normal</code>（静默，默认）/ <code>high</code>（响铃+横幅）</td></tr>
      </table>
      <div class="help">也支持 text/plain 纯文本（title 固定为「Webhook」）。响应：200 已送达（直连=已到手机，中继=已入中继队列）；401 token 错误；502 下游不可达（可重试）。</div>
    </div>
  </div>
</template>

<style scoped>
.desc { color: var(--text2); font-size: 13px; margin-bottom: 14px; }
.field { margin-bottom: 16px; }
.lbl { color: var(--text2); font-size: 12px; margin-bottom: 6px; }
.addr-row { display: flex; gap: 8px; }
.addr-select {
  flex: 0 0 auto; max-width: 45%;
  border: 1px solid var(--border); background: #fff; cursor: pointer;
  padding: 6px 8px; border-radius: 4px; font-size: 12px; font-family: monospace;
  color: var(--text);
}
.addr-input {
  flex: 1; border: 1px solid var(--border); background: #fff;
  padding: 6px 10px; border-radius: 4px; font-size: 12px; font-family: monospace;
}
.addr-input:focus { outline: none; border-color: var(--primary); }
.row { display: flex; align-items: center; gap: 8px; }
.token-box {
  flex: 1; background: var(--bg, #f5f7fa); border: 1px solid var(--border);
  padding: 6px 10px; border-radius: 4px; font-size: 12px; overflow-x: auto;
}
.help { color: var(--text2); font-size: 11px; margin-top: 4px; }
.tpl { margin-bottom: 12px; }
.tpl-head {
  display: flex; justify-content: space-between; align-items: center;
  font-size: 12px; color: var(--text2); margin-bottom: 4px;
}
.code {
  margin: 0; padding: 10px 12px; background: #282c34; color: #abb2bf;
  border-radius: 6px; font-size: 12px; line-height: 1.6;
  overflow-x: auto; white-space: pre;
}
.fields { border-collapse: collapse; width: 100%; font-size: 12px; }
.fields td { border-bottom: 1px dashed var(--border); padding: 5px 8px 5px 0; }
.fields td:first-child { width: 90px; }
</style>
