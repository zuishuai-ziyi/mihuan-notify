<script setup>
import { ref, onMounted } from 'vue'
import EyeIcon from './EyeIcon.vue'

const emit = defineEmits(['ok', 'setup'])
const password = ref('')
const showPassword = ref(false)
const loading = ref(false)
const error = ref('')
const needsSetup = ref(false)

onMounted(async () => {
  try {
    const h = await (await fetch('/health')).json()
    needsSetup.value = !!h.needs_setup
  } catch { /* 服务不可达时保持普通登录态 */ }
})

async function enterSetup() {
  loading.value = true
  error.value = ''
  try {
    // 服务端在 needs_setup 且未设密码时免密放行，返回 setup:true
    const r = await fetch('/admin/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: '' }),
    })
    const d = await r.json().catch(() => ({}))
    if (r.ok && d.setup) emit('setup')
    else {
      needsSetup.value = false
      error.value = d.error || '当前状态不支持向导，请直接输入密码登录'
    }
  } catch {
    error.value = '网络错误，服务未响应'
  }
  loading.value = false
}

async function submit() {
  if (!password.value) return
  loading.value = true
  error.value = ''
  try {
    const r = await fetch('/admin/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: password.value }),
    })
    const d = await r.json().catch(() => ({}))
    if (r.ok) {
      // setup:true = 首启未配置，服务端免密放行 → 进入向导
      if (d.setup) emit('setup')
      else emit('ok')
    } else {
      error.value = d.error === 'bad_credentials' ? '密码错误' : (d.error || '登录失败')
    }
  } catch {
    error.value = '网络错误，服务未响应'
  }
  loading.value = false
}
</script>

<template>
  <div class="wrap">
    <div class="card">
      <h1>通知转发 · 管理登录</h1>
      <button v-if="needsSetup" class="btn primary big" :disabled="loading" @click="enterSetup">
        {{ loading ? '进入中…' : '开始初始配置 →' }}
      </button>
      <form @submit.prevent="submit">
        <div v-if="!needsSetup" class="pw-wrap">
          <input :type="showPassword ? 'text' : 'password'" v-model="password" placeholder="管理密码" autofocus autocomplete="current-password">
          <button class="eye-inside" type="button" :title="showPassword ? '隐藏密码' : '显示密码'" @click="showPassword = !showPassword"><EyeIcon :closed="showPassword" :size="15" /></button>
        </div>
        <button v-if="!needsSetup" class="btn primary" type="submit" :disabled="loading || !password">
          {{ loading ? '登录中…' : '登 录' }}
        </button>
      </form>
      <div v-if="error" class="msg err">{{ error }}</div>
    </div>
  </div>
</template>

<style scoped>
.wrap { min-height: 100vh; display: flex; align-items: center; justify-content: center; }
.card {
  width: 340px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 32px;
}
h1 { font-size: 18px; margin-bottom: 6px; }
form { display: flex; flex-direction: column; gap: 12px; }
.pw-wrap { position: relative; }
.pw-wrap input { padding-right: 34px; }
.eye-inside {
  position: absolute; right: 4px; top: 50%; transform: translateY(-50%);
  width: 26px; height: 26px; padding: 0; border: none; background: none; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  color: #a8abb2;
}
.eye-inside:hover { color: var(--primary); }
.big { width: 100%; padding: 10px 0; font-size: 14px; }
</style>
