<template>
  <div class="task-create-page">
    <!-- 顶部工具栏（与评估器编辑器对齐） -->
    <header class="topbar">
      <div class="topbar-left">
        <el-button text @click="$router.back()" class="back-btn">
          <el-icon><ArrowLeft /></el-icon><span>返回</span>
        </el-button>
        <div class="topbar-sep" />
        <span class="topbar-title">创建评测任务</span>
      </div>
      <div class="topbar-right">
        <el-button v-if="hasDraft()" size="small" type="danger" plain @click="handleClearDraft">清空草稿</el-button>
        <el-button type="primary" @click="handleCreate" :loading="creating" :disabled="!canCreate">
          <el-icon style="margin-right:4px"><Check /></el-icon>创建任务
        </el-button>
      </div>
    </header>

    <el-alert v-if="selectedDataset || selectedModelIds.length || taskForm.name"
              title="已自动保存草稿，中途离开后再回来可恢复"
              type="success" :closable="false" show-icon style="margin-bottom: 16px" />

    <div class="form-layout">
      <div class="form-body" v-loading="loading">
        <!-- 1. 任务名称 -->
        <div class="form-row">
          <div class="form-row-left">
            <span class="form-num">1</span>
            <div>
              <div class="form-label">任务名称</div>
              <div class="form-hint">为本次评测命名</div>
            </div>
          </div>
          <div class="form-row-right">
            <el-input v-model="taskForm.name" placeholder="请输入评测任务名称" style="flex:1" />
          </div>
        </div>

        <!-- 2. 数据集选择 -->
        <div class="form-row">
          <div class="form-row-left">
            <span class="form-num">2</span>
            <div>
              <div class="form-label">数据集</div>
              <div class="form-hint">选择要评测的数据集</div>
            </div>
          </div>
          <div class="form-row-right" style="gap:8px">
            <el-select v-model="selectedDatasetName" filterable placeholder="选择数据集名称"
              @change="onDatasetNameChange" style="flex:1">
              <el-option v-for="g in groupedDatasets" :key="g.name" :label="g.name" :value="g.name" />
            </el-select>
            <el-select v-model="selectedDatasetId" filterable placeholder="选择版本"
              :disabled="!selectedDatasetName" @change="onDatasetChange" style="flex:1">
              <el-option v-for="ds in datasetVersions" :key="ds.id" :value="ds.id"
                :label="`v${ds.version}（${ds.totalCount} 条，${ds.hasModelResponse === 1 ? '含回答' : '只有问题'}）`">
                <span>v{{ ds.version }}</span>
                <span style="color:var(--text-mute);font-size:11px;margin-left:6px">{{ ds.totalCount }} 条</span>
                <el-tag v-if="ds.id === latestDatasetId" size="small" type="success" style="margin-left:auto">最新</el-tag>
              </el-option>
            </el-select>
            <el-tooltip content="预览数据集" placement="top">
              <el-button size="small" circle @click="previewItem('dataset')" :disabled="!selectedDataset">
                <el-icon><View /></el-icon>
              </el-button>
            </el-tooltip>
          </div>
        </div>

        <!-- 3. 裁判模型 -->
        <div class="form-row">
          <div class="form-row-left">
            <span class="form-num">3</span>
            <div>
              <div class="form-label">裁判模型</div>
              <div class="form-hint">判定 badcase 的 AI，必须选择</div>
            </div>
          </div>
          <div class="form-row-right">
            <el-select v-model="judgeModelId" filterable placeholder="选择裁判模型" style="flex:1">
              <el-option v-for="m in judgeModels" :key="m.id" :label="`${m.name}（${providerLabel(m.provider)}）`" :value="m.id" />
            </el-select>
            <el-tooltip content="预览模型" placement="top">
              <el-button size="small" circle @click="previewItem('judgeModel')" :disabled="!judgeModelId">
                <el-icon><View /></el-icon>
              </el-button>
            </el-tooltip>
          </div>
        </div>

        <!-- 4. 被测模型（仅 API 回答时） -->
        <div class="form-row" v-if="answerSource === 'api'">
          <div class="form-row-left">
            <span class="form-num">4</span>
            <div>
              <div class="form-label">被测模型</div>
              <div class="form-hint">你的 AI 产品 API，评测时生成回答</div>
            </div>
          </div>
          <div class="form-row-right">
            <el-select v-model="selectedModelIds" multiple filterable collapse-tags collapse-tags-tooltip
              placeholder="选择被测模型" style="flex:1">
              <el-option v-for="m in evaluatedModels" :key="m.id" :label="`${m.name}（${providerLabel(m.provider)}）`" :value="m.id" />
            </el-select>
          </div>
        </div>

        <!-- 5. 评估器 -->
        <div class="form-row">
          <div class="form-row-left">
            <span class="form-num">{{ answerSource === 'api' ? 5 : 4 }}</span>
            <div>
              <div class="form-label">评估器</div>
              <div class="form-hint">选择评测维度标准</div>
            </div>
          </div>
          <div class="form-row-right" style="gap:8px">
            <el-select v-model="selectedPromptName" filterable placeholder="选择评估器名称"
              @change="onPromptNameChange" style="flex:1">
              <el-option v-for="g in groupedPrompts" :key="g.name" :label="g.name" :value="g.name" />
            </el-select>
            <el-select v-model="selectedPromptId" filterable placeholder="选择版本"
              :disabled="!selectedPromptName" @change="onEvaluatorChange" style="flex:1">
              <el-option v-for="p in promptVersions" :key="p.id" :value="p.id"
                :label="`v${p.version}（${p.evaluationMode === 'reference' ? '参考对照' : '质量评判'}）`">
                <span>v{{ p.version }}</span>
                <span style="color:var(--text-mute);font-size:11px;margin-left:6px">{{ p.evaluationMode === 'reference' ? '参考对照' : '质量评判' }}</span>
                <el-tag v-if="p.id === latestPromptId" size="small" type="success" style="margin-left:auto">最新</el-tag>
              </el-option>
            </el-select>
            <el-tooltip content="预览评估器" placement="top">
              <el-button size="small" circle @click="previewItem('evaluator')" :disabled="!selectedPromptId">
                <el-icon><View /></el-icon>
              </el-button>
            </el-tooltip>
          </div>
        </div>

        <!-- 评估器模式冲突警告 -->
        <el-alert v-if="modeConflictPrompts.length" type="warning" :closable="false" show-icon
          :title="`以下评估器需要参考答案，但当前数据集「${selectedDataset?.name || ''}」没有参考答案，评测结果会失真：${modeConflictPrompts.map(p => '「' + p.name + '」').join('、')}`"
          style="margin-bottom: 16px" />

        <!-- 6. 字段映射（评估器选中后自动出现） -->
        <div v-if="selectedPromptId && hasFieldMapping" class="form-row">
          <div class="form-row-left">
            <span class="form-num">{{ answerSource === 'api' ? 6 : 5 }}</span>
            <div>
              <div class="form-label">字段映射</div>
              <div class="form-hint">将评估器中的占位符映射到数据集字段</div>
            </div>
          </div>
          <div class="form-row-right form-row-right--wide">
            <el-table :data="currentMappingFields" stripe border size="small" style="flex:1">
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
      </div>

      <!-- 右侧预览面板 -->
      <transition name="preview-slide">
        <div v-if="previewType" class="preview-panel">
          <div class="preview-head">
            <span class="preview-title">{{ previewTitle }}</span>
            <el-button size="small" circle @click="previewType = null">
              <el-icon><Close /></el-icon>
            </el-button>
          </div>
          <div class="preview-body" v-loading="previewLoading">
            <!-- 数据集预览 -->
            <template v-if="previewType === 'dataset' && previewData">
              <div class="preview-section">
                <div class="preview-kv"><span class="preview-k">名称</span><span class="preview-v">{{ previewData.name }}</span></div>
                <div class="preview-kv"><span class="preview-k">版本</span><span class="preview-v">v{{ previewData.version }}</span></div>
                <div class="preview-kv"><span class="preview-k">数据量</span><span class="preview-v">{{ previewData.totalCount }} 条</span></div>
                <div class="preview-kv"><span class="preview-k">回答</span><span class="preview-v">{{ previewData.hasModelResponse === 1 ? '含模型回答' : '只有问题' }}</span></div>
                <div class="preview-kv"><span class="preview-k">参考答案</span><span class="preview-v">{{ previewData.hasReference === 1 ? '有' : '无' }}</span></div>
                <div class="preview-kv"><span class="preview-k">创建时间</span><span class="preview-v">{{ previewData.createdAt }}</span></div>
              </div>
              <div class="preview-section" v-if="previewDatasetFields.length">
                <div class="preview-section-title">字段 Schema</div>
                <div v-for="f in previewDatasetFields" :key="f.fieldName" class="preview-field-row">
                  <code class="preview-field-name">{{ f.fieldName }}</code>
                  <el-tag v-if="f.role && f.role !== 'CUSTOM'" size="small" type="info">{{ f.role }}</el-tag>
                  <span class="preview-field-type">{{ f.fieldType }}</span>
                  <span class="preview-field-desc" v-if="f.description">{{ f.description }}</span>
                </div>
              </div>
            </template>
            <!-- 评估器预览 -->
            <template v-if="previewType === 'evaluator' && previewData">
              <div class="preview-section">
                <div class="preview-kv"><span class="preview-k">名称</span><span class="preview-v">{{ previewData.name }}</span></div>
                <div class="preview-kv"><span class="preview-k">版本</span><span class="preview-v">v{{ previewData.version }}</span></div>
                <div class="preview-kv"><span class="preview-k">模式</span><span class="preview-v">{{ previewData.evaluationMode === 'reference' ? '参考对照' : '质量评判' }}</span></div>
                <div class="preview-kv"><span class="preview-k">类型</span><span class="preview-v">{{ previewData.promptType === 'structured' ? '结构化规则' : '自由文本' }}</span></div>
              </div>
              <div class="preview-section" v-if="previewData.promptTemplate">
                <div class="preview-section-title">Prompt 模板</div>
                <pre class="preview-pre">{{ previewData.promptTemplate }}</pre>
              </div>
            </template>
            <!-- 模型预览 -->
            <template v-if="(previewType === 'judgeModel') && previewData">
              <div class="preview-section">
                <div class="preview-kv"><span class="preview-k">名称</span><span class="preview-v">{{ previewData.name }}</span></div>
                <div class="preview-kv"><span class="preview-k">模型ID</span><span class="preview-v" style="font-family:Menlo,monospace;font-size:12px">{{ previewData.modelId }}</span></div>
                <div class="preview-kv"><span class="preview-k">提供商</span><span class="preview-v">{{ providerLabel(previewData.provider) }}</span></div>
                <div class="preview-kv"><span class="preview-k">类型</span><span class="preview-v">{{ previewData.modelType === 'judge' ? '裁判模型' : '被测模型' }}</span></div>
                <div class="preview-kv"><span class="preview-k">温度</span><span class="preview-v">{{ previewData.temperature }}</span></div>
                <div class="preview-kv"><span class="preview-k">最大Token</span><span class="preview-v">{{ previewData.maxTokens }}</span></div>
                <div class="preview-kv"><span class="preview-k">API 地址</span><span class="preview-v" style="font-family:Menlo,monospace;font-size:12px;word-break:break-all">{{ previewData.apiBase }}</span></div>
              </div>
            </template>
          </div>
        </div>
      </transition>
    </div>
  </div>
