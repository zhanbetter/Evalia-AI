<template>
  <div class="compare-tab">
    <!-- 筛选栏 -->
    <div class="filter-bar">
      <el-select v-model="filterModelId" placeholder="全部模型" size="small" clearable style="width: 150px" @change="loadData">
        <el-option v-for="m in models" :key="m.id" :label="m.name" :value="m.id" />
      </el-select>
      <el-select v-model="filterPromptId" placeholder="全部评估器" size="small" clearable style="width: 150px" @change="loadData">
        <el-option v-for="p in prompts" :key="p.id" :label="p.name" :value="p.id" />
      </el-select>
      <span class="filter-chip" :class="{ active: onlyDisagree }" @click="toggleDisagree">
        <el-icon :size="12"><Warning /></el-icon> 只看不一致
      </span>
    </div>

    <!-- 统计卡 -->
    <div class="va-dashboard" v-if="stats">
      <div class="va-card disagree">
        <div class="va-icon"><el-icon :size="20"><Warning /></el-icon></div>
        <div class="va-body">
          <div class="va-rate">{{ stats.disagreementRate.toFixed(1) }}%</div>
          <div class="va-label">AI·金标准 不一致率</div>
          <div class="va-sub">{{ stats.disagreeCount }} / {{ stats.goldCount }} 不一致</div>
        </div>
      </div>
      <div class="va-card agree">
        <div class="va-icon"><el-icon :size="20"><CircleCheck /></el-icon></div>
        <div class="va-body">
          <div class="va-rate">{{ stats.agreementRate.toFixed(1) }}%</div>
          <div class="va-label">一致率</div>
          <div class="va-sub">{{ stats.agreeCount }} / {{ stats.goldCount }} 一致</div>
        </div>
      </div>
      <div class="va-card stat">
        <div class="va-rate">{{ stats.goldCount }}</div>
        <div class="va-label">金标准样本数</div>
      </div>
      <div class="va-card stat">
        <div class="va-rate warn">{{ stats.aiFalsePositive }}</div>
        <div class="va-label">AI 过严（假阳性）</div>
        <div class="va-sub">AI判Bad，金标准Good</div>
      </div>
      <div class="va-card stat">
        <div class="va-rate bad">{{ stats.aiFalseNegative }}</div>
        <div class="va-label">AI 漏判（假阴性）</div>
        <div class="va-sub">AI判Good，金标准Bad</div>
      </div>
    </div>

    <div class="va-tip" v-if="stats && stats.goldCount === 0">
      还没有金标准。请先在"人工标注" Tab 由专家裁决产出金标准，然后这里就能看到 AI 和金标准的对比。
    </div>
    <div class="va-tip warn" v-else-if="stats && stats.disagreementRate > 30">
      AI·金标准不一致率超过 30%，AI 裁判与专家结论分歧较大，建议优化评估器评分标准。
    </div>

    <!-- 样本列表 -->
    <div class="va-list" v-loading="listLoading">
      <div v-for="(s, i) in filteredSamples" :key="s.id"
        class="va-item" :class="{ disagree: s.aiGoldAgree === false }">
        <div class="va-item-head">
          <span class="va-seq">#{{ i + 1 }}</span>
          <span class="va-model">{{ s.modelName || getModelName(s.modelConfigId) }}</span>
          <span :class="['va-tag', s.isBadcase === 1 ? 'bad' : 'good']">AI: {{ s.isBadcase === 1 ? 'Badcase' : 'Goodcase' }}</span>
          <span class="va-arrow">→</span>
          <span v-if="s.goldLabel" :class="['va-tag gold', s.goldLabel.isBadcase === 1 ? 'bad' : 'good']">金标准: {{ s.goldLabel.isBadcase === 1 ? 'Badcase' : 'Goodcase' }}</span>
          <span :class="['va-result', s.aiGoldAgree === false ? 'disagree' : 'agree']">
            {{ s.aiGoldAgree === false ? '✗ 不一致' : '✓ 一致' }}
          </span>
        </div>
        <div class="va-item-body">
          <div class="va-q" v-if="extractCaseInfo(s).question">
            <span class="va-label">问题</span>{{ extractCaseInfo(s).question }}
          </div>
          <div class="va-a" v-if="extractCaseInfo(s).answer">
            <span class="va-label">模型回答</span>{{ extractCaseInfo(s).answer }}
          </div>
          <div class="va-reason" v-if="s.reason">
            <span class="va-label">AI理由</span>{{ s.reason }}
          </div>
        </div>
      </div>
      <div class="empty-va" v-if="!filteredSamples.length && !listLoading">没有对比样本</div>
      <div class="load-more" v-if="samples.length && hasMore">
        <el-button size="small" text type="primary" @click="loadMore" :loading="loadingMore">加载更多</el-button>
      </div>
    </div>
  </div>
