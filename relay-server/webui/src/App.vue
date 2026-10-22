<template>
  <div class="wrap">
    <Login v-if="!authed" @done="authed = true" />
    <Dashboard v-else @logout="authed = false" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api } from './api.js'
import Login from './components/Login.vue'
import Dashboard from './components/Dashboard.vue'

const authed = ref(false)

onMounted(async () => {
  try {
    const st = await api.status()
    authed.value = !!st.ok
  } catch {
    authed.value = false
  }
})
</script>
