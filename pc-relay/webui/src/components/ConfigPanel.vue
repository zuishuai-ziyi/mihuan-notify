<script setup>
// 设置面板：表单模式（schema 驱动，保存即热生效）+ 开机自启开关（仅 Windows）。
// 秘密字段打码显示，可切换明文；host/port 保存后提示「立即重启」。
// 注意：开机自启状态与 schema 一起在挂载时加载——两个 load 都要调用，否则开关区块不渲染。
import { ref, onMounted } from 'vue'
import { api, randHex } from '../api.js'
import EyeIcon from './EyeIcon.vue'

const schema = ref(null)     // 服务端 CONFIG_SCHEMA
const values = ref({})       // 表单值（秘密字段为 '__MASKED__' 占位）
const revealed = ref({})     // 秘密字段明文切换状态 { 'sec_key': bool }
const state = ref(null)      // C.describe()
const loading = ref(true)
const saving = ref(false)
const result = ref(null)     // {ok, text, needs_restart}

// 开机自启（仅 Windows 可用）
const autostart = ref(null) // {available, enabled, detail}
const autostartBusy = ref(false)
const autostartMsg = ref('')  // 开关操作结果反馈

onMounted(() => {
  load()
  loadAutostart()
})

async function load() {
  loading.value = true
  result.value = null
  try {
    const d = await api.config()
    schema.value = d.schema
    values.value = d.values
    state.value = d.state
  } catch (e) {
    result.value = { ok: false, text: `加载失败：${e.message}` }
  }
  loading.value = false
}

async function loadAutostart() {
  try {
    const prev = autostart.value
    autostart.value = await api.autostartStatus()
    // 开关成功后 detail 会变化：这里不覆盖用户正在看的操作反馈
    if (!prev) autostartMsg.value = ''
  } catch (e) {
    autostart.value = { available: false, hint: `无法读取自启状态：${e?.message || e}` }
  }
}

async function toggleAutostart() {
  autostartBusy.value = true
  autostartMsg.value = ''
  const enabling = !autostart.value?.enabled
  try {
    const r = await api.autostartSet(enabling)
    if (r.ok) {
      autostartMsg.value = enabling ? '✓ 已开启，下次开机登录后自动启动' : '✓ 已关闭'
      await loadAutostart()
    } else {
      autostartMsg.value = `✗ 操作失败：${r.detail || '未知原因'}`
    }
  } catch (e) {
    autostartMsg.value = `✗ 操作失败：${e.message}`
  }
  autostartBusy.value = false
}

function isSecret(sec, key) {
  return schema.value?.[sec]?.keys?.[key]?.type === 'secret'
}

function genSecret(sec, key) {
  values.value[sec][key] = randHex(32)
}

function toggleReveal(sec, key) {
  revealed.value[sec + '_' + key] = !revealed.value[sec + '_' + key]
}

async function save() {
  saving.value = true
  result.value = null
  try {
    const r = await api.saveValues(values.value)
    let text = r.notes.length ? r.notes.join('；') : '✓ 已保存并立即生效'
    result.value = { ok: true, text, needs_restart: r.needs_restart }
    await load()  // 重新拉取（state/打码复位）
    if (result.value) result.value.text = text  // load 会清 result，回填提示
  } catch (e) {
    result.value = { ok: false, text: e.message }
  }
  saving.value = false
}

const restarting = ref(false)
async function restartNow() {
  restarting.value = true
  try {
    await api.restart()
  } catch { /* 重启中连接断开属预期 */ }
  // 轮询等服务回来
  for (let i = 0; i < 20; i++) {
    await new Promise(r => setTimeout(r, 500))
    try {
      const r = await fetch('/health')
      if (r.ok) { location.reload(); return }
    } catch { /* 还没起来 */ }
  }
  restarting.value = false
  result.value = { ok: false, text: '重启超时，请手动刷新页面检查服务状态' }
}
</script>

