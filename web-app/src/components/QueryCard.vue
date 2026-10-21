<script setup>
// 查询面板通用外壳:标题 + 内容区(表单/结果由各面板自管)
</script>

<template>
  <section class="card">
    <div class="card-header"><div><h3 class="card-title"><slot name="title" /></h3><p v-if="$slots.description" class="card-description"><slot name="description" /></p></div><span class="card-detail" aria-hidden="true"><svg viewBox="0 0 20 20"><path d="M4 6h12M4 10h12M4 14h8"/></svg></span></div>
    <div class="card-body"><slot /></div>
  </section>
</template>

<style scoped>
.card { background: #fff; border: 1px solid #e3e8ef; border-radius: 12px; min-width: 0; box-shadow: 0 2px 4px #25385303; scroll-margin-top: 22px; overflow: hidden; }
.card-header { padding: 19px 20px 17px; display: flex; gap: 12px; justify-content: space-between; align-items: center; border-bottom: 1px solid #eef1f5; }
.card-title { margin: 0; font-size: 14px; font-weight: 600; color: #2b3c52; }
.card-description { margin: 6px 0 0; color: #9aa6b5; font-size: 11px; line-height: 1.7; }
.card-detail { color: #aab8c7; flex: 0 0 auto; }
.card-detail svg { width: 18px; height: 18px; fill: none; stroke: currentColor; stroke-width: 1.4; stroke-linecap: round; }
.card-body { padding: 19px 20px 20px; min-width: 0; }
@media(max-width: 760px) { .card-header { padding: 16px; } .card-body { padding: 16px; } }
</style>
