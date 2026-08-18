<template>
  <div class="overview-tab">
    <div v-if="loadingData" v-loading="true" style="height: 200px"></div>
    <template v-if="!loadingData">
      <!-- KPI 大卡片行 -->
      <div class="kpi-row">
        <div class="kpi t"><div class="num">{{ overall.totalCount || 0 }}</div><div class="label">总评测条数</div></div>
        <div class="kpi g"><div class="num">{{ overall.goodCount || 0 }}</div><div class="label">Goodcase（{{ overall.goodRate || 0 }}%）</div></div>
        <div class="kpi b"><div class="num">{{ overall.badcaseCount || 0 }}</div><div class="label">Badcase（{{ overall.badcaseRate || 0 }}%）</div></div>
        <div class="kpi r"><div class="num">{{ overall.badcaseRate || 0 }}%</div><div class="label">整体 Badcase 率</div></div>
      </div>

      <!-- 版本对比趋势 -->
      <div class="section version-compare" v-if="compareOverall && rateDelta !== null">
        <div class="sec-hd">
          <h2>版本对比</h2>
          <span class="sec-tip">当前 vs 对比版本 · badcase 率变化</span>
        </div>
        <div class="sec-bd">
          <div class="vc-topline">
            <div class="vc-item">
              <span class="vc-label">对比版本</span>
              <span class="vc-value">{{ compareOverall.badcaseRate }}%</span>
            </div>
            <div class="vc-arrow">→</div>
            <div class="vc-item">
              <span class="vc-label">当前版本</span>
              <span class="vc-value">{{ overall.badcaseRate }}%</span>
            </div>
            <div :class="['vc-delta', rateDelta < 0 ? 'improve' : rateDelta > 0 ? 'worse' : 'same']">
              <el-icon :size="16"><component :is="rateDelta <= 0 ? 'Top' : 'Bottom'" /></el-icon>
              {{ rateDelta > 0 ? '+' : '' }}{{ rateDelta }}pp
              <span class="vc-delta-text">{{ rateDelta <= 0 ? '改善' : '恶化' }}</span>
            </div>
          </div>

          <!-- 维度变化表 -->
          <table class="vc-table" v-if="dimCompareRows.length">
            <thead>
              <tr>
                <th>维度</th>
                <th>对比版本</th>
                <th>当前版本</th>
                <th>变化</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in dimCompareRows" :key="row.dimension">
                <td>{{ row.dimension }}</td>
                <td>{{ row.prev !== null ? row.prev + '%' : '-' }}</td>
                <td>{{ row.current }}%</td>
                <td>
                  <span v-if="row.delta === null" class="vc-new">新增维度</span>
                  <span v-else :class="['vc-delta-chip', row.delta < 0 ? 'improve' : row.delta > 0 ? 'worse' : 'same']">
                    {{ row.delta > 0 ? '+' : '' }}{{ row.delta }}pp
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- 各维度 Badcase 率概览 -->
      <div class="section" v-if="dimSummaries.length">
        <div class="sec-hd"><h2>各维度 Badcase 率概览</h2></div>
        <div class="sec-bd">
          <div class="topic-grid">
            <div v-for="ds in dimSummaries" :key="ds.dimension" class="topic-card" :class="getDimClass(ds.badcaseRate)"
              @click="goBadcase(ds.dimension)" title="点击查看该维度 Badcase">
              <div class="tc-name">{{ ds.dimension }}</div>
              <div class="tc-rate">{{ (100 - ds.badcaseRate).toFixed(1) }}%</div>
              <div class="tc-sub">采纳率 · {{ ds.totalCount - ds.badcaseCount }}/{{ ds.totalCount }} Goodcase</div>
              <div class="mini-bar"><div class="mini-bar-fill" :style="{ width: (100 - ds.badcaseRate) + '%' }"></div></div>
            </div>
          </div>
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
        <div class="sec-bd"><div ref="barRef" style="width: 100%; height: 380px;"></div></div>
      </div>

      <!-- 同题多模型回答对比 -->
      <div class="section" v-if="compareGroups.length">
        <div class="sec-hd">
          <h2>同题模型回答对比</h2>
          <span class="sec-tip">同一问题下各模型的回答与 AI 判定并列展示</span>
        </div>
        <div class="sec-bd">
          <div class="cmp-group" v-for="(g, gi) in compareGroups" :key="g.datasetItemId">
            <div class="cmp-group-head"><span class="cmp-seq">#{{ gi + 1 }}</span><span class="cmp-question">{{ g.question }}</span></div>
            <div class="cmp-grid">
              <div class="cmp-cell" v-for="a in g.answers" :key="a.modelConfigId" :class="a.isBadcase === 1 ? 'bad' : 'good'">
                <div class="cmp-cell-head">
                  <span class="cmp-model">{{ a.modelName }}</span>
                  <span :class="['cmp-status', a.isBadcase === 1 ? 'bad' : a.isBadcase === 0 ? 'good' : 'none']">
                    {{ a.isBadcase === 1 ? 'Badcase' : a.isBadcase === 0 ? 'Goodcase' : '未判定' }}
                  </span>
                </div>
                <div class="cmp-answer">{{ a.response || '（无回答）' }}</div>
                <div class="cmp-reason" v-if="a.judgeReason"><span class="cmp-reason-label">AI理由</span>{{ a.judgeReason }}</div>
                <div class="cmp-latency" v-if="a.latencyMs">耗时 {{ a.latencyMs }}ms</div>
              </div>
            </div>
          </div>
          <div class="cmp-more" v-if="compareGroups.length < compareTotal">
            <el-button size="small" text type="primary" @click="loadMoreCompare">加载更多对比</el-button>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script>