<template>
  <div class="panel">
    <h2>设置</h2>

    <div v-if="state?.needs_setup" class="msg" style="background:#fdf6ec;color:var(--warning)">
      尚未完成首次配置（手机地址 / 管理密码）——填写下方表单保存即可。
    </div>

    <div v-if="loading" class="empty">加载中…</div>

    <template v-else>
      <div v-for="(sec, secName) in schema" :key="secName" class="section">
        <div class="sec-title">{{ sec.label }}</div>
        <div v-for="(key, keyName) in sec.keys" :key="keyName" class="field">
          <label>{{ key.label }}</label>
          <div class="row" v-if="isSecret(secName, keyName)">
            <div class="sec-wrap">
              <input
                :type="revealed[secName + '_' + keyName] ? 'text' : 'password'"
                v-model="values[secName][keyName]"
                :placeholder="values[secName][keyName] === '__MASKED__' ? '已设置（留空保持不变）' : '未设置'"
                autocomplete="off"
              >
              <button class="eye-inside" type="button"
                :title="revealed[secName + '_' + keyName] ? '隐藏' : '显示'"
                @click="toggleReveal(secName, keyName)"><EyeIcon :closed="!!revealed[secName + '_' + keyName]" :size="15" /></button>
            </div>
            <button class="btn small" type="button" @click="genSecret(secName, keyName)">生成</button>
          </div>
          <select v-else-if="key.type === 'select'" v-model="values[secName][keyName]">
            <option v-for="o in key.options" :key="o.value" :value="o.value">{{ o.label }}</option>
          </select>
          <input v-else type="text" v-model="values[secName][keyName]" autocomplete="off">
          <div v-if="key.help" class="help">{{ key.help }}</div>
        </div>
      </div>

      <!-- 开机自启（仅 Windows）。区块恒显示：检测中 / 可开关 / 不可用（附原因），
           避免接口失败时整个选项静默消失。 -->
      <div class="section as-section">
        <div class="sec-title">开机自启</div>
        <div v-if="!autostart" class="help">检测中…</div>
        <template v-else-if="autostart.available">
          <div class="as-row">
            <span class="tag" :class="autostart.enabled ? 'ok' : 'fail'">
              {{ autostart.enabled ? '已启用' : '未启用' }}
            </span>
            <span v-if="autostart.enabled && autostart.detail" class="help">{{ autostart.detail }}</span>
            <span v-if="autostartMsg" class="help" :class="{ ok: autostartMsg.startsWith('✓') }">{{ autostartMsg }}</span>
            <button class="btn small" :disabled="autostartBusy" @click="toggleAutostart">
              {{ autostartBusy ? '处理中…' : (autostart.enabled ? '关闭' : '开启') }}
            </button>
          </div>
        </template>
        <div v-else class="help">
          {{ autostart.hint || '开机自启仅 Windows 可用——用打包版 exe 时请确认 manage.py 与 pc-relay.exe 在同一目录。' }}
        </div>
      </div>

      <div class="actions">
        <button class="btn primary" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存并应用' }}
        </button>
        <button v-if="result?.needs_restart" class="btn danger" :disabled="restarting" @click="restartNow">
          {{ restarting ? '重启中…' : '⟳ 立即重启（应用新端口/地址）' }}
        </button>
      </div>
    </template>

    <div v-if="result" class="msg" :class="result.ok ? 'ok' : 'err'">{{ result.text }}</div>
  </div>
</template>

<style scoped>
.section { margin-bottom: 18px; padding-bottom: 14px; border-bottom: 1px dashed var(--border); }
.section:last-of-type { border-bottom: none; }
.sec-title { font-weight: 600; margin-bottom: 10px; }
.as-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.as-row .help { margin-top: 0; }
.help { color: var(--text2); font-size: 11px; margin-top: 2px; }
.help.ok { color: var(--ok, #67c23a); }
.tag.ok { color: var(--ok, #67c23a); }
.tag.fail { color: var(--danger); }
.field { margin-bottom: 10px; }
label { display: block; color: var(--text2); font-size: 12px; margin-bottom: 4px; }
.row { display: flex; gap: 8px; }
.row input { flex: 1; }
.sec-wrap { position: relative; flex: 1; }
.sec-wrap input { width: 100%; padding-right: 34px; }
.eye-inside {
  position: absolute; right: 4px; top: 50%; transform: translateY(-50%);
  width: 26px; height: 26px; padding: 0; border: none; background: none; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  color: #a8abb2;
}
.eye-inside:hover { color: var(--primary); }
.help { color: var(--text2); font-size: 11px; margin-top: 2px; }
.actions { display: flex; gap: 8px; margin-top: 6px; flex-wrap: wrap; }
.empty { color: var(--text2); }
</style>
