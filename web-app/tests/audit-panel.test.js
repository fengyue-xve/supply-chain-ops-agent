import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import AuditPanel from '../src/components/AuditPanel.vue'
import { auditHistory } from '../src/api'

vi.mock('../src/api', () => ({ auditHistory: vi.fn() }))

function okResponse({ items = [], total = 0, page = 1, size = 20 } = {}) {
  return { data: { data: { items, total, page, size } }, headers: {} }
}

function row(overrides = {}) {
  return {
    id: 5,
    executedAt: '2026-09-02 11:21:00',
    actionType: 'po_create',
    who: 'agent',
    operator: null,
    taskKey: 'PO20260902001',
    beforeJson: null,
    afterJson: '{"poNo":"PO20260902001","status":"pending","qty":96}',
    statsJson: null,
    requestId: null,
    conversationId: null,
    toolCallId: null,
    ...overrides,
  }
}

function el(wrapper, test) {
  return wrapper.find(`[data-test="${test}"]`)
}

function cellTexts(wrapper) {
  return wrapper.findAll('tbody td').map((td) => td.text())
}

describe('AuditPanel 默认加载', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('挂载自动请求第 1 页 20 条;加载中按钮禁用;结果与固定提示渲染', async () => {
    auditHistory.mockResolvedValue(okResponse({ items: [row()], total: 1 }))
    const wrapper = mount(AuditPanel)
    await flushPromises()
    expect(auditHistory).toHaveBeenCalledWith({ page: 1, size: 20 })
    expect(el(wrapper, 'audit-query').text()).toBe('查询审计')
    expect(wrapper.text()).toContain('PO20260902001')
    expect(wrapper.text()).toContain('第 1 页 · 共 1 条')
    expect(el(wrapper, 'audit-notice').text()).toBe(
      '该时间线只记录已发生的状态迁移，不包含被拒请求、幂等回放等未产生写入的事件。',
    )
  })

  it('加载中:按钮禁用并显示查询中…,不渲染表格', async () => {
    auditHistory.mockReturnValue(new Promise(() => {}))
    const wrapper = mount(AuditPanel)
    await nextTick()
    expect(el(wrapper, 'audit-query').attributes('disabled')).toBeDefined()
    expect(el(wrapper, 'audit-query').text()).toBe('查询中…')
    expect(wrapper.find('table').exists()).toBe(false)
  })

  it('过滤条件 trim 后进请求体;空白/空串条件不传', async () => {
    auditHistory.mockResolvedValue(okResponse())
    const wrapper = mount(AuditPanel)
    await flushPromises()
    await el(wrapper, 'audit-task-key').setValue(' PO20260902001 ')
    await el(wrapper, 'audit-action-type').setValue(' po_create ')
    await el(wrapper, 'audit-operator').setValue('   ')
    await el(wrapper, 'audit-request-id').setValue('')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(auditHistory).toHaveBeenLastCalledWith({
      page: 1,
      size: 20,
      taskKey: 'PO20260902001',
      actionType: 'po_create',
    })
  })
})

describe('AuditPanel 行渲染', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('null 字段显示 —,非 null 字段原样显示', async () => {
    auditHistory.mockResolvedValue(okResponse({ items: [row()], total: 1 }))
    const wrapper = mount(AuditPanel)
    await flushPromises()
    const cells = cellTexts(wrapper)
    // 列序:时间/动作/执行者/操作者/业务键/变更前/变更后/统计/request_id/conversation_id/tool_call_id
    expect(cells[0]).toBe('2026-09-02 11:21:00')
    expect(cells[1]).toBe('po_create')
    expect(cells[2]).toBe('agent')
    expect(cells[3]).toBe('—')
    expect(cells[5]).toBe('—')
    expect(cells[6]).toBe('{"poNo":"PO20260902001","status":"pending","qty":96}')
    expect(cells[7]).toBe('—')
    expect(cells[8]).toBe('—')
    expect(cells[9]).toBe('—')
    expect(cells[10]).toBe('—')
  })

  it('<script> 等内容以纯文本渲染:无 script 节点、HTML 已转义、不执行', async () => {
    auditHistory.mockResolvedValue(
      okResponse({ items: [row({ afterJson: '<script>window.__auditXss = 1</script>' })], total: 1 }),
    )
    const wrapper = mount(AuditPanel)
    await flushPromises()
    expect(wrapper.find('script').exists()).toBe(false)
    expect(window.__auditXss).toBeUndefined()
    expect(cellTexts(wrapper)[6]).toBe('<script>window.__auditXss = 1</script>')
    expect(wrapper.html()).toContain('&lt;script&gt;')
  })
})