import { ref, computed, watch, nextTick } from 'vue'
import { resultApi } from '../../api'
// echarts 按需引入
import * as echarts from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
echarts.use([BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

export default {
  name: 'OverviewTab',
  props: {
    taskId: Number, compareTaskId: Number, modelMap: Object, promptMap: Object
  },
  setup(props, { emit }) {
    const barRef = ref(null)
    const summaries = ref([])
    const compareSummaries = ref([])
    const judgeResults = ref([])
    const loadingData = ref(false)
    const allCompareGroups = ref([])
    const compareGroups = ref([])
    const compareTotal = ref(0)
    const COMPARE_PAGE_SIZE = 5

    const modelNames = computed(() => [...new Set(summaries.value.map(s => props.modelMap[s.modelConfigId] || `模型${s.modelConfigId}`))])

    const overallRows = computed(() => summaries.value.filter(s => !s.dimension))
    const overall = computed(() => {
      const rows = overallRows.value
      if (!rows.length) return { totalCount: 0, badcaseCount: 0, badcaseRate: 0, goodCount: 0, goodRate: 0 }
      const total = rows.reduce((a, s) => a + (s.totalCount || 0), 0)
      const bad = rows.reduce((a, s) => a + (s.badcaseCount || 0), 0)
      const rate = total > 0 ? +(bad * 100 / total).toFixed(1) : 0
      return { totalCount: total, badcaseCount: bad, badcaseRate: rate, goodCount: total - bad, goodRate: (total ? ((total - bad) * 100 / total).toFixed(1) : 0) }
    })

    // ===== 版本对比 =====
    const compareOverall = computed(() => {
      const rows = compareSummaries.value.filter(s => !s.dimension)
      if (!rows.length) return null
      const total = rows.reduce((a, s) => a + (s.totalCount || 0), 0)
      const bad = rows.reduce((a, s) => a + (s.badcaseCount || 0), 0)
      const rate = total > 0 ? +(bad * 100 / total).toFixed(1) : 0
      return { totalCount: total, badcaseCount: bad, badcaseRate: rate, goodCount: total - bad }
    })
    // badcase 率变化
    const rateDelta = computed(() => {
      if (!compareOverall.value || !overall.value) return null
      return +(overall.value.badcaseRate - compareOverall.value.badcaseRate).toFixed(1)
    })
    const dimSummaries = computed(() => summaries.value.filter(s => s.dimension).sort((a, b) => b.badcaseRate - a.badcaseRate))
    // 维度对比数据
    const dimCompareRows = computed(() => {
      if (!compareSummaries.value.length) return []
      const cmpMap = {}
      compareSummaries.value.filter(s => s.dimension).forEach(s => { cmpMap[s.dimension] = s.badcaseRate })
      return dimSummaries.value.map(ds => {
        const prev = cmpMap[ds.dimension]
        return {
          dimension: ds.dimension,
          current: ds.badcaseRate,
          prev: prev !== undefined ? prev : null,
          delta: prev !== undefined ? +(ds.badcaseRate - prev).toFixed(1) : null
        }
      }).sort((a, b) => (b.delta !== null ? Math.abs(b.delta) : 0) - (a.delta !== null ? Math.abs(a.delta) : 0))
    })
    const worstDim = computed(() => dimSummaries.value.length ? dimSummaries.value[0] : null)
    const bestDim = computed(() => dimSummaries.value.length ? dimSummaries.value[dimSummaries.value.length - 1] : null)

    const getDimClass = (rate) => rate > 30 ? 'bad-t' : rate > 15 ? 'warn' : 'best'
    const getModelName = (id) => props.modelMap[id] || `模型${id}`

    const loadData = async () => {
      if (!props.taskId) return
      loadingData.value = true
      try {
        const [summaryRes, judgeRes] = await Promise.all([
          resultApi.getSummary(props.taskId),
          resultApi.listJudgeResults(props.taskId)
        ])
        summaries.value = summaryRes.data || []
        judgeResults.value = judgeRes.data || []
        loadCompareData()
        loadCompareTaskData()
        await nextTick()
        renderBar()
      } finally { loadingData.value = false }
    }

    // 加载对比任务的数据
    const loadCompareTaskData = async () => {
      if (!props.compareTaskId) { compareSummaries.value = []; return }
      try {
        const res = await resultApi.getSummary(props.compareTaskId)
        compareSummaries.value = res.data || []
      } catch (e) { compareSummaries.value = [] }
    }

    const loadCompareData = async () => {
      if (!props.taskId) return
      try {
        const res = await resultApi.compareWithJudge(props.taskId)
        allCompareGroups.value = res.data || []
        compareTotal.value = allCompareGroups.value.length
        compareGroups.value = allCompareGroups.value.slice(0, COMPARE_PAGE_SIZE)
      } catch (e) { /* */ }
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
        name: mn, type: 'bar', barMaxWidth: 40,
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
        tooltip: { trigger: 'axis', formatter: (params) => { let s = params[0].axisValue + '<br/>'; params.forEach(p => { s += `${p.marker} ${p.seriesName}: ${p.value}%<br/>` }); return s } },
        legend: { data: mNames, top: 0 },
        grid: { top: 40, left: 50, right: 20, bottom: 30 },
        xAxis: { type: 'category', data: dimNames, axisLabel: { rotate: dimNames.length > 8 ? 30 : 0 } },
        yAxis: { type: 'value', name: 'Badcase率(%)', max: value => Math.min(Math.ceil(value.max / 10) * 10 + 10, 100) },
        series: seriesData
      })
    }

    const goBadcase = (dim) => emit('go-badcase', dim)

    watch(() => props.taskId, () => { if (props.taskId) loadData() }, { immediate: true })
    watch(() => props.compareTaskId, () => { loadCompareTaskData() })

    return {
      barRef, summaries, loadingData, overall, dimSummaries, worstDim, bestDim,
      getDimClass, getModelName, compareGroups, compareTotal, loadMoreCompare, goBadcase,
      // 版本对比
      compareOverall, rateDelta, dimCompareRows
    }
  }
}
</script>

