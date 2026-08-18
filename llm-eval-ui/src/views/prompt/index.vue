<template>
  <div class="page-card">
    <div class="page-header">
      <div>
        <h2>评估器管理</h2>
        <p class="page-sub">编写评估器，定义维度、标准与 badcase 规则</p>
      </div>
      <el-button type="primary" @click="handleAdd">
        <el-icon style="margin-right:4px"><Plus /></el-icon> 添加Prompt
      </el-button>
    </div>

    <!-- 统计卡片行 -->
    <div class="stats-row">
      <div class="stat-card">
        <div class="stat-icon green"><el-icon :size="18"><ChatLineSquare /></el-icon></div>
        <div><div class="stat-num">{{ prompts.length }}</div><div class="stat-label">Prompt 总数</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon indigo"><el-icon :size="18"><List /></el-icon></div>
        <div><div class="stat-num">{{ structuredCount }}</div><div class="stat-label">结构化</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon orange"><el-icon :size="18"><Document /></el-icon></div>
        <div><div class="stat-num">{{ freeCount }}</div><div class="stat-label">自由文本</div></div>
      </div>
    </div>

    <!-- Prompt 卡片网格 -->
    <div class="prompt-grid" v-loading="loading">
      <div v-for="p in prompts" :key="p.id" class="prompt-card">
        <div class="prompt-card-top">
          <span class="mode-tag" :class="p.dimensionsConfig ? 'struct' : 'free'">
            <el-icon :size="12" style="margin-right:3px"><component :is="p.dimensionsConfig ? 'SetUp' : 'Document'" /></el-icon>
            {{ p.dimensionsConfig ? '结构化' : '自由文本' }}
          </span>
          <el-tag :type="p.status === 1 ? 'success' : 'info'" size="small">
            {{ p.status === 1 ? '启用' : '禁用' }}
          </el-tag>
        </div>
        <h3 class="prompt-name">{{ p.name }}</h3>
        <p class="prompt-desc">{{ p.description || '暂无描述' }}</p>
        <div class="prompt-meta" v-if="p.dimensionsConfig && dimCount(p)">
          <span class="meta-chip"><el-icon :size="11"><Grid /></el-icon> {{ dimCount(p) }} 个维度</span>
        </div>
        <div class="prompt-card-footer">
          <el-button size="small" type="primary" plain @click="handleEdit(p)">
            <el-icon style="margin-right:3px"><Edit /></el-icon> 编辑
          </el-button>
          <el-button size="small" @click="viewPrompt(p)">查看</el-button>
          <el-button size="small" type="danger" text @click="handleDelete(p)">删除</el-button>
        </div>
      </div>
      <div v-if="!prompts.length && !loading" class="prompt-empty">
        <el-empty description="还没有评估器，点击右上角添加" />
      </div>
    </div>

    <!-- 添加/编辑弹窗（分步向导） -->
    <el-dialog v-model="showDialog" :title="editingId ? '编辑评估器' : '添加评估器'" width="820px" top="5vh" class="prompt-dialog" :close-on-click-modal="false">
      <div class="prompt-dialog-body">
        <!-- ===== Step 0: 选择模板 ===== -->
        <div v-show="wizardStep === 0 && !skipTemplate && !editingId" class="wizard-pane template-pane">
          <div class="form-section">
            <div class="section-title">选择一个评测模板快速开始</div>
            <p class="template-sub">基于行业标准模板快速搭建评估器，你也可以稍后再自定义维度</p>

            <!-- 分类筛选 -->
            <div class="template-cat-filters">
              <span v-for="c in templateCategories" :key="c.key"
                class="template-cat-chip" :class="{ active: templateCat === c.key }"
                @click="templateCat = c.key">{{ c.label }}</span>
            </div>

            <!-- 模板卡片 -->
            <div class="template-grid">
              <div v-for="t in filteredTemplates" :key="t.id" class="template-card"
                :class="{ selected: selectedTemplate?.id === t.id }"
                @click="selectedTemplate = selectedTemplate?.id === t.id ? null : t">
                <div class="template-card-icon">
                  <el-icon :size="20"><component :is="t.icon" /></el-icon>
                </div>
                <div class="template-card-body">
                  <div class="template-card-name">{{ t.name }}</div>
                  <div class="template-card-desc">{{ t.description }}</div>
                </div>
                <div class="template-card-check" v-if="selectedTemplate?.id === t.id">
                  <el-icon :size="16"><Check /></el-icon>
                </div>
              </div>
              <div class="template-card template-from-scratch" :class="{ selected: !selectedTemplate }" @click="selectedTemplate = null">
                <div class="template-card-icon scratch-icon">
                  <el-icon :size="20"><Edit /></el-icon>
                </div>
                <div class="template-card-body">
                  <div class="template-card-name">从零开始</div>
                  <div class="template-card-desc">不套用模板，自己定义所有评测维度和标准</div>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 顶部步骤条（自定义，避免 el-steps 兼容问题） -->
        <div v-show="wizardStep >= 0 && !(wizardStep === 0 && !skipTemplate && !editingId)" class="wizard-steps">
          <div v-for="(s, i) in wizardSteps" :key="i"
            class="wizard-step"
            :class="{ active: wizardStep === i, done: wizardStep > i }"
            @click="wizardStep = i">
            <span class="ws-dot">
              <el-icon v-if="wizardStep > i" :size="11"><Check /></el-icon>
              <span v-else>{{ i + 1 }}</span>
            </span>
            <span class="ws-label">{{ s }}</span>
          </div>
        </div>

        <!-- ===== Step 1: 基本信息 ===== -->
        <div v-show="wizardStep === 0 && (skipTemplate || editingId)" class="wizard-pane">
          <div class="form-section">
            <div class="section-title">编辑模式</div>
            <el-radio-group v-model="editMode" @change="onModeChange" class="mode-radios">
              <el-radio-button label="structured">结构化编辑</el-radio-button>
              <el-radio-button label="free">自由文本</el-radio-button>
            </el-radio-group>
          </div>

          <el-form :model="form" label-position="top" class="base-form">
            <el-row :gutter="12">
              <el-col :span="12">
                <el-form-item label="评估器名称" required>
                  <el-input v-model="form.name" placeholder="如：召回质量评测" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="描述">
                  <el-input v-model="form.description" placeholder="可选" />
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item label="评测模式" required class="mode-form-item">
              <el-radio-group v-model="form.evaluationMode" class="mode-radios">
                <el-radio-button label="quality">质量评判（无需参考答案）</el-radio-button>
                <el-radio-button label="reference">参考对照（需参考答案）</el-radio-button>
              </el-radio-group>
              <div class="dim-tip">
                {{ form.evaluationMode === 'reference'
                  ? '评测时对照参考答案判断准确性/完整性，数据集需包含 reference_answer 字段'
                  : '评测时凭质量标准判断回答质量（相关性/流畅性/安全性等），无需参考答案' }}
              </div>
            </el-form-item>
          </el-form>

          <!-- 自由文本模式：直接编辑 prompt -->
          <template v-if="editMode === 'free'">
            <div class="form-section free-prompt-section">
              <div class="section-title with-action">
                <span>评估Prompt</span>
                <div class="free-actions">
                  <el-select v-model="polishModelId" placeholder="选择识别模型" size="small" style="width: 150px" clearable>
                    <el-option v-for="m in polishModels" :key="m.id" :label="m.name" :value="m.id" />
                  </el-select>
                  <el-button size="small" type="primary" plain :loading="parsing" @click="handleParseToDim">
                    <el-icon style="margin-right:3px"><MagicStick /></el-icon> AI 识别为规则
                  </el-button>
                </div>
              </div>
              <el-input v-model="form.promptTemplate" type="textarea" :rows="10"
                placeholder="用自然语言描述你的评测标准，例如：&#10;&#10;评测客服机器人的回答质量，要求：&#10;1. 能准确理解用户意图，不能答非所问&#10;2. 回答要完整，不能遗漏用户问题的关键点&#10;3. 语气要友好专业，不能生硬冷漠&#10;4. 不能编造不存在的政策或信息&#10;&#10;写完后点击右上角&quot;AI 识别为规则&quot;，自动转换为结构化评测维度" />
              <div class="dim-tip">
                也可以直接编写评估Prompt，支持变量：<code>{question}</code> 问题, <code>{reference_answer}</code> 参考答案, <code>{model_response}</code> 模型回复
              </div>
            </div>
          </template>
        </div>

        <!-- ===== Step 2: 评分维度（仅结构化） ===== -->
        <div v-show="wizardStep === 1 && editMode === 'structured'" class="wizard-pane">
          <!-- AI 润色工具栏 -->
          <div class="polish-toolbar">
            <div class="polish-info">
              <el-icon :size="14"><MagicStick /></el-icon>
              <span>AI 润色</span>
              <span class="polish-hint">让 AI 优化评分标准的文案描述，使其更专业、更具体</span>
            </div>
            <div class="polish-actions">
              <el-select v-model="polishModelId" placeholder="选择润色模型" size="small" style="width: 160px" clearable>
                <el-option v-for="m in polishModels" :key="m.id" :label="m.name" :value="m.id" />
              </el-select>
              <el-button size="small" type="primary" :loading="polishing" @click="handlePolish"
                :disabled="!dimConfig.dimensions.length || !polishModels.length">
                <el-icon style="margin-right:3px"><MagicStick /></el-icon> 润色全部维度
              </el-button>
            </div>
          </div>

          <div class="form-section">
            <div class="section-title">角色设定</div>
            <el-input v-model="dimConfig.role" type="textarea" :rows="2" class="role-input"
              placeholder="如：你是一名资深召回质量评测专家" />
          </div>

          <div class="form-section">
            <div class="section-title">数据注入模板</div>
            <el-input v-model="dimConfig.context_template" type="textarea" :rows="3"
              placeholder="数据注入模板：&#10;- ${caseDescription}：用户操作指令&#10;- ${expected_waypoints}：核心途径点" />
            <div class="dim-tip">支持 ${xxx} 和 {xxx} 占位符</div>
          </div>

          <div class="form-section">
            <div class="section-title with-action">
              <span>评分维度</span>
              <div class="dim-actions">
                <el-button v-if="selectedDims.length" size="small" type="danger" plain @click="batchDeleteDims">
                  <el-icon style="margin-right:3px"><Delete /></el-icon> 删除所选 ({{ selectedDims.length }})
                </el-button>
                <el-button type="primary" plain size="small" @click="addDimension">
                  <el-icon style="margin-right:3px"><Plus /></el-icon> 添加维度
                </el-button>
              </div>
            </div>

            <!-- 维度表格：可勾选、排序、展开编辑 -->
            <div class="dim-table-wrap">
              <el-table :data="dimConfig.dimensions" row-key="__uid" class="dim-table"
                @selection-change="onDimSelect" :max-height="360">
                <el-table-column type="selection" width="38" />
                <el-table-column type="expand">
                  <template #default="{ row }">
                    <div class="dim-expand-body">
                      <el-form label-position="top" class="dim-form">
                        <el-form-item label="维度名称" required>
                          <el-input v-model="row.name" placeholder="如：召回精度" />
                        </el-form-item>
                        <el-form-item label="评分方式">
                          <el-radio-group v-model="row.scoring_type" class="preset-radios" @change="applyScoringPreset(row)">
                            <el-radio-button label="score">分数 1/3/5</el-radio-button>
                            <el-radio-button label="boolean">采纳/不采纳</el-radio-button>
                            <el-radio-button label="enum">分级 G/S/B</el-radio-button>
                            <el-radio-button label="custom">自定义</el-radio-button>
                          </el-radio-group>
                        </el-form-item>
                        <el-row :gutter="12" v-if="row.scoring_type === 'enum' || row.scoring_type === 'custom'">
                          <el-col :span="12">
                            <el-form-item :label="row.scoring_type === 'enum' ? '可选值' : '等级'">
                              <el-input v-model="row.enum_values_str" :placeholder="row.scoring_type === 'enum' ? '用/分隔，如：G/S/B' : '用/分隔自定义等级，如：优/良/差'" />
                            </el-form-item>
                          </el-col>
                          <el-col :span="12">
                            <el-form-item label="badcase阈值">
                              <el-input v-model="row.badcase_threshold" :placeholder="row.scoring_type === 'enum' ? '如：=B' : '如：=差'" />
                            </el-form-item>
                          </el-col>
                        </el-row>
                        <el-row :gutter="12" v-else>
                          <el-col :span="12">
                            <el-form-item label="badcase阈值">
                              <el-input v-model="row.badcase_threshold" :placeholder="row.scoring_type === 'score' ? '如：&lt;3' : '如：=不采纳'" />
                            </el-form-item>
                          </el-col>
                          <el-col :span="12">
                            <el-form-item label="提示">
                              <div class="dim-tip preset-tip">
                                {{ row.scoring_type === 'score' ? '低于该分数判定为 badcase' : '等于该值判定为 badcase' }}
                              </div>
                            </el-form-item>
                          </el-col>
                        </el-row>

                        <el-form-item label="评分细则 (Rubric)">
                          <div v-if="row.scoring_type === 'enum'" class="enum-hint">
                            G = Good（优秀） · S = Satisfactory（合格） · B = Bad（不合格）
                          </div>
                          <div v-if="row.scoring_type === 'custom'" class="enum-hint">
                            自定义等级：在下方添加/删除等级，每个等级填写标准描述
                          </div>
                          <div class="rubric-list">
                            <div v-for="(r, ri) in row.rubric" :key="ri" class="rubric-row">
                              <template v-if="row.scoring_type === 'custom'">
                                <el-input v-model="r.level" placeholder="等级" style="width: 80px" size="small" />
                              </template>
                              <span v-else class="rubric-level" :class="'lv-' + r.level">{{ r.level }}</span>
                              <el-input v-model="r.desc" placeholder="填写该等级的标准描述" style="flex:1" size="small" />
                              <el-button v-if="row.scoring_type === 'custom'" size="small" type="danger" text @click="row.rubric.splice(ri, 1)">删除</el-button>
                            </div>
                            <el-button v-if="row.scoring_type === 'custom'" size="small" plain @click="row.rubric.push({ level: '', desc: '' })">+ 添加档位</el-button>
                          </div>
                        </el-form-item>
                      </el-form>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="#" width="44">
                  <template #default="{ $index }">{{ $index + 1 }}</template>
                </el-table-column>
                <el-table-column label="维度名称" min-width="150">
                  <template #default="{ row }">
                    <div class="dim-name-cell">
                      <el-icon v-if="isDimComplete(row)" :size="13" class="dim-check"><CircleCheckFilled /></el-icon>
                      <span class="dim-name-text">{{ row.name || '未命名维度' }}</span>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="评分方式" width="100">
                  <template #default="{ row }">{{ scoringLabel(row.scoring_type) }}</template>
                </el-table-column>
                <el-table-column label="badcase阈值" width="110">
                  <template #default="{ row }">
                    <code class="dim-threshold">{{ row.badcase_threshold || '-' }}</code>
                  </template>
                </el-table-column>
                <el-table-column label="状态" width="76">
                  <template #default="{ row }">
                    <span :class="['dim-state', isDimComplete(row) ? 'done' : 'todo']">
                      {{ isDimComplete(row) ? '已填写' : '未完成' }}
                    </span>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="110" fixed="right">
                  <template #default="{ $index }">
                    <el-button size="small" text :disabled="$index === 0" @click="moveDim($index, -1)">
                      <el-icon :size="14"><Top /></el-icon>
                    </el-button>
                    <el-button size="small" text :disabled="$index === dimConfig.dimensions.length - 1" @click="moveDim($index, 1)">
                      <el-icon :size="14"><Bottom /></el-icon>
                    </el-button>
                    <el-button size="small" type="danger" text @click="removeDimension($index)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
              <div v-if="!dimConfig.dimensions.length" class="dim-table-empty">
                还没有评分维度，点击右上角"添加维度"开始
              </div>
            </div>
          </div>
        </div>

        <!-- ===== Step 3: 判定规则（仅结构化） ===== -->
        <div v-show="wizardStep === 2 && editMode === 'structured'" class="wizard-pane">
          <div class="form-section">
            <div class="section-title">badcase 判定</div>
            <el-radio-group v-model="dimConfig.badcase_rule" class="rule-radios">
              <el-radio label="any">任一维度 badcase 即整体 badcase</el-radio>
              <el-radio label="majority">多数维度 badcase 则整体 badcase</el-radio>
              <el-radio label="all">所有维度均 badcase 才整体 badcase</el-radio>
            </el-radio-group>
            <el-input v-model="dimConfig.extra_instructions" type="textarea" :rows="2" class="extra-input"
              placeholder="额外指令，如：可用性=不可用时直接判定badcase" />
          </div>

          <div class="preview-row">
            <el-button size="small" @click="previewPrompt" :loading="previewing">
              <el-icon style="margin-right:4px"><View /></el-icon> 预览生成的 Prompt
            </el-button>
          </div>
        </div>
      </div>

      <template #footer>
        <div class="dialog-footer">
          <template v-if="wizardStep === 0 && !skipTemplate && !editingId">
            <el-button size="small" @click="selectedTemplate = null; skipTemplate = true; wizardStep = 0">跳过模板</el-button>
            <el-button size="small" type="primary" @click="applyTemplate">
              {{ selectedTemplate ? '使用此模板' : '从零开始' }}
            </el-button>
          </template>
          <template v-else>
            <el-button size="small" @click="showDialog = false">取消</el-button>
            <!-- 自由文本模式：单页编辑，直接保存，不需要分步 -->
            <template v-if="editMode === 'free'">
              <el-button size="small" type="primary" @click="handleSave" :loading="saving">保存</el-button>
            </template>
            <!-- 结构化模式：分步向导 -->
            <template v-else>
              <el-button v-if="wizardStep > 0" size="small" @click="wizardStep--">上一步</el-button>
              <el-button v-if="wizardStep < 2" size="small" type="primary" @click="nextStep">下一步</el-button>
              <el-button v-if="wizardStep === 2" size="small" type="primary" @click="handleSave" :loading="saving">保存</el-button>
            </template>
          </template>
        </div>
      </template>
    </el-dialog>

    <!-- Prompt 预览弹窗 -->
    <el-dialog v-model="showPreviewDialog" title="Prompt预览" width="800px">
      <pre class="prompt-preview">{{ previewText }}</pre>
    </el-dialog>

    <!-- Prompt 查看弹窗 -->
    <el-dialog v-model="showViewDialog" :title="`${viewingPrompt?.name} - 评估器`" width="800px">
      <pre class="prompt-preview">{{ viewingPrompt?.promptTemplate }}</pre>
    </el-dialog>
  </div>
