import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ChatPanel from '../src/components/ChatPanel.vue'
import { chat, chatHistory } from '../src/api'
import { saveAuth } from '../src/utils/auth'

vi.mock('../src/api', () => ({ chat: vi.fn(), chatHistory: vi.fn() }))

function chatResponse(overrides = {}) {
  return {
    data: {
      data: {
        request_id: 'req-x',
        conversation_id: 'conv-9',
        reply: 'AUD-001 当前可售 120 件',
        tool_calls: [],
        ...overrides,
      },
    },
    headers: {},
  }
}

async function send(wrapper, text) {
  await wrapper.find('[data-test="chat-input"]').setValue(text)
  await wrapper.find('form').trigger('submit')
  await flushPromises()
}

describe('ChatPanel 会话与调用', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    vi.clearAllMocks()
  })

  it('首轮不带 conversation_id,收到回填后次轮复用', async () => {
    chat.mockResolvedValueOnce(chatResponse({ reply: '第一轮' }))
    chat.mockResolvedValueOnce(chatResponse({ reply: '第二轮' }))
    const wrapper = mount(ChatPanel)
    await send(wrapper, 'AUD-001 库存多少？')
    expect(chat).toHaveBeenCalledTimes(1)
    expect(chat.mock.calls[0][0].conversation_id).toBeUndefined()
    expect(chat.mock.calls[0][0].message).toBe('AUD-001 库存多少？')
    expect(wrapper.text()).toContain('第一轮')

    await send(wrapper, '那补货呢？')
    expect(chat.mock.calls[1][0].conversation_id).toBe('conv-9')
    expect(wrapper.text()).toContain('第二轮')
  })

  it('tool_calls ok:false 显示工具名/失败与错误详情', async () => {
    chat.mockResolvedValue(
      chatResponse({
        reply: '查询失败，无法完成',
        tool_calls: [
          {
            tool_call_id: 't-1',
            tool: 'query-stock-level',
            params: { skus: ['AUD-001'] },
            ok: false,
            round: 1,
            summary: '',
            error: '403 无权限执行该操作',
          },
        ],
      }),
    )
    const wrapper = mount(ChatPanel)
    await send(wrapper, '查库存')
    const call = wrapper.find('[data-test="tool-call"]')
    expect(call.exists()).toBe(true)
    expect(call.text()).toContain('query-stock-level')
    expect(call.text()).toContain('失败')
    expect(call.find('[data-test="tool-error"]').text()).toBe('403 无权限执行该操作')
    expect(wrapper.text()).toContain('查询失败，无法完成')
  })

  it('tool_calls 全成功时标记成功,可折叠查看详情', async () => {
    chat.mockResolvedValue(
      chatResponse({
        reply: '建议补货 26 件',
        tool_calls: [{ tool_call_id: 't-1', tool: 'suggest-replenish', params: {}, ok: true, round: 1, summary: 'AUD-001: 26', error: '' }],
      }),
    )
    const wrapper = mount(ChatPanel)
    await send(wrapper, '补货建议')
    expect(wrapper.find('[data-test="tool-call"]').text()).toContain('成功')
    expect(wrapper.text()).toContain('建议补货 26 件')
  })

  it('request_id 展示(header 优先)', async () => {
    chat.mockResolvedValue(chatResponse())
    const wrapper = mount(ChatPanel)
    await send(wrapper, 'hi')
    expect(wrapper.find('[data-test="chat-request-id"]').text()).toBe('request_id: req-x')
  })

  it('后端错误消息展示为失败气泡', async () => {
    chat.mockRejectedValue({ response: { status: 502 }, message: '上游服务鉴权状态异常' })
    const wrapper = mount(ChatPanel)
    await send(wrapper, 'hi')
    const message = wrapper.findAll('[data-test="message"]').at(-1)
    expect(message.text()).toContain('上游服务鉴权状态异常')
    expect(message.classes()).toContain('failed')
  })

  it('服务不可达显示兜底文案', async () => {
    chat.mockRejectedValue(new Error('Network Error'))
    const wrapper = mount(ChatPanel)
    await send(wrapper, 'hi')
    expect(wrapper.text()).toContain('无法连接服务，请稍后重试')
  })

  it('超过 2000 字被拦截,不调用接口', async () => {
    const wrapper = mount(ChatPanel)
    await send(wrapper, 'a'.repeat(2001))
    expect(wrapper.find('[data-test="error"]').text()).toBe('message 不能超过 2000 字')
    expect(chat).not.toHaveBeenCalled()
  })

  it('token 不渲染进对话文本', async () => {
    saveAuth({ token: 'tok-hide-7', username: 'admin', role: 'admin' })
    chat.mockResolvedValue(chatResponse())
    const wrapper = mount(ChatPanel)
    await send(wrapper, 'hi')
    expect(wrapper.text()).not.toContain('tok-hide-7')
  })
})

