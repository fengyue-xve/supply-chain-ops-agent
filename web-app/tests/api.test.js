import { describe, it, expect, vi } from 'vitest'
import { login, queryStockLevel, querySales, suggestReplenish, chat, chatHistory, poPending, createPurchaseOrder, confirmPurchaseOrder, formRecord, formRun } from '../src/api'
import { http } from '../src/api/http'

vi.mock('../src/api/http', () => ({
  http: { post: vi.fn(), get: vi.fn() },
}))

describe('API 方法:相对路径 + camelCase 请求体', () => {
  it('login -> POST /auth/login {username,password}', () => {
    login('admin', 'pw-1')
    expect(http.post).toHaveBeenCalledWith('/auth/login', { username: 'admin', password: 'pw-1' })
  })

  it('queryStockLevel skus 复数数组', () => {
    queryStockLevel({ skus: ['AUD-001', 'AUD-002'] })
    expect(http.post).toHaveBeenCalledWith('/agent/tools/query-stock-level', { skus: ['AUD-001', 'AUD-002'] })
  })

  it('queryStockLevel category 形态(skus 与 category 二选一)', () => {
    queryStockLevel({ category: 'audio' })
    expect(http.post).toHaveBeenCalledWith('/agent/tools/query-stock-level', { category: 'audio' })
  })

  it('querySales 单数 sku + days', () => {
    querySales({ sku: 'AUD-001', days: 7 })
    expect(http.post).toHaveBeenCalledWith('/agent/tools/query-sales', { sku: 'AUD-001', days: 7 })
  })

  it('suggestReplenish 含 days 与 targetCoverDays', () => {
    suggestReplenish({ skus: ['AUD-001'], days: 30, targetCoverDays: 14 })
    expect(http.post).toHaveBeenCalledWith('/agent/tools/suggest-replenish', {
      skus: ['AUD-001'],
      days: 30,
      targetCoverDays: 14,
    })
  })

  it('chat 透传 conversation_id 与 message', () => {
    chat({ conversation_id: 'conv-1', message: 'hi' })
    expect(http.post).toHaveBeenCalledWith('/chat', { conversation_id: 'conv-1', message: 'hi' })
  })

  it('chatHistory 透传 conversation_id 与分页参数', () => {
    chatHistory({ conversation_id: 'conv-1', page: 1, size: 100 })
    expect(http.post).toHaveBeenCalledWith('/chat/history', { conversation_id: 'conv-1', page: 1, size: 100 })
  })

  it('poPending 透传分页参数', () => {
    poPending({ page: 1, size: 20 })
    expect(http.post).toHaveBeenCalledWith('/po/pending', { page: 1, size: 20 })
  })

  it('只调用这些相对路径,绝不拼 /api 前缀(防 /api/api)', () => {
    http.post.mockClear()
    login('a', 'b')
    queryStockLevel({ skus: ['A'] })
    querySales({ sku: 'A', days: 7 })
    suggestReplenish({ skus: ['A'], days: 7, targetCoverDays: 14 })
    chat({ message: 'x' })
    chatHistory({ conversation_id: 'conv-1' })
    poPending({ page: 1, size: 20 })
    const paths = http.post.mock.calls.map((call) => call[0])
    expect(paths).toEqual([
      '/auth/login',
      '/agent/tools/query-stock-level',
      '/agent/tools/query-sales',
      '/agent/tools/suggest-replenish',
      '/chat',
      '/chat/history',
      '/po/pending',
    ])
    expect(paths.every((p) => !p.startsWith('/api'))).toBe(true)
  })
})


describe('人工采购与表单代理协议', () => {
  it('创建采购传必需幂等头，确认另用独立单号接口', () => {
    createPurchaseOrder({ items: [{ sku: 'A', qty: 12 }] }, 'key-1')
    expect(http.post).toHaveBeenCalledWith('/agent/tools/create-purchase-order',
      { items: [{ sku: 'A', qty: 12 }] }, { headers: { 'Idempotency-Key': 'key-1' } })
    confirmPurchaseOrder('PO-1')
    expect(http.post).toHaveBeenCalledWith('/agent/tools/confirm-po', { poNo: 'PO-1' })
  })
  it('表单代理仅GET，路径组件编码防止拼接额外路径', () => {
    formRecord('R-1001')
    expect(http.get).toHaveBeenCalledWith('/forms/records/R-1001')
    formRun('a/b')
    expect(http.get).toHaveBeenCalledWith('/forms/runs/a%2Fb')
  })
})
