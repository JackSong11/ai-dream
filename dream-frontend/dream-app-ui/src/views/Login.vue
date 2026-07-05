<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { login, tokenStore } from '../api'

const router = useRouter()

const userId = ref('admin')
const password = ref('123456')
const loading = ref(false)
const errorMsg = ref('')

async function handleLogin(): Promise<void> {
  if (!userId.value || !password.value) {
    errorMsg.value = '请输入账号和密码'
    return
  }
  errorMsg.value = ''
  loading.value = true
  try {
    const resp = await login(userId.value.trim(), password.value)
    tokenStore.set(resp.token)
    await router.push({ name: 'home' })
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <!-- 背景光晕装饰 -->
    <div class="glow glow-1"></div>
    <div class="glow glow-2"></div>
    <div class="grid-bg"></div>

    <div class="login-card">
      <div class="logo">
        <span class="logo-dot"></span>
        DREAM
      </div>
      <h1 class="title">欢迎回来</h1>
      <p class="subtitle">登录以进入智能助手系统</p>

      <form class="form" @submit.prevent="handleLogin">
        <div class="field">
          <label>账号</label>
          <input
            v-model="userId"
            type="text"
            placeholder="请输入账号"
            autocomplete="username"
          />
        </div>

        <div class="field">
          <label>密码</label>
          <input
            v-model="password"
            type="password"
            placeholder="请输入密码"
            autocomplete="current-password"
            @keyup.enter="handleLogin"
          />
        </div>

        <p v-if="errorMsg" class="error">{{ errorMsg }}</p>

        <button class="btn" type="submit" :disabled="loading">
          <span v-if="!loading">登 录</span>
          <span v-else class="loading-text">登录中...</span>
        </button>
      </form>

      <p class="tip">默认账号 admin / test，密码 123456</p>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  position: relative;
  width: 100%;
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #0a0e1a;
  overflow: hidden;
}

/* 网格背景 */
.grid-bg {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(80, 130, 255, 0.06) 1px, transparent 1px),
    linear-gradient(90deg, rgba(80, 130, 255, 0.06) 1px, transparent 1px);
  background-size: 40px 40px;
  mask-image: radial-gradient(ellipse at center, #000 30%, transparent 75%);
}

/* 光晕 */
.glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  opacity: 0.55;
}
.glow-1 {
  width: 400px;
  height: 400px;
  background: #3b6bff;
  top: -120px;
  left: -80px;
}
.glow-2 {
  width: 360px;
  height: 360px;
  background: #00d4ff;
  bottom: -100px;
  right: -60px;
}

.login-card {
  position: relative;
  z-index: 2;
  width: 380px;
  padding: 44px 36px;
  border-radius: 18px;
  background: rgba(20, 26, 44, 0.72);
  border: 1px solid rgba(120, 160, 255, 0.18);
  backdrop-filter: blur(20px);
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.5);
}

.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 3px;
  color: #eaf0ff;
}
.logo-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: linear-gradient(135deg, #3b6bff, #00d4ff);
  box-shadow: 0 0 12px #00d4ff;
}

.title {
  margin-top: 28px;
  font-size: 26px;
  color: #fff;
}
.subtitle {
  margin-top: 8px;
  font-size: 14px;
  color: #8a93ad;
}

.form {
  margin-top: 30px;
}
.field {
  margin-bottom: 20px;
}
.field label {
  display: block;
  margin-bottom: 8px;
  font-size: 13px;
  color: #9aa4bf;
}
.field input {
  width: 100%;
  padding: 13px 14px;
  border-radius: 10px;
  border: 1px solid rgba(120, 160, 255, 0.2);
  background: rgba(10, 14, 26, 0.6);
  color: #eaf0ff;
  font-size: 15px;
  outline: none;
  transition: all 0.2s;
}
.field input::placeholder {
  color: #55607a;
}
.field input:focus {
  border-color: #3b6bff;
  box-shadow: 0 0 0 3px rgba(59, 107, 255, 0.2);
}

.error {
  margin-bottom: 14px;
  font-size: 13px;
  color: #ff6b81;
}

.btn {
  width: 100%;
  padding: 13px;
  border: none;
  border-radius: 10px;
  font-size: 16px;
  letter-spacing: 4px;
  font-weight: 600;
  color: #fff;
  cursor: pointer;
  background: linear-gradient(135deg, #3b6bff, #00d4ff);
  box-shadow: 0 8px 24px rgba(59, 107, 255, 0.4);
  transition: transform 0.15s, box-shadow 0.15s;
}
.btn:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 12px 30px rgba(59, 107, 255, 0.55);
}
.btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.loading-text {
  letter-spacing: 1px;
}

.tip {
  margin-top: 22px;
  text-align: center;
  font-size: 12px;
  color: #626c88;
}
</style>