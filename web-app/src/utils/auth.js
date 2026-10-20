// 登录态唯一存储:localStorage 单键 sca_auth。token 不渲染、不打日志。

import { clearConversationId } from './conversation'

const KEY = 'sca_auth'

export function readAuth() {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return null
    const value = JSON.parse(raw)
    if (!value || typeof value.token !== 'string' || !value.token) return null
    return { token: value.token, username: value.username || '', role: value.role || '' }
  } catch {
    return null
  }
}

export function saveAuth({ token, username, role }) {
  localStorage.setItem(KEY, JSON.stringify({ token, username: username || '', role: role || '' }))
}

// 登出/401:清登录态的同时清当前用户的会话存储键(不留续会话语义)
export function clearAuth() {
  const auth = readAuth()
  if (auth?.username) {
    clearConversationId(auth.username)
  }
  localStorage.removeItem(KEY)
}

export function getToken() {
  return readAuth()?.token ?? null
}

export function isLoggedIn() {
  return readAuth() !== null
}

// 登录后跳转白名单:只允许站内单斜杠相对路径;拒绝 //evil.com、https://、javascript: 等开放重定向。
export function safeRedirect(raw) {
  const value = String(raw ?? '').trim()
  if (!value) return '/'
  if (!value.startsWith('/') || value.startsWith('//')) return '/'
  try {
    const url = new URL(value, 'http://sca.invalid')
    if (url.origin !== 'http://sca.invalid' || url.protocol !== 'http:') return '/'
    return value
  } catch {
    return '/'
  }
}
