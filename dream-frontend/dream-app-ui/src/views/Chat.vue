<script setup lang="ts">
import { ref, onMounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import {
  listChats,
  createChat,
  deleteChats,
  listSessions,
  createSession,
  deleteSessions,
  getSession,
  chatCompletion,
  listDatasets,
  type Chat,
  type Session,
  type ChatMessage,
  type KnowledgeBase
} from '../api'

const router = useRouter()

const chats = ref<Chat[]>([])
const sessions = ref<Session[]>([])
const datasets = ref<KnowledgeBase[]>([])

const activeChat = ref<Chat | null>(null)
const activeSession = ref<Session | null>(null)
const messages = ref<ChatMessage[]>([])

const question = ref('')
const sending = ref(false)
const errorMsg = ref('')

// 新建助手弹窗
const showCreate = ref(false)
const newName = ref('')
const selectedKbIds = ref<string[]>([])

const msgBox = ref<HTMLElement | null>(null)

async function loadChats(): Promise<void> {
  try {
    const resp = await listChats(1, 50)
    chats.value = resp.chats
    if (chats.value.length && !activeChat.value) {
      await selectChat(chats.value[0])
    }
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '加载助手失败'
  }
}

async function selectChat(chat: Chat): Promise<void> {
  activeChat.value = chat
  activeSession.value = null
  messages.value = []
  await loadSessions()
}

async function loadSessions(): Promise<void> {
  if (!activeChat.value) return
  sessions.value = await listSessions(activeChat.value.id)
  if (sessions.value.length) {
    await selectSession(sessions.value[0])
  }
}

async function selectSession(session: Session): Promise<void> {
  if (!activeChat.value) return
  const detail = await getSession(activeChat.value.id, session.id)
  activeSession.value = detail
  messages.value = detail.messages ?? []
  scrollBottom()
}

async function handleNewSession(): Promise<void> {
  if (!activeChat.value) return
  const s = await createSession(activeChat.value.id, `会话 ${sessions.value.length + 1}`)
  sessions.value.unshift(s)
  await selectSession(s)
}

async function handleDeleteSession(session: Session, e: Event): Promise<void> {
  e.stopPropagation()
  if (!activeChat.value) return
  await deleteSessions(activeChat.value.id, [session.id])
  sessions.value = sessions.value.filter((x) => x.id !== session.id)
  if (activeSession.value?.id === session.id) {
    activeSession.value = null
    messages.value = []
    if (sessions.value.length) await selectSession(sessions.value[0])
  }
}

async function openCreate(): Promise<void> {
  showCreate.value = true
  newName.value = ''
  selectedKbIds.value = []
  try {
    const resp = await listDatasets(1, 100)
    datasets.value = resp.data
  } catch {
    datasets.value = []
  }
}

async function handleCreateChat(): Promise<void> {
  if (!newName.value.trim()) {
    errorMsg.value = '请输入助手名称'
    return
  }
  const chat = await createChat({
    name: newName.value.trim(),
    datasetIds: selectedKbIds.value
  })
  chats.value.unshift(chat)
  showCreate.value = false
  await selectChat(chat)
}

async function handleDeleteChat(chat: Chat, e: Event): Promise<void> {
  e.stopPropagation()
  await deleteChats([chat.id])
  chats.value = chats.value.filter((c) => c.id !== chat.id)
  if (activeChat.value?.id === chat.id) {
    activeChat.value = null
    sessions.value = []
    messages.value = []
    if (chats.value.length) await selectChat(chats.value[0])
  }
}

function toggleKb(id: string): void {
  const idx = selectedKbIds.value.indexOf(id)
  if (idx >= 0) selectedKbIds.value.splice(idx, 1)
  else selectedKbIds.value.push(id)
}

async function handleSend(): Promise<void> {
  const q = question.value.trim()
  if (!q || sending.value) return
  if (!activeChat.value) {
    errorMsg.value = '请先选择或创建一个助手'
    return
  }
  // 无会话则自动创建
  if (!activeSession.value) {
    await handleNewSession()
  }
  if (!activeSession.value) return

  errorMsg.value = ''
  messages.value.push({ role: 'user', content: q })
  question.value = ''
  scrollBottom()

  sending.value = true
  const thinking: ChatMessage = { role: 'assistant', content: '思考中…' }
  messages.value.push(thinking)
  try {
    const ans = await chatCompletion(activeChat.value.id, activeSession.value.id, q)
    thinking.content = ans.answer || '(无回复)'
  } catch (e) {
    thinking.content = '❌ ' + (e instanceof Error ? e.message : '请求失败')
  } finally {
    sending.value = false
    scrollBottom()
  }
}

function scrollBottom(): void {
  nextTick(() => {
    if (msgBox.value) msgBox.value.scrollTop = msgBox.value.scrollHeight
  })
}

onMounted(loadChats)
</script>

<template>
  <div class="chat-page">
    <!-- 助手列表 -->
    <aside class="col chats">
      <div class="col-head">
        <span>助手</span>
        <button class="add" @click="openCreate">+ 新建</button>
      </div>
      <div class="list">
        <div
          v-for="c in chats"
          :key="c.id"
          class="item"
          :class="{ active: activeChat?.id === c.id }"
          @click="selectChat(c)"
        >
          <div class="item-name">{{ c.name }}</div>
          <span class="del" @click="handleDeleteChat(c, $event)">✕</span>
        </div>
        <p v-if="!chats.length" class="empty">暂无助手，点击右上角新建</p>
      </div>
      <button class="back" @click="router.push({ name: 'home' })">← 返回</button>
    </aside>

    <!-- 会话列表 -->
    <aside class="col sessions">
      <div class="col-head">
        <span>会话</span>
        <button class="add" :disabled="!activeChat" @click="handleNewSession">+ 新会话</button>
      </div>
      <div class="list">
        <div
          v-for="s in sessions"
          :key="s.id"
          class="item"
          :class="{ active: activeSession?.id === s.id }"
          @click="selectSession(s)"
        >
          <div class="item-name">{{ s.name }}</div>
          <span class="del" @click="handleDeleteSession(s, $event)">✕</span>
        </div>
        <p v-if="activeChat && !sessions.length" class="empty">暂无会话</p>
        <p v-if="!activeChat" class="empty">请先选择助手</p>
      </div>
    </aside>

    <!-- 对话区 -->
    <main class="col conversation">
      <div class="conv-head">
        {{ activeChat ? activeChat.name : '请选择助手' }}
        <span v-if="activeChat?.kbNames?.length" class="kb-tag">
          知识库：{{ activeChat.kbNames.join('、') }}
        </span>
      </div>
      <div ref="msgBox" class="messages">
        <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
          <div class="bubble">{{ m.content }}</div>
        </div>
        <p v-if="!messages.length" class="empty center">向知识库提问吧～</p>
      </div>
      <p v-if="errorMsg" class="error">{{ errorMsg }}</p>
      <div class="input-bar">
        <input
          v-model="question"
          placeholder="输入问题，回车发送"
          @keyup.enter="handleSend"
        />
        <button :disabled="sending" @click="handleSend">
          {{ sending ? '生成中' : '发送' }}
        </button>
      </div>
    </main>

    <!-- 新建助手弹窗 -->
    <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
      <div class="modal">
        <h3>新建助手</h3>
        <input v-model="newName" class="modal-input" placeholder="助手名称" />
        <p class="modal-label">选择知识库（可多选）</p>
        <div class="kb-list">
          <label v-for="kb in datasets" :key="kb.id" class="kb-item">
            <input
              type="checkbox"
              :checked="selectedKbIds.includes(kb.id)"
              @change="toggleKb(kb.id)"
            />
            {{ kb.name }}
          </label>
          <p v-if="!datasets.length" class="empty">暂无知识库</p>
        </div>
        <div class="modal-actions">
          <button class="ghost" @click="showCreate = false">取消</button>
          <button class="primary" @click="handleCreateChat">创建</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-page {
  display: flex;
  height: 100vh;
  background: #0a0e1a;
  color: #cdd6f0;
}
.col {
  display: flex;
  flex-direction: column;
  border-right: 1px solid rgba(120, 160, 255, 0.12);
}
.chats { width: 220px; }
.sessions { width: 240px; }
.conversation { flex: 1; }
.col-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px;
  font-weight: 600;
  color: #eaf0ff;
  border-bottom: 1px solid rgba(120, 160, 255, 0.12);
}
.add {
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 6px;
  border: 1px solid rgba(120, 160, 255, 0.3);
  background: transparent;
  color: #7fb0ff;
  cursor: pointer;
}
.add:disabled { opacity: 0.4; cursor: not-allowed; }
.list { flex: 1; overflow-y: auto; padding: 8px; }
.item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  margin-bottom: 4px;
}
.item:hover { background: rgba(59, 107, 255, 0.12); }
.item.active { background: rgba(59, 107, 255, 0.25); color: #fff; }
.item-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.del { color: #6c7690; font-size: 12px; opacity: 0; }
.item:hover .del { opacity: 1; }
.del:hover { color: #ff6b81; }
.empty { color: #6c7690; font-size: 13px; padding: 12px; }
.empty.center { text-align: center; margin-top: 40px; }
.back {
  margin: 12px;
  padding: 8px;
  border-radius: 8px;
  border: 1px solid rgba(120, 160, 255, 0.2);
  background: transparent;
  color: #cdd6f0;
  cursor: pointer;
}
.conv-head {
  padding: 16px;
  font-weight: 600;
  color: #eaf0ff;
  border-bottom: 1px solid rgba(120, 160, 255, 0.12);
}
.kb-tag { font-size: 12px; color: #00d4ff; margin-left: 12px; font-weight: 400; }
.messages { flex: 1; overflow-y: auto; padding: 20px; }
.msg { display: flex; margin-bottom: 16px; }
.msg.user { justify-content: flex-end; }
.bubble {
  max-width: 68%;
  padding: 12px 16px;
  border-radius: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.msg.assistant .bubble { background: rgba(20, 26, 44, 0.9); border: 1px solid rgba(120, 160, 255, 0.18); }
.msg.user .bubble { background: linear-gradient(135deg, #3b6bff, #00d4ff); color: #fff; }
.input-bar { display: flex; gap: 10px; padding: 16px; border-top: 1px solid rgba(120, 160, 255, 0.12); }
.input-bar input {
  flex: 1;
  padding: 12px 14px;
  border-radius: 10px;
  border: 1px solid rgba(120, 160, 255, 0.25);
  background: rgba(20, 26, 44, 0.6);
  color: #eaf0ff;
  outline: none;
}
.input-bar button {
  padding: 0 22px;
  border-radius: 10px;
  border: none;
  color: #fff;
  cursor: pointer;
  background: linear-gradient(135deg, #3b6bff, #00d4ff);
}
.input-bar button:disabled { opacity: 0.5; cursor: not-allowed; }
.error { color: #ff6b81; font-size: 13px; padding: 0 16px; }
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
}
.modal {
  width: 420px;
  padding: 26px;
  border-radius: 14px;
  background: #141a2c;
  border: 1px solid rgba(120, 160, 255, 0.2);
}
.modal h3 { color: #fff; margin-bottom: 16px; }
.modal-input {
  width: 100%;
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid rgba(120, 160, 255, 0.25);
  background: rgba(10, 14, 26, 0.6);
  color: #eaf0ff;
  outline: none;
}
.modal-label { margin: 16px 0 8px; font-size: 13px; color: #9aa4bf; }
.kb-list { max-height: 180px; overflow-y: auto; }
.kb-item { display: block; padding: 6px 0; font-size: 14px; cursor: pointer; }
.modal-actions { display: flex; justify-content: flex-end; gap: 12px; margin-top: 20px; }
.ghost {
  padding: 8px 18px;
  border-radius: 8px;
  border: 1px solid rgba(120, 160, 255, 0.3);
  background: transparent;
  color: #cdd6f0;
  cursor: pointer;
}
.primary {
  padding: 8px 18px;
  border-radius: 8px;
  border: none;
  color: #fff;
  cursor: pointer;
  background: linear-gradient(135deg, #3b6bff, #00d4ff);
}
</style>