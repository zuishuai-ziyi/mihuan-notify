<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { api } from '../api.js'

const items = ref([])
const expanded = ref(null)
let timer = null

async function refresh() {
  try {
    const d = await api.recent()
    items.value = d.items
  } catch { /* 401 统一处理 */ }
}

function toggle(id) {
  expanded.value = expanded.value === id ? null : id
}

function fmtTs(ts) {
  // ISO → 本地可读
  try { return new Date(ts).toLocaleString('zh-CN', { hour12: false }) } catch { return ts }
}

onMounted(() => { refresh(); timer = setInterval(refresh, 5000) })
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div class="panel">
    <h2 class="with-action">
      最近消息（{{ items.length }}）
      <button class="btn small danger" @click="api.clearRecent().then(refresh)">清空</button>
    </h2>
    <p v-if="!items.length" class="empty">暂无消息。第三方应用调用 /webhook 或在「测试发送」发一条即可看到记录。</p>
    <table v-else>
      <thead>
        <tr><th>时间</th><th>标题</th><th>来源</th><th>优先级</th><th>结果</th></tr>
      </thead>
      <tbody>
        <template v-for="it in items" :key="it.id">
          <tr @click="toggle(it.id)" :class="{ clickable: true, open: expanded === it.id }">
            <td class="mono">{{ fmtTs(it.ts) }}</td>
            <td class="title">{{ it.title }}</td>
            <td>{{ it.source || '—' }}</td>
            <td>
              <span v-if="it.priority === 'high'" class="tag high">high</span>
              <span v-else class="dim">normal</span>
            </td>
            <td>
              <span class="tag" :class="it.delivered ? 'ok' : 'fail'">{{ it.delivered ? `送达（${it.attempts}次）` : '失败' }}</span>
            </td>
          </tr>
          <tr v-if="expanded === it.id" class="detail-row">
            <td colspan="5"><pre>{{ it.body }}</pre></td>
          </tr>
        </template>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.with-action { display: flex; justify-content: space-between; align-items: center; }
.empty { color: var(--text2); padding: 20px 0; text-align: center; }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th, td { padding: 7px 8px; text-align: left; border-bottom: 1px solid var(--border); }
th { color: var(--text2); font-weight: 500; font-size: 12px; }
tr.clickable { cursor: pointer; }
tr.open { background: #f5f7fa; }
.title { max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mono { font-family: Consolas, monospace; font-size: 12px; }
.dim { color: var(--text2); }
.detail-row td { background: #fafafa; }
pre { white-space: pre-wrap; word-break: break-all; font-family: inherit; font-size: 12px; color: var(--text2); }
</style>
