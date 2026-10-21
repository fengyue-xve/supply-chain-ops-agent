<script setup>
import { ref } from 'vue'
import { querySales } from '../api'
import ResultTable from './ResultTable.vue'

const columns = [
  { key: 'sku', label: 'SKU' },
  { key: 'productName', label: '名称' },
  { key: 'category', label: '类目' },
  { key: 'days', label: '窗口（天）' },
  { key: 'businessWindow', label: '业务日期区间' },
  { key: 'totalQty', label: '总销量' },
  { key: 'avgDailyQty', label: '日均' },
  { key: 'series', label: '逐日明细' },
]

// 注意:query-sales 的 sku 是单数,一次只允许一个 SKU(Java QuerySalesRequest.sku)。
const mode = ref('sku')
const sku = ref('')
const category = ref('')
const days = ref(7)
const quickCategories = ['audio', 'charging', 'accessory']

const result = ref(null)
const error = ref('')
const reqId = ref('')
const loading = ref(false)

function submit() {
  error.value = ''
  reqId.value = ''
  let payload
  if (mode.value === 'sku') {
    const value = sku.value.trim()
    if (!value) {
      error.value = '请输入一个 SKU'
      return
    }
    if (/[,，\n\s]/.test(value)) {
      error.value = '销量查询只支持单个 SKU，请一次输入一个'
      return
    }
    payload = { sku: value, days: days.value }
  } else {
    if (!category.value.trim()) {
      error.value = '请输入类目'
      return
    }
    payload = { category: category.value.trim(), days: days.value }
  }
  run(payload)
}

async function run(payload) {
  loading.value = true
  try {
    const response = await querySales(payload)
    result.value = response.data.data
    const headerId = response.headers['x-request-id']
    reqId.value = typeof headerId === 'string' && headerId ? headerId : ''
  } catch (e) {
    result.value = null
    error.value = e.response ? (e.message || '查询失败') : '无法连接服务，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <form @submit.prevent="submit">
    <p class="date-note" data-test="sales-date-note">统计按数据源业务日回溯，实际区间以结果为准；不表示截至今天的实时销量。</p>
    <div class="mode">
      <button type="button" :class="{ active: mode === 'sku' }" @click="mode = 'sku'">按 SKU 查询</button>
      <button type="button" :class="{ active: mode === 'category' }" @click="mode = 'category'">按类目查询</button>
    </div>

    <div v-if="mode === 'sku'" class="query-field">
      <label for="sales-sku" class="field-label">商品 SKU <span>单个商品</span></label>
      <input id="sales-sku" v-model="sku" placeholder="单个 SKU，如 AUD-001" data-test="sku" />
    </div>
    <div v-else>
      <label for="sales-category" class="field-label">商品类目</label>
      <input id="sales-category" v-model="category" placeholder="类目，如 audio" data-test="category" />
      <div class="chips">
        <button v-for="item in quickCategories" :key="item" type="button" @click="category = item">{{ item }}</button>
      </div>
    </div>

    <div class="days">
      <span>统计窗口</span>
      <label><input v-model.number="days" type="radio" :value="7" /> 回溯 7 天</label>
      <label><input v-model.number="days" type="radio" :value="30" /> 回溯 30 天</label>
    </div>

    <p v-if="error" class="error" data-test="error" role="alert">{{ error }}</p>
    <button type="submit" :disabled="loading" data-test="query">{{ loading ? '查询中…' : '查询销量' }}</button>

    <ResultTable
      v-if="result"
      :columns="columns"
      :rows="result.items || []"
      :missing-skus="result.missingSkus || []"
      :empty-text="'所选业务窗口内无销量记录'"
    >
      <template #cell="{ column, row }">
        <details v-if="column.key === 'series'" :data-test="'series-' + row.sku">
          <summary>{{ (row.series || []).length }} 天</summary>
          <div class="series">{{ (row.series || []).map((p) => `${p.date}: ${p.qty}`).join('，') }}</div>
        </details>
        <template v-else-if="column.key === 'businessWindow'">
          {{ row.salesWindowStart || row.series?.[0]?.date || '—' }} 至 {{ row.salesWindowEnd || row.series?.[row.series.length - 1]?.date || '—' }}
        </template>
        <template v-else>{{ row[column.key] ?? '—' }}</template>
      </template>
    </ResultTable>
    <p v-if="reqId" class="req-id" data-test="request-id">request_id: {{ reqId }}</p>
  </form>
</template>

<style scoped>
.date-note {
  font-size: 12px;
  line-height: 1.6;
  color: #4b5563;
  margin: 0 0 10px;
}
.mode {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}
.mode button {
  border: 1px solid #d1d5db;
  background: #fff;
  border-radius: 8px;
  padding: 5px 12px;
  font-size: 13px;
  cursor: pointer;
}
.mode button.active {
  background: #2563eb;
  border-color: #2563eb;
  color: #fff;
}
input {
  width: 100%;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 13px;
  font-family: inherit;
}
.chips {
  display: flex;
  gap: 6px;
  margin-top: 8px;
  flex-wrap: wrap;
}
.chips button {
  border: 1px solid #d1d5db;
  background: #f9fafb;
  border-radius: 999px;
  padding: 3px 10px;
  font-size: 12px;
  cursor: pointer;
}
.days {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-top: 10px;
  font-size: 13px;
  flex-wrap: wrap;
}
.days input {
  width: auto;
  flex: 0 0 auto;
}
.days label {
  white-space: nowrap;
  display: inline-flex;
  align-items: center;
  gap: 4px;
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
.series {
  white-space: normal;
  max-width: 280px;
}
.req-id {
  color: #6b7280;
  font-size: 12px;
  margin: 8px 0 0;
}
</style>
