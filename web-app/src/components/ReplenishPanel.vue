<script setup>
import { computed, ref } from 'vue'
import { suggestReplenish } from '../api'
import ResultTable from './ResultTable.vue'
import PurchaseDraftPanel from './PurchaseDraftPanel.vue'
import { readAuth } from '../utils/auth'

const emit = defineEmits(['created'])
const canPurchase = readAuth()?.role === 'admin'
const draftSeed = ref(null)
function canDraft(row) {
  return ['ready', 'reference'].includes(row.assessmentStatus) && Number.isInteger(row.suggestedQty) && row.suggestedQty > 0
}

const columns = [
  { key: 'sku', label: 'SKU' },
  { key: 'productName', label: '名称' },
  { key: 'currentQty', label: '现存库存' },
  { key: 'avgDailyQty', label: '日均销量' },
  { key: 'protectionDays', label: '保护期（天）' },
  { key: 'safetyQty', label: '安全库存' },
  { key: 'pkgQty', label: '箱规' },
  { key: 'rawGap', label: '原始缺口' },
  { key: 'suggestedQty', label: '整箱建议量' },
  { key: 'assessmentStatus', label: '评估状态' },
  { key: 'urgency', label: '紧急度' },
  { key: 'reason', label: '计算依据' },
  ...(canPurchase ? [{ key: 'purchase', label: '人工操作' }] : []),
]

// targetCoverDays 是包含交货提前期的总覆盖期；后端保护期至少覆盖提前期。
const mode = ref('skus')
const skusText = ref('')
const category = ref('')
const days = ref(7)
const coverDays = ref(14)
const quickCategories = ['audio', 'charging', 'accessory']

const result = ref(null)
const error = ref('')
const reqId = ref('')
const loading = ref(false)
const reviewCount = computed(() => (result.value?.items || [])
  .filter((item) => item.assessmentStatus === 'review_required').length)
const insufficientCount = computed(() => (result.value?.items || [])
  .filter((item) => item.assessmentStatus === 'insufficient_data').length)

function display(value) {
  return value === null || value === undefined || value === '' ? '—' : value
}

function assessmentLabel(status) {
  return ({ ready: '参考建议', reference: '参考建议', review_required: '需人工复核',
    insufficient_data: '数据不足' })[status] || '待核实'
}

function urgencyLabel(status) {
  return ({ out: '缺货（out）', urgent: '需关注（urgent）', normal: '一般（normal）',
    none: '无需补货（none）', unknown: '未知（unknown）' })[status] || display(status)
}

function dateOf(row, field) {
  const aliases = { stockSnapDate: 'inventoryDate', salesWindowStart: 'salesStartDate',
    salesWindowEnd: 'salesEndDate' }
  return display(row[field] ?? row[aliases[field]])
}

function parseSkuList(text) {
  return text
    .split(/[,，\n]+/)
    .map((item) => item.trim())
    .filter(Boolean)
}

function submit() {
  error.value = ''
  reqId.value = ''
  let payload = { days: days.value, targetCoverDays: coverDays.value }
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
    payload.skus = skus
  } else {
    if (!category.value.trim()) {
      error.value = '请输入类目'
      return
    }
    payload.category = category.value.trim()
  }
  run(payload)
}

