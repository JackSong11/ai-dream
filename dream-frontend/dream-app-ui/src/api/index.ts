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