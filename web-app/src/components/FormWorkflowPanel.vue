<script setup>
import { ref } from 'vue'
import { formRecord, formRun } from '../api'

const kind = ref('record')
const identifier = ref('R-1001')
const loading = ref(false)
const result = ref(null)
const shownKind = ref('')
const error = ref('')
const labels = { record_id: '记录号', source_record_id: '来源记录', sku_code: 'SKU', business_version: '业务版本',
  flow_type: '表单类型', run_id: '运行号', status: '执行状态', apply_result: '执行结果',
  write_count: '写入次数', replay_count: '回查次数', failure_reason: '复核原因', updated_at: '更新时间' }
function display(value) { return value === null || value === undefined || value === '' ? '—' : value }
async function query() {
  if (loading.value) return
  const id = identifier.value.trim()
  error.value = ''
  result.value = null
  if (!/^[A-Za-z0-9_-]{1,100}$/.test(id)) { error.value = '请输入有效记录号或运行号（字母、数字、横线或下划线）。'; return }
  const requestedKind = kind.value
  loading.value = true
  try {
    const response = await (requestedKind === 'record' ? formRecord(id) : formRun(id))
    result.value = response.data.data
    shownKind.value = requestedKind
  } catch (e) {
    error.value = e.response?.status === 503 ? '表单服务暂不可用；当前未取得状态，请确认独立服务已启动后重试。'
      : e.response ? (e.message || '查询失败') : '无法连接服务，当前未取得表单状态。'
  } finally { loading.value = false }
}
</script>

<template>
  <section data-test="form-workflow">
    <p class="notice">独立的发货准备表单：核验外箱唛、审批后受控回填。采购确认不会自动创建该表单；外箱唛完成也不代表出库、发货或签收。本面板仅查询，不执行审批或回填。</p>
    <form @submit.prevent="query">
      <label>查询类型 <select v-model="kind" :disabled="loading" data-test="form-kind"><option value="record">表单记录</option><option value="run">执行记录</option></select></label>
      <label>记录标识 <input v-model="identifier" :disabled="loading" placeholder="R-1001 或运行号" data-test="form-id" /></label>
      <button type="submit" :disabled="loading" data-test="form-query">{{ loading ? '查询中…' : '查询表单状态' }}</button>
    </form>
    <p v-if="error" class="error" role="alert" data-test="form-error">{{ error }}</p>
    <div v-if="result" data-test="form-result">
      <dl><template v-for="(label, key) in labels" :key="key"><template v-if="Object.hasOwn(result, key)"><dt>{{ label }}</dt><dd>{{ display(result[key]) }}</dd></template></template></dl>
      <div v-if="shownKind === 'record'" data-test="form-mark">
        <strong>外箱唛当前值</strong>：{{ display(result.outer_box_mark?.value) }}
        <p>规则来源：{{ display(result.outer_box_mark?.rule_source) }}</p>
      </div>
      <div v-else>
        <p v-if="result.approval">审批决定：{{ display(result.approval.decision) }} · {{ display(result.approval.actor_id) }}</p>
        <details v-if="result.plan"><summary>查看服务端执行计划</summary><pre>{{ JSON.stringify(result.plan, null, 2) }}</pre></details>
        <ol v-if="result.audit?.length" data-test="form-audit"><li v-for="entry in result.audit" :key="entry.seq">{{ entry.ts }} · {{ entry.event_type }} · {{ entry.actor_id }}</li></ol>
      </div>
    </div>
  </section>
</template>

<style scoped>
section { font-size: 13px; }
.notice { background: #f1f5f9; color: #475569; padding: 10px; border-radius: 8px; line-height: 1.6; margin-top: 0; }
form { display: flex; flex-wrap: wrap; align-items: end; gap: 10px; }
label { display: flex; flex-direction: column; gap: 5px; }
input, select, button { padding: 7px 10px; border: 1px solid #cbd5e1; border-radius: 6px; background: white; font: inherit; }
button { cursor: pointer; background: #2563eb; color: white; }
button:disabled { opacity: .5; }
.error { color: #b91c1c; }
dl { display: grid; grid-template-columns: 100px minmax(0, 1fr); gap: 7px; }
dt { color: #64748b; }
dd { margin: 0; overflow-wrap: anywhere; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; background: #f8fafc; padding: 10px; }
li { padding: 5px 0; overflow-wrap: anywhere; }
</style>
