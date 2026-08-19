<template>
  <div class="page">
    <!-- 顶部工具栏 -->
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
      </div>
      <div class="toolbar-right">
        <el-button v-if="currentTask" size="small" type="primary" plain @click="downloadReport">
          <el-icon style="margin-right:4px"><Download /></el-icon> 下载报告
        </el-button>
      </div>
    </div>

    <el-empty v-if="!selectedTaskId" description="请选择评测任务查看结果" />

    <div v-if="loadingData" v-loading="true" class="loading-block" style="height: 300px"></div>

    <template v-if="selectedTaskId && task && !loadingData">
      <!-- 结果标题 -->
      <div class="report-header">
        <h1>{{ task.name }} · 结果看板</h1>
        <div class="meta">版本 v{{ task.version }} · 评估器：{{ promptNames }}</div>
        <div class="header-actions">
          <span class="tag" v-for="m in modelNames" :key="m">{{ m }}</span>
        </div>
      </div>

      <!-- KPI 大卡片行 -->
      <div class="kpi-row">
        <div class="kpi t">
          <div class="num">{{ overall.totalCount || 0 }}</div>
          <div class="label">总评测条数</div>
        </div>
        <div class="kpi g">
          <div class="num">{{ overall.goodCount || 0 }}</div>
          <div class="label">Goodcase（{{ overall.goodRate || 0 }}%）</div>
        </div>
        <div class="kpi b">
          <div class="num">{{ overall.badcaseCount || 0 }}</div>
          <div class="label">Badcase（{{ overall.badcaseRate || 0 }}%）</div>
        </div>
        <div class="kpi r">
          <div class="num">{{ overall.unknownCount || 0 }}</div>
          <div class="label">Unknown（{{ overall.unknownRate || 0 }}%）</div>
        </div>
      </div>

      <!-- 各维度 Badcase 率概览 -->
      <div class="section" v-if="dimSummaries.length">
        <div class="sec-hd"><h2>各维度 Badcase 率概览</h2></div>
        <div class="sec-bd">
          <div class="topic-grid">
            <div v-for="ds in dimSummaries" :key="ds.dimension"
              class="topic-card"
              :class="getDimClass(ds.badcaseRate)">
              <div class="tc-name">{{ ds.dimension }}</div>
              <div class="tc-rate">{{ (100 - ds.badcaseRate).toFixed(1) }}%</div>
              <div class="tc-sub">采纳率 · {{ (ds.goodCount ?? (ds.totalCount - ds.badcaseCount)) }}/{{ ds.totalCount }} Goodcase</div>
              <div class="tc-sub" v-if="(ds.unknownCount || 0) > 0">其中 {{ ds.unknownCount }} 条 Unknown（无法判断）</div>
              <div class="mini-bar">
                <div class="mini-bar-fill" :style="{ width: (100 - ds.badcaseRate) + '%' }"></div>
              </div>
            </div>
          </div>

          <!-- 关键发现 -->
          <div class="insight" v-if="dimSummaries.length">
            <div class="insight-title">📌 关键发现</div>
            <ul>
              <li v-if="worstDim">最大问题维度为「{{ worstDim.dimension }}」，Badcase 率 {{ worstDim.badcaseRate }}%，共 {{ worstDim.badcaseCount }} 条</li>
              <li v-if="bestDim">最优维度为「{{ bestDim.dimension }}」，Badcase 率仅 {{ bestDim.badcaseRate }}%</li>
              <li v-if="overall.badcaseRate > 0">整体 Badcase 率 {{ overall.badcaseRate }}%，{{ overall.badcaseRate > 20 ? '需要重点关注' : overall.badcaseRate > 10 ? '仍有改善空间' : '表现良好' }}</li>
            </ul>
          </div>
        </div>
      </div>

      <!-- 模型对比柱状图 -->
      <div class="section" v-if="summaries.length">
        <div class="sec-hd"><h2>Badcase 率模型对比</h2></div>
        <div class="sec-bd">
          <div ref="barRef" style="width: 100%; height: 380px;"></div>
        </div>
      </div>

      <!-- 同题多模型回答对比 -->
      <div class="section" v-if="compareGroups.length">
        <div class="sec-hd">
          <h2>同题模型回答对比</h2>
          <span class="sec-tip">同一问题下各模型的回答与 AI 判定并列展示</span>
        </div>
        <div class="sec-bd">
          <div class="cmp-group" v-for="(g, gi) in compareGroups" :key="g.datasetItemId">
            <div class="cmp-group-head">
              <span class="cmp-seq">#{{ gi + 1 }}</span>
              <span class="cmp-question">{{ g.question }}</span>
            </div>
            <div class="cmp-grid">
              <div class="cmp-cell" v-for="a in g.answers" :key="a.modelConfigId"
                :class="a.isBadcase === 1 ? 'bad' : a.isBadcase === 0 ? 'good' : 'unk'">
                <div class="cmp-cell-head">
                  <span class="cmp-model">{{ a.modelName }}</span>
                  <span :class="['cmp-status', a.isBadcase === 1 ? 'bad' : a.isBadcase === 0 ? 'good' : 'unk']">
                    {{ a.isBadcase === 1 ? 'Badcase' : a.isBadcase === 0 ? 'Goodcase' : 'Unknown' }}
                  </span>
                </div>
                <div class="cmp-answer">{{ a.response || '（无回答）' }}</div>
                <div class="cmp-reason" v-if="a.judgeReason">
                  <span class="cmp-reason-label">AI理由</span>
                  {{ a.judgeReason }}
                </div>
                <div class="cmp-latency" v-if="a.latencyMs">耗时 {{ a.latencyMs }}ms</div>
              </div>
            </div>
          </div>
          <div class="cmp-more" v-if="compareGroups.length < compareTotal">
            <el-button size="small" text type="primary" @click="loadMoreCompare">加载更多对比</el-button>
          </div>
        </div>
      </div>

      <!-- 各指标采纳率总览表 -->
      <div class="section" v-if="dimSummaries.length">
        <div class="sec-hd"><h2>各指标 Badcase 率总览</h2></div>
        <div class="sec-bd">
          <table class="ind-table">
            <thead>
              <tr>
                <th style="width:22%">维度</th>
                <th style="width:14%">模型</th>
                <th class="c" style="width:7%">Good</th>
                <th class="c" style="width:7%">Bad</th>
                <th class="c" style="width:8%">Unknown</th>
                <th class="c" style="width:8%">采纳率</th>
                <th style="width:20%">占比</th>
                <th style="width:14%">操作</th>
              </tr>
            </thead>
            <tbody>
              <!-- 整体行 -->
              <tr v-for="os in overallRows" :key="'o-' + os.modelConfigId">
                <td><strong>整体</strong></td>
                <td>{{ getModelName(os.modelConfigId) }}</td>
                <td class="c"><span class="badge badge-g">{{ os.goodCount ?? (os.totalCount - os.badcaseCount) }}</span></td>
                <td class="c"><span class="badge badge-r">{{ os.badcaseCount }}</span></td>
                <td class="c"><span class="badge badge-u">{{ os.unknownCount || 0 }}</span></td>
                <td class="c"><strong :style="{ color: os.badcaseRate > 20 ? 'var(--red)' : 'var(--accent)' }">{{ (100 - os.badcaseRate).toFixed(1) }}%</strong></td>
                <td>
                  <div class="hbar">
                    <div class="hbar-good" :style="{ width: goodPct(os) + '%' }">{{ Math.round(goodPct(os)) }}%</div>
                    <div class="hbar-bad" :style="{ width: os.badcaseRate + '%' }">{{ os.badcaseCount }}</div>
                    <div class="hbar-unk" v-if="(os.unknownCount || 0) > 0" :style="{ width: unkPct(os) + '%' }">{{ Math.round(unkPct(os)) }}%</div>
                  </div>
                </td>
                <td></td>
              </tr>
              <!-- 维度行 -->
              <tr v-for="ds in dimSummaries" :key="ds.dimension + '-' + ds.modelConfigId">
                <td>{{ ds.dimension }}</td>
                <td>{{ getModelName(ds.modelConfigId) }}</td>
                <td class="c"><span class="badge badge-g">{{ ds.goodCount ?? (ds.totalCount - ds.badcaseCount) }}</span></td>
                <td class="c"><span class="badge badge-r">{{ ds.badcaseCount }}</span></td>
                <td class="c"><span class="badge badge-u">{{ ds.unknownCount || 0 }}</span></td>
                <td class="c"><strong :style="{ color: ds.badcaseRate > 30 ? 'var(--red)' : ds.badcaseRate > 15 ? 'var(--yellow)' : 'var(--accent)' }">{{ (100 - ds.badcaseRate).toFixed(1) }}%</strong></td>
                <td>
                  <div class="hbar">
                    <div class="hbar-good" :style="{ width: goodPct(ds) + '%' }">{{ Math.round(goodPct(ds)) }}%</div>
                    <div class="hbar-bad" :style="{ width: ds.badcaseRate + '%' }">{{ ds.badcaseCount }}</div>
                    <div class="hbar-unk" v-if="(ds.unknownCount || 0) > 0" :style="{ width: unkPct(ds) + '%' }">{{ Math.round(unkPct(ds)) }}%</div>
                  </div>
                </td>
                <td>
                  <el-button size="small" text type="primary" @click="goBadcase(ds.dimension)">查看 Badcase</el-button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- 评测结果列表（具体 Case） -->
      <div class="section" v-if="judgeResults.length">
        <div class="sec-hd"><h2>评测结果列表（共 {{ judgeResults.length }} 条，展示前 50 条）</h2></div>
        <div class="sec-bd">
          <div class="filter-bar" style="margin-bottom: 12px;">
            <el-radio-group v-model="resultFilter" size="small">
              <el-radio-button label="">全部 {{ judgeResults.length }}</el-radio-button>
              <el-radio-button label="bad">Badcase {{ badcaseCount }}</el-radio-button>
              <el-radio-button label="good">Goodcase {{ goodcaseCount }}</el-radio-button>
              <el-radio-button label="unknown">Unknown {{ unknownCount }}</el-radio-button>
            </el-radio-group>
          </div>
          <div class="bc-card" v-for="(jr, i) in filteredJudgeResults" :key="jr.id">
            <div class="bc-head">
              <span class="bc-seq">#{{ i + 1 }}</span>
              <span class="bc-model">{{ jr.modelName || getModelName(jr.modelConfigId) }}</span>
              <span :class="['bc-status', jr.isBadcase === 1 ? 'bc-bad' : jr.isBadcase === 0 ? 'bc-good' : 'bc-unk']">
                {{ jr.isBadcase === 1 ? 'Badcase' : jr.isBadcase === 0 ? 'Goodcase' : (jr.judgeStatus === 'SKIP' ? '解析失败' : 'Unknown') }}
              </span>
              <div class="bc-dims" v-if="jr.isBadcase === 1">
                <span class="dim-badge" v-for="d in parseDims(jr.dimensions)" :key="d">{{ d }}</span>
              </div>
            </div>
            <div class="bc-body">
              <div class="bc-question" v-if="extractCaseInfo(jr).question">
                <span class="bc-label">问题</span>
                <span class="bc-text">{{ extractCaseInfo(jr).question }}</span>
              </div>
              <div class="bc-answer" v-if="extractCaseInfo(jr).answer">
                <span class="bc-label">回答</span>
                <span class="bc-text">{{ extractCaseInfo(jr).answer }}</span>
              </div>
              <div class="bc-reason" v-if="jr.reason">
                <span class="bc-label">判定</span>
                <span class="bc-text">{{ jr.reason }}</span>
              </div>
            </div>
          </div>
          <div class="no-bc" v-if="!filteredJudgeResults.length">无符合条件的评测结果</div>
        </div>
      </div>
    </template>
  </div>