const CONV_KEY = 'sca_conv:admin'

function historyResponse(items) {
  return {
    data: {
      data: { conversationId: 'conv-restored', items, total: items.length, page: 1, size: 100 },
    },
  }
}

function userItem(content) {
  return { role: 'user', content, requestId: 'req-1', status: null, createdAt: '2026-01-01 00:00:00', toolCalls: null }
}

function agentItem(content, extra = {}) {
  return {
    role: 'assistant',
    content,
    requestId: 'req-1',
    status: 'success',
    createdAt: '2026-01-01 00:00:00',
    toolCalls: [],
    ...extra,
  }
}

describe('ChatPanel W4-1 会话恢复与持久化', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    vi.clearAllMocks()
  })

  it('凭用户存储键恢复历史气泡并复用会话 id', async () => {
    saveAuth({ token: 't', username: 'admin', role: 'admin' })
    sessionStorage.setItem(CONV_KEY, 'conv-restored')
    chatHistory.mockResolvedValue(
      historyResponse([
        userItem('AUD-001 还有多少库存？'),
        agentItem('库存 22 件。', {
          toolCalls: [{ tool_call_id: 'tc-1', tool: 'query-stock-level', params: {}, ok: true, round: 1, summary: '库存 22 件' }],
        }),
      ]),
    )
    const wrapper = mount(ChatPanel)
    await flushPromises()

    expect(chatHistory).toHaveBeenCalledWith({ conversation_id: 'conv-restored', page: 1, size: 100 })
    const bubbles = wrapper.findAll('[data-test="message"]')
    expect(bubbles).toHaveLength(2)
    expect(bubbles[0].text()).toContain('AUD-001 还有多少库存？')
    expect(bubbles[1].text()).toContain('库存 22 件。')
    expect(wrapper.find('[data-test="tool-call"]').text()).toContain('query-stock-level')

    chat.mockResolvedValueOnce(chatResponse({ reply: '补货 26 件' }))
    await send(wrapper, '那补货呢？')
    expect(chat.mock.calls[0][0].conversation_id).toBe('conv-restored')
  })

  it('无存储键时不请求历史,展示空态', async () => {
    saveAuth({ token: 't', username: 'admin', role: 'admin' })
    const wrapper = mount(ChatPanel)
    await flushPromises()
    expect(chatHistory).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('向 Agent 提问')
    expect(wrapper.find('[data-test="chat-new-session"]').exists()).toBe(false)
  })

  it('恢复空会话时清本地键回空态,不展示空壳会话', async () => {
    saveAuth({ token: 't', username: 'admin', role: 'admin' })
    sessionStorage.setItem(CONV_KEY, 'conv-empty')
    chatHistory.mockResolvedValue(historyResponse([]))
    const wrapper = mount(ChatPanel)
    await flushPromises()

    expect(sessionStorage.getItem(CONV_KEY)).toBeNull()
    expect(wrapper.findAll('[data-test="message"]')).toHaveLength(0)
    expect(wrapper.find('[data-test="error"]').exists()).toBe(false)
  })

  it('恢复 403(他人会话)清本地键并提示,随后发送开新会话', async () => {
    saveAuth({ token: 't', username: 'admin', role: 'admin' })
    sessionStorage.setItem(CONV_KEY, 'conv-other')
    chatHistory.mockRejectedValue({ response: { status: 403 }, message: '无权访问该会话' })
    const wrapper = mount(ChatPanel)
    await flushPromises()

    expect(wrapper.find('[data-test="error"]').text()).toBe('无权访问该会话')
    expect(sessionStorage.getItem(CONV_KEY)).toBeNull()

    chat.mockResolvedValueOnce(chatResponse())
    await send(wrapper, 'hi')
    expect(chat.mock.calls[0][0].conversation_id).toBeUndefined()
  })

  it('恢复请求网络失败提示兜底文案并保留会话键供重试', async () => {
    saveAuth({ token: 't', username: 'admin', role: 'admin' })
    sessionStorage.setItem(CONV_KEY, 'conv-x')
    chatHistory.mockRejectedValue(new Error('Network Error'))
    const wrapper = mount(ChatPanel)
    await flushPromises()

    expect(wrapper.find('[data-test="error"]').text()).toBe('无法连接服务，请稍后重试')
    expect(sessionStorage.getItem(CONV_KEY)).toBe('conv-x')
  })

  it('恢复请求 5xx 时保留会话键供服务恢复后重试', async () => {
    saveAuth({ token: 't', username: 'admin', role: 'admin' })
    sessionStorage.setItem(CONV_KEY, 'conv-x')
    chatHistory.mockRejectedValue({ response: { status: 503 }, message: '服务暂不可用' })
    const wrapper = mount(ChatPanel)
    await flushPromises()

    expect(wrapper.find('[data-test="error"]').text()).toBe('服务暂不可用')
    expect(sessionStorage.getItem(CONV_KEY)).toBe('conv-x')
  })

  it('恢复尚未完成时新建会话,迟到的旧响应不覆盖新会话状态', async () => {
    saveAuth({ token: 't', username: 'admin', role: 'admin' })
    sessionStorage.setItem(CONV_KEY, 'conv-old')
    let resolveHistory
    chatHistory.mockReturnValue(new Promise((resolve) => { resolveHistory = resolve }))
    const wrapper = mount(ChatPanel)

    await wrapper.find('[data-test="chat-new-session"]').trigger('click')
    expect(sessionStorage.getItem(CONV_KEY)).toBeNull()
    expect(wrapper.find('[data-test="chat-restoring"]').exists()).toBe(false)

    resolveHistory(historyResponse([userItem('旧会话问题'), agentItem('旧会话回答')]))
    await flushPromises()

    expect(wrapper.findAll('[data-test="message"]')).toHaveLength(0)
    expect(wrapper.find('[data-test="error"]').exists()).toBe(false)
  })

  it('首轮成功后会话 id 写入带用户名的存储键', async () => {
    saveAuth({ token: 't', username: 'viewer', role: 'viewer' })
    chat.mockResolvedValueOnce(chatResponse())
    const wrapper = mount(ChatPanel)
    await send(wrapper, 'hi')
    expect(sessionStorage.getItem('sca_conv:viewer')).toBe('conv-9')
  })

  it('不同用户的存储键互不干扰', async () => {
    sessionStorage.setItem('sca_conv:viewer', 'conv-viewer')
    saveAuth({ token: 't', username: 'admin', role: 'admin' })
    chat.mockResolvedValueOnce(chatResponse())
    const wrapper = mount(ChatPanel)
    await flushPromises()
    expect(chatHistory).not.toHaveBeenCalled()

    await send(wrapper, 'hi')
    expect(sessionStorage.getItem('sca_conv:admin')).toBe('conv-9')
    expect(sessionStorage.getItem('sca_conv:viewer')).toBe('conv-viewer')
  })

  it('新会话按钮清空气泡与本地键,下一轮不带 conversation_id', async () => {
    saveAuth({ token: 't', username: 'admin', role: 'admin' })
    sessionStorage.setItem(CONV_KEY, 'conv-restored')
    chatHistory.mockResolvedValue(historyResponse([userItem('旧问题'), agentItem('旧回答')]))
    const wrapper = mount(ChatPanel)
    await flushPromises()
    expect(wrapper.findAll('[data-test="message"]')).toHaveLength(2)

    await wrapper.find('[data-test="chat-new-session"]').trigger('click')
    expect(wrapper.findAll('[data-test="message"]')).toHaveLength(0)
    expect(sessionStorage.getItem(CONV_KEY)).toBeNull()

    chat.mockResolvedValueOnce(chatResponse())
    await send(wrapper, '新问题')
    expect(chat.mock.calls[0][0].conversation_id).toBeUndefined()
  })

  it('失败历史行以失败样式恢复', async () => {
    saveAuth({ token: 't', username: 'admin', role: 'admin' })
    sessionStorage.setItem(CONV_KEY, 'conv-failed')
    chatHistory.mockResolvedValue(
      historyResponse([agentItem('上游服务暂不可用，请稍后重试', { status: 'failed' })]),
    )
    const wrapper = mount(ChatPanel)
    await flushPromises()

    const bubble = wrapper.find('[data-test="message"]')
    expect(bubble.classes()).toContain('failed')
    expect(bubble.text()).toContain('上游服务暂不可用，请稍后重试')
  })
})

