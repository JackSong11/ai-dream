<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  listDocuments,
  uploadDocuments,
  ingestDocuments,
  type DocItem
} from '../api'

const route = useRoute()
const router = useRouter()

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
  0: { text: '未开始', cls: 'bg-surface-dim text-ink-faint' },
  1: { text: '解析中', cls: 'bg-blue-50 text-blue-600' },
  2: { text: '已取消', cls: 'bg-amber-50 text-amber-600' },
  3: { text: '已完成', cls: 'bg-green-50 text-green-600' },
  4: { text: '失败', cls: 'bg-red-50 text-red-600' }
}

function statusInfo(run: number | null) {
  return runStatusMap[run ?? 0] ?? runStatusMap[0]
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
  <div class="min-h-screen bg-canvas text-ink">
    <!-- Top bar -->
    <header class="sticky top-0 z-10 flex items-center justify-between border-b border-line-soft bg-surface px-8 py-3">
      <div class="flex items-center gap-1 text-sm text-ink-faint">
        <span class="cursor-pointer hover:text-accent transition" @click="router.push({ name: 'knowledge-base' })">知识库</span>
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="mx-1"><path d="m9 18 6-6-6-6"/></svg>
        <span class="font-medium text-ink">{{ datasetName }}</span>
      </div>
      <button
        class="rounded-full px-4 py-1.5 text-sm text-ink-soft hover:bg-surface-dim transition"
        @click="router.push({ name: 'knowledge-base' })"
      >
        返回列表
      </button>
    </header>

    <main class="mx-auto max-w-[960px] px-6 py-8">
      <div class="mb-6 flex items-center justify-between">
        <h1 class="text-xl font-semibold">
          文档列表
          <span class="ml-2 text-sm font-normal text-ink-faint">{{ total }} 个</span>
        </h1>
        <div class="flex items-center gap-2">
          <input
            v-model="keywords"
            class="w-48 rounded-full border border-line bg-surface px-4 py-1.5 text-sm outline-none placeholder:text-ink-faint focus:border-accent"
            placeholder="搜索文档"
            @keyup.enter="load()"
          />
          <button
            class="whitespace-nowrap rounded-full bg-accent px-4 py-1.5 text-sm font-medium text-white transition hover:bg-blue-700 disabled:opacity-50"
            :disabled="uploading"
            @click="triggerUpload"
          >
            {{ uploading ? '上传中...' : '上传文档' }}
          </button>
          <input ref="fileInput" type="file" multiple class="hidden" @change="onFilesSelected" />
        </div>
      </div>

      <p v-if="errorMsg" class="mb-4 text-sm text-red-600">{{ errorMsg }}</p>

      <div v-if="loading" class="py-20 text-center text-sm text-ink-faint">加载中...</div>
      <div v-else-if="!hasDocs" class="py-20 text-center text-sm text-ink-faint">
        该知识库暂无文档，点击右上角上传
      </div>

      <div v-else class="overflow-hidden rounded-xl border border-line bg-surface">
        <table class="w-full border-collapse text-sm">
          <thead>
            <tr class="border-b border-line-soft text-left text-xs text-ink-faint">
              <th class="px-4 py-3 font-medium">文档名称</th>
              <th class="px-4 py-3 font-medium">类型</th>
              <th class="px-4 py-3 font-medium">大小</th>
              <th class="px-4 py-3 font-medium">分块数</th>
              <th class="px-4 py-3 font-medium">状态</th>
              <th class="px-4 py-3 font-medium">创建时间</th>
              <th class="px-4 py-3 font-medium">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="doc in docs" :key="doc.id" class="border-b border-line-soft transition hover:bg-canvas/50">
              <td class="px-4 py-3">
                <span class="mr-2 rounded bg-accent-soft px-1.5 py-0.5 text-[11px] font-medium text-accent">{{ doc.suffix || '?' }}</span>
                <span class="font-medium">{{ doc.name }}</span>
              </td>
              <td class="px-4 py-3 text-ink-faint">{{ doc.type || '-' }}</td>
              <td class="px-4 py-3 text-ink-faint">{{ formatSize(doc.size) }}</td>
              <td class="px-4 py-3 text-ink-faint">{{ doc.chunkCount ?? 0 }}</td>
              <td class="min-w-[120px] px-4 py-3">
                <span class="inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium" :class="statusInfo(doc.run).cls">
                  {{ statusInfo(doc.run).text }}
                  <span v-if="doc.run === 1" class="ml-1">{{ progressPercent(doc) }}%</span>
                </span>
                <div
                  v-if="doc.run === 1 || doc.run === 3"
                  class="mt-1.5 h-1 w-20 overflow-hidden rounded-full bg-surface-dim"
                  :title="doc.progressMsg || ''"
                >
                  <div
                    class="h-full rounded-full transition-all duration-500"
                    :class="doc.run === 3 ? 'bg-green-500' : 'bg-accent'"
                    :style="{ width: progressPercent(doc) + '%' }"
                  ></div>
                </div>
              </td>
              <td class="px-4 py-3 text-xs text-ink-faint">{{ doc.createdTime }}</td>
              <td class="whitespace-nowrap px-4 py-3">
                <button
                  v-if="doc.run === 1"
                  class="mr-1 rounded-full px-3 py-1 text-xs text-ink-soft hover:bg-surface-dim transition disabled:opacity-50"
                  :disabled="isBusy(doc.id)"
                  @click="runIngest(doc, 2)"
                >取消</button>
                <button
                  v-else
                  class="mr-1 rounded-full bg-accent-soft px-3 py-1 text-xs font-medium text-accent hover:bg-blue-100 transition disabled:opacity-50"
                  :disabled="isBusy(doc.id)"
                  @click="runIngest(doc, 1)"
                >{{ doc.run === 3 ? '重新解析' : '解析' }}</button>
                <button
                  class="rounded-full bg-red-50 px-3 py-1 text-xs font-medium text-red-600 hover:bg-red-100 transition disabled:opacity-50"
                  :disabled="isBusy(doc.id)"
                  @click="removeDoc(doc)"
                >删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </main>
  </div>
</template>