<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, nextTick, computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  getCurrentUser,
  logout,
  tokenStore,
  listChats,
  createChat,
  createSession,
  chatCompletionStream,
  listDatasets,
  type Chat,
  type ChatMessage,
  type KnowledgeBase
} from '../api'

const router = useRouter()

/** 当前登录用户 */
const currentUser = ref('')
const userInitial = computed(() => (currentUser.value || 'U').charAt(0).toUpperCase())

/** 侧边栏展开/收起 */
const isSidebarOpen = ref(true)

/** 历史对话列表（真实接口） */
const chats = ref<Chat[]>([])
const activeChat = ref<Chat | null>(null)
const activeSessionId = ref('')

/** 知识库列表（真实接口） */
const datasets = ref<KnowledgeBase[]>([])
const selectedKb = ref<KnowledgeBase | null>(null)
const showKbSelector = ref(false)

/** 模型（占位，无接口） */
const models = ['Flash', 'Kimi-K2.5', 'Qwen3.5-122B', 'GPT-4o']
const selectedModel = ref(models[0])
const showModelMenu = ref(false)

/** 消息与输入 */
const messages = ref<ChatMessage[]>([])
const input = ref('')
const sending = ref(false)
const errorMsg = ref('')
const messagesEndRef = ref<HTMLElement | null>(null)

const isEmpty = computed(() => messages.value.length === 0)

function scrollToBottom(): void {
  nextTick(() => {
    messagesEndRef.value?.scrollIntoView({ behavior: 'smooth' })
  })
}

/** ============ 数据加载 ============ */
async function loadUser(): Promise<void> {
  try {
    currentUser.value = await getCurrentUser()
  } catch {
    await router.push({ name: 'login' })
  }
}

async function loadChats(): Promise<void> {
  try {
    const resp = await listChats(1, 50)
    chats.value = resp.chats
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '加载对话失败'
  }
}

async function loadDatasets(): Promise<void> {
  try {
    const resp = await listDatasets(1, 100)
    datasets.value = resp.data
  } catch {
    datasets.value = []
  }
}

/** ============ 交互 ============ */
function handleNewChat(): void {
  activeChat.value = null
  activeSessionId.value = ''
  messages.value = []
  input.value = ''
  errorMsg.value = ''
}

async function handleHistoryClick(chat: Chat): Promise<void> {
  activeChat.value = chat
  messages.value = []
  errorMsg.value = ''
  try {
    const s = await createSession(chat.id, `会话 ${Date.now()}`)
    activeSessionId.value = s.id
    messages.value = s.messages ?? []
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '打开对话失败'
  }
  scrollToBottom()
}

function selectKb(kb: KnowledgeBase): void {
  selectedKb.value = kb
  showKbSelector.value = false
}

function pickModel(m: string): void {
  selectedModel.value = m
  showModelMenu.value = false
}

async function handleSubmit(): Promise<void> {
  const q = input.value.trim()
  if (!q || sending.value) return
  errorMsg.value = ''

  try {
    if (!activeChat.value) {
      const chat = await createChat({
        name: q.slice(0, 20) || '新对话',
        datasetIds: selectedKb.value ? [selectedKb.value.id] : undefined
      })
      chats.value.unshift(chat)
      activeChat.value = chat
      const s = await createSession(chat.id, '默认会话')
      activeSessionId.value = s.id
    }
    if (!activeSessionId.value && activeChat.value) {
      const s = await createSession(activeChat.value.id, '默认会')
      activeSessionId.value = s.id
    }
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '创建对话失败'
    return
  }

  messages.value.push({ role: 'user', content: q })
  input.value = ''
  scrollToBottom()

  sending.value = true
  const thinking: ChatMessage = { role: 'assistant', content: '' }
  messages.value.push(thinking)
  let received = false
  await chatCompletionStream(activeChat.value!.id, activeSessionId.value, q, {
    onDelta: (answer) => {
      received = true
      thinking.content = answer
      scrollToBottom()
    },
    onError: (msg) => {
      thinking.content = msg
    },
    onDone: () => {
      if (!received) thinking.content = '(无回复)'
      sending.value = false
      scrollToBottom()
    }
  })
  sending.value = false
  scrollToBottom()
}

function onEnter(e: KeyboardEvent): void {
  if (e.shiftKey) return
  e.preventDefault()
  handleSubmit()
}

function textOf(m: ChatMessage): string {
  return m.content
}

async function handleLogout(): Promise<void> {
  try {
    await logout()
  } finally {
    tokenStore.clear()
    await router.push({ name: 'login' })
  }
}

/** 点击空白关闭弹窗 */
function handleClickOutside(e: MouseEvent): void {
  const target = e.target as HTMLElement
  if (!target.closest('[data-kb-picker]')) showKbSelector.value = false
  if (!target.closest('[data-model-picker]')) showModelMenu.value = false
}

