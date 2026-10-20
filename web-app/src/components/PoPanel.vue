<script setup>
import { computed, onMounted, ref } from 'vue'
import { poPending, confirmPurchaseOrder } from '../api'
import ResultTable from './ResultTable.vue'

const props = defineProps({ canConfirm: { type: Boolean, default: false } })
const emit = defineEmits(['confirmed'])
const selected = ref(null)
const confirming = ref(false)
const confirmError = ref('')
const confirmMessage = ref('')
async function confirmSelected() {
  if (!props.canConfirm || !selected.value || confirming.value) return
  confirming.value = true
  confirmError.value = ''
  try {
    await confirmPurchaseOrder(selected.value.poNo)
    confirmMessage.value = '采购单 ' + selected.value.poNo + ' 已确认；库存未变更，未生成发货表单。'
    selected.value = null
    await run(1)
    emit('confirmed', confirmMessage.value)
  } catch (e) {
    confirmError.value = e.response ? (e.message || '确认失败，请刷新后核对状态。') : '确认结果未知，请刷新核对；重试仍使用同一采购单号。'
  } finally { confirming.value = false }
}

const columns = [
  { key: 'poNo', label: '采购单号' },
  { key: 'skuCode', label: 'SKU' },
  { key: 'qty', label: '数量' },
  { key: 'status', label: '状态' },
  { key: 'manualConfirmRequired', label: '需人工确认' },
  { key: 'createdAt', label: '创建时间' },
  ...(props.canConfirm ? [{ key: 'action', label: '人工操作' }] : []),
]

const SIZE = 20

const result = ref(null)
const error = ref('')
const loading = ref(false)

const canPrev = computed(() => !!result.value && result.value.page > 1)
const hasNext = computed(
  () => !!result.value && result.value.page * result.value.size < result.value.total,
)

async function run(targetPage) {
  loading.value = true
  error.value = ''
  try {
    const response = await poPending({ page: targetPage, size: SIZE })
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
  if (value === null || value === undefined) {
    return '—'
  }
  return typeof value === 'boolean' ? (value ? '是' : '否') : value
}

onMounted(() => run(1))
</script>

<template>
  <div>
    <p class="notice" data-test="po-notice">
      {{ canConfirm ? '该列表只展示待人工确认（status=pending）的采购草稿；先核对单号、SKU 和数量，再独立确认。确认不增加库存，也不生成发货表单。' : '该列表只展示待人工确认（status=pending）的采购草稿；确认动作经后端 API 执行，本面板只读。' }}
    </p>

    <p v-if="error" class="error" data-test="po-error" role="alert">{{ error }}</p>
    <button type="button" class="refresh" :disabled="loading" data-test="po-refresh" @click="search">
      {{ loading ? '查询中…' : '刷新' }}
    </button>

    <ResultTable
      v-if="result"
      :columns="columns"
      :rows="result.items || []"
      :empty-text="'无待确认采购单'"
    >
      <template #cell="{ row, column }">
        <button v-if="column.key === 'action'" type="button" :disabled="confirming" :data-test="'po-review-' + row.poNo" @click="selected = row; confirmError = ''; confirmMessage = ''">核对并确认</button>
        <span v-else class="cell-text">{{ display(row[column.key]) }}</span>
      </template>
    </ResultTable>

    <div v-if="selected" class="confirmation" data-test="po-confirm-summary">
      <strong>确认采购意向</strong>
      <p>{{ selected.poNo }} · {{ selected.skuCode }} · {{ selected.qty }} 件</p>
      <p>该操作将草稿改为已确认采购意向，不代表到货入库。</p>
      <button type="button" :disabled="confirming" data-test="po-confirm-submit" @click="confirmSelected">{{ confirming ? '确认中…' : '确认这张采购单' }}</button>
      <button type="button" :disabled="confirming" data-test="po-confirm-cancel" @click="selected = null">取消</button>
    </div>
    <p v-if="confirmError" class="error" role="alert" data-test="po-confirm-error">{{ confirmError }}</p>
    <p v-if="confirmMessage" role="status" data-test="po-confirm-message">{{ confirmMessage }}</p>

    <template v-if="result">
      <p class="page-info" data-test="po-page-info">第 {{ result.page }} 页 · 共 {{ result.total }} 条</p>
      <div class="pager">
        <button type="button" :disabled="loading || !canPrev" data-test="po-prev" @click="prevPage">上一页</button>
        <button type="button" :disabled="loading || !hasNext" data-test="po-next" @click="nextPage">下一页</button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.confirmation { margin-top: 12px; padding: 12px; border: 1px solid #93c5fd; border-radius: 8px; font-size: 13px; }
.confirmation button, td button { padding: 6px 10px; border: 1px solid #cbd5e1; background: white; border-radius: 6px; margin-right: 6px; cursor: pointer; }
.notice {
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  color: #1e40af;
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 12px;
  margin: 0 0 10px;
}
.error {
  color: #dc2626;
  font-size: 13px;
  margin: 8px 0 0;
}
.refresh {
  margin-top: 10px;
  border: 0;
  background: #2563eb;
  color: #fff;
  border-radius: 8px;
  padding: 8px 16px;
  font-size: 13px;
  cursor: pointer;
}
.refresh:disabled {
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
