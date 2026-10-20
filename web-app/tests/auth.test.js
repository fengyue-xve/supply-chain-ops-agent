import { describe, it, expect, beforeEach } from 'vitest'
import {
  readAuth,
  saveAuth,
  clearAuth,
  getToken,
  isLoggedIn,
  safeRedirect,
} from '../src/utils/auth'

describe('auth 存取', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
  })

  it('保存后可读回完整登录态', () => {
    saveAuth({ token: 't-1', username: 'admin', role: 'admin' })
    expect(readAuth()).toEqual({ token: 't-1', username: 'admin', role: 'admin' })
  })

  it('无 token 视为未登录', () => {
    expect(isLoggedIn()).toBe(false)
    saveAuth({ token: 't-2', username: 'viewer', role: 'viewer' })
    expect(isLoggedIn()).toBe(true)
    expect(getToken()).toBe('t-2')
  })

  it('损坏 JSON / 空 token 返回 null', () => {
    localStorage.setItem('sca_auth', 'not-json{')
    expect(readAuth()).toBeNull()
    localStorage.setItem('sca_auth', JSON.stringify({ token: '', username: 'x' }))
    expect(readAuth()).toBeNull()
  })

  it('clearAuth 后回到未登录', () => {
    saveAuth({ token: 't-3', username: 'a', role: 'viewer' })
    clearAuth()
    expect(isLoggedIn()).toBe(false)
    expect(localStorage.getItem('sca_auth')).toBeNull()
  })

  it('clearAuth(登出/401)同步清当前用户的会话存储键,不动他人键', () => {
    saveAuth({ token: 't-4', username: 'admin', role: 'admin' })
    sessionStorage.setItem('sca_conv:admin', 'conv-a')
    sessionStorage.setItem('sca_conv:viewer', 'conv-v')
    clearAuth()
    expect(sessionStorage.getItem('sca_conv:admin')).toBeNull()
    expect(sessionStorage.getItem('sca_conv:viewer')).toBe('conv-v')
  })
})

describe('safeRedirect 站内白名单', () => {
  it('站内单斜杠路径放行', () => {
    expect(safeRedirect('/workbench')).toBe('/workbench')
    expect(safeRedirect('/')).toBe('/')
    expect(safeRedirect('/chat?x=1')).toBe('/chat?x=1')
  })

  it('开放重定向一律拒绝回 /', () => {
    expect(safeRedirect('//evil.com')).toBe('/')
    expect(safeRedirect('https://evil.com')).toBe('/')
    expect(safeRedirect('javascript:alert(1)')).toBe('/')
    expect(safeRedirect('')).toBe('/')
    expect(safeRedirect(undefined)).toBe('/')
    expect(safeRedirect('  /a  ')).toBe('/a')
  })
})
