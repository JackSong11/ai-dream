import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Vite 配置：前端开发服务器，端口 5173
// 后端已配置 CORS，前端直接跨域调用 http://localhost:8080
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173
  }
})