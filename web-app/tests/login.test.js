import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import Login from '../src/views/Login.vue'
import { createAppRouter } from '../src/router'
import { readAuth } from '../src/utils/auth'
import { login } from '../src/api'

vi.mock('../src/api', () => ({ login: vi.fn() }))

async function mountLogin(query) {
  const router = createAppRouter(createMemoryHistory())
  await router.push({ path: '/login', query })
  await router.isReady()
  const push = vi.spyOn(router, 'push')
  const wrapper = mount(Login, { global: { plugins: [router] } })
  return { wrapper, router, push }
}

async function fillAndSubmit(wrapper, name, password) {
  if (name !== undefined) await wrapper.find('[data-test="username"]').setValue(name)
  if (password !== undefined) await wrapper.find('[data-test="password"]').setValue(password)
  await wrapper.find('form').trigger('submit')
  await flushPromises()
}

describe('Login 渲染与空值校验', () => {
  beforeEach(() => localStorage.clear())

  it('渲染标题与用户名/密码输入框', async () => {
    const { wrapper } = await mountLogin()
    expect(wrapper.text()).toContain('供应链智能运营 Agent')
    expect(wrapper.find('[data-test="username"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="password"]').exists()).toBe(true)
  })

  it('空用户名提示,不调用接口', async () => {
    const { wrapper } = await mountLogin()
    await fillAndSubmit(wrapper, '  ', 'pw')
    expect(wrapper.find('[data-test="field-error"]').text()).toBe('请输入用户名')
    expect(login).not.toHaveBeenCalled()
  })

  it('缺密码提示,不调用接口', async () => {
    const { wrapper } = await mountLogin()
    await fillAndSubmit(wrapper, 'admin', '')
    expect(wrapper.find('[data-test="field-error"]').text()).toBe('请输入密码')
    expect(login).not.toHaveBeenCalled()
  })
})

describe('Login 提交', () => {
  beforeEach(() => localStorage.clear())

  it('成功:保存 sca_auth、token 不出现在页面文本、跳回站内 /', async () => {
    login.mockResolvedValue({
      data: { data: { token: 'tok-secret-9', username: 'admin', role: 'admin' } },
    })
    const { wrapper, router, push } = await mountLogin()
    await fillAndSubmit(wrapper, 'admin', 'pw-1')
    expect(login).toHaveBeenCalledWith('admin', 'pw-1')
    expect(readAuth()).toEqual({ token: 'tok-secret-9', username: 'admin', role: 'admin' })
    expect(wrapper.text()).not.toContain('tok-secret-9')
    expect(push).toHaveBeenCalledWith('/')
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('redirect 站内路径白名单放行,原样交给路由', async () => {
    login.mockResolvedValue({
      data: { data: { token: 'tok-1', username: 'u', role: 'viewer' } },
    })
    const { wrapper, router, push } = await mountLogin({ redirect: '/workbench' })
    await fillAndSubmit(wrapper, 'u', 'pw')
    expect(push).toHaveBeenCalledWith('/workbench')
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('开放重定向 redirect 一律收敛回 /', async () => {
    login.mockResolvedValue({
      data: { data: { token: 'tok-1', username: 'u', role: 'viewer' } },
    })
    const { wrapper, router, push } = await mountLogin({ redirect: '//evil.com' })
    await fillAndSubmit(wrapper, 'u', 'pw')
    expect(push).toHaveBeenCalledWith('/')
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('登录失败(401):仅本页提示,不保存登录态不跳转', async () => {
    login.mockRejectedValue({ response: { status: 401 }, message: '用户名或密码错误' })
    const { wrapper, router, push } = await mountLogin()
    await fillAndSubmit(wrapper, 'admin', 'wrong')
    expect(wrapper.find('[data-test="error"]').text()).toBe('用户名或密码错误')
    expect(readAuth()).toBeNull()
    expect(push).not.toHaveBeenCalled()
    expect(router.currentRoute.value.path).toBe('/login')
  })

  it('无 response(网络不可达):提示无法连接服务', async () => {
    login.mockRejectedValue(new Error('Network Error'))
    const { wrapper } = await mountLogin()
    await fillAndSubmit(wrapper, 'admin', 'pw')
    expect(wrapper.find('[data-test="error"]').text()).toBe('无法连接服务，请稍后重试')
    expect(readAuth()).toBeNull()
  })
})
