import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ReplenishPanel from '../src/components/ReplenishPanel.vue'
import { suggestReplenish } from '../src/api'

vi.mock('../src/api', () => ({ suggestReplenish: vi.fn() }))

function okResponse(items, missingSkus = [], headers = {}) {
  return { data: { data: { items, missingSkus } }, headers }
}

function row() {
  return {
    sku: 'AUD-001',
    productName: '无线耳机',
    currentQty: 5,
    avgDailyQty: 1,
    targetCoverDays: 14,
    safetyQty: 20,
    suggestedQty: 26,
    urgency: 'urgent',
    reason: '当前库存低于安全库存',
  }
}

async function submit(wrapper) {
  await wrapper.find('form').trigger('submit')
  await flushPromises()
}

async function switchMode(wrapper, label) {
  const button = wrapper.findAll('.mode button').find((b) => b.text() === label)
  await button.trigger('click')
}

describe('ReplenishPanel 校验', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('空 SKU 输入提示,不调用接口', async () => {
    const wrapper = mount(ReplenishPanel)
    await submit(wrapper)
    expect(wrapper.find('[data-test="error"]').text()).toBe('请输入至少一个 SKU（用逗号或换行分隔）')
    expect(suggestReplenish).not.toHaveBeenCalled()
  })

  it('category 模式空类目提示', async () => {
    const wrapper = mount(ReplenishPanel)
    await switchMode(wrapper, '按类目计算')
    await submit(wrapper)
    expect(wrapper.find('[data-test="error"]').text()).toBe('请输入类目')
    expect(suggestReplenish).not.toHaveBeenCalled()
  })
})

describe('ReplenishPanel 请求体与结果', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('skus 模式默认 days=7,targetCoverDays=14', async () => {
    suggestReplenish.mockResolvedValue(okResponse([row()]))
    const wrapper = mount(ReplenishPanel)
    await wrapper.find('[data-test="skus"]').setValue('AUD-001, AUD-002')
    await submit(wrapper)
    const payload = suggestReplenish.mock.calls[0][0]
    expect(payload.skus).toEqual(['AUD-001', 'AUD-002'])
    expect(Number(payload.days)).toBe(7)
    expect(Number(payload.targetCoverDays)).toBe(14)
  })

  it('可切换 days=30 与 targetCoverDays=30', async () => {
    suggestReplenish.mockResolvedValue(okResponse([]))
    const wrapper = mount(ReplenishPanel)
    await wrapper.find('[data-test="days"]').setValue('30')
    await wrapper.find('[data-test="cover-days"]').setValue('30')
    await wrapper.find('[data-test="skus"]').setValue('AUD-001')
    await submit(wrapper)
    expect(suggestReplenish).toHaveBeenCalledTimes(1)
    const payload = suggestReplenish.mock.calls[0][0]
    expect(Number(payload.days)).toBe(30)
    expect(Number(payload.targetCoverDays)).toBe(30)
  })

  it('category 模式:body 含 {category, days, targetCoverDays}', async () => {
    suggestReplenish.mockResolvedValue(okResponse([]))
    const wrapper = mount(ReplenishPanel)
    await switchMode(wrapper, '按类目计算')
    await wrapper.find('[data-test="category"]').setValue('audio')
    await submit(wrapper)
    expect(suggestReplenish).toHaveBeenCalledTimes(1)
    const payload = suggestReplenish.mock.calls[0][0]
    expect(payload).toEqual({ category: 'audio', days: 7, targetCoverDays: 14 })
  })

  it('展示 urgency 徽标、reason 与 request_id(header 存在时)', async () => {
    suggestReplenish.mockResolvedValue(okResponse([row()], [], { 'x-request-id': 'rid-r1' }))
    const wrapper = mount(ReplenishPanel)
    await wrapper.find('[data-test="skus"]').setValue('AUD-001')
    await submit(wrapper)
    expect(wrapper.find('.urgency.urgent').exists()).toBe(true)
    expect(wrapper.text()).toContain('urgent')
    expect(wrapper.text()).toContain('低于安全库存')
    expect(wrapper.find('[data-test="request-id"]').text()).toBe('request_id: rid-r1')
  })

  it('空结果展示空态', async () => {
    suggestReplenish.mockResolvedValue(okResponse([]))
    const wrapper = mount(ReplenishPanel)
    await wrapper.find('[data-test="skus"]').setValue('AUD-404')
    await submit(wrapper)
    expect(wrapper.text()).toContain('无匹配记录')
  })
})


