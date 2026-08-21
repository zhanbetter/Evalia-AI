<template>
  <div class="scores-tab">
    <div v-if="loading" v-loading="true" style="height: 200px"></div>
    <template v-if="!loading">
      <!-- 统计卡片行 -->
      <div class="score-kpi-row">
        <div class="score-kpi">
          <div class="sk-top">
            <span class="sk-icon good"><el-icon :size="18"><CircleCheck /></el-icon></span>
            <span class="sk-val">{{ overall.goodCount || 0 }}</span>
          </div>
          <div class="sk-label">Goodcase</div>
          <div class="sk-sub">{{ overall.goodRate || 0 }}%</div>
        </div>
        <div class="score-kpi">
          <div class="sk-top">
            <span class="sk-icon bad"><el-icon :size="18"><CircleClose /></el-icon></span>
            <span class="sk-val">{{ overall.badcaseCount || 0 }}</span>
          </div>
          <div class="sk-label">Badcase</div>
          <div class="sk-sub">{{ overall.badcaseRate || 0 }}%</div>
        </div>
        <div class="score-kpi">
          <div class="sk-top">
            <span class="sk-icon unknown"><el-icon :size="18"><QuestionFilled /></el-icon></span>
            <span class="sk-val">{{ overall.unknownCount || 0 }}</span>
          </div>
          <div class="sk-label">Unknown</div>
          <div class="sk-sub">{{ overall.unknownRate || 0 }}%</div>
        </div>
        <div class="score-kpi">
          <div class="sk-top">
            <span class="sk-icon latency"><el-icon :size="18"><Timer /></el-icon></span>
            <span class="sk-val">{{ overall.avgLatency || 0 }}<span class="sk-unit">ms</span></span>
          </div>
          <div class="sk-label">平均耗时</div>
          <div class="sk-sub">裁判模型响应</div>
        </div>
      </div>

      <!-- 2x2 卡片网格 -->
      <div class="score-grid">
        <!-- 评分分布（按维度） -->
        <div class="score-card">
          <div class="sc-hd">
            <h3>评分分布</h3>
            <el-select v-model="distDimFilter" placeholder="全部维度" size="small" clearable style="width: 130px">
              <el-option label="整体" value="" />
              <el-option v-for="d in dimensionNames" :key="d" :label="d" :value="d" />
            </el-select>
          </div>
          <div class="sc-bd">
            <div ref="distChartRef" style="width: 100%; height: 260px;"></div>
          </div>
        </div>

        <!-- 模型对比热力图（多模型时才展示） -->
        <div class="score-card" v-if="modelCount > 1">
          <div class="sc-hd"><h3>模型 × 维度 Badcase 率</h3></div>
          <div class="sc-bd">
            <div ref="heatmapRef" style="width: 100%; height: 260px;"></div>
          </div>
        </div>

        <!-- 各维度 Good/Bad/Unknown 占比 -->
        <div class="score-card">
          <div class="sc-hd"><h3>维度评分构成</h3></div>
          <div class="sc-bd">
            <div ref="stackBarRef" style="width: 100%; height: 260px;"></div>
          </div>
        </div>

        <!-- 模型维度明细表（多模型时才展示） -->
        <div class="score-card" v-if="modelCount > 1">
          <div class="sc-hd"><h3>模型 × 维度明细</h3></div>
          <div class="sc-bd score-table-wrap">
            <table class="score-table" v-if="modelDimRows.length">
              <thead>
                <tr>
                  <th>模型</th>
                  <th v-for="d in dimensionNames" :key="d">{{ d }}</th>
                  <th>整体</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in modelDimRows" :key="row.model">
                  <td class="model-name">{{ row.model }}</td>
                  <td v-for="d in dimensionNames" :key="d" :class="cellClass(row[d])">
                    {{ row[d] !== undefined ? row[d] + '%' : '-' }}
                  </td>
                  <td :class="cellClass(row.overall)">{{ row.overall }}%</td>
                </tr>
              </tbody>
            </table>
            <el-empty v-else description="暂无数据" :image-size="60" />
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script>
import { ref, computed, watch, nextTick, onBeforeUnmount } from 'vue'
import { resultApi } from '../../api'
import * as echarts from 'echarts/core'
import { BarChart, HeatmapChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent, VisualMapComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
echarts.use([BarChart, HeatmapChart, GridComponent, TooltipComponent, LegendComponent, VisualMapComponent, CanvasRenderer])

export default {
  name: 'ScoresTab',
  props: {
    taskId: Number, models: Array, prompts: Array, modelMap: Object, promptMap: Object
  },
  setup(props) {
    const loading = ref(false)
    const summaries = ref([])
    const judgeResults = ref([])
    const distDimFilter = ref('')
    const distChartRef = ref(null)
    const heatmapRef = ref(null)
    const stackBarRef = ref(null)
    let charts = []

    const getModelName = (id) => props.modelMap[id] || `模型${id}`

    // 整体统计
    const overallRows = computed(() => summaries.value.filter(s => !s.dimension))
    const overall = computed(() => {
      const rows = overallRows.value
      if (!rows.length) return { totalCount: 0, goodCount: 0, badcaseCount: 0, unknownCount: 0, goodRate: 0, badcaseRate: 0, unknownRate: 0, avgLatency: 0 }
      const total = rows.reduce((a, s) => a + (s.totalCount || 0), 0)
      const good = rows.reduce((a, s) => a + (s.goodCount || 0), 0)
      const bad = rows.reduce((a, s) => a + (s.badcaseCount || 0), 0)
      const unk = rows.reduce((a, s) => a + (s.unknownCount || 0), 0)
      const lat = rows.reduce((a, s) => a + (s.avgLatencyMs || 0), 0)
      const n = rows.length || 1
      return {
        totalCount: total, goodCount: good, badcaseCount: bad, unknownCount: unk,
        goodRate: total ? +(good * 100 / total).toFixed(1) : 0,
        badcaseRate: total ? +(bad * 100 / total).toFixed(1) : 0,
        unknownRate: total ? +(unk * 100 / total).toFixed(1) : 0,
        avgLatency: Math.round(lat / n)
      }
    })

    // 维度名列表
    const dimensionNames = computed(() => {
      const dims = new Set(summaries.value.filter(s => s.dimension).map(s => s.dimension))
      return [...dims].sort()
    })

    // 模型 x 维度 Badcase 率矩阵
    const modelDimRows = computed(() => {
      const dimS = summaries.value.filter(s => s.dimension)
      const overallS = summaries.value.filter(s => !s.dimension)
      const modelIds = [...new Set(summaries.value.map(s => s.modelConfigId))]
      return modelIds.map(mid => {
        const row = { model: getModelName(mid) }
        dimensionNames.value.forEach(d => {
          const found = dimS.find(s => s.modelConfigId === mid && s.dimension === d)
          row[d] = found ? +found.badcaseRate : undefined
        })
        const ov = overallS.find(s => s.modelConfigId === mid)
        row.overall = ov ? +ov.badcaseRate : 0
        return row
      }).sort((a, b) => b.overall - a.overall)
    })

    const cellClass = (val) => {
      if (val === undefined) return ''
      if (val >= 30) return 'cell-bad'
      if (val >= 15) return 'cell-warn'
      return 'cell-good'
    }

    // 多模型计数
    const modelCount = computed(() => new Set(summaries.value.map(s => s.modelConfigId)).size)

    const loadData = async () => {
      if (!props.taskId) return
      loading.value = true
      try {
        const [summaryRes, judgeRes] = await Promise.all([
          resultApi.getSummary(props.taskId),
          resultApi.listJudgeResults(props.taskId)
        ])
        summaries.value = summaryRes.data || []
        judgeResults.value = judgeRes.data || []
        await nextTick()
        renderAllCharts()
      } catch (e) {
        summaries.value = []
        judgeResults.value = []
      } finally { loading.value = false }
    }

    const renderAllCharts = () => {
      renderDistChart()
      renderHeatmap()
      renderStackBar()
    }

    // 评分分布柱状图
    const renderDistChart = () => {
      if (!distChartRef.value) return
      const chart = echarts.init(distChartRef.value)
      charts.push(chart)

      let filtered = judgeResults.value
      if (distDimFilter.value) {
        filtered = filtered.filter(r => {
          if (!r.dimensionResults) return false
          try {
            const dims = JSON.parse(r.dimensionResults)
            return dims.some(d => d.dimension === distDimFilter.value)
          } catch { return false }
        })
      }

      // 统计 good/bad/unknown 数量
      const counts = { good: 0, bad: 0, unknown: 0 }
      filtered.forEach(r => {
        if (r.isBadcase === 1) counts.bad++
        else if (r.isBadcase === 0) counts.good++
        else counts.unknown++
      })

      chart.setOption({
        tooltip: { trigger: 'axis' },
        grid: { top: 20, left: 50, right: 20, bottom: 30 },
        xAxis: { type: 'category', data: ['Goodcase', 'Badcase', 'Unknown'] },
        yAxis: { type: 'value', name: '数量' },
        series: [{
          type: 'bar', barMaxWidth: 60,
          data: [
            { value: counts.good, itemStyle: { color: '#10b981', borderRadius: [6, 6, 0, 0] } },
            { value: counts.bad, itemStyle: { color: '#ef4444', borderRadius: [6, 6, 0, 0] } },
            { value: counts.unknown, itemStyle: { color: '#94a3b8', borderRadius: [6, 6, 0, 0] } }
          ]
        }]
      })
    }

    // 模型 x 维度 热力图
    const renderHeatmap = () => {
      if (!heatmapRef.value) return
      const chart = echarts.init(heatmapRef.value)
      charts.push(chart)

      const dims = dimensionNames.value
      const rows = modelDimRows.value
      if (!dims.length || !rows.length) {
        chart.setOption({ title: { text: '暂无数据', left: 'center', top: 'center', textStyle: { color: '#94a3b8', fontSize: 13 } } })
        return
      }

      const data = []
      rows.forEach((row, yi) => {
        dims.forEach((d, xi) => {
          const val = row[d]
          if (val !== undefined) data.push([xi, yi, val])
        })
      })

      chart.setOption({
        tooltip: {
          formatter: (p) => `${rows[p.value[1]].model} · ${dims[p.value[0]]}<br/>Badcase率: ${p.value[2]}%`
        },
        grid: { top: 10, left: 80, right: 40, bottom: 30 },
        xAxis: { type: 'category', data: dims, splitArea: { show: true } },
        yAxis: { type: 'category', data: rows.map(r => r.model), splitArea: { show: true } },
        visualMap: {
          min: 0, max: 50, calculable: true,
          orient: 'horizontal', left: 'center', bottom: 0,
          inRange: { color: ['#d1fae5', '#fef3c7', '#fecaca', '#ef4444'] },
          textStyle: { fontSize: 11 }
        },
        series: [{
          type: 'heatmap', data,
          label: { show: true, fontSize: 11, formatter: (p) => p.value[2] + '%' },
          emphasis: { itemStyle: { shadowBlur: 6, shadowColor: 'rgba(0,0,0,0.3)' } }
        }]
      })
    }

    // 维度评分构成堆叠条形图
    const renderStackBar = () => {
      if (!stackBarRef.value) return
      const chart = echarts.init(stackBarRef.value)
      charts.push(chart)

      const dims = dimensionNames.value
      if (!dims.length) {
        chart.setOption({ title: { text: '暂无数据', left: 'center', top: 'center', textStyle: { color: '#94a3b8', fontSize: 13 } } })
        return
      }

      const dimS = summaries.value.filter(s => s.dimension)
      const goodData = [], badData = [], unkData = []
      dims.forEach(d => {
        const rows = dimS.filter(s => s.dimension === d)
        const total = rows.reduce((a, s) => a + (s.totalCount || 0), 0)
        const good = rows.reduce((a, s) => a + (s.goodCount || 0), 0)
        const bad = rows.reduce((a, s) => a + (s.badcaseCount || 0), 0)
        const unk = rows.reduce((a, s) => a + (s.unknownCount || 0), 0)
        goodData.push(total ? +(good * 100 / total).toFixed(1) : 0)
        badData.push(total ? +(bad * 100 / total).toFixed(1) : 0)
        unkData.push(total ? +(unk * 100 / total).toFixed(1) : 0)
      })

      chart.setOption({
        tooltip: { trigger: 'axis', formatter: (params) => {
          let s = params[0].axisValue + '<br/>'
          params.forEach(p => { s += `${p.marker} ${p.seriesName}: ${p.value}%<br/>` })
          return s
        }},
        legend: { data: ['Goodcase', 'Badcase', 'Unknown'], top: 0 },
        grid: { top: 30, left: 50, right: 20, bottom: 10 },
        xAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
        yAxis: { type: 'category', data: dims },
        series: [
          { name: 'Goodcase', type: 'bar', stack: 'total', data: goodData, itemStyle: { color: '#10b981' } },
          { name: 'Badcase', type: 'bar', stack: 'total', data: badData, itemStyle: { color: '#ef4444' } },
          { name: 'Unknown', type: 'bar', stack: 'total', data: unkData, itemStyle: { color: '#94a3b8' } }
        ]
      })
    }

    const handleResize = () => { charts.forEach(c => c.resize()) }

    watch(() => props.taskId, () => { if (props.taskId) loadData() }, { immediate: true })
    watch(distDimFilter, () => { nextTick(() => renderDistChart()) })

    onBeforeUnmount(() => {
      charts.forEach(c => c.dispose())
      charts = []
      if (typeof window !== 'undefined') {
        window.removeEventListener('resize', handleResize)
      }
    })

    // 监听窗口大小变化
    if (typeof window !== 'undefined') {
      window.addEventListener('resize', handleResize)
    }

    return {
      loading, overall, dimensionNames, modelDimRows, cellClass, modelCount,
      distDimFilter, distChartRef, heatmapRef, stackBarRef
    }
  }
}
</script>

<style scoped>
.score-kpi-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 16px; }
.score-kpi { background: var(--bg-card); border: 1px solid var(--border); border-radius: 10px; padding: 16px 18px; box-shadow: var(--shadow-sm); }
.sk-top { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.sk-icon { width: 32px; height: 32px; border-radius: 8px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.sk-icon.good { background: rgba(16,185,129,0.12); color: #10b981; }
.sk-icon.bad { background: rgba(239,68,68,0.12); color: #ef4444; }
.sk-icon.unknown { background: rgba(148,163,184,0.12); color: #94a3b8; }
.sk-icon.latency { background: rgba(6,182,212,0.12); color: #06b6d4; }
.sk-val { font-size: 26px; font-weight: 800; color: var(--text-prime); line-height: 1; }
.sk-unit { font-size: 13px; font-weight: 600; color: var(--text-mute); margin-left: 2px; }
.sk-label { font-size: 12px; color: var(--text-sec); font-weight: 500; }
.sk-sub { font-size: 11px; color: var(--text-mute); }

.score-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 14px; }
.score-card { background: var(--bg-card); border: 1px solid var(--border); border-radius: 12px; overflow: hidden; box-shadow: var(--shadow-sm); }
.sc-hd { padding: 14px 18px 0; display: flex; align-items: center; justify-content: space-between; }
.sc-hd h3 { font-size: 14px; font-weight: 700; color: var(--text-prime); }
.sc-bd { padding: 12px 16px 16px; }

.score-table-wrap { overflow-x: auto; }
.score-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.score-table th { background: var(--bg-input); padding: 8px 10px; font-weight: 600; text-align: center; border-bottom: 2px solid var(--border); color: var(--text-sec); font-size: 11px; }
.score-table th:first-child { text-align: left; }
.score-table td { padding: 8px 10px; border-bottom: 1px solid var(--border); text-align: center; color: var(--text-sec); }
.score-table td.model-name { text-align: left; font-weight: 600; color: var(--text-prime); }
.score-table tr:hover td { background: var(--bg-card-hover); }
.cell-good { color: #10b981; font-weight: 600; }
.cell-warn { color: #f59e0b; font-weight: 600; }
.cell-bad { color: #ef4444; font-weight: 600; }

@media (max-width: 900px) {
  .score-kpi-row { grid-template-columns: repeat(2, 1fr); }
  .score-grid { grid-template-columns: 1fr; }
}
</style>
