<template>
  <div>
    <div class="panel" v-for="(sec, sid) in schema" :key="sid">
      <h2>{{ sec.label }}</h2>
      <div class="field" v-for="(spec, key) in sec.keys" :key="key">
        <label>{{ spec.label }}</label>
        <template v-if="spec.type === 'secret'">
          <div class="row">
            <input :type="show[key] ? 'text' : 'password'" v-model="values[key]" :placeholder="hints[key + '_hint'] || ''" />
            <button class="btn small" @click="show[key] = !show[key]">{{ show[key] ? '隐藏' : '显示' }}</button>
          </div>
        </template>
        <input v-else v-model="values[key]" />
        <div class="help" v-if="spec.help">{{ spec.help }}</div>
      </div>
      <button class="btn primary" :disabled="busy" @click="save">{{ busy ? '保存中…' : '保存' }}</button>
      <div v-if="msg" class="msg" :class="msg.ok ? 'ok' : 'err'">{{ msg.text }}</div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { api } from '../api.js'

const schema = ref({})
const values = reactive({})
const hints = reactive({})
const show = reactive({})
const busy = ref(false)
const msg = ref(null)

onMounted(async () => {
  const r = await api.config()
  schema.value = r.schema || {}
  Object.assign(values, r.values || {})
  Object.assign(hints, r.hints || {})
})

async function save() {
  busy.value = true
  msg.value = null
  try {
    const r = await api.saveValues({ ...values })
    msg.value = { ok: !!r.ok, text: r.ok ? '已保存' : (r.error || '保存失败') }
    if (r.ok && r.notes?.length) msg.value.text = r.notes.join('；')
    if (r.ok) {
      const fresh = await api.config()
      Object.assign(values, fresh.values || {})
      Object.assign(hints, fresh.hints || {})
    }
  } catch {
    msg.value = { ok: false, text: '请求失败' }
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.field { margin-bottom: 12px; }
.field label { display: block; font-size: 13px; margin-bottom: 4px; }
.row { display: flex; gap: 6px; }
.row input { flex: 1; }
.help { font-size: 12px; color: var(--text2); margin-top: 4px; }
</style>
