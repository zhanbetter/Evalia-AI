<template>
  <div class="page review-page">
    <!-- 顶部 Header -->
    <div class="review-header">
      <div>
        <h1>人工校验工作台</h1>
        <p class="meta">人工标注产金标准 · 人机对比校准 AI 裁判</p>
      </div>
    </div>

    <!-- 任务选择 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <span class="toolbar-icon"><el-icon :size="16"><EditPen /></el-icon></span>
        <el-dropdown trigger="click" @command="onPickTask" v-if="tasks.length">
          <div class="task-trigger">
            <span class="task-trigger-name">{{ currentTask ? currentTask.name : '选择评测任务' }}</span>
            <span class="task-trigger-ver" v-if="currentTask">v{{ currentTask.version }}</span>
            <el-icon :size="13" class="trigger-caret"><ArrowDown /></el-icon>
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-for="t in tasks" :key="t.id" :command="t.id">
                <span class="dd-dot" :class="statusClass(t.status)"></span>
                <span class="dd-name">{{ t.name }}</span>
                <span class="dd-ver">v{{ t.version }}</span>
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
      <div class="toolbar-right">
        <span class="role-chip" :class="{ active: myRole === 'normal' }" @click="setRole('normal')">普通评委</span>
        <span class="role-chip" :class="{ active: myRole === 'expert' }" @click="setRole('expert')">专家</span>
      </div>
    </div>

    <el-empty v-if="!selectedTaskId" description="请选择评测任务开始人工校验" />

    <template v-if="selectedTaskId">
      <!-- 我的身份 -->
      <div class="reviewer-bar">
        <span class="reviewer-label">我的身份：</span>
        <el-input v-model="myName" placeholder="名字/工号" size="small" style="width: 180px" clearable />
        <span class="reviewer-role">当前角色：<strong :class="myRole === 'expert' ? 'role-expert' : 'role-normal'">{{ myRole === 'expert' ? '专家' : '普通评委' }}</strong></span>
        <span class="reviewer-hint">（{{ myRole === 'expert' ? '可看到所有人判定并裁决' : '只判自己分到的样本' }}）</span>
      </div>

      <!-- 双 Tab -->
      <el-tabs v-model="activeTab" class="review-tabs">
        <!-- Tab 1: 人工标注 -->
        <el-tab-pane label="人工标注（产金标准）" name="annotation">
          <annotation-tab :task-id="selectedTaskId" :my-name="myName" :my-role="myRole"
            :models="models" :prompts="prompts" :model-map="modelMap" :prompt-map="promptMap" />
        </el-tab-pane>
        <!-- Tab 2: 人机对比 -->
        <el-tab-pane label="人机对比（AI vs 金标准）" name="compare">
          <compare-tab :task-id="selectedTaskId" :models="models" :prompts="prompts"
            :model-map="modelMap" :prompt-map="promptMap" />
        </el-tab-pane>
      </el-tabs>
    </template>
  </div>
</template>

<script>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { taskApi, modelApi, promptApi } from '../../api'
import AnnotationTab from './AnnotationTab.vue'
import CompareTab from './CompareTab.vue'

