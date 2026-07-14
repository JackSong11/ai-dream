<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import {
  getCurrentUser,
  logout,
  tokenStore,
  listChats,
  type Chat
} from '../api'

/**
 * 共享侧边栏组件。
 * 在对话页（chat-home）中，父组件可通过事件接管“新建对话 / 点击历史”的行为；
 * 在其它页面（知识库 / 文档）中，点击这些入口会跳转回对话页并携带意图。
 */
const props = defineProps<{
  /** 当前激活的对话 id（仅对话页会传入，用于高亮） */
  activeChatId?: string
  /** 是否由父组件接管交互（对话页为 true） */
  embedded?: boolean
}>()

const emit = defineEmits<{
  (e: 'new-chat'): void
  (e: 'select-chat', chat: Chat): void
}>()

const router = useRouter()
const route = useRoute()

/** 侧边栏展开/收起 —— 通过 v-model 支持父组件双向绑定 */
const open = defineModel<boolean>('open', { default: true })

/** 当前登录用户 */
const currentUser = ref('')
const userInitial = computed(() => (currentUser.value || 'U').charAt(0).toUpperCase())

/** 历史对话列表 */
const chats = ref<Chat[]>([])

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
  } catch {
    chats.value = []
  }
}

function handleNewChat(): void {
  if (props.embedded) {
    emit('new-chat')
  } else {
    router.push({ name: 'chat-home' })
  }
}

function handleHistoryClick(chat: Chat): void {
  if (props.embedded) {
    emit('select-chat', chat)
  } else {
    // 从其它页面点击历史，跳转回对话页并携带要打开的对话 id
    router.push({ name: 'chat-home', query: { chatId: chat.id } })
  }
}

function goKnowledgeBase(): void {
  router.push({ name: 'knowledge-base' })
}

const isKbActive = computed(
  () => route.name === 'knowledge-base' || route.name === 'documents'
)

async function handleLogout(): Promise<void> {
  try {
    await logout()
  } finally {
    tokenStore.clear()
    await router.push({ name: 'login' })
  }
}

onMounted(() => {
  loadUser()
  loadChats()
})

defineExpose({ reloadChats: loadChats })
</script>

<template>
  <aside
    class="bg-[#F8F9FA] flex flex-col h-full shrink-0 transition-all duration-300 overflow-hidden"
    :class="open ? 'w-[280px] border-r border-gray-100' : 'w-0 border-r-0'"
  >
    <div class="w-[280px] h-full flex flex-col">
      <!-- Logo 与收起按钮 -->
      <div class="flex items-center px-[24px] h-[64px] shrink-0">
        <button
          class="flex items-center justify-center w-[16px] h-[32px] text-gray-600 hover:text-gray-800 transition-colors shrink-0"
          title="收起侧边栏"
          @click="open = false"
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
          class="flex items-center rounded-[8px] px-[12px] py-[10px] text-[14px] w-full transition-colors"
          :class="isKbActive ? 'bg-[#E8EAED] text-gray-900 font-medium' : 'hover:bg-gray-200/50 text-gray-700'"
          @click="goKnowledgeBase"
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
            :class="activeChatId === c.id ? 'bg-[#E8EAED] text-gray-900 font-medium' : 'hover:bg-gray-200/50 text-gray-700'"
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
</template>