</template>

<script>
import { ref, computed, watch } from 'vue'
import { resultApi } from '../../api'

export default {
  name: 'CompareTab',
  props: {
    taskId: Number, models: Array, prompts: Array, modelMap: Object, promptMap: Object
  },
  setup(props) {
    const filterModelId = ref(null)
    const filterPromptId = ref(null)
    const onlyDisagree = ref(false)
    const stats = ref(null)
    const samples = ref([])
    const listLoading = ref(false)
    const loadingMore = ref(false)
    const page = ref(1)
    const PAGE_SIZE = 10
    const hasMore = ref(true)

    const getModelName = (id) => props.modelMap[id] || `模型${id}`
    const extractCaseInfo = (s) => {
      let question = s.question || '', answer = s.modelResponse || ''
      if (!question && s.extraFields) {
        try {
          const extra = JSON.parse(s.extraFields)
          question = extra.caseDescription || extra.question || extra.输入 || ''
          answer = extra.path_summary || extra.model_response || extra.模型回答 || ''
        } catch { /* */ }
      }
      return { question, answer }
    }

    const filteredSamples = computed(() => {
      if (!onlyDisagree.value) return samples.value
      return samples.value.filter(s => s.aiGoldAgree === false)
    })

    const loadData = async () => {
      listLoading.value = true
      page.value = 1
      hasMore.value = true
      try {
        const [statsRes, samplesRes] = await Promise.all([
          resultApi.getHumanVsAiStats(props.taskId, {
            modelConfigId: filterModelId.value || undefined,
            promptId: filterPromptId.value || undefined
          }),
          resultApi.listHumanVsAiSamples(props.taskId, {
            promptId: filterPromptId.value || undefined,
            modelConfigId: filterModelId.value || undefined,
            page: 1, size: PAGE_SIZE
          })
        ])
        stats.value = statsRes.data
        samples.value = samplesRes.data || []
        if (samples.value.length < PAGE_SIZE) hasMore.value = false
      } finally { listLoading.value = false }
    }

    const loadMore = async () => {
      if (!hasMore.value) return
      loadingMore.value = true
      try {
        page.value++
        const res = await resultApi.listHumanVsAiSamples(props.taskId, {
          promptId: filterPromptId.value || undefined,
          modelConfigId: filterModelId.value || undefined,
          page: page.value, size: PAGE_SIZE
        })
        const newOnes = (res.data || []).filter(n => !samples.value.some(o => o.id === n.id))
        samples.value = [...samples.value, ...newOnes]
        if ((res.data || []).length < PAGE_SIZE) hasMore.value = false
      } finally { loadingMore.value = false }
    }

    const loadSamples = () => loadData()

    const toggleDisagree = () => { onlyDisagree.value = !onlyDisagree.value; loadData() }

    watch(() => props.taskId, () => { if (props.taskId) loadData() }, { immediate: true })

    return {
      filterModelId, filterPromptId, onlyDisagree, loadData, loadSamples, toggleDisagree,
      stats, samples, filteredSamples, listLoading, loadingMore, hasMore, loadMore,
      getModelName, extractCaseInfo
    }
  }
}
</script>

