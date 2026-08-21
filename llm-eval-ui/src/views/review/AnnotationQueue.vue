<template>
  <div class="annotation-queue-page">
    <!-- 页面标题 -->
    <div class="page-header">
      <h2>人工审核</h2>
    </div>
    <!-- 顶部工具栏 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <span class="toolbar-icon"><el-icon :size="16"><EditPen /></el-icon></span>
        <el-dropdown trigger="click" @command="onSelectTask" v-if="tasks.length">
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
        <span class="status-badge" v-if="currentTask" :class="statusClass(currentTask.status)">
          {{ statusLabel(currentTask.status) }}
        </span>
      </div>
    </div>

    <!-- 角色切换 + 身份（默认带入登录账号，可手动修改并记忆） -->
    <div class="reviewer-bar">
      <span class="reviewer-label">我的身份：</span>
      <el-input v-model="myName" placeholder="名字/工号" size="small" style="width: 160px" clearable />
      <span class="role-chip" :class="{ active: myRole === 'normal' }" @click="setRole('normal')">普通评委</span>
      <span class="role-chip" :class="{ active: myRole === 'expert' }" @click="setRole('expert')">专家</span>
      <span class="reviewer-hint" v-if="myRole === 'expert'">（可看所有人判定并裁决）</span>
    </div>

    <el-empty v-if="!selectedTaskId" description="选择左侧/上方任务下拉，进入人工审核" />

    <template v-if="selectedTaskId">
      <!-- 人工标注 -->
      <el-tabs v-model="subTab" class="review-sub-tabs">
        <el-tab-pane name="annotation">
          <template #label><span class="tab-label"><el-icon :size="13"><User /></el-icon> 人工标注</span></template>
          <annotation-tab :task-id="selectedTaskId" :my-name="myName" :my-role="myRole"
            :models="models" :prompts="prompts" :model-map="modelMap" :prompt-map="promptMap"
            :focus-sample="focusSample" />
        </el-tab-pane>
      </el-tabs>
    </template>
  </div>
</template>

<script>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { taskApi, modelApi, promptApi } from '../../api'
import { readLoggedUsername } from '../../utils/user'
import AnnotationTab from './AnnotationTab.vue'

// 标注者身份：优先用用户手填记忆过的名字，否则自动带入登录账号
const readReviewerName = () =>
  localStorage.getItem('eval-reviewer') || readLoggedUsername() || ''

