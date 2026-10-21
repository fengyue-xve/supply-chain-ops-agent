<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { readAuth, clearAuth } from '../utils/auth'
import QueryCard from '../components/QueryCard.vue'
import StockQueryPanel from '../components/StockQueryPanel.vue'
import SalesQueryPanel from '../components/SalesQueryPanel.vue'
import ReplenishPanel from '../components/ReplenishPanel.vue'
import PoPanel from '../components/PoPanel.vue'
import AuditPanel from '../components/AuditPanel.vue'
import ChatPanel from '../components/ChatPanel.vue'
import FormWorkflowPanel from '../components/FormWorkflowPanel.vue'
import { http } from '../api/http'

const router = useRouter()
const auth = readAuth()
const purchaseRevision = ref(0)
const purchaseStatus = ref('')
const resetEpoch = ref(0)
const resetting = ref(false)
async function resetDemo() {
  if (!window.confirm('恢复本机合成运营与表单基线，并清除本用户浏览器中的旧采购恢复请求；审计保留。继续？')) return
  resetting.value = true
  try {
    const response = await http.post('/demo/reset', { confirmed: true })
    localStorage.removeItem('sca_purchase_attempt:' + auth.username)
    sessionStorage.removeItem('sca_conv:' + auth.username)
    purchaseStatus.value = response.data.data.message
    resetEpoch.value++
    purchaseRevision.value++
  } catch (error) { purchaseStatus.value = error.message || '重置结果未确认，请核对业务状态。' }
  finally { resetting.value = false }
}
function purchaseChanged(message) {
  purchaseRevision.value++
  if (typeof message === 'string') purchaseStatus.value = message
}

function logout() {
  clearAuth()
  router.push('/login')
}
</script>

