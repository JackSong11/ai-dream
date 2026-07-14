// 统一 API 请求封装
const BASE_URL = 'http://localhost:8080'

const TOKEN_KEY = 'dream_token'

export interface Result<T> {
  code: string
  msg: string
  data: T
}

export interface LoginResp {
  token: string
  userId: string
  role: string
  avatarUrl: string | null
}

/** 本地 token 存取 */
export const tokenStore = {
  get: (): string => localStorage.getItem(TOKEN_KEY) ?? '',
  set: (token: string): void => localStorage.setItem(TOKEN_KEY, token),
  clear: (): void => localStorage.removeItem(TOKEN_KEY)
}

/** 通用请求方法，自动携带 token 并解析统一响应体 */
async function request<T>(url: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string>)
  }
  const token = tokenStore.get()
  if (token) {
    headers['Authorization'] = 'Bearer ' + token
  }

  const res = await fetch(BASE_URL + url, { ...options, headers })

  if (res.status === 401) {
    tokenStore.clear()
    throw new Error('登录已失效，请重新登录')
  }

  const body: Result<T> = await res.json()
  if (body.code !== '200') {
    throw new Error(body.msg || '请求失败')
  }
  return body.data
}

/** 登录 */
export function login(userId: string, password: string): Promise<LoginResp> {
  return request<LoginResp>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ userId, password })
  })
}

/** 退出登录 */
export function logout(): Promise<void> {
  return request<void>('/api/auth/logout', { method: 'POST' })
}

/** 获取当前登录用户 userId */
export function getCurrentUser(): Promise<string> {
  return request<string>('/api/auth/current', { method: 'GET' })
}

// ==================== 知识库 / 文档 ====================

/** 知识库 */
export interface KnowledgeBase {
  id: string
  name: string
  description: string | null
  userId: string
  permission: string
  chunkMethod: string
  docNum: number
  tokenNum: number
  chunkNum: number
  createdTime: string
  modifiedTime: string
}

/** 知识库列表返回 */
export interface KbListResp {
  data: KnowledgeBase[]
  total: number
}

/** 文档列表项 */
export interface DocItem {
  id: string
  name: string
  kbId: string
  chunkMethod: string
  type: string | null
  suffix: string | null
  size: number | null
  chunkCount: number | null
  tokenCount: number | null
  run: number | null
  /** 解析进度 0~1，-1 表示失败 */
  progress: number | null
  /** 解析进度描述信息（累积各阶段日志） */
  progressMsg: string | null
  status: string | null
  errorMsg: string | null
  createdTime: string
  modifiedTime: string
}

/** 文档列表返回 */
export interface DocListResp {
  total: number
  docs: DocItem[]
}

/** 文档过滤聚合返回，对应 GET /datasets/{id}/documents?type=filter */
export interface DocFilterResp {
  total: number
  filter: {
    suffix: Record<string, number>
    runStatus: Record<string, number>
    metadata: Record<string, Record<string, number>>
  }
}

/** 创建知识库入参 */
export interface CreateKbReq {
  name: string
  description?: string
  permission?: string
  chunkMethod?: string
}

/** 创建知识库，对应 POST /api/v1/datasets */
export function createDataset(req: CreateKbReq): Promise<KnowledgeBase> {
  return request<KnowledgeBase>('/api/v1/datasets', {
    method: 'POST',
    body: JSON.stringify(req)
  })
}

/** 知识库列表，对应 GET /api/v1/datasets */
export function listDatasets(page = 1, pageSize = 30, keywords = ''): Promise<KbListResp> {
  const params = new URLSearchParams({
    page: String(page),
    page_size: String(pageSize)
  })
  if (keywords) {
    params.set('keywords', keywords)
  }
  return request<KbListResp>(`/api/v1/datasets?${params.toString()}`, { method: 'GET' })
}

/** 知识库详情，对应 GET /api/v1/datasets/{id} */
export function getDataset(id: string): Promise<KnowledgeBase> {
  return request<KnowledgeBase>(`/api/v1/datasets/${id}`, { method: 'GET' })
}

/** 删除知识库，对应 DELETE /api/v1/datasets */
export function deleteDatasets(ids: string[]): Promise<number> {
  return request<number>('/api/v1/datasets', {
    method: 'DELETE',
    body: JSON.stringify({ ids, deleteAll: false })
  })
}

/** 知识库文档列表，对应 GET /api/v1/datasets/{id}/documents */
export function listDocuments(
  datasetId: string,
  page = 1,
  pageSize = 50,
  keywords = ''
): Promise<DocListResp> {
  const params = new URLSearchParams({
    page: String(page),
    page_size: String(pageSize)
  })
  if (keywords) {
    params.set('keywords', keywords)
  }
  return request<DocListResp>(
    `/api/v1/datasets/${datasetId}/documents?${params.toString()}`,
    { method: 'GET' }
  )
}

