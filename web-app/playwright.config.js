import { defineConfig } from '@playwright/test'

// 浏览器级 E2E 前置(均为本机,不部署):MySQL 就绪 + Java 8080(dev profile, .env.local 已 source)。
// Vite 5173 由 webServer 启动或复用 --strictPort 保证端口固定,避免误连 5174。
export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.e2e.js',
  timeout: 30_000,
  use: { baseURL: 'http://localhost:5173' },
  webServer: {
    command: 'npm run dev -- --strictPort',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 60_000,
  },
})