describe('AuditPanel 翻页', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('首页禁用上一页;下一页 payload 页码跟随;末页禁用下一页;回翻恢复', async () => {
    auditHistory
      .mockResolvedValueOnce(okResponse({ items: [row()], total: 40, page: 1 }))
      .mockResolvedValueOnce(okResponse({ items: [row({ id: 6 })], total: 40, page: 2 }))
      .mockResolvedValueOnce(okResponse({ items: [row()], total: 40, page: 1 }))

    const wrapper = mount(AuditPanel)
    await flushPromises()
    expect(el(wrapper, 'audit-prev').attributes('disabled')).toBeDefined()
    expect(el(wrapper, 'audit-next').attributes('disabled')).toBeUndefined()

    await el(wrapper, 'audit-next').trigger('click')
    await flushPromises()
    expect(auditHistory).toHaveBeenLastCalledWith({ page: 2, size: 20 })
    expect(wrapper.text()).toContain('第 2 页 · 共 40 条')
    expect(el(wrapper, 'audit-prev').attributes('disabled')).toBeUndefined()
    // 40 条 / 每页 20:第 2 页即末页
    expect(el(wrapper, 'audit-next').attributes('disabled')).toBeDefined()

    await el(wrapper, 'audit-prev').trigger('click')
    await flushPromises()
    expect(auditHistory).toHaveBeenLastCalledWith({ page: 1, size: 20 })
    expect(el(wrapper, 'audit-prev').attributes('disabled')).toBeDefined()
  })
})

describe('AuditPanel 错误与空态', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('403:展示后端 message,不渲染结果区与翻页', async () => {
    auditHistory.mockRejectedValue({ response: { status: 403 }, message: '无权限执行该操作' })
    const wrapper = mount(AuditPanel)
    await flushPromises()
    expect(el(wrapper, 'audit-error').text()).toBe('无权限执行该操作')
    expect(wrapper.find('table').exists()).toBe(false)
    expect(el(wrapper, 'audit-page-info').exists()).toBe(false)
    expect(el(wrapper, 'audit-next').exists()).toBe(false)
  })

  it('400:展示后端 message', async () => {
    auditHistory.mockRejectedValue({ response: { status: 400 }, message: 'size 必须在 1~100 之间' })
    const wrapper = mount(AuditPanel)
    await flushPromises()
    expect(el(wrapper, 'audit-error').text()).toBe('size 必须在 1~100 之间')
  })

  it('网络错误:无响应提示无法连接;500 有响应展示后端 message(空则兜底)', async () => {
    auditHistory.mockRejectedValueOnce(new Error('Network Error'))
    const wrapper = mount(AuditPanel)
    await flushPromises()
    expect(el(wrapper, 'audit-error').text()).toBe('无法连接服务，请稍后重试')

    auditHistory.mockRejectedValueOnce({ response: { status: 500 }, message: '' })
    const wrapper2 = mount(AuditPanel)
    await flushPromises()
    expect(el(wrapper2, 'audit-error').text()).toBe('查询失败')
  })

  it('空结果:空态文案与共 0 条,翻页按钮全禁用', async () => {
    auditHistory.mockResolvedValue(okResponse({ items: [], total: 0 }))
    const wrapper = mount(AuditPanel)
    await flushPromises()
    expect(wrapper.text()).toContain('无匹配记录')
    expect(wrapper.text()).toContain('第 1 页 · 共 0 条')
    expect(el(wrapper, 'audit-prev').attributes('disabled')).toBeDefined()
    expect(el(wrapper, 'audit-next').attributes('disabled')).toBeDefined()
  })
})