<style scoped>
.kpi-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 20px; }
.kpi { background: var(--bg-card); border-radius: 12px; padding: 20px 22px; border: 1px solid var(--border); position: relative; overflow: hidden; box-shadow: var(--shadow-sm); }
.kpi::before { content: ''; position: absolute; top: 0; left: 0; right: 0; height: 3px; }
.kpi.t::before { background: var(--accent); } .kpi.g::before { background: var(--accent); } .kpi.b::before { background: var(--red); } .kpi.r::before { background: var(--red); }
.kpi .num { font-size: 30px; font-weight: 800; line-height: 1; margin-bottom: 4px; }
.kpi.t .num { color: var(--accent); } .kpi.g .num { color: var(--accent); } .kpi.b .num { color: var(--red); } .kpi.r .num { color: var(--red); }
.kpi .label { font-size: 12px; color: var(--text-mute); }
.section { background: var(--bg-card); border-radius: 12px; border: 1px solid var(--border); margin-bottom: 20px; overflow: hidden; box-shadow: var(--shadow-sm); }
.sec-hd { padding: 18px 24px 0; display: flex; align-items: center; gap: 8px; }
.sec-hd h2 { font-size: 15px; font-weight: 700; color: var(--text-prime); }
.sec-hd h2::before { content: ''; display: inline-block; width: 4px; height: 16px; background: var(--accent); border-radius: 2px; margin-right: 8px; vertical-align: middle; }
.sec-tip { font-size: 11px; color: var(--text-mute); font-weight: 400; }
.sec-bd { padding: 16px 24px 22px; }

