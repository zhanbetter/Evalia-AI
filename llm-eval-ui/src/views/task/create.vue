<template>
  <div class="task-create-page">
    <div class="page-header">
      <h2>创建评测任务</h2>
      <div>
        <el-button v-if="hasDraft()" size="small" type="danger" plain @click="handleClearDraft">清空草稿</el-button>
        <el-button @click="$router.back()">返回</el-button>
      </div>
    </div>

    <el-alert v-if="step > 0 || selectedDataset || selectedModelIds.length || taskForm.name"
              title="已自动保存草稿，中途离开后再回来可恢复"
              type="success" :closable="false" show-icon style="margin-bottom: 16px" />

    <!-- 自定义步骤条 -->
    <div class="wizard-steps">
      <div v-for="(s, i) in wizardSteps" :key="i"
        class="wizard-step"
        :class="{ active: step === i, done: step > i }"
        @click="step = i">
        <span class="ws-dot">
          <el-icon v-if="step > i" :size="11"><Check /></el-icon>
          <span v-else>{{ i + 1 }}</span>
        </span>
        <span class="ws-label">{{ s }}</span>
      </div>
    </div>

    <!-- Step 1: 选择数据集 -->
    <div v-show="step === 0" class="step-content">
      <div class="ds-select-grid">
        <div v-for="ds in datasets" :key="ds.id" class="ds-select-card"
          :class="{ selected: selectedDataset && selectedDataset.id === ds.id }"
          @click="selectDataset(ds)">
          <div class="ds-card-top">
            <span class="ds-type">{{ ds.fileType }}</span>
            <el-tag size="small" type="success">v{{ ds.version }}</el-tag>
            <el-tag size="small" :type="ds.hasModelResponse === 1 ? 'success' : 'info'" style="margin-left:auto">
              {{ ds.hasModelResponse === 1 ? '含回答' : '只有问题' }}
            </el-tag>
          </div>
          <h3 class="ds-name">{{ ds.name }}</h3>
          <p class="ds-desc">{{ ds.description || '暂无描述' }}</p>
          <div class="ds-meta">
            <span><el-icon><Collection /></el-icon> {{ ds.totalCount }} 条</span>
            <span class="ds-check" v-if="selectedDataset && selectedDataset.id === ds.id">
              <el-icon><CircleCheck /></el-icon>
            </span>
          </div>
        </div>
        <div v-if="!datasets.length" class="ds-empty">
          <el-empty description="暂无数据集，请先上传数据集" />
        </div>
      </div>

      <!-- 回答来源：根据数据集字段自动判断 -->
      <div class="answer-source-bar" v-if="selectedDataset">
        <span class="as-label">回答来源：</span>
        <el-tag size="small" :type="answerSource === 'api' ? 'warning' : 'success'">
          {{ answerSource === 'api' ? '现场调用模型生成' : '数据集已有回答' }}
        </el-tag>
        <span class="as-tip">
          {{ answerSource === 'api'
            ? '当前数据集没有回答字段，评测时将调用被测模型 API 生成回答'
            : '当前数据集包含回答字段，评测时直接从数据集读取' }}
        </span>
      </div>

      <div class="step-footer">
        <span v-if="selectedDataset" class="selected-info">已选择: {{ selectedDataset.name }} v{{ selectedDataset.version }} ({{ selectedDataset.totalCount }} 条)</span>
        <el-button type="primary" :disabled="!selectedDataset" @click="loadSchemaAndNext">下一步</el-button>
      </div>
    </div>

    <!-- Step 2: 选择模型 与 评估器 -->
    <div v-show="step === 1" class="step-content">
      <div class="config-grid">
        <!-- 被测模型（仅"现场调用模型生成"时需要） -->
        <div class="config-panel" v-if="answerSource === 'api'">
          <div class="config-head">
            <span class="config-num">1</span>
            <div class="config-title-box">
              <div class="config-title">被测模型</div>
              <div class="config-desc">你的 AI 产品 API，评测时生成回答</div>
            </div>
          </div>
          <div class="config-body">
            <el-select v-model="selectedModelIds" multiple filterable collapse-tags collapse-tags-tooltip
              placeholder="选择被测模型" style="width: 100%">
              <el-option v-for="m in evaluatedModels" :key="m.id" :label="`${m.name}（${providerLabel(m.provider)}）`" :value="m.id" />
            </el-select>
          </div>
        </div>

        <!-- 裁判模型 -->
        <div class="config-panel">
          <div class="config-head">
            <span class="config-num">{{ answerSource === 'api' ? 2 : 1 }}</span>
            <div class="config-title-box">
              <div class="config-title">裁判模型</div>
              <div class="config-desc">判定 badcase 的 AI，必须选择</div>
            </div>
          </div>
          <div class="config-body">
            <el-select v-model="judgeModelId" filterable placeholder="选择裁判模型" style="width: 100%">
              <el-option v-for="m in judgeModels" :key="m.id" :label="`${m.name}（${providerLabel(m.provider)}）`" :value="m.id" />
            </el-select>
          </div>
        </div>

        <!-- 评估器 -->
        <div class="config-panel">
          <div class="config-head">
            <span class="config-num">{{ answerSource === 'api' ? 3 : 2 }}</span>
            <div class="config-title-box">
              <div class="config-title">评估器</div>
              <div class="config-desc">选择评测维度标准</div>
            </div>
          </div>
          <div class="config-body">
            <el-select v-model="selectedPromptId" filterable placeholder="选择评估器" style="width: 100%">
              <el-option v-for="p in availablePrompts" :key="p.id"
                :label="`${p.name}（${p.evaluationMode === 'reference' ? '参考对照' : '质量评判'}）`" :value="p.id" />
            </el-select>
          </div>
        </div>
      </div>
      <!-- 模式匹配警告 -->
      <el-alert v-if="modeConflictPrompts.length" type="warning" :closable="false" show-icon
        :title="`以下评估器需要参考答案，但当前数据集「${selectedDataset?.name || ''}」没有参考答案，评测结果会失真：${modeConflictPrompts.map(p => '「' + p.name + '」').join('、')}`"
        style="margin-top: 12px" />
      <div class="step-footer">
        <el-button @click="step = 0">上一步</el-button>
        <span class="selected-info">已选 {{ selectedModelIds.length }} 被测 / {{ judgeModelId ? getModelName(judgeModelId) : '未选裁判' }} / {{ selectedPromptIds.length }} 评估器</span>
        <el-button type="primary" :disabled="!judgeModelId || selectedPromptIds.length === 0 || modeConflictPrompts.length > 0" @click="goToMapping">下一步：字段映射</el-button>
      </div>
    </div>

    <!-- Step 3: 字段映射 -->
    <div v-show="step === 2" class="step-content">
      <el-alert title="将评估器中的占位符映射到数据集字段。占位符格式: {question} 或 ${case_description}" type="info" :closable="false" show-icon style="margin-bottom: 16px" />

      <el-tabs v-if="selectedPromptIds.length > 1">
        <el-tab-pane v-for="pid in selectedPromptIds" :key="pid" :label="getPromptName(pid)">
          <el-table :data="getJudgeMappings(pid)" stripe border size="small">
            <el-table-column label="Prompt占位符" width="180">
              <template #default="{ row }"><code class="ph-code">{{ row.raw }}</code></template>
            </el-table-column>
            <el-table-column label="→ 数据集字段" width="240">
              <template #default="{ row }">
                <el-select v-model="row.datasetField" size="small" placeholder="选择字段" filterable>
                  <el-option v-if="row.placeholder === 'model_response'" label="模型回复(系统内置)" value="model_response" />
                  <el-option v-for="f in datasetFields" :key="f.fieldName" :label="f.displayName + ' (' + f.fieldName + ')'" :value="f.fieldName" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="字段含义" min-width="200">
              <template #default="{ row }">
                <span v-if="getDatasetFieldDesc(row.datasetField)" class="field-desc">{{ getDatasetFieldDesc(row.datasetField) }}</span>
                <span v-else class="field-desc-empty">-</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
      <el-table v-else v-for="pid in selectedPromptIds" :key="pid" :data="getJudgeMappings(pid)" stripe border size="small">
        <template #header>
          <div style="padding: 8px 0; font-weight: 600">{{ getPromptName(pid) }} 的占位符映射</div>
        </template>
        <el-table-column label="Prompt占位符" width="180">
          <template #default="{ row }"><code class="ph-code">{{ row.raw }}</code></template>
        </el-table-column>
        <el-table-column label="→ 数据集字段" width="240">
          <template #default="{ row }">
            <el-select v-model="row.datasetField" size="small" placeholder="选择字段" filterable>
              <el-option v-if="row.placeholder === 'model_response'" label="模型回复(系统内置)" value="model_response" />
              <el-option v-for="f in datasetFields" :key="f.fieldName" :label="f.displayName + ' (' + f.fieldName + ')'" :value="f.fieldName" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="字段含义" min-width="200">
          <template #default="{ row }">
            <span v-if="getDatasetFieldDesc(row.datasetField)" class="field-desc">{{ getDatasetFieldDesc(row.datasetField) }}</span>
            <span v-else class="field-desc-empty">-</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="step-footer">
        <el-button @click="step = 1">上一步</el-button>
        <el-button type="primary" @click="step = 3">下一步：确认</el-button>
      </div>
    </div>

    <!-- Step 4: 确认 -->
    <div v-show="step === 3" class="step-content">
      <el-form label-width="100px" style="margin-bottom: 20px">
        <el-form-item label="任务名称" required>
          <el-input v-model="taskForm.name" placeholder="请输入评测任务名称" style="max-width: 400px" />
        </el-form-item>
        <el-form-item v-if="answerSource === 'api'" label="调用模板" required>
          <el-input v-model="callPromptTemplate" type="textarea" :rows="2"
            placeholder="请回答以下问题：{question}" style="max-width: 500px" />
          <div class="dim-tip" style="margin-top: 6px">调用被测模型的模板，支持 {question} 等占位符</div>
        </el-form-item>
      </el-form>

      <div class="confirm-panel">
        <div class="confirm-item">
          <span class="confirm-label">数据集</span>
          <span class="confirm-value">{{ selectedDataset?.name }} <el-tag size="small">v{{ selectedDataset?.version }}</el-tag></span>
        </div>
        <div class="confirm-item">
          <span class="confirm-label">评测模型</span>
          <span class="confirm-value">
            <el-tag v-for="id in selectedModelIds" :key="id" style="margin-right: 8px" type="success">{{ getModelName(id) }}</el-tag>
          </span>
        </div>
        <div class="confirm-item">
          <span class="confirm-label">裁判模型</span>
          <span class="confirm-value">
            <el-tag type="success">{{ judgeModelId ? getModelName(judgeModelId) : '自动选择' }}</el-tag>
          </span>
        </div>
        <div class="confirm-item">
          <span class="confirm-label">评估器</span>
          <span class="confirm-value">
            <el-tag v-for="id in selectedPromptIds" :key="id" style="margin-right: 8px" type="warning">{{ getPromptName(id) }}</el-tag>
          </span>
        </div>
        <div class="confirm-item">
          <span class="confirm-label">字段映射</span>
          <span class="confirm-value">
            <div v-for="pid in selectedPromptIds" :key="pid" style="margin-bottom: 6px">
              <div style="font-weight: 600; margin-bottom: 3px">{{ getPromptName(pid) }}</div>
              <div v-for="m in getJudgeMappings(pid)" :key="m.placeholder" class="mapping-line">
                <code>{{ m.raw }}</code> → <el-tag size="small">{{ m.datasetField || '未映射' }}</el-tag>
              </div>
            </div>
          </span>
        </div>
      </div>
      <div class="step-footer">
        <el-button @click="step = 2">上一步</el-button>
        <el-button type="primary" :disabled="!taskForm.name" @click="handleCreate" :loading="creating">创建任务</el-button>
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
    const step = ref(0)
    const creating = ref(false)
    const datasets = ref([])
    const selectedDataset = ref(null)
    const availableModels = ref([])
    const selectedModelIds = ref([])
    const judgeModelId = ref(null)
    const availablePrompts = ref([])
    const selectedPromptIds = ref([])
    const selectedPromptId = ref(null) // 单选评估器
    const taskForm = ref({ name: '' })
    const datasetFields = ref([])
    const judgePromptMappings = ref({})
    const answerSource = ref('dataset')
    const callPromptTemplate = ref('请回答以下问题：{question}')
    const wizardSteps = ['选择数据集', '选择模型与评估器', '字段映射', '确认创建']

    // 被测模型 / 裁判模型 按类型过滤
    const evaluatedModels = computed(() => availableModels.value.filter(m => !m.modelType || m.modelType === 'evaluated' || m.modelType === 'both'))
    const judgeModels = computed(() => availableModels.value.filter(m => m.modelType === 'judge' || m.modelType === 'both'))

    // 模式匹配校验：选中的评估器是"参考对照"模式，但数据集没有参考答案 → 冲突
    const modeConflictPrompts = computed(() => {
      if (!selectedDataset.value) return []
      const hasRef = selectedDataset.value.hasReference === 1
      if (hasRef) return []
      return availablePrompts.value.filter(p =>
        selectedPromptIds.value.includes(p.id) && p.evaluationMode === 'reference'
      )
    })

    const selectJudge = (row) => {
      if (row) judgeModelId.value = row.id
    }

    const providerLabel = (p) => ({ openai: 'OpenAI', deepseek: 'DeepSeek', zhipu: '智谱', tongyi: '通义', other: '其他' }[p] || p || '-')

    // ===== 草稿持久化 =====
    const saveDraft = () => {
      const draft = {
        step: step.value,
        selectedDatasetId: selectedDataset.value?.id || null,
        selectedModelIds: selectedModelIds.value,
        judgeModelId: judgeModelId.value,
        selectedPromptIds: selectedPromptIds.value,
        taskForm: { ...taskForm.value },
        datasetFields: datasetFields.value,
        judgePromptMappings: judgePromptMappings.value,
        answerSource: answerSource.value,
        callPromptTemplate: callPromptTemplate.value,
        savedAt: Date.now()
      }
      try { localStorage.setItem(STORAGE_KEY, JSON.stringify(draft)) } catch (e) {}
    }

    const loadDraft = () => {
      try {
        const raw = localStorage.getItem(STORAGE_KEY)
        if (!raw) return false
        const draft = JSON.parse(raw)
        if (Date.now() - (draft.savedAt || 0) > 7 * 24 * 3600 * 1000 || draft.callPromptMappings) {
          localStorage.removeItem(STORAGE_KEY)
          return false
        }
        step.value = draft.step || 0
        selectedModelIds.value = draft.selectedModelIds || []
        judgeModelId.value = draft.judgeModelId || null
        selectedPromptIds.value = draft.selectedPromptIds || []
        selectedPromptId.value = (draft.selectedPromptIds || [])[0] || null
        taskForm.value = draft.taskForm || { name: '' }
        datasetFields.value = draft.datasetFields || []
        answerSource.value = draft.answerSource || 'dataset'
        callPromptTemplate.value = draft.callPromptTemplate || '请回答以下问题：{question}'
        const fixMappings = (mappings) => {
          if (!mappings) return []
          return mappings.map(m => ({
            ...m,
            raw: m.raw || (m.placeholder ? `{${m.placeholder}}` : '')
          }))
        }
        judgePromptMappings.value = typeof draft.judgePromptMappings === 'object'
          ? Object.fromEntries(Object.entries(draft.judgePromptMappings).map(([k, v]) => [k, fixMappings(v)]))
          : {}
        return draft.selectedDatasetId
      } catch (e) { return false }
    }

    const clearDraft = () => {
      try { localStorage.removeItem(STORAGE_KEY) } catch (e) {}
    }

    const hasDraft = () => {
      try { return !!localStorage.getItem(STORAGE_KEY) } catch (e) { return false }
    }

    watch([step, selectedDataset, selectedModelIds, judgeModelId, selectedPromptIds, taskForm,
           datasetFields, judgePromptMappings, answerSource, callPromptTemplate], saveDraft, { deep: true })

    const loadDatasets = async () => { const res = await datasetApi.list(1, 100); datasets.value = res.data.records }
    const loadModels = async () => { const res = await modelApi.list(1, 100); availableModels.value = res.data.records.filter(m => m.status === 1) }
    const loadPrompts = async () => { const res = await promptApi.list(); availablePrompts.value = res.data.records.filter(p => p.status === 1) }
    const selectDataset = (row) => {
      selectedDataset.value = row
      // 选中立即判断回答来源（hasModelResponse 在数据集记录里，无需等 schema）
      if (row && row.hasModelResponse !== undefined && row.hasModelResponse !== null) {
        answerSource.value = row.hasModelResponse === 1 ? 'dataset' : 'api'
      }
    }

    // 单选评估器 → 同步到数组（后续逻辑仍用数组）
    watch(selectedPromptId, (v) => {
      selectedPromptIds.value = v ? [v] : []
    })
    const getModelName = (id) => { const m = availableModels.value.find(m => m.id === id); return m ? m.name : id }
    const getPromptName = (id) => { const p = availablePrompts.value.find(p => p.id === id); return p ? p.name : id }

    const getDatasetFieldDesc = (fieldName) => {
      if (!fieldName) return ''
      const f = datasetFields.value.find(f => f.fieldName === fieldName)
      return f ? (f.description || f.displayName) : ''
    }

    const loadSchemaAndNext = async () => {
      if (!selectedDataset.value) return
      try {
        const res = await datasetApi.getSchema(selectedDataset.value.id)
        datasetFields.value = res.data || []
        // 自动判断回答来源：优先用数据集声明的 hasModelResponse，否则靠字段名推断
        answerSource.value = detectAnswerSource(selectedDataset.value, datasetFields.value)
        step.value = 1
      } catch { datasetFields.value = []; step.value = 1 }
    }

    // 根据数据集判断回答来源
    // hasModelResponse=1 → 数据集已有回答；否则靠字段名推断
    const detectAnswerSource = (ds, fields) => {
      // 数据集明确声明了 hasModelResponse
      if (ds && ds.hasModelResponse !== undefined && ds.hasModelResponse !== null) {
        return ds.hasModelResponse === 1 ? 'dataset' : 'api'
      }
      // 旧数据兜底：靠字段名推断
      if (!fields || !fields.length) return 'api'
      const responseFieldNames = ['model_response', 'response', 'answer', 'output', '模型回答', '回答', 'result']
      const hasResponse = fields.some(f => {
        const name = (f.fieldName || '').toLowerCase()
        const role = (f.role || '').toLowerCase()
        return responseFieldNames.includes(name) || responseFieldNames.includes(role)
      })
      return hasResponse ? 'dataset' : 'api'
    }

    // 从模板提取占位符，支持 ${xxx} 和 {xxx} 两种格式
    // 返回 [{ name: 'xxx', raw: '${xxx}' }] 保留原始写法
    const extractPlaceholders = (template) => {
      const regex = /\$\{[a-zA-Z_][a-zA-Z0-9_:]*\}|\{[a-zA-Z_][a-zA-Z0-9_:]*\}/g
      const matches = template.match(regex) || []
      return matches.map(m => {
        const name = m.charAt(0) === '$' ? m.slice(2, -1) : m.slice(1, -1)
        return { name, raw: m }
      })
    }

    const goToMapping = async () => {
      if (!judgeModelId.value) {
        ElMessage.warning('请先选择裁判模型')
        return
      }
      const newJudgeMappings = {}
      for (const pid of selectedPromptIds.value) {
        const prompt = availablePrompts.value.find(p => p.id === pid)
        if (!prompt) continue
        const placeholders = extractPlaceholders(prompt.promptTemplate)
        newJudgeMappings[pid] = placeholders.map(p => {
          let autoField = p.name
          if (p.name === 'model_response') autoField = 'model_response'
          else {
            const matched = datasetFields.value.find(f => f.fieldName === p.name || f.role === p.name.toUpperCase())
            if (matched) autoField = matched.fieldName
          }
          return { placeholder: p.name, raw: p.raw, datasetField: autoField }
        })
      }
      judgePromptMappings.value = newJudgeMappings
      step.value = 2
    }

    const getJudgeMappings = (pid) => judgePromptMappings.value[pid] || []

    const buildFieldMapping = () => {
      const mapping = {}
      for (const pid of selectedPromptIds.value) {
        const mappings = judgePromptMappings.value[pid] || []
        for (const m of mappings) {
          if (m.placeholder && m.datasetField && !mapping[m.placeholder]) {
            mapping[m.placeholder] = m.datasetField
          }
        }
      }
      return JSON.stringify(mapping)
    }

    const handleCreate = async () => {
      if (!selectedDataset.value) {
        ElMessage.warning('请先选择数据集')
        step.value = 0
        return
      }
      if (!judgeModelId.value) {
        ElMessage.warning('请选择裁判模型')
        step.value = 1
        return
      }
      // 只有"现场调模型生成"时才需要被测模型
      if (answerSource.value === 'api' && selectedModelIds.value.length === 0) {
        ElMessage.warning('现场调模型生成需要至少选择一个被测模型')
        step.value = 1
        return
      }
      if (selectedPromptIds.value.length === 0) {
        ElMessage.warning('请至少选择一个评估器')
        step.value = 1
        return
      }
      let hasUnmapped = false
      for (const pid of selectedPromptIds.value) {
        const mappings = judgePromptMappings.value[pid] || []
        if (mappings.some(m => !m.datasetField)) { hasUnmapped = true; break }
      }
      if (hasUnmapped) {
        ElMessage.warning('有占位符未映射数据集字段，请检查')
        return
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
          promptIds: selectedPromptIds.value
        })
        ElMessage.success('评测任务创建成功')
        clearDraft()
        router.push('/task')
      } catch (e) {
        ElMessage.error(e.response?.data?.message || e.message || '创建失败')
      } finally {
        creating.value = false
      }
    }

    const handleClearDraft = async () => {
      await ElMessageBox.confirm('确定清空所有已填内容？', '提示', { type: 'warning' })
      clearDraft()
      step.value = 0
      selectedDataset.value = null
      selectedModelIds.value = []
      judgeModelId.value = null
      selectedPromptIds.value = []
      selectedPromptId.value = null
      taskForm.value = { name: '' }
      datasetFields.value = []
      judgePromptMappings.value = {}
      answerSource.value = 'dataset'
      callPromptTemplate.value = '请回答以下问题：{question}'
      ElMessage.success('已清空')
    }

    onMounted(async () => {
      await Promise.all([loadDatasets(), loadModels(), loadPrompts()])
      const datasetIdToRestore = loadDraft()
      if (datasetIdToRestore) {
        const ds = datasets.value.find(d => d.id === datasetIdToRestore)
        if (ds) {
          selectedDataset.value = ds
        } else {
          // 恢复的数据集不存在（可能被删了），回退到第一步
          selectedDataset.value = null
          step.value = 0
          clearDraft()
        }
      } else {
        // 没有可恢复的数据集，强制回到第一步（避免草稿残留导致跳转到中间步骤）
        if (selectedDataset.value == null && step.value > 0) {
          step.value = 0
        }
      }
    })

    return {
      step, creating, datasets, selectedDataset, selectDataset, loadSchemaAndNext,
      availableModels, selectedModelIds, judgeModelId, availablePrompts, selectedPromptIds, selectedPromptId,
      taskForm, datasetFields, judgePromptMappings,
      goToMapping, getJudgeMappings, getDatasetFieldDesc,
      getModelName, getPromptName, handleCreate, handleClearDraft, hasDraft,
      answerSource, callPromptTemplate, wizardSteps,
      evaluatedModels, judgeModels, selectJudge, modeConflictPrompts, providerLabel
    }
  }
}
</script>

