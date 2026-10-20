import { createApp, h } from 'vue'
import { RouterView } from 'vue-router'
import { router } from './router'
import { registerUnauthorizedHandler } from './api/http'

// 业务请求 401:单点清 token + 回登录(带 redirect);login 页的 401 由 Login.vue 自己处理。
registerUnauthorizedHandler((current) => {
  router.replace({ path: '/login', query: { redirect: current || '/' } })
})

createApp({ render: () => h(RouterView) }).use(router).mount('#app')
