<template>
  <div class="cases-tab">
    <!-- 筛选栏 -->
    <div class="filter-bar">
      <el-select v-model="filterPromptId" placeholder="筛选评估器" clearable size="small" style="width: 170px" @change="loadData">
        <el-option v-for="p in prompts" :key="p.id" :label="p.name" :value="p.id" />
      </el-select>
      <el-select v-model="filterModelId" placeholder="筛选模型" clearable size="small" style="width: 160px" @change="loadData">
        <el-option v-for="m in models" :key="m.id" :label="m.name" :value="m.id" />
      </el-select>
      <el-input v-model="searchKeyword" placeholder="搜索问题 / 回答 / 判定理由" clearable size="small"
        style="width: 240px" @keyup.enter="onSearch" @clear="onSearch">
        <template #append><el-button @click="onSearch">搜索</el-button></template>
      </el-input>
    </div>

    <!-- KPI -->
    <div class="kpi-row">
      <div class="kpi r"><div class="num">{{ total }}</div><div class="label">Badcase 总数</div></div>
      <div class="kpi b"><div class="num">{{ dimCounts.length }}</div><div class="label">涉及维度</div></div>
    </div>

    <!-- 维度 Tab 切换 -->
    <div class="section" v-if="dimCounts.length">
      <div class="sec-hd"><h2>按维度浏览</h2></div>
      <div class="sec-bd">
        <div class="tab-bar">
          <button class="tab-btn" :class="{ active: !selectedDim }" @click="selectedDim = ''; loadData()">全部 <span class="tc">{{ total }}</span></button>
          <button v-for="dc in dimCounts" :key="dc.dim" class="tab-btn" :class="{ active: selectedDim === dc.dim }"
            @click="selectedDim = dc.dim; loadData()">{{ dc.dim }} <span class="tc">{{ dc.count }}</span></button>
        </div>
      </div>
    </div>

    <!-- Badcase 列表 -->
    <div class="section">
      <div class="sec-hd"><h2>失败案例列表</h2></div>
      <div class="sec-bd">
        <div v-loading="loading">
          <div class="bc-card" v-for="(bc, i) in badcases" :key="bc.id">
            <div class="bc-head">
              <span class="bc-seq">#{{ (page - 1) * size + i + 1 }}</span>
              <span class="bc-model">{{ bc.modelName || getModelName(bc.modelConfigId) }}</span>
              <div class="bc-dims"><span class="dim-badge" v-for="d in parseDims(bc.dimensions)" :key="d">{{ d }}</span></div>
            </div>
            <div class="bc-body">
              <div class="bc-question" v-if="extractCaseInfo(bc).question"><span class="bc-label">问题</span><span class="bc-text">{{ extractCaseInfo(bc).question }}</span></div>
              <div class="bc-answer" v-if="extractCaseInfo(bc).answer"><span class="bc-label">回答</span><span class="bc-text">{{ extractCaseInfo(bc).answer }}</span></div>
              <div class="bc-reason" v-if="bc.reason"><span class="bc-label">判定</span><span class="bc-text">{{ bc.reason }}</span></div>
              <div class="bc-action-row">
                <el-button size="small" text type="primary" @click="goReview(bc)">去人工校验</el-button>
              </div>
            </div>
          </div>
          <div class="no-bc" v-if="!badcases.length && !loading">暂无 Badcase</div>
        </div>
        <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
          layout="total, prev, pager, next" @current-change="loadData" style="margin-top: 12px" />
      </div>
    </div>
  </div>
</template>

<script>
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { resultApi } from '../../api'

export default {
  name: 'CasesTab',
  props: {
    taskId: Number, models: Array, prompts: Array, modelMap: Object, promptMap: Object,
    initialDimension: String
  },
  setup(props) {
    const router = useRouter()
    const loading = ref(false)
    const badcases = ref([])
    const page = ref(1)
    const size = ref(20)
    const total = ref(0)
    const filterPromptId = ref(null)
    const filterModelId = ref(null)
    const selectedDim = ref(props.initialDimension || '')
    const searchKeyword = ref('')
    const dimCounts = ref([])

    const getModelName = (id) => props.modelMap[id] || `模型${id}`
    const parseDims = (dims) => { try { return JSON.parse(dims || '[]') } catch { return [] } }
    const extractCaseInfo = (bc) => {
      let question = bc.question || '', answer = bc.modelResponse || ''
      if (!question && bc.extraFields) {
        try {
          const extra = JSON.parse(bc.extraFields)
          question = extra.caseDescription || extra.question || extra.输入 || ''
          if (extra.expected_path) question += (question ? ' → ' : '') + extra.expected_path
          answer = extra.path_summary || extra.model_response || extra.模型回答 || ''
        } catch { /* */ }
      }
      return { question, answer }
    }

    const loadDimCounts = async () => {
      if (!props.taskId) { dimCounts.value = []; return }
      try {
        const res = await resultApi.getSummary(props.taskId)
        const map = {}
        res.data.filter(s => s.dimension).forEach(s => { map[s.dimension] = (map[s.dimension] || 0) + (s.badcaseCount || 0) })
        dimCounts.value = Object.entries(map).map(([dim, count]) => ({ dim, count })).sort((a, b) => b.count - a.count)
      } catch (e) { dimCounts.value = [] }
    }

    const loadData = async () => {
      if (!props.taskId) { badcases.value = []; total.value = 0; return }
      loading.value = true
      try {
        const params = {
          promptId: filterPromptId.value || undefined,
          modelConfigId: filterModelId.value || undefined,
          dimension: selectedDim.value || undefined,
          keyword: searchKeyword.value || undefined,
          page: page.value, size: size.value
        }
        const res = await resultApi.listBadcases(props.taskId, params)
        badcases.value = res.data.records
        total.value = res.data.total
      } finally { loading.value = false }
    }

    const onSearch = () => { page.value = 1; loadData() }

    const goReview = (bc) => {
      router.push({ path: '/annotation', query: { taskId: props.taskId } })
    }

    watch(() => props.taskId, () => { page.value = 1; selectedDim.value = props.initialDimension || ''; loadDimCounts(); loadData() }, { immediate: true })
    watch(() => props.initialDimension, (d) => { if (d) { selectedDim.value = d; page.value = 1; loadData() } })

    return {
      loading, badcases, page, size, total, loadData,
      filterPromptId, filterModelId, searchKeyword, onSearch,
      selectedDim, dimCounts, getModelName, parseDims, extractCaseInfo, goReview
    }
  }
}
</script>

