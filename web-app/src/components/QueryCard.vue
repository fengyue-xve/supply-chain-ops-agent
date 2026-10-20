<script setup>
// 查询面板通用外壳:标题 + 内容区(表单/结果由各面板自管)
</script>

<template>
  <section class="card">
    <h3 class="card-title"><slot name="title" /></h3>
    <div class="card-body"><slot /></div>
  </section>
</template>

<style scoped>
.card {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  padding: 16px;
  min-width: 0;
}
.card-title {
  margin: 0 0 12px;
  font-size: 15px;
}
</style>
