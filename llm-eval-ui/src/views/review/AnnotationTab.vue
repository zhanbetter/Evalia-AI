<template>
  <div class="annotation-tab">
    <!-- 筛选栏 -->
    <div class="filter-bar">
      <el-select v-model="filterModelId" placeholder="全部模型" size="small" clearable style="width: 150px" @change="loadData">
        <el-option v-for="m in models" :key="m.id" :label="m.name" :value="m.id" />
      </el-select>
      <el-select v-model="filterPromptId" placeholder="全部评估器" size="small" clearable style="width: 150px" @change="loadData">
        <el-option v-for="p in prompts" :key="p.id" :label="p.name" :value="p.id" />
      </el-select>
      <span class="filter-chip" :class="{ active: onlyUnreviewed }" @click="toggleUnreviewed">
        <el-icon :size="12"><Filter /></el-icon> 只看未校验
      </span>
      <span class="filter-chip" v-if="myRole === 'expert'" :class="{ active: onlyDisputed }" @click="toggleDisputed">
        <el-icon :size="12"><Warning /></el-icon> 只看待裁决
      </span>
    </div>

    <!-- 一致率仪表盘 -->
    <div class="agreement-dashboard" v-if="stats">
      <div class="agree-card hh">
        <div class="agree-icon"><el-icon :size="20"><User /></el-icon></div>
        <div class="agree-body">
          <div class="agree-rate" :class="rateClass(stats.humanHumanAgreementRate)">{{ stats.humanHumanAgreementRate.toFixed(1) }}%</div>
          <div class="agree-label">人人一致率</div>
          <div class="agree-sub">{{ stats.allAgreeSampleCount }} / {{ stats.multiReviewerSampleCount }} 双人样本一致</div>
        </div>
      </div>
      <div class="agree-card ha">
        <div class="agree-icon"><el-icon :size="20"><Cpu /></el-icon></div>
        <div class="agree-body">
          <div class="agree-rate" :class="rateClass(stats.humanAiAgreementRate)">{{ stats.humanAiAgreementRate.toFixed(1) }}%</div>
          <div class="agree-label">人机一致率</div>
          <div class="agree-sub">{{ stats.agreeWithAiCount }} / {{ stats.reviewedCount }} 与 AI 一致</div>
        </div>
      </div>
      <div class="agree-card kappa">
        <div class="agree-icon"><el-icon :size="20"><DataLine /></el-icon></div>
        <div class="agree-body">
          <div class="agree-rate" :class="kappaClass(stats.fleissKappa)">{{ formatKappa(stats.fleissKappa) }}</div>
          <div class="agree-label">Fleiss' Kappa</div>
          <div class="agree-sub">{{ stats.kappaLevel }}</div>
        </div>
      </div>
      <div class="agree-card kappa">
        <div class="agree-icon"><el-icon :size="20"><Histogram /></el-icon></div>
        <div class="agree-body">
          <div class="agree-rate" :class="kappaClass(stats.cohenKappaAvg)">{{ formatKappa(stats.cohenKappaAvg) }}</div>
          <div class="agree-label">Cohen's Kappa 均值</div>
          <div class="agree-sub">{{ stats.cohenKappaPairCount }} 对</div>
        </div>
      </div>
      <div class="agree-card stat">
        <div class="agree-rate">{{ stats.reviewedCount }}<span class="unit">/{{ stats.totalCount }}</span></div>
        <div class="agree-label">校验/总样本</div>
      </div>
      <div class="agree-card stat">
        <div class="agree-rate">{{ goldCount }}</div>
        <div class="agree-label">已裁决金标准</div>
      </div>
    </div>

    <!-- 提示 -->
    <div class="agree-tip" v-if="stats && stats.multiReviewerSampleCount >= 3 && !isNaN(stats.fleissKappa) && stats.fleissKappa < 0.4">
      <el-icon :size="14"><InfoFilled /></el-icon> Fleiss' Kappa 低于 0.4，评委间分歧较大，评分标准有歧义，需统一标注规范。
    </div>

    <!-- 专家视图：金标准进度 -->
    <div class="gold-progress" v-if="myRole === 'expert'">
      <span class="gp-label">金标准进度：</span>
      <el-progress :percentage="goldProgress" :stroke-width="10" :color="'#8b5cf6'" style="flex:1" />
      <span class="gp-text">{{ goldCount }} / {{ stats?.totalCount || 0 }}</span>
    </div>

    <!-- 左右分栏 -->
    <div class="review-main">
      <!-- 左侧样本列表 -->
      <div class="sample-list" v-loading="listLoading">
        <div v-for="(s, i) in filteredSamples" :key="s.id"
          class="sample-item" :class="{ active: activeSample && activeSample.id === s.id, bad: s.isBadcase === 1, disputed: s.hasDisagreement }"
          @click="activeSample = s">
          <div class="sample-item-head">
            <span class="sample-seq">#{{ i + 1 }}</span>
            <span :class="['sample-ai-tag', s.isBadcase === 1 ? 'bad' : 'good']">AI:{{ s.isBadcase === 1 ? 'Bad' : 'Good' }}</span>
            <span class="sample-model">{{ s.modelName || getModelName(s.modelConfigId) }}</span>
          </div>
          <div class="sample-q">{{ extractCaseInfo(s).question || '（无问题）' }}</div>
          <div class="sample-reviewers" v-if="s.reviewerVerdicts && s.reviewerVerdicts.length">
            <span v-for="rv in s.reviewerVerdicts" :key="rv.reviewer"
              :class="['rv-chip', rv.isBadcaseHuman === 1 ? 'bad' : 'good', rv.role === 'expert' ? 'expert' : '']"
              :title="rv.reviewer + (rv.role === 'expert' ? '（专家）' : '') + ': ' + (rv.isBadcaseHuman === 1 ? 'Badcase' : 'Goodcase')">
              {{ rv.reviewer }}{{ rv.role === 'expert' ? '★' : '' }}
            </span>
          </div>
          <div class="sample-status-row" v-if="myRole === 'expert'">
            <span v-if="s.hasDisagreement && !s.goldLabel" class="status-tag disputed">待裁决</span>
            <span v-else-if="s.goldLabel" class="status-tag gold">金标准: {{ s.goldLabel.isBadcase === 1 ? 'Bad' : 'Good' }}</span>
            <span v-else-if="s.reviewerVerdicts && s.reviewerVerdicts.length >= 2 && !s.hasDisagreement" class="status-tag agreed">评委一致</span>
          </div>
        </div>
        <div class="empty-samples" v-if="!filteredSamples.length && !listLoading">没有符合条件的样本</div>
        <div class="load-more" v-if="samples.length && hasMore">
          <el-button size="small" text type="primary" @click="loadMoreSamples" :loading="loadingMore">加载更多</el-button>
        </div>
      </div>

      <!-- 右侧详情 -->
      <div class="sample-detail" v-if="activeSample">
        <div class="detail-head">
          <span :class="['detail-ai-tag', activeSample.isBadcase === 1 ? 'bad' : 'good']">
            AI: {{ activeSample.isBadcase === 1 ? 'Badcase' : 'Goodcase' }}
          </span>
          <span class="detail-model">{{ activeSample.modelName || getModelName(activeSample.modelConfigId) }}</span>
        </div>

        <div class="detail-section" v-if="extractCaseInfo(activeSample).question">
          <div class="detail-label">问题</div>
          <div class="detail-text">{{ extractCaseInfo(activeSample).question }}</div>
        </div>
        <div class="detail-section" v-if="extractCaseInfo(activeSample).answer">
          <div class="detail-label">模型回答</div>
          <div class="detail-text answer">{{ extractCaseInfo(activeSample).answer }}</div>
        </div>
        <div class="detail-section" v-if="activeSample.reason">
          <div class="detail-label">AI 判定理由</div>
          <div class="detail-text">{{ activeSample.reason }}</div>
        </div>

        <!-- 所有评委判定（专家可见全部，普通评委可见自己） -->
        <div class="detail-section" v-if="visibleVerdicts.length">
          <div class="detail-label">评委判定（{{ visibleVerdicts.length }}人）</div>
          <div class="verdict-list">
            <div v-for="rv in visibleVerdicts" :key="rv.reviewer" class="verdict-row"
              :class="{ mine: rv.reviewer === myName, expert: rv.role === 'expert' }">
              <span class="v-reviewer">{{ rv.reviewer }}{{ rv.reviewer === myName ? '（我）' : '' }}{{ rv.role === 'expert' ? ' ★专家' : '' }}</span>
              <span :class="['v-tag', rv.isBadcaseHuman === 1 ? 'bad' : 'good']">
                {{ rv.isBadcaseHuman === 1 ? 'Badcase' : 'Goodcase' }}
              </span>
            </div>
          </div>
          <div class="disagree-hint" v-if="activeSample.hasDisagreement">
            <el-icon :size="13"><Warning /></el-icon> 评委有分歧，需专家裁决
          </div>
        </div>

        <!-- 金标准（已裁决） -->
        <div class="detail-section gold-section" v-if="activeSample.goldLabel">
          <div class="detail-label">金标准（已裁决）</div>
          <div class="gold-show">
            <span :class="['v-tag', activeSample.goldLabel.isBadcase === 1 ? 'bad' : 'good']">
              {{ activeSample.goldLabel.isBadcase === 1 ? 'Badcase' : 'Goodcase' }}
            </span>
            <span class="gold-by">裁决人：{{ activeSample.goldLabel.adjudicator }}</span>
          </div>
        </div>

        <!-- 我的判定（普通评委） -->
        <div class="my-verdict" v-if="myRole === 'normal'">
          <div class="my-verdict-label">我的判定：</div>
          <div class="my-verdict-actions">
            <el-button :type="myPick === 1 ? 'danger' : 'default'" plain @click="submitVerdict(1)">Badcase</el-button>
            <el-button :type="myPick === 0 ? 'success' : 'default'" plain @click="submitVerdict(0)">Goodcase</el-button>
          </div>
        </div>

        <!-- 专家裁决 -->
        <div class="my-verdict expert-verdict" v-if="myRole === 'expert' && (!activeSample.goldLabel || activeSample.hasDisagreement)">
          <div class="my-verdict-label">专家裁决（产出金标准）：</div>
          <div class="my-verdict-actions">
            <el-button :type="adjudicatePick === 1 ? 'danger' : 'default'" plain @click="submitAdjudication(1)">裁决为 Badcase</el-button>
            <el-button :type="adjudicatePick === 0 ? 'success' : 'default'" plain @click="submitAdjudication(0)">裁决为 Goodcase</el-button>
          </div>
          <div class="expert-hint" v-if="activeSample.hasDisagreement">
            评委有分歧，请根据专业判断给出最终结论
          </div>
          <div class="expert-hint" v-else>
            评委一致，确认即采纳
          </div>
        </div>
      </div>
      <div class="sample-detail empty" v-else>
        <el-empty description="从左侧选择一个样本" />
      </div>
    </div>
  </div>
