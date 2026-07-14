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
    await router.push({ name: 'chat-home' })
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="flex h-screen w-full items-center justify-center bg-canvas px-4">
    <div class="w-full max-w-[400px] rounded-2xl bg-surface p-10 shadow-card">
      <!-- Logo -->
      <div class="mb-8 flex flex-col items-center">
        <span class="mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-accent text-xl font-bold text-white">D</span>
        <h1 class="text-xl font-semibold text-ink">登录 DREAM</h1>
        <p class="mt-1 text-sm text-ink-faint">使用你的账号继续</p>
      </div>

      <form class="flex flex-col gap-4" @submit.prevent="handleLogin">
        <div>
          <label class="mb-1.5 block text-sm font-medium text-ink-soft">账号</label>
          <input
            v-model="userId"
            type="text"
            placeholder="请输入账号"
            autocomplete="username"
            class="w-full rounded-lg border border-line bg-surface px-4 py-2.5 text-sm text-ink outline-none transition placeholder:text-ink-faint focus:border-accent focus:ring-2 focus:ring-accent/20"
          />
        </div>

        <div>
          <label class="mb-1.5 block text-sm font-medium text-ink-soft">密码</label>
          <input
            v-model="password"
            type="password"
            placeholder="请输入密码"
            autocomplete="current-password"
            class="w-full rounded-lg border border-line bg-surface px-4 py-2.5 text-sm text-ink outline-none transition placeholder:text-ink-faint focus:border-accent focus:ring-2 focus:ring-accent/20"
            @keyup.enter="handleLogin"
          />
        </div>

        <p v-if="errorMsg" class="text-sm text-red-600">{{ errorMsg }}</p>

        <button
          type="submit"
          class="mt-2 w-full rounded-full bg-accent py-2.5 text-sm font-medium text-white transition hover:bg-blue-700 active:scale-[0.98] disabled:opacity-50"
          :disabled="loading"
        >
          {{ loading ? '登录中...' : '登录' }}
        </button>
      </form>

      <p class="mt-6 text-center text-xs text-ink-faint">默认账号 admin，密码 123456</p>
    </div>
  </div>
</template>