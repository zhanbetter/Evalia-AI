<template>
  <div class="annotation-tab">
    <!-- 顶部状态条：统计收敛为紧凑 chips，右侧放筛选 -->
    <div class="stat-strip">
      <div class="stat-chip">
        已校验 <b>{{ stats?.reviewedCount ?? 0 }}</b>
        <i>/{{ stats?.totalCount ?? 0 }}</i>
      </div>
      <div class="stat-chip">人人一致 <b :class="rateClass(stats?.humanHumanAgreementRate ?? NaN)">{{ fmtPct(stats?.humanHumanAgreementRate) }}</b></div>
      <div class="stat-chip">人机一致 <b :class="rateClass(stats?.humanAiAgreementRate ?? NaN)">{{ fmtPct(stats?.humanAiAgreementRate) }}</b></div>
      <div class="stat-chip">Kappa <b :class="kappaClass(stats?.fleissKappa)">{{ formatKappa(stats?.fleissKappa) }}</b></div>
      <div class="stat-chip" v-if="myRole === 'expert'">
        金标准 <el-progress :percentage="goldProgress" :stroke-width="7" :color="'#8b5cf6'" style="width: 86px" />
        <b>{{ goldCount }}</b>/{{ stats?.totalCount || 0 }}
      </div>

      <div class="stat-spacer"></div>

      <el-select v-model="filterModelId" placeholder="全部模型" size="small" clearable style="width: 130px" @change="loadData">
        <el-option v-for="m in models" :key="m.id" :label="m.name" :value="m.id" />
      </el-select>
      <el-select v-model="filterPromptId" placeholder="全部评估器" size="small" clearable style="width: 130px" @change="loadData">
        <el-option v-for="p in prompts" :key="p.id" :label="p.name" :value="p.id" />
      </el-select>
      <span class="filter-chip" :class="{ active: onlyUnreviewed }" @click="toggleUnreviewed">
        <el-icon :size="12"><Filter /></el-icon> 只看未校验
      </span>
      <span class="filter-chip" v-if="myRole === 'expert'" :class="{ active: onlyDisputed }" @click="toggleDisputed">
        <el-icon :size="12"><Warning /></el-icon> 只看待裁决
      </span>
      <span class="filter-chip diff" :class="{ active: onlyAiHumanDisagree }" @click="toggleAiHumanDisagree">
        <el-icon :size="12"><Warning /></el-icon> 仅看人机不一致
      </span>
    </div>

    <!-- Kappa 失真预警（仅分歧较大时出现） -->
    <div class="agree-tip" v-if="stats && stats.multiReviewerSampleCount >= 3 && !isNaN(stats.fleissKappa) && stats.fleissKappa < 0.4">
      <el-icon :size="14"><InfoFilled /></el-icon> Fleiss' Kappa 低于 0.4，评委间分歧较大，评分标准有歧义，需统一标注规范。
    </div>

    <!-- 主体：左队列 / 右标注 -->
    <div class="review-main">
      <!-- 左侧样本队列 -->
      <aside class="sample-list" v-loading="listLoading">
        <div class="list-head">
          <span class="list-title">样本队列</span>
          <span class="list-count">{{ filteredSamples.length }} 条</span>
        </div>

        <div v-for="(s, i) in filteredSamples" :key="s.id"
          class="sample-item"
          :class="{ active: activeSample && activeSample.id === s.id, bad: s.isBadcase === 1, disputed: s.hasDisagreement, done: myDone(s) }"
          @click="selectSample(s)">
          <div class="sample-item-head">
            <span class="sample-seq">#{{ i + 1 }}</span>
            <span v-if="s.goldLabel" class="s-status gold">金标准</span>
            <span v-else-if="myDone(s)" class="s-status done"><el-icon :size="10"><Check /></el-icon> 已标</span>
            <span v-else-if="s.hasDisagreement" class="s-status warn">分歧</span>
            <span v-else class="s-status todo">未标</span>
            <span v-if="aiHumanDisagree(s)" class="s-status diff"><el-icon :size="10"><Warning /></el-icon> 人机不一致</span>
          </div>
          <div class="sample-q">{{ extractCaseInfo(s).question || '（无问题）' }}</div>
          <div class="sample-meta">
            <span class="s-model">{{ s.modelName || getModelName(s.modelConfigId) }}</span>
            <span class="s-ai" :class="s.isBadcase === 1 ? 'bad' : 'good'">AI:{{ s.isBadcase === 1 ? 'Bad' : 'Good' }}</span>
            <span class="s-reviewers" v-if="s.reviewerVerdicts && s.reviewerVerdicts.length">
              <span v-for="rv in s.reviewerVerdicts" :key="rv.reviewer"
                :class="['rv-chip', rv.isBadcaseHuman === 1 ? 'bad' : 'good', rv.role === 'expert' ? 'expert' : '']"
                :title="rv.reviewer + (rv.role === 'expert' ? '（专家）' : '') + ': ' + (rv.isBadcaseHuman === 1 ? 'Badcase' : 'Goodcase')">
                {{ rv.reviewer }}{{ rv.role === 'expert' ? '★' : '' }}
              </span>
            </span>
          </div>
        </div>

        <div class="empty-samples" v-if="!filteredSamples.length && !listLoading">
          <el-empty :image-size="42" description="没有符合条件的样本" />
        </div>
        <div class="load-more" v-if="samples.length && hasMore">
          <el-button size="small" text type="primary" @click="loadMoreSamples" :loading="loadingMore">加载更多</el-button>
        </div>
      </aside>

      <!-- 右侧标注区 -->
      <section class="sample-detail" v-if="activeSample">
        <div class="detail-head">
          <span class="detail-model">{{ activeSample.modelName || getModelName(activeSample.modelConfigId) }}</span>
          <span class="detail-prompt" v-if="activeSample.promptName">{{ activeSample.promptName }}</span>
          <span :class="['detail-ai-tag', activeSample.isBadcase === 1 ? 'bad' : 'good']">
            判定：{{ activeSample.isBadcase === 1 ? 'Badcase' : 'Goodcase' }}
          </span>
          <span class="head-status" v-if="activeSample.goldLabel">· 已裁决金标准</span>
          <span class="head-status warn" v-else-if="activeSample.hasDisagreement">· 评委分歧待裁决</span>
          <span class="detail-diff-tag" v-if="aiHumanDisagree(activeSample)">
            <el-icon :size="12"><Warning /></el-icon> AI·人工不一致
          </span>
        </div>

        <div class="detail-body">
          <div class="detail-section" v-if="extractCaseInfo(activeSample).question">
            <div class="detail-label">问题</div>
            <div class="detail-text">{{ extractCaseInfo(activeSample).question }}</div>
          </div>

          <!-- 上下文若有 -->
          <div class="detail-section" v-if="extractCaseInfo(activeSample).context">
            <div class="detail-label">上下文</div>
            <div class="detail-text context">{{ extractCaseInfo(activeSample).context }}</div>
          </div>

          <div class="detail-section" v-if="extractCaseInfo(activeSample).answer">
            <div class="detail-label">模型回答</div>
            <div class="detail-text answer">{{ extractCaseInfo(activeSample).answer }}</div>
          </div>

          <div class="detail-section" v-if="activeSample.reason">
            <div class="detail-label">AI 判定理由</div>
            <div class="detail-text reason">{{ activeSample.reason }}</div>
          </div>

          <!-- 评委意见 -->
          <div class="detail-section" v-if="visibleVerdicts.length">
            <div class="detail-label">评委意见（{{ visibleVerdicts.length }} 人）</div>
            <div class="verdict-list">
              <div v-for="rv in visibleVerdicts" :key="rv.reviewer" class="verdict-row"
                :class="{ mine: rv.reviewer === myName, expert: rv.role === 'expert' }">
                <span class="v-reviewer">{{ rv.reviewer }}{{ rv.reviewer === myName ? '（我）' : '' }}{{ rv.role === 'expert' ? ' ★专家' : '' }}</span>
                <span :class="['v-tag', rv.isBadcaseHuman === 1 ? 'bad' : 'good']">
                  {{ rv.isBadcaseHuman === 1 ? 'Badcase' : 'Goodcase' }}
                </span>
                <span class="v-comment" v-if="rv.comment">{{ rv.comment }}</span>
              </div>
            </div>
            <div class="disagree-hint" v-if="activeSample.hasDisagreement">
              <el-icon :size="13"><Warning /></el-icon> 评委有分歧，需专家裁决
            </div>
          </div>
        </div>

        <!-- 底部固定判定区 -->
        <div class="judge-bar" :class="myRole === 'expert' ? 'expert' : ''">
          <!-- 普通评委 -->
          <template v-if="myRole !== 'expert'">
            <span class="judge-title">我的判定</span>
            <button type="button" class="jb bad" :class="{ active: pendingPick === 1 }" @click="pendingPick = 1">
              <span class="jb-title-row"><span class="kbd">1</span>Badcase</span><span class="jb-sub">问题样本</span>
            </button>
            <button type="button" class="jb good" :class="{ active: pendingPick === 0 }" @click="pendingPick = 0">
              <span class="jb-title-row"><span class="kbd">2</span>Goodcase</span><span class="jb-sub">达标样本</span>
            </button>
            <el-input v-model="verdictComment" placeholder="判定理由（可选）" size="small" clearable style="width: 200px" />
            <span class="jb-hint"><el-icon :size="12"><Pointer /></el-icon> 1/2 选择，Enter 保存并下一条</span>
            <el-button type="primary" size="small" style="margin-left: auto" @click="submitPending">保存并下一条</el-button>
          </template>

          <!-- 专家：已裁决 -->
          <template v-else-if="activeSample.goldLabel && !activeSample.hasDisagreement">
            <span class="judge-title">金标准</span>
            <span :class="['v-tag', activeSample.goldLabel.isBadcase === 1 ? 'bad' : 'good']">
              {{ activeSample.goldLabel.isBadcase === 1 ? 'Badcase' : 'Goodcase' }}
            </span>
            <span class="jb-hint">专家组裁决人：{{ activeSample.goldLabel.adjudicator }}</span>
            <span class="jb-hint" v-if="activeSample.goldLabel.adjudicateComment" style="margin-left: auto">裁决理由：{{ activeSample.goldLabel.adjudicateComment }}</span>
          </template>

          <!-- 专家：待裁决 -->
          <template v-else>
            <span class="judge-title">专家裁决</span>
            <button type="button" class="jb bad" :class="{ active: pendingPick === 1 }" @click="pendingPick = 1">
              <span class="jb-title-row"><span class="kbd">1</span>裁决 Badcase</span><span class="jb-sub">写入金标准</span>
            </button>
            <button type="button" class="jb good" :class="{ active: pendingPick === 0 }" @click="pendingPick = 0">
              <span class="jb-title-row"><span class="kbd">2</span>裁决 Goodcase</span><span class="jb-sub">写入金标准</span>
            </button>
            <el-input v-model="verdictComment" placeholder="裁决理由（可选）" size="small" clearable style="width: 200px" />
            <span class="jb-hint">
              <el-icon :size="12"><Pointer /></el-icon> 已预填多数意见 · Enter 确认
              <span v-if="activeSample.hasDisagreement" class="jb-warn">评委有分歧，请以专业判断裁决</span>
            </span>
            <el-button type="warning" size="small" style="margin-left: auto" @click="submitPendingAdjudicate">确认裁决</el-button>
          </template>
        </div>
      </section>

      <section class="sample-detail empty" v-else>
        <el-empty description="选择左侧样本开始标注" />
      </section>
    </div>
  </div>
