<template>
  <div class="analysis-page">
    <!-- 页面标题 -->
    <div class="page-header">
      <h2>结果分析</h2>
    </div>
    <!-- 顶部工具栏：共享任务上下文 + 版本对比 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <span class="toolbar-icon"><el-icon :size="16"><DataAnalysis /></el-icon></span>
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
        <span class="task-meta" v-if="currentTask">
          版本 v{{ currentTask.version }}
        </span>
      </div>
      <div class="toolbar-right">
        <div class="compare-selector" v-if="tasks.length">
          <span class="compare-label">对比：</span>
          <el-select v-model="compareTaskId" placeholder="选对比版本" clearable size="small" style="width: 180px"
            :disabled="!selectedTaskId" @change="onCompareChange">
            <el-option v-for="t in availableCompareTasks" :key="t.id"
              :label="`v${t.version} · ${t.name}`" :value="t.id" />
          </el-select>
          <el-button v-if="compareTaskId" size="small" text type="primary" @click="compareTaskId = null">
            清除对比
          </el-button>
        </div>
        <el-button v-if="currentTask" size="small" type="primary" plain @click="downloadReport">
          <el-icon style="margin-right:4px"><Download /></el-icon> 下载报告
        </el-button>
      </div>
    </div>

    <el-empty v-if="!selectedTaskId" description="请选择评测任务查看结果分析" />

    <template v-if="selectedTaskId">
      <el-tabs v-model="activeView" class="analysis-tabs" @tab-change="onTabChange">
        <el-tab-pane name="overview">
          <template #label><span class="tab-label"><el-icon :size="14"><DataLine /></el-icon> 概览</span></template>
          <overview-tab :task-id="selectedTaskId" :compare-task-id="compareTaskId"
            :model-map="modelMap" :prompt-map="promptMap" @go-badcase="goBadcase" />
        </el-tab-pane>
        <el-tab-pane name="all">
          <template #label><span class="tab-label"><el-icon :size="14"><List /></el-icon> 全部结果</span></template>
          <all-results-tab :task-id="selectedTaskId" :models="models"
            :model-map="modelMap" />
        </el-tab-pane>
        <el-tab-pane name="cases">
          <template #label><span class="tab-label"><el-icon :size="14"><Warning /></el-icon> 失败案例</span></template>
          <cases-tab :task-id="selectedTaskId" :models="models" :prompts="prompts"
            :model-map="modelMap" :prompt-map="promptMap" :initial-dimension="pendingDim" />
        </el-tab-pane>
      </el-tabs>
    </template>
  </div>
</template>