/** 文档过滤聚合，对应 GET /api/v1/datasets/{id}/documents?type=filter */
export function getDocFilters(datasetId: string, keywords = ''): Promise<DocFilterResp> {
  const params = new URLSearchParams({ type: 'filter' })
  if (keywords) {
    params.set('keywords', keywords)
  }
  return request<DocFilterResp>(
    `/api/v1/datasets/${datasetId}/documents?${params.toString()}`,
    { method: 'GET' }
  )
}

/** 上传文档到知识库，对应 POST /api/v1/documents/upload（multipart） */
export function uploadDocuments(datasetId: string, files: File[]): Promise<DocItem[]> {
  const form = new FormData()
  form.append('kbId', datasetId)
  files.forEach((f) => form.append('file', f))

  // 注意：FormData 不能手动设置 Content-Type，交给浏览器自动带 boundary
  const headers: Record<string, string> = {}
  const token = tokenStore.get()
  if (token) {
    headers['Authorization'] = 'Bearer ' + token
  }
  return fetch(BASE_URL + '/api/v1/documents/upload', {
    method: 'POST',
    headers,
    body: form
  }).then(async (res) => {
    if (res.status === 401) {
      tokenStore.clear()
      throw new Error('登录已失效，请重新登录')
    }
    const body: Result<DocItem[]> = await res.json()
    if (body.code !== '200') {
      throw new Error(body.msg || '上传失败')
    }
    return body.data
  })
}

// ==================== 聊天助手 / 会话 ====================

/** 聊天助手（Chat） */
export interface Chat {
  id: string
  name: string
  description: string | null
  userId: string
  llmId: string
  llmSetting: Record<string, unknown>
  promptConfig: Record<string, unknown>
  datasetIds: string[]
  kbNames: string[]
  rerankId: string
  topN: number
  topK: number
  similarityThreshold: number
  vectorSimilarityWeight: number
  createdTime: string
  modifiedTime: string
}

/** 助手列表返回 */
export interface ChatListResp {
  chats: Chat[]
  total: number
}

/** 会话消息 */
export interface ChatMessage {
  role: 'user' | 'assistant' | 'system'
  content: string
  id?: string
  [key: string]: unknown
}

/** 会话（Session） */
export interface Session {
  id: string
  chatId: string
  userId: string
  name: string
  messages: ChatMessage[]
  reference: unknown[]
  createdTime: string
  modifiedTime: string
}

/** 创建 / 更新助手入参 */
export interface ChatSaveReq {
  name: string
  description?: string
  datasetIds?: string[]
  llmId?: string
}

/** 创建助手，对应 POST /api/v1/chats */
export function createChat(req: ChatSaveReq): Promise<Chat> {
  return request<Chat>('/api/v1/chats', {
    method: 'POST',
    body: JSON.stringify(req)
  })
}

/** 助手列表，对应 GET /api/v1/chats */
export function listChats(page = 1, pageSize = 50, keywords = ''): Promise<ChatListResp> {
  const params = new URLSearchParams({ page: String(page), page_size: String(pageSize) })
  if (keywords) {
    params.set('keywords', keywords)
  }
  return request<ChatListResp>(`/api/v1/chats?${params.toString()}`, { method: 'GET' })
}

/** 更新助手，对应 PUT /api/v1/chats/{id} */
export function updateChat(id: string, req: ChatSaveReq): Promise<Chat> {
  return request<Chat>(`/api/v1/chats/${id}`, {
    method: 'PUT',
    body: JSON.stringify(req)
  })
}

/** 删除助手，对应 DELETE /api/v1/chats */
export function deleteChats(ids: string[]): Promise<number> {
  return request<number>('/api/v1/chats', {
    method: 'DELETE',
    body: JSON.stringify({ ids, deleteAll: false })
  })
}

/** 创建会话，对应 POST /api/v1/chats/{id}/sessions */
export function createSession(chatId: string, name = 'New session'): Promise<Session> {
  return request<Session>(`/api/v1/chats/${chatId}/sessions`, {
    method: 'POST',
    body: JSON.stringify({ name })
  })
}

/** 会话列表，对应 GET /api/v1/chats/{id}/sessions */
export function listSessions(chatId: string, page = 1, pageSize = 50): Promise<Session[]> {
  const params = new URLSearchParams({ page: String(page), page_size: String(pageSize) })
  return request<Session[]>(`/api/v1/chats/${chatId}/sessions?${params.toString()}`, {
    method: 'GET'
  })
}

