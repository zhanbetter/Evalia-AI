<template>
  <div class="page-card">
    <div class="page-header">
      <div>
        <h2>模型管理</h2>
        <p class="page-sub">配置 OpenAI 兼容接口的评测模型</p>
      </div>
      <el-button type="primary" @click="handleAdd">
        <el-icon style="margin-right:4px"><Plus /></el-icon> 添加{{ activeTab === 'judge' ? '裁判' : '被测' }}模型
      </el-button>
    </div>

    <!-- 模型类型 Tabs -->
    <el-tabs v-model="activeTab" class="model-tabs" @tab-change="onTabChange">
      <!-- 被测模型 Tab -->
      <el-tab-pane label="被测模型" name="evaluated">
        <div class="tab-tip">
          <el-icon><Cpu /></el-icon> 你的 AI 产品 API，评测时生成回答
        </div>
        <div class="model-grid" v-loading="loading">
          <div v-for="m in evaluatedModels" :key="m.id" class="model-card">
            <div class="model-card-head">
              <div class="model-avatar" :style="{ background: providerColor(m.provider).grad }">
                {{ m.name?.charAt(0) || 'M' }}
              </div>
              <div class="model-title">
                <div class="model-name">{{ m.name }}</div>
                <div class="model-provider">{{ providerLabel(m.provider) }}</div>
              </div>
              <span class="model-status" :class="m.status === 1 ? 'on' : 'off'">
                <span class="status-dot"></span>{{ m.status === 1 ? '启用' : '禁用' }}
              </span>
            </div>
            <div class="model-id">
              <span class="id-label">模型 ID</span>
              <code class="id-code">{{ m.modelId }}</code>
            </div>
            <div class="model-meta">
              <div class="meta-item"><span class="meta-label">温度</span><span class="meta-val">{{ m.temperature }}</span></div>
              <div class="meta-item"><span class="meta-label">最大Token</span><span class="meta-val">{{ m.maxTokens }}</span></div>
              <div class="meta-item"><span class="meta-label">提供商</span><span class="meta-val">{{ providerLabel(m.provider) }}</span></div>
            </div>
            <div class="model-api">
              <span class="id-label">API 地址</span>
              <div class="api-url" :title="m.apiBase">{{ m.apiBase }}</div>
            </div>
            <div class="model-card-footer">
              <el-button size="small" type="primary" plain @click="handleEdit(m)"><el-icon style="margin-right:3px"><Edit /></el-icon> 编辑</el-button>
              <el-button size="small" type="warning" plain @click="handleTest(m)" :loading="m._testing"><el-icon style="margin-right:3px"><Connection /></el-icon> 测试</el-button>
              <el-button size="small" type="danger" text @click="handleDelete(m)">删除</el-button>
            </div>
          </div>
          <div v-if="!evaluatedModels.length && !loading" class="model-empty">
            <el-empty description="还没有被测模型，点击右上角添加" />
          </div>
        </div>
        <div class="tab-footer">
          <el-pagination v-model:current-page="page" v-model:page-size="size" :total="evaluatedTotal"
            layout="total, prev, pager, next" @current-change="loadModels" />
        </div>
      </el-tab-pane>

      <!-- 裁判模型 Tab -->
      <el-tab-pane label="裁判模型" name="judge">
        <div class="tab-tip">
          <el-icon><MagicStick /></el-icon> 评测时判定回答好坏的 AI（如 GPT / DeepSeek）
        </div>
        <div class="model-grid" v-loading="loading">
          <div v-for="m in judgeModels" :key="m.id" class="model-card">
            <div class="model-card-head">
              <div class="model-avatar" :style="{ background: providerColor(m.provider).grad }">
                {{ m.name?.charAt(0) || 'M' }}
              </div>
              <div class="model-title">
                <div class="model-name">{{ m.name }}</div>
                <div class="model-provider">{{ providerLabel(m.provider) }}</div>
              </div>
              <span class="model-status" :class="m.status === 1 ? 'on' : 'off'">
                <span class="status-dot"></span>{{ m.status === 1 ? '启用' : '禁用' }}
              </span>
            </div>
            <div class="model-id">
              <span class="id-label">模型 ID</span>
              <code class="id-code">{{ m.modelId }}</code>
            </div>
            <div class="model-meta">
              <div class="meta-item"><span class="meta-label">温度</span><span class="meta-val">{{ m.temperature }}</span></div>
              <div class="meta-item"><span class="meta-label">最大Token</span><span class="meta-val">{{ m.maxTokens }}</span></div>
              <div class="meta-item"><span class="meta-label">提供商</span><span class="meta-val">{{ providerLabel(m.provider) }}</span></div>
            </div>
            <div class="model-api">
              <span class="id-label">API 地址</span>
              <div class="api-url" :title="m.apiBase">{{ m.apiBase }}</div>
            </div>
            <div class="model-card-footer">
              <el-button size="small" type="primary" plain @click="handleEdit(m)"><el-icon style="margin-right:3px"><Edit /></el-icon> 编辑</el-button>
              <el-button size="small" type="warning" plain @click="handleTest(m)" :loading="m._testing"><el-icon style="margin-right:3px"><Connection /></el-icon> 测试</el-button>
              <el-button size="small" type="danger" text @click="handleDelete(m)">删除</el-button>
            </div>
          </div>
          <div v-if="!judgeModels.length && !loading" class="model-empty">
            <el-empty description="还没有裁判模型，点击右上角添加" />
          </div>
        </div>
        <div class="tab-footer">
          <el-pagination v-model:current-page="page" v-model:page-size="size" :total="judgeTotal"
            layout="total, prev, pager, next" @current-change="loadModels" />
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 添加/编辑弹窗 -->
    <el-dialog v-model="showAddDialog" :title="editingId ? '编辑模型' : `添加${activeTab === 'judge' ? '裁判' : '被测'}模型`" width="550px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="名称">
          <el-input v-model="form.name" :placeholder="activeTab === 'judge' ? '如 DeepSeek-V3' : '如 我的问答产品'" />
        </el-form-item>
        <el-form-item label="提供商">
          <el-select v-model="form.provider" placeholder="选择提供商">
            <el-option label="OpenAI" value="openai" />
            <el-option label="DeepSeek" value="deepseek" />
            <el-option label="智谱" value="zhipu" />
            <el-option label="通义" value="tongyi" />
            <el-option label="其他" value="other" />
          </el-select>
        </el-form-item>
        <el-form-item label="API地址">
          <el-input v-model="form.apiBase" placeholder="如 https://api.deepseek.com" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" :placeholder="editingId ? '留空则保持原Key不变' : 'sk-...'" show-password />
        </el-form-item>
        <el-form-item label="模型ID">
          <el-input v-model="form.modelId" placeholder="如 deepseek-chat" />
        </el-form-item>
        <!-- 被测模型才需要温度/Token（生成回答），裁判模型不需要 -->
        <template v-if="form.modelType !== 'judge'">
          <el-form-item label="温度">
            <el-slider v-model="form.temperature" :min="0" :max="2" :step="0.1" show-input />
          </el-form-item>
          <el-form-item label="最大Token">
            <el-input-number v-model="form.maxTokens" :min="1" :max="32000" :step="256" />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="showAddDialog = false">取消</el-button>
        <el-button type="primary" @click="handleSave" :loading="saving">保存</el-button>
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
    const loading = ref(false)
    const models = ref([])
    const page = ref(1)
    const size = ref(10)
    const total = ref(0)
    const activeTab = ref('evaluated')

    const showAddDialog = ref(false)
    const saving = ref(false)
    const editingId = ref(null)
    const form = ref({
      name: '', provider: 'openai', apiBase: '', apiKey: '', modelId: '',
      modelType: 'evaluated', temperature: 0.7, maxTokens: 2048
    })

    // 按类型过滤（精确匹配，避免空值/其他值出现在两个Tab）
    const evaluatedModels = computed(() => models.value.filter(m => m.modelType === 'evaluated' || m.modelType === 'both'))
    const judgeModels = computed(() => models.value.filter(m => m.modelType === 'judge' || m.modelType === 'both'))
    const evaluatedTotal = computed(() => evaluatedModels.value.length)
    const judgeTotal = computed(() => judgeModels.value.length)

    const loadModels = async () => {
      loading.value = true
      try {
        const res = await modelApi.list(1, 100)
        models.value = res.data.records.map(m => ({ ...m, _testing: false }))
        total.value = res.data.total
      } finally {
        loading.value = false
      }
    }

    const handleAdd = () => {
      editingId.value = null
      form.value = {
        name: '', provider: 'openai', apiBase: '', apiKey: '', modelId: '',
        modelType: activeTab.value === 'judge' ? 'judge' : 'evaluated',
        temperature: 0.7, maxTokens: 2048
      }
      showAddDialog.value = true
    }

    const onTabChange = () => {
      page.value = 1
    }

    const providerLabel = (p) => ({ openai: 'OpenAI', deepseek: 'DeepSeek', zhipu: '智谱', tongyi: '通义', other: '其他' }[p] || p)
    const providerColor = (p) => {
      const map = {
        openai: { grad: 'linear-gradient(135deg,#10b981,#059669)' },
        deepseek: { grad: 'linear-gradient(135deg,#6366f1,#4f46e5)' },
        zhipu: { grad: 'linear-gradient(135deg,#f59e0b,#d97706)' },
        tongyi: { grad: 'linear-gradient(135deg,#06b6d4,#0891b2)' },
        other: { grad: 'linear-gradient(135deg,#64748b,#475569)' }
      }
      return map[p] || map.other
    }

    const handleEdit = (row) => {
      editingId.value = row.id
      form.value = {
        name: row.name, provider: row.provider, apiBase: row.apiBase,
        apiKey: row.apiKey, modelId: row.modelId,
        modelType: row.modelType || 'evaluated',
        temperature: row.temperature, maxTokens: row.maxTokens
      }
      showAddDialog.value = true
    }

    const modelTypeLabel = (t) => {
      const map = { evaluated: '被测', judge: '裁判', both: '两者' }
      return map[t] || '被测'
    }

    const handleSave = async () => {
      if (!form.value.name || !form.value.apiBase || !form.value.modelId) {
        ElMessage.warning('请填写必填项')
        return
      }
      if (!editingId.value && !form.value.apiKey) {
        ElMessage.warning('请填写API Key')
        return
      }
      saving.value = true
      try {
        if (editingId.value) {
          await modelApi.update(editingId.value, form.value)
          ElMessage.success('更新成功')
        } else {
          await modelApi.add(form.value)
          ElMessage.success('添加成功')
        }
        showAddDialog.value = false
        editingId.value = null
        form.value = { name: '', provider: 'openai', apiBase: '', apiKey: '', modelId: '', modelType: 'evaluated', temperature: 0.7, maxTokens: 2048 }
        loadModels()
      } finally {
        saving.value = false
      }
    }

    const handleTest = async (row) => {
      row._testing = true
      try {
        const res = await modelApi.testConnection(row.id)
        ElMessage.success(res.data)
      } catch (e) {
        // error handled by interceptor
      } finally {
        row._testing = false
      }
    }

    const handleDelete = async (row) => {
      await ElMessageBox.confirm('确定删除该模型配置？', '提示', { type: 'warning' })
      await modelApi.delete(row.id)
      ElMessage.success('删除成功')
      loadModels()
    }

    onMounted(() => {
      loadModels()
    })

    return {
      loading, models, page, size, total, loadModels,
      showAddDialog, saving, editingId, form,
      handleEdit, handleSave, handleTest, handleDelete,
      providerLabel, providerColor, modelTypeLabel,
      activeTab, onTabChange, handleAdd,
      evaluatedModels, judgeModels, evaluatedTotal, judgeTotal
    }
  }
}
</script>

