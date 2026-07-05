<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getCurrentUser, logout, tokenStore } from '../api'

const router = useRouter()
const currentUser = ref('')
const errorMsg = ref('')

async function loadUser(): Promise<void> {
  errorMsg.value = ''
  try {
    currentUser.value = await getCurrentUser()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '获取用户信息失败'
    // token 失效则回登录页
    await router.push({ name: 'login' })
  }
}

async function handleLogout(): Promise<void> {
  try {
    await logout()
  } finally {
    tokenStore.clear()
    await router.push({ name: 'login' })
  }
}

onMounted(loadUser)
</script>

<template>
  <div class="home-page">
    <div class="glow glow-1"></div>
    <div class="grid-bg"></div>

    <header class="topbar">
      <div class="logo">
        <span class="logo-dot"></span>
        DREAM
      </div>
      <button class="logout-btn" @click="handleLogout">退出登录</button>
    </header>

    <main class="content">
      <div class="welcome-card">
        <div class="avatar">{{ currentUser.charAt(0).toUpperCase() }}</div>
        <h1>登录成功</h1>
        <p class="hello">
          当前登录用户：<span class="uid">{{ currentUser || '...' }}</span>
        </p>
        <p v-if="errorMsg" class="error">{{ errorMsg }}</p>
        <p class="desc">
          该用户信息通过后端 UserContext 工具类获取，
          业务代码中可随时调用 <code>UserContext.getUserId()</code>。
        </p>
      </div>
    </main>
  </div>
</template>

<style scoped>
.home-page {
  position: relative;
  width: 100%;
  min-height: 100vh;
  background: #0a0e1a;
  overflow: hidden;
}
.grid-bg {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(80, 130, 255, 0.05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(80, 130, 255, 0.05) 1px, transparent 1px);
  background-size: 44px 44px;
  mask-image: radial-gradient(ellipse at top, #000 20%, transparent 70%);
}
.glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(100px);
  opacity: 0.4;
}
.glow-1 {
  width: 500px;
  height: 500px;
  background: #3b6bff;
  top: -180px;
  right: -120px;
}

.topbar {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 40px;
  border-bottom: 1px solid rgba(120, 160, 255, 0.12);
}
.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 3px;
  color: #eaf0ff;
}
.logo-dot {
  width: 11px;
  height: 11px;
  border-radius: 50%;
  background: linear-gradient(135deg, #3b6bff, #00d4ff);
  box-shadow: 0 0 12px #00d4ff;
}
.logout-btn {
  padding: 8px 18px;
  border-radius: 8px;
  border: 1px solid rgba(120, 160, 255, 0.3);
  background: transparent;
  color: #cdd6f0;
  cursor: pointer;
  transition: all 0.2s;
}
.logout-btn:hover {
  border-color: #3b6bff;
  color: #fff;
  background: rgba(59, 107, 255, 0.15);
}

.content {
  position: relative;
  z-index: 2;
  display: flex;
  justify-content: center;
  padding: 80px 20px;
}
.welcome-card {
  width: 460px;
  padding: 44px;
  text-align: center;
  border-radius: 18px;
  background: rgba(20, 26, 44, 0.72);
  border: 1px solid rgba(120, 160, 255, 0.18);
  backdrop-filter: blur(20px);
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.5);
}
.avatar {
  width: 72px;
  height: 72px;
  margin: 0 auto 20px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 30px;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(135deg, #3b6bff, #00d4ff);
  box-shadow: 0 0 24px rgba(0, 212, 255, 0.5);
}
.welcome-card h1 {
  font-size: 24px;
  color: #fff;
}
.hello {
  margin-top: 14px;
  color: #9aa4bf;
}
.uid {
  color: #00d4ff;
  font-weight: 600;
}
.desc {
  margin-top: 20px;
  font-size: 13px;
  line-height: 1.7;
  color: #6c7690;
}
.desc code {
  color: #7fb0ff;
  background: rgba(59, 107, 255, 0.12);
  padding: 2px 6px;
  border-radius: 4px;
}
.error {
  margin-top: 12px;
  color: #ff6b81;
  font-size: 13px;
}
</style>