</template>

<script>
import { ref, reactive, computed, onMounted } from 'vue'
import { promptApi, modelApi } from '../../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { evaluatorTemplates, templateCategories, templateToDimConfig } from '../../data/evaluatorTemplates'

export default {
  name: 'PromptPage',
  setup() {
    const loading = ref(false)
    const prompts = ref([])
    const showDialog = ref(false)
    const saving = ref(false)
    const editingId = ref(null)
    const editMode = ref('structured')
    const showPreviewDialog = ref(false)
    const showViewDialog = ref(false)
    const previewText = ref('')
    const previewing = ref(false)
    const viewingPrompt = ref(null)
    const expandedDim = ref(0)
    const wizardStep = ref(0)
    const wizardSteps = ['基本信息', '评分维度', '判定规则']

    // ===== 模板选择 =====
    const selectedTemplate = ref(null)
    const skipTemplate = ref(false)
    const templateCat = ref('all')

    const filteredTemplates = computed(() => {
      if (templateCat.value === 'all') return evaluatorTemplates
      return evaluatorTemplates.filter(t => t.category === templateCat.value)
    })

    const applyTemplate = () => {
      if (selectedTemplate.value) {
        const t = selectedTemplate.value
        // 应用模板数据
        const cfg = templateToDimConfig(t)
        cfg.dimensions.forEach(d => { d.__uid = Date.now() + '_' + Math.random().toString(36).slice(2, 8) })
        Object.assign(dimConfig, cfg)
        // 同步基本信息
        form.value.name = t.name
        form.value.description = t.description
        form.value.evaluationMode = t.evaluationMode || 'quality'
      }
      // 无论是否有模板，都进入基本信息步骤
      skipTemplate.value = true
      wizardStep.value = 0
    }

    // ===== AI 润色 =====
    const polishing = ref(false)
    const polishModelId = ref(null)
    const polishModels = ref([])

    const loadPolishModels = async () => {
      try {
        const res = await modelApi.list(1, 100)
        polishModels.value = res.data.records.filter(m => m.status === 1)
        // 默认自动选中 DeepSeek 模型（按 provider/name/modelId 模糊匹配）
        const deepseek = polishModels.value.find(m =>
          m.provider === 'deepseek' || /deepseek/i.test(m.name || '') || /deepseek/i.test(m.modelId || ''))
        polishModelId.value = deepseek ? deepseek.id : (polishModels.value[0]?.id || null)
      } catch (e) { polishModels.value = [] }
    }
    const handlePolish = async () => {
      if (!polishModelId.value) {
        ElMessage.warning('请先选择一个用于润色的模型')
        return
      }
      if (!dimConfig.dimensions.length) {
        ElMessage.warning('请先添加评分维度')
        return
      }
      // 校验维度名称
      const unnamed = dimConfig.dimensions.filter(d => !d.name)
      if (unnamed.length) {
        ElMessage.warning('请先填写所有维度的名称再润色')
        return
      }
      polishing.value = true
      try {
        const configStr = buildDimensionsConfig()
        const res = await promptApi.polish(polishModelId.value, configStr)
        // 解析润色结果，回填 rubric 描述
        const parsed = JSON.parse(res.data)
        if (parsed.dimensions && Array.isArray(parsed.dimensions)) {
          parsed.dimensions.forEach((pd, i) => {
            const dim = dimConfig.dimensions[i]
            if (!dim) return
            if (pd.rubric && Array.isArray(pd.rubric)) {
              // 匹配 level 回填 desc（润色后等级顺序可能变化）
              pd.rubric.forEach(pr => {
                const target = dim.rubric.find(r => r.level === pr.level)
                if (target && pr.desc) target.desc = pr.desc
              })
            }
          })
          ElMessage.success('润色完成，已更新评分标准描述')
        } else {
          ElMessage.warning('润色返回格式异常，未做修改')
        }
      } catch (e) {
        ElMessage.error(e.response?.data?.message || '润色失败，请检查模型配置')
      } finally {
        polishing.value = false
      }
    }

    // ===== 自然语言识别为规则 =====
    const parsing = ref(false)
    const handleParseToDim = async () => {
      if (!form.value.promptTemplate.trim()) {
        ElMessage.warning('请先用自然语言描述你的评测标准')
        return
      }
      if (!polishModelId.value) {
        ElMessage.warning('请先选择用于识别的模型（AI 润色旁的模型下拉框）')
        return
      }
      parsing.value = true
      try {
        const res = await promptApi.parseToDimensions(polishModelId.value, form.value.promptTemplate)
        const cfg = JSON.parse(res.data)
        // 归一化为 dimConfig 结构
        Object.assign(dimConfig, {
          role: cfg.role || '',
          context_template: cfg.context_template || '',
          badcase_rule: cfg.badcase_rule || 'any',
          extra_instructions: cfg.extra_instructions || '',
          dimensions: (cfg.dimensions || []).map(d => {
            const dim = {
              name: d.name || '',
              scoring_type: d.scoring_type || 'score',
              badcase_threshold: d.badcase_threshold || '<3',
              rubric: (d.rubric || []).map(r => ({ level: r.level, desc: r.desc })),
              enum_values_str: d.enum_values ? d.enum_values.join('/') : '',
              __uid: Date.now() + '_' + Math.random().toString(36).slice(2, 8)
            }
            return dim
          })
        })
        // 切换到结构化编辑模式并进入维度步骤
        editMode.value = 'structured'
        wizardStep.value = 1
        expandedDim.value = 0
        ElMessage.success(`识别成功，生成 ${dimConfig.dimensions.length} 个评测维度，可继续调整`)
      } catch (e) {
        ElMessage.error(e.response?.data?.message || '识别失败，请重试或手动编辑')
      } finally {
        parsing.value = false
      }
    }

    const form = ref({ name: '', description: '', promptTemplate: '', dimensionsConfig: '', evaluationMode: 'quality' })

    const createEmptyDimConfig = () => ({
      role: '',
      context_template: '',
      dimensions: [],
      badcase_rule: 'any',
      extra_instructions: ''
    })

    const createEmptyDimension = () => ({
      name: '',
      scoring_type: 'score',
      badcase_threshold: '',
      rubric: [],
      enum_values_str: '',
      __uid: Date.now() + '_' + Math.random().toString(36).slice(2, 8)
    })

    // ===== 维度表格 =====
    const selectedDims = ref([])
    const onDimSelect = (rows) => { selectedDims.value = rows }
    const batchDeleteDims = () => {
      const uids = new Set(selectedDims.value.map(d => d.__uid))
      dimConfig.dimensions = dimConfig.dimensions.filter(d => !uids.has(d.__uid))
      selectedDims.value = []
      if (!dimConfig.dimensions.length) expandedDim.value = -1
    }
    const moveDim = (idx, dir) => {
      const to = idx + dir
      if (to < 0 || to >= dimConfig.dimensions.length) return
      const arr = dimConfig.dimensions
      const t = arr[idx]; arr[idx] = arr[to]; arr[to] = t
    }
    const scoringLabel = (t) => ({
      score: '分数 1/3/5',
      boolean: '采纳/不采纳',
      enum: '分级 G/S/B',
      custom: '自定义'
    }[t] || t)

    /**
     * 评分方式预设：切换时自动填充等级(Rubric level)和默认阈值
     * 每个等级的描述(desc)由用户自行填写
     * 自定义(custom)：清空预设，完全由用户定义等级
     */
    const applyScoringPreset = (dim) => {
      if (dim.scoring_type === 'score') {
        dim.enum_values_str = ''
        dim.rubric = [
          { level: '5', desc: '' },
          { level: '3', desc: '' },
          { level: '1', desc: '' }
        ]
        dim.badcase_threshold = '<3'
      } else if (dim.scoring_type === 'boolean') {
        dim.enum_values_str = ''
        dim.rubric = [
          { level: '采纳', desc: '' },
          { level: '不采纳', desc: '' }
        ]
        dim.badcase_threshold = '=不采纳'
      } else if (dim.scoring_type === 'enum') {
        dim.enum_values_str = 'G/S/B'
        dim.rubric = [
          { level: 'G', desc: '' },
          { level: 'S', desc: '' },
          { level: 'B', desc: '' }
        ]
        dim.badcase_threshold = '=B'
      } else if (dim.scoring_type === 'custom') {
        dim.enum_values_str = ''
        dim.rubric = []
        dim.badcase_threshold = ''
      }
    }

    const dimConfig = reactive(createEmptyDimConfig())

    // 卡片统计
    const structuredCount = computed(() => prompts.value.filter(p => p.dimensionsConfig).length)
    const freeCount = computed(() => prompts.value.filter(p => !p.dimensionsConfig).length)
    const dimCount = (p) => {
      if (!p.dimensionsConfig) return 0
      try { return (JSON.parse(p.dimensionsConfig).dimensions || []).length } catch { return 0 }
    }

    const loadPrompts = async () => {
      loading.value = true
      try {
        const res = await promptApi.list()
        prompts.value = res.data.records
      } finally {
        loading.value = false
      }
    }

    const handleAdd = () => {
      editingId.value = null
      editMode.value = 'structured'
      form.value = { name: '', description: '', promptTemplate: '', dimensionsConfig: '', evaluationMode: 'quality' }
      Object.assign(dimConfig, createEmptyDimConfig())
      expandedDim.value = 0
      wizardStep.value = 0
      selectedTemplate.value = null
      skipTemplate.value = false
      showDialog.value = true
    }

    const handleEdit = (row) => {
      editingId.value = row.id
      form.value = {
        name: row.name,
        description: row.description,
        promptTemplate: row.promptTemplate || '',
        dimensionsConfig: row.dimensionsConfig || '',
        evaluationMode: row.evaluationMode || 'quality'
      }
      if (row.dimensionsConfig) {
        editMode.value = 'structured'
        try {
          const parsed = JSON.parse(row.dimensionsConfig)
          Object.assign(dimConfig, {
            role: parsed.role || '',
            context_template: parsed.context_template || '',
            dimensions: (parsed.dimensions || []).map(d => ({
              ...d,
              enum_values_str: d.enum_values ? d.enum_values.join('/') : '',
              __uid: Date.now() + '_' + Math.random().toString(36).slice(2, 8)
            })),
            badcase_rule: parsed.badcase_rule || 'any',
            extra_instructions: parsed.extra_instructions || ''
          })
          // 默认展开第一个维度
          expandedDim.value = dimConfig.dimensions.length ? 0 : 0
        } catch (e) {
          Object.assign(dimConfig, createEmptyDimConfig())
          expandedDim.value = 0
        }
      } else {
        editMode.value = 'free'
        Object.assign(dimConfig, createEmptyDimConfig())
        expandedDim.value = 0
      }
      wizardStep.value = 0
      selectedTemplate.value = null
      skipTemplate.value = true  // 编辑模式下跳过模板选择
      showDialog.value = true
    }

    /**
     * 下一步：校验当前步骤
     */
    const nextStep = () => {
      if (wizardStep.value === 0) {
        if (!form.value.name.trim()) {
          ElMessage.warning('请填写评估器名称')
          return
        }
      }
      wizardStep.value++
    }

    const onModeChange = (mode) => {
      // 切换模式时回到第一步
      wizardStep.value = 0
    }

    const addDimension = () => {
      const dim = createEmptyDimension()
      dimConfig.dimensions.push(dim)
      applyScoringPreset(dim)  // 默认填充分数 1/3/5 预设
      // 只展开新添加的维度
      expandedDim.value = dimConfig.dimensions.length - 1
    }

    const toggleDim = (idx) => {
      expandedDim.value = expandedDim.value === idx ? -1 : idx
    }

    /**
     * 判断维度是否已填写完成（名称 + 至少一个 rubric 描述）
     */
    const isDimComplete = (dim) => {
      const hasName = dim.name && dim.name.trim()
      const hasRubric = dim.rubric && dim.rubric.some(r => (r.desc && r.desc.trim()) || (r.level && r.level.trim()))
      return !!(hasName && hasRubric)
    }

    const removeDimension = (idx) => {
      dimConfig.dimensions.splice(idx, 1)
      // 修正展开的索引（如果删的是当前展开的，回退到上一个）
      if (expandedDim.value === idx) {
        expandedDim.value = Math.max(0, idx - 1)
      } else if (expandedDim.value > idx) {
        expandedDim.value -= 1
      }
    }

    const buildDimensionsConfig = () => {
      const config = {
        role: dimConfig.role,
        context_template: dimConfig.context_template,
        badcase_rule: dimConfig.badcase_rule,
        extra_instructions: dimConfig.extra_instructions,
        dimensions: dimConfig.dimensions.map(d => {
          const def = {
            name: d.name,
            scoring_type: d.scoring_type,
            badcase_threshold: d.badcase_threshold,
            rubric: d.rubric.filter(r => r.level || r.desc).map(r => ({ level: r.level, desc: r.desc }))
          }
          if (d.scoring_type === 'enum' && d.enum_values_str) {
            def.enum_values = d.enum_values_str.split('/').map(v => v.trim()).filter(Boolean)
          }
          if (d.scoring_type === 'custom') {
            // 自定义：可选等级从 rubric 的 level 提取
            def.enum_values = d.rubric.map(r => r.level).filter(v => v && v.trim()).map(v => v.trim())
          }
          return def
        })
      }
      return JSON.stringify(config)
    }

    const previewPrompt = async () => {
      previewing.value = true
      try {
        const configStr = buildDimensionsConfig()
        const res = await promptApi.preview({ dimensionsConfig: configStr })
        previewText.value = res.data
        showPreviewDialog.value = true
      } catch (e) {
        ElMessage.error('预览失败')
      } finally {
        previewing.value = false
      }
    }

    const handleSave = async () => {
      if (!form.value.name) {
        ElMessage.warning('请填写评估器名称')
        return
      }
      if (editMode.value === 'structured') {
        // 验证维度
        const validDims = dimConfig.dimensions.filter(d => d.name)
        if (validDims.length === 0) {
          ElMessage.warning('请至少添加一个评分维度')
          return
        }
        for (const d of validDims) {
          if (!d.badcase_threshold) {
            ElMessage.warning(`维度"${d.name}"需要填写badcase阈值`)
            return
          }
        }
        form.value.dimensionsConfig = buildDimensionsConfig()
        form.value.promptTemplate = '' // 结构化模式自动生成
      } else {
        if (!form.value.promptTemplate) {
          ElMessage.warning('自由文本模式下，评估Prompt不能为空')
          return
        }
        form.value.dimensionsConfig = ''
      }

      saving.value = true
      try {
        if (editingId.value) {
          await promptApi.update(editingId.value, form.value)
          ElMessage.success('更新成功')
        } else {
          await promptApi.add(form.value)
          ElMessage.success('添加成功')
        }
        showDialog.value = false
        editingId.value = null
        wizardStep.value = 0
        loadPrompts()
      } catch (e) {
        ElMessage.error(e.response?.data?.message || '保存失败')
      } finally {
        saving.value = false
      }
    }

    const handleDelete = async (row) => {
      await ElMessageBox.confirm('确定删除该评估器？', '提示', { type: 'warning' })
      await promptApi.delete(row.id)
      ElMessage.success('删除成功')
      loadPrompts()
    }

    const viewPrompt = (row) => {
      viewingPrompt.value = row
      showViewDialog.value = true
    }

    onMounted(() => { loadPrompts(); loadPolishModels() })

    return {
      loading, prompts, loadPrompts, showDialog, saving, editingId, editMode,
      form, dimConfig, addDimension, removeDimension, onModeChange, applyScoringPreset,
      previewPrompt, previewing, showPreviewDialog, previewText,
      handleAdd, handleEdit, handleSave, handleDelete,
      showViewDialog, viewingPrompt, viewPrompt,
      expandedDim, isDimComplete, toggleDim, wizardStep, wizardSteps, nextStep,
      structuredCount, freeCount, dimCount,
      // 维度表格
      selectedDims, onDimSelect, batchDeleteDims, moveDim, scoringLabel,
      // 模板
      selectedTemplate, skipTemplate, templateCat, filteredTemplates, templateCategories, applyTemplate,
      // AI 润色
      polishing, polishModelId, polishModels, handlePolish, loadPolishModels,
      // 自然语言识别为规则
      parsing, handleParseToDim
    }
  }
}
</script>

