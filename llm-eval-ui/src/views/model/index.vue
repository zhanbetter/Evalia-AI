<template>
  <div class="page-card">
    <div class="page-header">
      <div>
        <h2>模型管理</h2>
        <p class="page-sub">配置裁判模型和被测模型的 API 连接</p>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="model-tabs" @tab-change="onTabChange">
      <el-tab-pane label="裁判模型" name="judge">
        <template #label>
          <span class="tab-label"><span class="tab-dot judge"></span>裁判模型</span>
        </template>
      </el-tab-pane>
      <el-tab-pane label="被测模型" name="evaluated">
        <template #label>
          <span class="tab-label"><span class="tab-dot evaluated"></span>被测模型</span>
        </template>
      </el-tab-pane>
    </el-tabs>

    <!-- 统计卡片行 -->
    <div class="stats-row">
      <div class="stat-card">
        <div class="stat-icon" :class="activeTab"><el-icon :size="18"><Cpu /></el-icon></div>
        <div><div class="stat-num">{{ total }}</div><div class="stat-label">模型总数</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon green"><el-icon :size="18"><CircleCheck /></el-icon></div>
        <div><div class="stat-num">{{ enabledCount }}</div><div class="stat-label">已启用</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon orange"><el-icon :size="18"><Warning /></el-icon></div>
        <div><div class="stat-num">{{ disabledCount }}</div><div class="stat-label">已禁用</div></div>
      </div>
      <el-button type="primary" @click="openAdd" style="margin-left:auto;align-self:center">
        <el-icon style="margin-right:4px"><Plus /></el-icon> 添加{{ activeTab === 'judge' ? '裁判' : '被测' }}模型
      </el-button>
    </div>

    <!-- 模型卡片网格 -->
    <div class="model-grid" v-loading="loading">
      <div v-for="m in models" :key="m.id" class="model-card" :class="'model-type-' + m.modelType">
        <div class="model-card-top">
          <h3 class="model-name">{{ m.name }}</h3>
          <el-tag size="small" :type="m.status === 1 ? 'success' : 'info'">
            {{ m.status === 1 ? '已启用' : '已禁用' }}
          </el-tag>
        </div>
        <p class="model-id">模型ID: {{ m.modelId }}</p>
        <div class="model-meta">
          <span class="meta-item">
            <span class="provider-tag" :class="m.provider">{{ providerLabel(m.provider) }}</span>
          </span>
          <span class="meta-item">
            <el-icon :size="12"><Document /></el-icon>
            {{ m.apiBase }}
          </span>
        </div>
        <div class="model-card-footer">
          <el-button size="small" type="primary" plain @click="testModel(m)" :loading="testingId === m.id">
            测试连接
          </el-button>
          <div class="model-card-actions">
            <el-tooltip content="编辑" placement="top">
              <el-button size="small" circle @click="openEdit(m)"><el-icon><Edit /></el-icon></el-button>
            </el-tooltip>
            <el-tooltip content="删除" placement="top">
              <el-button size="small" circle type="danger" plain @click="handleDelete(m)"><el-icon><Delete /></el-icon></el-button>
            </el-tooltip>
          </div>
        </div>
      </div>

      <div v-if="!models.length && !loading" class="model-empty">
        <el-empty :description="'还没有' + (activeTab === 'judge' ? '裁判' : '被测') + '模型，点击添加'" />
      </div>
    </div>

    <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
      layout="total, prev, pager, next" @current-change="loadModels" />

    <!-- 添加/编辑弹窗 -->
    <el-dialog v-model="showDialog" :title="editingId ? '编辑模型' : '添加' + (activeTab === 'judge' ? '裁判' : '被测') + '模型'" width="560px" :close-on-click-modal="false">
      <el-form :model="form" label-width="100px" :rules="rules" ref="formRef">
        <el-form-item label="模型名称" prop="name">
          <el-input v-model="form.name" placeholder="如 DeepSeek-V3、GPT-4o" />
        </el-form-item>
        <el-form-item label="提供商" prop="provider">
          <el-select v-model="form.provider" placeholder="选择提供商" filterable style="width: 100%">
            <template v-if="activeTab === 'judge'">
              <el-option label="DeepSeek" value="deepseek" />
              <el-option label="智谱" value="zhipu" />
              <el-option label="通义" value="tongyi" />
              <el-option label="OpenAI" value="openai" />
              <el-option label="其他" value="other" />
            </template>
            <template v-else>
              <el-option label="OpenAI" value="openai" />
              <el-option label="智谱" value="zhipu" />
              <el-option label="通义" value="tongyi" />
              <el-option label="其他" value="other" />
            </template>
          </el-select>
        </el-form-item>
        <el-form-item label="API 地址" prop="apiBase">
          <el-input v-model="form.apiBase" :placeholder="activeTab === 'judge' ? 'https://api.deepseek.com/v1' : 'https://api.openai.com/v1'" />
        </el-form-item>
        <el-form-item label="API Key" prop="apiKey">
          <el-input v-model="form.apiKey" placeholder="sk-..." show-password />
        </el-form-item>
        <el-form-item label="模型 ID" prop="modelId">
          <el-input v-model="form.modelId" placeholder="如 deepseek-chat、gpt-4o" />
        </el-form-item>
        <el-form-item label="温度">
          <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" :precision="1" />
        </el-form-item>
        <el-form-item label="最大Token">
          <el-input-number v-model="form.maxTokens" :min="256" :max="128000" :step="256" />
        </el-form-item>
        <el-form-item label="启用状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showDialog = false">取消</el-button>
        <el-button type="primary" @click="handleSave" :loading="saving">{{ editingId ? '保存' : '添加' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import { ref, computed, onMounted } from 'vue'
import { modelApi } from '../../api'
import { ElMessage, ElMessageBox } from 'element-plus'

export default {
  name: 'ModelPage',
  setup() {
    const activeTab = ref('judge')
    const loading = ref(false)
    const models = ref([])
    const page = ref(1)
    const size = ref(10)
    const total = ref(0)

    const enabledCount = computed(() => models.value.filter(m => m.status === 1).length)
    const disabledCount = computed(() => models.value.filter(m => m.status !== 1).length)

    const showDialog = ref(false)
    const editingId = ref(null)
    const saving = ref(false)
    const testingId = ref(null)
    const formRef = ref(null)

    const defaultByTab = {
      judge: { name: '', provider: 'deepseek', apiBase: 'https://api.deepseek.com/v1', apiKey: '', modelId: 'deepseek-chat', modelType: 'judge', temperature: 0.7, maxTokens: 4096, status: 1 },
      evaluated: { name: '', provider: 'openai', apiBase: 'https://api.openai.com/v1', apiKey: '', modelId: '', modelType: 'evaluated', temperature: 0.7, maxTokens: 4096, status: 1 }
    }

    const form = ref({ ...defaultByTab.judge })

    const rules = {
      name: [{ required: true, message: '请输入模型名称', trigger: 'blur' }],
      provider: [{ required: true, message: '请选择提供商', trigger: 'change' }],
      apiBase: [{ required: true, message: '请输入 API 地址', trigger: 'blur' }],
      apiKey: [{ required: true, message: '请输入 API Key', trigger: 'blur' }],
      modelId: [{ required: true, message: '请输入模型 ID', trigger: 'blur' }]
    }

    const providerLabel = (p) => ({
      openai: 'OpenAI', deepseek: 'DeepSeek', zhipu: '智谱', tongyi: '通义', other: '其他'
    }[p] || p || '-')

    const onTabChange = () => {
      page.value = 1
      loadModels()
    }

    const loadModels = async () => {
      loading.value = true
      try {
        const res = await modelApi.list(page.value, size.value, activeTab.value)
        models.value = res.data.records || []
        total.value = res.data.total || 0
      } finally { loading.value = false }
    }

    const openAdd = () => {
      editingId.value = null
      form.value = { ...defaultByTab[activeTab.value] }
      showDialog.value = true
    }

    const openEdit = (m) => {
      editingId.value = m.id
      form.value = {
        name: m.name, provider: m.provider, apiBase: m.apiBase, apiKey: m.apiKey,
        modelId: m.modelId, modelType: m.modelType,
        temperature: m.temperature ?? 0.7, maxTokens: m.maxTokens ?? 4096,
        status: m.status ?? 1
      }
      showDialog.value = true
    }

    const handleSave = async () => {
      try { await formRef.value.validate() } catch { return }
      saving.value = true
      try {
        if (editingId.value) {
          await modelApi.update(editingId.value, form.value)
          ElMessage.success('模型已更新')
        } else {
          await modelApi.add(form.value)
          ElMessage.success('模型已添加')
        }
        showDialog.value = false
        loadModels()
      } catch (e) {
        ElMessage.error(e.response?.data?.message || e.message || '操作失败')
      } finally { saving.value = false }
    }

    const handleDelete = async (m) => {
      await ElMessageBox.confirm(`确定删除模型「${m.name}」？`, '确认删除', { type: 'warning' })
      await modelApi.delete(m.id)
      ElMessage.success('删除成功')
      loadModels()
    }

    const testModel = async (m) => {
      testingId.value = m.id
      try {
        const res = await modelApi.testConnection(m.id)
        ElMessage.success(res.data || '连接成功')
      } catch (e) {
        ElMessage.error(e.response?.data?.message || '连接失败')
      } finally { testingId.value = null }
    }

    onMounted(() => loadModels())

    return {
      activeTab, loading, models, page, size, total, enabledCount, disabledCount,
      showDialog, editingId, saving, testingId, form, rules, formRef,
      providerLabel, onTabChange, loadModels,
      openAdd, openEdit, handleSave, handleDelete, testModel
    }
  }
}
</script>

<style scoped>
.page-sub { color: var(--text-mute); font-size: 12px; margin-top: 2px; }

.model-tabs { margin-bottom: 14px; }
.tab-label { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; }
.tab-dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.tab-dot.judge { background: #3b82f6; }
.tab-dot.evaluated { background: #8b5cf6; }

.stats-row { display: flex; gap: 14px; margin-bottom: 18px; align-items: center; }
.stat-card {
  display: flex; align-items: center; gap: 12px;
  background: var(--bg-card); border: 1px solid var(--border);
  border-radius: 12px; padding: 14px 18px;
  box-shadow: var(--shadow-sm);
}
.stat-icon {
  width: 38px; height: 38px; border-radius: 10px;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.stat-icon.judge { background: rgba(59,130,246,0.12); color: #3b82f6; }
.stat-icon.evaluated { background: rgba(139,92,246,0.12); color: #8b5cf6; }
.stat-icon.green { background: var(--accent-soft); color: var(--accent); }
.stat-icon.orange { background: rgba(245,158,11,0.12); color: #f59e0b; }
.stat-num { font-size: 22px; font-weight: 700; color: var(--text-prime); line-height: 1.1; }
.stat-label { font-size: 11px; color: var(--text-mute); margin-top: 2px; }

.model-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 14px;
  align-content: start;
  overflow-y: auto;
  min-height: 0;
  flex: 1;
  padding-right: 4px;
}
.model-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  transition: all 0.2s;
  box-shadow: var(--shadow-sm);
}
.model-card.model-type-judge { border-left: 3px solid #3b82f6; }
.model-card.model-type-evaluated { border-left: 3px solid #8b5cf6; }
.model-card:hover { box-shadow: 0 4px 16px rgba(0,0,0,0.08); }

.model-card-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.model-name { font-size: 15px; font-weight: 600; color: var(--text-prime); margin: 0; }
.model-id { font-size: 12px; color: var(--text-mute); font-family: Menlo, monospace; margin-bottom: 10px; }
.model-meta { display: flex; flex-direction: column; gap: 4px; padding-top: 8px; border-top: 1px solid var(--border); }
.meta-item { display: flex; align-items: center; gap: 4px; font-size: 11px; color: var(--text-mute); }

.provider-tag {
  font-size: 10px; font-weight: 700; letter-spacing: 0.5px;
  padding: 2px 8px; border-radius: 4px;
  background: var(--accent-soft); color: var(--accent);
}
.provider-tag.openai { background: rgba(16,185,129,0.12); color: #10b981; }
.provider-tag.deepseek { background: rgba(99,102,241,0.12); color: #6366f1; }
.provider-tag.zhipu { background: rgba(245,158,11,0.12); color: #f59e0b; }
.provider-tag.tongyi { background: rgba(6,182,212,0.12); color: #06b6d4; }
.provider-tag.other { background: rgba(148,163,184,0.12); color: #94a3b8; }

.model-card-footer {
  display: flex; justify-content: space-between; align-items: center; gap: 4px; margin-top: 12px;
}
.model-card-actions { display: flex; gap: 4px; }
.model-card-footer .el-button { padding: 4px 8px; font-size: 11px; margin-left: 0; }
.model-empty { grid-column: 1 / -1; }
</style>
