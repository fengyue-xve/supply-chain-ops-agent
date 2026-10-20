<script setup>
import { ref } from 'vue'
import { queryStockLevel } from '../api'
import ResultTable from './ResultTable.vue'

const columns = [
  { key: 'sku', label: 'SKU' },
  { key: 'productName', label: '名称' },
  { key: 'category', label: '类目' },
  { key: 'availableQty', label: '可售数量' },
  { key: 'stockLevel', label: '库存档位' },
  { key: 'snapDate', label: '快照日期' },
]

// skus(camelCase 复数)与 category 二选一,与 Java QueryStockLevelRequest 一致
const mode = ref('skus')
const skusText = ref('')
const category = ref('')
const quickCategories = ['audio', 'charging', 'accessory']

const result = ref(null)
const error = ref('')
const reqId = ref('')
const loading = ref(false)

function parseSkuList(text) {
  return text
    .split(/[,，\n]+/)
    .map((item) => item.trim())
    .filter(Boolean)
}

function submit() {
  error.value = ''
  reqId.value = ''
  let payload
  if (mode.value === 'skus') {
    const skus = parseSkuList(skusText.value)
    if (!skus.length) {
      error.value = '请输入至少一个 SKU（用逗号或换行分隔）'
      return
    }
    if (skus.length > 20) {
      error.value = 'skus 数量不能超过 20'
      return
    }
    payload = { skus }
  } else {
    if (!category.value.trim()) {
      error.value = '请输入类目'
      return
    }
    payload = { category: category.value.trim() }
  }
  run(payload)
}

async function run(payload) {
  loading.value = true
  try {
    const response = await queryStockLevel(payload)
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
    <div class="mode">
      <button type="button" :class="{ active: mode === 'skus' }" @click="mode = 'skus'">按 SKU 查询</button>
      <button type="button" :class="{ active: mode === 'category' }" @click="mode = 'category'">按类目查询</button>
    </div>

    <div v-if="mode === 'skus'">
      <textarea v-model="skusText" rows="3" placeholder="多个 SKU 用逗号或换行分隔（最多 20 个），如 AUD-001, AUD-002" data-test="skus" />
    </div>
    <div v-else>
      <input v-model="category" placeholder="类目，如 audio" data-test="category" />
      <div class="chips">
        <button v-for="item in quickCategories" :key="item" type="button" @click="category = item">{{ item }}</button>
      </div>
    </div>

    <p v-if="error" class="error" data-test="error" role="alert">{{ error }}</p>
    <button type="submit" :disabled="loading" data-test="query">{{ loading ? '查询中…' : '查询库存' }}</button>

    <ResultTable
      v-if="result"
      :columns="columns"
      :rows="result.items || []"
      :missing-skus="result.missingSkus || []"
      :empty-text="'无匹配记录'"
    />
    <p v-if="reqId" class="req-id" data-test="request-id">request_id: {{ reqId }}</p>
  </form>
</template>

<style scoped>
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
textarea,
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
.req-id {
  color: #6b7280;
  font-size: 12px;
  margin: 8px 0 0;
}
</style>
