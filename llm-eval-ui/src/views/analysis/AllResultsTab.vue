<template>
  <div class="all-results-tab">
    <!-- KPI 概要 -->
    <div class="kpi-row">
      <div class="kpi-card" :class="{ active: filterVerdict === '' }" @click="filterVerdict = ''; applyFilter()">
        <div class="kpi-num">{{ allResults.length }}</div>
        <div class="kpi-label">全部</div>
      </div>
      <div class="kpi-card good" :class="{ active: filterVerdict === '0' }" @click="filterVerdict = '0'; applyFilter()">
        <div class="kpi-num">{{ goodCount }}</div>
        <div class="kpi-label">Goodcase</div>
      </div>
      <div class="kpi-card bad" :class="{ active: filterVerdict === '1' }" @click="filterVerdict = '1'; applyFilter()">
        <div class="kpi-num">{{ badCount }}</div>
        <div class="kpi-label">Badcase</div>
      </div>
      <div class="kpi-card unk" :class="{ active: filterVerdict === 'unknown' }" @click="filterVerdict = 'unknown'; applyFilter()">
        <div class="kpi-num">{{ unknownCount }}</div>
        <div class="kpi-label">Unknown</div>
      </div>
    </div>

    <!-- 筛选栏 -->
    <div class="filter-bar">
      <el-select v-model="filterModelId" placeholder="全部模型" clearable size="small" style="width: 150px" @change="applyFilter">
        <el-option v-for="m in models" :key="m.id" :label="m.name" :value="m.id" />
      </el-select>
      <el-select v-model="filterDimension" placeholder="全部维度" clearable size="small" style="width: 130px" @change="applyFilter">
        <el-option v-for="d in dimensionOptions" :key="d" :label="d" :value="d" />
      </el-select>
      <el-input v-model="filterKeyword" placeholder="搜索问题 / 回答 / 理由" clearable size="small"
        style="width: 240px" @keyup.enter="applyFilter" @clear="applyFilter">
        <template #prefix><el-icon :size="13"><Search /></el-icon></template>
      </el-input>
      <span class="filter-count">共 {{ filteredResults.length }} 条</span>
    </div>

    <!-- 结果卡片列表 -->
    <div class="result-list" v-loading="loading">
      <div v-for="(row, i) in pagedResults" :key="row.id" class="result-card" @click="toggleExpand(row.id)">
        <div class="rc-head">
          <span class="rc-seq">#{{ (currentPage - 1) * pageSize + i + 1 }}</span>
          <span class="rc-model">{{ row.modelName || getModelName(row.modelConfigId) }}</span>
          <span :class="['rc-verdict', verdictClass(row.isBadcase)]">{{ verdictLabel(row.isBadcase) }}</span>
          <span v-if="row.promptName" class="rc-prompt">{{ row.promptName }}</span>
        </div>

        <div class="rc-body">
          <div class="rc-row">
            <span class="rc-label q">问题</span>
            <span class="rc-text">{{ getQuestion(row) }}</span>
          </div>
          <div class="rc-row">
            <span class="rc-label a">回答</span>
            <span class="rc-text muted">{{ getAnswer(row) }}</span>
          </div>
        </div>

        <div class="rc-foot">
          <div class="rc-dims" v-if="parseDimensions(row.dimensions).length">
            <span v-for="d in parseDimensions(row.dimensions)" :key="d" class="dim-tag">{{ d }}</span>
          </div>
          <div v-else></div>
          <span class="rc-expand-hint">{{ expandedIds.has(row.id) ? '收起' : '展开详情' }}</span>
        </div>

        <!-- 展开详情 -->
        <transition name="slide">
          <div v-if="expandedIds.has(row.id)" class="rc-detail">
            <div class="detail-block">
              <div class="detail-hd">完整问题</div>
              <div class="detail-bd">{{ getQuestionRaw(row) || '（无）' }}</div>
            </div>
            <div class="detail-block">
              <div class="detail-hd">完整回答</div>
              <div class="detail-bd mono">{{ getAnswerRaw(row) || '（无回答）' }}</div>
            </div>
            <div class="detail-block" v-if="row.referenceAnswer">
              <div class="detail-hd">参考答案</div>
              <div class="detail-bd">{{ row.referenceAnswer }}</div>
            </div>
            <div class="detail-block" v-if="row.reason">
              <div class="detail-hd">AI 判定理由</div>
              <div class="detail-bd">{{ row.reason }}</div>
            </div>
            <div class="detail-block" v-if="getDimReasons(row).length">
              <div class="detail-hd">各维度判定</div>
              <div class="dim-reason-list">
                <div v-for="dr in getDimReasons(row)" :key="dr.dimension" class="dim-reason-item">
                  <div class="dr-head">
                    <span class="dr-dim">{{ dr.dimension }}</span>
                    <span :class="['dr-verdict', dr.isBadcase === 1 ? 'bad' : dr.isBadcase === 0 ? 'good' : 'unk']">
                      {{ dr.isBadcase === 1 ? 'Badcase' : dr.isBadcase === 0 ? 'Goodcase' : 'Unknown' }}
                    </span>
                  </div>
                  <div class="dr-reason" v-if="dr.reason">{{ dr.reason }}</div>
                </div>
              </div>
            </div>
          </div>
        </transition>
      </div>

      <div v-if="!pagedResults.length && !loading" class="empty-state">
        <el-empty description="暂无数据" :image-size="80" />
      </div>
    </div>

    <!-- 分页 -->
    <div class="pagination-row" v-if="filteredResults.length > pageSize">
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :total="filteredResults.length"
        :page-sizes="[20, 50, 100]"
        layout="sizes, prev, pager, next"
        small
      />
    </div>
  </div>
