<script setup>
import { ref, onMounted } from 'vue'
import Login from './components/Login.vue'
import SetupWizard from './components/SetupWizard.vue'
import Dashboard from './components/Dashboard.vue'

const authed = ref(false)
const wizard = ref(false)
const checking = ref(true)

async function checkAuth() {
  try {
    const r = await fetch('/admin/api/status')
    authed.value = r.ok
  } catch {
    authed.value = false
  }
  checking.value = false
}

onMounted(checkAuth)
</script>

<template>
  <div v-if="checking" class="boot">检查登录状态…</div>
  <!-- 向导分支必须排在 !authed 之前：enterSetup 拿到免密 cookie 后只置 wizard=true，
       authed 仍为 false，若 !authed 在前会永远渲染 Login（用户只能手动刷新才进向导） -->
  <SetupWizard v-if="wizard" @done="wizard = false; authed = true" />
  <Login v-else-if="!authed" @ok="authed = true" @setup="wizard = true" />
  <Dashboard v-else />
</template>

<style scoped>
.boot { padding: 80px; text-align: center; color: #909399; }
</style>
