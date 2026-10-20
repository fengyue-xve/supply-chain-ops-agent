<script setup>
import { onMounted, ref } from 'vue'
import { chat, chatHistory } from '../api'
import { readAuth } from '../utils/auth'
import { clearConversationId, readConversationId, saveConversationId } from '../utils/conversation'

// conversation_id:首轮由服务端返回后回填并按用户持久化(sessionStorage);成功取响应体,
// 失败(502/504)取 Java 回写的 X-Conversation-Id 响应头;已有本地 id 时一律不覆盖。
// 刷新/重挂载时凭存储键从 /api/chat/history 恢复气泡;新建会话或明确无权访问时清键。
const username = readAuth()?.username || ''
const input = ref('')
const sending = ref(false)
const error = ref('')
const conversationId = ref(readConversationId(username))
const messages = ref([])
const restoring = ref(false)
let restoreGeneration = 0
// 会话代次:新建会话 +1;send 开始时记录,请求返回后若代次已变则丢弃响应,不串写新会话。
let sessionGeneration = 0

const MAX_MESSAGE_LENGTH = 2000
// Java 单页上限 100:恢复只看最近一页,超出部分不回溯(演示量级足够)
const RESTORE_PAGE_SIZE = 100

onMounted(restoreHistory)

async function restoreHistory() {
  const requestedConversationId = conversationId.value
  if (!requestedConversationId) return
  const generation = ++restoreGeneration
  restoring.value = true
  error.value = ''
  try {
    const response = await chatHistory({
      conversation_id: requestedConversationId,
      page: 1,
      size: RESTORE_PAGE_SIZE,
    })
    if (generation !== restoreGeneration) return
    const data = response.data.data || {}
    const items = Array.isArray(data.items) ? data.items : []
    if (!items.length) {
      // 服务端没有本人消息(仅旧数据/已被清理):不展示空壳会话,直接开新会话
      startNewSession()
      return
    }
    messages.value = items.map(toMessage)
  } catch (e) {
    if (generation !== restoreGeneration) return
    // 403 表示该会话不可由当前用户继续访问;网络/5xx 等临时故障保留 id 供重试。
    if (e.response?.status === 403) dropStoredConversation()
    error.value = e.response ? e.message || '会话恢复失败' : '无法连接服务，请稍后重试'
  } finally {
    if (generation === restoreGeneration) restoring.value = false
  }
}

// W4-2 来源列表:只展示 doc/section/chunk_id 三个字符串字段(纯文本插值,绝不 v-html);
// 非数组/空数组/条目字段异常 → 降级为空,不渲染空壳区块。
function normalizeSources(raw) {
  if (!Array.isArray(raw)) return []
  return raw
    .filter((item) => item && typeof item === 'object')
    .map((item) => ({
      doc: typeof item.doc === 'string' ? item.doc : '',
      section: typeof item.section === 'string' ? item.section : '',
      chunk_id: typeof item.chunk_id === 'string' ? item.chunk_id : '',
    }))
    .filter((item) => item.doc || item.section || item.chunk_id)
}

function toMessage(item) {
  if (item.role === 'user') {
    return { role: 'user', text: item.content || '' }
  }
  return {
    role: 'agent',
    text: item.content || '（空回复）',
    toolCalls: Array.isArray(item.toolCalls) ? item.toolCalls : [],
    requestId: item.requestId || '',
    failed: item.status === 'failed',
  }
}

function dropStoredConversation() {
  conversationId.value = null
  clearConversationId(username)
}

function startNewSession() {
  sessionGeneration += 1
  restoreGeneration += 1
  restoring.value = false
  sending.value = false
  dropStoredConversation()
  messages.value = []
  error.value = ''
}

