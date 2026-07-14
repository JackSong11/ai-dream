<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, nextTick, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import {
  getCurrentUser,
  getSession,
  listSessions,
  createChat,
  updateChat,
  createSession,
  chatCompletionStream,
  listDatasets,
  listModels,
  type Chat,
  type ChatMessage,
  type KnowledgeBase,
  type ModelInfo
} from '../api'
import AppSidebar from '../components/AppSidebar.vue'

const router = useRouter()
const route = useRoute()

/** 共享侧边栏引用（用于刷新历史列表） */
const sidebarRef = ref<InstanceType<typeof AppSidebar> | null>(null)

/** 当前登录用户（顶部空状态标题展示） */
const currentUser = ref('')

/** 侧边栏展开/收起 */
const isSidebarOpen = ref(true)

/** 当前激活对话 */
const activeChat = ref<Chat | null>(null)
const activeSessionId = ref('')

/** 知识库列表（真实接口） */
const datasets = ref<KnowledgeBase[]>([])
const selectedKb = ref<KnowledgeBase | null>(null)
const showKbSelector = ref(false)

/** 模型列表（真实接口） */
const models = ref<ModelInfo[]>([])
const selectedModelKey = ref('')
const showModelMenu = ref(false)

/** 当前选中模型的展示名称 */
const selectedModelName = computed(
  () => models.value.find((m) => m.modelKey === selectedModelKey.value)?.name ?? '模型'
)

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
  // 回填该对话已绑定的知识库，保证 UI 与后端一致
  const boundId = chat.datasetIds?.[0]
  selectedKb.value = boundId ? datasets.value.find((d) => d.id === boundId) ?? null : null
  try {
    // 优先复用已有会话，避免每次点击都新建
    const sessions = await listSessions(chat.id, 1, 1)
    const s = sessions.length
      ? await getSession(chat.id, sessions[0].id)
      : await createSession(chat.id, `会话 ${Date.now()}`)
    activeSessionId.value = s.id
    messages.value = s.messages ?? []
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '打开对话失败'
  }
  scrollToBottom()
}

/**
 * 把当前选中的知识库同步到已存在的对话（dialog）。
 * 未创建对话时不处理，交由首次 handleSubmit 的 createChat 暂存。
 */
async function syncKbToChat(): Promise<void> {
  if (!activeChat.value) return
  const kbIds = selectedKb.value ? [selectedKb.value.id] : []
  // 与当前对话已绑定的知识库一致则跳过，避免多余请求
  const current = activeChat.value.datasetIds ?? []
  if (current.length === kbIds.length && current.every((id) => kbIds.includes(id))) return
  try {
    const updated = await updateChat(activeChat.value.id, {
      name: activeChat.value.name,
      datasetIds: kbIds
    })
    activeChat.value = updated
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '更新知识库绑定失败'
  }
}

async function selectKb(kb: KnowledgeBase): Promise<void> {
  selectedKb.value = kb
  showKbSelector.value = false
  await syncKbToChat()
}

/** 取消知识库绑定（同步到已存在对话） */
async function clearKb(): Promise<void> {
  selectedKb.value = null
  await syncKbToChat()
}

function pickModel(m: ModelInfo): void {
  selectedModelKey.value = m.modelKey
  showModelMenu.value = false
}

