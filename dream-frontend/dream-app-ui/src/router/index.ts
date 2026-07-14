import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { tokenStore } from '../api'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/chat-home'
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/Login.vue')
  },
  {
    path: '/chat-home',
    name: 'chat-home',
    component: () => import('../views/ChatHome.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/knowledge-base',
    name: 'knowledge-base',
    component: () => import('../views/KnowledgeBase.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/knowledge-base/:datasetId/documents',
    name: 'documents',
    component: () => import('../views/Documents.vue'),
    meta: { requiresAuth: true }
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

// 全局前置守卫：未登录跳转登录页
router.beforeEach((to) => {
  if (to.meta.requiresAuth && !tokenStore.get()) {
    return { name: 'login' }
  }
  if (to.name === 'login' && tokenStore.get()) {
    return { name: 'chat-home' }
  }
  return true
})

export default router