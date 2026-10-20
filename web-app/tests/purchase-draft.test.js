import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import PurchaseDraftPanel from '../src/components/PurchaseDraftPanel.vue'
import ReplenishPanel from '../src/components/ReplenishPanel.vue'
import { saveAuth } from '../src/utils/auth'
import { createPurchaseOrder, suggestReplenish } from '../src/api'

vi.mock('../src/api', () => ({ createPurchaseOrder: vi.fn(), suggestReplenish: vi.fn() }))
const pick = (w, id) => w.find('[data-test="' + id + '"]')
const suggestion = { sku: 'AUD-003', suggestedQty: 72, pkgQty: 12, assessmentStatus: 'ready' }
const success = { data: { data: { items: [{ poNo: 'PO-1', sku: 'AUD-003', qty: 72, status: 'pending' }], total: 1 } } }
async function draft() {
  const w = mount(PurchaseDraftPanel)
  await w.setProps({ suggestion: { ...suggestion } })
  return w
}
describe('人工采购意向与幂等恢复', () => {
  beforeEach(() => {
    localStorage.clear()
    saveAuth({ token: 't', username: 'admin', role: 'admin' })
    vi.clearAllMocks()
  })
  it('带入和内联复核都不写入；仅明确创建生成pending且不自动确认', async () => {
    createPurchaseOrder.mockResolvedValue(success)
    const w = await draft()
    expect(createPurchaseOrder).not.toHaveBeenCalled()
    await pick(w, 'draft-review').trigger('click')
    expect(pick(w, 'draft-review-summary').text()).toContain('AUD-003：72 件')
    expect(createPurchaseOrder).not.toHaveBeenCalled()
    await pick(w, 'draft-submit').trigger('click')
    await flushPromises()
    expect(createPurchaseOrder).toHaveBeenCalledWith({ items: [{ sku: 'AUD-003', qty: 72 }] }, expect.any(String))
    expect(pick(w, 'draft-result').text()).toContain('PO-1')
    expect(pick(w, 'draft-result').text()).toContain('独立确认')
    expect(w.emitted('created')).toHaveLength(1)
  })
  it.each([0, -12, 1.5, 73])('拒绝非法数量或非整箱数量 %s', async qty => {
    const w = await draft()
    await pick(w, 'draft-qty-AUD-003').setValue(qty)
    await pick(w, 'draft-review').trigger('click')
    expect(pick(w, 'draft-error').exists()).toBe(true)
    expect(pick(w, 'draft-submit').exists()).toBe(false)
    expect(createPurchaseOrder).not.toHaveBeenCalled()
  })
  it('viewer不显示写面板，补货结果也没有带入操作', async () => {
    saveAuth({ token: 't', username: 'viewer', role: 'viewer' })
    const w = await draft()
    expect(pick(w, 'purchase-draft').exists()).toBe(false)
    suggestReplenish.mockResolvedValue({ data: { data: { items: [suggestion] } }, headers: {} })
    const r = mount(ReplenishPanel)
    await pick(r, 'skus').setValue('AUD-003')
    await r.find('form').trigger('submit')
    await flushPromises()
    expect(pick(r, 'draft-add-AUD-003').exists()).toBe(false)
  })
  it('需要复核或数据不足的建议不能直接带入写操作', async () => {
    suggestReplenish.mockResolvedValue({ data: { data: { items: [
      { ...suggestion, assessmentStatus: 'review_required' },
      { ...suggestion, sku: 'AUD-004', suggestedQty: null, assessmentStatus: 'insufficient_data' },
    ] } }, headers: {} })
    const w = mount(ReplenishPanel)
    await pick(w, 'skus').setValue('AUD-003,AUD-004')
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(pick(w, 'draft-add-AUD-003').attributes('disabled')).toBeDefined()
    expect(pick(w, 'draft-add-AUD-004').attributes('disabled')).toBeDefined()
  })
  it('网络结果未知后冻结编辑，刷新仍恢复原内容并沿用同key重试', async () => {
    createPurchaseOrder.mockRejectedValueOnce(new Error('Network Error')).mockResolvedValueOnce(success)
    const w = await draft()
    await pick(w, 'draft-review').trigger('click')
    await pick(w, 'draft-submit').trigger('click')
    await flushPromises()
    const first = createPurchaseOrder.mock.calls[0]
    expect(pick(w, 'draft-unknown').exists()).toBe(true)
    expect(pick(w, 'draft-qty-AUD-003').attributes('disabled')).toBeDefined()
    await w.setProps({ suggestion: { ...suggestion, sku: 'AUD-004' } })
    expect(pick(w, 'draft-qty-AUD-004').exists()).toBe(false)
    w.unmount()
    const recovered = mount(PurchaseDraftPanel)
    expect(pick(recovered, 'draft-unknown').exists()).toBe(true)
    await pick(recovered, 'draft-submit').trigger('click')
    await flushPromises()
    expect(createPurchaseOrder.mock.calls[1]).toEqual(first)
    expect(pick(recovered, 'draft-result').text()).toContain('PO-1')
  })
  it('同payload再次提交保持key；改数量后生成新key并展示已有意向冲突', async () => {
    const conflict = { response: { status: 409, data: { data: { existingOrders: [{ poNo: 'PO-OLD', sku: 'AUD-003', qty: 96, status: 'pending' }] } } }, message: '已有待确认采购意向' }
    createPurchaseOrder.mockRejectedValue(conflict)
    const w = await draft()
    await pick(w, 'draft-review').trigger('click')
    await pick(w, 'draft-submit').trigger('click')
    await flushPromises()
    const key1 = createPurchaseOrder.mock.calls[0][1]
    expect(pick(w, 'draft-existing').text()).toContain('PO-OLD')
    await pick(w, 'draft-review').trigger('click')
    await pick(w, 'draft-submit').trigger('click')
    await flushPromises()
    expect(createPurchaseOrder.mock.calls[1][1]).toBe(key1)
    await pick(w, 'draft-qty-AUD-003').setValue(84)
    await pick(w, 'draft-review').trigger('click')
    await pick(w, 'draft-submit').trigger('click')
    await flushPromises()
    expect(createPurchaseOrder.mock.calls[2][1]).not.toBe(key1)
    expect(createPurchaseOrder.mock.calls[2][0].items[0].qty).toBe(84)
  })
  it('500也按结果未知处理，不解锁修改', async () => {
    createPurchaseOrder.mockRejectedValue({ response: { status: 500 }, message: 'server error' })
    const w = await draft()
    await pick(w, 'draft-review').trigger('click')
    await pick(w, 'draft-submit').trigger('click')
    await flushPromises()
    expect(pick(w, 'draft-unknown').exists()).toBe(true)
    expect(pick(w, 'draft-qty-AUD-003').attributes('disabled')).toBeDefined()
  })
})