async function run(payload) {
  loading.value = true
  try {
    const response = await suggestReplenish(payload)
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
    <p class="business-note" data-test="replenish-scope">
      采购意向测算：仅按现存库存计算，不含预留和在途。建议需人工核对日期与商品参数；确认采购也不代表到货入库。
    </p>
    <div class="mode">
      <button type="button" :class="{ active: mode === 'skus' }" @click="mode = 'skus'">按 SKU 计算</button>
      <button type="button" :class="{ active: mode === 'category' }" @click="mode = 'category'">按类目计算</button>
    </div>

    <div v-if="mode === 'skus'">
      <textarea v-model="skusText" rows="3" placeholder="多个 SKU 用逗号或换行分隔（最多 20 个）" data-test="skus" />
    </div>
    <div v-else>
      <input v-model="category" placeholder="类目，如 audio" data-test="category" />
      <div class="chips">
        <button v-for="item in quickCategories" :key="item" type="button" @click="category = item">{{ item }}</button>
      </div>
    </div>

    <div class="selects">
      <label>
        销量窗口
        <select v-model.number="days" data-test="days">
          <option :value="7">业务日回溯 7 天</option>
          <option :value="30">业务日回溯 30 天</option>
        </select>
      </label>
      <label>
        目标覆盖期（含提前期）
        <select v-model.number="coverDays" data-test="cover-days">
          <option :value="7">7 天</option>
          <option :value="14">14 天</option>
          <option :value="30">30 天</option>
        </select>
      </label>
    </div>

    <p class="formula" data-test="replenish-formula">
      保护期 = max(目标覆盖期, 交货提前期)，覆盖差值 = 保护期 − 提前期；目标库存向上取整（日均销量 × 保护期 + 安全库存），缺口再按箱规向上取整。
    </p>
    <p v-if="error" class="error" data-test="error" role="alert">{{ error }}</p>
    <button type="submit" :disabled="loading" data-test="query">{{ loading ? '计算中…' : '计算补货建议' }}</button>

    <div v-if="result" class="assessment-note" data-test="replenish-assessment" role="status">
      统计与库存日期见各商品计算依据。
      <span v-if="reviewCount">{{ reviewCount }} 项需人工复核。</span>
      <span v-if="insufficientCount">{{ insufficientCount }} 项数据不足，不给出采购数量。</span>
    </div>
    <ResultTable
      v-if="result"
      :columns="columns"
      :rows="result.items || []"
      :missing-skus="result.missingSkus || []"
      :empty-text="'无匹配记录'"
    >
      <template #cell="{ column, row }">
        <button v-if="column.key === 'purchase'" type="button" :disabled="!canDraft(row)" :data-test="'draft-add-' + row.sku" @click="draftSeed = { ...row }">带入采购表</button>
        <span v-else-if="column.key === 'urgency'" class="urgency" :class="row.urgency">{{ urgencyLabel(row.urgency) }}</span>
        <span v-else-if="column.key === 'assessmentStatus'" :class="['assessment', row.assessmentStatus]">{{ assessmentLabel(row.assessmentStatus) }}</span>
        <template v-else-if="column.key === 'reason'">
          <details :data-test="'basis-' + row.sku">
            <summary>查看依据</summary>
            <div class="reason">
              <p>库存日期：{{ dateOf(row, 'stockSnapDate') }}</p>
              <p>销量区间：{{ dateOf(row, 'salesWindowStart') }} 至 {{ dateOf(row, 'salesWindowEnd') }}</p>
              <p>提前期 {{ display(row.leadDays) }} 天 + 覆盖差值 {{ display(row.reviewDays) }} 天 = 保护期 {{ display(row.protectionDays) }} 天；请求覆盖期 {{ display(row.targetCoverDays) }} 天。</p>
              <p>目标库存 {{ display(row.targetQty) }}；原始缺口 {{ display(row.rawGap) }}；箱规 {{ display(row.pkgQty) }}；整箱建议 {{ display(row.suggestedQty) }}。</p>
              <p>{{ row.reason || '缺少计算依据，请核对后再决策。' }}</p>
            </div>
          </details>
        </template>
        <template v-else>{{ display(row[column.key]) }}</template>
      </template>
    </ResultTable>
    <p v-if="reqId" class="req-id" data-test="request-id">request_id: {{ reqId }}</p>
  </form>
  <PurchaseDraftPanel v-if="canPurchase" :suggestion="draftSeed" @created="emit('created')" />
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
input,
select {
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
.selects {
  display: flex;
  gap: 12px;
  margin-top: 10px;
  flex-wrap: wrap;
}
.selects label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
  flex: 1;
  min-width: 140px;
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
.urgency {
  padding: 1px 8px;
  border-radius: 999px;
  background: #6b7280;
  color: #fff;
  font-size: 12px;
}
.urgency.out,
.urgency.urgent {
  background: #dc2626;
}
.urgency.low {
  background: #d97706;
}
.reason {
  white-space: normal;
  width: 300px;
  line-height: 1.6;
}
.reason p {
  margin: 4px 0;
}
.business-note,
.assessment-note {
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  padding: 9px 10px;
  font-size: 12px;
  line-height: 1.6;
  color: #1e40af;
}
.business-note {
  margin: 0 0 12px;
}
.assessment-note {
  margin-top: 12px;
}
.formula {
  font-size: 12px;
  line-height: 1.6;
  color: #4b5563;
  margin: 8px 0 0;
}
.assessment.review_required,
.assessment.insufficient_data {
  color: #b45309;
}
.urgency.unknown {
  background: #6b7280;
}
.req-id {
  color: #6b7280;
  font-size: 12px;
  margin: 8px 0 0;
}
</style>
