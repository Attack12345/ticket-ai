import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'

const routes = [
  { path: '/login', component: () => import('../views/Login.vue') },
  {
    path: '/',
    component: () => import('../layout/Layout.vue'),
    redirect: '/tickets',
    children: [
      { path: 'tickets', component: () => import('../views/TicketList.vue') },
      { path: 'tickets/new', component: () => import('../views/TicketCreate.vue') },
      { path: 'tickets/:id', component: () => import('../views/TicketDetail.vue') },
      { path: 'agents', component: () => import('../views/AgentManage.vue') },
      { path: 'skill-groups', component: () => import('../views/SkillGroupManage.vue') },
      { path: 'sla', component: () => import('../views/SlaManage.vue') },
      { path: 'kb', component: () => import('../views/KnowledgeBase.vue') },
      { path: 'dashboard', component: () => import('../views/Dashboard.vue') }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach(to => {
  const userStore = useUserStore()
  if (to.path !== '/login' && !userStore.accessToken) {
    return '/login'
  }
  if (to.path === '/login' && userStore.accessToken) {
    return '/'
  }
  return true
})

export default router