<style scoped>
.page-sub { color: var(--text-mute); font-size: 12px; margin-top: 2px; }
.stats-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 14px; margin-bottom: 20px; }
.stat-card {
  display: flex; align-items: center; gap: 12px;
  background: var(--bg-card); border: 1px solid var(--border);
  border-radius: 12px; padding: 16px 18px;
  box-shadow: var(--shadow-sm);
}
.stat-icon {
  width: 38px; height: 38px; border-radius: 10px;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.stat-icon.green { background: var(--accent-soft); color: var(--accent); }
.stat-icon.indigo { background: rgba(99,102,241,0.12); color: #6366f1; }
.stat-icon.orange { background: rgba(245,158,11,0.12); color: #f59e0b; }
.stat-num { font-size: 22px; font-weight: 700; color: var(--text-prime); line-height: 1.1; }
.stat-label { font-size: 11px; color: var(--text-mute); margin-top: 2px; }

.prompt-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 14px;
  align-content: start;
  overflow-y: auto;
  min-height: 0;
  flex: 1;
  padding-right: 4px;
}
.prompt-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 16px;
  box-shadow: var(--shadow-sm);
  transition: all 0.2s;
  display: flex;
  flex-direction: column;
}
.prompt-card:hover {
  border-color: var(--accent);
  box-shadow: 0 8px 20px rgba(0,0,0,0.08);
}
.prompt-card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}
.mode-tag {
  display: inline-flex;
  align-items: center;
  font-size: 11px;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: 20px;
}
.mode-tag.struct { background: var(--accent-soft); color: var(--accent); }
.mode-tag.free { background: rgba(148,163,184,0.12); color: var(--text-sec); }
.prompt-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-prime);
  margin-bottom: 4px;
}
.prompt-desc {
  font-size: 12px;
  color: var(--text-mute);
  margin-bottom: 10px;
  flex: 1;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.prompt-meta { margin-bottom: 10px; }
.meta-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--text-sec);
  background: var(--bg-input);
  border: 1px solid var(--border);
  padding: 2px 10px;
  border-radius: 20px;
}
.prompt-card-footer {
  display: flex;
  align-items: center;
  gap: 4px;
  padding-top: 10px;
  border-top: 1px solid var(--border);
}
.prompt-card-footer .el-button:last-child { margin-left: auto; }
.prompt-empty { grid-column: 1 / -1; }