export default {
  name: 'AnnotationQueue',
  components: { AnnotationTab },
  setup() {
    const route = useRoute()
    const router = useRouter()
    const tasks = ref([])
    const selectedTaskId = ref(null)
    const models = ref([])
    const prompts = ref([])
    const modelMap = ref({})
    const promptMap = ref({})
    const myName = ref(readReviewerName())
    const myRole = ref(localStorage.getItem('eval-reviewer-role') || 'normal')
    const subTab = ref('annotation')
    const focusSample = ref(null)

    const currentTask = computed(() => tasks.value.find(t => t.id === selectedTaskId.value) || null)
    const statusClass = (s) => ({ PENDING: 'pend', RUNNING: 'run', COMPLETED: 'done', FAILED: 'fail', CANCELLED: 'fail' }[s] || 'pend')
    const statusLabel = (s) => ({ PENDING: '待启动', RUNNING: '运行中', COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消' }[s] || s)

    const setRole = (r) => {
      myRole.value = r
      localStorage.setItem('eval-reviewer-role', r)
    }
    watch(myName, (v) => localStorage.setItem('eval-reviewer', (v || '').trim()))

    const loadTasks = async () => {
      const res = await taskApi.list(1, 100)
      tasks.value = res.data.records.filter(t => t.status === 'COMPLETED')
      // 从 query 参数恢复
      if (route.query.taskId) {
        selectedTaskId.value = Number(route.query.taskId)
      }
    }
    const loadMaps = async () => {
      const [modelRes, promptRes] = await Promise.all([modelApi.list(1, 100, undefined, 1), promptApi.list()])
      models.value = modelRes.data.records
      prompts.value = promptRes.data.records
      modelRes.data.records.forEach(m => { modelMap.value[m.id] = m.name })
      promptRes.data.records.forEach(p => { promptMap.value[p.id] = p.name })
    }
    const onSelectTask = (id) => {
      selectedTaskId.value = Number(id)
      focusSample.value = null
      router.replace({ query: { taskId: id } })
    }

    // 从结果分析跳转过来时携带 focusSample
    watch(() => route.query.focusModelConfigId, (v) => {
      if (v && selectedTaskId.value) {
        focusSample.value = {
          modelConfigId: Number(v),
          datasetItemId: route.query.focusDatasetItemId ? Number(route.query.focusDatasetItemId) : undefined,
          promptId: route.query.focusPromptId ? Number(route.query.focusPromptId) : undefined
        }
        subTab.value = 'annotation'
      }
    })

    onMounted(() => { loadTasks(); loadMaps() })

    return {
      tasks, selectedTaskId, currentTask, onSelectTask, statusClass, statusLabel,
      models, prompts, modelMap, promptMap, myName, myRole, setRole,
      subTab, focusSample
    }
  }
}
</script>

<style scoped>
.annotation-queue-page {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 14px;
  box-shadow: var(--shadow-card);
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  padding: 20px 24px;
  font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Microsoft YaHei', sans-serif;
  color: var(--text-prime);
  line-height: 1.6;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  flex-shrink: 0;
}
.page-header h2 {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-prime);
  margin: 0;
}
.toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 16px; padding: 10px 14px; background: var(--bg-card); border: 1px solid var(--border); border-radius: 12px; box-shadow: var(--shadow-sm); }
.toolbar-left { display: flex; align-items: center; gap: 10px; flex: 1; min-width: 0; }
.toolbar-icon { width: 32px; height: 32px; border-radius: 8px; background: rgba(139,92,246,0.12); color: #8b5cf6; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.task-trigger { display: inline-flex; align-items: center; gap: 6px; padding: 7px 14px; border-radius: 8px; border: 1px solid var(--border); background: var(--bg-input); cursor: pointer; max-width: 300px; }
.task-trigger:hover { border-color: #8b5cf6; }
.task-trigger-name { font-size: 13px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.task-trigger-ver { font-size: 11px; color: var(--text-mute); }
.trigger-caret { color: var(--text-mute); }
.status-badge { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; font-weight: 500; padding: 3px 10px; border-radius: 20px; }
.status-badge.pend { background: rgba(148,163,184,0.12); color: var(--text-sec); }
.status-badge.run { background: var(--yellow-soft); color: var(--yellow); }
.status-badge.done { background: var(--accent-soft); color: var(--accent); }
.status-badge.fail { background: var(--red-soft); color: var(--red); }
.dd-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 6px; }
.dd-dot.pend { background: var(--text-mute); } .dd-dot.run { background: var(--yellow); } .dd-dot.done { background: var(--accent); } .dd-dot.fail { background: var(--red); }
.dd-name { font-size: 13px; color: var(--text-prime); }
.dd-ver { font-size: 11px; color: var(--text-mute); margin-left: 6px; }

.reviewer-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; padding: 8px 12px; background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; flex-wrap: wrap; }
.reviewer-label { font-size: 12px; font-weight: 600; color: var(--text-sec); }
.reviewer-hint { font-size: 11px; color: var(--text-mute); }
.role-chip { padding: 3px 12px; border-radius: 20px; font-size: 11px; cursor: pointer; border: 1px solid var(--border); background: var(--bg-input); color: var(--text-sec); user-select: none; }
.role-chip:hover { border-color: #8b5cf6; color: #8b5cf6; }
.role-chip.active { background: rgba(139,92,246,0.12); border-color: #8b5cf6; color: #8b5cf6; font-weight: 600; }

.review-sub-tabs { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.review-sub-tabs :deep(.el-tabs__header) { margin-bottom: 12px; flex-shrink: 0; }
.review-sub-tabs :deep(.el-tabs__content) { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.review-sub-tabs :deep(.el-tab-pane) { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.review-sub-tabs :deep(.el-tabs__item.is-active) { color: #8b5cf6; }
.review-sub-tabs :deep(.el-tabs__active-bar) { background-color: #8b5cf6; }
.tab-label { display: inline-flex; align-items: center; gap: 4px; }
</style>