</template>

<script>
import { ref, computed, watch, reactive } from 'vue'
import { resultApi } from '../../api'

export default {
  name: 'AllResultsTab',
  props: {
    taskId: Number,
    models: Array,
    modelMap: Object
  },
  setup(props) {
    const loading = ref(false)
    const allResults = ref([])
    const dimResultsMap = ref({}) // key: "modelConfigId_datasetItemId" -> [{ dimension, isBadcase, reason }]
    const filterVerdict = ref('')
    const filterModelId = ref(null)
    const filterDimension = ref('')
    const filterKeyword = ref('')
    const currentPage = ref(1)
    const pageSize = ref(20)
    const expandedIds = reactive(new Set())

    const goodCount = computed(() => allResults.value.filter(r => r.isBadcase === 0).length)
    const badCount = computed(() => allResults.value.filter(r => r.isBadcase === 1).length)
    const unknownCount = computed(() => allResults.value.filter(r => r.isBadcase === null || r.isBadcase === undefined).length)

    const dimensionOptions = computed(() => {
      const dims = new Set()
      allResults.value.forEach(r => { parseDimensions(r.dimensions).forEach(d => dims.add(d)) })
      return [...dims].sort()
    })

    const filteredResults = computed(() => {
      let list = allResults.value
      if (filterVerdict.value === '0') list = list.filter(r => r.isBadcase === 0)
      else if (filterVerdict.value === '1') list = list.filter(r => r.isBadcase === 1)
      else if (filterVerdict.value === 'unknown') list = list.filter(r => r.isBadcase == null)
      if (filterDimension.value) list = list.filter(r => parseDimensions(r.dimensions).includes(filterDimension.value))
      if (filterKeyword.value) {
        const kw = filterKeyword.value.toLowerCase()
        list = list.filter(r =>
          (r.question && r.question.toLowerCase().includes(kw)) ||
          (r.modelResponse && r.modelResponse.toLowerCase().includes(kw)) ||
          (r.reason && r.reason.toLowerCase().includes(kw))
        )
      }
      return list
    })

    const pagedResults = computed(() => {
      const start = (currentPage.value - 1) * pageSize.value
      return filteredResults.value.slice(start, start + pageSize.value)
    })

    const applyFilter = () => { currentPage.value = 1; loadData() }

    const parseDimensions = (dims) => {
      if (!dims) return []
      try { return JSON.parse(dims) } catch { return [] }
    }
    const getQuestion = (row) => {
      let q = row.question || ''
      if (!q && row.extraFields) {
        try { const e = JSON.parse(row.extraFields); q = e.caseDescription || e.question || e.输入 || '' } catch { /* */ }
      }
      return q.length > 80 ? q.slice(0, 80) + '...' : q || '-'
    }
    const getAnswer = (row) => {
      let a = row.modelResponse || ''
      if (!a && row.extraFields) {
        try { const e = JSON.parse(row.extraFields); a = e.path_summary || e.model_response || e.模型回答 || '' } catch { /* */ }
      }
      return a.length > 60 ? a.slice(0, 60) + '...' : a || '-'
    }
    const getQuestionRaw = (row) => {
      let q = row.question || ''
      if (!q && row.extraFields) {
        try { const e = JSON.parse(row.extraFields); q = e.caseDescription || e.question || e.输入 || '' } catch { /* */ }
      }
      return q
    }
    const getAnswerRaw = (row) => {
      let a = row.modelResponse || ''
      if (!a && row.extraFields) {
        try { const e = JSON.parse(row.extraFields); a = e.path_summary || e.model_response || e.模型回答 || '' } catch { /* */ }
      }
      return a
    }
    const getModelName = (id) => props.modelMap?.[id] || `模型${id}`
    const verdictLabel = (v) => v === 1 ? 'Badcase' : v === 0 ? 'Goodcase' : 'Unknown'
    const verdictClass = (v) => v === 1 ? 'bad' : v === 0 ? 'good' : 'unk'

    const getDimReasons = (row) => {
      // 优先用维度记录接口返回的数据
      const key = `${row.modelConfigId}_${row.datasetItemId}`
      const apiDims = dimResultsMap.value[key]
      if (apiDims && apiDims.length) return apiDims
      // 降级：从 parsedResult 原始 JSON 里解析维度信息
      if (row.parsedResult) {
        try {
          const parsed = JSON.parse(row.parsedResult)
          const dimObj = parsed.dimensions
          if (dimObj && typeof dimObj === 'object' && !Array.isArray(dimObj)) {
            return Object.entries(dimObj).map(([dim, val]) => {
              let isBadcase = null
              if (val && typeof val === 'object') {
                if ('is_badcase' in val) isBadcase = val.is_badcase ? 1 : 0
                else if ('score' in val) isBadcase = val.score < 3 ? 1 : 0
                else if ('result' in val) {
                  const r = String(val.result).toLowerCase()
                  isBadcase = ['bad', 'false', '否', '不采纳', '差'].includes(r) ? 1 : 0
                }
              }
              return { dimension: dim, isBadcase, reason: val?.reason || '' }
            })
          }
        } catch { /* */ }
      }
      return []
    }

    const toggleExpand = (id) => {
      if (expandedIds.has(id)) expandedIds.delete(id)
      else expandedIds.add(id)
    }

    const loadData = async () => {
      if (!props.taskId) return
      loading.value = true
      try {
        const params = {}
        if (filterModelId.value) params.modelConfigId = filterModelId.value
        const [res, dimRes] = await Promise.all([
          resultApi.listJudgeResults(props.taskId, params),
          resultApi.listDimensionResults(props.taskId, params)
        ])
        let mainResults = res.data || []

        // 按 modelConfigId_datasetItemId 分组维度结果（用于详情展开）
        const dimList = dimRes.data || []
        const map = {}
        dimList.forEach(d => {
          const key = `${d.modelConfigId}_${d.datasetItemId}`
          if (!map[key]) map[key] = []
          map[key].push({ dimension: d.dimension, isBadcase: d.isBadcase, reason: d.reason })
        })
        dimResultsMap.value = map

        // 当整体记录为空但有维度记录时，按 datasetItemId 去重后作为卡片展示
        if (mainResults.length === 0 && dimList.length) {
          const seen = new Set()
          mainResults = dimList.filter(d => {
            const key = `${d.modelConfigId}_${d.datasetItemId}`
            if (seen.has(key)) return false
            seen.add(key)
            return true
          })
        }

        allResults.value = mainResults
      } catch {
        allResults.value = []
        dimResultsMap.value = {}
      } finally {
        loading.value = false
      }
    }

    watch(() => props.taskId, () => {
      if (props.taskId) {
        currentPage.value = 1
        filterVerdict.value = ''
        filterModelId.value = null
        filterDimension.value = ''
        filterKeyword.value = ''
        expandedIds.clear()
        loadData()
      }
    }, { immediate: true })

    return {
      loading, allResults, filteredResults, pagedResults,
      filterVerdict, filterModelId, filterDimension, filterKeyword,
      currentPage, pageSize, applyFilter, expandedIds,
      goodCount, badCount, unknownCount, dimensionOptions,
      parseDimensions, getQuestion, getAnswer, getQuestionRaw, getAnswerRaw,
      getModelName, verdictLabel, verdictClass, toggleExpand, getDimReasons
    }
  }
}
</script>

