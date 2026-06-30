<script setup lang="ts">
import { ref, onMounted } from 'vue'

interface HelloResponse {
  code: number
  message: string
  timestamp: string
}

const message = ref<string>('加载中...')
const timestamp = ref<string>('')
const error = ref<string>('')

async function fetchHello(): Promise<void> {
  error.value = ''
  message.value = '请求中...'
  try {
    const res = await fetch('http://localhost:8080/api/hello')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    const data: HelloResponse = await res.json()
    message.value = data.message
    timestamp.value = data.timestamp
  } catch (e) {
    error.value = '请求失败: ' + (e instanceof Error ? e.message : String(e))
    message.value = ''
  }
}

onMounted(fetchHello)
</script>

<template>
  <div class="container">
    <h1>Dream App</h1>
    <p class="desc">前后端连通测试（dream-app-ui → dream-app-starter）</p>

    <div class="card">
      <p v-if="message"><strong>后端返回：</strong>{{ message }}</p>
      <p v-if="timestamp" class="ts">时间：{{ timestamp }}</p>
      <p v-if="error" class="err">{{ error }}</p>
    </div>

    <button @click="fetchHello">重新请求后端</button>
  </div>
</template>

<style scoped>
.container {
  max-width: 600px;
  margin: 60px auto;
  font-family: system-ui, sans-serif;
  text-align: center;
}
.desc { color: #666; }
.card {
  margin: 24px 0;
  padding: 20px;
  border: 1px solid #ddd;
  border-radius: 8px;
}
.ts { color: #888; font-size: 14px; }
.err { color: #d33; }
button {
  padding: 8px 18px;
  cursor: pointer;
}
</style>