export default {
  name: 'ReviewPage',
  components: { AnnotationTab, CompareTab },
  setup() {
    const route = useRoute()
    const tasks = ref([])
    const selectedTaskId = ref(null)
    const models = ref([])
    const prompts = ref([])
    const modelMap = ref({})
    const promptMap = ref({})
    const activeTab = ref('annotation')
    const myName = ref(localStorage.getItem('eval-reviewer') || '')
    const myRole = ref(localStorage.getItem('eval-reviewer-role') || 'normal')

    const currentTask = computed(() => tasks.value.find(t => t.id === selectedTaskId.value) || null)
    const statusClass = (s) => ({ PENDING: 'pend', RUNNING: 'run', COMPLETED: 'done', FAILED: 'fail' }[s] || 'pend')

    const setRole = (r) => {
      myRole.value = r
      localStorage.setItem('eval-reviewer-role', r)
    }

    const loadTasks = async () => {
      const res = await taskApi.list(1, 100)
      tasks.value = res.data.records
      if (route.query.taskId) {
        selectedTaskId.value = Number(route.query.taskId)
      }
    }
    const loadMaps = async () => {
      const [modelRes, promptRes] = await Promise.all([modelApi.list(1, 100), promptApi.list()])
      models.value = modelRes.data.records
      prompts.value = promptRes.data.records
      modelRes.data.records.forEach(m => { modelMap.value[m.id] = m.name })
      promptRes.data.records.forEach(p => { promptMap.value[p.id] = p.name })
    }
    const onPickTask = (id) => { selectedTaskId.value = Number(id) }

    onMounted(() => { loadTasks(); loadMaps() })

    return {
      tasks, selectedTaskId, currentTask, onPickTask, statusClass,
      models, prompts, modelMap, promptMap,
      activeTab, myName, myRole, setRole
    }
  }
}
</script>

<style scoped>
.review-page { max-width: 1280px; margin: 0 auto; padding: 24px 20px; font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Microsoft YaHei', sans-serif; background: var(--bg-root); color: var(--text-prime); line-height: 1.6; }
.review-header { background: linear-gradient(135deg, #8b5cf6, #6d28d9); border-radius: 16px; padding: 28px 32px; margin-bottom: 20px; color: #fff; }
.review-header h1 { font-size: 22px; font-weight: 700; margin-bottom: 4px; }
.review-header .meta { font-size: 13px; opacity: 0.85; }

.toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 16px; padding: 10px 14px; background: var(--bg-card); border: 1px solid var(--border); border-radius: 12px; box-shadow: var(--shadow-sm); }
.toolbar-left { display: flex; align-items: center; gap: 10px; flex: 1; min-width: 0; }
.toolbar-right { display: flex; gap: 6px; }
.toolbar-icon { width: 32px; height: 32px; border-radius: 8px; background: rgba(139,92,246,0.12); color: #8b5cf6; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.task-trigger { display: inline-flex; align-items: center; gap: 6px; padding: 7px 14px; border-radius: 8px; border: 1px solid var(--border); background: var(--bg-input); cursor: pointer; max-width: 280px; }
.task-trigger:hover { border-color: #8b5cf6; }
.task-trigger-name { font-size: 13px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.task-trigger-ver { font-size: 11px; color: var(--text-mute); }
.trigger-caret { color: var(--text-mute); }

.role-chip { padding: 4px 14px; border-radius: 20px; font-size: 12px; cursor: pointer; border: 1px solid var(--border); background: var(--bg-input); color: var(--text-sec); user-select: none; }
.role-chip:hover { border-color: #8b5cf6; color: #8b5cf6; }
.role-chip.active { background: rgba(139,92,246,0.12); border-color: #8b5cf6; color: #8b5cf6; font-weight: 600; }

.dd-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 6px; }
.dd-dot.pend { background: var(--text-mute); } .dd-dot.run { background: var(--yellow); } .dd-dot.done { background: var(--accent); } .dd-dot.fail { background: var(--red); }
.dd-name { font-size: 13px; } .dd-ver { font-size: 11px; color: var(--text-mute); margin-left: 6px; }

.reviewer-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; padding: 8px 12px; background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; flex-wrap: wrap; }
.reviewer-label { font-size: 12px; font-weight: 600; color: var(--text-sec); }
.reviewer-role { font-size: 12px; color: var(--text-sec); }
.role-expert { color: #d97706; }
.role-normal { color: #8b5cf6; }
.reviewer-hint { font-size: 11px; color: var(--text-mute); }

.review-tabs :deep(.el-tabs__header) { margin-bottom: 14px; }
.review-tabs :deep(.el-tabs__item.is-active) { color: #8b5cf6; }
.review-tabs :deep(.el-tabs__active-bar) { background-color: #8b5cf6; }
</style>
