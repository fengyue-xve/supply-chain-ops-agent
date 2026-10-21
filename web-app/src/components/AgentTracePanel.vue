<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { agentTrace, cancelAgentRun } from '../api'
const props = defineProps({ requestId: { type: String, default: '' }, running: Boolean })
const emit = defineEmits(['cancelled'])
const trace = ref(null)
const message = ref('开始一次对话后，可查看本轮实际轨迹。')
let timer = null
let generation = 0
async function load(id, ticket) {
  try {
    const response = await agentTrace(id)
    if (ticket !== generation) return
    trace.value = response.data.data
    message.value = ''
  } catch (error) {
    if (ticket !== generation) return
    message.value = error.response?.status === 404 ? '正在等待本轮轨迹，或轨迹已过期。' : '轨迹暂不可用；对话结果仍以主流程为准。'
  }
  if (ticket === generation && props.running && !trace.value?.finished) timer = setTimeout(() => load(id, ticket), 300)
}
watch(() => [props.requestId, props.running], ([id]) => {
  clearTimeout(timer)
  const ticket = ++generation
  if (trace.value?.request_id !== id) trace.value = null
  if (id) void load(id, ticket)
}, { immediate: true })
onBeforeUnmount(() => { generation++; clearTimeout(timer) })
async function cancel() {
  if (!props.requestId) return
  try { await cancelAgentRun(props.requestId); emit('cancelled'); await load(props.requestId, generation) }
  catch { message.value = '取消状态未确认，请核对运行结果；不会自动重试业务写入。' }
}
</script>

<template>
  <aside class="agent-trace-panel" data-test="agent-trace-panel">
    <div class="trace-heading"><h3>Agent 运行轨迹与上下文</h3><button v-if="running && !trace?.finished" type="button" @click="cancel">取消本轮</button></div>
    <p v-if="message" role="status">{{ message }}</p>
    <template v-if="trace">
      <p data-test="trace-state"><strong>{{ trace.status }}</strong> · {{ trace.rounds_used }}/{{ trace.max_rounds }} 轮 · {{ trace.duration_ms }} ms</p>
      <p>停因：{{ trace.stop_reason || '运行中' }}</p>
      <h4>Prompt 拼装分层</h4>
      <ol class="context-layers"><li v-for="layer in trace.context_layers" :key="layer.kind"><strong>{{ layer.kind }}</strong> · {{ layer.messages }} 条 / {{ layer.characters }} 字符<br>{{ layer.description }}<small v-if="layer.chunk_ids?.length">{{ layer.chunk_ids.join(' · ') }}</small></li></ol>
      <h4>工具与阶段</h4>
      <ol class="trace-events"><li v-for="event in trace.events" :key="event.seq"><strong>{{ event.type }}</strong><span v-if="event.round"> · 轮次 {{ event.round }}</span><span v-if="event.tool"> · {{ event.tool }}</span><span v-if="event.schema_validation"> · Schema {{ event.schema_validation }}</span><span v-if="event.duration_ms != null"> · {{ event.duration_ms }} ms</span><small v-if="event.reason">{{ event.reason }}</small><small v-if="event.reference_ids?.length">引用 {{ event.reference_ids.join(' · ') }}</small></li></ol>
    </template>
    <small>仅展示上下文结构、规则结果与耗时；不展示历史正文、工具参数值或凭证。轨迹按当前用户凭证隔离，15分钟后失效。</small>
  </aside>
</template>

<style scoped>
.agent-trace-panel { min-width: 0; border: 1px solid #dce7e6; background: #fff; padding: 20px; border-radius: 14px; font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
.trace-heading { display: flex; align-items: center; gap: 12px; justify-content: space-between; }
h3 { margin: 0; color: #123c3a; font-size: 15px; } h4 { margin: 16px 0 8px; }
ol { padding-left: 20px; } li { margin: 7px 0; } small { display: block; color: #64817e; }
.trace-events { max-height: 340px; overflow: auto; } button { font-size: 12px; }
</style>
