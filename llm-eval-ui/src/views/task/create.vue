<template>
  <div class="task-create-page">
    <div class="page-header">
      <h2>创建评测任务</h2>
      <div>
        <el-button v-if="hasDraft()" size="small" type="danger" plain @click="handleClearDraft">清空草稿</el-button>
        <el-button @click="$router.back()">返回</el-button>
      </div>
    </div>

    <el-alert v-if="selectedDataset || selectedModelIds.length || taskForm.name"
              title="已自动保存草稿，中途离开后再回来可恢复"
              type="success" :closable="false" show-icon style="margin-bottom: 16px" />

    <div class="form-body" v-loading="loading">
      <!-- 1. 数据集选择 -->
      <div class="form-row">
        <div class="form-row-left">
          <span class="form-num">1</span>
          <div>
            <div class="form-label">数据集</div>
            <div class="form-hint">选择要评测的数据集</div>
          </div>
        </div>
        <div class="form-row-right">
          <el-select v-model="selectedDatasetId" filterable placeholder="请选择数据集"
            @change="onDatasetChange">
            <el-option v-for="ds in datasets" :key="ds.id" :value="ds.id"
              :label="`${ds.name}（v${ds.version}，${ds.totalCount} 条，${ds.hasModelResponse === 1 ? '含回答' : '只有问题'}）`" />
          </el-select>
        </div>
      </div>
      <!-- 回答来源提示（数据集选中后显示） -->
      <div class="info-bar" v-if="selectedDataset">
        <span class="info-bar-label">回答来源：</span>
        <el-tag size="small" :type="answerSource === 'api' ? 'warning' : 'success'">
          {{ answerSource === 'api' ? '现场调用模型生成' : '数据集已有回答' }}
        </el-tag>
        <span class="info-bar-tip">
          {{ answerSource === 'api' ? '评测时将调用被测模型 API 生成回答' : '评测时直接从数据集读取' }}
        </span>
      </div>

      <!-- 2. 被测模型（仅 API 回答时） -->
      <div class="form-row" v-if="answerSource === 'api'">
        <div class="form-row-left">
          <span class="form-num">2</span>
          <div>
            <div class="form-label">被测模型</div>
            <div class="form-hint">你的 AI 产品 API，评测时生成回答</div>
          </div>
        </div>
        <div class="form-row-right">
          <el-select v-model="selectedModelIds" multiple filterable collapse-tags collapse-tags-tooltip
            placeholder="选择被测模型">
            <el-option v-for="m in evaluatedModels" :key="m.id" :label="`${m.name}（${providerLabel(m.provider)}）`" :value="m.id" />
          </el-select>
        </div>
      </div>

      <!-- 3. 裁判模型 -->
      <div class="form-row">
        <div class="form-row-left">
          <span class="form-num">{{ answerSource === 'api' ? 3 : 2 }}</span>
          <div>
            <div class="form-label">裁判模型</div>
            <div class="form-hint">判定 badcase 的 AI，必须选择</div>
          </div>
        </div>
        <div class="form-row-right">
          <el-select v-model="judgeModelId" filterable placeholder="选择裁判模型">
            <el-option v-for="m in judgeModels" :key="m.id" :label="`${m.name}（${providerLabel(m.provider)}）`" :value="m.id" />
          </el-select>
        </div>
      </div>

      <!-- 4. 评估器 -->
      <div class="form-row">
        <div class="form-row-left">
          <span class="form-num">{{ answerSource === 'api' ? 4 : 3 }}</span>
          <div>
            <div class="form-label">评估器</div>
            <div class="form-hint">选择评测维度标准</div>
          </div>
        </div>
        <div class="form-row-right">
          <el-select v-model="selectedPromptId" filterable placeholder="选择评估器"
            @change="onEvaluatorChange">
            <el-option v-for="p in availablePrompts" :key="p.id"
              :label="`${p.name}${p.version && p.version > 1 ? `（v${p.version}）` : '（v1）'}（${p.evaluationMode === 'reference' ? '参考对照' : '质量评判'}）`" :value="p.id" />
          </el-select>
        </div>
      </div>
      <!-- 评估器模式冲突警告 -->
      <el-alert v-if="modeConflictPrompts.length" type="warning" :closable="false" show-icon
        :title="`以下评估器需要参考答案，但当前数据集「${selectedDataset?.name || ''}」没有参考答案，评测结果会失真：${modeConflictPrompts.map(p => '「' + p.name + '」').join('、')}`"
        style="margin-bottom: 16px" />

      <!-- 可用变量面板 -->
      <div v-if="datasetFields.length" class="var-panel">
        <div class="var-panel-head">
          <span class="var-panel-title">
            <el-icon><InfoFilled /></el-icon>
            数据集可用变量
          </span>
          <span class="var-panel-tip">在评估器 prompt 中用 <code>${字段名}</code> 引用，如 <code>${背景}</code>、<code>${case_type}</code></span>
        </div>
        <div class="var-panel-body">
          <div v-for="f in datasetFields" :key="f.fieldName" class="var-chip"
            :class="{ 'var-used': evaluatorUsedVars.has(f.fieldName) }"
            @click="copyVarName(f.fieldName)"
            :title="`点击复制 $${f.fieldName}，角色: ${f.role || 'CUSTOM'}`">
            <span class="var-chip-name">{{ f.fieldName }}</span>
            <span class="var-chip-role" v-if="f.role && f.role !== 'CUSTOM'">{{ f.role }}</span>
            <el-icon class="var-chip-copy" :size="12"><CopyDocument /></el-icon>
          </div>
          <div class="var-chip var-builtin" title="系统内置变量，自动可用">
            <span class="var-chip-name">model_response</span>
            <span class="var-chip-role">SYSTEM</span>
          </div>
        </div>
        <div v-if="evaluatorUsedVars.size > 0" class="var-panel-foot">
          <span class="var-used-label">评估器已引用：</span>
          <code v-for="v in evaluatorUsedVars" :key="v" class="var-used-tag">${{ v }}</code>
        </div>
        <div v-else-if="selectedPromptId" class="var-panel-foot var-panel-foot--warn">
          当前评估器 prompt 未引用任何变量，模型评判时只能看到固定文本
        </div>
      </div>

      <!-- 5. 字段映射（评估器选中后自动出现） -->
      <div v-if="selectedPromptId && hasFieldMapping" class="form-row">
        <div class="form-row-left">
          <span class="form-num">{{ answerSource === 'api' ? 5 : 4 }}</span>
          <div>
            <div class="form-label">字段映射</div>
            <div class="form-hint">将评估器中的占位符映射到数据集字段</div>
          </div>
        </div>
        <div class="form-row-right form-row-right--wide">
          <el-table :data="currentMappingFields" stripe border size="small">
            <el-table-column label="占位符" width="180">
              <template #default="{ row }"><code class="ph-code">{{ row.raw }}</code></template>
            </el-table-column>
            <el-table-column label="→ 数据集字段" width="260">
              <template #default="{ row }">
                <el-select v-model="row.datasetField" size="small" placeholder="选择字段" filterable>
                  <el-option v-if="row.placeholder === 'model_response'" label="模型回复（系统内置）" value="model_response" />
                  <el-option v-for="f in datasetFields" :key="f.fieldName" :label="`${f.displayName}（${f.fieldName}）`" :value="f.fieldName" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="字段含义" min-width="200">
              <template #default="{ row }">
                <el-tag v-if="row.datasetField === 'model_response'" size="small" type="success">系统注入：模型回复</el-tag>
                <template v-else-if="mappingFieldExists(row)">
                  <span class="field-desc">{{ getDatasetFieldDesc(row.datasetField) || row.datasetField }}</span>
                </template>
                <el-tag v-else-if="row.datasetField" size="small" type="danger">字段不存在</el-tag>
                <span v-else class="field-desc-empty">-</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>

      <!-- 6. 任务名称 -->
      <div class="form-row">
        <div class="form-row-left">
          <span class="form-num">{{ answerSource === 'api' ? 6 : 5 }}</span>
          <div>
            <div class="form-label">任务名称</div>
            <div class="form-hint">为本次评测命名</div>
          </div>
        </div>
        <div class="form-row-right">
          <el-input v-model="taskForm.name" placeholder="请输入评测任务名称" style="max-width: 360px" />
        </div>
      </div>

      <!-- 7. 调用模板（仅 API 回答时） -->
      <div class="form-row" v-if="answerSource === 'api'">
        <div class="form-row-left">
          <span class="form-num">7</span>
          <div>
            <div class="form-label">调用模板</div>
            <div class="form-hint">调用被测模型的模板，支持占位符</div>
          </div>
        </div>
        <div class="form-row-right form-row-right--wide">
          <el-input v-model="callPromptTemplate" type="textarea" :rows="2"
            placeholder="请回答以下问题：{question}" style="max-width: 480px" />
          <div v-if="datasetFields.length" class="call-prompt-vars">
            <span class="call-prompt-vars-label">可用变量：</span>
            <code v-for="f in datasetFields" :key="f.fieldName" class="call-prompt-var"
              @click="insertCallVar(f.fieldName)" :title="`点击插入到模板`">${{ f.fieldName }}</code>
          </div>
        </div>
      </div>

      <!-- 创建按钮 -->
      <div class="form-footer">
        <el-button size="large" type="primary" :disabled="!canCreate" @click="handleCreate" :loading="creating">
          创建评测任务
        </el-button>
      </div>
    </div>
  </div>
