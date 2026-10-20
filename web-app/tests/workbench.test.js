import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import Workbench from '../src/views/Workbench.vue'
import { createAppRouter } from '../src/router'
import { readAuth, saveAuth } from '../src/utils/auth'
import { auditHistory, poPending } from '../src/api'

// AuditPanel 与 PoPanel 挂载即拉取,这里 mock 掉避免真实网络;其余面板都是提交才请求。
vi.mock('../src/api', () => ({
  auditHistory: vi.fn().mockResolvedValue({
    data: { data: { items: [], total: 0, page: 1, size: 20 } },
    headers: {},
  }),
  poPending: vi.fn().mockResolvedValue({
    data: { data: { items: [], total: 0, page: 1, size: 20 } },
    headers: {},
  }),
}))

async function mountWorkbench(role = 'viewer') {
  saveAuth({ token: 't-1', username: '风月', role })
  const router = createAppRouter(createMemoryHistory())
  await router.push('/')
  await router.isReady()
  const wrapper = mount(Workbench, { global: { plugins: [router] } })
  return { wrapper, router }
}

describe('Workbench 顶栏', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('admin 显示用户名与管理员工牌', async () => {
    const { wrapper } = await mountWorkbench('admin')
    expect(wrapper.text()).toContain('风月')
    expect(wrapper.find('[data-test="role-badge"]').text()).toBe('管理员')
    expect(wrapper.find('[data-test="role-badge"]').classes()).toContain('admin')
  })

  it('viewer 显示查看者工牌', async () => {
    const { wrapper } = await mountWorkbench('viewer')
    expect(wrapper.find('[data-test="role-badge"]').text()).toBe('查看者')
    expect(wrapper.find('[data-test="role-badge"]').classes()).toContain('viewer')
  })

  it('退出登录:清 sca_auth 并回 /login', async () => {
    const { wrapper, router } = await mountWorkbench('admin')
    await wrapper.find('[data-test="logout"]').trigger('click')
    await flushPromises()
    expect(readAuth()).toBeNull()
    expect(router.currentRoute.value.path).toBe('/login')
  })

  it('页面含四大区块:库存/销量/补货/对话', async () => {
    const { wrapper } = await mountWorkbench()
    const text = wrapper.text()
    expect(text).toContain('库存查询')
    expect(text).toContain('销量查询')
    expect(text).toContain('补货建议')
    expect(text).toContain('Agent 对话')
  })

  it('admin 显示审计时间线区块并自动请求默认页', async () => {
    const { wrapper } = await mountWorkbench('admin')
    await flushPromises()
    expect(wrapper.text()).toContain('审计时间线')
    expect(wrapper.find('[data-test="audit-task-key"]').exists()).toBe(true)
    expect(auditHistory).toHaveBeenCalledWith({ page: 1, size: 20 })
  })

  it('viewer 不渲染审计区块,也不发审计请求', async () => {
    const { wrapper } = await mountWorkbench('viewer')
    await flushPromises()
    expect(wrapper.text()).not.toContain('审计时间线')
    expect(wrapper.find('[data-test="audit-query"]').exists()).toBe(false)
    expect(auditHistory).not.toHaveBeenCalled()
  })

  it('admin 显示待确认采购单区块并自动请求默认页', async () => {
    const { wrapper } = await mountWorkbench('admin')
    await flushPromises()
    expect(wrapper.text()).toContain('待确认采购单')
    expect(wrapper.find('[data-test="po-refresh"]').exists()).toBe(true)
    expect(poPending).toHaveBeenCalledWith({ page: 1, size: 20 })
  })

  it('viewer 不渲染待确认采购单区块,也不发请求', async () => {
    const { wrapper } = await mountWorkbench('viewer')
    await flushPromises()
    expect(wrapper.text()).not.toContain('待确认采购单')
    expect(wrapper.find('[data-test="po-refresh"]').exists()).toBe(false)
    expect(poPending).not.toHaveBeenCalled()
  })
})


describe('工作台采购结果刷新', () => {
  beforeEach(() => { localStorage.clear(); vi.clearAllMocks() })
  it('创建草稿事件同时刷新待确认单和审计，不自动确认', async () => {
    const { wrapper } = await mountWorkbench('admin')
    await flushPromises()
    expect(poPending).toHaveBeenCalledTimes(1)
    expect(auditHistory).toHaveBeenCalledTimes(1)
    wrapper.findComponent({ name: 'ReplenishPanel' }).vm.$emit('created')
    await flushPromises()
    expect(poPending).toHaveBeenCalledTimes(2)
    expect(auditHistory).toHaveBeenCalledTimes(2)
  })
  it('独立确认事件刷新两处且在父层保留成功事实提示', async () => {
    const { wrapper } = await mountWorkbench('admin')
    await flushPromises()
    wrapper.findComponent({ name: 'PoPanel' }).vm.$emit('confirmed', '采购单 PO-1 已确认；库存未变更。')
    await flushPromises()
    expect(poPending).toHaveBeenCalledTimes(2)
    expect(auditHistory).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-test="purchase-status"]').text()).toContain('PO-1')
  })
})