</template>

<script>
import { ref, computed, watch } from 'vue'
import { resultApi } from '../../api'
import { ElMessage } from 'element-plus'

export default {
  name: 'AnnotationTab',
  props: {
    taskId: Number, myName: String, myRole: String,
    models: Array, prompts: Array, modelMap: Object, promptMap: Object,
    focusSample: Object
  },
  setup(props) {
    const filterModelId = ref(null)
    const filterPromptId = ref(null)
    const onlyUnreviewed = ref(false)
    const onlyDisputed = ref(false)
    const stats = ref(null)
    const samples = ref([])
    const activeSample = ref(null)
    const listLoading = ref(false)
    const loadingMore = ref(false)
    const page = ref(1)
    const PAGE_SIZE = 10
    const hasMore = ref(true)
    const goldCount = ref(0)

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
      let list = samples.value
      if (onlyUnreviewed.value) {
        list = list.filter(s => !s.reviewerVerdicts || s.reviewerVerdicts.length === 0)
      }
      if (onlyDisputed.value && props.myRole === 'expert') {
        list = list.filter(s => s.hasDisagreement && (!s.goldLabel || s.goldLabel.status === 'PENDING'))
      }
      return list
    })

    const visibleVerdicts = computed(() => {
      if (!activeSample.value || !activeSample.value.reviewerVerdicts) return []
      if (props.myRole === 'expert') return activeSample.value.reviewerVerdicts
      // 普通评委只看自己
      return activeSample.value.reviewerVerdicts.filter(v => v.reviewer === props.myName)
    })

    const myPick = computed(() => {
      if (!activeSample.value || !activeSample.value.reviewerVerdicts || !props.myName) return null
      const mine = activeSample.value.reviewerVerdicts.find(v => v.reviewer === props.myName)
      return mine ? mine.isBadcaseHuman : null
    })

    const adjudicatePick = computed(() => {
      if (!activeSample.value) return null
      if (activeSample.value.goldLabel) return activeSample.value.goldLabel.isBadcase
      // 默认采纳多数评委意见
      const vs = activeSample.value.reviewerVerdicts || []
      if (vs.length === 0) return null
      const badCount = vs.filter(v => v.isBadcaseHuman === 1).length
      return badCount > vs.length / 2 ? 1 : 0
    })

    const goldProgress = computed(() => {
      if (!stats.value || stats.value.totalCount === 0) return 0
      return Math.round(goldCount.value * 100 / stats.value.totalCount)
    })

    const rateClass = (r) => r >= 90 ? 'good' : r >= 80 ? 'ok' : 'low'
    const kappaClass = (k) => {
      if (k === null || k === undefined || isNaN(k)) return 'low'
      if (k >= 0.8) return 'good'
      if (k >= 0.6) return 'ok'
      if (k >= 0.4) return 'warn'
      return 'low'
    }
    const formatKappa = (k) => (k === null || k === undefined || isNaN(k)) ? '-' : k.toFixed(3)

    const loadStats = async () => {
      try {
        const res = await resultApi.getHumanReviewStats(props.taskId, {
          modelConfigId: filterModelId.value || undefined,
          promptId: filterPromptId.value || undefined
        })
        stats.value = res.data
        // 金标准数
        // 这里简单用总数 - 未裁决数估算，实际可单独接口。暂从 samples 推算
      } catch (e) { /* */ }
    }

    const loadData = async () => {
      listLoading.value = true
      page.value = 1
      hasMore.value = true
      try {
        await loadStats()
        await loadSamples()
      } finally { listLoading.value = false }
    }

    const toggleUnreviewed = () => { onlyUnreviewed.value = !onlyUnreviewed.value; loadData() }
    const toggleDisputed = () => { onlyDisputed.value = !onlyDisputed.value; loadData() }

    const loadSamples = async () => {
      const res = await resultApi.listReviewSamples(props.taskId, {
        promptId: filterPromptId.value || undefined,
        modelConfigId: filterModelId.value || undefined,
        reviewer: props.myName || undefined,
        role: props.myRole || 'normal',
        page: page.value, size: PAGE_SIZE
      })
      if (page.value === 1) {
        samples.value = res.data || []
        activeSample.value = samples.value[0] || null
        // 统计金标准数
        goldCount.value = (res.data || []).filter(s => s.goldLabel).length
      } else {
        const newOnes = (res.data || []).filter(n => !samples.value.some(o => o.id === n.id))
        samples.value = [...samples.value, ...newOnes]
        goldCount.value = samples.value.filter(s => s.goldLabel).length
      }
      if ((res.data || []).length < PAGE_SIZE) hasMore.value = false
    }

    const loadMoreSamples = async () => {
      if (!hasMore.value) return
      loadingMore.value = true
      try {
        page.value++
        await loadSamples()
      } finally { loadingMore.value = false }
    }

    const refreshCurrent = async () => {
      await loadStats()
      const res = await resultApi.listReviewSamples(props.taskId, {
        promptId: filterPromptId.value || undefined,
        modelConfigId: filterModelId.value || undefined,
        reviewer: props.myName || undefined,
        role: props.myRole || 'normal',
        page: 1, size: page.value * PAGE_SIZE
      })
      const oldActiveId = activeSample.value?.id
      samples.value = res.data || []
      goldCount.value = samples.value.filter(s => s.goldLabel).length
      activeSample.value = samples.value.find(s => s.id === oldActiveId) || samples.value[0] || null
    }

    const submitVerdict = async (humanBad) => {
      if (!props.myName || !props.myName.trim()) {
        ElMessage.warning('请先在上方填写"我的身份"'); return
      }
      localStorage.setItem('eval-reviewer', props.myName.trim())
      try {
        await resultApi.submitHumanReview({
          taskId: props.taskId,
          modelConfigId: activeSample.value.modelConfigId,
          promptId: activeSample.value.promptId,
          datasetItemId: activeSample.value.datasetItemId,
          isBadcaseHuman: humanBad,
          reviewer: props.myName.trim(),
          role: props.myRole || 'normal'
        })
        ElMessage.success('已提交判定')
        await refreshCurrent()
      } catch (e) {
        ElMessage.error(e.response?.data?.message || '提交失败')
      }
    }

    const submitAdjudication = async (goldBad) => {
      if (!props.myName || !props.myName.trim()) {
        ElMessage.warning('请先填写"我的身份"'); return
      }
      if (props.myRole !== 'expert') {
        ElMessage.warning('只有专家可以裁决'); return
      }
      try {
        await resultApi.adjudicate({
          taskId: props.taskId,
          modelConfigId: activeSample.value.modelConfigId,
          promptId: activeSample.value.promptId,
          datasetItemId: activeSample.value.datasetItemId,
          isBadcase: goldBad,
          adjudicator: props.myName.trim()
        })
        ElMessage.success('已裁决，写入金标准')
        await refreshCurrent()
      } catch (e) {
        ElMessage.error(e.response?.data?.message || '裁决失败')
      }
    }

    watch(() => props.taskId, () => { if (props.taskId) loadData() }, { immediate: true })

    // 从失败案例跳转定位到指定样本
    watch(() => props.focusSample, (fs) => {
      if (!fs) return
      const found = samples.value.find(s =>
        s.modelConfigId === fs.modelConfigId && s.datasetItemId === fs.datasetItemId)
      if (found) {
        activeSample.value = found
      } else {
        // 当前页没加载到，尝试加载更多直到找到（简单处理：重载第一页并尝试）
        loadData()
      }
    })

    return {
      filterModelId, filterPromptId, onlyUnreviewed, onlyDisputed, loadData, loadSamples,
      toggleUnreviewed, toggleDisputed,
      stats, rateClass, kappaClass, formatKappa,
      samples, filteredSamples, activeSample, listLoading, loadingMore, hasMore, loadMoreSamples,
      getModelName, extractCaseInfo,
      visibleVerdicts, myPick, adjudicatePick, submitVerdict, submitAdjudication,
      goldCount, goldProgress
    }
  }
}
</script>

