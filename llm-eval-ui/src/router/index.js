import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', redirect: '/dataset' },
  { path: '/dataset', name: 'Dataset', component: () => import('../views/dataset/index.vue') },
  { path: '/dataset/:id', name: 'DatasetDetail', component: () => import('../views/dataset/detail.vue'), props: true },
  { path: '/model', name: 'Model', component: () => import('../views/model/index.vue') },
  { path: '/prompt', name: 'Prompt', component: () => import('../views/prompt/index.vue') },
  { path: '/prompt/editor/:id?', name: 'PromptEditor', component: () => import('../views/prompt/editor.vue'), props: true },
  { path: '/task', name: 'Task', component: () => import('../views/task/index.vue') },
  { path: '/task/create', name: 'TaskCreate', component: () => import('../views/task/create.vue') },
  { path: '/analysis', name: 'Analysis', component: () => import('../views/analysis/index.vue') },
  { path: '/knowledge', name: 'Knowledge', component: () => import('../views/knowledge/index.vue') },
  { path: '/knowledge/:id', name: 'KnowledgeDetail', component: () => import('../views/knowledge/detail.vue'), props: true },
  { path: '/result', redirect: '/analysis' },
  { path: '/badcase', redirect: '/analysis' },
  { path: '/review', redirect: '/analysis' },
  { path: '/report', redirect: '/analysis' }
]

const router = createRouter({ history: createWebHistory(), routes })
export default router
