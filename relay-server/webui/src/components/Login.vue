<template>
  <div class="login-box panel">
    <h2>mihuan-notify 中继服务</h2>
    <template v-if="needsSetup === null">
      <p class="setup-hint">正在检测服务状态…</p>
    </template>
    <template v-else-if="!needsSetup">
      <div class="field">
        <label>管理密码</label>
        <input type="password" v-model="password" @keyup.enter="doLogin" placeholder="管理密码" />
      </div>
      <button class="btn primary" :disabled="busy" @click="doLogin">{{ busy ? '登录中…' : '登录' }}</button>
      <div v-if="err" class="msg err">{{ err }}</div>
    </template>
    <template v-else>
      <p class="setup-hint">首次使用，尚未设置管理密码。请直接进入控制台完成初始配置。</p>
      <button class="btn primary" :disabled="busy" @click="doLogin">{{ busy ? '进入中…' : '开始初始配置' }}</button>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api.js'

const emit = defineEmits(['done'])
// null = 检测中（避免先闪现密码表单再切换成初始配置）
const needsSetup = ref(null)
const password = ref('')
const busy = ref(false)
const err = ref('')

onMounted(async () => {
  try {
    const h = await fetch('/health').then(r => r.json())
    needsSetup.value = !!h.needs_setup
  } catch {
    needsSetup.value = false // /health 不可达时按密码登录处理
  }
})

async function doLogin() {
  busy.value = true
  err.value = ''
  try {
    const r = await api.login(password.value)
    if (r.ok) {
      emit('done')
    } else {
      err.value = '密码错误'
    }
  } catch (e) {
    err.value = '连接失败'
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.login-box { max-width: 360px; margin: 10vh auto; }
.setup-hint { margin-bottom: 12px; color: var(--text2); font-size: 13px; }
</style>
