import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import FormWorkflowPanel from '../src/components/FormWorkflowPanel.vue'
import { formRecord, formRun } from '../src/api'
vi.mock('../src/api', () => ({ formRecord: vi.fn(), formRun: vi.fn() }))
const pick = (w, id) => w.find('[data-test="' + id + '"]')
async function query(w) { await w.find('form').trigger('submit'); await flushPromises() }
describe('独立发货准备表单只读查询', () => {
  beforeEach(() => vi.clearAllMocks())
  it('默认不自动请求，记录查询展示版本及外箱唛，并说明采购边界', async () => {
    formRecord.mockResolvedValue({ data: { data: { record_id: 'R-1001', sku_code: 'AUD-001', business_version: 1, outer_box_mark: { value: 'DEMO', rule_source: 'fixture-rule' } } } })
    const w = mount(FormWorkflowPanel)
    expect(formRecord).not.toHaveBeenCalled()
    await query(w)
    expect(formRecord).toHaveBeenCalledWith('R-1001')
    expect(pick(w, 'form-mark').text()).toContain('DEMO')
    expect(w.text()).toContain('采购确认不会自动创建该表单')
    expect(w.text()).toContain('本面板仅查询')
  })
  it('查询run展示状态、审批与审计，并以文本转义执行计划', async () => {
    formRun.mockResolvedValue({ data: { data: { run_id: 'RUN-1', status: 'SUCCEEDED', write_count: 1, approval: { decision: 'APPROVED', actor_id: 'approver' }, plan: { value: '<script>unsafe()</script>' }, audit: [{ seq: 1, event_type: 'APPLIED', ts: '2026-10-20', actor_id: 'agent' }] } } })
    const w = mount(FormWorkflowPanel)
    await pick(w, 'form-kind').setValue('run')
    await pick(w, 'form-id').setValue('RUN-1')
    await query(w)
    expect(formRun).toHaveBeenCalledWith('RUN-1')
    expect(w.text()).toContain('SUCCEEDED')
    expect(pick(w, 'form-audit').text()).toContain('APPLIED')
    expect(w.find('script').exists()).toBe(false)
    expect(w.html()).toContain('&lt;script&gt;')
  })
  it('503明确未取得状态，不伪造空成功；无写操作', async () => {
    formRecord.mockRejectedValue({ response: { status: 503 } })
    const w = mount(FormWorkflowPanel)
    await query(w)
    expect(pick(w, 'form-error').text()).toContain('当前未取得状态')
    expect(pick(w, 'form-result').exists()).toBe(false)
    expect(w.findAll('button')).toHaveLength(1)
  })
  it('非法标识本地拒绝，后端仍为最终鉴权边界', async () => {
    const w = mount(FormWorkflowPanel)
    await pick(w, 'form-id').setValue('../secret')
    await query(w)
    expect(formRecord).not.toHaveBeenCalled()
    expect(pick(w, 'form-error').exists()).toBe(true)
  })
})