<template>
  <div class="workbench">
    <aside class="sidebar" aria-label="工作台导航">
      <a href="#overview" class="brand">
        <span class="brand-mark" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="m12 3 9 5-9 5-9-5 9-5Z"/><path d="m3 12 9 5 9-5M3 16l9 5 9-5"/></svg></span>
        <span><strong>供应链运营</strong><small>SUPPLY CHAIN / AGENT</small></span>
      </a>
      <div class="nav-label">运营工作空间</div>
      <nav class="side-nav">
        <a href="#overview" class="nav-overview"><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/></svg>运营概览<span class="nav-dot"></span></a>
        <a href="#operating-data"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 20V10m8 10V4m8 16v-7M2 20h20"/></svg>经营数据</a>
        <a href="#replenishment"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M7 3v8m10-8v8M4 17h16m-8-4v8"/></svg>补货测算</a>
        <a v-if="auth?.role === 'admin'" href="#purchases"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 5H5v16h14V5h-4M9 3h6v4H9zM8 12h8m-8 4h5"/></svg>采购复核</a>
        <a href="#preparation"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 8 4v10l-8 4-8-4V7l8-4Zm0 8v10M4 7l8 4 8-4M8 5l8 4"/></svg>表单核验</a>
        <a v-if="auth?.role === 'admin'" href="#audit"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 4H5v17h14V4h-4M9 3h6v4H9zM8 12h8m-8 4h8"/></svg>操作审计</a>
        <a href="#agent-assistant"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 4h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H9l-6 4V6a2 2 0 0 1 2-2Z"/><path d="M8 9h8m-8 4h5"/></svg>Agent 助手</a>
      </nav>
      <div class="sidebar-note"><span class="note-line"></span><strong>让每一步决策有据可查</strong><p>基于业务快照计算<br />由人工确认采购意向</p></div>
      <div class="sidebar-foot"><span class="workspace-icon">SC</span><div>个人演示工作空间<small>供应链智能运营 Agent</small></div></div>
    </aside>

    <div class="workspace-body">
      <header class="topbar">
        <div class="breadcrumb"><span>工作空间</span><svg viewBox="0 0 16 16" aria-hidden="true"><path d="m6 4 4 4-4 4"/></svg><strong>运营工作台</strong></div>
        <div class="user-info"><span class="user-avatar" aria-hidden="true">{{ (auth?.username || 'U').slice(0, 1).toUpperCase() }}</span><span class="user-name">{{ auth?.username || '' }}</span><span class="role-badge" :class="auth?.role" data-test="role-badge">{{ auth?.role === 'admin' ? '管理员' : '查看者' }}</span></div>
        <button v-if="auth?.role === 'admin'" class="logout" data-test="reset-demo" :disabled="resetting" @click="resetDemo">重置演示数据</button>
        <button class="logout" data-test="logout" @click="logout">退出登录</button>
      </header>

      <div id="overview" class="workspace-content">
        <div class="page-heading"><div><div class="eyebrow">OPERATIONS WORKBENCH</div><h1>供应链运营工作台</h1><p>从经营事实出发，完成补货测算与采购复核。</p></div><span class="environment-label"><i></i>业务快照演示</span></div>

        <div class="business-flow" data-test="business-flow">
          <div class="flow-title"><span class="flow-icon" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M4 12h16m-6-6 6 6-6 6"/></svg></span><div><strong>补货调查 → 采购意向 → 人工确认</strong><span>先核对库存与销量日期，再按商品提前期、安全库存和箱规计算；采购确认不增加库存。</span></div></div>
          <span class="flow-tag">人工决策闭环</span>
        </div>
        <p v-if="purchaseStatus" class="purchase-status" role="status" data-test="purchase-status">{{ purchaseStatus }}</p>

        <main :key="resetEpoch" class="layout">
          <section class="col-left">
            <section id="operating-data" class="section-group">
              <div class="section-heading"><span class="section-number">01</span><div><h2>经营数据核对</h2><p>先确认库存快照与销量窗口，再进入补货测算。</p></div></div>
              <div class="query-grid">
                <QueryCard><template #title>库存查询</template><template #description>按商品或类目核对现存库存</template><StockQueryPanel /></QueryCard>
                <QueryCard><template #title>销量查询</template><template #description>回溯业务日期内的销量明细</template><SalesQueryPanel /></QueryCard>
              </div>
            </section>

            <section id="replenishment" class="section-group">
              <div class="section-heading"><span class="section-number">02</span><div><h2>补货决策与采购意向</h2><p>参考建议提供计算依据，采购数量由人工独立复核。</p></div></div>
              <QueryCard><template #title>补货建议</template><template #description>库存、提前期、安全库存与箱规共同计算</template><ReplenishPanel @created="purchaseChanged" /></QueryCard>
            </section>

            <!-- 写操作需人工复核，Java 独立校验权限与业务状态。 -->
            <QueryCard v-if="auth?.role === 'admin'" id="purchases"><template #title>待确认采购单</template><template #description>逐单核对采购意向，确认操作不改变库存</template><PoPanel :key="'po-' + purchaseRevision" :can-confirm="auth?.role === 'admin'" @confirmed="purchaseChanged" /></QueryCard>
            <QueryCard id="preparation"><template #title>发货准备表单（只读）</template><template #description>独立核验表单准备状态与执行记录</template><FormWorkflowPanel /></QueryCard>
            <!-- 审计时间线仅 admin 渲染，后端独立校验权限。 -->
            <QueryCard v-if="auth?.role === 'admin'" id="audit"><template #title>审计时间线</template><template #description>追溯已发生的状态变更与关联请求</template><AuditPanel :key="'audit-' + purchaseRevision" /></QueryCard>
          </section>
          <section id="agent-assistant" class="col-right"><ChatPanel /><div class="assistant-note"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6l8-3Z"/><path d="m8 12 3 3 5-6"/></svg><span>对话用于调查与解释。采购动作请在业务面板中核对确认。</span></div></section>
        </main>
        <footer class="page-footer">供应链智能运营 Agent <span>业务快照有日期 · 采购意向需复核 · 确认不等于入库</span></footer>
      </div>
    </div>
  </div>
</template>

