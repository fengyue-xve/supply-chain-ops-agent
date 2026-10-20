// 全部接口路径相对 baseURL(/api),严禁再拼 /api 前缀。
// 请求体键名与 Java DTO 一致(camelCase),提交前由各面板做基础校验,后端 400 为最终校验。

import { http } from './http'

export const login = (username, password) => http.post('/auth/login', { username, password })

export const queryStockLevel = (payload) => http.post('/agent/tools/query-stock-level', payload)

export const querySales = (payload) => http.post('/agent/tools/query-sales', payload)

export const suggestReplenish = (payload) => http.post('/agent/tools/suggest-replenish', payload)

export const chat = (payload) => http.post('/chat', payload)

// W4-1 会话历史:仅本人会话可读(后端 403 为最终边界)
export const chatHistory = (payload) => http.post('/chat/history', payload)

// W3-④ 审计时间线:仅 admin 可用(后端 403 为最终边界,前端 v-if 只是体验层)。
export const auditHistory = (payload) => http.post('/audit/history', payload)

// W4 待确认采购单:仅 admin 可用(后端 403 为最终边界,前端 v-if 只是体验层)。
export const poPending = (payload) => http.post('/po/pending', payload)

// 写操作仅在人工复核后触发；幂等键由采购面板保留，未知结果重试必须沿用原键。
export const createPurchaseOrder = (payload, key) =>
  http.post('/agent/tools/create-purchase-order', payload, { headers: { 'Idempotency-Key': key } })
export const confirmPurchaseOrder = (poNo) => http.post('/agent/tools/confirm-po', { poNo })

// 经 Java 鉴权代理，只读查询独立发货准备表单。
export const formRecord = (id) => http.get('/forms/records/' + encodeURIComponent(id))
export const formRun = (id) => http.get('/forms/runs/' + encodeURIComponent(id))
