import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 開發時用 proxy 讓前端與 API 同源（正式環境由 nginx 反向代理，見 nginx.conf）
const API = process.env.TICKET_API ?? 'http://localhost:8083'
const PROFILE_API = process.env.PROFILE_API ?? 'http://localhost:8084'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5176,
    strictPort: true,
    proxy: {
      '/api': { target: API, changeOrigin: true },
      '/profile-api': {
        target: PROFILE_API,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/profile-api/, '/api'),
      },
    },
  },
})