<style scoped>
.annotation-tab { }
.filter-bar { display: flex; gap: 8px; margin-bottom: 14px; flex-wrap: wrap; align-items: center; }
.filter-chip { display: inline-flex; align-items: center; gap: 4px; padding: 4px 12px; border-radius: 20px; font-size: 12px; cursor: pointer; border: 1px solid var(--border); background: var(--bg-input); color: var(--text-sec); user-select: none; }
.filter-chip:hover { border-color: #8b5cf6; color: #8b5cf6; }
.filter-chip.active { background: rgba(139,92,246,0.12); border-color: #8b5cf6; color: #8b5cf6; font-weight: 600; }

.agreement-dashboard { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 10px; margin-bottom: 14px; }
.agree-card { background: var(--bg-card); border: 1px solid var(--border); border-radius: 12px; padding: 14px 16px; display: flex; align-items: center; gap: 12px; box-shadow: var(--shadow-sm); }
.agree-card.stat { display: block; text-align: center; padding: 12px; }
.agree-card.ha { border-left: 3px solid #8b5cf6; }
.agree-card.hh { border-left: 3px solid #06b6d4; }
.agree-card.kappa { border-left: 3px solid #f59e0b; }
.agree-icon { width: 38px; height: 38px; border-radius: 10px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.agree-card.ha .agree-icon { background: rgba(139,92,246,0.12); color: #8b5cf6; }
.agree-card.hh .agree-icon { background: rgba(6,182,212,0.12); color: #06b6d4; }
.agree-card.kappa .agree-icon { background: rgba(245,158,11,0.12); color: #f59e0b; }
.agree-rate { font-size: 22px; font-weight: 800; line-height: 1.1; }
.agree-rate.good { color: var(--accent); } .agree-rate.ok { color: var(--yellow); } .agree-rate.warn { color: #d97706; } .agree-rate.low { color: var(--red); }
.agree-card.stat .agree-rate { font-size: 22px; }
.unit { font-size: 13px; color: var(--text-mute); font-weight: 600; }
.agree-label { font-size: 11px; color: var(--text-mute); margin-top: 2px; }
.agree-sub { font-size: 10px; color: var(--text-mute); margin-top: 2px; }

.agree-tip { background: var(--yellow-soft); border: 1px solid rgba(251,191,36,0.25); border-radius: 8px; padding: 8px 12px; font-size: 12px; color: #92400e; margin-bottom: 12px; display: flex; align-items: center; gap: 6px; }

.gold-progress { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; padding: 8px 12px; background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; }
.gp-label { font-size: 12px; font-weight: 600; color: var(--text-sec); white-space: nowrap; }
.gp-text { font-size: 12px; color: var(--text-mute); white-space: nowrap; }

.review-main { display: grid; grid-template-columns: 320px 1fr; gap: 14px; min-height: 400px; }
.sample-list { background: var(--bg-card); border: 1px solid var(--border); border-radius: 12px; padding: 8px; overflow-y: auto; max-height: calc(100vh - 460px); }
.sample-item { border: 1px solid var(--border); border-radius: 8px; padding: 9px 11px; margin-bottom: 6px; cursor: pointer; transition: all 0.15s; }
.sample-item:hover { border-color: var(--border-strong); }
.sample-item.active { border-color: #8b5cf6; background: rgba(139,92,246,0.06); box-shadow: 0 0 0 1px #8b5cf6; }
.sample-item.bad { border-left: 3px solid var(--red); }
.sample-item.disputed { border-left: 3px solid #f59e0b; }
.sample-item-head { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; }
.sample-seq { font-size: 10px; color: var(--text-mute); font-weight: 600; }
.sample-ai-tag { font-size: 10px; font-weight: 600; padding: 1px 6px; border-radius: 20px; }
.sample-ai-tag.bad { background: var(--red-soft); color: var(--red); }
.sample-ai-tag.good { background: var(--accent-soft); color: var(--accent); }
.sample-model { font-size: 10px; color: var(--text-mute); margin-left: auto; }
.sample-q { font-size: 12px; color: var(--text-prime); line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.sample-reviewers { display: flex; gap: 3px; flex-wrap: wrap; margin-top: 5px; }
.rv-chip { font-size: 9px; font-weight: 600; padding: 1px 6px; border-radius: 20px; }
.rv-chip.bad { background: var(--red-soft); color: var(--red); }
.rv-chip.good { background: var(--accent-soft); color: var(--accent); }
.rv-chip.expert { border: 1px solid #d97706; }
.sample-status-row { margin-top: 5px; }
.status-tag { font-size: 10px; font-weight: 600; padding: 1px 8px; border-radius: 20px; }
.status-tag.disputed { background: rgba(245,158,11,0.15); color: #d97706; }
.status-tag.gold { background: rgba(139,92,246,0.15); color: #8b5cf6; }
.status-tag.agreed { background: var(--accent-soft); color: var(--accent); }
.empty-samples { color: var(--text-mute); font-size: 13px; text-align: center; padding: 24px 0; }
.load-more { text-align: center; padding: 6px 0; }

.sample-detail { background: var(--bg-card); border: 1px solid var(--border); border-radius: 12px; padding: 16px 20px; overflow-y: auto; max-height: calc(100vh - 460px); }
.sample-detail.empty { display: flex; align-items: center; justify-content: center; }
.detail-head { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; padding-bottom: 10px; border-bottom: 1px solid var(--border); }
.detail-ai-tag { font-size: 12px; font-weight: 700; padding: 3px 10px; border-radius: 20px; }
.detail-ai-tag.bad { background: var(--red-soft); color: var(--red); }
.detail-ai-tag.good { background: var(--accent-soft); color: var(--accent); }
.detail-model { font-size: 12px; font-weight: 600; color: #8b5cf6; }
.detail-section { margin-bottom: 14px; }
.detail-label { font-size: 11px; font-weight: 600; color: var(--text-mute); margin-bottom: 4px; text-transform: uppercase; letter-spacing: 0.4px; }
.detail-text { font-size: 13px; color: var(--text-sec); line-height: 1.7; white-space: pre-wrap; word-break: break-all; }
.detail-text.answer { background: var(--bg-input); padding: 10px 12px; border-radius: 8px; }
.verdict-list { display: flex; flex-direction: column; gap: 5px; }
.verdict-row { display: flex; align-items: center; gap: 8px; padding: 6px 10px; background: var(--bg-input); border-radius: 6px; }
.verdict-row.mine { background: rgba(139,92,246,0.08); border: 1px solid rgba(139,92,246,0.3); }
.verdict-row.expert { border-left: 3px solid #d97706; }
.v-reviewer { font-size: 12px; font-weight: 600; min-width: 80px; }
.v-tag { font-size: 10px; font-weight: 600; padding: 1px 8px; border-radius: 20px; }
.v-tag.bad { background: var(--red-soft); color: var(--red); }
.v-tag.good { background: var(--accent-soft); color: var(--accent); }
.disagree-hint { font-size: 11px; color: #d97706; margin-top: 6px; display: flex; align-items: center; gap: 4px; }
.gold-section { background: rgba(139,92,246,0.06); padding: 10px 12px; border-radius: 8px; border: 1px solid rgba(139,92,246,0.2); }
.gold-show { display: flex; align-items: center; gap: 10px; }
.gold-by { font-size: 11px; color: var(--text-mute); }

.my-verdict { margin-top: 16px; padding: 14px; background: rgba(139,92,246,0.06); border: 1px dashed rgba(139,92,246,0.4); border-radius: 10px; }
.expert-verdict { background: rgba(245,158,11,0.06); border-color: rgba(245,158,11,0.4); }
.my-verdict-label { font-size: 13px; font-weight: 600; color: #8b5cf6; margin-bottom: 8px; }
.expert-verdict .my-verdict-label { color: #d97706; }
.my-verdict-actions { display: flex; gap: 8px; }
.expert-hint { font-size: 11px; color: var(--text-mute); margin-top: 6px; }

@media (max-width: 900px) { .review-main { grid-template-columns: 1fr; } }
</style>
