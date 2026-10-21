import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import AgentTracePanel from '../src/components/AgentTracePanel.vue'
import { agentTrace, cancelAgentRun } from '../src/api'
vi.mock('../src/api', () => ({ agentTrace: vi.fn(), cancelAgentRun: vi.fn() }))
const wrappers=[]
afterEach(() => { wrappers.forEach(wrapper => wrapper.unmount()); wrappers.length=0; vi.clearAllMocks() })
describe('Agent trace panel', () => {
  it('shows actual layers, rounds, schema result and stop reason as text', async () => {
    agentTrace.mockResolvedValue({ data: { data: { request_id:'run-a',status:'ok',finished:true,rounds_used:2,max_rounds:5,duration_ms:12,stop_reason:'answered',context_layers:[{kind:'reference',messages:1,characters:30,description:'<reference-documents>',chunk_ids:['doc#section']}],events:[{seq:1,type:'tool_call',tool:'query-stock-level',schema_validation:'passed',duration_ms:5}] } } })
    const wrapper=mount(AgentTracePanel,{props:{requestId:'run-a',running:false}}); wrappers.push(wrapper); await flushPromises()
    expect(wrapper.text()).toContain('2/5'); expect(wrapper.text()).toContain('Schema passed'); expect(wrapper.text()).toContain('answered')
    expect(wrapper.find('reference-documents').exists()).toBe(false)
  })
  it('requests cancellation only for the current running trace', async () => {
    agentTrace.mockResolvedValue({data:{data:{request_id:'run-b',status:'running',finished:false,context_layers:[],events:[]}}})
    cancelAgentRun.mockResolvedValue({data:{}})
    const wrapper=mount(AgentTracePanel,{props:{requestId:'run-b',running:true}}); wrappers.push(wrapper); await flushPromises()
    await wrapper.find('button').trigger('click'); await flushPromises()
    expect(cancelAgentRun).toHaveBeenCalledWith('run-b'); expect(wrapper.emitted('cancelled')).toHaveLength(1)
  })
})
