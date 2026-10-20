import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import StockQueryPanel from '../src/components/StockQueryPanel.vue'
import { queryStockLevel } from '../src/api'

vi.mock('../src/api', () => ({ queryStockLevel: vi.fn() }))

function okResponse(items, missingSkus = [], headers = {}) {
  return { data: { data: { items, missingSkus } }, headers }
}

function row(sku) {
  return { sku, productName: '产品' + sku, category: 'audio', availableQty: 10, stockLevel: 'NORMAL', snapDate: '2026-09-01' }
}

async function submit(wrapper) {
  await wrapper.find('form').trigger('submit')
  await flushPromises()
}

async function switchMode(wrapper, label) {
  const button = wrapper.findAll('.mode button').find((b) => b.text() === label)
  await button.trigger('click')
}

describe('StockQueryPanel 校验', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('空 SKU 输入提示,不调用接口', async () => {
    const wrapper = mount(StockQueryPanel)
    await submit(wrapper)
    expect(wrapper.find('[data-test="error"]').text()).toBe('请输入至少一个 SKU（用逗号或换行分隔）')
    expect(queryStockLevel).not.toHaveBeenCalled()
  })

  it('超过 20 个 SKU 被拦截', async () => {
    const wrapper = mount(StockQueryPanel)
    await wrapper.find('[data-test="skus"]').setValue(Array.from({ length: 21 }, (_, i) => 'S-' + i).join('\n'))
    await submit(wrapper)
    expect(wrapper.find('[data-test="error"]').text()).toBe('skus 数量不能超过 20')
    expect(queryStockLevel).not.toHaveBeenCalled()
  })

  it('category 模式空类目提示', async () => {
    const wrapper = mount(StockQueryPanel)
    await switchMode(wrapper, '按类目查询')
    await submit(wrapper)
    expect(wrapper.find('[data-test="error"]').text()).toBe('请输入类目')
    expect(queryStockLevel).not.toHaveBeenCalled()
  })
})

describe('StockQueryPanel 请求体与结果', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('skus 模式:body 为 {skus:[...]},表格与 missingSkus 展示', async () => {
    queryStockLevel.mockResolvedValue(okResponse([row('AUD-001')], ['X-999']))
    const wrapper = mount(StockQueryPanel)
    await wrapper.find('[data-test="skus"]').setValue('AUD-001, AUD-002')
    await submit(wrapper)
    expect(queryStockLevel).toHaveBeenCalledWith({ skus: ['AUD-001', 'AUD-002'] })
    expect(wrapper.text()).toContain('AUD-001')
    const missing = wrapper.find('[data-test="missing-skus"]')
    expect(missing.exists()).toBe(true)
    expect(missing.text()).toBe('以下 SKU 未找到：X-999')
  })

  it('category 模式:body 为 {category}', async () => {
    queryStockLevel.mockResolvedValue(okResponse([row('AUD-001')]))
    const wrapper = mount(StockQueryPanel)
    await switchMode(wrapper, '按类目查询')
    await wrapper.find('[data-test="category"]').setValue('audio')
    await submit(wrapper)
    expect(queryStockLevel).toHaveBeenCalledWith({ category: 'audio' })
  })

  it('空结果展示 emptyText,不渲染表', async () => {
    queryStockLevel.mockResolvedValue(okResponse([]))
    const wrapper = mount(StockQueryPanel)
    await wrapper.find('[data-test="skus"]').setValue('AUD-404')
    await submit(wrapper)
    expect(wrapper.text()).toContain('无匹配记录')
  })

  it('header 有 x-request-id 时展示 request_id', async () => {
    queryStockLevel.mockResolvedValue(okResponse([row('AUD-001')], [], { 'x-request-id': 'rid-s1' }))
    const wrapper = mount(StockQueryPanel)
    await wrapper.find('[data-test="skus"]').setValue('AUD-001')
    await submit(wrapper)
    expect(wrapper.find('[data-test="request-id"]').text()).toBe('request_id: rid-s1')
  })

  it('header 无 x-request-id 时不展示 request_id 区域', async () => {
    queryStockLevel.mockResolvedValue(okResponse([row('AUD-001')]))
    const wrapper = mount(StockQueryPanel)
    await wrapper.find('[data-test="skus"]').setValue('AUD-001')
    await submit(wrapper)
    expect(wrapper.find('[data-test="request-id"]').exists()).toBe(false)
  })
})
