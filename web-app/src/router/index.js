import { createRouter, createWebHistory } from 'vue-router'
import { isLoggedIn } from '../utils/auth'
import Login from '../views/Login.vue'
import Workbench from '../views/Workbench.vue'

const routes = [
  { path: '/login', name: 'login', component: Login },
  { path: '/', name: 'workbench', component: Workbench },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

export function createAppRouter(history = createWebHistory()) {
  const router = createRouter({ history, routes })
  router.beforeEach((to) => {
    const logged = isLoggedIn()
    if (to.path === '/login' && logged) return { path: '/' }
    if (to.path !== '/login' && !logged) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
    return true
  })
  return router
}

export const router = createAppRouter()
