<template>
  <div class="editor-page">
    <!-- ===== 顶部工具栏 ===== -->
    <header class="topbar">
      <div class="topbar-left">
        <el-button text @click="goBack" class="back-btn">
          <el-icon><ArrowLeft /></el-icon><span>返回</span>
        </el-button>
        <div class="topbar-sep" />
        <el-input v-model="form.name" placeholder="评估器名称" class="topbar-field topbar-name" />
        <el-input v-model="form.description" placeholder="描述（可选）" class="topbar-field topbar-desc" />
      </div>
      <div class="topbar-right">
        <el-button @click="openPlayground" :disabled="!canTest">
          <el-icon style="margin-right:4px"><VideoPlay /></el-icon>测试
        </el-button>
        <el-button type="primary" @click="handleSave" :loading="saving">
          <el-icon style="margin-right:4px"><Check /></el-icon>保存
        </el-button>
      </div>
    </header>

    <!-- ===== 主体分栏 ===== -->
    <div class="editor-body">
      <!-- ── 左栏：配置面板 ── -->
      <aside class="panel-left" v-loading="loading">
        <!-- 编辑模式 + 模板导入 -->
        <div class="cfg-section">
          <div class="cfg-row">
            <span class="cfg-label">编辑模式</span>
            <el-radio-group v-model="editMode" size="small">
              <el-radio-button label="structured">结构化</el-radio-button>
              <el-radio-button label="free">自由文本</el-radio-button>
            </el-radio-group>
            <el-popover trigger="click" :width="420" placement="bottom-start">
              <template #reference>
                <el-button size="small" text type="primary" style="margin-left:auto">
                  <el-icon style="margin-right:3px"><DocumentCopy /></el-icon>从模板导入
                </el-button>
              </template>
              <div class="tpl-popover">
                <div class="tpl-popover-title">选择评测模板</div>
                <el-select v-model="tplCat" size="small" style="width:100%;margin-bottom:8px" placeholder="全部分类">
                  <el-option v-for="c in templateCategories" :key="c.key" :label="c.label" :value="c.key" />
                </el-select>
                <div class="tpl-list">
                  <div v-for="t in filteredTemplates" :key="t.id" class="tpl-item" @click="applyTemplate(t)">
                    <div class="tpl-item-name">{{ t.name }}</div>
                    <div class="tpl-item-desc">{{ t.description }}</div>
                  </div>
                </div>
              </div>
            </el-popover>
          </div>
        </div>

        <!-- 评测模式 -->
        <div class="cfg-section">
          <span class="cfg-label">评测模式</span>
          <el-radio-group v-model="form.evaluationMode" size="small" class="cfg-mode-rg">
            <el-radio-button label="quality">质量评判（无需参考答案）</el-radio-button>
            <el-radio-button label="reference">参考对照（需参考答案）</el-radio-button>
          </el-radio-group>
          <div class="cfg-hint">
            {{ form.evaluationMode === 'reference'
              ? '对照参考答案判断准确性/完整性，数据集需含 reference_answer 字段'
              : '凭质量标准判断回答质量，无需参考答案' }}
          </div>
        </div>

        <!-- ========== 结构化模式 ========== -->
        <template v-if="editMode === 'structured'">
          <!-- 角色设定 -->
          <div class="cfg-section">
            <span class="cfg-label">角色设定</span>
            <el-input v-model="dimConfig.role" type="textarea" :rows="2"
              placeholder="如：你是一名专业的评测专家" />
          </div>

          <!-- 数据注入模板 -->
          <div class="cfg-section">
            <span class="cfg-label">数据注入</span>
            <el-input v-model="dimConfig.context_template" type="textarea" :rows="3"
              placeholder="${question}：用户问题&#10;${reference_answer}：参考答案&#10;${model_response}：模型回答" />
            <div class="cfg-hint">支持 <code>${xxx}</code> 占位符，运行时替换为数据集字段值</div>
          </div>

          <!-- 评分维度 -->
          <div class="cfg-section cfg-dims">
            <div class="cfg-row">
              <span class="cfg-label">评分维度</span>
              <div class="cfg-dim-actions">
                <el-button v-if="dimConfig.dimensions.length" size="small" type="danger" plain
                  @click="dimConfig.dimensions = []">
                  <el-icon style="margin-right:3px"><Delete /></el-icon>清空
                </el-button>
                <el-button size="small" type="primary" plain @click="addDimension">
                  <el-icon style="margin-right:3px"><Plus /></el-icon>添加维度
                </el-button>
              </div>
            </div>

            <div v-if="!dimConfig.dimensions.length" class="cfg-empty">
              还没有评分维度，点击「添加维度」开始定义评测标准
            </div>

            <!-- 维度卡片列表 -->
            <div v-for="(dim, i) in dimConfig.dimensions" :key="dim.__uid" class="dim-card">
              <div class="dim-head">
                <span class="dim-idx">#{{ i + 1 }}</span>
                <el-input v-model="dim.name" size="small" placeholder="维度名称" class="dim-name-input" />
                <div class="dim-head-actions">
                  <el-button size="small" text :disabled="i === 0" @click="moveDim(i, -1)"><el-icon :size="14"><Top /></el-icon></el-button>
                  <el-button size="small" text :disabled="i === dimConfig.dimensions.length - 1" @click="moveDim(i, 1)"><el-icon :size="14"><Bottom /></el-icon></el-button>
                  <el-button size="small" type="danger" text @click="removeDim(i)"><el-icon :size="14"><Delete /></el-icon></el-button>
                </div>
              </div>

              <div class="dim-body">
                <!-- 评分方式 -->
                <div class="dim-row">
                  <span class="dim-row-label">评分方式</span>
                  <el-radio-group v-model="dim.scoring_type" size="small" @change="applyPreset(dim)">
                    <el-radio-button label="score">分数 1/3/5</el-radio-button>
                    <el-radio-button label="boolean">采纳/不采纳</el-radio-button>
                    <el-radio-button label="enum">分级 G/S/B</el-radio-button>
                    <el-radio-button label="custom">自定义</el-radio-button>
                  </el-radio-group>
                </div>

                <!-- badcase 阈值 -->
                <div class="dim-row">
                  <span class="dim-row-label">badcase 阈值</span>
                  <el-input v-model="dim.badcase_threshold" size="small" :placeholder="phThresh(dim)" class="dim-thresh" />
                  <span class="dim-hint">{{ hintThresh(dim) }}</span>
                </div>

                <!-- enum / custom 可选值 -->
                <div v-if="dim.scoring_type === 'enum' || dim.scoring_type === 'custom'" class="dim-row">
                  <span class="dim-row-label">{{ dim.scoring_type === 'enum' ? '可选值' : '等级' }}</span>
                  <el-input v-model="dim.enum_values_str" size="small"
                    :placeholder="dim.scoring_type === 'enum' ? '用/分隔：G/S/B' : '用/分隔：优/良/差'" class="dim-thresh" />
                </div>

                <!-- 评分细则 -->
                <div class="dim-rubric-wrap">
                  <span class="dim-row-label">评分细则</span>
                  <div class="rubric-list">
                    <div v-for="(r, ri) in dim.rubric" :key="ri" class="rubric-row">
                      <template v-if="dim.scoring_type === 'custom'">
                        <el-input v-model="r.level" size="small" placeholder="等级" style="width:60px" />
                      </template>
                      <span v-else class="rubric-badge" :class="'lv-' + r.level">{{ r.level }}</span>
                      <el-input v-model="r.desc" size="small" placeholder="该等级的标准描述" />
                      <el-button v-if="dim.scoring_type === 'custom'" size="small" type="danger" text @click="dim.rubric.splice(ri, 1)"><el-icon><Delete /></el-icon></el-button>
                    </div>
                    <el-button v-if="dim.scoring_type === 'custom'" size="small" text type="primary" @click="dim.rubric.push({ level: '', desc: '' })">+ 添加等级</el-button>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- 判定规则 -->
          <div class="cfg-section">
            <span class="cfg-label">badcase 判定规则</span>
            <p class="rule-text">默认按维度逐项判定：每个维度独立评判是否为 badcase，最终以「任一维度 badcase 即整体 badcase」规则汇总。</p>
            <el-input v-model="dimConfig.extra_instructions" type="textarea" :rows="2"
              placeholder="额外指令（可选），如：可用性=不可用时直接判定 badcase" style="margin-top:8px" />
          </div>

          <!-- Chain-of-Thought -->
          <div class="cfg-section">
            <div class="cfg-row">
              <span class="cfg-label">思维链引导 (CoT)</span>
              <el-switch v-model="dimConfig.enable_cot" />
            </div>
            <div class="cfg-hint" :class="{ 'hint-active': dimConfig.enable_cot }">
              {{ dimConfig.enable_cot
                ? '已开启：Prompt 要求模型逐步分析各维度后再输出评分，提升复杂场景判断质量'
                : '关闭：直接输出评分结果' }}
            </div>
          </div>

          <!-- Few-shot 示例 -->
          <div class="cfg-section">
            <div class="cfg-row">
              <span class="cfg-label">评测示例 (Few-shot)</span>
              <div class="cfg-dim-actions">
                <el-button size="small" type="primary" plain @click="addFewShot">
                  <el-icon style="margin-right:3px"><Plus /></el-icon>添加示例
                </el-button>
              </div>
            </div>
            <div v-if="!dimConfig.few_shots.length" class="cfg-hint" style="margin:0">
              添加 1-2 个已标注的评测示例，可显著提升评分一致性
            </div>
            <div v-for="(fs, i) in dimConfig.few_shots" :key="i" class="fewshot-card">
              <div class="fewshot-head">
                <span class="fewshot-idx">示例 {{ i + 1 }}</span>
                <el-button size="small" type="danger" text @click="dimConfig.few_shots.splice(i, 1)">
                  <el-icon><Delete /></el-icon>
                </el-button>
              </div>
              <div class="fewshot-fields">
                <div class="fewshot-field">
                  <span class="fewshot-field-label">问题</span>
                  <el-input v-model="fs.question" size="small" placeholder="用户提问" />
                </div>
                <div class="fewshot-field">
                  <span class="fewshot-field-label">模型回答</span>
                  <el-input v-model="fs.response" size="small" placeholder="待评测的模型回答" />
                </div>
                <div class="fewshot-field" v-if="form.evaluationMode === 'reference'">
                  <span class="fewshot-field-label">参考答案</span>
                  <el-input v-model="fs.reference" size="small" placeholder="标准答案" />
                </div>
                <div class="fewshot-field">
                  <span class="fewshot-field-label">期望输出</span>
                  <el-input v-model="fs.expected_output" size="small" type="textarea" :rows="3"
                    placeholder='{"维度名": {"score": 5, "reason": "..."}, ...}' />
                </div>
              </div>
            </div>
          </div>
        </template>

        <!-- ========== 自由文本模式 ========== -->
        <template v-else>
          <div class="cfg-section free-prompt-section">
            <div class="cfg-row" style="margin-bottom:10px">
              <span class="cfg-label" style="margin-bottom:0">评估 Prompt</span>
              <el-button size="small" type="primary" plain :loading="parsing" @click="handleParseToDim">
                <el-icon style="margin-right:3px"><MagicStick /></el-icon>AI 识别为结构化规则
              </el-button>
            </div>
            <el-input v-model="form.promptTemplate" type="textarea" class="free-textarea"
              placeholder="用自然语言描述你的评测标准，例如：&#10;&#10;评测客服机器人的回答质量，要求：&#10;1. 能准确理解用户意图，不能答非所问&#10;2. 回答要完整，不能遗漏关键点&#10;3. 语气要友好专业&#10;&#10;写完后点「AI 识别为结构化规则」自动转换维度" />
          </div>
        </template>
      </aside>

      <!-- ── 右栏：实时 Prompt 预览 ── -->
      <div class="panel-right">
        <div class="preview-head">
          <span class="preview-title">Prompt 预览</span>
          <div class="preview-head-right">
            <el-tag size="small" :type="editMode === 'structured' ? 'success' : 'info'">
              {{ editMode === 'structured' ? '自动生成' : '自由文本' }}
            </el-tag>
            <el-button size="small" text type="primary" :loading="previewLoading" @click="refreshPreview">
              <el-icon :size="13"><Refresh /></el-icon>
            </el-button>
          </div>
        </div>
        <div class="preview-box" v-loading="previewLoading && !previewText">
          <pre v-if="previewText" class="preview-pre">{{ formattedPreview }}</pre>
          <div v-else class="preview-empty">
            <el-icon :size="28"><View /></el-icon>
            <span>{{ editMode === 'structured' ? '填写左侧配置后自动生成' : '编写 Prompt 后自动显示' }}</span>
          </div>
        </div>
        <div class="preview-vars">
          <span class="vars-label">可用变量</span>
          <code>{question}</code> <code>{reference_answer}</code> <code>{model_response}</code>
          <code>{context}</code> <code>{category}</code>
        </div>
      </div>
    </div>

    <!-- ===== Playground 测试弹窗 ===== -->
    <el-dialog v-model="showPlayground" :title="`测试 - ${form.name || '评估器'}`" width="1000px" top="5vh" class="pg-dialog" :close-on-click-modal="false">
      <div class="pg-toolbar">
        <div class="pg-toolbar-left">
          <span class="pg-toolbar-label">模型</span>
          <el-select v-model="pgModelId" placeholder="选择模型" style="width: 240px" size="default">
            <el-option v-for="m in pgModels" :key="m.id" :label="m.name" :value="m.id">
              <span>{{ m.name }}</span>
              <span style="float:right;color:var(--text-mute);font-size:12px">{{ m.provider }}</span>
            </el-option>
          </el-select>
        </div>
        <el-button type="primary" @click="runPlayground" :loading="pgRunning" :disabled="!pgModelId">
          <el-icon style="margin-right:4px"><VideoPlay /></el-icon>运行
        </el-button>
      </div>
      <div class="pg-body">
        <div class="pg-col pg-prompt-col">
          <div class="pg-section-head">
            <span class="pg-label">Prompt</span>
            <div class="pg-switch">
              <span class="pg-switch-item" :class="{ active: pgView === 'editor' }" @click="pgView = 'editor'">编辑模板</span>
              <span class="pg-switch-item" :class="{ active: pgView === 'rendered' }" @click="pgView = 'rendered'">渲染结果</span>
            </div>
          </div>
          <textarea v-if="pgView === 'editor'" v-model="pgPromptTemplate" class="pg-textarea" spellcheck="false"
            placeholder="编写评测 Prompt，使用 {question} 等占位符"></textarea>
          <div v-else class="pg-rendered-wrap" v-loading="pgRunning">
            <pre v-if="pgRenderedPrompt" class="pg-rendered">{{ pgRenderedPrompt }}</pre>
            <div v-else class="pg-rendered-empty">
              <el-icon :size="22"><View /></el-icon>
              <span>运行后可查看渲染后的 Prompt</span>
            </div>
          </div>
        </div>
        <div class="pg-divider" />
        <div class="pg-col pg-side-col">
          <div class="pg-section">
            <div class="pg-section-head">
              <span class="pg-label">测试数据</span>
              <el-button size="small" text type="primary" @click="openImportDialog">
                <el-icon style="margin-right:3px"><FolderOpened /></el-icon>从数据集导入
              </el-button>
            </div>
            <div v-if="pgPlaceholders.length" class="pg-fields-wrap">
              <div v-for="ph in pgPlaceholders" :key="ph" class="pg-field">
                <label class="pg-field-name">{{ ph }}</label>
                <input v-model="pgFields[ph]" class="pg-field-input" :placeholder="ph" spellcheck="false" />
              </div>
            </div>
            <div v-else class="pg-empty-tip">使用 <code>{question}</code> 等占位符后自动生成输入框</div>
          </div>
          <div class="pg-section pg-output-section">
            <div class="pg-section-head">
              <span class="pg-label">模型输出</span>
              <span v-if="pgResult" class="pg-meta">{{ pgResult.modelName }} · {{ pgResult.latencyMs }}ms · {{ pgResult.tokenUsage }} tokens</span>
            </div>
            <div class="pg-response-box" v-loading="pgRunning">
              <template v-if="pgResult && !pgRunning">
                <span class="pg-response-text">{{ formatJson(pgResult.response) }}</span>
              </template>
              <template v-else-if="!pgRunning">
                <span class="pg-response-empty"><el-icon :size="22"><ChatLineSquare /></el-icon>点击「运行」查看模型输出</span>
              </template>
            </div>
          </div>
        </div>
      </div>
    </el-dialog>

    <!-- 从数据集导入 -->
    <el-dialog v-model="showImportDialog" title="从数据集导入测试数据" width="520px">
      <div style="display:flex;flex-direction:column;gap:12px">
        <el-select v-model="importDatasetId" placeholder="选择数据集" style="width:100%" filterable @change="loadImportItems">
          <el-option v-for="ds in importDatasets" :key="ds.id" :label="`${ds.name}${ds.version ? ' v' + ds.version : ''} · ${ds.totalCount ?? 0}条`" :value="ds.id" />
        </el-select>
        <el-table :data="importItems" stripe size="small" max-height="280" v-loading="importLoading"
          highlight-current-row @row-click="onPickImportItem">
          <el-table-column prop="seqNo" label="#" width="50" />
          <el-table-column label="问题" show-overflow-tooltip>
            <template #default="{ row }">{{ row.question || '-' }}</template>
          </el-table-column>
          <el-table-column label="参考答案" show-overflow-tooltip>
            <template #default="{ row }">{{ row.referenceAnswer || '-' }}</template>
          </el-table-column>
        </el-table>
        <div style="font-size:12px;color:var(--text-mute)">点击行导入该条数据，匹配的占位符字段自动填充</div>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { promptApi, modelApi, playgroundApi, datasetApi } from '../../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { evaluatorTemplates, templateCategories, templateToDimConfig } from '../../data/evaluatorTemplates'

