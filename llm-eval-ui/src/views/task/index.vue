<template>
  <div class="page-card">
    <div class="page-header">
      <div>
        <h2>评测任务</h2>
        <p class="page-sub">创建并管理评测任务，查看评测结果</p>
      </div>
      <el-button type="primary" @click="$router.push('/task/create')">
        <el-icon style="margin-right:4px"><Plus /></el-icon> 创建评测任务
      </el-button>
    </div>

    <!-- 统计卡片行 -->
    <div class="stats-row">
      <div class="stat-card">
        <div class="stat-icon green"><el-icon :size="18"><List /></el-icon></div>
        <div><div class="stat-num">{{ total }}</div><div class="stat-label">任务总数</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon blue"><el-icon :size="18"><VideoPlay /></el-icon></div>
        <div><div class="stat-num">{{ runningCount }}</div><div class="stat-label">运行中</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon orange"><el-icon :size="18"><CircleCheck /></el-icon></div>
        <div><div class="stat-num">{{ completedCount }}</div><div class="stat-label">已完成</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon red"><el-icon :size="18"><CircleClose /></el-icon></div>
        <div><div class="stat-num">{{ failedCount }}</div><div class="stat-label">失败</div></div>
      </div>
    </div>

    <!-- 任务卡片网格 -->
    <div class="task-grid" v-loading="loading">
      <div v-for="t in tasks" :key="t.id" class="task-card">
        <div class="task-card-head">
          <div class="task-status-dot" :class="statusClass(t.status)"></div>
          <div class="task-title">
            <div class="task-name">{{ t.name }}</div>
            <div class="task-meta">v{{ t.version }} · {{ datasetName(t.datasetId) }}</div>
          </div>
          <span class="task-status-tag" :class="statusClass(t.status)">{{ statusLabel(t.status) }}</span>
        </div>

        <div class="task-progress">
          <div class="progress-label">
            <span>进度</span>
            <span>{{ t.progress || getProgress(t.id) || 0 }}%</span>
          </div>
          <el-progress :percentage="t.progress || getProgress(t.id) || 0" :stroke-width="8"
            :status="t.status === 'COMPLETED' ? 'success' : t.status === 'FAILED' ? 'exception' : ''"
            :color="t.status === 'RUNNING' ? '#f59e0b' : undefined" />
        </div>

        <div class="task-card-footer">
          <span class="task-time"><el-icon :size="12"><Clock /></el-icon> {{ formatTime(t) }}</span>
          <div class="task-actions">
            <el-button v-if="t.status === 'PENDING'" size="small" type="success" @click="handleStart(t)">
              <el-icon style="margin-right:3px"><VideoPlay /></el-icon> 启动
            </el-button>
            <el-button v-if="t.status === 'RUNNING'" size="small" type="warning" @click="handleCancel(t)">
              <el-icon style="margin-right:3px"><CircleClose /></el-icon> 取消
            </el-button>
            <el-button v-if="t.status === 'RUNNING'" size="small" @click="refreshProgress(t)">
              <el-icon style="margin-right:3px"><Refresh /></el-icon> 刷新
            </el-button>
            <el-button v-if="t.status === 'COMPLETED'" size="small" type="primary" plain
              @click="$router.push({ path: '/result', query: { taskId: t.id } })">
              <el-icon style="margin-right:3px"><DataAnalysis /></el-icon> 查看结果
            </el-button>
          </div>
        </div>
      </div>
      <div v-if="!tasks.length && !loading" class="task-empty">
        <el-empty description="还没有评测任务，点击右上角创建" />
      </div>
    </div>

    <el-pagination
      v-model:current-page="page"
      v-model:page-size="size"
      :total="total"
      layout="total, prev, pager, next"
      @current-change="loadTasks"
    />
  </div>
</template>

<script>
import { ref, computed, onMounted } from 'vue'
import { taskApi, datasetApi } from '../../api'
import { ElMessage, ElMessageBox } from 'element-plus'

