<script setup>
// 首启向导：三步完成配置（手机连接 → 管理密码 → 完成）。
// 免密登录后进入；保存走 /admin/api/config 表单模式（保存即热生效）。
import { ref, computed } from 'vue'
import { api, randHex } from '../api.js'
import EyeIcon from './EyeIcon.vue'

const emit = defineEmits(['done'])

const step = ref(1)
const busy = ref(false)
const error = ref('')

// 第 1 步：手机端
const phoneUrl = ref('')
const phoneToken = ref('')
const testing = ref(false)
const testResult = ref(null) // {ok, text}

// 第 2 步：管理密码
const password = ref('')
const password2 = ref('')
const showPw = ref(false)
const regenerateWebhook = ref(false)

const canNext1 = computed(() => /^https?:\/\/.+:\d+$/.test(phoneUrl.value.trim()) && phoneToken.value.trim().length >= 8)
const canFinish = computed(() => password.value.length >= 6 && password.value === password2.value)

async function testPhone() {
  testing.value = true
  testResult.value = null
  try {
    // 先保存，再用保存后的配置实测连通（状态接口会 GET /ping）
    await api.saveValues({ phone: { url: phoneUrl.value.trim(), token: phoneToken.value.trim() } })
    const st = await api.status()
    testResult.value = st.phone.ok
      ? { ok: true, text: '✓ 手机连接成功' }
      : { ok: false, text: `✗ 无法连通（${st.phone.detail}）——确认手机 App 已启动、地址正确、同一网络` }
  } catch (e) {
    testResult.value = { ok: false, text: e.message }
  }
  testing.value = false
}

async function finish() {
  busy.value = true
  error.value = ''
  try {
    const values = {
      phone: { url: phoneUrl.value.trim(), token: phoneToken.value.trim() },
      admin: { password: password.value },
    }
    if (regenerateWebhook.value) {
      values.webhook = { token: randHex(32) }
    }
    const r = await api.saveValues(values)
    if (r.needs_restart) {
      await api.restart().catch(() => {})  // 重启中响应会断，忽略
      await new Promise(resolve => setTimeout(resolve, 2500))
    }
    emit('done')
  } catch (e) {
    error.value = e.message
  }
  busy.value = false
}
</script>

<template>
  <div class="wrap">
    <div class="card">
      <h1>欢迎使用 · 首次配置</h1>

      <!-- 步骤 1：手机连接 -->
      <template v-if="step === 1">
        <div class="step-title">① 连接手机 App</div>
        <div class="field">
          <label>手机地址（安卓 App 首页显示，形如 http://192.168.1.23:8788）</label>
          <input type="text" v-model="phoneUrl" placeholder="http://192.168.1.23:8788">
        </div>
        <div class="field">
          <label>手机 Token（App 首页显示）</label>
          <input type="text" v-model="phoneToken" placeholder="App 首页的 Token">
        </div>
        <button class="btn" :disabled="!canNext1 || testing" @click="testPhone">
          {{ testing ? '测试中…' : '测试连接' }}
        </button>
        <div v-if="testResult" class="msg" :class="testResult.ok ? 'ok' : 'err'">{{ testResult.text }}</div>
        <div class="nav">
          <span></span>
          <button class="btn primary" :disabled="!canNext1" @click="step = 2">下一步</button>
        </div>
      </template>

      <!-- 步骤 2：管理密码 -->
      <template v-else-if="step === 2">
        <div class="step-title">② 设置管理密码</div>
        <div class="field">
          <label>管理密码（≥6 位，用于登录本界面）</label>
          <div class="pw-wrap">
            <input :type="showPw ? 'text' : 'password'" v-model="password" autocomplete="new-password">
            <button class="eye-inside" type="button" :title="showPw ? '隐藏密码' : '显示密码'" @click="showPw = !showPw"><EyeIcon :closed="showPw" :size="15" /></button>
          </div>
        </div>
        <div class="field">
          <label>再输一遍</label>
          <div class="pw-wrap">
            <input :type="showPw ? 'text' : 'password'" v-model="password2" autocomplete="new-password">
            <button class="eye-inside" type="button" :title="showPw ? '隐藏密码' : '显示密码'" @click="showPw = !showPw"><EyeIcon :closed="showPw" :size="15" /></button>
          </div>
        </div>
        <div v-if="password2 && password !== password2" class="msg err">两次输入不一致</div>
        <label class="check">
          <input type="checkbox" v-model="regenerateWebhook">
          顺便重新生成 Webhook Token（第三方应用接入用；默认保留自动生成的）
        </label>
        <div class="nav">
          <button class="btn" @click="step = 1">上一步</button>
          <button class="btn primary" :disabled="!canFinish" @click="step = 3">下一步</button>
        </div>
      </template>

      <!-- 步骤 3：完成 -->
      <template v-else>
        <div class="step-title">③ 完成</div>
        <ul class="summary">
          <li>手机：{{ phoneUrl }}</li>
          <li>管理密码：已设置（下次登录使用）</li>
          <li v-if="regenerateWebhook">Webhook Token：将重新生成</li>
        </ul>
        <div class="nav">
          <button class="btn" @click="step = 2">上一步</button>
          <button class="btn primary" :disabled="busy" @click="finish">
            {{ busy ? '保存中…' : '保存并进入管理界面' }}
          </button>
        </div>
        <div v-if="error" class="msg err">{{ error }}</div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.wrap { min-height: 100vh; display: flex; align-items: center; justify-content: center; padding: 20px; }
.card {
  width: 480px; max-width: 100%;
  background: #fff; border: 1px solid var(--border);
  border-radius: 8px; padding: 32px;
}
h1 { font-size: 18px; margin-bottom: 4px; }
.step-title { font-size: 15px; font-weight: 600; margin-bottom: 16px; }
.field { margin-bottom: 14px; }
label { display: block; color: var(--text2); font-size: 12px; margin-bottom: 4px; }
.pw-wrap { position: relative; }
.pw-wrap input { padding-right: 34px; }
.eye-inside {
  position: absolute; right: 4px; top: 50%; transform: translateY(-50%);
  width: 26px; height: 26px; padding: 0; border: none; background: none; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  color: #a8abb2;
}
.eye-inside:hover { color: var(--primary); }
.nav { display: flex; justify-content: space-between; margin-top: 24px; }
.summary { margin: 0 0 8px 18px; color: var(--text2); line-height: 2; }
.check { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--text2); }
.check input { width: auto; }
</style>
