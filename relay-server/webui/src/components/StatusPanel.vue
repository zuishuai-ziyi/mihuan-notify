<template>
  <div>
    <div class="panel">
      <h2>服务状态</h2>
      <table class="kv">
        <tr><td>服务</td><td>relay-server v{{ st.version }}</td></tr>
        <tr><td>监听</td><td class="mono">{{ st.listen }}</td></tr>
        <tr>
          <td>通道</td>
          <td>{{ st.channels_online }}/{{ st.channels_total }} 在线</td>
        </tr>
        <tr><td>待推送</td><td>{{ st.stats.pending ?? '-' }}</td></tr>
        <tr><td>消息总数</td><td>{{ st.stats.messages ?? '-' }}</td></tr>
        <tr><td>运行时长</td><td>{{ fmtUptime(st.uptime_sec) }}</td></tr>
        <tr><td>数据文件</td><td class="mono small">{{ st.state?.path }}</td></tr>
      </table>
    </div>
    <div class="panel">
      <h2>使用说明</h2>
      <p>通道建立后，PC 端把 WebHook 消息推送到本服务，手机端从本服务拉取并弹通知。</p>
      <p>典型场景：PC 与手机不在同一网络（PC 在公司/家里，手机走移动网络）时，由具有公网的中继转发。</p>
    </div>
  </div>
</template>

<script setup>
import { fmtUptime } from '../api.js'
defineProps({ st: { type: Object, required: true } })
</script>

<style scoped>
.kv { width: 100%; border-collapse: collapse; }
.kv td { padding: 5px 8px; border-bottom: 1px solid var(--border); font-size: 13px; }
.kv td:first-child { color: var(--text2); width: 110px; }
.mono { font-family: Consolas, monospace; }
.small { font-size: 12px; }
p { font-size: 13px; color: var(--text2); margin-bottom: 8px; }
</style>