</template>

<script>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
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
    const onlyAiHumanDisagree = ref(false)
    const stats = ref(null)
    const samples = ref([])
    const activeSample = ref(null)
    const verdictComment = ref('')
    const pendingPick = ref(null)
    const listLoading = ref(false)
    const loadingMore = ref(false)
    const page = ref(1)
    const PAGE_SIZE = 10
    const hasMore = ref(true)
    const goldCount = ref(0)

    const getModelName = (id) => props.modelMap[id] || `模型${id}`
    const extractCaseInfo = (s) => {
      let question = s.question || '', answer = s.modelResponse || '', context = s.context || ''
      if ((!question || !answer || !context) && s.extraFields) {
        try {
          const extra = JSON.parse(s.extraFields)
          if (!question) question = extra.caseDescription || extra.question || extra.输入 || ''
          if (!answer) answer = extra.path_summary || extra.model_response || extra.模型回答 || ''
          if (!context) context = extra.context || extra.背景 || ''
        } catch { /* */ }
      }
      return { question, answer, context }
    }

    // 人机不一致：AI 判定 与 任何人工结论（专家金标准 或 任一评委判定）相悖
    const aiHumanDisagree = (s) => {
      if (s.isBadcase == null) return false
      const g = s.goldLabel
      if (g && g.status === 'CONFIRMED' && g.isBadcase != null && g.isBadcase !== s.isBadcase) return true
      const vs = s.reviewerVerdicts || []
      return vs.some(v => v.isBadcaseHuman != null && v.isBadcaseHuman !== s.isBadcase)
    }

    const filteredSamples = computed(() => {
      let list = samples.value
      if (onlyUnreviewed.value) {
        list = list.filter(s => !s.reviewerVerdicts || s.reviewerVerdicts.length === 0)
      }
      if (onlyDisputed.value && props.myRole === 'expert') {
        list = list.filter(s => s.hasDisagreement && (!s.goldLabel || s.goldLabel.status === 'PENDING'))
      }
      if (onlyAiHumanDisagree.value) {
        list = list.filter(s => aiHumanDisagree(s))
      }
      return list
    })

    const visibleVerdicts = computed(() => {
      if (!activeSample.value || !activeSample.value.reviewerVerdicts) return []
      if (props.myRole === 'expert') return activeSample.value.reviewerVerdicts
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
      const vs = activeSample.value.reviewerVerdicts || []
      if (vs.length === 0) return null
      const badCount = vs.filter(v => v.isBadcaseHuman === 1).length
      return badCount > vs.length / 2 ? 1 : 0
    })

    const goldProgress = computed(() => {
      if (!stats.value || stats.value.totalCount === 0) return 0
      return Math.round(goldCount.value * 100 / stats.value.totalCount)
    })

    const statsReviewdDone = null
    const fmtPct = (v) => (v === null || v === undefined || isNaN(v)) ? '-' : v.toFixed(1) + '%'
    const myDone = (s) => (s.reviewerVerdicts || []).some(v => v.reviewer === props.myName)
    const rateClass = (r) => (isNaN(r) ? 'mute' : r >= 90 ? 'good' : r >= 80 ? 'ok' : 'low')
    const kappaClass = (k) => {
      if (k === null || k === undefined || isNaN(k)) return 'mute'
      if (k >= 0.8) return 'good'
      if (k >= 0.6) return 'ok'
      if (k >= 0.4) return 'warn'
      return 'low'
    }
    const formatKappa = (k) => (k === null || k === undefined || isNaN(k)) ? '-' : k.toFixed(3)

    const selectSample = (s) => { activeSample.value = s }

    const loadStats = async () => {
      try {
        const res = await resultApi.getHumanReviewStats(props.taskId, {
          modelConfigId: filterModelId.value || undefined,
          promptId: filterPromptId.value || undefined
        })
        stats.value = res.data
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
    const toggleAiHumanDisagree = () => { onlyAiHumanDisagree.value = !onlyAiHumanDisagree.value }

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

    // 保存后推进到当前过滤视图下的下一条（自动跳过已处理项）
    const afterSave = async () => {
      await refreshCurrent()
      const list = filteredSamples.value
      const idx = activeSample.value ? list.findIndex(s => s.id === activeSample.value.id) : -1
      const next = list.find((s, i) => i > idx)
      activeSample.value = next || null
      if (!next && list.length) ElMessage.success('本轮样本已全部处理')
    }

    const nextSample = () => {
      const list = filteredSamples.value
      const idx = activeSample.value ? list.findIndex(s => s.id === activeSample.value.id) : -1
      activeSample.value = list.find((s, i) => i > idx) || null
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
          role: props.myRole || 'normal',
          comment: verdictComment.value.trim() || undefined
        })
        verdictComment.value = ''
        ElMessage.success(humanBad === 1 ? '已标为 Badcase' : '已标为 Goodcase')
        await afterSave()
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
          adjudicator: props.myName.trim(),
          comment: verdictComment.value.trim() || undefined
        })
        verdictComment.value = ''
        ElMessage.success('已裁决并写入金标准')
        await afterSave()
      } catch (e) {
        ElMessage.error(e.response?.data?.message || '裁决失败')
      }
    }

    // 判定/裁决「保存并下一条」入口：先选择（pendingPick）+ 可选理由，再提交
    const submitPending = () => {
      if (pendingPick.value === null || pendingPick.value === undefined) {
        ElMessage.warning('请先选择 Badcase 或 Goodcase'); return
      }
      submitVerdict(pendingPick.value)
    }
    const submitPendingAdjudicate = () => {
      if (pendingPick.value === null || pendingPick.value === undefined) {
        ElMessage.warning('请先选择裁决结论'); return
      }
      submitAdjudication(pendingPick.value)
    }
    // Enter 统一入口：无选择则跳到下一条，有选择则提交
    const submitNow = () => {
      if (pendingPick.value === null || pendingPick.value === undefined) nextSample()
      else props.myRole === 'expert' ? submitAdjudication(pendingPick.value) : submitVerdict(pendingPick.value)
    }

    // 快捷键：1/2 切换判定，Enter 提交并下一项（输入框内 Enter 同样生效）
    const onKeydown = (e) => {
      if (!activeSample.value) return
      const t = e.target
      if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA')) {
        if (e.key === 'Enter') { e.preventDefault(); submitNow() }
        return
      }
      if (e.key === '1') { e.preventDefault(); pendingPick.value = 1 }
      else if (e.key === '2') { e.preventDefault(); pendingPick.value = 0 }
      else if (e.key === 'Enter') { e.preventDefault(); submitNow() }
    }
    onMounted(() => window.addEventListener('keydown', onKeydown))
    onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))

    watch(() => props.taskId, () => { if (props.taskId) loadData() }, { immediate: true })

    // 切换样本时：重置理由，预填已有判定/多数意见（专家预填后才可 Enter 直接确认）
    watch(activeSample, () => {
      verdictComment.value = ''
      pendingPick.value = props.myRole === 'expert' ? adjudicatePick.value : myPick.value
    })

    watch(() => props.focusSample, (fs) => {
      if (!fs) return
      const found = samples.value.find(s =>
        s.modelConfigId === fs.modelConfigId && s.datasetItemId === fs.datasetItemId)
      if (found) {
        activeSample.value = found
      } else {
        loadData()
      }
    })

    return {
      filterModelId, filterPromptId, onlyUnreviewed, onlyDisputed, loadData, toggleUnreviewed, toggleDisputed,
      onlyAiHumanDisagree, toggleAiHumanDisagree, aiHumanDisagree,
      stats, rateClass, kappaClass, formatKappa, fmtPct,
      samples, filteredSamples, activeSample, verdictComment, pendingPick, submitPending, submitPendingAdjudicate, listLoading, loadingMore, hasMore, loadMoreSamples,
      getModelName, extractCaseInfo, selectSample, myDone,
      visibleVerdicts, myPick, adjudicatePick, submitVerdict, submitAdjudication,
      goldCount, goldProgress
    }
  }
}
</script>

