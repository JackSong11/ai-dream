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

// 上传相关
const fileInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
// 正在解析操作中的文档 id 集合
const busyIds = ref<Set<string>>(new Set())

// 轮询定时器：存在解析中的文档时，定时静默刷新列表以呈现进度实时更新
const POLL_INTERVAL = 3000
let pollTimer: number | null = null

const runStatusMap: Record<number, { text: string; cls: string }> = {
  0: { text: '未开始', cls: 'st-unstart' },
  1: { text: '解析中', cls: 'st-running' },
  2: { text: '已取消', cls: 'st-cancel' },
  3: { text: '已完成', cls: 'st-done' },
  4: { text: '失败', cls: 'st-fail' }
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

/** 进度百分比（0~100），失败(-1)按 0 处理，仅用于进度条宽度 */
function progressPercent(doc: DocItem): number {
  const p = doc.progress ?? 0
  if (p < 0) return 100
  return Math.min(100, Math.max(0, Math.round(p * 100)))
}

const hasDocs = computed(() => docs.value.length > 0)

// 是否存在解析中的文档（对齐 RagFlow：有 RUNNING 文档才轮询）
const hasRunning = computed(() => docs.value.some((d) => d.run === 1))

/**
 * 加载文档列表。silent=true 时用于轮询刷新，不显示整页 loading，避免闪烁。
 */
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

/** 根据是否存在解析中的文档，自动开启 / 关闭轮询 */
function syncPolling(): void {
  if (hasRunning.value && pollTimer === null) {
    pollTimer = window.setInterval(() => load(true), POLL_INTERVAL)
  } else if (!hasRunning.value && pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

/** 点击「上传文档」触发文件选择 */
function triggerUpload(): void {
  fileInput.value?.click()
}

/** 选择文件后上传，对应 POST /documents/upload */
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
    // 清空以便重复选择同一文件
    input.value = ''
  }
}

/** 触发解析 / 取消解析，对应 POST /documents/ingest（run: 1=解析, 2=取消） */
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

/** 删除文档（复用 ingest 的 delete 语义） */
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
  <div class="doc-page">
    <div class="grid-bg"></div>
    <div class="glow glow-1"></div>

    <header class="topbar">
      <div class="crumb">
        <span class="link" @click="router.push({ name: 'knowledge-base' })">知识库</span>
        <span class="sep">/</span>
        <span class="cur">{{ datasetName }}</span>
      </div>
      <button class="ghost-btn" @click="router.push({ name: 'knowledge-base' })">返回列表</button>
    </header>

    <main class="content">
      <div class="toolbar">
        <h1>文档列表 <span class="count">{{ total }}</span></h1>
        <div class="tools-right">
          <input
            v-model="keywords"
            class="search"
            placeholder="搜索文档名称"
            @keyup.enter="load"
          />
          <button class="primary-btn" :disabled="uploading" @click="triggerUpload">
            {{ uploading ? '上传中…' : '+ 上传文档' }}
          </button>
          <input
            ref="fileInput"
            type="file"
            multiple
            class="hidden-input"
            @change="onFilesSelected"
          />
        </div>
      </div>

      <p v-if="errorMsg" class="error">{{ errorMsg }}</p>

      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="!hasDocs" class="empty">该知识库暂无文档，点击右上角上传</div>

      <table v-else class="doc-table">
        <thead>
          <tr>
            <th>文档名称</th>
            <th>类型</th>
            <th>大小</th>
            <th>分块数</th>
            <th>状态</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="doc in docs" :key="doc.id">
            <td class="name-cell">
              <span class="suffix-tag">{{ doc.suffix || '?' }}</span>
              {{ doc.name }}
            </td>
            <td>{{ doc.type || '-' }}</td>
            <td>{{ formatSize(doc.size) }}</td>
            <td>{{ doc.chunkCount ?? 0 }}</td>
            <td class="status-cell">
              <span class="status" :class="statusInfo(doc.run).cls">
                {{ statusInfo(doc.run).text }}
                <span v-if="doc.run === 1" class="pct">{{ progressPercent(doc) }}%</span>
              </span>
              <!-- 解析中 / 已完成 展示进度条，鼠标悬停可见最新进度日志 -->
              <div
                v-if="doc.run === 1 || doc.run === 3"
                class="progress-track"
                :title="doc.progressMsg || ''"
              >
                <div
                  class="progress-bar"
                  :class="{ done: doc.run === 3 }"
                  :style="{ width: progressPercent(doc) + '%' }"
                ></div>
              </div>
            </td>
            <td class="time-cell">{{ doc.createdTime }}</td>
            <td class="op-cell">
              <button
                v-if="doc.run === 1"
                class="op-btn"
                :disabled="isBusy(doc.id)"
                @click="runIngest(doc, 2)"
              >取消</button>
              <button
                v-else
                class="op-btn primary"
                :disabled="isBusy(doc.id)"
                @click="runIngest(doc, 1)"
              >{{ doc.run === 3 ? '重新解析' : '解析' }}</button>
              <button
                class="op-btn danger"
                :disabled="isBusy(doc.id)"
                @click="removeDoc(doc)"
              >删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </main>
  </div>
</template>

<style scoped>
.doc-page {
  position: relative;
  min-height: 100vh;
  background: #0a0e1a;
  overflow-x: hidden;
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
.glow-1 {
  position: absolute;
  width: 500px;
  height: 500px;
  border-radius: 50%;
  filter: blur(120px);
  opacity: 0.35;
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
  padding: 18px 40px;
  border-bottom: 1px solid rgba(120, 160, 255, 0.12);
}
.crumb {
  color: #9aa4bf;
  font-size: 14px;
}
.crumb .link {
  color: #7fb0ff;
  cursor: pointer;
}
.crumb .sep {
  margin: 0 8px;
  color: #4a5372;
}
.crumb .cur {
  color: #fff;
  font-weight: 600;
}
.ghost-btn {
  padding: 9px 16px;
  border-radius: 8px;
  border: 1px solid rgba(120, 160, 255, 0.3);
  background: transparent;
  color: #cdd6f0;
  cursor: pointer;
}
.content {
  position: relative;
  z-index: 2;
  max-width: 1100px;
  margin: 0 auto;
  padding: 36px 24px;
}
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 22px;
}
.toolbar h1 {
  color: #fff;
  font-size: 22px;
}
.count {
  margin-left: 6px;
  font-size: 14px;
  color: #00d4ff;
}
.search {
  width: 240px;
  padding: 9px 14px;
  border-radius: 8px;
  border: 1px solid rgba(120, 160, 255, 0.25);
  background: rgba(20, 26, 44, 0.6);
  color: #eaf0ff;
  outline: none;
}
.tools-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.primary-btn {
  padding: 9px 18px;
  border-radius: 8px;
  border: none;
  background: linear-gradient(135deg, #3b6bff, #00d4ff);
  color: #fff;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
}
.primary-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.hidden-input {
  display: none;
}
.op-cell {
  white-space: nowrap;
}
.op-btn {
  margin-right: 8px;
  padding: 5px 12px;
  border-radius: 6px;
  border: 1px solid rgba(120, 160, 255, 0.3);
  background: transparent;
  color: #cdd6f0;
  font-size: 12px;
  cursor: pointer;
}
.op-btn:hover {
  background: rgba(59, 107, 255, 0.12);
}
.op-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.op-btn.primary {
  border-color: rgba(59, 107, 255, 0.6);
  color: #7fb0ff;
}
.op-btn.danger {
  border-color: rgba(255, 90, 110, 0.4);
  color: #ff6b81;
}
.doc-table {
  width: 100%;
  border-collapse: collapse;
  background: rgba(20, 26, 44, 0.6);
  border-radius: 12px;
  overflow: hidden;
}
.doc-table th {
  text-align: left;
  padding: 14px 16px;
  font-size: 13px;
  color: #8b95b0;
  border-bottom: 1px solid rgba(120, 160, 255, 0.15);
}
.doc-table td {
  padding: 14px 16px;
  font-size: 14px;
  color: #d4dcf0;
  border-bottom: 1px solid rgba(120, 160, 255, 0.08);
}
.doc-table tbody tr:hover {
  background: rgba(59, 107, 255, 0.06);
}
.name-cell {
  color: #fff;
}
.suffix-tag {
  display: inline-block;
  margin-right: 8px;
  padding: 2px 8px;
  font-size: 11px;
  border-radius: 5px;
  background: rgba(59, 107, 255, 0.18);
  color: #7fb0ff;
  text-transform: uppercase;
}
.time-cell {
  color: #6c7690;
  font-size: 13px;
}
.status {
  padding: 3px 10px;
  border-radius: 20px;
  font-size: 12px;
}
.status-cell {
  min-width: 130px;
}
.pct {
  margin-left: 6px;
  font-weight: 600;
}
.progress-track {
  margin-top: 8px;
  width: 110px;
  height: 6px;
  border-radius: 4px;
  background: rgba(120, 160, 255, 0.15);
  overflow: hidden;
}
.progress-bar {
  height: 100%;
  border-radius: 4px;
  background: linear-gradient(90deg, #3b6bff, #00d4ff);
  transition: width 0.4s ease;
}
.progress-bar.done {
  background: linear-gradient(90deg, #32c878, #4fd88a);
}
.st-unstart {
  background: rgba(120, 130, 160, 0.2);
  color: #a2acc8;
}
.st-running {
  background: rgba(59, 107, 255, 0.2);
  color: #7fb0ff;
}
.st-cancel {
  background: rgba(180, 150, 60, 0.2);
  color: #e0c060;
}
.st-done {
  background: rgba(50, 200, 120, 0.18);
  color: #4fd88a;
}
.st-fail {
  background: rgba(255, 90, 110, 0.18);
  color: #ff6b81;
}
.empty {
  padding: 80px 0;
  text-align: center;
  color: #6c7690;
}
.error {
  color: #ff6b81;
  margin-bottom: 12px;
}
</style>