describe('ChatPanel W4-2 来源展示', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    vi.clearAllMocks()
  })

  it('sources 渲染 doc/section/chunk_id 纯文本', async () => {
    chat.mockResolvedValue(
      chatResponse({
        reply: '采购单需人工确认。',
        sources: [
          { doc: '供应链运营知识', section: '采购单与人工确认', chunk_id: 'supply-ops.zh#purchase-approval-1' },
        ],
      }),
    )
    const wrapper = mount(ChatPanel)
    await send(wrapper, '采购单要确认吗？')

    const sources = wrapper.find('[data-test="sources"]')
    expect(sources.exists()).toBe(true)
    expect(sources.text()).toContain('参考来源')
    const item = wrapper.find('[data-test="source"]')
    expect(item.text()).toContain('供应链运营知识')
    expect(item.text()).toContain('采购单与人工确认')
    expect(item.text()).toContain('supply-ops.zh#purchase-approval-1')
  })

  it('无 sources 键 / 空数组都不渲染空壳区块', async () => {
    chat.mockResolvedValueOnce(chatResponse())
    const withoutKey = mount(ChatPanel)
    await send(withoutKey, 'hi')
    expect(withoutKey.find('[data-test="sources"]').exists()).toBe(false)

    chat.mockResolvedValueOnce(chatResponse({ sources: [] }))
    const emptyArray = mount(ChatPanel)
    await send(emptyArray, 'hi')
    expect(emptyArray.find('[data-test="sources"]').exists()).toBe(false)
  })

  it('字段异常条目被过滤,合法条目保留', async () => {
    chat.mockResolvedValue(
      chatResponse({ sources: [null, 'x', 42, { doc: 7, section: null }, { chunk_id: 'only-id-1' }] }),
    )
    const wrapper = mount(ChatPanel)
    await send(wrapper, 'hi')

    const items = wrapper.findAll('[data-test="source"]')
    expect(items).toHaveLength(1)
    expect(items[0].text()).toContain('only-id-1')
  })

  it('全非法 sources 不渲染区块且不崩溃', async () => {
    chat.mockResolvedValue(chatResponse({ sources: [null, {}, { doc: 1 }] }))
    const wrapper = mount(ChatPanel)
    await send(wrapper, 'hi')
    expect(wrapper.find('[data-test="sources"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('AUD-001 当前可售 120 件')
  })

  it('XSS:来源字段按纯文本转义渲染,不注入元素', async () => {
    chat.mockResolvedValue(
      chatResponse({
        sources: [
          { doc: '<img src=x onerror=alert(1)>', section: '<script>alert(2)</script>', chunk_id: 'ok-1' },
        ],
      }),
    )
    const wrapper = mount(ChatPanel)
    await send(wrapper, 'hi')

    const item = wrapper.find('[data-test="source"]')
    expect(item.element.querySelector('img')).toBeNull()
    expect(item.element.querySelector('script')).toBeNull()
    expect(item.element.querySelector('[onerror]')).toBeNull()
    expect(item.text()).toContain('<img src=x onerror=alert(1)>')
    expect(item.text()).toContain('<script>alert(2)</script>')
    expect(wrapper.find('[data-test="sources"]').html()).not.toContain('<script>')
  })

  it('后端 500 失败时展示失败气泡,无来源区块', async () => {
    chat.mockRejectedValue({ response: { status: 500 }, message: '服务内部错误，请稍后重试' })
    const wrapper = mount(ChatPanel)
    await send(wrapper, 'hi')

    expect(wrapper.find('[data-test="sources"]').exists()).toBe(false)
    const bubble = wrapper.findAll('[data-test="message"]').at(-1)
    expect(bubble.classes()).toContain('failed')
    expect(bubble.text()).toContain('服务内部错误，请稍后重试')
  })
})

