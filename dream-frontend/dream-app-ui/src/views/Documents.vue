<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  listDocuments,
  uploadDocuments,
  ingestDocuments,
  type DocItem
} from '../api'
import AppSidebar from '../components/AppSidebar.vue'

const route = useRoute()
const router = useRouter()

/** 侧边栏展开状态 */
const isSidebarOpen = ref(true)

const datasetId = route.params.datasetId as string
const datasetName = (route.query.name as string) || '知识库'

const docs = ref<DocItem[]>([])
const total = ref(0)
const loading = ref(false)
const errorMsg = ref('')
const keywords = ref('')

const fileInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
const busyIds = ref<Set<string>>(new Set())

const POLL_INTERVAL = 3000
let pollTimer: number | null = null

const runStatusMap: Record<number, { text: string; cls: string }> = {
  0: { text: '未开始', cls: 'bg-gray-100 text-gray-500' },
  1: { text: '解析中', cls: 'bg-blue-50 text-blue-600' },
  2: { text: '已取消', cls: 'bg-amber-50 text-amber-600' },
  3: { text: '已完成', cls: 'bg-green-50 text-green-600' },
  4: { text: '失败', cls: 'bg-red-50 text-red-600' }
}

function statusInfo(run: number | null) {
  return runStatusMap[run ?? 0] ?? runStatusMap[0]
}

/** 根据文件后缀返回 FontAwesome 图标类名 */
function fileIcon(doc: DocItem): string {
  const suffix = (doc.suffix || doc.name.split('.').pop() || '').toLowerCase()
  if (suffix === 'pdf') return 'fas fa-file-pdf text-red-500'
  if (suffix === 'doc' || suffix === 'docx') return 'fas fa-file-word text-blue-500'
  if (suffix === 'xls' || suffix === 'xlsx') return 'fas fa-file-excel text-green-500'
  if (suffix === 'ppt' || suffix === 'pptx') return 'fas fa-file-powerpoint text-orange-500'
  if (['png', 'jpg', 'jpeg', 'gif', 'webp'].includes(suffix)) return 'fas fa-file-image text-purple-500'
  return 'fas fa-file-alt text-gray-400'
}

