<script setup>
import { computed, ref, watch } from 'vue'
import { createPurchaseOrder } from '../api'
import { readAuth } from '../utils/auth'

const props = defineProps({ suggestion: { type: Object, default: null } })
const emit = defineEmits(['created'])
const auth = readAuth()
const isAdmin = auth?.role === 'admin'
const storageKey = 'sca_purchase_attempt:' + (auth?.username || '')
const rows = ref([])
const reviewing = ref(false)
const busy = ref(false)
const outcome = ref('')
const error = ref('')
const result = ref(null)
const existing = ref([])
let attempt = null
const blocked = computed(() => busy.value || outcome.value === 'unknown')

function persist() {
  // 必须先保存再请求。浏览器不能保存时不执行写入，避免刷新后丢失未知请求的幂等键。
  localStorage.setItem(storageKey, JSON.stringify(attempt))
}
try {
  const saved = JSON.parse(localStorage.getItem(storageKey) || 'null')
  if (saved && typeof saved.key === 'string' && Array.isArray(saved.payload?.items) &&
      saved.payload.items.length && saved.payload.items.every(r => typeof r.sku === 'string' && Number.isInteger(r.qty) && r.qty > 0)) {
    attempt = saved
    if (saved.state === 'success' || saved.state === 'rejected') {
      outcome.value = saved.state
      rows.value = saved.payload.items.map(r => ({ ...r, pkgQty: null }))
    }
    if (saved.state !== 'success' && saved.state !== 'rejected') {
      rows.value = saved.payload.items.map(r => ({ ...r, pkgQty: null }))
      outcome.value = 'unknown'
      reviewing.value = true
    }
  }
} catch {
  error.value = '无法恢复采购请求记录；请先核对待确认采购单，确认浏览器存储可用后再操作。'
}

watch(() => props.suggestion, row => {
  if (!row || !isAdmin) return
  if (blocked.value) {
    error.value = '上一请求结果尚未确认，请先使用原请求核对，暂不能改变采购内容。'
    return
  }
  const item = { sku: row.sku, qty: row.suggestedQty, pkgQty: row.pkgQty }
  const index = rows.value.findIndex(r => r.sku === row.sku)
  if (index >= 0) rows.value[index] = item
  else rows.value.push(item)
  reviewing.value = false
  result.value = null
  error.value = ''
})

function prepare() {
  error.value = ''
  result.value = null
  existing.value = []
  if (!rows.value.length) { error.value = '请先从参考建议带入商品。'; return }
  for (const row of rows.value) {
    const qty = Number(row.qty)
    if (!Number.isInteger(qty) || qty <= 0) { error.value = row.sku + ' 数量必须是正整数。'; return }
    if (row.pkgQty && qty % row.pkgQty !== 0) {
      error.value = row.sku + ' 数量必须是箱规 ' + row.pkgQty + ' 的整数倍。'; return
    }
  }
  reviewing.value = true
}

function startNewIntent() {
  if (busy.value || outcome.value === 'unknown') return
  try { localStorage.removeItem(storageKey) } catch {
    error.value = '无法清除上次请求记录，本次未开始新采购意向。'; return
  }
  attempt = null
  rows.value = []
  reviewing.value = false
  outcome.value = ''
  result.value = null
  existing.value = []
  error.value = ''
}

async function submit() {
  if (!isAdmin || !reviewing.value || busy.value) return
  const payload = outcome.value === 'unknown' ? attempt.payload :
    { items: rows.value.map(r => ({ sku: r.sku, qty: Number(r.qty) })) }
  const fingerprint = JSON.stringify(payload)
  if (!attempt || JSON.stringify(attempt.payload) !== fingerprint) {
    attempt = { payload, key: globalThis.crypto?.randomUUID?.() || 'po-' + Date.now() + '-' + Math.random().toString(36).slice(2), state: 'pending' }
  }
  attempt.state = 'pending'
  try { persist() } catch {
    error.value = '无法保存幂等请求记录，本次未发送。请确认浏览器本地存储可用。'; return
  }
  busy.value = true
  error.value = ''
  existing.value = []
  try {
    const response = await createPurchaseOrder(attempt.payload, attempt.key)
    result.value = response.data.data
    outcome.value = 'success'
    attempt.state = 'success'
    try { persist() } catch { /* 已收到成功响应，内存仍保留幂等键。 */ }
    reviewing.value = false
    emit('created')
  } catch (e) {
    if (!e.response || e.response.status >= 500) {
      outcome.value = 'unknown'
      attempt.state = 'unknown'
      error.value = '结果未确认。请先刷新待确认采购单；重试只发送原内容并沿用原幂等键，不会自动新建请求。'
    } else {
      outcome.value = 'rejected'
      attempt.state = 'rejected'
      reviewing.value = false
      const data = e.response.data?.data
      existing.value = data?.existingOrders || data?.items || []
      error.value = e.response.status === 409
        ? '采购请求冲突：' + (e.message || '已有采购意向，请先核对待确认采购单。')
        : (e.message || '采购草稿未创建，请核对内容。')
    }
    try { persist() } catch { /* 原 pending 记录已在发送前持久化。 */ }
  } finally { busy.value = false }
}
</script>

