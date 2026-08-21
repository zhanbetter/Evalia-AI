import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', name: 'Login', component: () => import('../views/auth/Login.vue'), meta: { public: true } },
  { path: '/', redirect: '/dataset' },
  { path: '/dataset', name: 'Dataset', component: () => import('../views/dataset/index.vue') },
  { path: '/dataset/:id', name: 'DatasetDetail', component: () => import('../views/dataset/detail.vue'), props: true },
  { path: '/model', name: 'Model', component: () => import('../views/model/index.vue') },
  { path: '/prompt', name: 'Prompt', component: () => import('../views/prompt/index.vue') },
  { path: '/prompt/editor/:id?', name: 'PromptEditor', component: () => import('../views/prompt/editor.vue'), props: true },
  { path: '/task', name: 'Task', component: () => import('../views/task/index.vue') },
  { path: '/task/create', name: 'TaskCreate', component: () => import('../views/task/create.vue') },
  { path: '/analysis', name: 'Analysis', component: () => import('../views/analysis/index.vue') },
  { path: '/annotation', name: 'AnnotationQueue', component: () => import('../views/review/AnnotationQueue.vue') },
  { path: '/knowledge', name: 'Knowledge', component: () => import('../views/knowledge/index.vue') },
  { path: '/knowledge/:id', name: 'KnowledgeDetail', component: () => import('../views/knowledge/detail.vue'), props: true },
  { path: '/result', redirect: '/analysis' },
  { path: '/badcase', redirect: '/analysis' },
  { path: '/review', redirect: '/analysis' },
  { path: '/report', redirect: '/analysis' },
  { path: '/:pathMatch(.*)*', name: 'NotFound', component: () => import('../views/auth/NotFound.vue') }
]

const router = createRouter({ history: createWebHistory(), routes })

// 登录守卫：非 public 路由要求已登录（存在 JWT 令牌），否则跳登录页并携带回跳地址
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('eval-token')
  if (!to.meta.public && !token) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }
  // 已登录用户访问登录页 → 直接回首页
  if (to.path === '/login' && token) {
    next({ path: '/dataset' })
    return
  }
  next()
})

export default router