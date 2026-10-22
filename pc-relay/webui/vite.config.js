import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 构建产物输出到 dist/，由 FastAPI StaticFiles 挂载到 /admin/ui
export default defineConfig({
  plugins: [vue()],
  base: '/admin/ui/',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
  server: {
    // 开发模式：npm run dev，API 代理到本机 8787 的 pc-relay
    proxy: {
      '/admin': 'http://127.0.0.1:8787',
    },
  },
})