<style scoped>
.workbench { min-height: 100vh; }
.sidebar { width: 232px; position: fixed; inset: 0 auto 0 0; display: flex; flex-direction: column; padding: 26px 16px 18px; background: #111c32; color: #d4dfed; z-index: 20; overflow-y: auto; }
.brand { display: flex; align-items: center; gap: 11px; padding: 0 7px; color: white; text-decoration: none; }
.brand-mark { width: 37px; height: 39px; display: grid; place-items: center; background: #0f766e; border: 1px solid #2b948b; border-radius: 10px; }
.brand-mark svg { width: 25px; height: 25px; }
.brand strong { display: block; font-size: 17px; letter-spacing: 1px; }
.brand small { display: block; font-size: 11px; letter-spacing: 1.2px; margin-top: 5px; color: #8eabb9; }
.nav-label { margin: 38px 12px 12px; font-size: 11px; color: #74849e; letter-spacing: 1px; }
.side-nav { display: flex; flex-direction: column; gap: 5px; }
.side-nav a { display: flex; align-items: center; gap: 12px; min-height: 45px; padding: 10px 13px; color: #a9b9ce; font-size: 13px; border-radius: 7px; text-decoration: none; transition: background .15s, color .15s; }
.side-nav a:hover { background: #1d2d45; color: white; }
.side-nav svg { width: 19px; height: 19px; fill: none; stroke: currentColor; stroke-width: 1.6; stroke-linecap: round; stroke-linejoin: round; flex: 0 0 auto; }
.side-nav .nav-overview { background: #203c46; color: #6ee7c7; }
.nav-dot { width: 5px; height: 5px; border-radius: 50%; background: #5cdbbd; margin-left: auto; }
.sidebar-note { margin: 32px 12px 30px; padding-top: 20px; border-top: 1px solid #28344b; color: #8192aa; }
.note-line { width: 22px; height: 2px; display: block; margin-bottom: 14px; background: #39bda4; }
.sidebar-note strong { font-size: 12px; color: #c5d4e4; font-weight: 500; }
.sidebar-note p { font-size: 11px; line-height: 1.9; margin-bottom: 0; }
.sidebar-foot { display: flex; align-items: center; gap: 10px; padding: 17px 8px 0; border-top: 1px solid #28344b; margin-top: auto; font-size: 11px; color: #d0dbe8; }
.workspace-icon { display: grid; place-items: center; width: 31px; height: 31px; border-radius: 7px; background: #29354d; color: #a8c7ce; font-size: 11px; letter-spacing: .5px; }
.sidebar-foot small { display: block; color: #74849e; margin-top: 5px; font-size: 11px; }
.workspace-body { margin-left: 232px; min-width: 0; }
.topbar { min-height: 74px; padding: 16px 32px; background: #fff; border-bottom: 1px solid #e6eaf0; display: flex; gap: 16px; align-items: center; }
.breadcrumb { display: flex; align-items: center; gap: 11px; font-size: 12px; color: #8893a5; }
.breadcrumb svg { width: 13px; height: 13px; fill: none; stroke: #aeb7c4; stroke-width: 1.5; }
.breadcrumb strong { color: #34435a; font-weight: 500; }
.user-info { margin-left: auto; display: flex; align-items: center; gap: 9px; font-size: 12px; }
.user-avatar { height: 31px; width: 31px; display: grid; place-items: center; background: #e9f4f1; color: #0f766e; font-weight: 600; border-radius: 50%; }
.user-name { color: #334155; }
.role-badge { font-size: 11px; font-weight: 500; padding: 3px 7px; border: 1px solid #dce5ee; background: #f3f6f9; color: #617389; border-radius: 5px; }
.role-badge.admin { color: #0f766e; background: #effaf6; border-color: #cde8dc; }
.logout { border: 1px solid #e1e6ed; color: #64748b; background: white; padding: 6px 10px; border-radius: 6px; font-size: 11px; cursor: pointer; }
.logout:hover { border-color: #94a3b8; color: #334155; }
.workspace-content { padding: 28px 32px 0; max-width: 1740px; margin: auto; }
.page-heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin-bottom: 24px; }
.eyebrow { color: #91a0b3; font-size: 11px; font-weight: 600; letter-spacing: 2px; margin-bottom: 7px; }
h1 { color: #1b2a40; font-size: 25px; line-height: 1.4; letter-spacing: -.5px; margin: 0; font-weight: 650; }
.page-heading p { color: #7b889b; margin: 8px 0 0; font-size: 12px; }
.environment-label { display: flex; align-items: center; gap: 7px; white-space: nowrap; font-size: 11px; color: #5e7187; border: 1px solid #e1e7ef; padding: 7px 10px; border-radius: 6px; background: white; }
.environment-label i { width: 5px; height: 5px; border-radius: 50%; background: #23a68b; }
.business-flow { display: flex; justify-content: space-between; gap: 16px; align-items: center; border: 1px solid #d8e7e4; background: #f0f8f6; border-radius: 9px; padding: 15px 18px; margin-bottom: 26px; }
.flow-title { display: flex; gap: 13px; align-items: center; }
.flow-icon { width: 33px; height: 33px; border-radius: 8px; background: #deeeea; color: #0f766e; display: grid; place-items: center; flex: 0 0 auto; }
.flow-icon svg { width: 19px; height: 19px; fill: none; stroke: currentColor; stroke-width: 1.6; }
.flow-title strong { display: block; font-size: 12px; color: #1b625a; font-weight: 600; }
.flow-title span:not(.flow-icon) { display: block; font-size: 11px; line-height: 1.8; color: #6a827d; margin-top: 4px; }
.flow-tag { white-space: nowrap; border: 1px solid #d5e6e0; color: #598277; background: #fff; padding: 5px 8px; font-size: 11px; border-radius: 5px; }
.purchase-status { padding: 12px 16px; color: #166534; background: #ecfdf5; border: 1px solid #bbf7d0; border-radius: 8px; font-size: 12px; }
.layout { display: grid; grid-template-columns: minmax(0, 1fr) 330px; gap: 24px; align-items: start; }
.col-left { display: flex; flex-direction: column; gap: 23px; min-width: 0; }
.col-right { min-width: 0; position: sticky; top: 20px; scroll-margin-top: 20px; }
.section-group { min-width: 0; scroll-margin-top: 22px; }
.section-heading { display: flex; gap: 9px; align-items: center; margin-bottom: 14px; }
.section-number { font-size: 11px; line-height: 24px; width: 25px; text-align: center; background: #e8edf4; color: #7a899d; border-radius: 6px; font-weight: 600; }
.section-heading h2 { margin: 0; color: #34435a; font-size: 14px; font-weight: 600; }
.section-heading p { margin: 4px 0 0; color: #8a97a7; font-size: 11px; }
.query-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 18px; }
.assistant-note { display: flex; align-items: flex-start; gap: 7px; color: #94a1b2; line-height: 1.8; font-size: 11px; padding: 13px 8px; }
.assistant-note svg { width: 14px; height: 14px; stroke: #8399a9; fill: none; stroke-width: 1.6; flex: 0 0 auto; margin-top: 2px; }
.page-footer { padding: 26px 0 22px; color: #9ba7b6; font-size: 11px; display: flex; gap: 12px; justify-content: space-between; }
@media(min-width: 1650px) { .layout { grid-template-columns: minmax(0, 1fr) 360px; } }
@media(max-width: 1300px) { .workspace-content { padding: 24px 24px 0; } .topbar { padding-left: 24px; padding-right: 24px; } .layout { grid-template-columns: minmax(0, 1fr) 300px; gap: 18px; } .query-grid { grid-template-columns: minmax(0, 1fr); gap: 16px; } }
@media(max-width: 1050px) { .sidebar { width: 204px; padding-left: 12px; padding-right: 12px; } .workspace-body { margin-left: 204px; } .layout { grid-template-columns: minmax(0, 1fr); } .query-grid { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); } .col-right { position: static; } .flow-tag { display: none; } }
@media(max-width: 760px) { .sidebar { position: static; width: 100%; padding: 16px 18px 0; overflow: visible; } .brand { padding: 0; } .brand-mark { width: 31px; height: 33px; } .brand strong { font-size: 15px; } .brand small { font-size: 11px; } .nav-label, .sidebar-note, .sidebar-foot { display: none; } .side-nav { flex-direction: row; gap: 4px; overflow-x: auto; margin-top: 16px; padding-bottom: 10px; } .side-nav a { white-space: nowrap; min-height: 34px; padding: 7px 10px; font-size: 11px; gap: 6px; } .side-nav svg { width: 15px; height: 15px; } .nav-dot { display: none; } .workspace-body { margin-left: 0; } .topbar { min-height: 58px; padding: 12px 18px; gap: 10px; } .user-info { gap: 6px; } .user-avatar { display: none; } .breadcrumb { font-size: 11px; gap: 6px; } .breadcrumb span { display: none; } .breadcrumb svg { display: none; } .logout { font-size: 11px; padding: 5px 8px; } .workspace-content { padding: 22px 16px 0; } .page-heading { align-items: flex-start; margin-bottom: 18px; } h1 { font-size: 22px; } .page-heading p { max-width: 215px; line-height: 1.8; font-size: 11px; } .environment-label { padding: 5px 6px; font-size: 11px; margin-top: 20px; } .business-flow { padding: 13px; margin-bottom: 22px; } .flow-title { align-items: flex-start; gap: 9px; } .flow-icon { width: 26px; height: 26px; } .flow-title strong { font-size: 11px; line-height: 1.7; } .flow-title span:not(.flow-icon) { font-size: 11px; } .query-grid { grid-template-columns: minmax(0, 1fr); gap: 16px; } .layout { gap: 22px; } .page-footer { flex-direction: column; gap: 6px; line-height: 1.7; } }
</style>

<style scoped>
.layout { grid-template-columns:minmax(0,1fr); }
.col-right { min-width:0; }
.topbar { flex-wrap:wrap; }
</style>