<template>
  <section v-if="isAdmin" class="draft" data-test="purchase-draft">
    <h3>人工采购草稿</h3>
    <p>先从参考建议带入，人工调整并复核后创建 pending 草稿；采购确认在下方另行执行，不增加库存。</p>
    <p v-if="!rows.length">尚未选择商品。</p>
    <div v-if="outcome === 'success' || outcome === 'rejected'" class="warning" data-test="new-intent-warning">
      <p>再次采购前请核对既有意向。仅在明确需要独立新意向时开始；普通重试仍沿用上次请求。</p>
      <button type="button" :disabled="blocked" data-test="draft-new-intent" @click="startNewIntent">我已核对，开始新的采购意向</button>
    </div>
    <div v-for="(row, index) in rows" :key="row.sku" class="draft-row">
      <strong>{{ row.sku }}</strong>
      <label>采购数量 <input v-model.number="row.qty" type="number" min="1" step="1" :disabled="blocked" :data-test="'draft-qty-' + row.sku" @input="reviewing = false" /></label>
      <span>箱规 {{ row.pkgQty || '由后端复核' }}</span>
      <button type="button" :disabled="blocked" :data-test="'draft-remove-' + row.sku" @click="rows.splice(index, 1); reviewing = false">移除</button>
    </div>
    <p v-if="error" role="alert" class="error" data-test="draft-error">{{ error }}</p>
    <p v-for="po in existing" :key="po.poNo" data-test="draft-existing">已有采购单 {{ po.poNo }} · {{ po.sku || po.skuCode }} · {{ po.qty }} · {{ po.status }}</p>
    <p v-if="outcome === 'unknown'" class="warning" data-test="draft-unknown">上一请求结果未知，当前内容已锁定。刷新页面后仍会保留原请求；请核对下方待确认单。</p>
    <button v-if="rows.length && !reviewing" type="button" :disabled="blocked" data-test="draft-review" @click="prepare">复核采购草稿</button>
    <div v-if="reviewing" class="review" data-test="draft-review-summary">
      <strong>请核对本次采购意向</strong>
      <p v-for="row in rows" :key="row.sku">{{ row.sku }}：{{ row.qty }} 件</p>
      <p>本次仅创建草稿，不确认采购、不收货、不生成发货表单。商品状态、箱规和重复意向由后端最终校验。</p>
      <button type="button" :disabled="busy" data-test="draft-submit" @click="submit">{{ busy ? '提交中…' : outcome === 'unknown' ? '使用原请求核对并重试' : '确认创建采购草稿' }}</button>
      <button v-if="outcome !== 'unknown'" type="button" :disabled="busy" data-test="draft-cancel" @click="reviewing = false">返回修改</button>
    </div>
    <div v-if="result" role="status" data-test="draft-result">
      <strong>{{ result.duplicated ? '已返回原采购草稿' : '采购草稿已创建' }}</strong>
      <p v-for="po in result.items || []" :key="po.poNo">{{ po.poNo }} · {{ po.sku }} · {{ po.qty }} 件 · {{ po.status }}</p>
      <p>请在待确认采购单中核对并独立确认。</p>
    </div>
  </section>
</template>

<style scoped>
.draft { margin-top: 16px; padding: 12px; border: 1px solid #cbd5e1; border-radius: 8px; font-size: 13px; }
h3 { margin: 0 0 8px; font-size: 14px; }
p { line-height: 1.6; }
.draft-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin: 8px 0; }
input { width: 100px; padding: 6px; border: 1px solid #cbd5e1; border-radius: 6px; }
button { border: 1px solid #cbd5e1; background: #fff; border-radius: 6px; padding: 7px 10px; cursor: pointer; margin-right: 6px; }
button:disabled { opacity: .5; cursor: default; }
[data-test="draft-submit"] { background: #1d4ed8; color: white; }
.review { padding: 10px; background: #eff6ff; border-radius: 6px; margin-top: 10px; }
.error { color: #b91c1c; }
.warning { color: #92400e; background: #fffbeb; padding: 8px; }
</style>
