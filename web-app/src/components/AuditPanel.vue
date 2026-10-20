<script setup>
import { computed, onMounted, ref } from 'vue'
import { auditHistory } from '../api'
import ResultTable from './ResultTable.vue'

const columns = [
  { key: 'executedAt', label: '时间' },
  { key: 'actionType', label: '动作' },
  { key: 'who', label: '执行者' },
  { key: 'operator', label: '操作者' },
  { key: 'taskKey', label: '业务键' },
  { key: 'beforeJson', label: '变更前' },
  { key: 'afterJson', label: '变更后' },
  { key: 'statsJson', label: '统计' },
  { key: 'requestId', label: 'request_id' },
  { key: 'conversationId', label: 'conversation_id' },
  { key: 'toolCallId', label: 'tool_call_id' },
]

const SIZE = 20

const filters = ref({ taskKey: '', actionType: '', operator: '', requestId: '', conversationId: '' })
const result = ref(null)
const error = ref('')
const loading = ref(false)

const canPrev = computed(() => !!result.value && result.value.page > 1)
const hasNext = computed(
  () => !!result.value && result.value.page * result.value.size < result.value.total,
)

function buildPayload(targetPage) {
  const payload = { page: targetPage, size: SIZE }
  for (const [key, value] of Object.entries(filters.value)) {
    const trimmed = value.trim()
    if (trimmed) {
      payload[key] = trimmed
    }
  }
  return payload
}

async function run(targetPage) {
  loading.value = true
  error.value = ''
  try {
    const response = await auditHistory(buildPayload(targetPage))
    result.value = response.data.data
  } catch (e) {
    result.value = null
    error.value = e.response ? (e.message || '查询失败') : '无法连接服务，请稍后重试'
  } finally {
    loading.value = false
  }
}

function search() {
  run(1)
}

function prevPage() {
  if (canPrev.value && !loading.value) {
    run(result.value.page - 1)
  }
}

function nextPage() {
  if (hasNext.value && !loading.value) {
    run(result.value.page + 1)
  }
}

function display(value) {
  return value === null || value === undefined ? '—' : value
}

onMounted(() => run(1))
</script>

<template>
  <div>
    <p class="notice" data-test="audit-notice">
      该时间线只记录已发生的状态迁移，不包含被拒请求、幂等回放等未产生写入的事件。
    </p>

    <form @submit.prevent="search">
      <div class="filters">
        <input v-model="filters.taskKey" placeholder="业务键（精确）" data-test="audit-task-key" />
        <input v-model="filters.actionType" placeholder="动作，如 po_create" data-test="audit-action-type" />
        <input v-model="filters.operator" placeholder="操作者（精确）" data-test="audit-operator" />
        <input v-model="filters.requestId" placeholder="request_id（精确）" data-test="audit-request-id" />
        <input v-model="filters.conversationId" placeholder="conversation_id（精确）" data-test="audit-conversation-id" />
      </div>

      <p v-if="error" class="error" data-test="audit-error" role="alert">{{ error }}</p>
      <button type="submit" :disabled="loading" data-test="audit-query">
        {{ loading ? '查询中…' : '查询审计' }}
      </button>
    </form>

    <ResultTable
      v-if="result"
      :columns="columns"
      :rows="result.items || []"
      :empty-text="'无匹配记录'"
    >
      <template #cell="{ row, column }">
        <span class="cell-text">{{ display(row[column.key]) }}</span>
      </template>
    </ResultTable>

    <template v-if="result">
      <p class="page-info" data-test="audit-page-info">第 {{ result.page }} 页 · 共 {{ result.total }} 条</p>
      <div class="pager">
        <button type="button" :disabled="loading || !canPrev" data-test="audit-prev" @click="prevPage">上一页</button>
        <button type="button" :disabled="loading || !hasNext" data-test="audit-next" @click="nextPage">下一页</button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.notice {
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  color: #1e40af;
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 12px;
  margin: 0 0 10px;
}
.filters {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}
.filters input {
  width: 100%;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 13px;
  font-family: inherit;
}
.error {
  color: #dc2626;
  font-size: 13px;
  margin: 8px 0 0;
}
[type='submit'] {
  margin-top: 10px;
  border: 0;
  background: #2563eb;
  color: #fff;
  border-radius: 8px;
  padding: 8px 16px;
  font-size: 13px;
  cursor: pointer;
}
[type='submit']:disabled {
  opacity: 0.6;
  cursor: default;
}
.page-info {
  color: #6b7280;
  font-size: 12px;
  margin: 10px 0 6px;
}
.pager {
  display: flex;
  gap: 8px;
}
.pager button {
  border: 1px solid #d1d5db;
  background: #fff;
  border-radius: 8px;
  padding: 5px 12px;
  font-size: 13px;
  cursor: pointer;
}
.pager button:disabled {
  opacity: 0.5;
  cursor: default;
}
</style>
