<template>
  <div class="page">
    <!-- 渐变色 Header -->
    <div class="report-header">
      <h1>Badcase 详情分析</h1>
      <div class="meta">按维度和模型筛选查看 Badcase 条目</div>
    </div>

    <div class="toolbar">
      <div class="toolbar-left">
        <span class="toolbar-icon"><el-icon :size="16"><Warning /></el-icon></span>
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
        <span class="status-badge" v-if="currentTask" :class="statusClass(currentTask.status)">
          {{ statusLabel(currentTask.status) }}
        </span>
      </div>
    </div>

    <div class="filter-bar" v-if="selectedTaskId">
      <el-select v-model="selectedPromptId" placeholder="筛选Prompt" @change="loadBadcases" clearable style="width: 200px">
        <el-option v-for="p in prompts" :key="p.id" :label="p.name" :value="p.id" />
      </el-select>
      <el-select v-model="selectedModelId" placeholder="筛选模型" @change="loadBadcases" clearable style="width: 180px">
        <el-option v-for="m in models" :key="m.id" :label="m.name" :value="m.id" />
      </el-select>
      <el-input v-model="searchKeyword" placeholder="搜索问题 / 回答 / 判定理由" clearable size="default"
        style="width: 260px" @keyup.enter="onSearch" @clear="onSearch">
        <template #append><el-button @click="onSearch">搜索</el-button></template>
      </el-input>
    </div>

    <el-empty v-if="!selectedTaskId" description="请选择评测任务查看 Badcase" />

    <template v-if="selectedTaskId">
      <!-- KPI -->
      <div class="kpi-row">
        <div class="kpi r">
          <div class="num">{{ total }}</div>
          <div class="label">Badcase 总数</div>
        </div>
        <div class="kpi b">
          <div class="num">{{ dimCounts.length }}</div>
          <div class="label">涉及维度</div>
        </div>
      </div>

      <!-- 维度 Tab 切换 -->
      <div class="section" v-if="dimCounts.length">
        <div class="sec-hd"><h2>按维度浏览 Badcase</h2></div>
        <div class="sec-bd">
          <div class="tab-bar">
            <button class="tab-btn" :class="{ active: !selectedDim }" @click="selectedDim = ''; loadBadcases()">全部 <span class="tc">{{ total }}</span></button>
            <button v-for="dc in dimCounts" :key="dc.dim"
              class="tab-btn" :class="{ active: selectedDim === dc.dim }"
              @click="selectedDim = dc.dim; loadBadcases()">
              {{ dc.dim }} <span class="tc">{{ dc.count }}</span>
            </button>
          </div>
        </div>
      </div>

      <!-- Badcase 列表卡片 -->
      <div class="section">
        <div class="sec-hd"><h2>Badcase 列表</h2></div>
        <div class="sec-bd">
          <div v-loading="loading">
            <div class="bc-card" v-for="(bc, i) in badcases" :key="bc.id">
              <div class="bc-head">
                <span class="bc-seq">#{{ i + 1 }}</span>
                <span class="bc-model">{{ bc.modelName || getModelName(bc.modelConfigId) }}</span>
                <div class="bc-dims">
                  <span class="dim-badge" v-for="d in parseDims(bc.dimensions)" :key="d">{{ d }}</span>
                </div>
              </div>
              <div class="bc-body">
                <div class="bc-question" v-if="extractCaseInfo(bc).question">
                  <span class="bc-label">问题</span>
                  <span class="bc-text">{{ extractCaseInfo(bc).question }}</span>
                </div>
                <div class="bc-answer" v-if="extractCaseInfo(bc).answer">
                  <span class="bc-label">回答</span>
                  <span class="bc-text">{{ extractCaseInfo(bc).answer }}</span>
                </div>
                <div class="bc-reason" v-if="bc.reason">
                  <span class="bc-label">判定</span>
                  <span class="bc-text">{{ bc.reason }}</span>
                </div>
              </div>
            </div>
            <div class="bc-more" v-if="total > size">共 {{ total }} 条，当前第 {{ page }} 页</div>
            <div class="no-bc" v-if="!badcases.length && !loading">暂无 Badcase</div>
          </div>

          <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
            layout="total, prev, pager, next" @current-change="loadBadcases" />
        </div>
      </div>
    </template>
  </div>
</template>

<script>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { taskApi, resultApi, modelApi, promptApi } from '../../api'

