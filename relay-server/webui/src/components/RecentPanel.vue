<template>
  <div>
    <div class="panel">
      <div class="toolbar">
        <select v-model="chFilter" @change="load">
          <option value="">全部通道</option>
          <option v-for="c in channels" :key="c.id" :value="c.id">{{ c.name }}</option>
        </select>
        <span class="grow"></span>
        <button class="btn small danger" @click="clear">清空</button>
      </div>

      <table class="tbl">
        <thead>
          <tr><th>时间</th><th>通道</th><th>标题</th><th>来源</th><th>状态</th></tr>
        </thead>
        <tbody>
          <tr v-if="messages.length === 0">
            <td colspan="5" class="empty">暂无消息</td>
          </tr>
          <tr v-for="m in messages" :key="m.channel_id + m.id" @click="expand = expand === m.id ? '' : m.id" class="row">
            <td class="mono nowrap">{{ fmtTime(m.created) }}</td>
            <td>{{ m.channel }}</td>
            <td class="title-cell">{{ m.title }}<span class="high-tag tag high" v-if="m.priority === 'high'">高</span></td>
            <td class="dim">{{ m.source || '-' }}</td>
            <td><span class="tag" :class="m.state === 'delivered' ? 'ok' : ''">{{ m.state === 'delivered' ? '已送达' : '待推送' }}</span></td>
          </tr>
        </tbody>
      </table>

      <div v-if="expanded" class="panel body-view">
        <b>{{ expanded.title }}</b>
        <div class="dim mono small">{{ expanded.created }} · {{ expanded.channel }}</div>
        <pre>{{ expanded.body }}</pre>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { api, fmtTime } from '../api.js'

const messages = ref([])
const channels = ref([])
const chFilter = ref('')
const expand = ref('')
let timer = null

const expanded = computed(() => messages.value.find(m => m.id === expand.value))

onMounted(() => {
  load()
  loadChannels()
  timer = setInterval(load, 5000)
})
onUnmounted(() => clearInterval(timer))

async function load() {
  try {
    const r = await api.recent(chFilter.value)
    messages.value = r.messages || []
  } catch {}
}

async function loadChannels() {
  try {
    const r = await api.channels()
    channels.value = r.channels || []
  } catch {}
}

async function clear() {
  if (!confirm('清空所选范围的消息记录？')) return
  await api.clearRecent(chFilter.value)
  await load()
}
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; }
.toolbar select { width: auto; }
.grow { flex: 1; }
.tbl { width: 100%; border-collapse: collapse; font-size: 13px; }
.tbl th { text-align: left; color: var(--text2); font-weight: normal; padding: 6px 8px; border-bottom: 1px solid var(--border); }
.tbl td { padding: 6px 8px; border-bottom: 1px solid var(--border); }
.row { cursor: pointer; }
.row:hover { background: #f5f7fa; }
.empty { text-align: center; color: var(--text2); padding: 20px 0; }
.mono { font-family: Consolas, monospace; font-size: 12px; }
.nowrap { white-space: nowrap; }
.title-cell { max-width: 320px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.high-tag { margin-left: 6px; }
.dim { color: var(--text2); font-size: 12px; }
.body-view { margin-top: 12px; }
.body-view pre { white-space: pre-wrap; word-break: break-all; font-size: 13px; margin-top: 8px; }
.small { font-size: 12px; }
</style>