/** 会话详情，对应 GET /api/v1/chats/{id}/sessions/{sid} */
export function getSession(chatId: string, sessionId: string): Promise<Session> {
  return request<Session>(`/api/v1/chats/${chatId}/sessions/${sessionId}`, { method: 'GET' })
}

/** 重命名会话，对应 PATCH /api/v1/chats/{id}/sessions/{sid} */
export function renameSession(chatId: string, sessionId: string, name: string): Promise<Session> {
  return request<Session>(`/api/v1/chats/${chatId}/sessions/${sessionId}`, {
    method: 'PATCH',
    body: JSON.stringify({ name })
  })
}

/** 删除会话，对应 DELETE /api/v1/chats/{id}/sessions */
export function deleteSessions(chatId: string, ids: string[]): Promise<number> {
  return request<number>(`/api/v1/chats/${chatId}/sessions`, {
    method: 'DELETE',
    body: JSON.stringify({ ids, deleteAll: false })
  })
}

/** 聊天补全答案 */
export interface ChatAnswer {
  answer: string
  reference: Record<string, unknown> | null
  id: string | null
  convId: string | null
  dialogId: string | null
}

/** 聊天补全（非流式），对应 POST /api/v1/chat/completions */
export function chatCompletion(
  dialogId: string,
  convId: string,
  question: string
): Promise<ChatAnswer> {
  return request<ChatAnswer>('/api/v1/chat/completions', {
    method: 'POST',
    body: JSON.stringify({
      dialogId,
      convId,
      messages: [{ role: 'user', content: question }]
    })
  })
}

/** 触发文档解析 / 运行状态变更，对应 POST /api/v1/documents/ingest */
export function ingestDocuments(
  docIds: string[],
  run: number,
  opts: { delete?: boolean; applyKb?: boolean } = {}
): Promise<boolean> {
  return request<boolean>('/api/v1/documents/ingest', {
    method: 'POST',
    body: JSON.stringify({
      docIds,
      run,
      delete: opts.delete ?? false,
      applyKb: opts.applyKb ?? false
    })
  })
}

/** 流式回调：onDelta 收到累积的完整答案文本；onDone 结束；onError 出错 */
export interface StreamHandlers {
  onDelta: (answer: string) => void
  onDone?: () => void
  onError?: (msg: string) => void
}

/**
 * 聊天补全（流式 SSE），对应 POST /api/v1/chat/completions/stream。
 * 后端每帧推送 data: {"code":0,"data":{"answer": 累积全文, "reference": {...}, "final": false}}，
 * 结束帧为 data: {"code":0,"data":{"answer":"", "final": true, ...}}（answer 为空，不覆盖内容）。
 */
export async function chatCompletionStream(
  dialogId: string,
  convId: string,
  question: string,
  handlers: StreamHandlers
): Promise<void> {
  const token = tokenStore.get()
  const res = await fetch(BASE_URL + '/api/v1/chat/completions/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: 'Bearer ' + token } : {})
    },
    body: JSON.stringify({
      dialogId,
      convId,
      messages: [{ role: 'user', content: question }]
    })
  })

  if (res.status === 401) {
    tokenStore.clear()
    handlers.onError?.('登录已失效，请重新登录')
    return
  }
  if (!res.body) {
    handlers.onError?.('服务端未返回数据流')
    return
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  const handleFrame = (raw: string): boolean => {
    // 一个 SSE 帧可能包含多行（含 \r），逐行剥离 data: 前缀后拼接为完整 JSON
    const line = raw
      .split(/\r?\n/)
      .map((l) => l.replace(/^data:\s*/, ''))
      .join('')
      .trim()
    if (!line) return false
    try {
      const obj = JSON.parse(line)

      // 业务错误码：优先提示并结束
      if (obj?.code && obj.code !== 0 && obj?.message) {
        handlers.onError?.(obj.message)
        return false
      }

      const data = obj?.data
      // 兼容旧协议：结束帧为 data === true
      if (data === true) {
        handlers.onDone?.()
        return true
      }

      if (data && typeof data === 'object') {
        // 仅在有非空答案时更新，避免结束帧的空 answer 覆盖已渲染内容
        if (typeof data.answer === 'string' && data.answer.length > 0) {
          handlers.onDelta(data.answer)
        }
        // RagFlow 协议：final === true 表示流结束
        if (data.final === true) {
          handlers.onDone?.()
          return true
        }
      }
    } catch {
      // 忽略无法解析的心跳/空帧
    }
    return false
  }

  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const parts = buffer.split(/\n\n/)
    buffer = parts.pop() ?? ''
    for (const part of parts) {
      if (handleFrame(part)) return
    }
  }
  if (buffer.trim()) handleFrame(buffer)
  handlers.onDone?.()
}