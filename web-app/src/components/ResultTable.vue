<script setup>
defineProps({
  columns: { type: Array, required: true },
  rows: { type: Array, default: () => [] },
  missingSkus: { type: Array, default: () => [] },
  emptyText: { type: String, default: '无匹配记录' },
})
</script>

<template>
  <div class="result">
    <div v-if="missingSkus.length" class="missing" data-test="missing-skus">
      以下 SKU 未找到：{{ missingSkus.join('、') }}
    </div>
    <p v-if="!rows.length" class="empty">{{ emptyText }}</p>
    <div v-else class="table-wrap">
      <table>
        <thead>
          <tr>
            <th v-for="column in columns" :key="column.key">{{ column.label }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in rows" :key="index">
            <td v-for="column in columns" :key="column.key">
              <slot name="cell" :column="column" :row="row">{{ row[column.key] }}</slot>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.result {
  margin-top: 12px;
}
.missing {
  background: #fef3c7;
  border: 1px solid #fcd34d;
  color: #92400e;
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 13px;
  margin-bottom: 10px;
}
.empty {
  color: #6b7280;
  font-size: 13px;
  margin: 8px 0;
}
.table-wrap {
  overflow-x: auto;
}
table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  min-width: 560px;
}
th,
td {
  border-bottom: 1px solid #e5e7eb;
  padding: 7px 8px;
  text-align: left;
  vertical-align: top;
  white-space: nowrap;
}
</style>
