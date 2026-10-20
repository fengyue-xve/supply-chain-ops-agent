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
    <form class="login-card" @submit.prevent="submit">
      <h1>供应链智能运营 Agent</h1>
      <label>
        <span>用户名</span>
        <input v-model="username" autocomplete="username" data-test="username" />
      </label>
      <label>
        <span>密码</span>
        <input v-model="password" type="password" autocomplete="current-password" data-test="password" />
      </label>
      <p v-if="fieldError" class="error" data-test="field-error" role="alert">{{ fieldError }}</p>
      <p v-if="error" class="error" data-test="error" role="alert">{{ error }}</p>
      <button type="submit" :disabled="loading" data-test="submit">
        {{ loading ? '登录中…' : '登录' }}
      </button>
    </form>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
}
.login-card {
  width: 100%;
  max-width: 380px;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  padding: 28px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.login-card h1 {
  margin: 0 0 6px;
  font-size: 18px;
  text-align: center;
}
.login-card label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 14px;
}
.login-card input {
  padding: 9px 10px;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  font-size: 14px;
}
.login-card button {
  padding: 10px;
  border: 0;
  border-radius: 8px;
  background: #2563eb;
  color: #fff;
  font-size: 15px;
  cursor: pointer;
}
.login-card button:disabled {
  opacity: 0.6;
  cursor: default;
}
.error {
  margin: 0;
  color: #dc2626;
  font-size: 13px;
}
</style>