</template>

<script>
import { ref, computed, onMounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { taskApi, resultApi, modelApi, promptApi } from '../../api'
// echarts 按需引入，减小首屏体积
import * as echarts from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

export default {
  name: 'ResultPage',
  setup() {
    const route = useRoute()
    const router = useRouter()
    const barRef = ref(null)
    const tasks = ref([])
    const selectedTaskId = ref(null)
    const task = ref(null)
    const summaries = ref([])
    const modelMap = ref({})
    const promptMap = ref({})
    const judgeResults = ref([])
    const resultFilter = ref('')
    const loadingData = ref(false)

    const modelNames = computed(() => [...new Set(summaries.value.map(s => modelMap.value[s.modelConfigId] || `模型${s.modelConfigId}`))])
    const promptNames = computed(() => [...new Set(summaries.value.map(s => promptMap.value[s.promptId] || `Prompt${s.promptId}`))].join('、'))

    const overallRows = computed(() => summaries.value.filter(s => !s.dimension))
    const overall = computed(() => {
      const rows = overallRows.value
      if (!rows.length) return { totalCount: 0, badcaseCount: 0, badcaseRate: 0, goodCount: 0, goodRate: 0, unknownCount: 0, unknownRate: 0 }
      // 合并多模型（三态：good / bad / unknown）
      const total = rows.reduce((a, s) => a + (s.totalCount || 0), 0)
      const bad = rows.reduce((a, s) => a + (s.badcaseCount || 0), 0)
      const good = rows.reduce((a, s) => a + (s.goodCount ?? (s.totalCount - s.badcaseCount)), 0)
      const unknown = total - good - bad
      const rate = total > 0 ? +(bad * 100 / total).toFixed(1) : 0
      return {
        totalCount: total, badcaseCount: bad, badcaseRate: rate,
        goodCount: good, goodRate: total ? (good * 100 / total).toFixed(1) : 0,
        unknownCount: unknown, unknownRate: total ? (unknown * 100 / total).toFixed(1) : 0
      }
    })

    const dimSummaries = computed(() => summaries.value.filter(s => s.dimension).sort((a, b) => b.badcaseRate - a.badcaseRate))
    const worstDim = computed(() => dimSummaries.value.length ? dimSummaries.value[0] : null)
    const bestDim = computed(() => dimSummaries.value.length ? dimSummaries.value[dimSummaries.value.length - 1] : null)

    const badcaseCount = computed(() => judgeResults.value.filter(jr => jr.isBadcase === 1).length)
    const goodcaseCount = computed(() => judgeResults.value.filter(jr => jr.isBadcase === 0).length)
    const unknownCount = computed(() => judgeResults.value.filter(jr => jr.isBadcase == null).length)
    const filteredJudgeResults = computed(() => {
      let list = judgeResults.value
      if (resultFilter.value === 'bad') list = list.filter(jr => jr.isBadcase === 1)
      else if (resultFilter.value === 'good') list = list.filter(jr => jr.isBadcase === 0)
      else if (resultFilter.value === 'unknown') list = list.filter(jr => jr.isBadcase == null)
      return list.slice(0, 50)
    })
    const parseDims = (dims) => { try { return JSON.parse(dims || '[]') } catch { return [] } }
    // 三态占比 helper（good/bad/unknown 平分 total）
    const goodPct = (s) => {
      const t = s.totalCount || 0
      const g = s.goodCount ?? (t - s.badcaseCount)
      return t ? +(g * 100 / t) : 0
    }
    const unkPct = (s) => {
      const t = s.totalCount || 0
      const u = s.unknownCount || 0
      return t ? +(u * 100 / t) : 0
    }

    // 从 extraFields JSON 中提取可读的问题和回答
    const extractCaseInfo = (jr) => {
      let question = jr.question || ''
      let answer = jr.modelResponse || ''
      if (!question && jr.extraFields) {
        try {
          const extra = JSON.parse(jr.extraFields)
          question = extra.caseDescription || extra.question || extra.输入 || ''
          if (extra.expected_path) question += (question ? ' → ' : '') + extra.expected_path
          answer = extra.path_summary || extra.actual_graph_path || extra.model_response || extra.模型回答 || ''
        } catch { /* ignore */ }
      }
      return { question, answer }
    }

    const getDimClass = (rate) => rate > 30 ? 'bad-t' : rate > 15 ? 'warn' : 'best'
    const getModelName = (id) => modelMap.value[id] || `模型${id}`
    const getPromptName = (id) => promptMap.value[id] || `Prompt${id}`

    const loadTasks = async () => {
      const res = await taskApi.list(1, 100)
      tasks.value = res.data.records
      if (route.query.taskId) {
        selectedTaskId.value = Number(route.query.taskId)
        // 等 maps 加载完再加载数据，避免模型名显示为"模型123"
        await Promise.all([loadMaps()])
        loadData()
      }
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
    const onSelectTask = (id) => {
      selectedTaskId.value = Number(id)
      loadData()
    }
    const loadMaps = async () => {
      const [modelRes, promptRes] = await Promise.all([modelApi.list(1, 100), promptApi.list()])
      modelRes.data.records.forEach(m => { modelMap.value[m.id] = m.name })
      promptRes.data.records.forEach(p => { promptMap.value[p.id] = p.name })
    }

    const loadData = async () => {
      if (!selectedTaskId.value) { task.value = null; summaries.value = []; judgeResults.value = []; return }
      loadingData.value = true
      try {
        const [taskRes, summaryRes, judgeRes] = await Promise.all([
          taskApi.getById(selectedTaskId.value),
          resultApi.getSummary(selectedTaskId.value),
          resultApi.listJudgeResults(selectedTaskId.value)
        ])
        task.value = taskRes.data
        summaries.value = summaryRes.data || []
        judgeResults.value = judgeRes.data || []
        // 加载模型对比数据
        loadCompareData()
        // 等待 DOM 更新后渲染图表
        await nextTick()
        renderBar()
      } finally {
        loadingData.value = false
      }
    }

    // ===== 模型对比 =====
    const allCompareGroups = ref([])
    const compareGroups = ref([])
    const compareTotal = ref(0)
    const COMPARE_PAGE_SIZE = 5

    const loadCompareData = async () => {
      if (!selectedTaskId.value) return
      try {
        const res = await resultApi.compareWithJudge(selectedTaskId.value)
        allCompareGroups.value = res.data || []
        compareTotal.value = allCompareGroups.value.length
        compareGroups.value = allCompareGroups.value.slice(0, COMPARE_PAGE_SIZE)
      } catch (e) { /* ignore */ }
    }

    const loadMoreCompare = () => {
      const cur = compareGroups.value.length
      compareGroups.value = allCompareGroups.value.slice(0, cur + COMPARE_PAGE_SIZE)
    }

    const renderBar = () => {
      if (!barRef.value) return
      const chart = echarts.init(barRef.value)
      const dimS = summaries.value.filter(s => s.dimension != null)
      const overallS = summaries.value.filter(s => s.dimension == null)
      const dimNames = [...new Set(dimS.map(s => s.dimension))]
      dimNames.unshift('整体')
      const mNames = [...new Set(summaries.value.map(s => getModelName(s.modelConfigId)))]

      const seriesData = mNames.map(mn => ({
        name: mn,
        type: 'bar',
        barMaxWidth: 40,
        itemStyle: { borderRadius: [4, 4, 0, 0] },
        data: dimNames.map(dn => {
          if (dn === '整体') {
            const found = overallS.find(s => getModelName(s.modelConfigId) === mn)
            return found ? found.badcaseRate : 0
          }
          const found = dimS.find(s => s.dimension === dn && getModelName(s.modelConfigId) === mn)
          return found ? found.badcaseRate : 0
        })
      }))

      chart.setOption({
        tooltip: { trigger: 'axis', formatter: (params) => {
          let s = params[0].axisValue + '<br/>'
          params.forEach(p => { s += `${p.marker} ${p.seriesName}: ${p.value}%<br/>` })
          return s
        }},
        legend: { data: mNames, top: 0 },
        grid: { top: 40, left: 50, right: 20, bottom: 30 },
        xAxis: { type: 'category', data: dimNames, axisLabel: { rotate: dimNames.length > 8 ? 30 : 0 } },
        yAxis: { type: 'value', name: 'Badcase率(%)', max: value => Math.min(Math.ceil(value.max / 10) * 10 + 10, 100) },
        series: seriesData
      })
    }

    const goBadcase = (dim) => {
      router.push({ path: '/badcase', query: { taskId: selectedTaskId.value, dimension: dim } })
    }

    const downloadReport = () => {
      if (!selectedTaskId.value) return
      window.open(`/api/reports/${selectedTaskId.value}/download`, '_blank')
    }

    onMounted(() => { loadTasks(); loadMaps() })

    return {
      barRef, tasks, selectedTaskId, task, summaries,
      modelNames, promptNames, overall, overallRows, dimSummaries, worstDim, bestDim,
      getDimClass, getModelName, getPromptName, loadData, goBadcase,
      judgeResults, resultFilter, filteredJudgeResults, badcaseCount, goodcaseCount, unknownCount, parseDims, extractCaseInfo,
      goodPct, unkPct,
      downloadReport, statusClass, statusLabel, currentTask, onSelectTask, loadingData,
      // 模型对比
      compareGroups, compareTotal, loadMoreCompare
    }
  }
}
</script>

<style scoped>
.page { max-width:1360px; margin:0 auto; padding:28px 20px; font-family:-apple-system,BlinkMacSystemFont,'PingFang SC','Hiragino Sans GB','Microsoft YaHei',sans-serif; background:var(--bg-root); color:var(--text-prime); line-height:1.6; font-size:14px; }
.report-header { background:linear-gradient(135deg,#10b981,#059669); border-radius:16px; padding:32px 36px; margin-bottom:24px; color:#fff; }
.header-actions { display:flex; align-items:center; gap:6px; margin-top:8px; flex-wrap:wrap; }
.download-btn { margin-left:auto; }
.report-header h1 { font-size:22px; font-weight:700; margin-bottom:4px; }
.report-header .meta { font-size:13px; opacity:.8; margin-top:6px; }
.tag { display:inline-block; background:rgba(255,255,255,.18); border-radius:20px; padding:2px 10px; font-size:12px; margin:4px 4px 0 0; }
.filter-bar { margin-bottom:20px; }
/* 顶部工具栏 */
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
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
.toolbar-right { flex-shrink: 0; }
/* 下拉菜单 */
.dd-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 6px; }
.dd-dot.pend { background: var(--text-mute); }
.dd-dot.run { background: var(--yellow); }
.dd-dot.done { background: var(--accent); }
.dd-dot.fail { background: var(--red); }
.dd-name { font-size: 13px; color: var(--text-prime); }
.dd-ver { font-size: 11px; color: var(--text-mute); margin-left: 6px; }
.kpi-row { display:grid; grid-template-columns:repeat(4,1fr); gap:14px; margin-bottom:24px; }
.kpi { background:var(--bg-card); border-radius:12px; padding:20px 22px; border:1px solid var(--border); position:relative; overflow:hidden; box-shadow:var(--shadow-sm); }
.kpi::before { content:''; position:absolute; top:0; left:0; right:0; height:3px; }
.kpi.t::before { background:var(--accent); } .kpi.g::before { background:var(--accent); } .kpi.b::before { background:var(--red); } .kpi.r::before { background:var(--red); }
.kpi .num { font-size:30px; font-weight:800; line-height:1; margin-bottom:4px; }
.kpi.t .num { color:var(--accent); } .kpi.g .num { color:var(--accent); } .kpi.b .num { color:var(--red); } .kpi.r .num { color:var(--red); }
.kpi .label { font-size:12px; color:var(--text-mute); }
.section { background:var(--bg-card); border-radius:12px; border:1px solid var(--border); margin-bottom:20px; overflow:hidden; box-shadow:var(--shadow-sm); }
.sec-hd { padding:18px 24px 0; }
.sec-hd h2 { font-size:15px; font-weight:700; color:var(--text-prime); }
.sec-hd h2::before { content:''; display:inline-block; width:4px; height:16px; background:var(--accent); border-radius:2px; margin-right:8px; vertical-align:middle; }
.sec-bd { padding:16px 24px 22px; }
.topic-grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(180px,1fr)); gap:12px; }
.topic-card { border-radius:10px; border:1.5px solid var(--border); padding:14px 16px; background:var(--bg-input); }
.tc-name { font-size:13px; font-weight:600; margin-bottom:8px; color:var(--text-prime); }
.tc-rate { font-size:24px; font-weight:800; line-height:1; }
.tc-sub { font-size:11px; color:var(--text-mute); margin-top:2px; }
.best .tc-rate { color:var(--accent); } .warn .tc-rate { color:var(--yellow); } .bad-t .tc-rate { color:var(--red); }
.mini-bar { height:6px; border-radius:3px; background:var(--bg-input); margin-top:10px; overflow:hidden; }
.mini-bar-fill { height:100%; border-radius:3px; background:var(--brand-gradient); }
.insight { background:var(--yellow-soft); border:1px solid rgba(251,191,36,0.2); border-radius:10px; padding:14px 18px; margin-top:14px; }
.insight-title { font-size:13px; font-weight:700; color:var(--yellow); margin-bottom:6px; }
.insight ul { padding-left:16px; }
.insight li { font-size:13px; color:var(--text-sec); margin-bottom:3px; line-height:1.6; }
.ind-table { width:100%; border-collapse:collapse; font-size:13px; }
.ind-table thead th { background:var(--bg-input); padding:9px 10px; font-weight:600; text-align:left; border-bottom:2px solid var(--border); color:var(--text-sec); }
.ind-table thead th.c { text-align:center; }
.ind-table tbody td { padding:9px 10px; border-bottom:1px solid var(--border); vertical-align:middle; color:var(--text-sec); }
.ind-table tbody tr:last-child td { border-bottom:none; }
.ind-table tbody tr:hover { background:var(--bg-card-hover); }
.badge { display:inline-block; padding:2px 8px; border-radius:20px; font-size:11px; font-weight:600; }
.badge-g { background:var(--accent-soft); color:var(--accent); } .badge-r { background:var(--red-soft); color:var(--red); }
.badge-u { background:var(--yellow-soft); color:var(--yellow); }
.hbar { display:flex; height:20px; border-radius:5px; overflow:hidden; background:var(--bg-input); min-width:140px; }
.hbar-good { background:var(--accent); display:flex; align-items:center; justify-content:flex-end; padding-right:5px; color:var(--accent-text); font-size:11px; font-weight:600; }
.hbar-bad { background:var(--red); display:flex; align-items:center; padding-left:4px; color:#fff; font-size:11px; font-weight:600; opacity:0.85; }
.hbar-unk { background:var(--yellow); display:flex; align-items:center; justify-content:center; color:#fff; font-size:11px; font-weight:600; }
.bc-card { border:1px solid var(--border); border-radius:10px; margin-bottom:8px; overflow:hidden; background:var(--bg-card); }
.bc-head { background:var(--bg-input); padding:9px 14px; display:flex; align-items:center; gap:10px; flex-wrap: wrap; }
.bc-seq { font-size:11px; color:var(--text-mute); font-weight:500; white-space:nowrap; }
.bc-model { font-size:12px; font-weight:600; color:var(--accent); }
.bc-status { font-size:11px; font-weight:600; padding:2px 8px; border-radius:20px; }
.bc-status.bc-bad { background:var(--red-soft); color:var(--red); }
.bc-status.bc-good { background:var(--accent-soft); color:var(--accent); }
.bc-status.bc-unk { background:var(--yellow-soft); color:var(--yellow); }
.bc-dims { display: flex; gap: 4px; flex-wrap: wrap; }
.dim-badge { display:inline-block; background:var(--accent-soft); color:var(--accent); border-radius:20px; padding:2px 8px; font-size:11px; font-weight:600; }
.bc-body { padding:9px 14px; font-size:12px; color:var(--text-sec); line-height:1.7; border-top:1px solid var(--border); }
.bc-question, .bc-answer, .bc-reason { margin-bottom: 6px; }
.bc-label { display:inline-block; background:var(--bg-input); border-radius:4px; padding:1px 6px; font-size:10px; font-weight:600; color:var(--text-mute); margin-right:6px; vertical-align:top; }
.bc-question .bc-label { background:var(--accent-soft); color:var(--accent); }
.bc-answer .bc-label { background:var(--accent-soft); color:var(--accent); }
.bc-reason .bc-label { background:var(--accent-soft); color:var(--accent); }
.bc-text { font-size:12px; color:var(--text-sec); line-height:1.7; word-break: break-all; }
.no-bc { color:var(--text-mute); font-size:13px; padding:8px 0; }

/* ===== 人工校验 ===== */
.sec-tip { font-size: 11px; color: var(--text-mute); margin-left: 8px; font-weight: 400; }
.review-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}
.review-stat {
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 12px 14px;
  text-align: center;
}
.rs-num {
  font-size: 22px;
  font-weight: 800;
  color: var(--accent);
  line-height: 1.2;
}
.rs-num.agree { color: var(--accent); }
.rs-num.disagree { color: var(--red); }
.rs-label { font-size: 11px; color: var(--text-mute); margin-top: 3px; }
.review-tip {
  background: var(--yellow-soft);
  border: 1px solid rgba(251,191,36,0.25);
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 12px;
  color: var(--text-sec);
  margin-bottom: 14px;
  line-height: 1.6;
}
.review-tip.good {
  background: var(--accent-soft);
  border-color: rgba(16,185,129,0.2);
  color: var(--accent);
}
.review-sample-list { display: flex; flex-direction: column; gap: 8px; }
.review-card {
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
  background: var(--bg-card);
}
.review-card-head {
  background: var(--bg-input);
  padding: 8px 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.review-ai-tag, .review-human-tag, .review-agree-tag {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 8px;
  border-radius: 20px;
}
.review-ai-tag.bad { background: var(--red-soft); color: var(--red); }
.review-ai-tag.good { background: var(--accent-soft); color: var(--accent); }
.review-human-tag.bad { background: rgba(245,158,11,0.15); color: #d97706; }
.review-human-tag.good { background: rgba(16,185,129,0.1); color: var(--accent); }
.review-agree-tag.ok { background: var(--accent-soft); color: var(--accent); }
.review-agree-tag.no { background: var(--red-soft); color: var(--red); }
.review-card-body { padding: 9px 14px; }
.review-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--border);
}
.review-act-label { font-size: 12px; color: var(--text-mute); }
.review-comment { font-size: 11px; color: var(--text-mute); margin-left: 6px; }
.review-more { text-align: center; padding: 6px 0; }
.reviewer-input-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
  padding: 8px 12px;
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: 8px;
}
.reviewer-input-label { font-size: 12px; font-weight: 600; color: var(--text-sec); }

/* ===== 同题模型对比 ===== */
.cmp-group {
  border: 1px solid var(--border);
  border-radius: 10px;
  margin-bottom: 14px;
  overflow: hidden;
  background: var(--bg-card);
}
.cmp-group-head {
  background: var(--bg-input);
  padding: 9px 14px;
  display: flex;
  align-items: flex-start;
  gap: 10px;
}
.cmp-seq { font-size: 11px; color: var(--text-mute); font-weight: 600; margin-top: 1px; flex-shrink: 0; }
.cmp-question { font-size: 13px; font-weight: 600; color: var(--text-prime); line-height: 1.5; }
.cmp-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 8px;
  padding: 10px 12px;
}
.cmp-cell {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 10px 12px;
  background: var(--bg-card);
}
.cmp-cell.bad { border-color: rgba(239,68,68,0.35); }
.cmp-cell.good { border-color: rgba(16,185,129,0.35); }
.cmp-cell.unk { border-color: rgba(245,158,11,0.4); }
.cmp-cell-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.cmp-model { font-size: 12px; font-weight: 700; color: var(--accent); }
.cmp-status { font-size: 11px; font-weight: 600; padding: 1px 8px; border-radius: 20px; }
.cmp-status.bad { background: var(--red-soft); color: var(--red); }
.cmp-status.good { background: var(--accent-soft); color: var(--accent); }
.cmp-status.unk { background: rgba(245,158,11,0.15); color: #d97706; }
.cmp-status.none { background: var(--bg-input); color: var(--text-mute); }
.cmp-answer {
  font-size: 12px;
  color: var(--text-sec);
  line-height: 1.7;
  max-height: 120px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
.cmp-reason {
  font-size: 11px;
  color: var(--text-mute);
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px dashed var(--border);
  line-height: 1.6;
}
.cmp-reason-label {
  display: inline-block;
  background: var(--bg-input);
  border-radius: 4px;
  padding: 0 5px;
  font-size: 10px;
  font-weight: 600;
  color: var(--text-mute);
  margin-right: 4px;
}
.cmp-latency { font-size: 10px; color: var(--text-mute); margin-top: 4px; }
.cmp-more { text-align: center; padding: 6px 0; }
</style>