onMounted(() => {
  loadUser()
  loadChats()
  loadDatasets()
  document.addEventListener('click', handleClickOutside)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', handleClickOutside)
})
</script>

<template>
  <div class="w-full h-screen bg-white flex font-sans overflow-hidden text-gray-800">
    <!-- 左侧边栏 -->
    <aside
      class="bg-[#F8F9FA] flex flex-col h-full shrink-0 transition-all duration-300 overflow-hidden"
      :class="isSidebarOpen ? 'w-[280px] border-r border-gray-100' : 'w-0 border-r-0'"
    >
      <div class="w-[280px] h-full flex flex-col">
        <!-- Logo 与收起按钮 -->
        <div class="flex items-center px-[24px] h-[64px] shrink-0">
          <button
            class="flex items-center justify-center w-[16px] h-[32px] text-gray-600 hover:text-gray-800 transition-colors shrink-0"
            title="收起侧边栏"
            @click="isSidebarOpen = false"
          >
            <i class="fas fa-bars text-[16px]"></i>
          </button>
          <div
            class="text-[18px] font-medium flex items-center cursor-pointer hover:opacity-80 transition-opacity ml-[12px] text-gray-800"
            @click="handleNewChat"
          >
            DreamAI
          </div>
        </div>

        <!-- 新建对话 -->
        <div class="px-[12px] mb-[12px]">
          <button
            class="flex items-center bg-gray-200/50 hover:bg-gray-200 rounded-[20px] px-[12px] py-[10px] text-[14px] w-full transition-colors"
            @click="handleNewChat"
          >
            <i class="fas fa-edit text-[16px] text-gray-600 w-[16px] flex items-center justify-center shrink-0"></i>
            <span class="ml-[12px] font-medium text-gray-800">发起新对话</span>
          </button>
        </div>

        <!-- 知识库入口 -->
        <div class="px-[12px] mb-[16px]">
          <button
            class="flex items-center hover:bg-gray-200/50 rounded-[8px] px-[12px] py-[10px] text-[14px] w-full transition-colors text-gray-700"
            @click="router.push({ name: 'knowledge-base' })"
          >
            <i class="fas fa-database text-[16px] text-gray-600 w-[16px] flex items-center justify-center shrink-0"></i>
            <span class="ml-[12px] font-medium">知识库管理</span>
          </button>
        </div>

        <!-- 历史记录 -->
        <div class="flex-1 overflow-y-auto px-[12px]">
          <div class="text-[12px] text-gray-500 pl-[12px] pr-[12px] py-[4px] mb-[4px] font-medium">最近</div>
          <ul class="flex flex-col gap-[2px]">
            <li
              v-for="c in chats"
              :key="c.id"
              class="px-[16px] py-[10px] cursor-pointer transition-colors flex items-center rounded-full text-[13px]"
              :class="activeChat?.id === c.id ? 'bg-[#E8EAED] text-gray-900 font-medium' : 'hover:bg-gray-200/50 text-gray-700'"
              @click="handleHistoryClick(c)"
            >
              <span class="flex-1 truncate">{{ c.name }}</span>
            </li>
            <li v-if="!chats.length" class="px-[16px] py-[10px] text-[13px] text-gray-400">暂无对话</li>
          </ul>
        </div>

        <!-- 底部用户信息 -->
        <div class="px-[12px] pb-[16px] mt-auto shrink-0">
          <div class="flex items-center justify-between hover:bg-gray-200/50 cursor-pointer transition-colors rounded-[8px] px-[12px] py-[10px]">
            <div class="flex items-center gap-[12px]">
              <div class="w-[32px] h-[32px] rounded-full bg-indigo-500 text-white flex items-center justify-center text-[16px] font-medium shrink-0">
                {{ userInitial }}
              </div>
              <span class="text-[15px] text-gray-800 font-medium truncate">{{ currentUser || '用户' }}</span>
            </div>
            <button
              class="text-gray-500 hover:text-gray-800 transition-colors flex items-center justify-center w-[28px] h-[28px] shrink-0"
              title="退出登录"
              @click="handleLogout"
            >
              <i class="fas fa-cog text-[16px]"></i>
            </button>
          </div>
        </div>
      </div>
    </aside>

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

      <!-- 顶部栏 -->
      <header class="h-[64px] flex justify-end items-center px-[24px] shrink-0"></header>

      <!-- 对话内容区 -->
      <div class="flex-1 overflow-y-auto px-[20%] relative flex flex-col">
        <!-- 空状态 -->
        <div v-if="isEmpty" class="flex-1 flex flex-col items-center justify-center -mt-[10%]">
          <h2 class="text-[36px] bg-clip-text text-transparent bg-gradient-to-r from-blue-500 to-purple-500 mb-[40px] font-medium">
            {{ currentUser || 'Jack' }}, 稍微一等
          </h2>
          <!-- 居中大输入框 -->
          <div class="w-full max-w-[760px] relative">
            <!-- 知识库绑定提示 -->
            <div
              v-if="selectedKb"
              class="absolute top-[-36px] left-[24px] bg-blue-50 text-blue-600 text-[12px] px-[12px] py-[4px] rounded-[12px] flex items-center gap-[6px] shadow-sm"
            >
              <i class="fas fa-book"></i>
              已绑定: {{ selectedKb.name }}
              <i class="fas fa-times cursor-pointer hover:text-blue-800 ml-[4px]" @click="selectedKb = null"></i>
            </div>
            <form
             class="bg-white rounded-[32px] shadow-[0_2px_12px_rgba(0,0,0,0.08)] border border-gray-100 flex items-center px-[24px] py-[12px] focus-within:shadow-[0_4px_16px_rgba(0,0,0,0.12)] transition-shadow"
              @submit.prevent="handleSubmit"
            >
              <div class="relative flex items-center" data-kb-picker>
                <i
                  class="fas fa-plus inline-flex items-center justify-center w-[16px] h-[16px] mr-[16px] cursor-pointer transition-colors"
                  :class="selectedKb ? 'text-blue-500' : 'text-gray-400 hover:text-blue-500'"
                  title="添加附件或绑定知识库"
                  @click="showKbSelector = !showKbSelector"
                ></i>
                <div
                  v-if="showKbSelector"
                  class="absolute bottom-[36px] left-[-8px] w-[220px] bg-white border border-gray-100 shadow-[0_4px_20px_rgba(0,0,0,0.1)] rounded-[12px] py-[8px] z-10"
                >
                  <div class="px-[16px] py-[6px] text-[12px] text-gray-500 font-medium">选择知识库</div>
                  <div
                    v-for="kb in datasets"
                    :key="kb.id"
                    class="px-[16px] py-[8px] hover:bg-gray-50 text-[13px] cursor-pointer flex items-center justify-between transition-colors"
                    @click="selectKb(kb)"
                  >
                    {{ kb.name }}
                    <i v-if="selectedKb?.id === kb.id" class="fas fa-check text-blue-500 text-[12px]"></i>
                  </div>
                  <div v-if="!datasets.length" class="px-[16px] py-[8px] text-[13px] text-gray-400">暂无知识库</div>
                </div>
              </div>
              <input
                v-model="input"
                type="text"
                placeholder="问问 Dream"
                class="flex-1 bg-transparent outline-none focus:outline-none focus:ring-0 border-none text-[16px] text-gray-800 placeholder:text-gray-400"
                :disabled="sending"
              />
              <div class="flex items-center gap-[16px] ml-[16px] text-gray-500">
                <div class="relative" data-model-picker>
                  <div
                    class="flex items-center gap-[4px] cursor-pointer hover:bg-gray-100 px-[8px] py-[4px] rounded-[8px]"
                    @click="showModelMenu = !showModelMenu"
                  >
                    <span class="text-[14px]">{{ selectedModel }}</span>
                    <i class="fas fa-chevron-down inline-flex items-center justify-center w-[12px] h-[12px] text-[12px]"></i>
                  </div>
                  <div
                    v-if="showModelMenu"
                    class="absolute bottom-[32px] right-0 w-[200px] bg-white border border-gray-100 shadow-[0_4px_20px_rgba(0,0,0,0.1)] rounded-[12px] py-[8px] z-10"
                  >
                    <div
                      v-for="m in models"
                      :key="m"
                      class="px-[16px] py-[8px] hover:bg-gray-50 text-[13px] cursor-pointer flex items-center justify-between transition-colors"
                      @click="pickModel(m)"
                    >
                      {{ m }}
                      <i v-if="m === selectedModel" class="fas fa-check text-blue-500 text-[12px]"></i>
                    </div>
                  </div>
                </div>
                <button
                  type="submit"
                  :disabled="!input.trim() || sending"
                  class="text-blue-500 hover:text-blue-600 disabled:text-gray-300 disabled:cursor-not-allowed"
                >
                  <i v-if="sending" class="fas fa-stop inline-flex items-center justify-center w-[16px] h-[16px]"></i>
                  <i v-else class="fas fa-paper-plane inline-flex items-center justify-center w-[18px] h-[18px] text-[18px]"></i>
                </button>
              </div>
            </form>
          </div>
        </div>

        <!-- 流式对话容器 -->
        <div v-else class="flex-1 py-[24px] flex flex-col gap-[32px]">
          <div v-for="(m, i) in messages" :key="i" class="flex gap-[16px] text-[15px]">
            <div class="flex-1 leading-relaxed">
              <div
                v-if="m.role === 'user'"
                class="bg-[#F0F4F9] text-gray-800 p-[16px] rounded-[24px] rounded-tr-[4px] inline-block float-right max-w-[85%] shadow-sm"
              >
                {{ textOf(m) }}
              </div>
              <div v-else class="text-gray-800 mt-[4px]">{{ textOf(m) }}</div>
              <div class="clear-both"></div>
            </div>
          </div>
          <!-- AI 正在输入动画 -->
          <div v-if="sending" class="flex gap-[16px]">
            <div class="flex-1 pt-[8px] flex gap-[4px]">
              <div class="w-[6px] h-[6px] bg-blue-400 rounded-full animate-bounce"></div>
              <div class="w-[6px] h-[6px] bg-blue-400 rounded-full animate-bounce" style="animation-delay: 0.2s"></div>
              <div class="w-[6px] h-[6px] bg-blue-400 rounded-full animate-bounce" style="animation-delay: 0.4s"></div>
            </div>
          </div>
          <div ref="messagesEndRef" class="h-[20px]"></div>
        </div>
      </div>

      <!-- 底部悬浮输入框 -->
      <div
        v-if="!isEmpty"
        class="px-[20%] pb-[24px] pt-[12px] bg-gradient-to-t from-white via-white to-transparent shrink-0 relative"
      >
        <!-- 知识库绑定提示 -->
        <div
          v-if="selectedKb"
          class="absolute top-[-24px] left-[20%] bg-blue-50 text-blue-600 text-[12px] px-[12px] py-[4px] rounded-[12px] flex items-center gap-[6px] shadow-sm ml-[24px]"
        >
          <i class="fas fa-book"></i>
          已绑定: {{ selectedKb.name }}
          <i class="fas fa-times cursor-pointer hover:text-blue-800 ml-[4px]" @click="selectedKb = null"></i>
        </div>
        <p v-if="errorMsg" class="text-center text-[12px] text-red-500 mb-[8px]">{{ errorMsg }}</p>
        <form
          class="bg-[#F0F4F9] rounded-[32px] flex items-center px-[24px] py-[12px] focus-within:bg-white focus-within:shadow-[0_2px_12px_rgba(0,0,0,0.08)] transition-all border border-transparent focus-within:border-gray-200"
          @submit.prevent="handleSubmit"
        >
          <div class="relative flex items-center" data-kb-picker>
            <i
              class="fas fa-plus inline-flex items-center justify-center w-[16px] h-[16px] mr-[16px] cursor-pointer transition-colors"
              :class="selectedKb ? 'text-blue-500' : 'text-gray-400 hover:text-blue-500'"
              title="添加附件或绑定知识库"
              @click="showKbSelector = !showKbSelector"
            ></i>
            <div
              v-if="showKbSelector"
              class="absolute bottom-[40px] left-[-8px] w-[220px] bg-white border border-gray-100 shadow-[0_4px_20px_rgba(0,0,0,0.1)] rounded-[12px] py-[8px] z-10"
            >
              <div class="px-[16px] py-[6px] text-[12px] text-gray-500 font-medium">选择知识库</div>
              <div
                v-for="kb in datasets"
                :key="kb.id"
                class="px-[16px] py-[8px] hover:bg-gray-50 text-[13px] cursor-pointer flex items-center justify-between transition-colors"
                @click="selectKb(kb)"
              >
                {{ kb.name }}
                <i v-if="selectedKb?.id === kb.id" class="fas fa-check text-blue-500 text-[12px]"></i>
              </div>
              <div v-if="!datasets.length" class="px-[16px] py-[8px] text-[13px] text-gray-400">暂无知识库</div>
            </div>
          </div>
          <input
            v-model="input"
            type="text"
            placeholder="在此输入消息..."
            class="flex-1 bg-transparent outline-none focus:outline-none focus:ring-0 border-none text-[15px] text-gray-800 placeholder:text-gray-500"
            :disabled="sending"
            @keydown.enter="onEnter"
          />
          <div class="flex items-center gap-[16px] ml-[16px] text-gray-500">
            <button
              type="submit"
              :disabled="!input.trim() || sending"
              class="text-blue-500 hover:text-blue-600 disabled:text-gray-300 disabled:cursor-not-allowed"
            >
              <i v-if="sending" class="fas fa-stop inline-flex items-center justify-center w-[16px] h-[16px]"></i>
              <i v-else class="fas fa-paper-plane inline-flex items-center justify-center w-[18px] h-[18px] text-[18px]"></i>
            </button>
          </div>
        </form>
        <p class="text-center text-[12px] text-gray-400 mt-[12px]">AI 可能提供不准确的信息，请核对重要内容。</p>
      </div>
    </main>
  </div>
</template>