export default {
  name: 'PromptEditor',
  setup() {
    const route = useRoute()
    const router = useRouter()
    const loading = ref(true)
    const saving = ref(false)
    const editingId = computed(() => route.params.id || null)

    // ===== 表单数据 =====
    const form = ref({ name: '', description: '', promptTemplate: '', dimensionsConfig: '', evaluationMode: 'quality' })
    const editMode = ref('structured')

    const createDimConfig = () => ({ role: '', context_template: '', dimensions: [], badcase_rule: 'any', extra_instructions: '', enable_cot: false, few_shots: [] })
    const dimConfig = reactive(createDimConfig())

    // ===== 模板 =====
    const tplCat = ref('all')
    const filteredTemplates = computed(() => tplCat.value === 'all' ? evaluatorTemplates : evaluatorTemplates.filter(t => t.category === tplCat.value))

    const applyTemplate = (t) => {
      const cfg = templateToDimConfig(t)
      cfg.dimensions.forEach(d => { d.__uid = uid() })
      // 将 context_template 转换为 contextMappings
      const mappings = []
      if (cfg.context_template) {
        const re = /\$\{([a-zA-Z_][a-zA-Z0-9_:]*)\}|\{([a-zA-Z_][a-zA-Z0-9_:]*)\}/g
        let m; while ((m = re.exec(cfg.context_template)) !== null) {
          const name = m[1] || m[2]
          mappings.push({ varName: name, dbField: name })
        }
      }
      Object.assign(dimConfig, { role: cfg.role, context_template: cfg.context_template,
        dimensions: cfg.dimensions, badcase_rule: cfg.badcase_rule, extra_instructions: cfg.extra_instructions,
        enable_cot: false, few_shots: [] })
      form.value.name = t.name
      form.value.description = t.description
      form.value.evaluationMode = t.evaluationMode || 'quality'
      editMode.value = 'structured'
      refreshPreview()
    }

    // ===== Few-shot =====
    const addFewShot = () => { dimConfig.few_shots.push({ question: '', response: '', reference: '', expected_output: '' }) }

    // ===== 维度操作 =====
    const uid = () => Date.now() + '_' + Math.random().toString(36).slice(2, 8)
    const createDim = () => ({ name: '', scoring_type: 'score', badcase_threshold: '', rubric: [], enum_values_str: '', __uid: uid() })

    const addDimension = () => {
      const dim = createDim()
      applyPreset(dim)
      dimConfig.dimensions.push(dim)
    }
    const removeDim = (i) => { dimConfig.dimensions.splice(i, 1) }
    const moveDim = (i, dir) => {
      const to = i + dir; if (to < 0 || to >= dimConfig.dimensions.length) return
      const arr = dimConfig.dimensions; const t = arr[i]; arr[i] = arr[to]; arr[to] = t
    }
    const applyPreset = (dim) => {
      if (dim.scoring_type === 'score') {
        dim.enum_values_str = ''; dim.rubric = [{ level: '5', desc: '' }, { level: '3', desc: '' }, { level: '1', desc: '' }]; dim.badcase_threshold = '<3'
      } else if (dim.scoring_type === 'boolean') {
        dim.enum_values_str = ''; dim.rubric = [{ level: '采纳', desc: '' }, { level: '不采纳', desc: '' }]; dim.badcase_threshold = '=不采纳'
      } else if (dim.scoring_type === 'enum') {
        dim.enum_values_str = 'G/S/B'; dim.rubric = [{ level: 'G', desc: '' }, { level: 'S', desc: '' }, { level: 'B', desc: '' }]; dim.badcase_threshold = '=B'
      } else {
        dim.enum_values_str = ''; dim.rubric = []; dim.badcase_threshold = ''
      }
    }
    const phThresh = (dim) => dim.scoring_type === 'score' ? '<3' : dim.scoring_type === 'boolean' ? '=不采纳' : '=B'
    const hintThresh = (dim) => dim.scoring_type === 'score' ? '低于该分数判定为 badcase' : dim.scoring_type === 'boolean' ? 'AI 无法判断时可输出 unknown' : '等于该值判定为 badcase'

    // ===== 构建 dimensionsConfig JSON =====
    const buildDimensionsConfig = () => {
      return JSON.stringify({
        role: dimConfig.role, context_template: dimConfig.context_template,
        badcase_rule: dimConfig.badcase_rule, extra_instructions: dimConfig.extra_instructions,
        enable_cot: !!dimConfig.enable_cot,
        few_shots: (dimConfig.few_shots || []).filter(fs => fs.question && fs.response),
        dimensions: dimConfig.dimensions.filter(d => (d.name || '').trim()).map(d => {
          const def = { name: d.name, scoring_type: d.scoring_type, badcase_threshold: d.badcase_threshold,
            rubric: d.rubric.filter(r => r.level || r.desc).map(r => ({ level: r.level, desc: r.desc })) }
          if (d.scoring_type === 'enum' && d.enum_values_str) def.enum_values = d.enum_values_str.split('/').map(v => v.trim()).filter(Boolean)
          if (d.scoring_type === 'custom') def.enum_values = d.rubric.map(r => r.level).filter(Boolean)
          return def
        })
      })
    }

    // ===== 实时 Prompt 预览（防抖） =====
    const previewText = ref('')
    const previewLoading = ref(false)

    // 自动格式化 JSON 块：先尝试严格解析，失败则用视觉格式化
    const formatJsonBlock = (s) => {
      try { return JSON.stringify(JSON.parse(s), null, 2) } catch {}
      // 视觉格式化：在 { } [ ] , 前后换行，加缩进
      let out = '', indent = 0, inStr = false, esc = false
      for (let i = 0; i < s.length; i++) {
        const c = s[i]
        if (esc) { out += c; esc = false; continue }
        if (c === '\\' && inStr) { out += c; esc = true; continue }
        if (c === '"') { inStr = !inStr; out += c; continue }
        if (inStr) { out += c; continue }
        // 跳过原有空白/换行，避免和新增缩进叠加
        if (c === ' ' || c === '\n' || c === '\r' || c === '\t') continue
        if (c === '{' || c === '[') { indent++; out += c + '\n' + '  '.repeat(indent) }
        else if (c === '}' || c === ']') { indent--; out += '\n' + '  '.repeat(indent) + c }
        else if (c === ',') { out += c + '\n' + '  '.repeat(indent) }
        else if (c === ':') { out += ': ' }
        else { out += c }
      }
      return out.replace(/\n\s*\n/g, '\n').trim()
    }

    const formatPreviewText = (text) => {
      if (!text) return ''
      const result = []
      let i = 0
      while (i < text.length) {
        if (text[i] === '{') {
          let depth = 0, end = i
          for (let j = i; j < text.length; j++) {
            if (text[j] === '{') depth++
            else if (text[j] === '}') { depth--; if (depth === 0) { end = j; break } }
          }
          if (depth === 0) {
            result.push(formatJsonBlock(text.substring(i, end + 1)))
            i = end + 1; continue
          }
        }
        result.push(text[i]); i++
      }
      return result.join('')
    }
    const formattedPreview = computed(() => formatPreviewText(previewText.value))

    let previewTimer = null
    const refreshPreview = () => {
      clearTimeout(previewTimer)
      previewTimer = setTimeout(async () => {
        if (editMode.value === 'free') {
          previewText.value = form.value.promptTemplate || ''
          return
        }
        if (!dimConfig.dimensions.length) { previewText.value = ''; return }
        try {
          previewLoading.value = true
          const res = await promptApi.preview({ dimensionsConfig: buildDimensionsConfig() })
          previewText.value = res.data || ''
        } catch { previewText.value = '（预览生成失败）' }
        finally { previewLoading.value = false }
      }, 500)
    }
    watch([() => form.value.promptTemplate, () => dimConfig.role, () => dimConfig.context_template, () => dimConfig.extra_instructions, () => dimConfig.enable_cot, () => dimConfig.few_shots, editMode, () => dimConfig.dimensions], refreshPreview)
    watch(() => dimConfig.dimensions, refreshPreview, { deep: true })

    // ===== AI 识别为结构化规则 =====
    const parsing = ref(false)
    const handleParseToDim = async () => {
      if (!form.value.promptTemplate.trim()) { ElMessage.warning('请先编写评测 Prompt'); return }
      const modelId = polishModelId.value
      if (!modelId) { ElMessage.warning('请先在右上角配置中选择一个模型'); return }
      parsing.value = true
      try {
        const res = await promptApi.parseToDimensions(modelId, form.value.promptTemplate)
        const cfg = JSON.parse(res.data)
        Object.assign(dimConfig, { role: cfg.role || '', context_template: cfg.context_template || '',
          badcase_rule: cfg.badcase_rule || 'any', extra_instructions: cfg.extra_instructions || '',
          enable_cot: !!cfg.enable_cot,
          few_shots: (cfg.few_shots || []).map(fs => ({ question: fs.question, response: fs.response, reference: fs.reference, expected_output: fs.expected_output })),
          dimensions: (cfg.dimensions || []).map(d => ({
            name: d.name || '', scoring_type: d.scoring_type || 'score', badcase_threshold: d.badcase_threshold || '<3',
            rubric: (d.rubric || []).map(r => ({ level: r.level, desc: r.desc })),
            enum_values_str: d.enum_values ? d.enum_values.join('/') : '', __uid: uid()
          }))
        })
        editMode.value = 'structured'
        ElMessage.success(`识别成功，生成 ${dimConfig.dimensions.length} 个维度`)
      } catch (e) { ElMessage.error(e.response?.data?.message || '识别失败') }
      finally { parsing.value = false }
    }

    // ===== AI 润色 =====
    const polishModelId = ref(null)
    const polishModels = ref([])
    const polishing = ref(false)
    const loadPolishModels = async () => {
      try {
        const res = await modelApi.list(1, 100)
        polishModels.value = res.data.records.filter(m => m.status === 1)
        const ds = polishModels.value.find(m => /deepseek/i.test(m.name || '') || /deepseek/i.test(m.modelId || ''))
        polishModelId.value = ds?.id || polishModels.value[0]?.id || null
      } catch {}
    }

    // ===== 路由 =====
    const goBack = () => router.push('/prompt')

    // ===== 保存 =====
    const handleSave = async () => {
      if (!form.value.name?.trim()) { ElMessage.warning('请填写评估器名称'); return }
      if (editMode.value === 'structured') {
        const validDims = dimConfig.dimensions.filter(d => d.name?.trim())
        if (!validDims.length) { ElMessage.warning('请至少添加一个评分维度'); return }
        for (const d of validDims) { if (!d.badcase_threshold) { ElMessage.warning(`维度"${d.name}"需要填写 badcase 阈值`); return } }
        form.value.dimensionsConfig = buildDimensionsConfig()
        form.value.promptTemplate = ''
      } else {
        if (!form.value.promptTemplate) { ElMessage.warning('请填写评估 Prompt'); return }
        form.value.dimensionsConfig = ''
      }
      saving.value = true
      try {
        if (editingId.value) { await promptApi.update(editingId.value, form.value); ElMessage.success('更新成功') }
        else { await promptApi.add(form.value); ElMessage.success('添加成功') }
        router.push('/prompt')
      } catch (e) { ElMessage.error(e.response?.data?.message || '保存失败') }
      finally { saving.value = false }
    }

    // ===== Playground =====
    const showPlayground = ref(false)
    const pgModelId = ref(null)
    const pgModels = ref([])
    const pgPromptTemplate = ref('')
    const pgFields = ref({})
    const pgRunning = ref(false)
    const pgResult = ref(null)
    const pgView = ref('editor')
    const pgRenderedPrompt = ref('')
    const pgPlaceholders = computed(() => {
      const re = /\$\{([a-zA-Z_][a-zA-Z0-9_:]*)\}|\{([a-zA-Z_][a-zA-Z0-9_:]*)\}/g
      const s = new Set(); let m; while ((m = re.exec(pgPromptTemplate.value)) !== null) s.add(m[1] || m[2]); return [...s]
    })
    watch(pgPlaceholders, (ps) => { const f = {}; for (const p of ps) f[p] = pgFields.value[p] || ''; pgFields.value = f }, { immediate: true })

    const canTest = computed(() => {
      if (editMode.value === 'free') return !!form.value.promptTemplate?.trim()
      return dimConfig.dimensions.length > 0
    })
    const openPlayground = async () => {
      let promptText = ''
      if (editMode.value === 'structured') {
        if (!dimConfig.dimensions.length) { ElMessage.warning('请先添加评分维度'); return }
        try { const res = await promptApi.preview({ dimensionsConfig: buildDimensionsConfig() }); promptText = res.data } catch { ElMessage.error('生成 Prompt 失败'); return }
      } else { promptText = form.value.promptTemplate }
      pgPromptTemplate.value = promptText; pgResult.value = null; pgFields.value = {}; pgView.value = 'editor'; pgRenderedPrompt.value = ''
      if (!pgModels.value.length) { try { const res = await modelApi.list(1, 100); pgModels.value = res.data.records.filter(m => m.status === 1); if (pgModels.value.length) pgModelId.value = pgModels.value[0].id } catch {} }
      showPlayground.value = true
    }
    const runPlayground = async () => {
      if (!pgModelId.value || !pgPromptTemplate.value) { ElMessage.warning('请选择模型并填写 Prompt'); return }
      pgRunning.value = true; pgResult.value = null
      try {
        const res = await playgroundApi.run({ modelConfigId: pgModelId.value, promptTemplate: pgPromptTemplate.value, fields: { ...pgFields.value } })
        pgResult.value = res.data; pgRenderedPrompt.value = res.data.prompt; pgView.value = 'rendered'
      } catch (e) { ElMessage.error(e.response?.data?.message || '调用失败') }
      pgRunning.value = false
    }
    const formatJson = (t) => { try { return JSON.stringify(JSON.parse(t), null, 2) } catch { return t } }

    // ===== 数据集导入 =====
    const showImportDialog = ref(false)
    const importDatasets = ref([])
    const importDatasetId = ref(null)
    const importItems = ref([])
    const importLoading = ref(false)
    const openImportDialog = () => { showImportDialog.value = true; if (!importDatasets.value.length) { datasetApi.list(1, 100).then(r => { importDatasets.value = r.data.records || []; if (importDatasets.value.length) { importDatasetId.value = importDatasets.value[0].id; loadImportItems() } }).catch(() => {}) } }
    const loadImportItems = async () => { if (!importDatasetId.value) return; importLoading.value = true; try { const r = await datasetApi.listItems(importDatasetId.value, 1, 200); importItems.value = r.data.records || r.data || [] } catch {} importLoading.value = false }
    const IMPORT_ALIASES = { question: ['question', 'query', 'q', '问题'], reference_answer: ['reference_answer', 'reference', '参考答案'], context: ['context', '上下文'], category: ['category', '类别'], model_response: ['model_response', 'response', 'answer', 'output'] }
    const normK = (k) => String(k).toLowerCase().replace(/[\s_-]+/g, '')
    const onPickImportItem = (item) => {
      if (!item) return
      const pool = []
      const push = (k, v) => { if (v != null) pool.push({ keys: [k, normK(k)], val: String(v) }) }
      push('question', item.question); push('reference_answer', item.referenceAnswer); push('context', item.context); push('category', item.category)
      if (item.extraFields) try { for (const [k, v] of Object.entries(JSON.parse(item.extraFields))) push(k, v) } catch {}
      const next = { ...pgFields.value }; let filled = 0
      for (const ph of pgPlaceholders.value) {
        let hit = pool.find(p => p.keys.some(k => normK(k) === normK(ph)))
        if (!hit) for (const [sem, als] of Object.entries(IMPORT_ALIASES)) { if (normK(sem) === normK(ph) || als.map(normK).includes(normK(ph))) { hit = pool.find(p => p.keys.some(k => als.some(a => normK(a) === normK(k)) || normK(k) === normK(sem))); if (hit) break } }
        if (hit) { next[ph] = hit.val; filled++ }
      }
      pgFields.value = next; showImportDialog.value = false; ElMessage.success(`已填充 ${filled} 个字段`)
    }

    // ===== 初始化 =====
    const init = async () => {
      loading.value = true
      await loadPolishModels()
      if (editingId.value) {
        try {
          const res = await promptApi.getById(editingId.value)
          const d = res.data
          form.value = { name: d.name, description: d.description || '', promptTemplate: d.promptTemplate || '', dimensionsConfig: d.dimensionsConfig || '', evaluationMode: d.evaluationMode || 'quality' }
          if (d.dimensionsConfig) {
            editMode.value = 'structured'
            try {
              const p = JSON.parse(d.dimensionsConfig)
              Object.assign(dimConfig, { role: p.role || '', context_template: p.context_template || '',
                dimensions: (p.dimensions || []).map(d => ({ ...d, enum_values_str: d.enum_values ? d.enum_values.join('/') : '', __uid: uid() })),
                badcase_rule: p.badcase_rule || 'any', extra_instructions: p.extra_instructions || '',
                enable_cot: !!p.enable_cot,
                few_shots: (p.few_shots || []).map(fs => ({ question: fs.question || '', response: fs.response || '', reference: fs.reference || '', expected_output: fs.expected_output || '' })) })
            } catch { Object.assign(dimConfig, createDimConfig()) }
          } else {
            editMode.value = 'free'
            Object.assign(dimConfig, createDimConfig())
          }
        } catch { ElMessage.error('加载评估器失败') }
      } else {
        Object.assign(dimConfig, createDimConfig())
      }
      loading.value = false
      refreshPreview()
    }
    onMounted(init)

    return {
      loading, saving, editingId, form, editMode, dimConfig,
      // 模板
      tplCat, filteredTemplates, applyTemplate,
      // Few-shot
      addFewShot,
      // 维度
      addDimension, removeDim, moveDim, applyPreset, phThresh, hintThresh,
      // 预览
      previewText, formattedPreview, previewLoading, refreshPreview,
      // AI
      parsing, handleParseToDim, polishModelId, polishing,
      // 路由
      goBack, handleSave,
      // Playground
      showPlayground, pgModelId, pgModels, pgPromptTemplate, pgFields, pgPlaceholders,
      pgRunning, pgResult, pgView, pgRenderedPrompt, canTest, openPlayground, runPlayground, formatJson,
      // 数据集导入
      showImportDialog, importDatasets, importDatasetId, importItems, importLoading,
      openImportDialog, loadImportItems, onPickImportItem,
      templateCategories
    }
  }
}
</script>