describe('采购请求记录存储边界', () => {
  it('本地存储不可写时不发送采购创建请求', async () => {
    localStorage.clear()
    saveAuth({ token: 't', username: 'admin', role: 'admin' })
    createPurchaseOrder.mockClear()
    const w = await draft()
    const failedStore = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
    try {
      await pick(w, 'draft-review').trigger('click')
      await pick(w, 'draft-submit').trigger('click')
      await flushPromises()
      expect(createPurchaseOrder).not.toHaveBeenCalled()
      expect(pick(w, 'draft-error').text()).toContain('本次未发送')
    } finally { failedStore.mockRestore() }
  })
})


describe('明确的新采购意向', () => {
  beforeEach(() => {
    localStorage.clear()
    saveAuth({ token: 't', username: 'admin', role: 'admin' })
    vi.clearAllMocks()
  })
  it('相同内容普通重发沿用key，明确新意向才使用新key', async () => {
    createPurchaseOrder.mockResolvedValue(success)
    const w = await draft()
    await pick(w, 'draft-review').trigger('click')
    await pick(w, 'draft-submit').trigger('click')
    await flushPromises()
    const firstKey = createPurchaseOrder.mock.calls[0][1]
    await pick(w, 'draft-review').trigger('click')
    await pick(w, 'draft-submit').trigger('click')
    await flushPromises()
    expect(createPurchaseOrder.mock.calls[1][1]).toBe(firstKey)
    await pick(w, 'draft-new-intent').trigger('click')
    expect(pick(w, 'draft-qty-AUD-003').exists()).toBe(false)
    await w.setProps({ suggestion: { ...suggestion } })
    await pick(w, 'draft-review').trigger('click')
    await pick(w, 'draft-submit').trigger('click')
    await flushPromises()
    expect(createPurchaseOrder.mock.calls[2][1]).not.toBe(firstKey)
  })
  it('未知结果不能清除原请求或开始新意向', async () => {
    createPurchaseOrder.mockRejectedValue(new Error('network'))
    const w = await draft()
    await pick(w, 'draft-review').trigger('click')
    await pick(w, 'draft-submit').trigger('click')
    await flushPromises()
    expect(pick(w, 'draft-new-intent').exists()).toBe(false)
    expect(JSON.parse(localStorage.getItem('sca_purchase_attempt:admin')).state).toBe('unknown')
  })
})
