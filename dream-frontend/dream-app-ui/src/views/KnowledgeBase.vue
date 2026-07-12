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

// 新建弹窗
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
  if (!confirm(`确定删除知识库「${kb.name}」及其全部文档吗？`)) {
    return
  }
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
  <div class="kb-page">
    <div class="grid-bg"></div>
    <div class="glow glow-1"></div>

    <header class="topbar">
      <div class="logo"><span class="logo-dot"></span> DREAM · 知识库</div>
      <button class="ghost-btn" @click="router.push({ name: 'home' })">返回首页</button>
    </header>

    <main class="content">
      <div class="toolbar">
        <h1>我的知识库 <span class="count">{{ total }}</span></h1>
        <div class="actions">
          <input
            v-model="keywords"
            class="search"
            placeholder="搜索知识库名称"
            @keyup.enter="load"
          />
          <button class="primary-btn" @click="showCreate = true">+ 新建知识库</button>
        </div>
      </div>

      <p v-if="errorMsg" class="error">{{ errorMsg }}</p>

      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="list.length === 0" class="empty">还没有知识库，点击右上角新建一个吧</div>

      <div v-else class="card-grid">
        <div v-for="kb in list" :key="kb.id" class="kb-card" @click="openDocs(kb)">
          <div class="kb-icon">{{ kb.name.charAt(0).toUpperCase() }}</div>
          <div class="kb-body">
            <div class="kb-name">{{ kb.name }}</div>
            <div class="kb-desc">{{ kb.description || '暂无描述' }}</div>
            <div class="kb-meta">
              <span>{{ kb.docNum }} 文档</span>
              <span>·</span>
              <span>{{ kb.chunkMethod }}</span>
            </div>
          </div>
          <button class="del-btn" title="删除" @click.stop="handleDelete(kb)">✕</button>
        </div>
      </div>
    </main>

    <!-- 新建弹窗 -->
    <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
      <div class="modal">
        <h2>新建知识库</h2>
        <label>名称</label>
        <input v-model="form.name" class="field" placeholder="请输入知识库名称" />
        <label>描述</label>
        <textarea v-model="form.description" class="field" rows="3" placeholder="选填"></textarea>
        <label>分块方法</label>
        <select v-model="form.chunkMethod" class="field">
          <option value="naive">naive（通用）</option>
          <option value="picture">picture（图片）</option>
          <option value="presentation">presentation（演示）</option>
          <option value="email">email（邮件）</option>
        </select>
        <div class="modal-actions">
          <button class="ghost-btn" @click="showCreate = false">取消</button>
          <button class="primary-btn" :disabled="creating" @click="handleCreate">
            {{ creating ? '创建中…' : '创建' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.kb-page {
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
.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 700;
  letter-spacing: 2px;
  color: #eaf0ff;
}
.logo-dot {
  width: 11px;
  height: 11px;
  border-radius: 50%;
  background: linear-gradient(135deg, #3b6bff, #00d4ff);
  box-shadow: 0 0 12px #00d4ff;
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
  margin-bottom: 24px;
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
.actions {
  display: flex;
  gap: 12px;
}
.search {
  width: 220px;
  padding: 9px 14px;
  border-radius: 8px;
  border: 1px solid rgba(120, 160, 255, 0.25);
  background: rgba(20, 26, 44, 0.6);
  color: #eaf0ff;
  outline: none;
}
.primary-btn {
  padding: 9px 18px;
  border-radius: 8px;
  border: none;
  cursor: pointer;
  color: #fff;
  background: linear-gradient(135deg, #3b6bff, #00d4ff);
  font-weight: 600;
}
.primary-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.ghost-btn {
  padding: 9px 16px;
  border-radius: 8px;
  border: 1px solid rgba(120, 160, 255, 0.3);
  background: transparent;
  color: #cdd6f0;
  cursor: pointer;
}
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 18px;
}
.kb-card {
  position: relative;
  display: flex;
  gap: 14px;
  padding: 20px;
  border-radius: 14px;
  background: rgba(20, 26, 44, 0.7);
  border: 1px solid rgba(120, 160, 255, 0.16);
  cursor: pointer;
  transition: all 0.2s;
}
.kb-card:hover {
  border-color: #3b6bff;
  transform: translateY(-2px);
}
.kb-icon {
  flex-shrink: 0;
  width: 46px;
  height: 46px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(135deg, #3b6bff, #00d4ff);
}
.kb-name {
  color: #fff;
  font-weight: 600;
  font-size: 16px;
}
.kb-desc {
  margin-top: 4px;
  color: #8b95b0;
  font-size: 13px;
  min-height: 18px;
}
.kb-meta {
  margin-top: 10px;
  display: flex;
  gap: 8px;
  font-size: 12px;
  color: #6c7690;
}
.del-btn {
  position: absolute;
  top: 12px;
  right: 12px;
  border: none;
  background: transparent;
  color: #5a6480;
  cursor: pointer;
}
.del-btn:hover {
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
.modal-mask {
  position: fixed;
  inset: 0;
  z-index: 20;
  background: rgba(5, 8, 16, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
}
.modal {
  width: 420px;
  padding: 28px;
  border-radius: 16px;
  background: #141a2c;
  border: 1px solid rgba(120, 160, 255, 0.2);
}
.modal h2 {
  color: #fff;
  margin-bottom: 16px;
}
.modal label {
  display: block;
  margin: 12px 0 6px;
  color: #9aa4bf;
  font-size: 13px;
}
.field {
  width: 100%;
  padding: 9px 12px;
  border-radius: 8px;
  border: 1px solid rgba(120, 160, 255, 0.25);
  background: rgba(10, 14, 26, 0.6);
  color: #eaf0ff;
  outline: none;
  box-sizing: border-box;
}
.modal-actions {
  margin-top: 22px;
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
</style>