</template>

<script>
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { datasetApi, modelApi, promptApi, taskApi } from '../../api'
import { ElMessage, ElMessageBox } from 'element-plus'

const STORAGE_KEY = 'task-create-draft'

export default {
  name: 'TaskCreatePage',
  setup() {
    const router = useRouter()
    const loading = ref(false)
    const creating = ref(false)
    const datasets = ref([])
    const selectedDatasetId = ref(null)
    const selectedDataset = ref(null)
    const availableModels = ref([])
    const selectedModelIds = ref([])
    const judgeModelId = ref(null)
    const availablePrompts = ref([])
    const selectedPromptId = ref(null)
    const taskForm = ref({ name: '' })
    const datasetFields = ref([])
    const judgePromptMappings = ref({})
    const answerSource = ref('dataset')
    const callPromptTemplate = ref('请回答以下问题：{question}')

    const evaluatedModels = computed(() => availableModels.value.filter(m => !m.modelType || m.modelType === 'evaluated' || m.modelType === 'both'))
    const judgeModels = computed(() => availableModels.value.filter(m => m.modelType === 'judge' || m.modelType === 'both'))

    const modeConflictPrompts = computed(() => {
      if (!selectedDataset.value) return []
      if (selectedDataset.value.hasReference === 1) return []
      return availablePrompts.value.filter(p =>
        selectedPromptId.value === p.id && p.evaluationMode === 'reference'
      )
    })

    const evaluatorUsedVars = computed(() => {
      const vars = new Set()
      const pid = selectedPromptId.value
      if (!pid) return vars
      const prompt = availablePrompts.value.find(p => p.id === pid)
      if (!prompt) return vars
      extractPlaceholders(prompt.promptTemplate || '').forEach(p => vars.add(p.name))
      try {
        const dc = JSON.parse(prompt.dimensionsConfig || '{}')
        if (dc.context_template) extractPlaceholders(dc.context_template).forEach(p => vars.add(p.name))
      } catch {}
      return vars
    })

    const hasFieldMapping = computed(() => {
      const pid = selectedPromptId.value
      if (!pid) return false
      const m = judgePromptMappings.value[pid]
      return m && m.length > 0
    })

    const currentMappingFields = computed(() => {
      return judgePromptMappings.value[selectedPromptId.value] || []
    })

    const canCreate = computed(() => {
      if (!selectedDataset.value || !judgeModelId.value || !selectedPromptId.value || !taskForm.value.name) return false
      if (answerSource.value === 'api' && selectedModelIds.value.length === 0) return false
      return true
    })

    const providerLabel = (p) => ({ openai: 'OpenAI', deepseek: 'DeepSeek', zhipu: '智谱', tongyi: '通义', other: '其他' }[p] || p || '-')

    const copyVarName = (fieldName) => {
      const text = '${' + fieldName + '}'
      navigator.clipboard.writeText(text).then(() => ElMessage.success(`已复制 ${text}`)).catch(() => ElMessage.info(`变量语法: ${text}`))
    }

    const insertCallVar = (fieldName) => {
      callPromptTemplate.value += '${' + fieldName + '}'
      ElMessage.success(`已插入 $${fieldName}`)
    }

    // ===== 草稿持久化 =====
    const saveDraft = () => {
      const draft = {
        selectedDatasetId: selectedDataset.value?.id || null,
        selectedModelIds: selectedModelIds.value,
        judgeModelId: judgeModelId.value,
        selectedPromptId: selectedPromptId.value,
        taskForm: { ...taskForm.value },
        datasetFields: datasetFields.value,
        judgePromptMappings: judgePromptMappings.value,
        answerSource: answerSource.value,
        callPromptTemplate: callPromptTemplate.value,
        savedAt: Date.now()
      }
      try { localStorage.setItem(STORAGE_KEY, JSON.stringify(draft)) } catch {}
    }

    const loadDraft = () => {
      try {
        const raw = localStorage.getItem(STORAGE_KEY)
        if (!raw) return false
        const draft = JSON.parse(raw)
        if (Date.now() - (draft.savedAt || 0) > 7 * 24 * 3600 * 1000 || draft.callPromptMappings) {
          localStorage.removeItem(STORAGE_KEY); return false
        }
        selectedModelIds.value = draft.selectedModelIds || []
        judgeModelId.value = draft.judgeModelId || null
        selectedPromptId.value = draft.selectedPromptId || null
        taskForm.value = draft.taskForm || { name: '' }
        datasetFields.value = draft.datasetFields || []
        answerSource.value = draft.answerSource || 'dataset'
        callPromptTemplate.value = draft.callPromptTemplate || '请回答以下问题：{question}'
        const fixMappings = (mappings) => (mappings || []).map(m => ({ ...m, raw: m.raw || (m.placeholder ? `{${m.placeholder}}` : '') }))
        judgePromptMappings.value = typeof draft.judgePromptMappings === 'object'
          ? Object.fromEntries(Object.entries(draft.judgePromptMappings).map(([k, v]) => [k, fixMappings(v)]))
          : {}
        return draft.selectedDatasetId
      } catch { return false }
    }

    const clearDraft = () => { try { localStorage.removeItem(STORAGE_KEY) } catch {} }
    const hasDraft = () => { try { return !!localStorage.getItem(STORAGE_KEY) } catch { return false } }

    watch([selectedDatasetId, selectedModelIds, judgeModelId, selectedPromptId, taskForm,
           datasetFields, judgePromptMappings, answerSource, callPromptTemplate], saveDraft, { deep: true })

    const loadDatasets = async () => { const res = await datasetApi.list(1, 100); datasets.value = res.data.records }
    const loadModels = async () => { const res = await modelApi.list(1, 100); availableModels.value = res.data.records.filter(m => m.status === 1) }
    const loadPrompts = async () => { const res = await promptApi.list(); availablePrompts.value = res.data.records.filter(p => p.status === 1) }

    const detectAnswerSource = (ds, fields) => {
      if (ds && ds.hasModelResponse !== undefined && ds.hasModelResponse !== null) {
        return ds.hasModelResponse === 1 ? 'dataset' : 'api'
      }
      if (!fields || !fields.length) return 'api'
      const names = ['model_response', 'response', 'answer', 'output', '模型回答', '回答', 'result']
      return fields.some(f => names.includes((f.fieldName || '').toLowerCase())) ? 'dataset' : 'api'
    }

    const onDatasetChange = async (dsId) => {
      const ds = datasets.value.find(d => d.id === dsId) || null
      selectedDataset.value = ds
      if (ds) {
        answerSource.value = ds.hasModelResponse === 1 ? 'dataset' : 'api'
        try {
          const res = await datasetApi.getSchema(ds.id)
          datasetFields.value = res.data || []
          answerSource.value = detectAnswerSource(ds, datasetFields.value)
        } catch { datasetFields.value = [] }
        judgePromptMappings.value = {}
        if (selectedPromptId.value) buildMappingsForEvaluator(selectedPromptId.value)
      } else {
        datasetFields.value = []
        answerSource.value = 'dataset'
      }
    }

    const getDatasetFieldDesc = (fieldName) => {
      if (!fieldName) return ''
      const f = datasetFields.value.find(f => f.fieldName === fieldName)
      return f ? (f.description || f.displayName) : ''
    }

    const mappingFieldExists = (row) => {
      if (!row.datasetField) return false
      if (row.datasetField === 'model_response') return true
      return datasetFields.value.some(f => f.fieldName === row.datasetField)
    }

    const extractPlaceholders = (template) => {
      const matches = (template.match(/\$\{[a-zA-Z_][a-zA-Z0-9_:]*\}|\{[a-zA-Z_][a-zA-Z0-9_:]*\}/g) || [])
      return matches.map(m => ({ name: m.charAt(0) === '$' ? m.slice(2, -1) : m.slice(1, -1), raw: m }))
    }

    const onEvaluatorChange = async (pid) => {
      if (!pid) { judgePromptMappings.value = {}; return }
      await buildMappingsForEvaluator(pid)
    }

    const buildMappingsForEvaluator = async (pid) => {
      const prompt = availablePrompts.value.find(p => p.id === pid)
      if (!prompt) return
      let placeholders = extractPlaceholders(prompt.promptTemplate || '')
      if (!placeholders.length && prompt.dimensionsConfig) {
        try { const dc = JSON.parse(prompt.dimensionsConfig); if (dc.context_template) placeholders = extractPlaceholders(dc.context_template) } catch {}
      }
      if (!placeholders.length && prompt.dimensionsConfig) {
        try { const res = await promptApi.preview({ dimensionsConfig: prompt.dimensionsConfig }); if (res.data) placeholders = extractPlaceholders(res.data) } catch {}
      }
      judgePromptMappings.value[pid] = placeholders.map(p => ({
        placeholder: p.name, raw: p.raw, datasetField: autoMatchFieldName(p.name)
      }))
    }

    const MAPPING_ALIASES = {
      question: ['question', 'query', 'q', '问题', 'user', '用户问题'],
      reference_answer: ['reference_answer', 'reference', '期望回答', 'golden', 'golden_answer', '参考答案', '标准答案'],
      context: ['context', 'contexts', '上下文', '检索上下文', '检索内容', 'knowledge', '背景'],
      category: ['category', 'category_name', '类别', '分类'],
      model_response: ['model_response', 'model_output', 'response', 'answer', 'output', 'prediction', 'pred', 'generated', '模型回答', '模型输出', '模型回复', '回答', '生成结果', '回复', 'result']
    }
    const normalizeKey = (k) => String(k || '').toLowerCase().replace(/[\s_-]+/g, '')

    const autoMatchFieldName = (placeholder) => {
      const norm = normalizeKey(placeholder)
      const byName = datasetFields.value.find(f => normalizeKey(f.fieldName) === norm)
      if (byName) return byName.fieldName
      const roleMap = { question: 'QUESTION', reference_answer: 'REFERENCE', context: 'CONTEXT', category: 'CATEGORY' }
      const role = roleMap[norm]
      if (role) { const byRole = datasetFields.value.find(f => (f.role || '').toUpperCase() === role); if (byRole) return byRole.fieldName }
      for (const [group, aliases] of Object.entries(MAPPING_ALIASES)) {
        if (!(aliases.some(a => normalizeKey(a) === norm) || normalizeKey(group) === norm)) continue
        const hit = datasetFields.value.find(f => {
          const fNorm = normalizeKey(f.fieldName); const dNorm = normalizeKey(f.displayName)
          return normalizeKey(group) === fNorm || fNorm === norm || aliases.some(a => normalizeKey(a) === fNorm || normalizeKey(a) === dNorm)
        })
        if (hit) return hit.fieldName
      }
      return placeholder
    }

    const buildFieldMapping = () => {
      const mapping = {}
      const pid = selectedPromptId.value
      if (!pid) return JSON.stringify(mapping)
      for (const m of (judgePromptMappings.value[pid] || [])) {
        if (m.placeholder && m.datasetField && !mapping[m.placeholder]) mapping[m.placeholder] = m.datasetField
      }
      return JSON.stringify(mapping)
    }

    const handleCreate = async () => {
      if (!selectedDataset.value) { ElMessage.warning('请选择数据集'); return }
      if (!judgeModelId.value) { ElMessage.warning('请选择裁判模型'); return }
      if (answerSource.value === 'api' && selectedModelIds.value.length === 0) { ElMessage.warning('现场调模型生成需要至少选择一个被测模型'); return }
      if (!selectedPromptId.value) { ElMessage.warning('请选择评估器'); return }
      const mappings = judgePromptMappings.value[selectedPromptId.value] || []
      for (const m of mappings) {
        if (!m.datasetField) { ElMessage.warning('有占位符未映射数据集字段，请检查'); return }
        if (!mappingFieldExists(m)) { ElMessage.warning('存在无效的字段映射，请修正'); return }
      }
      creating.value = true
      try {
        await taskApi.create({
          name: taskForm.value.name,
          datasetId: selectedDataset.value.id,
          judgeModelId: judgeModelId.value || undefined,
          answerSource: answerSource.value,
          promptTemplate: answerSource.value === 'api' ? (callPromptTemplate.value || '') : '',
          fieldMapping: buildFieldMapping(),
          modelConfigIds: selectedModelIds.value,
          promptIds: selectedPromptId.value ? [selectedPromptId.value] : []
        })
        ElMessage.success('评测任务创建成功')
        clearDraft()
        router.push('/task')
      } catch (e) { ElMessage.error(e.response?.data?.message || e.message || '创建失败') }
      finally { creating.value = false }
    }

    const handleClearDraft = async () => {
      await ElMessageBox.confirm('确定清空所有已填内容？', '提示', { type: 'warning' })
      clearDraft()
      selectedDatasetId.value = null; selectedDataset.value = null
      selectedModelIds.value = []; judgeModelId.value = null; selectedPromptId.value = null
      taskForm.value = { name: '' }; datasetFields.value = []; judgePromptMappings.value = {}
      answerSource.value = 'dataset'; callPromptTemplate.value = '请回答以下问题：{question}'
      ElMessage.success('已清空')
    }

    onMounted(async () => {
      loading.value = true
      await Promise.all([loadDatasets(), loadModels(), loadPrompts()])
      const datasetIdToRestore = loadDraft()
      if (datasetIdToRestore) {
        const ds = datasets.value.find(d => d.id === datasetIdToRestore)
        if (ds) {
          selectedDatasetId.value = ds.id; selectedDataset.value = ds
          answerSource.value = ds.hasModelResponse === 1 ? 'dataset' : 'api'
          if (!datasetFields.value.length) {
            try { const res = await datasetApi.getSchema(ds.id); datasetFields.value = res.data || []; answerSource.value = detectAnswerSource(ds, datasetFields.value) } catch {}
          }
          if (selectedPromptId.value && !Object.keys(judgePromptMappings.value).length) await buildMappingsForEvaluator(selectedPromptId.value)
        } else { selectedDatasetId.value = null; selectedDataset.value = null; clearDraft() }
      }
      loading.value = false
    })

    return {
      loading, creating, datasets, selectedDatasetId, selectedDataset, onDatasetChange,
      availableModels, selectedModelIds, judgeModelId, availablePrompts, selectedPromptId,
      taskForm, datasetFields, judgePromptMappings,
      onEvaluatorChange, currentMappingFields, hasFieldMapping,
      getDatasetFieldDesc, mappingFieldExists,
      handleCreate, handleClearDraft, hasDraft,
      answerSource, callPromptTemplate, canCreate,
      evaluatedModels, judgeModels, modeConflictPrompts, providerLabel,
      evaluatorUsedVars, copyVarName, insertCallVar
    }
  }
}
</script>

