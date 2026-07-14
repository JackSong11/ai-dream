import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

// Vite 配置：前端开发服务器，端口 5173
// 后端已配置 CORS，前端直接跨域调用 http://localhost:8080
export default defineConfig({
  plugins: [vue(), tailwindcss()],
  appType: 'spa',
  server: {
    port: 5173
  },
  preview: {
    port: 5173
  }
})