describe('ChatPanel W4 收口:发送中切换会话保护与失败会话恢复', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    vi.clearAllMocks()
  })

  it('发送中新建会话:迟到旧响应被丢弃,新会话可立即发送且 finally 不破坏新发送', async () => {
    saveAuth({ token: 't', username: 'admin', role: 'admin' })
    let resolveOld
    chat.mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve }))
    const wrapper = mount(ChatPanel)
    await send(wrapper, '旧会话问题')
    expect(wrapper.find('[data-test="chat-send"]').text()).toContain('发送中')

    await wrapper.find('[data-test="chat-new-session"]').trigger('click')
    expect(wrapper.findAll('[data-test="message"]')).toHaveLength(0)
    expect(wrapper.find('[data-test="chat-send"]').text()).toBe('发送')

    // 新会话里再次发送(旧请求仍未返回)
    let resolveNew
    chat.mockImplementationOnce(() => new Promise((resolve) => { resolveNew = resolve }))
    await send(wrapper, '新会话问题')
    expect(chat.mock.calls[1][0].conversation_id).toBeUndefined()

    // 旧响应迟到:不追气泡、不回填 id、不清新发送的 sending
    resolveOld(chatResponse({ reply: '旧回答', conversation_id: 'conv-old' }))
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧回答')
    expect(wrapper.findAll('[data-test="message"]')).toHaveLength(1)
    expect(sessionStorage.getItem(CONV_KEY)).toBeNull()
    expect(wrapper.find('[data-test="chat-send"]').text()).toContain('发送中')

    resolveNew(chatResponse({ reply: '新回答', conversation_id: 'conv-new' }))
    await flushPromises()
    expect(wrapper.text()).toContain('新回答')
    expect(wrapper.findAll('[data-test="message"]')).toHaveLength(2)
    expect(sessionStorage.getItem(CONV_KEY)).toBe('conv-new')
    expect(wrapper.find('[data-test="chat-send"]').text()).toBe('发送')
  })

  it('首轮失败凭 X-Conversation-Id 响应头恢复会话,失败气泡展示 request_id', async () => {
    saveAuth({ token: 't', username: 'admin', role: 'admin' })
    chat.mockRejectedValueOnce({
      response: {
        status: 502,
        headers: { 'x-conversation-id': 'conv-recovered', 'x-request-id': 'req-502' },
      },
      message: '上游服务暂不可用，请稍后重试',
    })
    const wrapper = mount(ChatPanel)
    await send(wrapper, 'hi')

    expect(sessionStorage.getItem(CONV_KEY)).toBe('conv-recovered')
    const bubble = wrapper.findAll('[data-test="message"]').at(-1)
    expect(bubble.classes()).toContain('failed')
    expect(bubble.text()).toContain('上游服务暂不可用，请稍后重试')
    expect(bubble.text()).toContain('request_id: req-502')

    chat.mockResolvedValueOnce(chatResponse({ reply: '续接成功' }))
    await send(wrapper, '继续')
    expect(chat.mock.calls[1][0].conversation_id).toBe('conv-recovered')
    expect(wrapper.text()).toContain('续接成功')
  })

  it('已有本地会话 id 时失败响应头不覆盖本地值', async () => {
    saveAuth({ token: 't', username: 'admin', role: 'admin' })
    sessionStorage.setItem(CONV_KEY, 'conv-local')
    chatHistory.mockResolvedValue(historyResponse([userItem('旧问题'), agentItem('旧回答')]))
    const wrapper = mount(ChatPanel)
    await flushPromises()

    chat.mockRejectedValueOnce({
      response: { status: 504, headers: { 'x-conversation-id': 'conv-server' } },
      message: '上游响应超时，请稍后重试',
    })
    await send(wrapper, 'hi')

    expect(chat.mock.calls[0][0].conversation_id).toBe('conv-local')
    expect(sessionStorage.getItem(CONV_KEY)).toBe('conv-local')
  })

  it('无响应头的网络失败不展示 request_id,不产生会话键', async () => {
    saveAuth({ token: 't', username: 'admin', role: 'admin' })
    chat.mockRejectedValueOnce(new Error('Network Error'))
    const wrapper = mount(ChatPanel)
    await send(wrapper, 'hi')

    expect(wrapper.text()).toContain('无法连接服务，请稍后重试')
    expect(wrapper.find('[data-test="chat-request-id"]').exists()).toBe(false)
    expect(sessionStorage.getItem(CONV_KEY)).toBeNull()
  })
})
