<script setup>
defineProps({ status: Object })
</script>

<template>
  <div class="panel" v-if="status">
    <h2>服务状态</h2>
    <div class="rows">
      <div class="row"><span class="k">监听地址</span><span>{{ status.bind }}</span></div>
      <div class="row"><span class="k">运行时长</span>
        <span>{{ Math.floor(status.uptime_sec / 3600) }}时{{ Math.floor((status.uptime_sec % 3600) / 60) }}分</span>
      </div>
      <div class="row">
        <span class="k">传输模式</span>
        <span>{{ status.mode === 'relay' ? '经中继服务器' : '直连手机（局域网）' }}</span>
      </div>
      <div class="row">
        <span class="k">{{ status.mode === 'relay' ? '中继通道' : '手机端连通性' }}</span>
        <span>
          <span class="tag" :class="status.phone.ok ? 'ok' : 'fail'">{{ status.phone.ok ? '正常' : '不可达' }}</span>
          <code class="url" v-if="status.mode !== 'relay'">{{ status.phone.url }}</code>
          <span v-if="!status.phone.ok" class="detail">{{ status.phone.detail }} —— <template v-if="status.mode === 'relay'">检查中继服务器地址/token/通道 ID 是否正确</template><template v-else>检查手机 App 是否运行、IP 是否变化</template></span>
        </span>
      </div>
      <div class="row"><span class="k">最近消息条数</span><span>{{ status.recent_count }} 条（保留条数可在设置页调整）</span></div>
    </div>
  </div>
  <div class="panel tip" v-else>正在获取状态…</div>
</template>

<style scoped>
.rows { display: flex; flex-direction: column; gap: 10px; }
.row { display: flex; align-items: baseline; gap: 16px; }
.k { width: 110px; color: var(--text2); flex-shrink: 0; }
.url { color: var(--text2); font-size: 12px; }
.detail { color: var(--danger); font-size: 12px; }
.tip { color: var(--text2); }
</style>