<style scoped>
.compare-tab { }
.filter-bar { display: flex; gap: 8px; margin-bottom: 14px; flex-wrap: wrap; align-items: center; }
.filter-chip { display: inline-flex; align-items: center; gap: 4px; padding: 4px 12px; border-radius: 20px; font-size: 12px; cursor: pointer; border: 1px solid var(--border); background: var(--bg-input); color: var(--text-sec); user-select: none; }
.filter-chip:hover { border-color: #ef4444; color: #ef4444; }
.filter-chip.active { background: rgba(239,68,68,0.1); border-color: #ef4444; color: #ef4444; font-weight: 600; }

.va-dashboard { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 10px; margin-bottom: 14px; }
.va-card { background: var(--bg-card); border: 1px solid var(--border); border-radius: 12px; padding: 14px 16px; display: flex; align-items: center; gap: 12px; box-shadow: var(--shadow-sm); }
.va-card.stat { display: block; text-align: center; padding: 12px; }
.va-card.disagree { border-left: 3px solid var(--red); }
.va-card.agree { border-left: 3px solid var(--accent); }
.va-icon { width: 38px; height: 38px; border-radius: 10px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.va-card.disagree .va-icon { background: var(--red-soft); color: var(--red); }
.va-card.agree .va-icon { background: var(--accent-soft); color: var(--accent); }
.va-rate { font-size: 22px; font-weight: 800; line-height: 1.1; }
.va-rate.warn { color: #d97706; }
.va-rate.bad { color: var(--red); }
.va-label { font-size: 11px; color: var(--text-mute); margin-top: 2px; }
.va-sub { font-size: 10px; color: var(--text-mute); margin-top: 2px; }

.va-tip { background: var(--bg-input); border: 1px solid var(--border); border-radius: 8px; padding: 8px 12px; font-size: 12px; color: var(--text-sec); margin-bottom: 14px; }
.va-tip.warn { background: var(--yellow-soft); border-color: rgba(251,191,36,0.25); color: #92400e; }

.va-list { display: flex; flex-direction: column; gap: 8px; }
.va-item { border: 1px solid var(--border); border-radius: 10px; background: var(--bg-card); overflow: hidden; }
.va-item.disagree { border-left: 3px solid var(--red); }
.va-item-head { display: flex; align-items: center; gap: 8px; padding: 8px 14px; background: var(--bg-input); flex-wrap: wrap; }
.va-seq { font-size: 11px; color: var(--text-mute); font-weight: 600; }
.va-model { font-size: 12px; font-weight: 600; color: #8b5cf6; }
.va-tag { font-size: 11px; font-weight: 600; padding: 1px 8px; border-radius: 20px; }
.va-tag.bad { background: var(--red-soft); color: var(--red); }
.va-tag.good { background: var(--accent-soft); color: var(--accent); }
.va-tag.gold { border: 1px solid #8b5cf6; }
.va-arrow { color: var(--text-mute); }
.va-result { font-size: 11px; font-weight: 600; padding: 2px 10px; border-radius: 20px; margin-left: auto; }
.va-result.disagree { background: var(--red-soft); color: var(--red); }
.va-result.agree { background: var(--accent-soft); color: var(--accent); }
.va-item-body { padding: 10px 14px; }
.va-q, .va-a, .va-reason { font-size: 12px; color: var(--text-sec); line-height: 1.7; margin-bottom: 5px; word-break: break-all; }
.va-label { display: inline-block; background: var(--bg-input); border-radius: 4px; padding: 1px 6px; font-size: 10px; font-weight: 600; color: var(--text-mute); margin-right: 6px; }
.empty-va { color: var(--text-mute); font-size: 13px; text-align: center; padding: 24px 0; }
.load-more { text-align: center; padding: 6px 0; }
</style>
