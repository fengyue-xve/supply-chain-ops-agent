import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import SalesQueryPanel from '../src/components/SalesQueryPanel.vue'
import { querySales } from '../src/api'

vi.mock('../src/api', () => ({ querySales: vi.fn() }))

function okResponse(items, missingSkus = [], headers = {}) {
  return { data: { data: { items, missingSkus } }, headers }
}

async function submit(wrapper) {
  await wrapper.find('form').trigger('submit')
  await flushPromises()
}

async function switchMode(wrapper, label) {
  const button = wrapper.findAll('.mode button').find((b) => b.text() === label)
  await button.trigger('click')
}

async function pickDays(wrapper, value) {
  const radio = wrapper.find(`input[type="radio"][value="${value}"]`)
  radio.element.checked = true
  await radio.trigger('change')
}

describe('SalesQueryPanel 校验', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('空 SKU 提示,不调用接口', async () => {
    const wrapper = mount(SalesQueryPanel)
    await submit(wrapper)
    expect(wrapper.find('[data-test="error"]').text()).toBe('请输入一个 SKU')
    expect(querySales).not.toHaveBeenCalled()
  })

  it('多 SKU(逗号/空格)被拒绝:query-sales 只收单数 sku', async () => {
    // 注:单行 input 在浏览器/jsdom 中会把粘贴的换行折叠,故不测换行;折叠合并后的
    // 非法 SKU 由后端 400 兜底(前端基础校验,后端最终校验)。
    for (const bad of ['AUD-001, AUD-002', 'AUD-001 AUD-002', 'AUD-001，AUD-002']) {
      const wrapper = mount(SalesQueryPanel)
      await wrapper.find('[data-test="sku"]').setValue(bad)
      await submit(wrapper)
      expect(wrapper.find('[data-test="error"]').text()).toBe('销量查询只支持单个 SKU，请一次输入一个')
      expect(querySales).not.toHaveBeenCalled()
    }
  })

  it('category 模式空类目提示', async () => {
    const wrapper = mount(SalesQueryPanel)
    await switchMode(wrapper, '按类目查询')
    await submit(wrapper)
    expect(wrapper.find('[data-test="error"]').text()).toBe('请输入类目')
    expect(querySales).not.toHaveBeenCalled()
  })
})

describe('SalesQueryPanel 请求体与结果', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('sku 模式默认 7 天:body {sku, days:7}', async () => {
    querySales.mockResolvedValue(okResponse([]))
    const wrapper = mount(SalesQueryPanel)
    await wrapper.find('[data-test="sku"]').setValue('AUD-001')
    await submit(wrapper)
    expect(querySales).toHaveBeenCalledWith({ sku: 'AUD-001', days: 7 })
  })

  it('切换 30 天后 body {sku, days:30}', async () => {
    querySales.mockResolvedValue(okResponse([]))
    const wrapper = mount(SalesQueryPanel)
    await pickDays(wrapper, 30)
    await wrapper.find('[data-test="sku"]').setValue('AUD-001')
    await submit(wrapper)
    const payload = querySales.mock.calls[0][0]
    expect(Number(payload.days)).toBe(30)
    expect(payload.sku).toBe('AUD-001')
  })

  it('category 模式:body {category, days}', async () => {
    querySales.mockResolvedValue(okResponse([]))
    const wrapper = mount(SalesQueryPanel)
    await switchMode(wrapper, '按类目查询')
    await wrapper.find('[data-test="category"]').setValue('audio')
    await submit(wrapper)
    expect(querySales).toHaveBeenCalledWith({ category: 'audio', days: 7 })
  })

  it('结果含 series 明细(逐日),无记录时显示空态', async () => {
    const items = [
      {
        sku: 'AUD-001',
        productName: '无线耳机',
        category: 'audio',
        days: 7,
        totalQty: 14,
        avgDailyQty: 2,
        series: [
          { date: '2026-09-01', qty: 2 },
          { date: '2026-09-02', qty: 3 },
        ],
      },
    ]
    querySales.mockResolvedValue(okResponse(items))
    const wrapper = mount(SalesQueryPanel)
    await wrapper.find('[data-test="sku"]').setValue('AUD-001')
    await submit(wrapper)
    expect(wrapper.text()).toContain('14')
    const detail = wrapper.find('[data-test="series-AUD-001"]')
    expect(detail.exists()).toBe(true)
    expect(detail.find('summary').text()).toBe('2 天')
    expect(detail.text()).toContain('2026-09-01: 2')
  })

  it('header 无 x-request-id 时不展示 request_id', async () => {
    querySales.mockResolvedValue(okResponse([{ sku: 'AUD-001', series: [] }]))
    const wrapper = mount(SalesQueryPanel)
    await wrapper.find('[data-test="sku"]').setValue('AUD-001')
    await submit(wrapper)
    expect(wrapper.find('[data-test="request-id"]').exists()).toBe(false)
  })
})


it('销量显示数据中的业务区间，避免把历史样本当作实时数据', async () => {
  querySales.mockResolvedValue(okResponse([{
    sku: 'AUD-001', days: 7, totalQty: 7, avgDailyQty: 1,
    series: [{ date: '2026-08-28', qty: 1 }, { date: '2026-09-03', qty: 1 }],
  }]))
  const wrapper = mount(SalesQueryPanel)
  await wrapper.find('[data-test="sku"]').setValue('AUD-001')
  await submit(wrapper)
  expect(wrapper.text()).toContain('2026-08-28 至 2026-09-03')
  expect(wrapper.find('[data-test="sales-date-note"]').text()).toContain('不表示截至今天的实时销量')
})