<style scoped>
.page-sub { color: var(--text-mute); font-size: 12px; margin-top: 2px; }
.model-tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.model-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.model-tabs :deep(.el-tabs__content .el-tab-pane) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.tab-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-mute);
  margin-bottom: 12px;
  padding: 8px 12px;
  background: var(--bg-input);
  border-radius: 8px;
  flex-shrink: 0;
}
.tab-tip .el-icon { color: var(--accent); }
.tab-footer {
  flex-shrink: 0;
  margin-top: 14px;
}
.model-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 10px;
  align-content: start;
  overflow-y: auto;
  min-height: 0;
  flex: 1;
  padding-right: 4px;
}
.model-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 12px 14px;
  box-shadow: var(--shadow-xs);
  transition: all 0.2s;
}
.model-card:hover {
  border-color: var(--accent);
  box-shadow: 0 4px 12px rgba(0,0,0,0.08);
}
.model-card-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.model-type-tag {
  font-size: 10px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 20px;
  flex-shrink: 0;
}
.model-type-tag.evaluated { background: rgba(99,102,241,0.12); color: #6366f1; }
.model-type-tag.judge { background: var(--accent-soft); color: var(--accent); }
.model-type-tag.both { background: rgba(245,158,11,0.12); color: #f59e0b; }
.model-avatar {
  width: 30px; height: 30px; border-radius: 8px;
  display: flex; align-items: center; justify-content: center;
  color: #fff; font-weight: 700; font-size: 13px;
  flex-shrink: 0;
}
.model-title { flex: 1; min-width: 0; }
.model-name { font-size: 13px; font-weight: 600; color: var(--text-prime); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.model-provider { font-size: 10px; color: var(--text-mute); margin-top: 1px; }
.model-status {
  display: inline-flex; align-items: center; gap: 3px;
  font-size: 10px; font-weight: 500;
  padding: 1px 6px; border-radius: 20px;
  flex-shrink: 0;
}
.model-status .status-dot { width: 5px; height: 5px; border-radius: 50%; }
.model-status.on { background: var(--accent-soft); color: var(--accent); }
.model-status.on .status-dot { background: var(--accent); }
.model-status.off { background: rgba(148,163,184,0.12); color: var(--text-sec); }
.model-status.off .status-dot { background: var(--text-mute); }

.model-id {
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 5px 10px;
  margin-bottom: 8px;
}
.id-label { font-size: 9px; color: var(--text-mute); text-transform: uppercase; letter-spacing: 0.4px; display: block; margin-bottom: 1px; }
.id-code {
  font-size: 12px; font-weight: 600;
  color: var(--accent);
  font-family: 'SF Mono', Menlo, monospace;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  display: block;
}

.model-meta {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
  margin-bottom: 8px;
}
.meta-item {
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 5px 6px;
  text-align: center;
}
.meta-label { display: block; font-size: 9px; color: var(--text-mute); margin-bottom: 1px; }
.meta-val { font-size: 12px; font-weight: 600; color: var(--text-prime); }

.model-api {
  background: var(--bg-input);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 5px 10px;
  margin-bottom: 8px;
}
.api-url {
  font-size: 11px; color: var(--text-sec);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.model-card-footer { display: flex; align-items: center; gap: 2px; }
.model-card-footer .el-button:last-child { margin-left: auto; }
.model-card-footer :deep(.el-button) { padding: 4px 8px; font-size: 11px; }
.model-empty { grid-column: 1 / -1; }
</style>
