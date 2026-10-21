<script setup>
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { login } from '../api'
import { saveAuth, safeRedirect } from '../utils/auth'

const router = useRouter()
const route = useRoute()

const username = ref('')
const password = ref('')
const fieldError = ref('')
const error = ref('')
const loading = ref(false)

async function submit() {
  fieldError.value = ''
  error.value = ''
  const name = username.value.trim()
  if (!name) {
    fieldError.value = '请输入用户名'
    return
  }
  if (!password.value) {
    fieldError.value = '请输入密码'
    return
  }
  loading.value = true
  try {
    const response = await login(name, password.value)
    const data = response.data.data
    saveAuth({ token: data.token, username: data.username, role: data.role })
    router.push(safeRedirect(route.query.redirect))
  } catch (e) {
    // 登录接口 401 = 凭证错,只在本页提示;无 response = 网络/服务不可达
    error.value = e.response ? (e.message || '登录失败') : '无法连接服务，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <aside class="login-story">
      <div class="login-brand"><span><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 9 5-9 5-9-5 9-5Zm-9 9 9 5 9-5M3 16l9 5 9-5"/></svg></span><div>供应链运营<small>SUPPLY CHAIN / AGENT</small></div></div>
      <div class="story-content"><div class="story-eyebrow">OPERATIONS, WITH EVIDENCE.</div><h2>每一步决策，<br />都有业务依据。</h2><p>连接库存、销量与采购意向，<br />让调查更清楚，让复核更从容。</p><div class="story-flow"><div><span>01</span><strong>核对事实</strong><small>库存快照 · 销量窗口</small></div><div><span>02</span><strong>计算建议</strong><small>提前期 · 安全库存 · 箱规</small></div><div><span>03</span><strong>人工复核</strong><small>独立确认 · 过程可追溯</small></div></div></div>
      <div class="story-footer"><span></span> 基于业务快照，保留人工决策</div>
    </aside>
    <main class="login-main">
      <div class="login-topline">供应链智能运营 Agent <span>个人演示工作空间</span></div>
      <form class="login-card" @submit.prevent="submit">
        <span class="login-eyebrow">WELCOME TO YOUR WORKSPACE</span><h1>登录运营工作台</h1><p class="login-intro">使用工作空间账号，继续你的业务调查。</p>
        <label><span>用户名</span><input v-model="username" autocomplete="username" placeholder="请输入用户名" data-test="username" /></label>
        <label><span>密码</span><input v-model="password" type="password" autocomplete="current-password" placeholder="请输入密码" data-test="password" /></label>
        <p v-if="fieldError" class="error" data-test="field-error" role="alert">{{ fieldError }}</p><p v-if="error" class="error" data-test="error" role="alert">{{ error }}</p>
        <button type="submit" :disabled="loading" data-test="submit">{{ loading ? '登录中…' : '登录' }}<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 12h16m-6-6 6 6-6 6"/></svg></button>
        <div class="login-note"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6l8-3Z"/><path d="m8 12 3 3 5-6"/></svg>账号权限决定可查询与可操作的业务范围</div>
      </form>
      <footer class="login-footer">供应链智能运营 Agent <span>调查有依据，采购需复核。</span></footer>
    </main>
  </div>
</template>

<style scoped>
.login-page { min-height: 100vh; display: grid; grid-template-columns: minmax(360px, 43%) minmax(0, 1fr); background: white; }
.login-story { background: #111c32; color: white; padding: 42px 12%; display: flex; flex-direction: column; min-height: 100vh; }
.login-brand { display: flex; align-items: center; gap: 12px; font-size: 17px; font-weight: 600; letter-spacing: 1px; }
.login-brand > span { display: grid; place-items: center; width: 40px; height: 42px; background: #0f766e; border: 1px solid #278e84; border-radius: 10px; }
.login-brand svg { width: 27px; height: 27px; stroke: #e1fff6; fill: none; stroke-width: 1.6; }
.login-brand small { display: block; font-size: 11px; letter-spacing: 1.2px; color: #8fa9b8; margin-top: 6px; }
.story-content { margin: auto 0; padding: 70px 0; }
.story-eyebrow { font-size: 11px; letter-spacing: 2px; color: #59bda7; }
.story-content h2 { font-size: clamp(30px, 3.2vw, 46px); font-weight: 500; letter-spacing: -1px; line-height: 1.55; margin: 22px 0; }
.story-content > p { color: #95a7be; font-size: 13px; line-height: 2; }
.story-flow { margin-top: 46px; display: flex; flex-direction: column; gap: 0; }
.story-flow > div { display: grid; grid-template-columns: 32px 1fr; column-gap: 12px; padding-bottom: 26px; position: relative; }
.story-flow > div:not(:last-child)::after { content: ''; height: 24px; width: 1px; background: #31435b; position: absolute; left: 15px; top: 33px; }
.story-flow span { grid-row: span 2; background: #203a46; color: #62c8b0; width: 31px; height: 32px; display: grid; place-items: center; border-radius: 7px; font-size: 11px; }
.story-flow strong { color: #d9e4f0; font-size: 12px; font-weight: 500; }
.story-flow small { font-size: 11px; color: #778da6; margin-top: 5px; }
.story-footer { display: flex; align-items: center; gap: 7px; color: #68839d; font-size: 11px; }
.story-footer span { width: 5px; height: 5px; border-radius: 50%; background: #3da991; }
.login-main { display: flex; flex-direction: column; padding: 40px 8%; min-width: 0; }
.login-topline { display: flex; justify-content: space-between; gap: 12px; color: #64748b; font-size: 11px; }
.login-topline span { color: #a1aaba; font-size: 11px; }
.login-card { width: 100%; max-width: 365px; margin: auto; padding: 70px 0; }
.login-eyebrow { font-size: 11px; letter-spacing: 1.5px; color: #94a3b8; }
.login-card h1 { font-size: 28px; color: #1e2d43; font-weight: 600; letter-spacing: -.6px; margin: 14px 0 9px; }
.login-intro { color: #94a0b1; font-size: 12px; line-height: 1.8; margin: 0 0 35px; }
.login-card label { display: flex; flex-direction: column; gap: 10px; font-size: 12px; color: #475569; margin-top: 21px; }
.login-card input { width: 100%; min-width: 0; border: 1px solid #dce3eb; padding: 13px 14px; border-radius: 7px; font: inherit; outline: none; transition: border-color .15s, box-shadow .15s; background: #fff; }
.login-card input:focus { border-color: #228779; box-shadow: 0 0 0 3px #0f766e0f; }
.login-card input::placeholder { color: #b3bdcb; }
.login-card button { width: 100%; display: flex; justify-content: center; align-items: center; gap: 12px; margin-top: 27px; padding: 13px; border: 1px solid #0f766e; background: #0f766e; color: white; border-radius: 7px; font: inherit; font-size: 13px; font-weight: 500; cursor: pointer; box-shadow: 0 3px 6px #0f766e12; }
.login-card button:hover { background: #0b655e; }
.login-card button svg { height: 16px; width: 16px; fill: none; stroke: currentColor; stroke-width: 1.5; }
.login-card button:disabled { opacity: .6; cursor: default; }
.login-note { display: flex; justify-content: center; align-items: center; gap: 7px; font-size: 11px; color: #9aa7b8; margin-top: 22px; }
.login-note svg { width: 14px; height: 14px; stroke: #8498ac; fill: none; stroke-width: 1.6; }
.error { font-size: 12px; color: #b91c1c; background: #fff5f5; border-radius: 6px; padding: 10px 12px; margin-bottom: 0; }
.login-footer { display: flex; justify-content: space-between; gap: 12px; color: #a7b1c0; font-size: 11px; line-height: 1.8; }
@media(max-width: 900px) { .login-page { grid-template-columns: minmax(300px, 40%) minmax(0, 1fr); } .login-story { padding-left: 30px; padding-right: 30px; } .login-main { padding-left: 32px; padding-right: 32px; } .login-topline span { display: none; } .login-footer { flex-direction: column; gap: 3px; } }
@media(max-width: 650px) { .login-page { display: flex; flex-direction: column; } .login-story { min-height: auto; padding: 25px 25px 30px; } .story-content { padding: 24px 0 0; } .story-content h2 { font-size: 26px; margin: 10px 0 0; line-height: 1.5; } .story-content h2 br { display: none; } .story-content > p, .story-flow, .story-footer { display: none; } .story-eyebrow { font-size: 11px; letter-spacing: 1.5px; } .login-main { padding: 28px 26px 24px; min-height: 62vh; flex: 1; } .login-card { padding: 24px 0 42px; } .login-topline { display: none; } .login-card h1 { font-size: 25px; margin-top: 10px; } .login-intro { margin-bottom: 25px; } .login-footer { font-size: 11px; flex-direction: row; } }
</style>
