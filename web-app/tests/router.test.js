import { describe, it, expect, beforeEach } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { createAppRouter } from '../src/router'
import { saveAuth } from '../src/utils/auth'

describe('路由守卫:未登录导向 /login 并带 redirect', () => {
  beforeEach(() => localStorage.clear())

  it('未登录访问 / -> /login?redirect=/', async () => {
    const router = createAppRouter(createMemoryHistory())
    await router.push('/')
    await router.isReady()
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/')
  })

  it('未登录访问未知路径 -> 经兜底后到 /login?redirect=/', async () => {
    const router = createAppRouter(createMemoryHistory())
    await router.push('/nope')
    await router.isReady()
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/')
  })

  it('未登录不允许进入工作台', async () => {
    const router = createAppRouter(createMemoryHistory())
    await router.push('/')
    expect(router.currentRoute.value.name).not.toBe('workbench')
  })
})

describe('路由守卫:已登录状态', () => {
  beforeEach(() => localStorage.clear())

  it('已登录访问 /login -> 自动回 /', async () => {
    saveAuth({ token: 't-1', username: 'u', role: 'viewer' })
    const router = createAppRouter(createMemoryHistory())
    await router.push('/login')
    await router.isReady()
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('已登录访问 / 放行进入工作台', async () => {
    saveAuth({ token: 't-1', username: 'u', role: 'viewer' })
    const router = createAppRouter(createMemoryHistory())
    await router.push('/')
    await router.isReady()
    expect(router.currentRoute.value.path).toBe('/')
    expect(router.currentRoute.value.name).toBe('workbench')
  })
})