<style scoped>
.annotation-tab { font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Microsoft YaHei', sans-serif; flex: 1; min-height: 0; display: flex; flex-direction: column; }

/* ===== 顶部状态条 ===== */
.stat-strip { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; padding: 8px 12px; margin-bottom: 10px; background: var(--bg-card); border: 1px solid var(--border); border-radius: 10px; box-shadow: var(--shadow-sm); }
.stat-chip { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; color: var(--text-mute); padding: 2px 4px; white-space: nowrap; }
.stat-chip + .stat-chip::before { content: ''; width: 1px; height: 12px; background: var(--border); margin-right: 4px; }
.stat-chip b { font-weight: 700; color: var(--text-sec); }
.stat-chip b.done { color: var(--accent); }
.stat-chip b.good { color: var(--accent); } .stat-chip b.ok { color: var(--yellow); }
.stat-chip b.warn { color: #d97706; } .stat-chip b.low { color: var(--red); } .stat-chip b.mute { color: var(--text-mute); }
.stat-chip i { font-style: normal; color: var(--text-mute); font-size: 11px; }
.stat-spacer { flex: 1; }
.filter-chip { display: inline-flex; align-items: center; gap: 4px; padding: 3px 11px; border-radius: 20px; font-size: 12px; cursor: pointer; border: 1px solid var(--border); background: var(--bg-input); color: var(--text-sec); user-select: none; white-space: nowrap; }
.filter-chip:hover { border-color: #8b5cf6; color: #8b5cf6; }
.filter-chip.active { background: rgba(139,92,246,0.12); border-color: #8b5cf6; color: #8b5cf6; font-weight: 600; }
.filter-chip.diff:hover { border-color: var(--red); color: var(--red); }
.filter-chip.diff.active { background: var(--red-soft); border-color: var(--red); color: var(--red); font-weight: 600; }

.agree-tip { background: var(--yellow-soft); border: 1px solid rgba(251,191,36,0.25); border-radius: 8px; padding: 7px 12px; font-size: 12px; color: #92400e; margin-bottom: 10px; display: flex; align-items: center; gap: 6px; }

/* ===== 主体布局 ===== */
.review-main { flex: 1; min-height: 0; display: grid; grid-template-columns: 336px 1fr; gap: 14px; }

/* ===== 左侧队列 ===== */
.sample-list { background: var(--bg-card); border: 1px solid var(--border); border-radius: 12px; padding: 8px; overflow-y: auto; display: flex; flex-direction: column; box-shadow: var(--shadow-sm); min-height: 0; }
.list-head { display: flex; align-items: center; justify-content: space-between; padding: 4px 6px 8px; }
.list-title { font-size: 13px; font-weight: 700; color: var(--text-prime); }
.list-count { font-size: 11px; color: var(--text-mute); }
.sample-item { border: 1px solid var(--border); border-radius: 8px; padding: 8px 11px; margin-bottom: 6px; cursor: pointer; transition: all 0.15s; flex-shrink: 0; }
.sample-item:hover { border-color: var(--border-strong); background: var(--bg-card-hover); }
.sample-item.active { border-color: #8b5cf6; background: rgba(139,92,246,0.06); box-shadow: 0 0 0 1px #8b5cf6; }
.sample-item.bad { border-left: 3px solid var(--red); }
.sample-item.disputed { border-left: 3px solid #f59e0b; }
.sample-item.done { opacity: 0.82; }
.sample-item-head { display: flex; align-items: center; gap: 5px; margin-bottom: 4px; }
.sample-seq { font-size: 10px; color: var(--text-mute); font-weight: 600; }
.s-status { font-size: 10px; font-weight: 600; padding: 1px 7px; border-radius: 20px; display: inline-flex; align-items: center; gap: 2px; }
.s-status.done { background: var(--accent-soft); color: var(--accent); }
.s-status.todo { background: var(--bg-input); color: var(--text-mute); border: 1px dashed var(--border-strong); }
.s-status.warn { background: rgba(245,158,11,0.15); color: #d97706; }
.s-status.gold { background: rgba(139,92,246,0.15); color: #8b5cf6; }
.s-status.diff { background: var(--red-soft); color: var(--red); }
.sample-q { font-size: 12px; color: var(--text-prime); line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.sample-meta { display: flex; align-items: center; gap: 5px; margin-top: 5px; flex-wrap: wrap; }
.s-model { font-size: 10px; color: var(--text-mute); }
.s-ai { font-size: 10px; font-weight: 600; padding: 0px 6px; border-radius: 20px; }
.s-ai.bad { background: var(--red-soft); color: var(--red); } .s-ai.good { background: var(--accent-soft); color: var(--accent); }
.s-reviewers { display: flex; gap: 3px; flex-wrap: wrap; }
.rv-chip { font-size: 9px; font-weight: 600; padding: 1px 6px; border-radius: 20px; }
.rv-chip.bad { background: var(--red-soft); color: var(--red); }
.rv-chip.good { background: var(--accent-soft); color: var(--accent); }
.rv-chip.expert { border: 1px solid #d97706; }
.empty-samples { padding: 10px 0; }
.load-more { text-align: center; padding: 6px 0; flex-shrink: 0; }

/* ===== 右侧详情 ===== */
.sample-detail { background: var(--bg-card); border: 1px solid var(--border); border-radius: 12px; overflow-y: auto; display: flex; flex-direction: column; box-shadow: var(--shadow-sm); min-height: 0; }
.sample-detail.empty { align-items: center; justify-content: center; }
.detail-head { display: flex; align-items: center; gap: 10px; padding: 12px 18px; border-bottom: 1px solid var(--border); flex-shrink: 0; }
.detail-model { font-size: 13px; font-weight: 700; color: #8b5cf6; }
.detail-prompt { font-size: 11px; color: var(--text-mute); padding: 1px 8px; background: var(--bg-input); border-radius: 20px; }
.detail-ai-tag { font-size: 11px; font-weight: 700; padding: 2px 9px; border-radius: 20px; margin-left: auto; }
.detail-ai-tag.bad { background: var(--red-soft); color: var(--red); } .detail-ai-tag.good { background: var(--accent-soft); color: var(--accent); }
.head-status { font-size: 11px; color: var(--accent); }
.head-status.warn { color: #d97706; }
.detail-diff-tag { font-size: 11px; font-weight: 600; color: var(--red); background: var(--red-soft); padding: 2px 9px; border-radius: 20px; display: inline-flex; align-items: center; gap: 4px; }

.detail-body { padding: 14px 18px 6px; flex: 1; min-height: 0; overflow-y: auto; }
.detail-section { margin-bottom: 16px; }
.detail-label { font-size: 11px; font-weight: 700; color: var(--text-mute); margin-bottom: 5px; letter-spacing: 0.5px; }
.detail-text { font-size: 13px; color: var(--text-sec); line-height: 1.75; white-space: pre-wrap; word-break: break-all; }
.detail-text.answer { background: var(--bg-input); padding: 11px 13px; border-radius: 8px; border-left: 3px solid #8b5cf6; }
.detail-text.context { border-left: 3px solid var(--border-strong); padding-left: 10px; }
.detail-text.reason { font-size: 12px; color: var(--text-mute); }
.verdict-list { display: flex; flex-wrap: wrap; gap: 6px; }
.verdict-row { display: inline-flex; align-items: center; gap: 8px; padding: 5px 10px; background: var(--bg-input); border-radius: 8px; }
.verdict-row.mine { background: rgba(139,92,246,0.08); border: 1px solid rgba(139,92,246,0.3); }
.verdict-row.expert { border-left: 3px solid #d97706; }
.v-reviewer { font-size: 12px; font-weight: 600; }
.v-tag { font-size: 10px; font-weight: 600; padding: 1px 8px; border-radius: 20px; }
.v-tag.bad { background: var(--red-soft); color: var(--red); } .v-tag.good { background: var(--accent-soft); color: var(--accent); }
.v-comment { font-size: 11px; color: var(--text-mute); line-height: 1.5; }
.disagree-hint { font-size: 11px; color: #d97706; margin-top: 8px; display: flex; align-items: center; gap: 4px; }

/* ===== 底部判定区 ===== */
.judge-bar { display: flex; align-items: center; gap: 10px; padding: 12px 18px; border-top: 1px solid var(--border); background: var(--bg-card); flex-shrink: 0; flex-wrap: wrap; }
.judge-title { font-size: 13px; font-weight: 700; color: var(--text-prime); margin-right: 4px; }
.jb { display: inline-flex; flex-direction: column; align-items: flex-start; gap: 1px; border: 1px solid var(--border); border-radius: 9px; padding: 6px 14px 6px 10px; background: var(--bg-input); cursor: pointer; transition: all 0.15s; font-family: inherit; }
.jb:hover { transform: translateY(-1px); box-shadow: var(--shadow-sm); }
.jb .kbd { display: inline-flex; align-items: center; justify-content: center; min-width: 17px; height: 17px; padding: 0 4px; border: 1px solid var(--border-strong); border-radius: 4px; font-size: 11px; font-weight: 700; color: var(--text-sec); background: var(--bg-card); margin-right: 6px; }
.jb-title-row { display: flex; align-items: center; gap: 4px; }
.jb .jb-sub { font-size: 10px; color: var(--text-mute); font-weight: 500; }
.jb.bad { color: var(--red); font-weight: 700; font-size: 13px; }
.jb.bad.active { background: var(--red-soft); border-color: var(--red); box-shadow: 0 0 0 1px var(--red); }
.jb.good { color: var(--accent); font-weight: 700; font-size: 13px; }
.jb.good.active { background: var(--accent-soft); border-color: var(--accent); box-shadow: 0 0 0 1px var(--accent); }
.jb-hint { font-size: 11px; color: var(--text-mute); display: inline-flex; align-items: center; gap: 4px; }
.jb-warn { color: #d97706; margin-left: 8px; font-weight: 600; }

@media (max-width: 1000px) { .review-main { grid-template-columns: 1fr; height: auto; } }
</style>