// 仅当本地尚无会话 id 时回填:优先响应体(成功),其次 X-Conversation-Id 响应头
// (Java 在服务端生成 id 的所有路径回写,含 502/504);绝不覆盖本地已有 id。
function adoptServerConversationId(bodyId, headers) {
  if (conversationId.value) return
  const fromBody = typeof bodyId === 'string' && bodyId ? bodyId : ''
  const adopted = fromBody || headerValue(headers, 'x-conversation-id')
  if (!adopted) return
  conversationId.value = adopted
  saveConversationId(username, adopted)
}

function headerValue(headers, name) {
  const value = headers ? headers[name] : ''
  return typeof value === 'string' && value ? value : ''
}

async function send() {
  const text = input.value.trim()
  if (!text || sending.value || restoring.value) return
  if (text.length > MAX_MESSAGE_LENGTH) {
    error.value = `message 不能超过 ${MAX_MESSAGE_LENGTH} 字`
    return
  }
  // 发送中切会话保护:请求返回后若代次已变(用户点过新会话),旧响应一律丢弃——
  // 不回填 id、不追气泡、finally 也不清新会话的 sending。
  const generation = sessionGeneration
  sending.value = true
  error.value = ''
  messages.value.push({ role: 'user', text })
  input.value = ''
  try {
    const response = await chat({ conversation_id: conversationId.value ?? undefined, message: text })
    if (generation !== sessionGeneration) return
    const data = response.data.data || {}
    adoptServerConversationId(data.conversation_id, response.headers)
    const requestId = headerValue(response.headers, 'x-request-id') || data.request_id || ''
    messages.value.push({
      role: 'agent',
      text: data.reply || '（空回复）',
      toolCalls: data.tool_calls || [],
      sources: normalizeSources(data.sources),
      requestId,
    })
  } catch (e) {
    if (generation !== sessionGeneration) return
    // 失败路径响应体没有 data:凭 X-Conversation-Id 头恢复会话 id;
    // 失败气泡附带既有 X-Request-Id 便于定位(信封与接口字段均未改动)。
    adoptServerConversationId('', e.response?.headers)
    messages.value.push({
      role: 'agent',
      text: e.response ? (e.message || '请求失败') : '无法连接服务，请稍后重试',
      requestId: headerValue(e.response?.headers, 'x-request-id'),
      failed: true,
    })
  } finally {
    if (generation === sessionGeneration) sending.value = false
  }
}
</script>

<template>
  <div class="chat-card">
    <div class="chat-head">
      <span>Agent 对话</span>
      <button
        v-if="conversationId || messages.length"
        type="button"
        class="new-session"
        data-test="chat-new-session"
        @click="startNewSession"
      >
        新会话
      </button>
    </div>
    <div class="messages" data-test="messages">
      <div v-if="restoring" class="placeholder" data-test="chat-restoring">正在恢复会话…</div>
      <div v-else-if="!messages.length" class="placeholder">向 Agent 提问，如「AUD-001 库存还有多少？」</div>
      <div v-for="(message, index) in messages" :key="index" class="row" :class="message.role">
        <div class="bubble" :class="{ failed: message.failed }" data-test="message">
          {{ message.text }}
          <template v-if="message.role === 'agent' && message.toolCalls && message.toolCalls.length">
            <div class="tools" data-test="tool-calls">
              <div v-for="(call, callIndex) in message.toolCalls" :key="callIndex" class="tool" :class="{ failed: !call.ok }" data-test="tool-call">
                <span class="tool-name">{{ call.tool }}</span>
                <span class="tool-status">{{ call.ok ? '成功' : '失败' }}</span>
                <span v-if="call.error" class="tool-error" data-test="tool-error">{{ call.error }}</span>
                <details>
                  <summary>调用详情（第 {{ call.round }} 轮）</summary>
                  <pre>{{ JSON.stringify({ params: call.params, summary: call.summary }, null, 2) }}</pre>
                </details>
              </div>
            </div>
          </template>
          <template v-if="message.role === 'agent' && message.sources && message.sources.length">
            <div class="sources" data-test="sources">
              <div class="sources-title">参考来源</div>
              <div v-for="(source, sourceIndex) in message.sources" :key="sourceIndex" class="source" data-test="source">
                <span class="source-doc">{{ source.doc }}</span>
                <span v-if="source.section" class="source-section">{{ source.section }}</span>
                <span class="source-id">{{ source.chunk_id }}</span>
              </div>
            </div>
          </template>
          <p v-if="message.requestId" class="req-id" data-test="chat-request-id">request_id: {{ message.requestId }}</p>
        </div>
      </div>
    </div>
    <p v-if="error" class="error" data-test="error" role="alert">{{ error }}</p>
    <form class="input-row" @submit.prevent="send">
      <textarea v-model="input" rows="2" maxlength="2001" placeholder="输入消息，Enter 发送…（Shift+Enter 换行）" data-test="chat-input" @keydown.enter.exact.prevent="send" />
      <button type="submit" :disabled="sending || restoring" data-test="chat-send">{{ sending ? '发送中…' : '发送' }}</button>
    </form>
  </div>