<style scoped>
.task-create-page {
  background: var(--bg-card);
  border-radius: 14px;
  padding: 24px;
  border: 1px solid var(--border);
  box-shadow: var(--shadow-card);
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; flex-shrink: 0; }
.page-header h2 { font-size: 18px; font-weight: 700; color: var(--text-prime); }

.form-body {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
  padding-right: 4px;
}

.form-section {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  padding: 16px 0;
  border-bottom: 1px solid var(--border);
}
.form-section:last-of-type { border-bottom: none; }

.section-label {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  min-width: 130px;
  flex-shrink: 0;
  padding-top: 4px;
}
.section-num {
  width: 24px; height: 24px; border-radius: 7px;
  background: var(--accent); color: var(--accent-text);
  font-size: 12px; font-weight: 700;
  display: inline-flex; align-items: center; justify-content: center;
  flex-shrink: 0; margin-top: 1px;
}
.section-title { font-size: 14px; font-weight: 600; color: var(--text-prime); }
.section-desc { font-size: 11px; color: var(--text-mute); margin-top: 1px; }

.section-content { flex: 1; min-width: 0; display: flex; justify-content: flex-end; flex-wrap: wrap; align-items: flex-start; }
.section-content > .el-select { width: 320px; }
.section-content > .el-alert { width: 100%; }
.section-content > .el-table { width: 100%; flex-shrink: 0; }
.section-content > .el-input { width: 100%; max-width: 400px; }
/* 任务名称和调用模板保持左对齐 */
.form-section.section-left .section-content { justify-content: flex-start; }