describe('补货业务依据与不确定性', () => {
  beforeEach(() => vi.clearAllMocks())

  it('展示后端返回的商品参数、业务日期与整箱结果，保持只读请求', async () => {
    suggestReplenish.mockResolvedValue(okResponse([{
      ...row(), currentQty: 5, avgDailyQty: 2,
      leadDays: 4, reviewDays: 10, protectionDays: 14,
      pkgQty: 12, targetQty: 48, rawGap: 43, suggestedQty: 48,
      stockSnapDate: '2026-09-03', salesWindowStart: '2026-08-28',
      salesWindowEnd: '2026-09-03', stockScope: 'on_hand_only', assessmentStatus: 'ready',
    }]))
    const wrapper = mount(ReplenishPanel)
    await wrapper.find('[data-test="skus"]').setValue('AUD-001')
    await submit(wrapper)
    const basis = wrapper.find('[data-test="basis-AUD-001"]')
    expect(basis.text()).toContain('库存日期：2026-09-03')
    expect(basis.text()).toContain('2026-08-28 至 2026-09-03')
    expect(basis.text()).toContain('提前期 4 天 + 覆盖差值 10 天 = 保护期 14 天')
    expect(basis.text()).toContain('原始缺口 43；箱规 12；整箱建议 48')
    expect(wrapper.text()).toContain('参考建议')
    expect(wrapper.find('[data-test="replenish-scope"]').text()).toContain('不含预留和在途')
    expect(suggestReplenish.mock.calls[0][0]).toEqual({ skus: ['AUD-001'], days: 7, targetCoverDays: 14 })
  })

  it('日期不一致的结果显示人工复核状态和各自日期', async () => {
    suggestReplenish.mockResolvedValue(okResponse([{
      ...row(), assessmentStatus: 'review_required', stockSnapDate: '2026-09-01',
      salesWindowStart: '2026-08-28', salesWindowEnd: '2026-09-03',
    }]))
    const wrapper = mount(ReplenishPanel)
    await wrapper.find('[data-test="skus"]').setValue('AUD-001')
    await submit(wrapper)
    expect(wrapper.find('[data-test="replenish-assessment"]').text()).toContain('1 项需人工复核')
    expect(wrapper.find('.assessment.review_required').text()).toBe('需人工复核')
    expect(wrapper.find('[data-test="basis-AUD-001"]').text()).toContain('库存日期：2026-09-01')
  })

  it('缺失销量时将未知日均与建议量显示为破折号，而非零采购量', async () => {
    suggestReplenish.mockResolvedValue(okResponse([{
      ...row(), avgDailyQty: null, targetQty: null, rawGap: null, suggestedQty: null,
      assessmentStatus: 'insufficient_data', urgency: 'unknown',
    }]))
    const wrapper = mount(ReplenishPanel)
    await wrapper.find('[data-test="skus"]').setValue('AUD-001')
    await submit(wrapper)
    const cells = wrapper.findAll('tbody tr')[0].findAll('td')
    expect(cells[3].text()).toBe('—')
    expect(cells[8].text()).toBe('—')
    expect(wrapper.find('[data-test="replenish-assessment"]').text()).toContain('1 项数据不足，不给出采购数量')
    expect(wrapper.find('.assessment.insufficient_data').text()).toBe('数据不足')
    expect(wrapper.find('[data-test="basis-AUD-001"]').text()).toContain('整箱建议 —')
  })
})
