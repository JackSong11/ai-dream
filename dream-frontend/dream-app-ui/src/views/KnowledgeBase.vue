<script setup lang="ts">
import { ref, onMounted } from 'vue'
import {
  listDatasets,
  createDataset,
  deleteDatasets,
  type KnowledgeBase
} from '../api'
import AppSidebar from '../components/AppSidebar.vue'
import { useRouter } from 'vue-router'

const router = useRouter()

/** 侧边栏展开状态（与对话页保持一致的交互） */
const isSidebarOpen = ref(true)

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
  <div class="w-full h-screen bg-white flex font-sans overflow-hidden text-gray-800">
    <!-- 左侧边栏（共享组件，保持不动） -->
    <AppSidebar v-model:open="isSidebarOpen" />

    <!-- 右侧主区域：仅此处替换对话区，展示知识库 -->
    <main class="flex-1 flex flex-col relative h-full bg-white">
      <!-- 展开侧边栏按钮 -->
      <button
        v-if="!isSidebarOpen"
        class="absolute top-[16px] left-[16px] z-10 flex items-center justify-center w-[40px] h-[40px] rounded-full hover:bg-gray-100 text-gray-600 transition-colors"
        title="展开侧边栏"
        @click="isSidebarOpen = true"
      >
        <i class="fas fa-bars text-[16px]"></i>
      </button>

      <!-- 顶部栏 -->
      <header class="h-[64px] px-[24px] flex items-center justify-between border-b border-gray-100 bg-white shrink-0">
        <h2 class="text-[18px] font-medium text-gray-800">
          知识库管理
          <span class="ml-[8px] text-[13px] font-normal text-gray-400">{{ total }} 个</span>
        </h2>
        <button
          class="bg-blue-500 hover:bg-blue-600 text-white px-[16px] py-[8px] rounded-[8px] text-[14px] transition-colors flex items-center gap-[6px]"
          @click="showCreate = true"
        >
          <i class="fas fa-plus"></i> 新增知识库
        </button>
      </header>

      <!-- 内容区 -->
      <div class="flex-1 overflow-y-auto p-[24px] bg-gray-50/50">
        <!-- 工具栏 -->
        <div class="flex items-center mb-[24px]">
          <div class="relative">
            <i class="fas fa-search absolute left-[16px] top-1/2 -translate-y-1/2 text-[13px] text-gray-400"></i>
            <input
              v-model="keywords"
              class="w-[240px] rounded-full bg-white border border-gray-100 pl-[40px] pr-[16px] py-[10px] text-[14px] outline-none placeholder:text-gray-400 focus:shadow-[0_2px_8px_rgba(0,0,0,0.06)] transition-all"
              placeholder="搜索知识库"
              @keyup.enter="load"
            />
          </div>
        </div>

        <p v-if="errorMsg" class="mb-[16px] text-[13px] text-red-500">{{ errorMsg }}</p>

        <div v-if="loading" class="py-[80px] text-center text-[14px] text-gray-400">加载中...</div>
        <div v-else-if="list.length === 0" class="py-[80px] text-center text-[14px] text-gray-400">
          还没有知识库，点击右上角新建
        </div>

        <div v-else class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-[20px]">
          <div
            v-for="kb in list"
            :key="kb.id"
            class="group relative bg-white border border-gray-100 rounded-[12px] p-[20px] hover:shadow-md transition-shadow cursor-pointer flex flex-col h-[140px]"
            @click="openDocs(kb)"
          >
            <div class="flex items-start justify-between mb-[12px]">
              <div class="w-[40px] h-[40px] bg-blue-50 text-blue-500 rounded-[10px] flex items-center justify-center text-[18px]">
                <i class="fas fa-database"></i>
              </div>
              <button
                class="text-gray-400 hover:text-red-500 w-[24px] h-[24px] flex items-center justify-center rounded-full hover:bg-red-50 transition-colors"
                title="删除"
                @click.stop="handleDelete(kb)"
              >
                <i class="fas fa-trash-alt text-[13px]"></i>
              </button>
            </div>
            <h3 class="text-[15px] font-medium text-gray-800 mb-[12px] truncate leading-tight">{{ kb.name }}</h3>
            <div class="flex items-center text-[12px] text-gray-500 justify-between mt-auto">
              <span><i class="far fa-file-alt mr-[6px]"></i>{{ kb.docNum }} 份文档</span>
              <span>{{ (kb.modifiedTime || kb.createdTime || '').slice(0, 10) }}</span>
            </div>
          </div>
        </div>
      </div>
    </main>

    <!-- 新建弹窗 -->
    <div
      v-if="showCreate"
      class="fixed inset-0 z-20 flex items-center justify-center bg-black/30 animate-fade-in"
      @click.self="showCreate = false"
    >
      <div class="w-full max-w-[420px] rounded-[24px] bg-white p-[24px] shadow-[0_8px_40px_rgba(0,0,0,0.16)]">
        <h2 class="text-[18px] font-semibold text-gray-800">新建知识库</h2>
        <div class="mt-[16px]">
          <label class="mb-[6px] block text-[13px] text-gray-500">名称</label>
          <input
            v-model="form.name"
            class="w-full rounded-[12px] border border-gray-200 px-[14px] py-[10px] text-[14px] outline-none placeholder:text-gray-400 focus:border-blue-400"
            placeholder="请输入知识库名称"
          />
        </div>
        <div class="mt-[14px]">
          <label class="mb-[6px] block text-[13px] text-gray-500">描述</label>
          <textarea
            v-model="form.description"
            rows="3"
            class="w-full resize-none rounded-[12px] border border-gray-200 px-[14px] py-[10px] text-[14px] outline-none placeholder:text-gray-400 focus:border-blue-400"
            placeholder="选填"
          ></textarea>
        </div>
        <div class="mt-[14px]">
          <label class="mb-[6px] block text-[13px] text-gray-500">分块方法</label>
          <select
            v-model="form.chunkMethod"
            class="w-full rounded-[12px] border border-gray-200 px-[14px] py-[10px] text-[14px] outline-none focus:border-blue-400"
          >
            <option value="naive">naive（通用）</option>
            <option value="picture">picture（图片）</option>
            <option value="presentation">presentation（演示）</option>
            <option value="email">email（邮件）</option>
          </select>
        </div>
        <div class="mt-[24px] flex justify-end gap-[10px]">
          <button
            class="rounded-full px-[20px] py-[10px] text-[14px] text-gray-600 hover:bg-gray-100 transition"
            @click="showCreate = false"
          >
            取消
          </button>
          <button
            class="rounded-full bg-blue-500 px-[20px] py-[10px] text-[14px] font-medium text-white transition hover:bg-blue-600 disabled:opacity-50"
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