export default {
  name: 'BadcasePage',
  setup() {
    const route = useRoute()
    const loading = ref(false)
    const badcases = ref([])
    const page = ref(1)
    const size = ref(20)
    const total = ref(0)
    const tasks = ref([])
    const selectedTaskId = ref(null)
    const prompts = ref([])
    const selectedPromptId = ref(null)
    const models = ref([])
    const selectedModelId = ref(null)
    const modelMap = ref({})
    const promptMap = ref({})
    const selectedDim = ref('')
    const searchKeyword = ref('')
    // 维度计数来自 summary 接口（全量统计，非当前页）
    const dimCounts = ref([])

    const loadDimCounts = async () => {
      if (!selectedTaskId.value) { dimCounts.value = []; return }
      try {
        const res = await resultApi.getSummary(selectedTaskId.value)
        const map = {}
        res.data.filter(s => s.dimension).forEach(s => {
          map[s.dimension] = (map[s.dimension] || 0) + (s.badcaseCount || 0)
        })
        dimCounts.value = Object.entries(map).map(([dim, count]) => ({ dim, count })).sort((a, b) => b.count - a.count)
      } catch (e) { dimCounts.value = [] }
    }

    const loadTasks = async () => {
      const res = await taskApi.list(1, 100)
      tasks.value = res.data.records
      if (route.query.taskId) {
        selectedTaskId.value = Number(route.query.taskId)
        if (route.query.dimension) selectedDim.value = route.query.dimension
        loadDimCounts()
        loadBadcases()
      }
    }
    const loadMaps = async () => {
      const [modelRes, promptRes] = await Promise.all([modelApi.list(1, 100), promptApi.list()])
      models.value = modelRes.data.records
      modelRes.data.records.forEach(m => { modelMap.value[m.id] = m.name })
      prompts.value = promptRes.data.records
      promptRes.data.records.forEach(p => { promptMap.value[p.id] = p.name })
    }
    const getModelName = (id) => modelMap.value[id] || `模型${id}`
    const getPromptName = (id) => promptMap.value[id] || `Prompt${id}`
    const parseDims = (dims) => { try { return JSON.parse(dims || '[]') } catch { return [] } }

    // 从 extraFields JSON 中提取可读的问题和回答
    const extractCaseInfo = (bc) => {
      let question = bc.question || ''
      let answer = bc.modelResponse || ''
      if (!question && bc.extraFields) {
        try {
          const extra = JSON.parse(bc.extraFields)
          question = extra.caseDescription || extra.question || extra.输入 || ''
          if (extra.expected_path) question += (question ? ' → ' : '') + extra.expected_path
          answer = extra.path_summary || extra.actual_graph_path || extra.model_response || extra.模型回答 || ''
        } catch { /* ignore */ }
      }
      return { question, answer }
    }

    const onTaskChange = () => { selectedDim.value = ''; searchKeyword.value = ''; page.value = 1; loadDimCounts(); loadBadcases() }
    const onPickTask = (id) => {
      selectedTaskId.value = Number(id)
      selectedDim.value = ''
      searchKeyword.value = ''
      page.value = 1
      loadDimCounts()
      loadBadcases()
    }
    const statusClass = (s) => {
      const map = { PENDING: 'pend', RUNNING: 'run', COMPLETED: 'done', FAILED: 'fail', CANCELLED: 'fail' }
      return map[s] || 'pend'
    }
    const statusLabel = (s) => {
      const map = { PENDING: '待启动', RUNNING: '运行中', COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消' }
      return map[s] || s
    }
    const currentTask = computed(() => tasks.value.find(t => t.id === selectedTaskId.value) || null)
    const onSearch = () => { page.value = 1; loadBadcases() }

    const loadBadcases = async () => {
      if (!selectedTaskId.value) { badcases.value = []; total.value = 0; return }
      loading.value = true
      try {
        const params = {
          promptId: selectedPromptId.value || undefined,
          modelConfigId: selectedModelId.value || undefined,
          dimension: selectedDim.value || undefined,
          keyword: searchKeyword.value || undefined,
          page: page.value, size: size.value
        }
        const res = await resultApi.listBadcases(selectedTaskId.value, params)
        badcases.value = res.data.records
        total.value = res.data.total
      } finally { loading.value = false }
    }

    onMounted(() => { loadTasks(); loadMaps() })

    return {
      loading, badcases, page, size, total, loadBadcases,
      tasks, selectedTaskId, prompts, selectedPromptId, models, selectedModelId,
      getModelName, getPromptName, parseDims, onTaskChange, onPickTask, statusClass, statusLabel, currentTask,
      selectedDim, dimCounts, extractCaseInfo, searchKeyword, onSearch
    }
  }
}
</script>

<style scoped>
.page { max-width:1360px; margin:0 auto; padding:28px 20px; font-family:-apple-system,BlinkMacSystemFont,'PingFang SC','Hiragino Sans GB','Microsoft YaHei',sans-serif; background:var(--bg-root); color:var(--text-prime); line-height:1.6; font-size:14px; }
.report-header { background:linear-gradient(135deg,#10b981,#059669); border-radius:16px; padding:32px 36px; margin-bottom:24px; color:#fff; }
.report-header h1 { font-size:22px; font-weight:700; margin-bottom:4px; }
.report-header .meta { font-size:13px; opacity:.8; margin-top:6px; }
.filter-bar { margin-bottom:14px; display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
/* 顶部工具栏 */
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
  padding: 10px 14px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 12px;
  box-shadow: var(--shadow-sm);
}
.toolbar-left {
  display: flex; align-items: center; gap: 10px; flex: 1; min-width: 0;
}
.toolbar-icon {
  width: 32px; height: 32px;
  border-radius: 8px;
  background: var(--accent-soft);
  color: var(--accent);
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.task-trigger {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 7px 14px;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: var(--bg-input);
  cursor: pointer;
  transition: all 0.15s;
  max-width: 320px;
}
.task-trigger:hover { border-color: var(--accent); }
.task-trigger-name {
  font-size: 13px; font-weight: 600; color: var(--text-prime);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.task-trigger-ver { font-size: 11px; color: var(--text-mute); }
.trigger-caret { color: var(--text-mute); }
.status-badge {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: 11px; font-weight: 500;
  padding: 3px 10px; border-radius: 20px;
}
.status-badge.pend { background: rgba(148,163,184,0.12); color: var(--text-sec); }
.status-badge.run { background: var(--yellow-soft); color: var(--yellow); }
.status-badge.done { background: var(--accent-soft); color: var(--accent); }
.status-badge.fail { background: var(--red-soft); color: var(--red); }
.dd-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 6px; }
.dd-dot.pend { background: var(--text-mute); }
.dd-dot.run { background: var(--yellow); }
.dd-dot.done { background: var(--accent); }
.dd-dot.fail { background: var(--red); }
.dd-name { font-size: 13px; color: var(--text-prime); }
.dd-ver { font-size: 11px; color: var(--text-mute); margin-left: 6px; }
.kpi-row { display:grid; grid-template-columns:repeat(2,1fr); gap:14px; margin-bottom:24px; }
.kpi {
  background: var(--bg-card); border-radius:12px; padding:20px 22px;
  border:1px solid var(--border); position:relative; overflow:hidden;
  box-shadow: var(--shadow-sm);
}
.kpi::before { content:''; position:absolute; top:0; left:0; right:0; height:3px; }
.kpi.r::before { background: var(--accent); } .kpi.b::before { background: #10b981; }
.kpi .num { font-size:30px; font-weight:800; line-height:1; margin-bottom:4px; }
.kpi.r .num { color: var(--accent); } .kpi.b .num { color: var(--accent); }
.kpi .label { font-size:12px; color: var(--text-mute); }
.sec-hd h2::before { background: var(--accent); }
.tab-bar { display:flex; gap:6px; flex-wrap:wrap; }
.tab-btn {
  padding:4px 12px; border-radius:20px;
  border:1px solid var(--border);
  background: var(--bg-input);
  cursor:pointer; font-size:12px; font-weight:500; color:var(--text-sec);
  transition: all 0.15s;
}
.tab-btn:hover { border-color: var(--accent); color: var(--text-prime); }
.tab-btn.active {
  background: var(--accent); border-color: var(--accent); color: var(--accent-text);
  font-weight: 600;
}
.tab-btn .tc { display:inline-block; background:rgba(255,255,255,.3); border-radius:10px; padding:0 5px; font-size:10px; margin-left:3px; }
.tab-btn:not(.active) .tc { background: var(--accent-soft); color: var(--accent); }
.bc-card {
  border:1px solid var(--border); border-radius:10px; margin-bottom:8px; overflow:hidden;
  background: var(--bg-card);
  box-shadow: var(--shadow-xs);
}
.bc-head { background: var(--bg-input); padding:9px 14px; display:flex; align-items:center; gap:10px; flex-wrap: wrap; }
.bc-seq { font-size:11px; color:var(--text-mute); font-weight:500; white-space:nowrap; }
.bc-model { font-size:12px; font-weight:600; color: var(--accent); }
.bc-dims { display: flex; gap: 4px; flex-wrap: wrap; }
.dim-badge {
  display:inline-block;
  background: var(--accent-soft);
  color: var(--accent);
  border-radius:20px; padding:2px 8px; font-size:11px; font-weight:600;
}
.bc-body { padding:9px 14px; font-size:12px; color:var(--text-sec); line-height:1.7; border-top:1px solid var(--border); }
.bc-question, .bc-answer, .bc-reason { margin-bottom: 6px; }
.bc-label {
  display:inline-block;
  background: var(--bg-input);
  border-radius:4px; padding:1px 6px;
  font-size:10px; font-weight:600; color:var(--text-mute);
  margin-right:6px; vertical-align:top;
}
.bc-question .bc-label { background: var(--accent-soft); color: var(--accent); }
.bc-answer .bc-label { background: var(--accent-soft); color: var(--accent); }
.bc-reason .bc-label { background: var(--accent-soft); color: var(--accent); }
.bc-text { font-size:12px; color:var(--text-sec); line-height:1.7; word-break: break-all; }
.bc-more { text-align:center; font-size:12px; color:var(--text-mute); padding:8px; background:var(--bg-input); border-radius:6px; margin-top:4px; }
.no-bc { color:var(--text-mute); font-size:13px; padding:8px 0; }
</style>
