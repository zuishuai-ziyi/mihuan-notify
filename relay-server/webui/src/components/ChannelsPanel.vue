<template>
  <div>
    <div class="panel">
      <h2>通道列表</h2>
      <div class="ch-add">
        <input v-model="newName" placeholder="通道名称，如：我的手机" @keyup.enter="add" />
        <button class="btn primary" :disabled="!newName.trim() || busy" @click="add">新建通道</button>
      </div>

      <div v-if="channels.length === 0" class="empty">还没有通道。新建一个，然后把 push/poll token 分别填入 PC 端与手机端。</div>

      <div v-for="c in channels" :key="c.id" class="ch-card">
        <div class="ch-head">
          <b>{{ c.name }}</b>
          <span class="mono dim">{{ c.id }}</span>
          <span class="tag" :class="c.online ? 'ok' : 'fail'">{{ c.online ? '手机在线' : '手机离线' }}</span>
          <span class="tag" v-if="c.pending > 0">待推送 {{ c.pending }}</span>
          <span class="grow"></span>
          <button class="btn small" @click="test(c)" :disabled="testing === c.id">{{ testing === c.id ? '发送中…' : '测试' }}</button>
          <button class="btn small danger" @click="del(c)">删除</button>
        </div>

        <div class="ch-tokens">
          <div class="tok">
            <label>Push Token（PC 端推送用）</label>
            <div class="tok-row">
              <input :type="showPush[c.id] ? 'text' : 'password'" :value="c.push_token" readonly />
              <button class="btn small" @click="showPush[c.id] = !showPush[c.id]">{{ showPush[c.id] ? '隐藏' : '显示' }}</button>
              <button class="btn small" @click="copy(c.push_token)">复制</button>
              <button class="btn small" @click="rotate(c, 'push')">重置</button>
              <button class="btn small" @click="copyPushUrl(c)">复制推送 URL</button>
            </div>
          </div>
          <div class="tok">
            <label>Poll Token（手机端拉取用）</label>
            <div class="tok-row">
              <input :type="showPoll[c.id] ? 'text' : 'password'" :value="c.poll_token" readonly />
              <button class="btn small" @click="showPoll[c.id] = !showPoll[c.id]">{{ showPoll[c.id] ? '隐藏' : '显示' }}</button>
              <button class="btn small" @click="copy(c.poll_token)">复制</button>
              <button class="btn small" @click="rotate(c, 'poll')">重置</button>
            </div>
          </div>
        </div>

        <div v-if="msgs[c.id]" class="msg" :class="msgs[c.id].ok ? 'ok' : 'err'">{{ msgs[c.id].text }}</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, reactive } from 'vue'
import { api } from '../api.js'

const channels = ref([])
const newName = ref('')
const busy = ref(false)
const testing = ref('')
const showPush = reactive({})
const showPoll = reactive({})
const msgs = reactive({})
let timer = null

onMounted(() => {
  poll()
  timer = setInterval(poll, 5000)
})
onUnmounted(() => clearInterval(timer))

async function poll() {
  try {
    const r = await api.channels()
    channels.value = r.channels || []
  } catch {}
}

async function add() {
  if (!newName.value.trim()) return
  busy.value = true
  try {
    await api.createChannel(newName.value.trim())
    newName.value = ''
    await poll()
  } finally {
    busy.value = false
  }
}

async function del(c) {
  if (!confirm(`删除通道「${c.name}」？其全部消息与 token 将作废。`)) return
  await api.deleteChannel(c.id)
  await poll()
}

async function rotate(c, which) {
  if (!confirm(`重置 ${which === 'push' ? 'Push' : 'Poll'} Token？旧 token 立即失效，对应端需更新。`)) return
  await api.rotateToken(c.id, which)
  await poll()
}

async function test(c) {
  testing.value = c.id
  try {
    const r = await api.testSend(c.id, '中继测试', `通道「${c.name}」的测试消息。手机端在线时将立即拉取并弹通知。`)
    msgs[c.id] = r.ok
      ? { ok: true, text: `已入队（${r.id}）。手机端下次拉取时送达。` }
      : { ok: false, text: r.error || '失败' }
  } catch {
    msgs[c.id] = { ok: false, text: '请求失败' }
  } finally {
    testing.value = ''
    setTimeout(() => { delete msgs[c.id] }, 5000)
  }
}

async function copy(text) {
  await api.copyText(text)
  alert('已复制到剪贴板')
}

async function copyPushUrl(c) {
  const base = location.origin
  await api.copyText(`${base}/channels/${c.id}/messages`)
  alert('已复制推送 URL（PC 端填中继 URL 时可直接粘贴）')
}
</script>

<style scoped>
.ch-add { display: flex; gap: 8px; margin-bottom: 14px; }
.ch-add input { flex: 1; }
.empty { color: var(--text2); font-size: 13px; padding: 12px 0; }
.ch-card { border: 1px solid var(--border); border-radius: 6px; padding: 12px 14px; margin-bottom: 12px; }
.ch-head { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.grow { flex: 1; }
.dim { color: var(--text2); font-size: 12px; }
.ch-tokens { display: grid; gap: 10px; }
.tok label { display: block; font-size: 12px; color: var(--text2); margin-bottom: 4px; }
.tok-row { display: flex; gap: 6px; }
.tok-row input { flex: 1; font-family: Consolas, monospace; font-size: 12px; }
</style>