.wizard-steps {
  display: flex;
  gap: 4px;
  margin-bottom: 14px;
  padding: 10px 14px;
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: 10px;
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
.wizard-step.done { cursor: pointer; }
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
.wizard-pane {
  min-height: 240px;
}
.prompt-dialog :deep(.el-dialog) {
  display: flex;
  flex-direction: column;
}
.prompt-dialog :deep(.el-dialog__header) {
  flex-shrink: 0;
  padding: 18px 20px 0;
}
.prompt-dialog :deep(.el-dialog__body) {
  flex: 1;
  min-height: 0;
  height: 480px;
  padding: 14px 20px;
  overflow-y: auto;
}
.prompt-dialog :deep(.el-dialog__footer) {
  flex-shrink: 0;
  padding: 10px 20px 16px;
  border-top: 1px solid var(--border);
}
.prompt-dialog-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
}
/* AI 润色工具栏 */
.polish-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 8px 12px;
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: 10px;
  margin-bottom: 12px;
}
.polish-info {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--accent);
}
.polish-info .el-icon { font-size: 14px; }
.polish-hint {
  font-size: 11px;
  font-weight: 400;
  color: var(--text-mute);
  margin-left: 4px;
}
.polish-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 模式切换按钮 */
.mode-radios {
  display: flex;
  gap: 8px;
}
.mode-radios :deep(.el-radio-button__inner) {
  border-radius: 6px;
  font-size: 12px;
  padding: 6px 18px;
  border-color: var(--border);
  background: var(--bg-card);
  color: var(--text-sec);
  box-shadow: none !important;
}
.mode-radios :deep(.el-radio-button:not(:first-child) .el-radio-button__inner) {
  border-left: 1px solid var(--border);
}
.mode-radios :deep(.el-radio-button.is-active .el-radio-button__inner) {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-text);
}
/* 基础表单 */
.base-form {
  margin-top: 14px;
  padding: 14px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 10px;
}
.base-form :deep(.el-form-item) { margin-bottom: 0; }
.base-form :deep(.el-form-item__label) {
  padding-bottom: 4px;
  font-size: 12px;
  color: var(--text-sec);
  line-height: 1.2;
}
/* 评测模式与上方名称/描述行拉开间距 */
.base-form .mode-form-item {
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px dashed var(--border);
}
/* 表单分区 */
.form-section {
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 10px 12px;
}
.free-prompt-section {
  margin-top: 4px;
  background: var(--bg-card);
}
.form-section :deep(.el-textarea__inner) { margin-bottom: 6px; }
.form-section :deep(.el-textarea__inner:last-child) { margin-bottom: 0; }
.section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-prime);
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.section-title::before {
  content: '';
  width: 3px;
  height: 14px;
  background: var(--accent);
  border-radius: 2px;
}
.section-title.with-action {
  justify-content: space-between;
}
.section-title.with-action::before { display: none; }
/* 自由文本区操作 */
.free-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}
/* 维度表格 */
.dim-actions {
  display: flex;
  gap: 6px;
}
.dim-table-wrap {
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
  background: var(--bg-card);
}
.dim-table :deep(.el-table__inner-wrapper::before) {
  display: none;
}
.dim-table :deep(.el-table__header th) {
  background: var(--bg-input);
  color: var(--text-sec);
  font-weight: 600;
  font-size: 12px;
}
.dim-table :deep(.el-table__row) {
  background: var(--bg-card);
}
.dim-table :deep(.el-table__row:hover > td.el-table__cell) {
  background: var(--bg-card-hover);
}
.dim-table :deep(.el-table__cell) {
  padding: 6px 0;
}
.dim-name-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.dim-check {
  color: var(--accent);
  flex-shrink: 0;
}
.dim-name-text {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-prime);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dim-threshold {
  font-family: 'SF Mono', Menlo, monospace;
  font-size: 12px;
  font-weight: 600;
  color: var(--accent);
  background: var(--accent-soft);
  padding: 1px 8px;
  border-radius: 4px;
}
.dim-state {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 8px;
  border-radius: 20px;
}
.dim-state.done { background: var(--accent-soft); color: var(--accent); }
.dim-state.todo { background: rgba(148,163,184,0.12); color: var(--text-mute); }
.dim-expand-body {
  padding: 14px 16px 6px 48px;
  background: var(--bg-input);
}
.dim-table-empty {
  padding: 28px;
  text-align: center;
  color: var(--text-mute);
  font-size: 13px;
}
.dim-form :deep(.el-form-item) { margin-bottom: 10px; }
.dim-form :deep(.el-form-item__label) {
  padding-bottom: 4px;
  font-size: 12px;
  color: var(--text-sec);
  line-height: 1.2;
}
/* 评分方式预设按钮 */
.preset-radios {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.preset-radios :deep(.el-radio-button__inner) {
  border-radius: 6px !important;
  font-size: 12px;
  padding: 5px 12px;
  border-color: var(--border) !important;
  background: var(--bg-card);
  color: var(--text-sec);
}
.preset-radios :deep(.el-radio-button:not(:first-child) .el-radio-button__inner) {
  border-left: 1px solid var(--border) !important;
  box-shadow: none !important;
}
.preset-radios :deep(.el-radio-button.is-active .el-radio-button__inner) {
  background: var(--accent) !important;
  border-color: var(--accent) !important;
  color: var(--accent-text) !important;
  font-weight: 600;
}
.preset-tip {
  margin-top: 0;
  padding-top: 8px;
}
/* 阈值提示 */
.dim-tip {
  color: var(--text-mute);
  font-size: 12px;
  margin-top: 4px;
  line-height: 1.6;
}
.dim-tip code {
  background: var(--accent-soft);
  padding: 1px 4px;
  border-radius: 3px;
  color: var(--accent);
}
/* Rubric */
.rubric-list { width: 100%; }
.rubric-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}
.rubric-level {
  width: 72px;
  height: 26px;
  border-radius: 6px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 12px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  border: 1px solid rgba(16,185,129,0.25);
}
.enum-hint {
  font-size: 12px;
  color: var(--text-mute);
  background: var(--bg-card);
  border: 1px dashed var(--border);
  border-radius: 6px;
  padding: 6px 10px;
  margin-bottom: 8px;
}
/* 判定规则 */
.rule-radios {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 10px;
}
.rule-radios :deep(.el-radio) { margin-right: 0; }
.rule-radios :deep(.el-radio__label) { font-size: 13px; }
.extra-input { margin-top: 4px; }
/* 预览 */
.preview-row {
  display: flex;
  justify-content: flex-end;
}
/* 底部 */
.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding-top: 2px;
}
.prompt-preview {
  background: var(--bg-input);
  padding: 16px;
  border-radius: 8px;
  font-size: 13px;
  white-space: pre-wrap;
  max-height: 60vh;
  overflow-y: auto;
  color: var(--text-sec);
  font-family: 'SF Mono', Menlo, monospace;
}

