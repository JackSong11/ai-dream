<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  listDatasets,
  createDataset,
  deleteDatasets,
  type KnowledgeBase
} from '../api'

const router = useRouter()
const list = ref<KnowledgeBase[]>([])
const total = ref(0)
const loading = ref(false)
const errorMsg = ref('')
const keywords = ref('')

const showCreate = ref(false)
const form = ref({ name: '', description: '', chunkMethod: 'naive' })
const creating = ref(false)

async function load(): Promise<void> {
  loading.value = true
  errorMsg.value = ''
  try {
    const resp = await listDatasets(1, 30, keywords.value.trim())
    list.value = resp.data
    total.value = resp.total
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function handleCreate(): Promise<void> {
  if (!form.value.name.trim()) {
    errorMsg.value = '请输入知识库名称'
    return
  }
  creating.value = true
  try {
    await createDataset({
      name: form.value.name.trim(),
      description: form.value.description.trim(),
      chunkMethod: form.value.chunkMethod
    })
    showCreate.value = false
    form.value = { name: '', description: '', chunkMethod: 'naive' }
    await load()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '创建失败'
  } finally {
    creating.value = false
  }
}

async function handleDelete(kb: KnowledgeBase): Promise<void> {
  if (!confirm(`确定删除知识库「${kb.name}」及其全部文档吗？`)) return
  try {
    await deleteDatasets([kb.id])
    await load()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '删除失败'
  }
}

function openDocs(kb: KnowledgeBase): void {
  router.push({ name: 'documents', params: { datasetId: kb.id }, query: { name: kb.name } })
}

onMounted(load)
</script>

<template>
  <div class="min-h-screen bg-canvas text-ink">
    <!-- Top bar -->
    <header class="sticky top-0 z-10 flex items-center justify-between border-b border-line-soft bg-surface px-8 py-3">
      <div class="flex items-center gap-2 text-sm font-medium">
        <span class="flex h-7 w-7 items-center justify-center rounded-full bg-accent text-xs font-bold text-white">D</span>
        DREAM · 知识库
      </div>
      <button
        class="rounded-full px-4 py-1.5 text-sm text-ink-soft hover:bg-surface-dim transition"
        @click="router.push({ name: 'chat-home' })"
      >
        返回对话
      </button>
    </header>

    <main class="mx-auto max-w-[960px] px-6 py-8">
      <div class="mb-6 flex items-center justify-between">
        <h1 class="text-xl font-semibold">
          我的知识库
          <span class="ml-2 text-sm font-normal text-ink-faint">{{ total }} 个</span>
        </h1>
        <div class="flex gap-2">
          <input
            v-model="keywords"
            class="w-52 rounded-full border border-line bg-surface px-4 py-1.5 text-sm outline-none placeholder:text-ink-faint focus:border-accent"
            placeholder="搜索知识库"
            @keyup.enter="load"
          />
          <button
            class="rounded-full bg-accent px-4 py-1.5 text-sm font-medium text-white transition hover:bg-blue-700 active:scale-[0.98]"
            @click="showCreate = true"
          >
            新建
          </button>
        </div>
      </div>

      <p v-if="errorMsg" class="mb-4 text-sm text-red-600">{{ errorMsg }}</p>

      <div v-if="loading" class="py-20 text-center text-sm text-ink-faint">加载中...</div>
      <div v-else-if="list.length === 0" class="py-20 text-center text-sm text-ink-faint">
        还没有知识库，点击右上角新建
      </div>

      <div v-else class="grid grid-cols-[repeat(auto-fill,minmax(280px,1fr))] gap-4">
        <div
          v-for="kb in list"
          :key="kb.id"
          class="group relative cursor-pointer rounded-2xl border border-line bg-surface p-5 transition hover:border-accent/30 hover:shadow-card"
          @click="openDocs(kb)"
        >
          <div class="flex items-start gap-3">
            <div class="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-xl bg-accent-soft text-sm font-bold text-accent">
              {{ kb.name.charAt(0).toUpperCase() }}
            </div>
            <div class="min-w-0 flex-1">
              <div class="truncate font-medium text-ink">{{ kb.name }}</div>
              <div class="mt-1 line-clamp-1 text-sm text-ink-faint">
                {{ kb.description || '暂无描述' }}
              </div>
              <div class="mt-2 flex gap-3 text-xs text-ink-faint">
                <span>{{ kb.docNum }} 文档</span>
                <span>{{ kb.chunkMethod }}</span>
              </div>
            </div>
          </div>
          <button
            class="absolute right-3 top-3 rounded-full p-1.5 text-ink-faint opacity-0 transition hover:bg-red-50 hover:text-red-600 group-hover:opacity-100"
            title="删除"
            @click.stop="handleDelete(kb)"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"><path d="M18 6 6 18M6 6l12 12"/></svg>
          </button>
        </div>
      </div>
    </main>

    <!-- Create dialog -->
    <div
      v-if="showCreate"
      class="fixed inset-0 z-20 flex items-center justify-center bg-black/30 animate-fade-in"
      @click.self="showCreate = false"
    >
      <div class="animate-pop-in w-full max-w-[420px] rounded-2xl bg-surface p-6 shadow-float">
        <h2 class="text-lg font-semibold">新建知识库</h2>
        <div class="mt-4">
          <label class="mb-1 block text-sm text-ink-soft">名称</label>
          <input
            v-model="form.name"
            class="w-full rounded-lg border border-line bg-surface px-3 py-2 text-sm outline-none placeholder:text-ink-faint focus:border-accent"
            placeholder="请输入知识库名称"
          />
        </div>
        <div class="mt-3">
          <label class="mb-1 block text-sm text-ink-soft">描述</label>
          <textarea
            v-model="form.description"
            rows="3"
            class="w-full resize-none rounded-lg border border-line bg-surface px-3 py-2 text-sm outline-none placeholder:text-ink-faint focus:border-accent"
            placeholder="选填"
          ></textarea>
        </div>
        <div class="mt-3">
          <label class="mb-1 block text-sm text-ink-soft">分块方法</label>
          <select
            v-model="form.chunkMethod"
            class="w-full rounded-lg border border-line bg-surface px-3 py-2 text-sm outline-none focus:border-accent"
          >
            <option value="naive">naive（通用）</option>
            <option value="picture">picture（图片）</option>
            <option value="presentation">presentation（演示）</option>
            <option value="email">email（邮件）</option>
          </select>
        </div>
        <div class="mt-5 flex justify-end gap-2">
          <button
            class="rounded-full px-4 py-2 text-sm text-ink-soft hover:bg-surface-dim transition"
            @click="showCreate = false"
          >
            取消
          </button>
          <button
            class="rounded-full bg-accent px-4 py-2 text-sm font-medium text-white transition hover:bg-blue-700 disabled:opacity-50"
            :disabled="creating"
            @click="handleCreate"
          >
            {{ creating ? '创建中...' : '创建' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>