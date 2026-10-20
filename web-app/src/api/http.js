// axios 单实例:baseURL=/api(经 Vite proxy 到 Java,前端永不直连 FastAPI)。
// 401 分流:登录接口的 401 只交 Login.vue 展示;业务 401 清 sca_auth 并触发一次回登录跳转。
// 403/400/5xx 不清 token,组件展示后端 message。

import axios from 'axios'
import { getToken, clearAuth } from '../utils/auth'

export const http = axios.create({ baseURL: '/api', timeout: 60000 })

let redirecting = false
let onUnauthorized = null

export function registerUnauthorizedHandler(handler) {
  onUnauthorized = handler
}

/** 仅测试用:复位防重复跳转标志与处理器。 */
export function _resetAuthFlowForTest() {
  redirecting = false
  onUnauthorized = null
}

/** 兼容相对路径('/auth/login')、完整 URL(http://host/api/auth/login)、带 query 三种形态。 */
export function isLoginUrl(url) {
  let path = String(url ?? '')
  try {
    path = new URL(path, window.location.origin).pathname
  } catch {
    // 保留原样再按 path 判断
  }
  path = path.split('?')[0]
  return path === '/api/auth/login' || path.endsWith('/auth/login')
}

function currentLocation() {
  return window.location.pathname + window.location.search
}

export function requestInterceptor(config) {
  const token = getToken()
  if (token) {
    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
}

export function responseErrorInterceptor(error) {
  const response = error.response
  const envelopeMessage = response?.data?.message
  if (typeof envelopeMessage === 'string' && envelopeMessage) {
    error.message = envelopeMessage
  }
  if (response?.status === 401 && !isLoginUrl(error.config?.url)) {
    clearAuth()
    if (!redirecting) {
      redirecting = true
      if (onUnauthorized) onUnauthorized(currentLocation())
    }
  }
  return Promise.reject(error)
}

http.interceptors.request.use(requestInterceptor)
http.interceptors.response.use((response) => response, responseErrorInterceptor)
