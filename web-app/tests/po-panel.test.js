import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import PoPanel from '../src/components/PoPanel.vue'
import { poPending, confirmPurchaseOrder } from '../src/api'

vi.mock('../src/api', () => ({ poPending: vi.fn(), confirmPurchaseOrder: vi.fn() }))

function okResponse({ items = [], total = 0, page = 1, size = 20 } = {}) {
  return { data: { data: { items, total, page, size } }, headers: {} }
}

function row(overrides = {}) {
  return {
    poNo: 'PO20260903001',
    skuCode: 'CHG-001',
    qty: 120,
    status: 'pending',
    manualConfirmRequired: true,
    createdAt: '2026-09-03 09:40:00',
    ...overrides,
  }
}

function el(wrapper, test) {
  return wrapper.find(`[data-test="${test}"]`)
}

function cellTexts(wrapper) {
  return wrapper.findAll('tbody td').map((td) => td.text())
}

describe('PoPanel 默认加载', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('挂载自动请求第 1 页 20 条;加载完成后刷新按钮可用;结果与固定提示渲染', async () => {
    poPending.mockResolvedValue(okResponse({ items: [row()], total: 1 }))
    const wrapper = mount(PoPanel)
    await flushPromises()
    expect(poPending).toHaveBeenCalledWith({ page: 1, size: 20 })
    expect(el(wrapper, 'po-refresh').text()).toBe('刷新')
    expect(wrapper.text()).toContain('PO20260903001')
    expect(wrapper.text()).toContain('第 1 页 · 共 1 条')
    expect(el(wrapper, 'po-notice').text()).toBe(
      '该列表只展示待人工确认（status=pending）的采购草稿；确认动作经后端 API 执行，本面板只读。',
    )
  })

  it('加载中:按钮禁用并显示查询中…,不渲染表格', async () => {
    poPending.mockReturnValue(new Promise(() => {}))
    const wrapper = mount(PoPanel)
    await nextTick()
    expect(el(wrapper, 'po-refresh').attributes('disabled')).toBeDefined()
    expect(el(wrapper, 'po-refresh').text()).toBe('查询中…')
    expect(wrapper.find('table').exists()).toBe(false)
  })

  it('刷新按钮重新请求第 1 页', async () => {
    poPending.mockResolvedValue(okResponse({ items: [row()], total: 1 }))
    const wrapper = mount(PoPanel)
    await flushPromises()
    await el(wrapper, 'po-refresh').trigger('click')
    await flushPromises()
    expect(poPending).toHaveBeenCalledTimes(2)
    expect(poPending).toHaveBeenLastCalledWith({ page: 1, size: 20 })
  })
})

describe('PoPanel 行渲染', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('列序与字段原样显示(单号/SKU/数量/状态/需人工确认/创建时间)', async () => {
    poPending.mockResolvedValue(okResponse({ items: [row()], total: 1 }))
    const wrapper = mount(PoPanel)
    await flushPromises()
    const cells = cellTexts(wrapper)
    expect(cells[0]).toBe('PO20260903001')
    expect(cells[1]).toBe('CHG-001')
    expect(cells[2]).toBe('120')
    expect(cells[3]).toBe('pending')
    expect(cells[4]).toBe('是')
    expect(cells[5]).toBe('2026-09-03 09:40:00')
  })

  it('布尔字段 true→是 false→否;null/undefined 显示 —', async () => {
    poPending.mockResolvedValue(
      okResponse({
        items: [
          row({ poNo: 'PO-A', manualConfirmRequired: false }),
          row({ poNo: 'PO-B', manualConfirmRequired: null, createdAt: null, qty: null }),
        ],
        total: 2,
      }),
    )
    const wrapper = mount(PoPanel)
    await flushPromises()
    const rows = wrapper.findAll('tbody tr')
    expect(rows[0].findAll('td')[4].text()).toBe('否')
    const cellsB = rows[1].findAll('td').map((td) => td.text())
    expect(cellsB[4]).toBe('—')
    expect(cellsB[5]).toBe('—')
    expect(cellsB[2]).toBe('—')
  })

  it('<script> 等内容以纯文本渲染:无 script 节点、HTML 已转义、不执行', async () => {
    poPending.mockResolvedValue(
      okResponse({ items: [row({ poNo: '<script>window.__poXss = 1</script>' })], total: 1 }),
    )
    const wrapper = mount(PoPanel)
    await flushPromises()
    expect(wrapper.find('script').exists()).toBe(false)
    expect(window.__poXss).toBeUndefined()
    expect(cellTexts(wrapper)[0]).toBe('<script>window.__poXss = 1</script>')
    expect(wrapper.html()).toContain('&lt;script&gt;')
  })
})