<style scoped>
.wizard-steps {
  display: flex;
  gap: 4px;
  margin-bottom: 20px;
  padding: 10px 14px;
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: 10px;
  flex-shrink: 0;
}
.wizard-step {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s;
  border: 1px solid transparent;
}
.wizard-step:hover { background: var(--bg-card); }
.wizard-step.active {
  background: var(--accent-soft);
  border-color: var(--accent);
}
.ws-dot {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--bg-card);
  border: 1.5px solid var(--border);
  color: var(--text-mute);
  font-size: 11px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.wizard-step.active .ws-dot {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-text);
}
.wizard-step.done .ws-dot {
  background: var(--accent-soft);
  border-color: var(--accent);
  color: var(--accent);
}
.ws-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-sec);
  white-space: nowrap;
}
.wizard-step.active .ws-label,
.wizard-step.done .ws-label { color: var(--accent); }
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
.step-content { min-height: 300px; flex: 1; display: flex; flex-direction: column; min-height: 0; }

/* ===== 步骤2：下拉选择布局 ===== */
.config-grid {
  display: flex;
  flex-direction: column;
  gap: 12px;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding-right: 4px;
}
.config-panel {
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--bg-card);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px;
  flex-shrink: 0;
}
.config-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}
.config-num {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  background: var(--accent);
  color: var(--accent-text);
  font-size: 13px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.config-title-box { min-width: 120px; }
.config-title { font-size: 14px; font-weight: 600; color: var(--text-prime); }
.config-desc { font-size: 11px; color: var(--text-mute); margin-top: 1px; }
.config-body {
  width: 320px;
  flex-shrink: 0;
  margin-left: auto;
}
.config-count {
  margin-left: auto;
  font-size: 12px;
  color: var(--accent);
  font-weight: 600;
  background: var(--accent-soft);
  padding: 2px 10px;
  border-radius: 20px;
  flex-shrink: 0;
}

.panel-hint { font-size: 12px; color: var(--text-mute); margin-top: 8px; line-height: 1.6; flex-shrink: 0; }
.step-footer {
  display: flex; justify-content: flex-end; align-items: center; gap: 12px;
  margin-top: 20px; padding-top: 16px; border-top: 1px solid var(--border);
  flex-shrink: 0;
}.selected-info { color: var(--accent); font-size: 14px; }

/* 回答来源自动判断栏 */
.answer-source-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: 10px;
  margin-top: 14px;
  flex-shrink: 0;
}
.as-label { font-size: 13px; font-weight: 600; color: var(--text-prime); flex-shrink: 0; }
.as-tip { font-size: 12px; color: var(--text-mute); }