</template>

<script>
import { ref, computed, onMounted, watch, nextTick } from 'vue'
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
    const selectedDatasetName = ref('')
    const selectedDatasetId = ref(null)
    const selectedDataset = ref(null)
    const availableModels = ref([])
    const selectedModelIds = ref([])
    const judgeModelId = ref(null)
    const availablePrompts = ref([])
    const selectedPromptName = ref('')
    const selectedPromptId = ref(null)
    const taskForm = ref({ name: '' })
    const datasetFields = ref([])
    const judgePromptMappings = ref({})
    const answerSource = ref('dataset')

    // 预览面板状态
    const previewType = ref(null)
    const previewData = ref(null)
    const previewLoading = ref(false)
    const previewDatasetFields = ref([])

    const previewTitle = computed(() => ({
      dataset: '数据集详情',
      evaluator: '评估器详情',
      judgeModel: '裁判模型详情'
    }[previewType.value] || ''))

    const evaluatedModels = computed(() => availableModels.value.filter(m => !m.modelType || m.modelType === 'evaluated' || m.modelType === 'both'))
    const judgeModels = computed(() => availableModels.value.filter(m => m.modelType === 'judge' || m.modelType === 'both'))

    // 按名称分组，版本降序
    const groupedDatasets = computed(() => {
      const map = {}
      for (const ds of datasets.value) {
        if (!map[ds.name]) map[ds.name] = []
        map[ds.name].push(ds)
      }
      return Object.entries(map).map(([name, items]) => {
        items.sort((a, b) => (b.version || 0) - (a.version || 0))
        return { name, versions: items, latest: items[0].id }
      }).sort((a, b) => a.name.localeCompare(b.name))
    })

    const datasetVersions = computed(() => {
      const g = groupedDatasets.value.find(g => g.name === selectedDatasetName.value)
      return g ? g.versions : []
    })
    const latestDatasetId = computed(() => {
      const g = groupedDatasets.value.find(g => g.name === selectedDatasetName.value)
      return g ? g.latest : null
    })

    const groupedPrompts = computed(() => {
      const map = {}
      for (const p of availablePrompts.value) {
        if (!map[p.name]) map[p.name] = []
        map[p.name].push(p)
      }
      return Object.entries(map).map(([name, items]) => {
        items.sort((a, b) => (b.version || 0) - (a.version || 0))
        return { name, versions: items, latest: items[0].id }
      }).sort((a, b) => a.name.localeCompare(b.name))
    })

    const promptVersions = computed(() => {
      const g = groupedPrompts.value.find(g => g.name === selectedPromptName.value)
      return g ? g.versions : []
    })
    const latestPromptId = computed(() => {
      const g = groupedPrompts.value.find(g => g.name === selectedPromptName.value)
      return g ? g.latest : null
    })

    const modeConflictPrompts = computed(() => {
      if (!selectedDataset.value) return []
      if (selectedDataset.value.hasReference === 1) return []
      return availablePrompts.value.filter(p =>
        selectedPromptId.value === p.id && p.evaluationMode === 'reference'
      )
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

    // ===== 预览 =====
    const previewItem = async (type) => {
      if (previewType.value === type) { previewType.value = null; return }
      previewType.value = type
      previewData.value = null
      previewDatasetFields.value = []
      previewLoading.value = true
      try {
        if (type === 'dataset' && selectedDataset.value) {
          previewData.value = selectedDataset.value
          try { const res = await datasetApi.getSchema(selectedDataset.value.id); previewDatasetFields.value = res.data || [] } catch {}
        } else if (type === 'evaluator' && selectedPromptId.value) {
          const p = availablePrompts.value.find(x => x.id === selectedPromptId.value)
          previewData.value = p || null
        } else if (type === 'judgeModel' && judgeModelId.value) {
          const m = availableModels.value.find(x => x.id === judgeModelId.value)
          previewData.value = m || null
        }
      } finally { previewLoading.value = false }
    }

    // ===== 草稿持久化 =====
    let skipSave = false
    const saveDraft = () => {
      if (skipSave) return
      const draft = {
        selectedDatasetName: selectedDatasetName.value,
        selectedDatasetId: selectedDataset.value?.id || null,
        selectedModelIds: selectedModelIds.value,
        judgeModelId: judgeModelId.value,
        selectedPromptName: selectedPromptName.value,
        selectedPromptId: selectedPromptId.value,
        taskForm: { ...taskForm.value },
        datasetFields: datasetFields.value,
        judgePromptMappings: judgePromptMappings.value,
        answerSource: answerSource.value,
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
        selectedDatasetName.value = draft.selectedDatasetName || ''
        selectedPromptName.value = draft.selectedPromptName || ''
        selectedPromptId.value = draft.selectedPromptId || null
        taskForm.value = draft.taskForm || { name: '' }
        datasetFields.value = draft.datasetFields || []
        answerSource.value = draft.answerSource || 'dataset'
        const fixMappings = (mappings) => (mappings || []).map(m => ({ ...m, raw: m.raw || (m.placeholder ? `{${m.placeholder}}` : '') }))
        judgePromptMappings.value = typeof draft.judgePromptMappings === 'object'
          ? Object.fromEntries(Object.entries(draft.judgePromptMappings).map(([k, v]) => [k, fixMappings(v)]))
          : {}
        return draft.selectedDatasetId
      } catch { return false }
    }

    const clearDraft = () => { try { localStorage.removeItem(STORAGE_KEY) } catch {} }
    const hasDraft = () => { try { return !!localStorage.getItem(STORAGE_KEY) } catch { return false } }

    watch([selectedDatasetName, selectedDatasetId, selectedModelIds, judgeModelId, selectedPromptName, selectedPromptId, taskForm,
           datasetFields, judgePromptMappings, answerSource], saveDraft, { deep: true })

    const loadDatasets = async () => { const res = await datasetApi.list(1, 100); datasets.value = res.data.records }
    const loadModels = async () => { const res = await modelApi.list(1, 100, undefined, 1); availableModels.value = res.data.records }
    const loadPrompts = async () => { const res = await promptApi.list(); availablePrompts.value = res.data.records.filter(p => p.status === 1) }

    const detectAnswerSource = (ds, fields) => {
      if (ds && ds.hasModelResponse !== undefined && ds.hasModelResponse !== null) {
        return ds.hasModelResponse === 1 ? 'dataset' : 'api'
      }
      if (!fields || !fields.length) return 'api'
      const names = ['model_response', 'response', 'answer', 'output', '模型回答', '回答', 'result']
      return fields.some(f => names.includes((f.fieldName || '').toLowerCase())) ? 'dataset' : 'api'
    }

    const onDatasetNameChange = (name) => {
      // 切换名称时自动选最新版本
      const g = groupedDatasets.value.find(g => g.name === name)
      if (g && g.latest) {
        selectedDatasetId.value = g.latest
        onDatasetChange(g.latest)
      } else {
        selectedDatasetId.value = null
        selectedDataset.value = null
        datasetFields.value = []
        answerSource.value = 'dataset'
      }
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

    const onPromptNameChange = (name) => {
      const g = groupedPrompts.value.find(g => g.name === name)
      if (g && g.latest) {
        selectedPromptId.value = g.latest
        onEvaluatorChange(g.latest)
      } else {
        selectedPromptId.value = null
        judgePromptMappings.value = {}
      }
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
          promptTemplate: '',
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
      skipSave = true
      clearDraft()
      selectedDatasetName.value = ''; selectedDatasetId.value = null; selectedDataset.value = null
      selectedModelIds.value = []; judgeModelId.value = null
      selectedPromptName.value = ''; selectedPromptId.value = null
      taskForm.value = { name: '' }; datasetFields.value = []; judgePromptMappings.value = {}
      answerSource.value = 'dataset'
      previewType.value = null; previewData.value = null
      await nextTick()
      skipSave = false
      ElMessage.success('已清空')
    }

    onMounted(async () => {
      loading.value = true
      await Promise.all([loadDatasets(), loadModels(), loadPrompts()])
      const datasetIdToRestore = loadDraft()
      if (datasetIdToRestore) {
        const ds = datasets.value.find(d => d.id === datasetIdToRestore)
        if (ds) {
          selectedDatasetName.value = ds.name
          selectedDatasetId.value = ds.id; selectedDataset.value = ds
          answerSource.value = ds.hasModelResponse === 1 ? 'dataset' : 'api'
          if (!datasetFields.value.length) {
            try { const res = await datasetApi.getSchema(ds.id); datasetFields.value = res.data || []; answerSource.value = detectAnswerSource(ds, datasetFields.value) } catch {}
          }
          if (selectedPromptId.value && !Object.keys(judgePromptMappings.value).length) {
            const savedPrompt = availablePrompts.value.find(p => p.id === selectedPromptId.value)
            if (savedPrompt) selectedPromptName.value = savedPrompt.name
            await buildMappingsForEvaluator(selectedPromptId.value)
          }
        } else { selectedDatasetId.value = null; selectedDataset.value = null; selectedDatasetName.value = ''; clearDraft() }
      }
      loading.value = false
    })

    return {
      loading, creating, datasets, selectedDatasetName, selectedDatasetId, selectedDataset,
      onDatasetNameChange, onDatasetChange, datasetVersions, latestDatasetId,
      availableModels, selectedModelIds, judgeModelId, availablePrompts,
      selectedPromptName, selectedPromptId, onPromptNameChange,
      promptVersions, latestPromptId,
      taskForm, datasetFields, judgePromptMappings,
      onEvaluatorChange, currentMappingFields, hasFieldMapping,
      getDatasetFieldDesc, mappingFieldExists,
      handleCreate, handleClearDraft, hasDraft,
      answerSource, canCreate,
      evaluatedModels, judgeModels, modeConflictPrompts, providerLabel,
      groupedDatasets, groupedPrompts,
      previewType, previewData, previewLoading, previewDatasetFields, previewTitle, previewItem
    }
  }
}
</script>

<style scoped>
.task-create-page {
  background: #fff;
  border-radius: 14px;
  border: 1px solid var(--border);
  box-shadow: var(--shadow-card);
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}
/* 顶部工具栏（与评估器编辑器一致） */
.topbar { display: flex; align-items: center; justify-content: space-between; padding: 0 20px; height: 54px; flex-shrink: 0; border-bottom: 1px solid #e5e7eb; background: #ffffff; }
.topbar-left { display: flex; align-items: center; gap: 10px; flex: 1; min-width: 0; }
.topbar-right { display: flex; align-items: center; gap: 8px; flex-shrink: 0; margin-left: 16px; }
.back-btn { font-size: 13px; color: var(--text-sec) !important; }
.topbar-sep { width: 1px; height: 20px; background: #e5e7eb; flex-shrink: 0; }
.topbar-title { font-size: 15px; font-weight: 600; color: #111827; }

.form-layout {
  flex: 1;
  display: flex;
  min-height: 0;
  gap: 0;
  padding: 0 20px;
}

.form-body {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
  padding-right: 4px;
}

/* 表单行：左标签 + 右控件 */
.form-row {
  display: flex;
  align-items: flex-start;
  gap: 20px;
  padding: 14px 0;
  border-bottom: 1px solid var(--border);
}
.form-row:last-of-type { border-bottom: none; }

.form-row-left {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  width: 150px;
  flex-shrink: 0;
  padding-top: 5px;
}
.form-num {
  width: 22px; height: 22px; border-radius: 6px;
  background: var(--accent); color: var(--accent-text);
  font-size: 11px; font-weight: 700;
  display: inline-flex; align-items: center; justify-content: center;
  flex-shrink: 0; margin-top: 1px;
}
.form-label { font-size: 14px; font-weight: 600; color: var(--text-prime); line-height: 1.3; }
.form-hint { font-size: 11px; color: var(--text-mute); margin-top: 1px; line-height: 1.3; }

.form-row-right {
  flex: 1;
  min-width: 0;
  max-width: 500px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.form-row-right--wide {
  max-width: 700px;
}

.field-desc { color: var(--text-sec); font-size: 12px; }
.field-desc-empty { color: var(--text-mute); }
.ph-code { background: rgba(245,158,11,0.1); padding: 2px 8px; border-radius: 3px; color: #f59e0b; font-family: Menlo, monospace; font-size: 13px; }


/* ===== 右侧预览面板 ===== */
.preview-panel {
  width: 340px;
  flex-shrink: 0;
  border-left: 1px solid var(--border);
  margin-left: 20px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.preview-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}
.preview-title { font-size: 14px; font-weight: 600; color: var(--text-prime); }
.preview-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
}
.preview-section { margin-bottom: 18px; }
.preview-section-title {
  font-size: 12px; font-weight: 600; color: var(--text-sec);
  margin-bottom: 8px; padding-bottom: 4px; border-bottom: 1px solid var(--border);
}
.preview-kv {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  padding: 5px 0;
  font-size: 13px;
}
.preview-k { color: var(--text-mute); flex-shrink: 0; margin-right: 12px; }
.preview-v { color: var(--text-prime); text-align: right; word-break: break-all; }

.preview-field-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 0;
  border-bottom: 1px solid var(--border);
  font-size: 12px;
}
.preview-field-row:last-child { border-bottom: none; }
.preview-field-name { font-family: Menlo, monospace; font-weight: 600; color: var(--text-prime); }
.preview-field-type { color: var(--text-mute); font-size: 11px; }
.preview-field-desc { color: var(--text-sec); font-size: 11px; margin-left: auto; }

.preview-pre {
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 10px 12px;
  font-family: Menlo, monospace;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-prime);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 300px;
  overflow-y: auto;
}

/* 预览面板过渡动画 */
.preview-slide-enter-active, .preview-slide-leave-active {
  transition: all 0.25s ease;
}
.preview-slide-enter-from, .preview-slide-leave-to {
  width: 0;
  opacity: 0;
  margin-left: 0;
  padding: 0;
}
</style>