/* 版本对比 */
.version-compare { border-top: 3px solid #06b6d4; }
.version-compare .sec-hd h2::before { background: #06b6d4; }
.vc-topline { display: flex; align-items: center; gap: 16px; padding: 6px 0 14px; flex-wrap: wrap; }
.vc-item { text-align: center; }
.vc-label { display: block; font-size: 11px; color: var(--text-mute); margin-bottom: 2px; }
.vc-value { font-size: 24px; font-weight: 800; color: var(--text-prime); }
.vc-arrow { color: var(--text-mute); font-size: 18px; }
.vc-delta { display: inline-flex; align-items: center; gap: 4px; font-size: 15px; font-weight: 800; padding: 6px 14px; border-radius: 20px; }
.vc-delta.improve { background: var(--accent-soft); color: var(--accent); }
.vc-delta.worse { background: var(--red-soft); color: var(--red); }
.vc-delta.same { background: var(--bg-input); color: var(--text-mute); }
.vc-delta-text { font-size: 11px; font-weight: 600; }
.vc-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.vc-table thead th { background: var(--bg-input); padding: 8px 10px; font-weight: 600; text-align: left; border-bottom: 2px solid var(--border); color: var(--text-sec); }
.vc-table tbody td { padding: 8px 10px; border-bottom: 1px solid var(--border); color: var(--text-sec); }
.vc-table tbody tr:last-child td { border-bottom: none; }
.vc-table tbody tr:hover { background: var(--bg-card-hover); }
.vc-delta-chip { font-size: 11px; font-weight: 700; padding: 2px 8px; border-radius: 20px; }
.vc-delta-chip.improve { background: var(--accent-soft); color: var(--accent); }
.vc-delta-chip.worse { background: var(--red-soft); color: var(--red); }
.vc-delta-chip.same { background: var(--bg-input); color: var(--text-mute); }
.vc-new { font-size: 11px; font-weight: 600; color: #06b6d4; background: rgba(6,182,212,0.1); padding: 2px 8px; border-radius: 20px; }
.topic-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 12px; }
.topic-card { border-radius: 10px; border: 1.5px solid var(--border); padding: 14px 16px; background: var(--bg-input); cursor: pointer; transition: all 0.15s; }
.topic-card:hover { border-color: var(--accent); transform: translateY(-1px); }
.tc-name { font-size: 13px; font-weight: 600; margin-bottom: 8px; color: var(--text-prime); }
.tc-rate { font-size: 24px; font-weight: 800; line-height: 1; }
.tc-sub { font-size: 11px; color: var(--text-mute); margin-top: 2px; }
.best .tc-rate { color: var(--accent); } .warn .tc-rate { color: var(--yellow); } .bad-t .tc-rate { color: var(--red); }
.mini-bar { height: 6px; border-radius: 3px; background: var(--bg-input); margin-top: 10px; overflow: hidden; }
.mini-bar-fill { height: 100%; border-radius: 3px; background: var(--brand-gradient); }
.insight { background: var(--yellow-soft); border: 1px solid rgba(251,191,36,0.2); border-radius: 10px; padding: 14px 18px; margin-top: 14px; }
.insight-title { font-size: 13px; font-weight: 700; color: var(--yellow); margin-bottom: 6px; }
.insight ul { padding-left: 16px; }
.insight li { font-size: 13px; color: var(--text-sec); margin-bottom: 3px; line-height: 1.6; }

/* 同题对比 */
.cmp-group { border: 1px solid var(--border); border-radius: 10px; margin-bottom: 14px; overflow: hidden; background: var(--bg-card); }
.cmp-group-head { background: var(--bg-input); padding: 9px 14px; display: flex; align-items: flex-start; gap: 10px; }
.cmp-seq { font-size: 11px; color: var(--text-mute); font-weight: 600; margin-top: 1px; flex-shrink: 0; }
.cmp-question { font-size: 13px; font-weight: 600; color: var(--text-prime); line-height: 1.5; }
.cmp-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 8px; padding: 10px 12px; }
.cmp-cell { border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; background: var(--bg-card); }
.cmp-cell.bad { border-color: rgba(239,68,68,0.35); }
.cmp-cell.good { border-color: rgba(16,185,129,0.35); }
.cmp-cell-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.cmp-model { font-size: 12px; font-weight: 700; color: var(--accent); }
.cmp-status { font-size: 11px; font-weight: 600; padding: 1px 8px; border-radius: 20px; }
.cmp-status.bad { background: var(--red-soft); color: var(--red); }
.cmp-status.good { background: var(--accent-soft); color: var(--accent); }
.cmp-status.none { background: var(--bg-input); color: var(--text-mute); }
.cmp-answer { font-size: 12px; color: var(--text-sec); line-height: 1.7; max-height: 120px; overflow-y: auto; white-space: pre-wrap; word-break: break-all; }
.cmp-reason { font-size: 11px; color: var(--text-mute); margin-top: 6px; padding-top: 6px; border-top: 1px dashed var(--border); line-height: 1.6; }
.cmp-reason-label { display: inline-block; background: var(--bg-input); border-radius: 4px; padding: 0 5px; font-size: 10px; font-weight: 600; color: var(--text-mute); margin-right: 4px; }
.cmp-latency { font-size: 10px; color: var(--text-mute); margin-top: 4px; }
.cmp-more { text-align: center; padding: 6px 0; }
@media (max-width: 900px) { .kpi-row { grid-template-columns: repeat(2, 1fr); } }
</style>