<script>
import { ref, computed, watch, nextTick, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { taskApi, modelApi, promptApi, reportApi } from '../../api'
import OverviewTab from './OverviewTab.vue'
import CasesTab from './CasesTab.vue'
import AllResultsTab from './AllResultsTab.vue'

export default {
  name: 'AnalysisPage',
  components: { OverviewTab, CasesTab, AllResultsTab },
  setup() {
    const route = useRoute()
    const tasks = ref([])
    const selectedTaskId = ref(null)
    const compareTaskId = ref(null)
    const models = ref([])
    const prompts = ref([])
    const modelMap = ref({})
    const promptMap = ref({})
    const activeView = ref('overview')
    const pendingDim = ref('')

    const currentTask = computed(() => tasks.value.find(t => t.id === selectedTaskId.value) || null)
    const availableCompareTasks = computed(() => {
      if (!selectedTaskId.value) return []
      const current = currentTask.value
      if (!current) return []
      return tasks.value.filter(t =>
        t.id !== selectedTaskId.value &&
        t.name === current.name &&
        t.status === 'COMPLETED'
      ).sort((a, b) => b.version - a.version)
    })
    const statusClass = (s) => ({ PENDING: 'pend', RUNNING: 'run', COMPLETED: 'done', FAILED: 'fail', CANCELLED: 'fail' }[s] || 'pend')
    const statusLabel = (s) => ({ PENDING: '待启动', RUNNING: '运行中', COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消' }[s] || s)

    const loadTasks = async () => {
      const res = await taskApi.list(1, 100)
      tasks.value = res.data.records
      if (route.query.taskId) {
        selectedTaskId.value = Number(route.query.taskId)
      } else if (tasks.value.length) {
        const completed = tasks.value.find(t => t.status === 'COMPLETED')
        if (completed) selectedTaskId.value = completed.id
        else selectedTaskId.value = tasks.value[0].id
      }
      if (route.query.view) {
        activeView.value = route.query.view
      }
      if (route.query.dimension) {
        pendingDim.value = route.query.dimension
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
      compareTaskId.value = null
      pendingDim.value = ''
      activeView.value = 'overview'
    }
    const onCompareChange = () => {}
    const onTabChange = () => { pendingDim.value = '' }

    const goBadcase = (dim) => {
      pendingDim.value = dim
      activeView.value = 'cases'
      nextTick(() => { pendingDim.value = dim })
    }

    const downloadReport = async () => {
      if (!selectedTaskId.value) return
      try {
        await reportApi.download(selectedTaskId.value)
      } catch (e) {
        ElMessage.error('报告下载失败')
      }
    }

    onMounted(() => { loadTasks(); loadMaps() })

    return {
      tasks, selectedTaskId, compareTaskId, availableCompareTasks, onCompareChange,
      currentTask, onSelectTask, statusClass, statusLabel,
      models, prompts, modelMap, promptMap, activeView, onTabChange,
      pendingDim, goBadcase, downloadReport
    }
  }
}
</script>

<style scoped>
.analysis-page {
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
.toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 16px; padding: 10px 14px; background: var(--bg-card); border: 1px solid var(--border); border-radius: 12px; box-shadow: var(--shadow-sm); flex-shrink: 0; }
.toolbar-left { display: flex; align-items: center; gap: 10px; flex: 1; min-width: 0; }
.toolbar-right { flex-shrink: 0; display: flex; align-items: center; gap: 8px; }
.compare-selector { display: flex; align-items: center; gap: 6px; }
.compare-label { font-size: 12px; font-weight: 600; color: var(--text-sec); }
.toolbar-icon { width: 32px; height: 32px; border-radius: 8px; background: rgba(6,182,212,0.12); color: #06b6d4; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.task-trigger { display: inline-flex; align-items: center; gap: 6px; padding: 7px 14px; border-radius: 8px; border: 1px solid var(--border); background: var(--bg-input); cursor: pointer; max-width: 300px; }
.task-trigger:hover { border-color: #06b6d4; }
.task-trigger-name { font-size: 13px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.task-trigger-ver { font-size: 11px; color: var(--text-mute); }
.trigger-caret { color: var(--text-mute); }
.status-badge { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; font-weight: 500; padding: 3px 10px; border-radius: 20px; }
.status-badge.pend { background: rgba(148,163,184,0.12); color: var(--text-sec); }
.status-badge.run { background: var(--yellow-soft); color: var(--yellow); }
.status-badge.done { background: var(--accent-soft); color: var(--accent); }
.status-badge.fail { background: var(--red-soft); color: var(--red); }
.task-meta { font-size: 11px; color: var(--text-mute); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dd-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 6px; }
.dd-dot.pend { background: var(--text-mute); } .dd-dot.run { background: var(--yellow); } .dd-dot.done { background: var(--accent); } .dd-dot.fail { background: var(--red); }
.dd-name { font-size: 13px; color: var(--text-prime); }
.dd-ver { font-size: 11px; color: var(--text-mute); margin-left: 6px; }

.analysis-tabs { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.analysis-tabs :deep(.el-tabs__header) { margin-bottom: 16px; flex-shrink: 0; }
.analysis-tabs :deep(.el-tabs__content) { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.analysis-tabs :deep(.el-tab-pane) { flex: 1; min-height: 0; overflow-y: auto; }
.analysis-tabs :deep(.el-tabs__item) { font-size: 14px; }
.analysis-tabs :deep(.el-tabs__item.is-active) { color: #06b6d4; font-weight: 600; }
.analysis-tabs :deep(.el-tabs__active-bar) { background-color: #06b6d4; }
.tab-label { display: inline-flex; align-items: center; gap: 5px; }
</style>
