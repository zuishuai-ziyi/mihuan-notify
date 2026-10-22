// render_check.mjs — 无浏览器渲染检查（需要 node + webui/node_modules）
//
// 为什么存在：Web UI 里有一类「定义了但没被调用」的静默 bug ——
// 例如设置页的「开机自启」区块依赖 onMounted 里调用 loadAutostart()，
// 漏调用时 ref 恒为 null，v-if 把整个区块吞掉，页面上什么都看不到，
// 而接口 /admin/api/autostart 本身是好的（后端测试全绿）。
// SSR（renderToString）抓不到这种问题：它不执行 onMounted。
// 所以这里用 Vue 的 createRenderer 造一个无 DOM 的自定义渲染器
// （元素带最小 DOM 垫片，够 v-model / v-show 指令用），
// 让 onMounted 真正跑起来，再把渲染出的节点树 dump 成文本做断言。
//
// 用法：<node> tests/render_check.mjs      （cwd 任意，脚本自己定位路径）

import { readFileSync, writeFileSync, rmSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { parse, compileScript } from '@vue/compiler-sfc'
import { createRenderer, nextTick } from 'vue'

const HERE = dirname(fileURLToPath(import.meta.url))   // webui/tests
const SRC = join(HERE, '..', 'src', 'components')
const TMP = []            // 生成后要删除的临时文件
let pass = 0
let fail = 0

function cleanup() {
  for (const f of TMP.splice(0)) {
    try { rmSync(f, { force: true }) } catch { /* 忽略 */ }
  }
}
process.on('exit', cleanup)
process.on('uncaughtException', (e) => {
  const where = e && e.stack ? e.stack.split('\n').slice(0, 4).join('\n      ') : ''
  console.log(`FAIL  未捕获异常 — ${e && e.message ? e.message : e}\n      ${where}`)
  cleanup()
  process.exit(1)
})

function chk(name, ok, extra = '') {
  if (ok) { pass++; console.log(`PASS  ${name}`) }
  else { fail++; console.log(`FAIL  ${name}${extra ? ' — ' + extra : ''}`) }
}

// ---------------------------------------------------------------- 编译 SFC

function compile(name, rewrites) {
  const src = readFileSync(join(SRC, `${name}.vue`), 'utf8')
  const { descriptor, errors } = parse(src, { filename: `${name}.vue` })
  if (errors.length) throw new Error(`${name}.vue 解析失败: ${errors[0].message}`)
  // inlineTemplate：模板编译进 setup，省去额外产物
  let js = compileScript(descriptor, { inlineTemplate: true, id: name }).content
  for (const [from, to] of rewrites) js = js.split(from).join(to)
  const out = join(SRC, `_rc_${name}.mjs`)
  writeFileSync(out, js)
  TMP.push(out)
  return out
}

const rewriteDeps = [
  ["'../api.js'", "'./_rc_api.mjs'"],
  ['./EyeIcon.vue', './_rc_EyeIcon.mjs'],
]

// 桩：api 的每个方法都返回同一个「什么都有」的对象，够 ConfigPanel/SetupWizard 渲染
const STUB_API = `export const randHex = () => 'a'.repeat(64)
const GENERIC = {
  ok: true, available: true, enabled: false, detail: '', hint: '',
  needs_restart: false, notes: [], version: '0.0.0-test', uptime_sec: 1,
  schema: {
    server: { label: '服务', keys: {
      host: { label: '监听地址', type: 'string' },
      port: { label: '监听端口', type: 'int' } } },
    phone: { label: '手机端（直连模式）', keys: {
      url: { label: '手机地址', type: 'string' },
      token: { label: '手机 Token', type: 'secret', help: '手机端页面上显示的 Token' } } },
    relay: { label: '中继服务器', keys: {
      mode: { label: '传输模式', type: 'select', options: [
        { value: 'direct', label: '直连手机（局域网）' },
        { value: 'relay', label: '经中继服务器' } ] },
      channel_id: { label: '通道 ID', type: 'string' } } },
  },
  values: {
    server: { host: '127.0.0.1', port: '8787' },
    phone: { url: '', token: '__MASKED__' },
    relay: { mode: 'direct', channel_id: '' },
  },
  state: { needs_setup: false },
}
const fn = async () => GENERIC
export const api = new Proxy({}, { get: () => fn })
export const handle = async (r) => r
export const fmtUptime = () => '0 秒'
export const fmtTime = () => ''
`
{
  const f = join(SRC, '_rc_api.mjs')
  writeFileSync(f, STUB_API)
  TMP.push(f)
}
compile('EyeIcon', [])
compile('ConfigPanel', rewriteDeps)
compile('Login', rewriteDeps)

const ConfigPanel = (await import(pathToFileURL(join(SRC, '_rc_ConfigPanel.mjs')).href)).default

// ---------------------------------------------------------------- 无 DOM 渲染器

// 最小 DOM 垫片：v-model / v-show 这些 runtime-dom 指令会用到
function fakeEl(tag) {
  return {
    tag, props: {}, children: [], style: {}, value: '', type: '',
    options: [], multiple: false, selectedIndex: -1,   // v-model 用在 select 时会读这些字段
    addEventListener() {}, removeEventListener() {},
    setAttribute(k, v) { this.props[k] = v },
    getAttribute(k) { return k in this.props ? this.props[k] : null },
    focus() {}, blur() {}, appendChild() {}, insertBefore() {}, removeChild() {},
  }
}

function createOps() {
  return {
    createElement: (tag) => fakeEl(tag),
    createText: (text) => ({ text }),
    createComment: (text) => ({ comment: text }),
    setText: (node, text) => { node.text = text },
    setElementText: (el, text) => { el.children = [{ text }] },
    insert: (child, parent) => { child.parent = parent; parent.children.push(child) },
    remove: (child) => {
      const p = child.parent
      if (p) p.children = p.children.filter((c) => c !== child)
    },
    parentNode: (node) => node.parent || null,
    nextSibling: () => null,
    querySelector: () => null,
    setScopeId: () => {},
    cloneNode: (n) => JSON.parse(JSON.stringify(n)),
    insertStaticContent: () => null,
    patchProp: (el, key, _prev, next) => { el.props[key] = next },
  }
}

// 把节点树 dump 成文本（元素的字符串属性带上，文本节点原样）
function dump(n, out = []) {
  if (!n) return out
  if (n.comment !== undefined) return out
  if (n.text !== undefined) { out.push(String(n.text)); return out }
  const attrs = Object.entries(n.props || {})
    .filter(([, v]) => typeof v === 'string' || typeof v === 'boolean')
    .map(([k, v]) => (v === true ? ` ${k}` : ` ${k}="${v}"`))
    .join('')
  out.push(`<${n.tag}${attrs}>`)
  for (const c of n.children || []) dump(c, out)
  out.push(`</${n.tag}>`)
  return out
}

async function render(Comp, props = {}) {
  const { createApp } = createRenderer(createOps())
  const root = fakeEl('root')
  createApp(Comp, props).mount(root)
  // 等 onMounted 里的异步加载落地
  for (let i = 0; i < 5; i++) { await new Promise((r) => setTimeout(r, 20)); await nextTick() }
  return dump(root).join('\n')
}

// ---------------------------------------------------------------- 断言

try {
  const html = await render(ConfigPanel)

  chk('设置页渲染出「开机自启」区块', html.includes('开机自启'))
  chk('自启区块不是空壳（拿到 available 状态）', html.includes('未启用') && html.includes('开启'),
    '说明 loadAutostart() 没被调用或没生效')
  chk('设置页仍渲染 schema 表单', html.includes('监听地址') && html.includes('手机地址'))
  chk('select 类型字段渲染为选项文本', html.includes('直连手机（局域网）'))
  chk('secret 字段的图标在输入框内（sec-wrap + eye-inside + svg）',
    html.includes('class="sec-wrap"') && html.includes('class="eye-inside"') && html.includes('<svg'))
  chk('secret 输入框为 password 类型且已打码',
    html.includes('type="password"') && html.includes('已设置（留空保持不变）'))

  const loginHtml = await render((await import(pathToFileURL(join(SRC, '_rc_Login.mjs')).href)).default)
  chk('Login 密码框为 pw-wrap 容器', loginHtml.includes('class="pw-wrap"'))
  chk('Login 图标在输入框内（input 在 eye-inside 之前）',
    loginHtml.indexOf('<input') > -1 && loginHtml.indexOf('<input') < loginHtml.indexOf('eye-inside'))
} catch (e) {
  fail++
  console.log(`FAIL  渲染检查异常 — ${e && e.stack ? e.stack.split('\n')[0] : e}`)
} finally {
  cleanup()
}

console.log('----')
console.log(`PASS=${pass} FAIL=${fail}`)
process.exit(fail ? 1 : 0)
