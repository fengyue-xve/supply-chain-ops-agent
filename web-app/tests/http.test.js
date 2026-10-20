import { describe, it, expect, beforeEach, vi } from 'vitest'
import {
  http,
  requestInterceptor,
  responseErrorInterceptor,
  registerUnauthorizedHandler,
  isLoginUrl,
  _resetAuthFlowForTest,
} from '../src/api/http'
import { saveAuth } from '../src/utils/auth'

function apiError(url, status, message) {
  return {
    response: { status, data: { code: status, message, data: null } },
    config: { url },
    message: 'Request failed',
  }
}

describe('isLoginUrl 兼容相对/完整 URL/query', () => {
  it('识别三种形态的登录地址', () => {
    expect(isLoginUrl('/auth/login')).toBe(true)
    expect(isLoginUrl('/api/auth/login')).toBe(true)
    expect(isLoginUrl('http://localhost:8080/api/auth/login?x=1')).toBe(true)
    expect(isLoginUrl('/auth/login?redirect=%2F')).toBe(true)
  })

  it('业务地址不是登录地址', () => {
    expect(isLoginUrl('/chat')).toBe(false)
    expect(isLoginUrl('/agent/tools/query-stock-level')).toBe(false)
    expect(isLoginUrl(undefined)).toBe(false)
  })
})

describe('request 拦截器:统一注入 Bearer', () => {
  beforeEach(() => localStorage.clear())

  it('有 token 时注入 Authorization', () => {
    saveAuth({ token: 'tok-9', username: 'u', role: 'viewer' })
    const config = { headers: {} }
    expect(requestInterceptor(config).headers.Authorization).toBe('Bearer tok-9')
  })

  it('无 token 时不注入', () => {
    const config = { headers: {} }
    expect(requestInterceptor(config).headers.Authorization).toBeUndefined()
  })
})

describe('response 拦截器:401 分流 / 403 不清 token', () => {
  let handler
  beforeEach(() => {
    localStorage.clear()
    _resetAuthFlowForTest()
    handler = vi.fn()
    registerUnauthorizedHandler(handler)
  })

  it('登录接口 401 只 reject,不清 token、不跳转', async () => {
    saveAuth({ token: 'keep-me', username: 'u', role: 'viewer' })
    const error = apiError('/auth/login', 401, '用户名或密码错误')
    await expect(responseErrorInterceptor(error)).rejects.toBe(error)
    expect(error.message).toBe('用户名或密码错误')
    expect(localStorage.getItem('sca_auth')).not.toBeNull()
    expect(handler).not.toHaveBeenCalled()
  })

  it('业务 401 清 token 并触发一次跳转', async () => {
    saveAuth({ token: 'drop-me', username: 'u', role: 'admin' })
    const error = apiError('/chat', 401, '认证失败')
    await expect(responseErrorInterceptor(error)).rejects.toBe(error)
    expect(localStorage.getItem('sca_auth')).toBeNull()
    expect(handler).toHaveBeenCalledTimes(1)
    expect(handler).toHaveBeenCalledWith(expect.any(String))
  })

  it('并发业务 401 只跳转一次(防重复跳转)', async () => {
    saveAuth({ token: 'drop-me', username: 'u', role: 'admin' })
    const first = apiError('/chat', 401, '认证失败')
    const second = apiError('/agent/tools/query-stock-level', 401, '认证失败')
    await expect(responseErrorInterceptor(first)).rejects.toBe(first)
    await expect(responseErrorInterceptor(second)).rejects.toBe(second)
    expect(handler).toHaveBeenCalledTimes(1)
    expect(localStorage.getItem('sca_auth')).toBeNull()
  })

  it('403 不清 token,仅透传后端 message', async () => {
    saveAuth({ token: 'keep-me', username: 'u', role: 'viewer' })
    const error = apiError('/agent/tools/query-stock-level', 403, '无权限执行该操作')
    await expect(responseErrorInterceptor(error)).rejects.toBe(error)
    expect(error.message).toBe('无权限执行该操作')
    expect(localStorage.getItem('sca_auth')).not.toBeNull()
    expect(handler).not.toHaveBeenCalled()
  })

  it('业务 400 带后端 message 且不清 token', async () => {
    saveAuth({ token: 'keep-me', username: 'u', role: 'viewer' })
    const error = apiError('/agent/tools/query-sales', 400, 'days 必须为 7 或 30')
    await expect(responseErrorInterceptor(error)).rejects.toBe(error)
    expect(error.message).toBe('days 必须为 7 或 30')
    expect(localStorage.getItem('sca_auth')).not.toBeNull()
  })

  it('401 且无 message 时保留原错误文案', async () => {
    saveAuth({ token: 'drop-me', username: 'u', role: 'admin' })
    const error = { response: { status: 401, data: null }, config: { url: '/chat' }, message: 'boom' }
    await expect(responseErrorInterceptor(error)).rejects.toBe(error)
    expect(error.message).toBe('boom')
  })
})

describe('http 实例', () => {
  it('baseURL=/api,调用方只写相对路径(防 /api/api)', () => {
    expect(http.defaults.baseURL).toBe('/api')
  })
})
