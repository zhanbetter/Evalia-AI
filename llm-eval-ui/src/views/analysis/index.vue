<template>
  <div class="page analysis-page">
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
          版本 v{{ currentTask.version }} · 评估器：{{ promptNames }}
        </span>
      </div>
      <div class="toolbar-right">
        <!-- 版本对比选择器 -->
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
      <!-- 三视图 Tab -->
      <el-tabs v-model="activeView" class="analysis-tabs" @tab-change="onTabChange">
        <el-tab-pane name="overview">
          <template #label><span class="tab-label"><el-icon :size="14"><DataLine /></el-icon> 概览</span></template>
          <overview-tab :task-id="selectedTaskId" :compare-task-id="compareTaskId"
            :model-map="modelMap" :prompt-map="promptMap" @go-badcase="goBadcase" />
        </el-tab-pane>
        <el-tab-pane name="cases">
          <template #label><span class="tab-label"><el-icon :size="14"><Warning /></el-icon> 失败案例</span></template>
          <cases-tab :task-id="selectedTaskId" :models="models" :prompts="prompts"
            :model-map="modelMap" :prompt-map="promptMap" :initial-dimension="pendingDim"
            @go-review="goReview" />
        </el-tab-pane>
        <el-tab-pane name="review">
          <template #label><span class="tab-label"><el-icon :size="14"><EditPen /></el-icon> 人工校验</span></template>
          <review-tab :task-id="selectedTaskId" :models="models" :prompts="prompts"
            :model-map="modelMap" :prompt-map="promptMap" :focus-sample="focusSample" />
        </el-tab-pane>
      </el-tabs>
    </template>
  </div>
</template>

<script>
import { ref, computed, onMounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { taskApi, modelApi, promptApi } from '../../api'
import OverviewTab from './OverviewTab.vue'
import CasesTab from './CasesTab.vue'
import ReviewTab from './ReviewTab.vue'

export default {
  name: 'AnalysisPage',
  components: { OverviewTab, CasesTab, ReviewTab },
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
    const focusSample = ref(null)

    const currentTask = computed(() => tasks.value.find(t => t.id === selectedTaskId.value) || null)
    // 可对比的任务：同一名称的其他版本（排除当前）
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
    const promptNames = computed(() => '')
    const statusClass = (s) => ({ PENDING: 'pend', RUNNING: 'run', COMPLETED: 'done', FAILED: 'fail' }[s] || 'pend')
    const statusLabel = (s) => ({ PENDING: '待启动', RUNNING: '运行中', COMPLETED: '已完成', FAILED: '失败' }[s] || s)

    const loadTasks = async () => {
      const res = await taskApi.list(1, 100)
      tasks.value = res.data.records
      if (route.query.taskId) {
        selectedTaskId.value = Number(route.query.taskId)
      }
      if (route.query.view) {
        activeView.value = route.query.view
      }
      if (route.query.dimension) {
        pendingDim.value = route.query.dimension
      }
    }
    const loadMaps = async () => {
      const [modelRes, promptRes] = await Promise.all([modelApi.list(1, 100), promptApi.list()])
      models.value = modelRes.data.records
      prompts.value = promptRes.data.records
      modelRes.data.records.forEach(m => { modelMap.value[m.id] = m.name })
      promptRes.data.records.forEach(p => { promptMap.value[p.id] = p.name })
    }
    const onSelectTask = (id) => {
      selectedTaskId.value = Number(id)
      compareTaskId.value = null
      pendingDim.value = ''
      focusSample.value = null
      activeView.value = 'overview'
    }
    const onCompareChange = () => {
      // 对比任务变化，概览会自行刷新（watch compareTaskId）
    }
    const onTabChange = () => { pendingDim.value = '' }

    const goBadcase = (dim) => {
      pendingDim.value = dim
      activeView.value = 'cases'
      nextTick(() => { pendingDim.value = dim })
    }
    const goReview = (sample) => {
      focusSample.value = sample
      activeView.value = 'review'
      nextTick(() => { focusSample.value = sample })
    }

    const downloadReport = () => {
      if (!selectedTaskId.value) return
      window.open(`/api/reports/${selectedTaskId.value}/download`, '_blank')
    }

    onMounted(() => { loadTasks(); loadMaps() })

    return {
      tasks, selectedTaskId, compareTaskId, availableCompareTasks, onCompareChange,
      currentTask, onSelectTask, statusClass, statusLabel,
      models, prompts, modelMap, promptMap, activeView, onTabChange,
      pendingDim, focusSample, goBadcase, goReview, downloadReport, promptNames
    }
  }
}
</script>

<style scoped>
.analysis-page { max-width: 1280px; margin: 0 auto; padding: 24px 20px; font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Microsoft YaHei', sans-serif; background: var(--bg-root); color: var(--text-prime); line-height: 1.6; }
.toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 16px; padding: 10px 14px; background: var(--bg-card); border: 1px solid var(--border); border-radius: 12px; box-shadow: var(--shadow-sm); }
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

.analysis-tabs :deep(.el-tabs__header) { margin-bottom: 16px; }
.analysis-tabs :deep(.el-tabs__item) { font-size: 14px; }
.analysis-tabs :deep(.el-tabs__item.is-active) { color: #06b6d4; font-weight: 600; }
.analysis-tabs :deep(.el-tabs__active-bar) { background-color: #06b6d4; }
.tab-label { display: inline-flex; align-items: center; gap: 5px; }
</style>