function formatSize(size: number | null): string {
  if (!size) return '-'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

function progressPercent(doc: DocItem): number {
  const p = doc.progress ?? 0
  if (p < 0) return 100
  return Math.min(100, Math.max(0, Math.round(p * 100)))
}

const hasDocs = computed(() => docs.value.length > 0)
const hasRunning = computed(() => docs.value.some((d) => d.run === 1))

async function load(silent = false): Promise<void> {
  if (!silent) loading.value = true
  errorMsg.value = ''
  try {
    const resp = await listDocuments(datasetId, 1, 50, keywords.value.trim())
    docs.value = resp.docs
    total.value = resp.total
    syncPolling()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    if (!silent) loading.value = false
  }
}

function syncPolling(): void {
  if (hasRunning.value && pollTimer === null) {
    pollTimer = window.setInterval(() => load(true), POLL_INTERVAL)
  } else if (!hasRunning.value && pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

function triggerUpload(): void {
  fileInput.value?.click()
}

async function onFilesSelected(e: Event): Promise<void> {
  const input = e.target as HTMLInputElement
  const files = input.files ? Array.from(input.files) : []
  if (files.length === 0) return

  uploading.value = true
  errorMsg.value = ''
  try {
    await uploadDocuments(datasetId, files)
    await load()
  } catch (err) {
    errorMsg.value = err instanceof Error ? err.message : '上传失败'
  } finally {
    uploading.value = false
    input.value = ''
  }
}

async function runIngest(doc: DocItem, run: number): Promise<void> {
  busyIds.value.add(doc.id)
  errorMsg.value = ''
  try {
    await ingestDocuments([doc.id], run, { applyKb: run === 1 })
    await load()
  } catch (err) {
    errorMsg.value = err instanceof Error ? err.message : '操作失败'
  } finally {
    busyIds.value.delete(doc.id)
  }
}

async function removeDoc(doc: DocItem): Promise<void> {
  if (!confirm(`确定删除文档「${doc.name}」？`)) return
  busyIds.value.add(doc.id)
  errorMsg.value = ''
  try {
    await ingestDocuments([doc.id], doc.run ?? 0, { delete: true })
    await load()
  } catch (err) {
    errorMsg.value = err instanceof Error ? err.message : '删除失败'
  } finally {
    busyIds.value.delete(doc.id)
  }
}

function isBusy(id: string): boolean {
  return busyIds.value.has(id)
}

onMounted(() => load())
onUnmounted(() => {
  if (pollTimer !== null) window.clearInterval(pollTimer)
})
</script>

<template>
  <div class="w-full h-screen bg-white flex font-sans overflow-hidden text-gray-800">
    <!-- 左侧边栏（共享组件，保持不动） -->
    <AppSidebar v-model:open="isSidebarOpen" />

    <!-- 右侧主区域 -->
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

      <!-- 顶部栏：面包屑 -->
      <header class="h-[64px] px-[24px] flex items-center justify-between border-b border-gray-100 bg-white shrink-0">
        <div class="flex items-center gap-[12px]">
          <button
            class="text-gray-500 hover:text-gray-800 flex items-center justify-center w-[32px] h-[32px] rounded-full hover:bg-gray-100 transition-colors"
            title="返回"
            @click="router.push({ name: 'knowledge-base' })"
          >
            <i class="fas fa-arrow-left"></i>
          </button>
          <h2 class="text-[18px] font-medium text-gray-800">{{ datasetName }}</h2>
          <span class="text-[13px] font-normal text-gray-400">{{ total }} 个文档</span>
        </div>
        <button
          class="flex items-center gap-[6px] whitespace-nowrap bg-blue-500 hover:bg-blue-600 text-white px-[16px] py-[8px] rounded-[8px] text-[14px] transition-colors disabled:opacity-50"
          :disabled="uploading"
          @click="triggerUpload"
        >
          <i class="fas fa-upload"></i>
          {{ uploading ? '上传中...' : '上传文件' }}
        </button>
        <input ref="fileInput" type="file" multiple class="hidden" @change="onFilesSelected" />
      </header>

      <!-- 内容区 -->
      <div class="flex-1 overflow-y-auto p-[24px] bg-gray-50/50">
        <!-- 工具栏 -->
        <div class="mb-[24px] flex items-center">
          <div class="relative">
            <i class="fas fa-search absolute left-[16px] top-1/2 -translate-y-1/2 text-[13px] text-gray-400"></i>
            <input
              v-model="keywords"
              class="w-[240px] rounded-full bg-white border border-gray-100 pl-[40px] pr-[16px] py-[10px] text-[14px] outline-none placeholder:text-gray-400 focus:shadow-[0_2px_8px_rgba(0,0,0,0.06)] transition-all"
              placeholder="搜索文档"
              @keyup.enter="load()"
            />
          </div>
        </div>

        <p v-if="errorMsg" class="mb-[16px] text-[13px] text-red-500">{{ errorMsg }}</p>

        <div v-if="loading" class="py-[80px] text-center text-[14px] text-gray-400">加载中...</div>
        <div v-else-if="!hasDocs" class="py-[80px] text-center text-[14px] text-gray-400">
          该知识库暂无文档，点击右上角上传
        </div>

        <div v-else class="overflow-hidden rounded-[20px] border border-gray-100 bg-white">
          <table class="w-full border-collapse text-[14px]">
            <thead>
              <tr class="bg-gray-50 border-b border-gray-100 text-left text-[13px] text-gray-500">
                <th class="px-[24px] py-[12px] font-medium">文件名</th>
                <th class="px-[24px] py-[12px] font-medium">类型</th>
                <th class="px-[24px] py-[12px] font-medium">大小</th>
                <th class="px-[24px] py-[12px] font-medium">分块数</th>
                <th class="px-[24px] py-[12px] font-medium">状态</th>
                <th class="px-[24px] py-[12px] font-medium">上传时间</th>
                <th class="px-[24px] py-[12px] font-medium">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="doc in docs" :key="doc.id" class="border-b border-gray-50 transition hover:bg-gray-50/50 text-[14px]">
                <td class="px-[24px] py-[16px]">
                  <div class="flex items-center gap-[12px]">
                    <i :class="fileIcon(doc)" class="text-[20px]"></i>
                    <span class="text-gray-800 font-medium">{{ doc.name }}</span>
                  </div>
                </td>
                <td class="px-[24px] py-[16px] text-gray-500">{{ doc.type || doc.suffix || '-' }}</td>
                <td class="px-[24px] py-[16px] text-gray-500">{{ formatSize(doc.size) }}</td>
               <td class="px-[24px] py-[16px] text-gray-500">{{ doc.chunkCount ?? 0 }}</td>
                <td class="min-w-[140px] px-[24px] py-[16px]">
                  <span class="inline-flex items-center rounded-full px-[10px] py-[4px] text-[12px] font-medium" :class="statusInfo(doc.run).cls">
                    <i v-if="doc.run === 1" class="fas fa-spinner fa-spin mr-[4px]"></i>
                    {{ statusInfo(doc.run).text }}
                    <span v-if="doc.run === 1" class="ml-[4px]">{{ progressPercent(doc) }}%</span>
                  </span>
                  <div
                    v-if="doc.run === 1 || doc.run === 3"
                    class="mt-[6px] h-[4px] w-[80px] overflow-hidden rounded-full bg-gray-100"
                    :title="doc.progressMsg || ''"
                  >
                    <div
                      class="h-full rounded-full transition-all duration-500"
                      :class="doc.run === 3 ? 'bg-green-500' : 'bg-blue-500'"
                      :style="{ width: progressPercent(doc) + '%' }"
                    ></div>
                  </div>
                </td>
                <td class="px-[24px] py-[16px] text-[12px] text-gray-500">{{ doc.createdTime }}</td>
                <td class="whitespace-nowrap px-[24px] py-[16px]">
                  <button
                    v-if="doc.run === 1"
                    class="mr-[6px] rounded-full px-[14px] py-[6px] text-[12px] text-gray-600 hover:bg-gray-100 transition disabled:opacity-50"
                    :disabled="isBusy(doc.id)"
                    @click="runIngest(doc, 2)"
                  >取消</button>
                  <button
                    v-else
                    class="mr-[6px] rounded-full bg-blue-50 px-[14px] py-[6px] text-[12px] font-medium text-blue-500 hover:bg-blue-100 transition disabled:opacity-50"
                    :disabled="isBusy(doc.id)"
                    @click="runIngest(doc, 1)"
                  >{{ doc.run === 3 ? '重新解析' : '解析' }}</button>
                  <button
                    class="text-gray-400 hover:text-red-500 transition-colors w-[28px] h-[28px] inline-flex items-center justify-center rounded-full hover:bg-red-50 disabled:opacity-50"
                    title="删除文件"
                    :disabled="isBusy(doc.id)"
                    @click="removeDoc(doc)"
                  ><i class="fas fa-trash-alt"></i></button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </main>
  </div>
</template>