import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// 前端只访问 Java:/api/** 全部代理到 Java 8080,浏览器侧零 CORS、零 FastAPI 配置。
export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': { target: process.env.JAVA_API_URL || 'http://127.0.0.1:8080', changeOrigin: true },
    },
  },
  test: {
    environment: 'jsdom',
    include: ['tests/**/*.test.js'],
    css: false,
  },
})