describe('PoPanel 翻页', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('首页禁用上一页;下一页 payload 页码跟随;末页禁用下一页;回翻恢复', async () => {
    poPending
      .mockResolvedValueOnce(okResponse({ items: [row()], total: 40, page: 1 }))
      .mockResolvedValueOnce(okResponse({ items: [row({ poNo: 'PO-2' })], total: 40, page: 2 }))
      .mockResolvedValueOnce(okResponse({ items: [row()], total: 40, page: 1 }))

    const wrapper = mount(PoPanel)
    await flushPromises()
    expect(el(wrapper, 'po-prev').attributes('disabled')).toBeDefined()
    expect(el(wrapper, 'po-next').attributes('disabled')).toBeUndefined()

    await el(wrapper, 'po-next').trigger('click')
    await flushPromises()
    expect(poPending).toHaveBeenLastCalledWith({ page: 2, size: 20 })
    expect(wrapper.text()).toContain('第 2 页 · 共 40 条')
    expect(el(wrapper, 'po-prev').attributes('disabled')).toBeUndefined()
    // 40 条 / 每页 20:第 2 页即末页
    expect(el(wrapper, 'po-next').attributes('disabled')).toBeDefined()

    await el(wrapper, 'po-prev').trigger('click')
    await flushPromises()
    expect(poPending).toHaveBeenLastCalledWith({ page: 1, size: 20 })
    expect(el(wrapper, 'po-prev').attributes('disabled')).toBeDefined()
  })
})

describe('PoPanel 错误与空态', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('403:展示后端 message,不渲染结果区与翻页', async () => {
    poPending.mockRejectedValue({ response: { status: 403 }, message: '无权限执行该操作' })
    const wrapper = mount(PoPanel)
    await flushPromises()
    expect(el(wrapper, 'po-error').text()).toBe('无权限执行该操作')
    expect(wrapper.find('table').exists()).toBe(false)
    expect(el(wrapper, 'po-page-info').exists()).toBe(false)
    expect(el(wrapper, 'po-next').exists()).toBe(false)
  })

  it('400:展示后端 message', async () => {
    poPending.mockRejectedValue({ response: { status: 400 }, message: 'size 必须在 1~100 之间' })
    const wrapper = mount(PoPanel)
    await flushPromises()
    expect(el(wrapper, 'po-error').text()).toBe('size 必须在 1~100 之间')
  })

  it('网络错误:无响应提示无法连接;500 有响应展示后端 message(空则兜底)', async () => {
    poPending.mockRejectedValueOnce(new Error('Network Error'))
    const wrapper = mount(PoPanel)
    await flushPromises()
    expect(el(wrapper, 'po-error').text()).toBe('无法连接服务，请稍后重试')

    poPending.mockRejectedValueOnce({ response: { status: 500 }, message: '' })
    const wrapper2 = mount(PoPanel)
    await flushPromises()
    expect(el(wrapper2, 'po-error').text()).toBe('查询失败')
  })

  it('空结果:空态文案与共 0 条,翻页按钮全禁用', async () => {
    poPending.mockResolvedValue(okResponse({ items: [], total: 0 }))
    const wrapper = mount(PoPanel)
    await flushPromises()
    expect(wrapper.text()).toContain('无待确认采购单')
    expect(wrapper.text()).toContain('第 1 页 · 共 0 条')
    expect(el(wrapper, 'po-prev').attributes('disabled')).toBeDefined()
    expect(el(wrapper, 'po-next').attributes('disabled')).toBeDefined()
  })
})


describe('采购确认独立复核', () => {
  beforeEach(() => { localStorage.clear(); vi.clearAllMocks() })
  it('先展示单号SKU数量，点击独立确认才写，成功刷新并通知审计', async () => {
    poPending.mockResolvedValue(okResponse({ items: [row()], total: 1 }))
    confirmPurchaseOrder.mockResolvedValue({ data: { data: { status: 'confirmed' } } })
    const wrapper = mount(PoPanel, { props: { canConfirm: true } })
    await flushPromises()
    await el(wrapper, 'po-review-PO20260903001').trigger('click')
    expect(el(wrapper, 'po-confirm-summary').text()).toContain('CHG-001 · 120 件')
    expect(confirmPurchaseOrder).not.toHaveBeenCalled()
    await el(wrapper, 'po-confirm-submit').trigger('click')
    await flushPromises()
    expect(confirmPurchaseOrder).toHaveBeenCalledWith('PO20260903001')
    expect(poPending).toHaveBeenCalledTimes(2)
    expect(wrapper.emitted('confirmed')).toHaveLength(1)
    expect(el(wrapper, 'po-confirm-message').text()).toContain('库存未变更')
  })
  it('确认网络失败保留同张单复核，不宣称成功', async () => {
    poPending.mockResolvedValue(okResponse({ items: [row()], total: 1 }))
    confirmPurchaseOrder.mockRejectedValue(new Error('Network Error'))
    const wrapper = mount(PoPanel, { props: { canConfirm: true } })
    await flushPromises()
    await el(wrapper, 'po-review-PO20260903001').trigger('click')
    await el(wrapper, 'po-confirm-submit').trigger('click')
    await flushPromises()
    expect(el(wrapper, 'po-confirm-error').text()).toContain('确认结果未知')
    expect(el(wrapper, 'po-confirm-summary').exists()).toBe(true)
    expect(wrapper.emitted('confirmed')).toBeUndefined()
  })
  it('默认只读实例不显示确认操作', async () => {
    poPending.mockResolvedValue(okResponse({ items: [row()], total: 1 }))
    const wrapper = mount(PoPanel)
    await flushPromises()
    expect(el(wrapper, 'po-review-PO20260903001').exists()).toBe(false)
  })
})