<style scoped>
/* KPI 概要 */
.kpi-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 14px; }
.kpi-card {
  background: var(--bg-card); border: 1.5px solid var(--border); border-radius: 10px;
  padding: 12px 16px; cursor: pointer; transition: all 0.15s; text-align: center;
}
.kpi-card:hover { border-color: var(--accent); }
.kpi-card.active { border-color: var(--accent); background: var(--accent-soft); }
.kpi-card.good.active { border-color: #10b981; background: rgba(16,185,129,0.08); }
.kpi-card.bad.active { border-color: #ef4444; background: rgba(239,68,68,0.08); }
.kpi-card.unk.active { border-color: #f59e0b; background: rgba(245,158,11,0.08); }
.kpi-num { font-size: 22px; font-weight: 800; line-height: 1.1; }
.kpi-card .kpi-num { color: var(--text-prime); }
.kpi-card.good .kpi-num { color: #10b981; }
.kpi-card.bad .kpi-num { color: #ef4444; }
.kpi-card.unk .kpi-num { color: #f59e0b; }
.kpi-label { font-size: 11px; color: var(--text-mute); margin-top: 2px; }

/* 筛选栏 */
.filter-bar {
  display: flex; gap: 8px; margin-bottom: 14px; align-items: center; flex-wrap: wrap;
  padding: 10px 14px; background: var(--bg-card); border: 1px solid var(--border);
  border-radius: 10px; box-shadow: var(--shadow-sm);
}
.filter-count { font-size: 12px; color: var(--text-mute); margin-left: auto; }

/* 卡片列表 */
.result-list { display: flex; flex-direction: column; gap: 8px; }
.result-card {
  background: var(--bg-card); border: 1px solid var(--border); border-radius: 10px;
  padding: 12px 16px; cursor: pointer; transition: all 0.15s; box-shadow: var(--shadow-xs);
}
.result-card:hover { border-color: rgba(6,182,212,0.4); box-shadow: var(--shadow-sm); }

.rc-head {
  display: flex; align-items: center; gap: 8px; margin-bottom: 8px; flex-wrap: wrap;
}
.rc-seq { font-size: 11px; color: var(--text-mute); font-weight: 500; }
.rc-model { font-size: 12px; font-weight: 700; color: var(--accent); }
.rc-prompt {
  font-size: 10px; color: var(--text-mute); background: var(--bg-input);
  padding: 1px 7px; border-radius: 8px; margin-left: auto;
}
.rc-verdict {
  display: inline-block; font-size: 11px; font-weight: 600; padding: 2px 10px;
  border-radius: 20px;
}
.rc-verdict.good { background: rgba(16,185,129,0.1); color: #10b981; }
.rc-verdict.bad { background: rgba(239,68,68,0.1); color: #ef4444; }
.rc-verdict.unk { background: rgba(245,158,11,0.1); color: #f59e0b; }

.rc-body { display: flex; flex-direction: column; gap: 5px; }
.rc-row { display: flex; align-items: flex-start; gap: 8px; }
.rc-label {
  flex-shrink: 0; font-size: 10px; font-weight: 700; padding: 2px 7px;
  border-radius: 4px; letter-spacing: 0.5px; margin-top: 1px;
}
.rc-label.q { background: rgba(99,102,241,0.1); color: #6366f1; }
.rc-label.a { background: rgba(148,163,184,0.12); color: var(--text-mute); }
.rc-text { font-size: 13px; color: var(--text-prime); line-height: 1.6; }
.rc-text.muted { color: var(--text-sec); }

.rc-foot {
  display: flex; align-items: center; justify-content: space-between;
  margin-top: 8px; padding-top: 8px; border-top: 1px dashed var(--border);
}
.rc-dims { display: flex; gap: 4px; flex-wrap: wrap; }
.dim-tag {
  display: inline-block; background: var(--bg-input); color: var(--text-sec);
  border-radius: 10px; padding: 1px 8px; font-size: 10px; font-weight: 500;
  border: 1px solid var(--border);
}
.rc-expand-hint { font-size: 11px; color: var(--text-mute); flex-shrink: 0; }

/* 展开详情 */
.rc-detail {
  margin-top: 10px; padding: 12px 14px; background: var(--bg-input);
  border-radius: 8px; border: 1px solid var(--border);
}
.detail-block { margin-bottom: 10px; }
.detail-block:last-child { margin-bottom: 0; }
.detail-hd {
  font-size: 11px; font-weight: 700; color: var(--text-mute);
  margin-bottom: 4px; text-transform: uppercase; letter-spacing: 0.5px;
}
.detail-bd {
  font-size: 13px; color: var(--text-sec); line-height: 1.7;
  white-space: pre-wrap; word-break: break-all;
  background: var(--bg-card); border: 1px solid var(--border);
  border-radius: 8px; padding: 10px 14px;
}
.detail-bd.mono { font-family: 'SF Mono', 'Fira Code', Consolas, monospace; font-size: 12px; }

/* 维度判定列表 */
.dim-reason-list { display: flex; flex-direction: column; gap: 8px; }
.dim-reason-item {
  background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px;
  padding: 10px 14px;
}
.dr-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 4px; }
.dr-dim { font-size: 12px; font-weight: 700; color: var(--text-prime); }
.dr-verdict { font-size: 10px; font-weight: 600; padding: 1px 8px; border-radius: 10px; }
.dr-verdict.good { background: rgba(16,185,129,0.1); color: #10b981; }
.dr-verdict.bad { background: rgba(239,68,68,0.1); color: #ef4444; }
.dr-verdict.unk { background: rgba(245,158,11,0.1); color: #f59e0b; }
.dr-reason { font-size: 12px; color: var(--text-sec); line-height: 1.6; }

/* 过渡动画 */
.slide-enter-active, .slide-leave-active { transition: all 0.2s ease; }
.slide-enter-from, .slide-leave-to { opacity: 0; max-height: 0; }
.slide-enter-to, .slide-leave-from { opacity: 1; max-height: 600px; }

/* 分页 */
.pagination-row {
  display: flex; justify-content: center; margin-top: 14px;
}

.empty-state { padding: 40px 0; }

@media (max-width: 900px) {
  .kpi-row { grid-template-columns: repeat(2, 1fr); }
}
</style>
