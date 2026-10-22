<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { api, fmtUptime } from '../api.js'
import StatusPanel from './StatusPanel.vue'
import RecentPanel from './RecentPanel.vue'
import TestPanel from './TestPanel.vue'
import ConfigPanel from './ConfigPanel.vue'
import WebhookPanel from './WebhookPanel.vue'

const tab = ref('status')
const tabs = [
  ['status', '状态'],
  ['recent', '最近消息'],
  ['test', '测试发送'],
  ['webhook', '接入指引'],
  ['config', '设置'],
]

// 全局轮询状态（StatusPanel 内部消费）
const status = ref(null)
let timer = null

async function refresh() {
  try {
    status.value = await api.status()
    if (status.value?.state?.needs_setup) tab.value = 'config'  // 未配置完直达设置页
  } catch { /* 401 由 api.js 统一处理 */ }
}

onMounted(() => {
  refresh()
  timer = setInterval(refresh, 5000)
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div class="layout">
    <header>
      <span class="logo">通知转发 · PC 端管理</span>
      <span v-if="status" class="sub">
        v{{ status.version }} · 已运行 {{ fmtUptime(status.uptime_sec) }}
      </span>
      <button class="btn small" @click="api.logout()">退出</button>
    </header>

    <nav>
      <button v-for="[id, label] in tabs" :key="id" class="tab" :class="{ active: tab === id }" @click="tab = id">
        {{ label }}
        <span v-if="id === 'config' && status?.state?.needs_setup" class="dot">!</span>
      </button>
    </nav>
    <main>
      <StatusPanel :status="status" v-show="tab === 'status'" />
      <RecentPanel v-show="tab === 'recent'" />
      <TestPanel :status="status" v-show="tab === 'test'" />
      <WebhookPanel v-show="tab === 'webhook'" />
      <ConfigPanel v-show="tab === 'config'" />
    </main>
  </div>
</template>

<style scoped>
.layout { max-width: 860px; margin: 0 auto; padding: 20px 16px; }
header {
  display: flex; align-items: center; gap: 12px;
  margin-bottom: 16px;
}
.logo { font-size: 17px; font-weight: 600; }
.sub { color: var(--text2); flex: 1; }
nav { display: flex; gap: 4px; margin-bottom: 16px; border-bottom: 1px solid var(--border); }
.tab {
  padding: 8px 18px; border: none; background: none; cursor: pointer;
  font-size: 14px; color: var(--text2); border-bottom: 2px solid transparent;
  position: relative;
}
.tab.active { color: var(--primary); border-bottom-color: var(--primary); }
.dot {
  position: absolute; top: 4px; right: 6px;
  background: var(--danger); color: #fff;
  width: 14px; height: 14px; border-radius: 50%;
  font-size: 10px; line-height: 14px; text-align: center;
}
</style>