</template>

<style scoped>
.chat-card {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.chat-head {
  font-size: 15px;
  font-weight: 600;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.new-session {
  border: 1px solid #d1d5db;
  background: #fff;
  color: #374151;
  border-radius: 8px;
  padding: 4px 10px;
  font-size: 12px;
  cursor: pointer;
}
.new-session:hover {
  border-color: #2563eb;
  color: #2563eb;
}
.messages {
  min-height: 260px;
  max-height: 60vh;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 4px;
}
.placeholder {
  color: #9ca3af;
  font-size: 13px;
  text-align: center;
  margin: auto;
}
.row {
  display: flex;
}
.row.user {
  justify-content: flex-end;
}
.row.agent {
  justify-content: flex-start;
}
.bubble {
  max-width: 86%;
  border-radius: 10px;
  padding: 8px 12px;
  font-size: 14px;
  white-space: pre-wrap;
  word-break: break-word;
  background: #f3f4f6;
  border: 1px solid #e5e7eb;
}
.row.user .bubble {
  background: #2563eb;
  color: #fff;
  border-color: #2563eb;
}
.bubble.failed {
  background: #fef2f2;
  border-color: #fca5a5;
  color: #991b1b;
}
.tools {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.tool {
  border: 1px solid #d1d5db;
  background: #fff;
  border-radius: 8px;
  padding: 6px 8px;
  font-size: 13px;
}
.tool.failed {
  border-color: #fca5a5;
}
.tool-name {
  font-weight: 600;
  margin-right: 8px;
}
.tool-status {
  margin-right: 8px;
}
.tool-error {
  color: #dc2626;
  display: block;
  margin: 4px 0;
}
.tool pre {
  margin: 6px 0 0;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 200px;
  overflow-y: auto;
}
.req-id {
  margin: 6px 0 0;
  color: #6b7280;
  font-size: 12px;
}
.sources {
  margin-top: 8px;
  border-top: 1px dashed #d1d5db;
  padding-top: 6px;
  font-size: 12px;
}
.sources-title {
  color: #6b7280;
  margin-bottom: 4px;
}
.source {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  color: #4b5563;
  padding: 2px 0;
}
.source-section {
  color: #6b7280;
}
.source-id {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: #2563eb;
  word-break: break-all;
}
.error {
  margin: 0;
  color: #dc2626;
  font-size: 13px;
}
.input-row {
  display: flex;
  gap: 8px;
  align-items: flex-end;
}
.input-row textarea {
  flex: 1;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 13px;
  font-family: inherit;
  resize: vertical;
  min-height: 44px;
}
.input-row button {
  border: 0;
  background: #2563eb;
  color: #fff;
  border-radius: 8px;
  padding: 9px 16px;
  font-size: 13px;
  cursor: pointer;
  height: 44px;
}
.input-row button:disabled {
  opacity: 0.6;
  cursor: default;
}
</style>