.answer-source-bar {
  display: flex; align-items: center; gap: 10px; width: 100%;
  padding: 8px 12px; background: var(--bg-input); border: 1px solid var(--border);
  border-radius: 8px; margin-top: 10px;
}
.as-label { font-size: 12px; font-weight: 600; color: var(--text-prime); flex-shrink: 0; }
.as-tip { font-size: 12px; color: var(--text-mute); }

.field-desc { color: var(--text-sec); font-size: 12px; }
.field-desc-empty { color: var(--text-mute); }
.ph-code { background: rgba(245,158,11,0.1); padding: 2px 8px; border-radius: 3px; color: #f59e0b; font-family: Menlo, monospace; font-size: 13px; }

.form-footer {
  display: flex; justify-content: center; padding: 24px 0 8px; flex-shrink: 0;
}

/* 可用变量面板 */
.var-panel {
  margin: 12px 0; border: 1px solid var(--border); border-radius: 10px;
  background: var(--bg-input); overflow: hidden;
}
.var-panel-head {
  display: flex; align-items: center; gap: 10px; padding: 10px 14px;
  border-bottom: 1px solid var(--border);
  background: linear-gradient(135deg, rgba(24,144,255,0.06), rgba(102,107,210,0.04));
}
.var-panel-title {
  font-size: 13px; font-weight: 600; color: var(--accent);
  display: flex; align-items: center; gap: 4px; white-space: nowrap;
}
.var-panel-tip { font-size: 12px; color: var(--text-mute); line-height: 1.5; }
.var-panel-tip code {
  background: rgba(24,144,255,0.1); color: var(--accent);
  padding: 1px 5px; border-radius: 3px; font-size: 11px; font-family: Menlo, monospace;
}
.var-panel-body { display: flex; flex-wrap: wrap; gap: 8px; padding: 12px 14px; }
.var-chip {
  display: inline-flex; align-items: center; gap: 4px; padding: 5px 10px;
  border: 1px solid var(--border); border-radius: 6px; background: var(--bg-card);
  cursor: pointer; transition: all 0.15s; font-size: 12px;
}
.var-chip:hover { border-color: var(--accent); box-shadow: 0 0 0 2px var(--accent-soft); }
.var-chip:active { transform: scale(0.96); }
.var-chip.var-used { border-color: var(--accent); background: rgba(24,144,255,0.08); }
.var-chip.var-builtin { border-style: dashed; opacity: 0.75; cursor: default; }
.var-chip-name { font-family: Menlo, monospace; font-weight: 600; color: var(--text-prime); }
.var-chip-role {
  font-size: 10px; padding: 1px 4px; border-radius: 3px;
  background: rgba(24,144,255,0.1); color: var(--accent); font-weight: 600; letter-spacing: 0.3px;
}
.var-chip-copy { color: var(--text-mute); opacity: 0; transition: opacity 0.15s; }
.var-chip:hover .var-chip-copy { opacity: 1; }

.var-panel-foot {
  padding: 8px 14px; border-top: 1px solid var(--border); font-size: 12px;
  color: var(--text-sec); display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
}
.var-panel-foot--warn { color: #e6a23c; }
.var-used-label { color: var(--text-mute); white-space: nowrap; }
.var-used-tag {
  background: rgba(24,144,255,0.1); color: var(--accent);
  padding: 1px 6px; border-radius: 3px; font-size: 11px; font-family: Menlo, monospace;
}

.call-prompt-vars { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; align-items: center; }
.call-prompt-vars-label { font-size: 12px; color: var(--text-mute); white-space: nowrap; }
.call-prompt-var {
  font-size: 11px; font-family: Menlo, monospace; padding: 2px 7px; border-radius: 4px;
  background: rgba(24,144,255,0.08); color: var(--accent); cursor: pointer;
  border: 1px solid transparent; transition: all 0.15s;
}
.call-prompt-var:hover { background: rgba(24,144,255,0.18); border-color: var(--accent); }
</style>
