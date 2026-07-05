import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
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
  }
]

const router = createRouter({
  history: createWebHistory(),
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