/** 加载可用模型列表，默认选中后端标记的 current 模型 */
async function loadModels(): Promise<void> {
  try {
    const list = await listModels()
    models.value = list
    const current = list.find((m) => m.current) ?? list[0]
    selectedModelKey.value = current?.modelKey ?? ''
  } catch {
    models.value = []
  }
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
      activeChat.value = chat
      const s = await createSession(chat.id, '默认会话')
      activeSessionId.value = s.id
      // 新建对话后刷新侧边栏历史
      sidebarRef.value?.reloadChats()
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

/** 输入框自动增高 */
function autoGrow(e: Event): void {
  const el = e.target as HTMLTextAreaElement
  el.style.height = 'auto'
  el.style.height = el.scrollHeight + 'px'
}

function textOf(m: ChatMessage): string {
  return m.content
}

/** 点击空白关闭弹窗 */
function handleClickOutside(e: MouseEvent): void {
  const target = e.target as HTMLElement
  if (!target.closest('[data-kb-picker]')) showKbSelector.value = false
  if (!target.closest('[data-model-picker]')) showModelMenu.value = false
}

/** 侧边栏点击历史 -> 打开对话 */
function onSidebarSelectChat(chat: Chat): void {
  handleHistoryClick(chat)
}

onMounted(async () => {
  loadDatasets()
  loadModels()
  document.addEventListener('click', handleClickOutside)

  // 顶部问候语所需用户名
  try {
    currentUser.value = await getCurrentUser()
  } catch {
    /* 忽略，侧边栏守卫会处理未登录 */
  }

  // 从其它页面（如知识库）点击历史跳转过来，自动打开指定对话
  const chatId = route.query.chatId as string | undefined
  if (chatId) {
    handleHistoryClick({ id: chatId, name: '' } as Chat)
    router.replace({ name: 'chat-home' })
  }
})

onBeforeUnmount(() => {
  document.removeEventListener('click', handleClickOutside)
})
</script>

<template>
  <div class="w-full h-screen bg-white flex font-sans overflow-hidden text-gray-800">
    <!-- 左侧边栏（共享组件） -->
    <AppSidebar
      ref="sidebarRef"
      v-model:open="isSidebarOpen"
      :active-chat-id="activeChat?.id"
      embedded
      @new-chat="handleNewChat"
      @select-chat="onSidebarSelectChat"
    />

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
              <i class="fas fa-times cursor-pointer hover:text-blue-800 ml-[4px]" @click="clearKb"></i>
            </div>
            <form
              class="bg-white rounded-[24px] shadow-[0_2px_12px_rgba(0,0,0,0.08)] border border-gray-100 flex flex-col p-[16px] focus-within:shadow-[0_4px_16px_rgba(0,0,0,0.12)] transition-shadow"
              @submit.prevent="handleSubmit"
            >
              <textarea
                v-model="input"
                placeholder="问问 Dream"
                rows="1"
                class="w-full bg-transparent outline-none focus:outline-none focus:ring-0 border-none text-[16px] text-gray-800 placeholder:text-gray-400 resize-none min-h-[48px] max-h-[160px] overflow-y-auto mb-[8px]"
                :disabled="sending"
                @input="autoGrow"
                @keydown.enter="onEnter"
              ></textarea>
              <div class="flex items-center justify-between mt-[4px]">
                <div class="relative flex items-center" data-kb-picker>
                  <i
                    class="fas fa-plus inline-flex items-center justify-center w-[32px] h-[32px] text-[20px] rounded-full hover:bg-gray-100 cursor-pointer transition-colors"
                    :class="selectedKb ? 'text-blue-500' : 'text-gray-400 hover:text-blue-500'"
                    title="添加附件或绑定知识库"
                    @click="showKbSelector = !showKbSelector"
                  ></i>
                  <div
                    v-if="showKbSelector"
                    class="absolute bottom-[36px] left-0 w-[220px] bg-white border border-gray-100 shadow-[0_4px_20px_rgba(0,0,0,0.1)] rounded-[12px] py-[8px] z-10"
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
                <div class="flex items-center gap-[16px] text-gray-500">
                  <div class="relative" data-model-picker>
                    <div
                      class="flex items-center gap-[4px] cursor-pointer hover:bg-gray-100 px-[8px] py-[4px] rounded-[8px]"
                      @click="showModelMenu = !showModelMenu"
                    >
                      <span class="text-[14px]">{{ selectedModelName }}</span>
                      <i class="fas fa-chevron-down inline-flex items-center justify-center w-[12px] h-[12px] text-[12px]"></i>
                    </div>
                    <div
                      v-if="showModelMenu"
                      class="absolute bottom-[32px] right-0 w-[200px] bg-white border border-gray-100 shadow-[0_4px_20px_rgba(0,0,0,0.1)] rounded-[12px] py-[8px] z-10"
                    >
                      <div
                        v-for="m in models"
                        :key="m.modelKey"
                        class="px-[16px] py-[8px] hover:bg-gray-50 text-[13px] cursor-pointer flex items-center justify-between transition-colors"
                        @click="pickModel(m)"
                      >
                        {{ m.name }}
                        <i v-if="m.modelKey === selectedModelKey" class="fas fa-check text-blue-500 text-[12px]"></i>
                      </div>
                      <div v-if="!models.length" class="px-[16px] py-[8px] text-[13px] text-gray-400">暂无可用模型</div>
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
          <i class="fas fa-times cursor-pointer hover:text-blue-800 ml-[4px]" @click="clearKb"></i>
        </div>
        <p v-if="errorMsg" class="text-center text-[12px] text-red-500 mb-[8px]">{{ errorMsg }}</p>
        <form
          class="bg-[#F0F4F9] rounded-[24px] flex flex-col p-[16px] focus-within:bg-white focus-within:shadow-[0_2px_12px_rgba(0,0,0,0.08)] transition-all border border-transparent focus-within:border-gray-200"
          @submit.prevent="handleSubmit"
        >
          <textarea
            v-model="input"
            placeholder="在此输入消息..."
            rows="1"
            class="w-full bg-transparent outline-none focus:outline-none focus:ring-0 border-none text-[15px] text-gray-800 placeholder:text-gray-500 resize-none min-h-[44px] max-h-[160px] overflow-y-auto mb-[8px]"
            :disabled="sending"
            @input="autoGrow"
            @keydown.enter="onEnter"
          ></textarea>
          <div class="flex items-center justify-between mt-[4px]">
            <div class="relative flex items-center" data-kb-picker>
              <i
                class="fas fa-plus inline-flex items-center justify-center w-[32px] h-[32px] text-[20px] rounded-full hover:bg-gray-100 cursor-pointer transition-colors"
                :class="selectedKb ? 'text-blue-500' : 'text-gray-400 hover:text-blue-500'"
                title="添加附件或绑定知识库"
                @click="showKbSelector = !showKbSelector"
              ></i>
              <div
                v-if="showKbSelector"
                class="absolute bottom-[36px] left-0 w-[220px] bg-white border border-gray-100 shadow-[0_4px_20px_rgba(0,0,0,0.1)] rounded-[12px] py-[8px] z-10"
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
            <div class="flex items-center gap-[16px] text-gray-500">
              <div class="relative" data-model-picker>
                <div
                  class="flex items-center gap-[4px] cursor-pointer hover:bg-gray-100 px-[8px] py-[4px] rounded-[8px]"
                  @click="showModelMenu = !showModelMenu"
                >
                  <span class="text-[14px]">{{ selectedModelName }}</span>
                  <i class="fas fa-chevron-down inline-flex items-center justify-center w-[12px] h-[12px] text-[12px]"></i>
                </div>
                <div
                  v-if="showModelMenu"
                  class="absolute bottom-[32px] right-0 w-[200px] bg-white border border-gray-100 shadow-[0_4px_20px_rgba(0,0,0,0.1)] rounded-[12px] py-[8px] z-10"
                >
                  <div
                    v-for="m in models"
                    :key="m.modelKey"
                    class="px-[16px] py-[8px] hover:bg-gray-50 text-[13px] cursor-pointer flex items-center justify-between transition-colors"
                    @click="pickModel(m)"
                  >
                    {{ m.name }}
                    <i v-if="m.modelKey === selectedModelKey" class="fas fa-check text-blue-500 text-[12px]"></i>
                  </div>
                  <div v-if="!models.length" class="px-[16px] py-[8px] text-[13px] text-gray-400">暂无可用模型</div>
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
          </div>
        </form>
        <p class="text-center text-[12px] text-gray-400 mt-[12px]">AI 可能提供不准确的信息，请核对重要内容。</p>
      </div>
    </main>
  </div>
</template>