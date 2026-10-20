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

const router = useRouter()
const auth = readAuth()
const purchaseRevision = ref(0)
const purchaseStatus = ref('')
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
    <header class="topbar">
      <span class="app-name">供应链智能运营 Agent</span>
      <span class="user-info">
        {{ auth?.username || '' }}
        <span class="role-badge" :class="auth?.role" data-test="role-badge">
          {{ auth?.role === 'admin' ? '管理员' : '查看者' }}
        </span>
      </span>
      <button class="logout" data-test="logout" @click="logout">退出登录</button>
    </header>
    <div class="business-flow" data-test="business-flow">
      <strong>补货调查 → 采购意向 → 人工确认</strong>
      <span>先核对库存与销量日期，再按商品提前期、安全库存和箱规计算；采购确认不增加库存。</span>
    </div>
    <p v-if="purchaseStatus" class="business-flow" role="status" data-test="purchase-status">{{ purchaseStatus }}</p>
    <main class="layout">
      <section class="col-left">
        <QueryCard><template #title>库存查询</template><StockQueryPanel /></QueryCard>
        <QueryCard><template #title>销量查询</template><SalesQueryPanel /></QueryCard>
        <QueryCard><template #title>补货建议</template><ReplenishPanel @created="purchaseChanged" /></QueryCard>
        <!-- 写操作需人工复核，Java 独立校验权限与业务状态。 -->
        <QueryCard v-if="auth?.role === 'admin'">
          <template #title>待确认采购单</template>
          <PoPanel :key="'po-' + purchaseRevision" :can-confirm="auth?.role === 'admin'" @confirmed="purchaseChanged" />
        </QueryCard>
        <!-- W3-④ 审计时间线:仅 admin 渲染(后端独立 403,这里只是体验层) -->
        <QueryCard v-if="auth?.role === 'admin'">
          <template #title>审计时间线</template>
          <AuditPanel :key="'audit-' + purchaseRevision" />
        </QueryCard>
        <QueryCard><template #title>发货准备表单（只读）</template><FormWorkflowPanel /></QueryCard>
      </section>
      <section class="col-right">
        <ChatPanel />
      </section>
    </main>
  </div>
</template>

<style scoped>
.business-flow {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  margin: 16px auto 0;
  padding: 10px 16px;
  max-width: 1288px;
  border-radius: 8px;
  background: #eff6ff;
  color: #1e40af;
  font-size: 13px;
  line-height: 1.6;
}
.business-flow span {
  color: #475569;
}
.topbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 20px;
  height: 52px;
  background: #111827;
  color: #fff;
}
.app-name {
  font-weight: 600;
}
.user-info {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}
.role-badge {
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 12px;
  background: #374151;
}
.role-badge.admin {
  background: #1d4ed8;
}
.logout {
  border: 1px solid #4b5563;
  background: transparent;
  color: #fff;
  border-radius: 8px;
  padding: 5px 12px;
  font-size: 13px;
  cursor: pointer;
}
.layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 440px);
  gap: 16px;
  max-width: 1320px;
  margin: 0 auto;
  padding: 16px;
  align-items: start;
}
.col-left {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}
.col-right {
  min-width: 0;
  position: sticky;
  top: 16px;
}
@media (max-width: 520px) {
  .topbar {
    height: auto;
    min-height: 52px;
    flex-wrap: wrap;
    padding: 10px 16px;
    gap: 8px;
  }
  .app-name {
    flex-basis: 100%;
  }
  .user-info {
    margin-left: 0;
    white-space: nowrap;
  }
  .logout {
    margin-left: auto;
    white-space: nowrap;
  }
}
@media (max-width: 980px) {
  .layout {
    grid-template-columns: minmax(0, 1fr);
  }
  .col-right {
    position: static;
  }
}
</style>