<style scoped>
.filter-bar { display: flex; gap: 8px; margin-bottom: 14px; flex-wrap: wrap; align-items: center; }
.kpi-row { display: grid; grid-template-columns: repeat(2, 1fr); gap: 14px; margin-bottom: 20px; }
.kpi { background: var(--bg-card); border-radius: 12px; padding: 18px 20px; border: 1px solid var(--border); position: relative; overflow: hidden; box-shadow: var(--shadow-sm); }
.kpi::before { content: ''; position: absolute; top: 0; left: 0; right: 0; height: 3px; }
.kpi.r::before { background: var(--red); } .kpi.b::before { background: #10b981; }
.kpi .num { font-size: 28px; font-weight: 800; line-height: 1; margin-bottom: 4px; }
.kpi.r .num { color: var(--red); } .kpi.b .num { color: var(--accent); }
.kpi .label { font-size: 12px; color: var(--text-mute); }
.section { background: var(--bg-card); border-radius: 12px; border: 1px solid var(--border); margin-bottom: 16px; overflow: hidden; box-shadow: var(--shadow-sm); }
.sec-hd { padding: 16px 24px 0; }
.sec-hd h2 { font-size: 15px; font-weight: 700; color: var(--text-prime); }
.sec-hd h2::before { content: ''; display: inline-block; width: 4px; height: 16px; background: var(--red); border-radius: 2px; margin-right: 8px; vertical-align: middle; }
.sec-bd { padding: 14px 24px 18px; }
.tab-bar { display: flex; gap: 6px; flex-wrap: wrap; }
.tab-btn { padding: 4px 12px; border-radius: 20px; border: 1px solid var(--border); background: var(--bg-input); cursor: pointer; font-size: 12px; font-weight: 500; color: var(--text-sec); transition: all 0.15s; }
.tab-btn:hover { border-color: var(--accent); color: var(--text-prime); }
.tab-btn.active { background: var(--red); border-color: var(--red); color: #fff; font-weight: 600; }
.tab-btn .tc { display: inline-block; background: rgba(255,255,255,.3); border-radius: 10px; padding: 0 5px; font-size: 10px; margin-left: 3px; }
.tab-btn:not(.active) .tc { background: var(--red-soft); color: var(--red); }
.bc-card { border: 1px solid var(--border); border-radius: 10px; margin-bottom: 8px; overflow: hidden; background: var(--bg-card); box-shadow: var(--shadow-xs); }
.bc-head { background: var(--bg-input); padding: 9px 14px; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.bc-seq { font-size: 11px; color: var(--text-mute); font-weight: 500; white-space: nowrap; }
.bc-model { font-size: 12px; font-weight: 600; color: var(--red); }
.bc-dims { display: flex; gap: 4px; flex-wrap: wrap; }
.dim-badge { display: inline-block; background: var(--red-soft); color: var(--red); border-radius: 20px; padding: 2px 8px; font-size: 11px; font-weight: 600; }
.bc-body { padding: 9px 14px; font-size: 12px; color: var(--text-sec); line-height: 1.7; border-top: 1px solid var(--border); }
.bc-question, .bc-answer, .bc-reason { margin-bottom: 6px; }
.bc-label { display: inline-block; background: var(--bg-input); border-radius: 4px; padding: 1px 6px; font-size: 10px; font-weight: 600; color: var(--text-mute); margin-right: 6px; vertical-align: top; }
.bc-question .bc-label { background: var(--red-soft); color: var(--red); }
.bc-answer .bc-label { background: var(--red-soft); color: var(--red); }
.bc-reason .bc-label { background: var(--red-soft); color: var(--red); }
.bc-text { font-size: 12px; color: var(--text-sec); line-height: 1.7; word-break: break-all; }
.bc-action-row { text-align: right; margin-top: 4px; }
.no-bc { color: var(--text-mute); font-size: 13px; padding: 8px 0; }
</style>