export default {
  name: 'TaskPage',
  setup() {
    const loading = ref(false)
    const tasks = ref([])
    const page = ref(1)
    const size = ref(10)
    const total = ref(0)
    const progressMap = ref({})
    const datasetMap = ref({})

    const runningCount = computed(() => tasks.value.filter(t => t.status === 'RUNNING').length)
    const completedCount = computed(() => tasks.value.filter(t => t.status === 'COMPLETED').length)
    const failedCount = computed(() => tasks.value.filter(t => t.status === 'FAILED').length)

    const loadTasks = async () => {
      loading.value = true
      try {
        const res = await taskApi.list(page.value, size.value)
        tasks.value = res.data.records
        total.value = res.data.total
        // 自动刷新运行中的任务进度
        tasks.value.filter(t => t.status === 'RUNNING').forEach(t => refreshProgress(t))
        // 加载数据集名称映射
        const dsRes = await datasetApi.list(1, 1000)
        dsRes.data.records.forEach(d => { datasetMap.value[d.id] = d.name })
      } finally {
        loading.value = false
      }
    }

    const datasetName = (id) => datasetMap.value[id] || `数据集#${id}`

    const statusClass = (status) => {
      const map = { PENDING: 'pend', RUNNING: 'run', COMPLETED: 'done', FAILED: 'fail' }
      return map[status] || 'pend'
    }
    const statusLabel = (status) => {
      const map = { PENDING: '待启动', RUNNING: '运行中', COMPLETED: '已完成', FAILED: '失败' }
      return map[status] || status
    }

    const formatTime = (row) => {
      const cellValue = row.createdAt
      if (!cellValue) return '-'
      const d = new Date(cellValue)
      if (isNaN(d.getTime())) return cellValue
      return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`
    }

    const getProgress = (id) => {
      return progressMap.value[id] || 0
    }

    const refreshProgress = async (row) => {
      try {
        const res = await taskApi.getProgress(row.id)
        progressMap.value[row.id] = res.data
        row.progress = res.data
        if (res.data >= 100) {
          row.status = 'COMPLETED'
        }
      } catch (e) {
        // ignore
      }
    }

    const handleStart = async (row) => {
      await ElMessageBox.confirm('确定启动该评测任务？', '提示', { type: 'warning' })
      await taskApi.start(row.id)
      ElMessage.success('任务已启动')
      loadTasks()
    }

    const handleCancel = async (row) => {
      await ElMessageBox.confirm('确定取消该评测任务？', '提示', { type: 'warning' })
      await taskApi.cancel(row.id)
      ElMessage.success('任务已取消')
      loadTasks()
    }

    onMounted(() => {
      loadTasks()
    })

    return {
      loading, tasks, page, size, total, loadTasks,
      runningCount, completedCount, failedCount,
      datasetName, statusClass, statusLabel, getProgress, refreshProgress,
      handleStart, handleCancel, formatTime
    }
  }
}
</script>

<style scoped>
.page-sub { color: var(--text-mute); font-size: 12px; margin-top: 2px; }
.stats-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 14px; margin-bottom: 20px; }
.stat-card {
  display: flex; align-items: center; gap: 12px;
  background: var(--bg-card); border: 1px solid var(--border);
  border-radius: 12px; padding: 14px 16px;
  box-shadow: var(--shadow-sm);
}
.stat-icon {
  width: 36px; height: 36px; border-radius: 10px;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.stat-icon.green { background: var(--accent-soft); color: var(--accent); }
.stat-icon.blue { background: rgba(99,102,241,0.12); color: #6366f1; }
.stat-icon.orange { background: rgba(245,158,11,0.12); color: #f59e0b; }
.stat-icon.red { background: var(--red-soft); color: var(--red); }
.stat-num { font-size: 20px; font-weight: 700; color: var(--text-prime); line-height: 1.1; }
.stat-label { font-size: 11px; color: var(--text-mute); margin-top: 2px; }

.task-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 14px;
  align-content: start;
  overflow-y: auto;
  min-height: 0;
  flex: 1;
  padding-right: 4px;
}
.task-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 14px 16px;
  box-shadow: var(--shadow-sm);
  transition: border-color 0.2s;
}
.task-card:hover { border-color: var(--accent); }
.task-card-head { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.task-status-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.task-status-dot.pend { background: var(--text-mute); }
.task-status-dot.run { background: var(--yellow); box-shadow: 0 0 6px var(--yellow-soft); }
.task-status-dot.done { background: var(--accent); }
.task-status-dot.fail { background: var(--red); }
.task-title { flex: 1; min-width: 0; }
.task-name { font-size: 14px; font-weight: 600; color: var(--text-prime); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.task-meta { font-size: 11px; color: var(--text-mute); margin-top: 2px; }
.task-status-tag {
  font-size: 11px; font-weight: 500; padding: 2px 10px; border-radius: 20px;
  flex-shrink: 0;
}
.task-status-tag.pend { background: rgba(148,163,184,0.12); color: var(--text-sec); }
.task-status-tag.run { background: var(--yellow-soft); color: var(--yellow); }
.task-status-tag.done { background: var(--accent-soft); color: var(--accent); }
.task-status-tag.fail { background: var(--red-soft); color: var(--red); }

.task-progress { margin-bottom: 12px; }
.progress-label {
  display: flex; justify-content: space-between;
  font-size: 11px; color: var(--text-mute); margin-bottom: 4px;
}

.task-card-footer {
  display: flex; justify-content: space-between; align-items: center;
  padding-top: 10px; border-top: 1px solid var(--border);
}
.task-time { display: flex; align-items: center; gap: 4px; font-size: 11px; color: var(--text-mute); }
.task-actions { display: flex; gap: 4px; }
.task-empty { grid-column: 1 / -1; }
</style>