/* ===== 模板选择 ===== */
.template-pane {
  max-height: 440px;
  overflow-y: auto;
  padding-right: 4px;
}
.template-sub {
  font-size: 12px;
  color: var(--text-mute);
  margin-bottom: 12px;
  line-height: 1.5;
}
.template-cat-filters {
  display: flex;
  gap: 6px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.template-cat-chip {
  padding: 4px 12px;
  border-radius: 20px;
  font-size: 11px;
  font-weight: 500;
  cursor: pointer;
  background: var(--bg-card);
  border: 1px solid var(--border);
  color: var(--text-sec);
  transition: all 0.15s;
  user-select: none;
}
.template-cat-chip:hover {
  border-color: var(--accent);
  color: var(--accent);
}
.template-cat-chip.active {
  background: var(--accent-soft);
  border-color: var(--accent);
  color: var(--accent);
  font-weight: 600;
}
.template-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}
.template-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border-radius: 10px;
  border: 1.5px solid var(--border);
  background: var(--bg-card);
  cursor: pointer;
  transition: all 0.15s;
  position: relative;
}
.template-card:hover {
  border-color: var(--border-strong);
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
}
.template-card.selected {
  border-color: var(--accent);
  background: var(--accent-soft);
  box-shadow: 0 0 0 1px var(--accent);
}
.template-from-scratch {
  border-style: dashed;
  opacity: 0.7;
}
.template-from-scratch:hover {
  opacity: 1;
  border-color: var(--accent);
}
.template-from-scratch.selected {
  opacity: 1;
  border-style: solid;
}
.template-card-icon {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: var(--accent-soft);
  color: var(--accent);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.scratch-icon {
  background: var(--bg-input);
  color: var(--text-mute);
}
.template-card-body {
  flex: 1;
  min-width: 0;
}
.template-card-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-prime);
  margin-bottom: 2px;
}
.template-card-desc {
  font-size: 11px;
  color: var(--text-mute);
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.template-card-check {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--accent);
  color: var(--accent-text);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
</style>
