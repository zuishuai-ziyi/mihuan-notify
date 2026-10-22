<template>
  <div class="dash">
    <header>
      <div class="brand">
        <b>mihuan-notify 中继服务</b>
        <span class="ver">{{ st.version }}</span>
        <span class="tag ok" v-if="docker">Docker</span>
      </div>
      <div class="meta">
        运行 {{ fmtUptime(st.uptime_sec) }} · 通道 {{ st.channels_online }}/{{ st.channels_total }} 在线 · 待推送 {{ st.stats.pending }}
        <button class="btn small" @click="logout">退出</button>
      </div>
    </header>

    <nav>
      <button v-for="t in tabs" :key="t.id" class="btn" :class="{ primary: tab === t.id }" @click="tab = t.id">{{ t.label }}</button>
    </nav>

    <StatusPanel v-show="tab === 'status'" :st="st" />
    <ChannelsPanel v-show="tab === 'channels'" />
    <RecentPanel v-show="tab === 'recent'" />
    <ConfigPanel v-show="tab === 'config'" />
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { api, fmtUptime } from '../api.js'
import StatusPanel from './StatusPanel.vue'
import ChannelsPanel from './ChannelsPanel.vue'
import RecentPanel from './RecentPanel.vue'
import ConfigPanel from './ConfigPanel.vue'

const emit = defineEmits(['logout'])
const st = ref({ version: '-', uptime_sec: 0, stats: {}, channels_online: 0, channels_total: 0, docker: false })
const tab = ref('status')
const tabs = [
  { id: 'status', label: '状态' },
  { id: 'channels', label: '通道' },
  { id: 'recent', label: '消息' },
  { id: 'config', label: '设置' },
]

let timer = null
onMounted(() => {
  poll()
  timer = setInterval(poll, 5000)
})
onUnmounted(() => clearInterval(timer))

async function poll() {
  try {
    st.value = await api.status()
  } catch {}
}

async function logout() {
  await api.logout()
  emit('logout')
}
</script>

<style scoped>
header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 0;
  flex-wrap: wrap;
  gap: 8px;
}
.brand { display: flex; align-items: center; gap: 10px; }
.ver { color: var(--text2); font-size: 12px; }
.meta { color: var(--text2); font-size: 13px; display: flex; align-items: center; gap: 10px; }
nav { display: flex; gap: 8px; margin-bottom: 14px; }
</style>
