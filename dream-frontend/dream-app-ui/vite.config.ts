import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

// Vite 配置：前端开发服务器，端口 5173；API 通过开发服务器同源代理到后端。
export default defineConfig({
  plugins: [vue(), tailwindcss()],
  appType: 'spa',
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  preview: {
    port: 5173
  }
})