<style scoped>
.editor-page { height: 100%; display: flex; flex-direction: column; background: #ffffff; border-radius: 12px; overflow: hidden; }

/* ===== 顶栏 ===== */
.topbar { display: flex; align-items: center; justify-content: space-between; padding: 0 20px; height: 54px; flex-shrink: 0; border-bottom: 1px solid #e5e7eb; background: #ffffff; }
.topbar-left { display: flex; align-items: center; gap: 10px; flex: 1; min-width: 0; }
.topbar-right { display: flex; align-items: center; gap: 8px; flex-shrink: 0; margin-left: 16px; }
.back-btn { font-size: 13px; color: #6b7280 !important; }
.topbar-sep { width: 1px; height: 20px; background: #e5e7eb; flex-shrink: 0; }
.topbar-field :deep(.el-input__wrapper) { box-shadow: none; border: 1px solid transparent; border-radius: 8px; background: #f3f4f6; transition: border-color .15s; }
.topbar-field :deep(.el-input__wrapper):hover { border-color: #d1d5db; }
.topbar-field :deep(.el-input__wrapper.is-focus) { border-color: var(--accent); box-shadow: 0 0 0 2px var(--accent-soft); }
.topbar-name { width: 200px; }
.topbar-name :deep(.el-input__inner) { font-weight: 600; font-size: 15px; }
.topbar-desc { width: 260px; }

/* ===== 主体 ===== */
.editor-body { flex: 1; display: flex; min-height: 0; overflow: hidden; background: #ffffff; }
.panel-left { flex: 1; min-width: 0; overflow-y: auto; padding: 16px 20px; display: flex; flex-direction: column; gap: 10px; background: #ffffff; }
.panel-right { width: 44%; min-width: 320px; border-left: 1px solid #e5e7eb; display: flex; flex-direction: column; padding: 16px; overflow: hidden; background: #ffffff; }

/* ===== 左栏配置区 ===== */
.cfg-section {
  background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 10px;
  padding: 12px 14px;
}
.free-prompt-section { flex: 1; display: flex; flex-direction: column; }
.free-prompt-section .free-textarea { flex: 1; display: flex; flex-direction: column; }
.free-prompt-section .free-textarea :deep(.el-textarea__inner) { flex: 1; min-height: 0; }
.cfg-row { display: flex; align-items: center; gap: 8px; }
.cfg-label {
  font-size: 13px; font-weight: 600; color: #111827;
  display: flex; align-items: center; gap: 6px; margin-bottom: 8px; flex-shrink: 0;
}
.cfg-label::before { content: ''; width: 3px; height: 14px; background: var(--accent); border-radius: 2px; flex-shrink: 0; }
.cfg-row .cfg-label { margin-bottom: 0; }
.cfg-hint { font-size: 11px; color: #9ca3af; margin-top: 6px; line-height: 1.6; }
.cfg-hint code { background: #ecfdf5; padding: 0 4px; border-radius: 3px; color: #059669; font-family: monospace; }
.cfg-hint.hint-active { color: #059669; font-weight: 500; }
.cfg-empty { text-align: center; padding: 24px 12px; color: #9ca3af; font-size: 13px; }
.cfg-mode-rg :deep(.el-radio-button__inner) { font-size: 12px; padding: 6px 14px; }

/* ===== 维度卡片 ===== */
.cfg-dims { display: flex; flex-direction: column; gap: 8px; }
.cfg-dim-actions { display: flex; gap: 6px; margin-left: auto; }

.dim-card {
  border: 1px solid #e5e7eb; border-radius: 10px; overflow: hidden;
  background: #ffffff;
}
.dim-head {
  display: flex; align-items: center; gap: 8px; padding: 8px 12px;
  background: #ffffff; border-bottom: 1px solid #e5e7eb;
}
.dim-idx {
  font-size: 12px; font-weight: 700; color: #059669; background: #ecfdf5;
  width: 28px; height: 22px; border-radius: 6px; display: inline-flex;
  align-items: center; justify-content: center; flex-shrink: 0;
}
.dim-name-input { flex: 1; }
.dim-head-actions { display: flex; gap: 0; flex-shrink: 0; }

.dim-body { padding: 10px 12px; display: flex; flex-direction: column; gap: 8px; background: #ffffff; }
.dim-row { display: flex; align-items: center; gap: 8px; }
.dim-row-label { font-size: 12px; font-weight: 500; color: #4b5563; white-space: nowrap; min-width: 70px; flex-shrink: 0; }
.dim-hint { font-size: 11px; color: #9ca3af; white-space: nowrap; }
.dim-thresh { width: 140px; }

/* 评分方式小按钮 */
.dim-body :deep(.el-radio-button__inner) { font-size: 11px; padding: 4px 10px; border-radius: 4px !important; }
.dim-body :deep(.el-radio-button:not(:first-child) .el-radio-button__inner) { border-left: 1px solid #e5e7eb !important; }
.dim-body :deep(.el-radio-button.is-active .el-radio-button__inner) { background: var(--accent) !important; border-color: var(--accent) !important; color: #fff !important; }

/* 评分细则 */
.dim-rubric-wrap { display: flex; flex-direction: column; gap: 6px; }
.rubric-list { display: flex; flex-direction: column; gap: 5px; }
.rubric-row { display: flex; gap: 6px; align-items: center; }
.rubric-badge {
  width: 48px; height: 24px; border-radius: 6px; font-size: 11px; font-weight: 700;
  display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0;
  background: #ecfdf5; color: #059669; border: 1px solid rgba(5,150,105,0.15);
}

/* 判定规则 */
.rule-text { font-size: 13px; color: #4b5563; line-height: 1.6; margin: 0; }

/* ===== Few-shot ===== */
.fewshot-card { border: 1px solid #e5e7eb; border-radius: 8px; margin-top: 8px; overflow: hidden; background: #ffffff; }
.fewshot-head { display: flex; align-items: center; justify-content: space-between; padding: 6px 10px; background: #f9fafb; border-bottom: 1px solid #e5e7eb; }
.fewshot-idx { font-size: 12px; font-weight: 600; color: #4b5563; }
.fewshot-fields { padding: 8px 10px; display: flex; flex-direction: column; gap: 6px; }
.fewshot-field { display: flex; align-items: flex-start; gap: 6px; }
.fewshot-field-label { font-size: 11px; font-weight: 600; color: #6b7280; min-width: 60px; padding-top: 5px; flex-shrink: 0; }

/* ===== 右栏预览 ===== */
.preview-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; flex-shrink: 0; }
.preview-head-right { display: flex; align-items: center; gap: 6px; }
.preview-title { font-size: 14px; font-weight: 600; color: #111827; }
.preview-box {
  flex: 1; min-height: 0; border: 1px solid #e5e7eb; border-radius: 10px;
  background: #f9fafb; overflow: hidden; display: flex;
}
.preview-pre {
  margin: 0; padding: 14px; font-size: 12px; line-height: 1.7; white-space: pre-wrap;
  word-break: break-word; overflow-y: auto; color: #1f2937; width: 100%;
  font-family: 'Consolas', 'SF Mono', Menlo, monospace;
}
.preview-empty {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 8px; color: #9ca3af; font-size: 13px; width: 100%;
}
.preview-vars {
  margin-top: 10px; padding: 8px 12px; background: #ffffff; border: 1px solid #e5e7eb;
  border-radius: 8px; font-size: 11px; color: #6b7280; flex-shrink: 0;
}
.preview-vars code { background: #ecfdf5; padding: 0 5px; border-radius: 3px; color: #059669; font-family: monospace; margin-right: 4px; }
.vars-label { font-weight: 600; color: #4b5563; margin-right: 6px; }

/* ===== 模板弹窗 ===== */
.tpl-popover { max-height: 360px; display: flex; flex-direction: column; }
.tpl-popover-title { font-size: 13px; font-weight: 600; margin-bottom: 8px; }
.tpl-list { display: flex; flex-direction: column; gap: 4px; overflow-y: auto; }
.tpl-item { padding: 8px 10px; border-radius: 8px; cursor: pointer; border: 1px solid transparent; transition: all .15s; }
.tpl-item:hover { background: #ecfdf5; border-color: #059669; }
.tpl-item-name { font-size: 13px; font-weight: 600; }
.tpl-item-desc { font-size: 11px; color: #9ca3af; line-height: 1.4; margin-top: 2px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }

/* ===== Playground 弹窗 ===== */
.pg-dialog :deep(.el-dialog__header) { padding: 14px 20px 10px; border-bottom: 1px solid #e5e7eb; margin-right: 0; }
.pg-dialog :deep(.el-dialog__body) { padding: 0; overflow: hidden; }
.pg-toolbar { display: flex; align-items: center; justify-content: space-between; padding: 10px 16px; background: #f9fafb; border-bottom: 1px solid #e5e7eb; }
.pg-toolbar-left { display: flex; align-items: center; gap: 8px; }
.pg-toolbar-label { font-size: 13px; font-weight: 600; color: #4b5563; }
.pg-body { display: flex; height: 56vh; min-height: 400px; }
.pg-col { display: flex; flex-direction: column; gap: 8px; padding: 14px; box-sizing: border-box; }
.pg-prompt-col { flex: 1; min-width: 0; }
.pg-divider { width: 1px; background: #e5e7eb; flex-shrink: 0; }
.pg-side-col { width: 320px; flex-shrink: 0; gap: 0; }
.pg-section { display: flex; flex-direction: column; gap: 8px; }
.pg-side-col .pg-section:first-child { flex: 1; min-height: 0; overflow: hidden; }
.pg-section-head { display: flex; align-items: center; justify-content: space-between; min-height: 22px; }
.pg-label { font-size: 13px; font-weight: 600; color: #4b5563; }
.pg-meta { font-size: 11px; color: #9ca3af; }
.pg-switch { display: flex; gap: 2px; padding: 2px; background: #f3f4f6; border: 1px solid #e5e7eb; border-radius: 6px; }
.pg-switch-item { padding: 2px 10px; font-size: 11px; border-radius: 4px; color: #9ca3af; cursor: pointer; transition: all .15s; user-select: none; }
.pg-switch-item.active { background: var(--accent); color: #fff; font-weight: 600; }
.pg-textarea { width: 100%; flex: 1; min-height: 0; resize: none; padding: 10px 12px; font-family: 'Consolas', 'SF Mono', Menlo, monospace; font-size: 12px; line-height: 1.6; border: 1px solid #e5e7eb; border-radius: 8px; background: #ffffff; color: #1f2937; outline: none; box-sizing: border-box; }
.pg-textarea:focus { border-color: var(--accent); }
.pg-rendered-wrap { flex: 1; min-height: 0; overflow-y: auto; border: 1px solid #e5e7eb; border-radius: 8px; background: #ffffff; }
.pg-rendered { margin: 0; padding: 12px; font-family: monospace; font-size: 12px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; }
.pg-rendered-empty { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; height: 100%; color: #9ca3af; font-size: 12px; }
.pg-fields-wrap { display: flex; flex-direction: column; gap: 6px; overflow-y: auto; flex: 1; min-height: 0; }
.pg-field { display: flex; flex-direction: column; gap: 3px; padding: 6px 8px; background: #ffffff; border: 1px solid #e5e7eb; border-radius: 6px; }
.pg-field:focus-within { border-color: var(--accent); }
.pg-field-name { font-size: 11px; font-weight: 600; color: #059669; font-family: monospace; }
.pg-field-input { width: 100%; padding: 4px 6px; font-size: 12px; border: 1px solid transparent; border-radius: 4px; background: #f9fafb; color: #1f2937; outline: none; font-family: inherit; box-sizing: border-box; }
.pg-field-input:focus { border-color: var(--accent); }
.pg-empty-tip { padding: 12px; border: 1px dashed #d1d5db; border-radius: 8px; font-size: 12px; color: #9ca3af; background: #ffffff; }
.pg-empty-tip code { background: #ecfdf5; padding: 0 4px; border-radius: 3px; color: #059669; }
.pg-output-section { flex: 0 0 220px; min-height: 0; }
.pg-response-box { flex: 1; min-height: 140px; padding: 12px; background: #ffffff; border: 1px solid #e5e7eb; border-radius: 8px; overflow-y: auto; display: flex; align-items: flex-start; box-sizing: border-box; }
.pg-response-text { font-family: monospace; font-size: 12px; line-height: 1.7; color: #1f2937; white-space: pre-wrap; word-break: break-word; width: 100%; }
.pg-response-empty { display: flex; align-items: center; justify-content: center; gap: 8px; width: 100%; height: 100%; color: #9ca3af; font-size: 12px; flex-direction: column; }
</style>
