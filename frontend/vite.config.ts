import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Vite 配置文件：负责开发服务器与构建行为。
// 关键点是下面的 /api 代理：开发阶段，前端跑在 5173 端口，
// 所有发往 /api 的请求会被转发到后端 Spring Boot（8080 端口），
// 从而天然避开跨域（CORS）问题，前端代码里只需写相对路径 /api/...。
export default defineConfig({
  base: './',
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
