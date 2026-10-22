<script setup>
import { ref } from 'vue'
import { api } from '../api.js'

const title = ref('测试通知')
const body = ref('这是一条来自 Web UI 的测试消息')
const priority = ref('normal')
const sending = ref(false)
const result = ref(null) // {ok, text}

async function send() {
  sending.value = true
  result.value = null
  try {
    const d = await api.testSend(title.value, body.value, priority.value)
    result.value = d.delivered
      ? { ok: true, text: `已送达手机（第 ${d.attempts} 次尝试成功），手环应已收到` }
      : { ok: false, text: `转发失败（尝试 ${d.attempts} 次）——检查手机连通性` }
  } catch (e) {
    result.value = { ok: false, text: e.message }
  }
  sending.value = false
}
</script>

<template>
  <div class="panel">
    <h2>测试发送（走完整 webhook → 手机链路）</h2>
    <form @submit.prevent="send">
      <div class="field">
        <label>标题</label>
        <input type="text" v-model="title" maxlength="64">
      </div>
      <div class="field">
        <label>正文</label>
        <textarea v-model="body" rows="3" maxlength="4096"></textarea>
      </div>
      <div class="field inline">
        <label>优先级</label>
        <select v-model="priority">
          <option value="normal">normal（静默）</option>
          <option value="high">high（响铃+横幅）</option>
        </select>
      </div>
      <button class="btn primary" type="submit" :disabled="sending">
        {{ sending ? '发送中…（最长约 20s，含重试）' : '发送到手机' }}
      </button>
      <div v-if="result" class="msg" :class="result.ok ? 'ok' : 'err'">{{ result.text }}</div>
    </form>
  </div>
</template>

<style scoped>
.field { margin-bottom: 12px; }
.field.inline { display: flex; align-items: center; gap: 10px; }
.field.inline select { width: 220px; }
label { display: block; color: var(--text2); font-size: 12px; margin-bottom: 4px; }
.field.inline label { margin-bottom: 0; }
</style>
