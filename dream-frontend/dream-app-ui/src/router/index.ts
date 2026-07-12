import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { tokenStore } from '../api'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/home'
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/Login.vue')
  },
  {
    path: '/home',
    name: 'home',
    component: () => import('../views/Home.vue'),
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
  },
  {
    path: '/chat',
    name: 'chat',
    component: () => import('../views/Chat.vue'),
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
    return { name: 'home' }
  }
  return true
})

export default router