.field-desc { color: var(--text-sec); font-size: 12px; }
.field-desc-empty { color: var(--text-mute); }
.mapping-line { margin-bottom: 4px; }
.mapping-line code { background: var(--bg-input); padding: 2px 6px; border-radius: 3px; color: var(--accent); }
.ph-code { background: rgba(245,158,11,0.1); padding: 2px 8px; border-radius: 3px; color: #f59e0b; font-family: Menlo, monospace; font-size: 13px; }

/* 数据集选择卡片 */
.ds-select-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
  overflow-y: auto;
  min-height: 0;
  flex: 1;
  padding-right: 4px;
  align-content: start;
}
.ds-select-card {
  background: var(--bg-card);
  border: 1.5px solid var(--border);
  border-radius: 12px;
  padding: 14px 16px;
  cursor: pointer;
  transition: all 0.2s;
  box-shadow: var(--shadow-sm);
  height: auto;
  display: flex;
  flex-direction: column;
}
.ds-select-card:hover { border-color: var(--accent); }
.ds-select-card.selected {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-soft);
}
.ds-card-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; gap: 6px; }
.ds-type {
  font-size: 10px; font-weight: 700; letter-spacing: 0.5px;
  color: var(--accent);
  background: var(--accent-soft);
  padding: 2px 8px; border-radius: 4px;
}
.ds-name { font-size: 14px; font-weight: 600; color: var(--text-prime); margin-bottom: 3px; }
.ds-desc {
  font-size: 12px; color: var(--text-mute); margin-bottom: 8px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  flex: 1;
}
.ds-meta {
  display: flex; justify-content: space-between;
  font-size: 11px; color: var(--text-mute);
  padding-top: 6px; border-top: 1px solid var(--border);
}
.ds-meta span { display: flex; align-items: center; gap: 4px; }
.ds-check { color: var(--accent); }
.ds-empty { grid-column: 1 / -1; }

/* 确认面板 */
.confirm-panel {
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: 12px;
  overflow: hidden;
}
.confirm-item {
  display: flex;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
  font-size: 13px;
}
.confirm-item:last-child { border-bottom: none; }
.confirm-label {
  width: 90px;
  flex-shrink: 0;
  font-weight: 600;
  color: var(--text-sec);
}
.confirm-value {
  flex: 1;
  color: var(--text-prime);
  line-height: 1.7;
}
</style>
