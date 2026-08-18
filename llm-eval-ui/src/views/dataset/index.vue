<template>
  <div class="page-card">
    <div class="page-header">
      <div>
        <h2>数据集管理</h2>
        <p class="page-sub">上传评测数据集，管理版本与字段映射</p>
      </div>
      <el-button type="primary" @click="showUploadDialog = true">
        <el-icon style="margin-right:4px"><Upload /></el-icon> 上传数据集
      </el-button>
    </div>

    <!-- 统计卡片行 -->
    <div class="stats-row">
      <div class="stat-card">
        <div class="stat-icon green"><el-icon :size="18"><Files /></el-icon></div>
        <div><div class="stat-num">{{ total }}</div><div class="stat-label">数据集总数</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon blue"><el-icon :size="18"><Collection /></el-icon></div>
        <div><div class="stat-num">{{ totalSamples }}</div><div class="stat-label">累计样本</div></div>
      </div>
    </div>

    <!-- 数据集卡片网格 -->
    <div class="ds-grid" v-loading="loading">
      <div v-for="ds in datasets" :key="ds.id" class="ds-card" @click="goDetail(ds)">
        <div class="ds-card-top">
          <div class="ds-type">{{ ds.fileType }}</div>
          <el-tag size="small" type="success">v{{ ds.version }}</el-tag>
          <el-tag size="small" :type="ds.hasModelResponse === 1 ? 'success' : 'info'" style="margin-left:auto">
            {{ ds.hasModelResponse === 1 ? '含回答' : '只有问题' }}
          </el-tag>
        </div>
        <h3 class="ds-name">{{ ds.name }}</h3>
        <p class="ds-desc">{{ ds.description || '暂无描述' }}</p>
        <div class="ds-meta">
          <span><el-icon><Collection /></el-icon> {{ ds.totalCount }} 条</span>
          <span>{{ formatDate(ds.createdAt) }}</span>
        </div>
        <div class="ds-card-footer">
          <el-button size="small" type="primary" plain @click.stop="goDetail(ds)">详情</el-button>
          <div class="ds-card-actions">
            <el-tooltip content="提交新版本" placement="top">
              <el-button size="small" circle @click.stop="openNewVersion(ds)"><el-icon><Upload /></el-icon></el-button>
            </el-tooltip>
            <el-tooltip content="编辑" placement="top">
              <el-button size="small" circle @click.stop="openEdit(ds)"><el-icon><Edit /></el-icon></el-button>
            </el-tooltip>
            <el-tooltip content="删除" placement="top">
              <el-button size="small" circle type="danger" plain @click.stop="handleDelete(ds)"><el-icon><Delete /></el-icon></el-button>
            </el-tooltip>
          </div>
        </div>
      </div>
      <div v-if="!datasets.length && !loading" class="ds-empty">
        <el-empty description="还没有数据集，点击右上角上传" />
      </div>
    </div>

    <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
      layout="total, prev, pager, next" @current-change="loadDatasets" />

    <!-- 编辑数据集弹窗 -->
    <el-dialog v-model="showEditDialog" title="编辑数据集" width="480px">
      <el-form :model="editForm" label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="editForm.name" placeholder="请输入数据集名称" />
        </el-form-item>
        <el-form-item label="评测类型">
          <el-radio-group v-model="editForm.hasReference">
            <el-radio :label="1">含参考答案</el-radio>
            <el-radio :label="0">无参考答案（自由判断）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="模型结果">
          <el-radio-group v-model="editForm.hasModelResponse">
            <el-radio :label="1">已含模型回答</el-radio>
            <el-radio :label="0">只有问题</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editForm.description" type="textarea" :rows="3" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showEditDialog = false">取消</el-button>
        <el-button type="primary" @click="handleEditSave" :loading="editing">保存</el-button>
      </template>
    </el-dialog>

    <!-- 上传向导弹窗 -->
    <el-dialog v-model="showUploadDialog" title="上传数据集" width="900px" :close-on-click-modal="false">
      <!-- Step 1: 上传文件 -->
      <div v-if="uploadStep === 1">
        <el-form :model="uploadForm" label-width="80px">
          <el-form-item label="名称"><el-input v-model="uploadForm.name" placeholder="请输入数据集名称" /></el-form-item>
          <el-form-item label="评测类型">
            <el-radio-group v-model="uploadForm.hasReference">
              <el-radio :label="1">含参考答案（对照评测）</el-radio>
              <el-radio :label="0">无参考答案（自由判断）</el-radio>
            </el-radio-group>
            <div class="upload-tip">
              {{ uploadForm.hasReference === 1
                ? '数据含标准答案，AI 裁判对照答案判对错，适合有唯一答案的题（如数学、知识问答）'
                : '数据无标准答案，AI 按评分标准判好坏，适合主观/开放场景（如客服语气、RAG 忠实度）' }}
            </div>
          </el-form-item>
          <el-form-item label="模型结果">
            <el-radio-group v-model="uploadForm.hasModelResponse">
              <el-radio :label="1">已含模型回答</el-radio>
              <el-radio :label="0">只有问题</el-radio>
            </el-radio-group>
            <div class="upload-tip">
              {{ uploadForm.hasModelResponse === 1
                ? '数据集已存被测模型的回答，评测时直接读取，无需调用被测模型 API'
                : '数据集只有问题，评测时调用被测模型 API 生成回答（需要配置被测模型）' }}
            </div>
          </el-form-item>
          <el-form-item label="描述"><el-input v-model="uploadForm.description" type="textarea" :rows="2" placeholder="可选" /></el-form-item>
          <el-form-item label="文件">
            <el-upload ref="uploadRef" :auto-upload="false" :limit="1" :show-file-list="false" accept=".json,.csv,.xlsx,.xls" :on-change="handleFileChange" :on-exceed="handleFileExceed" :on-remove="() => uploadFile = null">
              <template #trigger>
                <div class="up-file-select">
                  <el-button>
                    <el-icon style="margin-right:4px"><Document /></el-icon>{{ uploadFile ? '重新选择' : '选择文件' }}
                  </el-button>
                  <span v-if="uploadFile" class="up-file-name"><el-icon><File /></el-icon> {{ uploadFile.name }}（{{ formatFileSize(uploadFile.size) }}）</span>
                  <span v-else class="up-file-hint">未选择文件</span>
                </div>
              </template>
              <template #tip><div class="el-upload__tip">支持 JSON / CSV / XLSX 格式</div></template>
            </el-upload>
          </el-form-item>
        </el-form>
        <div style="text-align:right"><el-button type="primary" @click="handlePreview" :loading="previewing">下一步：字段映射</el-button></div>
      </div>

      <!-- Step 2: 字段映射 -->
      <div v-if="uploadStep === 2">
        <el-alert title="请为每个字段选择角色，系统已自动推荐映射" type="info" :closable="false" show-icon style="margin-bottom: 16px" />
        <div v-if="previewData.previewRows && previewData.previewRows.length" style="margin-bottom: 16px; overflow-x: auto">
          <el-table :data="previewData.previewRows" stripe border size="small" max-height="200">
            <el-table-column v-for="col in previewData.columns" :key="col" :prop="col" :label="col" min-width="120" show-overflow-tooltip />
          </el-table>
        </div>
        <el-table :data="mappingFields" stripe border size="small">
          <el-table-column prop="fieldName" label="原始字段名" width="140" />
          <el-table-column label="显示名称" width="150">
            <template #default="{ row }"><el-input v-model="row.displayName" size="small" /></template>
          </el-table-column>
          <el-table-column label="角色" width="160">
            <template #default="{ row }">
              <el-select v-model="row.role" size="small">
                <el-option label="问题(QUESTION)" value="QUESTION" /><el-option label="参考答案(REFERENCE)" value="REFERENCE" />
                <el-option label="上下文(CONTEXT)" value="CONTEXT" /><el-option label="分类(CATEGORY)" value="CATEGORY" />
                <el-option label="自定义(CUSTOM)" value="CUSTOM" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="含义描述" min-width="150">
            <template #default="{ row }"><el-input v-model="row.description" size="small" placeholder="选填" /></template>
          </el-table-column>
          <el-table-column label="必填" width="60">
            <template #default="{ row }"><el-checkbox v-model="row.required" :true-label="1" :false-label="0" /></template>
          </el-table-column>
        </el-table>
        <div style="text-align:right; margin-top: 16px">
          <el-button @click="uploadStep = 1">上一步</el-button>
          <el-button type="primary" @click="handleUpload" :loading="uploading">确认上传</el-button>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { datasetApi } from '../../api'
import { ElMessage, ElMessageBox } from 'element-plus'

export default {
  name: 'DatasetPage',
  setup() {
    const router = useRouter()
    const loading = ref(false)
    const datasets = ref([])
    const allDatasets = ref([])
    const page = ref(1)
    const size = ref(10)
    const total = ref(0)

    const totalSamples = computed(() => allDatasets.value.reduce((a, d) => a + (d.totalCount || 0), 0))
    const formatDate = (dt) => {
      if (!dt) return '-'
      const d = new Date(dt)
      return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`
    }

    const showUploadDialog = ref(false)
    const uploadStep = ref(1)
    const uploading = ref(false)
    const previewing = ref(false)
    const uploadForm = ref({ name: '', description: '', hasReference: 1, hasModelResponse: 0 })
    const uploadFile = ref(null)
    const uploadRef = ref(null)
    const previewData = ref({})
    const mappingFields = ref([])
    const showEditDialog = ref(false)
    const editing = ref(false)
    const editForm = ref({ id: null, name: '', description: '' })

    const openEdit = (row) => {
      editForm.value = {
        id: row.id,
        name: row.name,
        description: row.description || '',
        hasReference: row.hasReference !== undefined && row.hasReference !== null ? row.hasReference : 1,
        hasModelResponse: row.hasModelResponse !== undefined && row.hasModelResponse !== null ? row.hasModelResponse : 0
      }
      showEditDialog.value = true
    }

    // 提交新版本：打开上传弹窗并预填名称/模式
    const openNewVersion = (row) => {
      uploadForm.value = {
        name: row.name,
        description: row.description || '',
        hasReference: row.hasReference !== undefined && row.hasReference !== null ? row.hasReference : 1,
        hasModelResponse: row.hasModelResponse !== undefined && row.hasModelResponse !== null ? row.hasModelResponse : 0
      }
      uploadStep.value = 1
      uploadFile.value = null
      previewData.value = {}
      mappingFields.value = []
      showUploadDialog.value = true
    }

    const handleEditSave = async () => {
      if (!editForm.value.name.trim()) { ElMessage.warning('请输入数据集名称'); return }
      editing.value = true
      try {
        await datasetApi.updateInfo(editForm.value.id, {
          name: editForm.value.name,
          description: editForm.value.description,
          hasReference: editForm.value.hasReference,
          hasModelResponse: editForm.value.hasModelResponse
        })
        ElMessage.success('数据集已更新')
        showEditDialog.value = false
        loadDatasets()
      } catch (e) {
        ElMessage.error(e.response?.data?.message || e.message || '更新失败')
      } finally {
        editing.value = false
      }
    }

    const loadDatasets = async () => {
      loading.value = true
      try {
        const res = await datasetApi.list(page.value, size.value)
        datasets.value = res.data.records
        total.value = res.data.total
        // 额外拉全量数据用于统计（累计样本、支持格式不受分页影响）
        const allRes = await datasetApi.list(1, 1000)
        allDatasets.value = allRes.data.records || []
      } finally { loading.value = false }
    }

    const goDetail = (row) => { router.push(`/dataset/${row.id}`) }

    const handleFileChange = (file) => { uploadFile.value = file.raw }

    // limit=1 时重新选择会触发 on-exceed，这里替换旧文件
    const handleFileExceed = (files) => {
      if (files && files.length) {
        uploadFile.value = files[0]
        // 同步清空 el-upload 内部状态，让下次还能选
        if (uploadRef.value) uploadRef.value.clearFiles()
        uploadRef.value.handleStart(files[0])
      }
    }

    const formatFileSize = (bytes) => {
      if (!bytes) return ''
      if (bytes < 1024) return bytes + ' B'
      if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
      return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
    }

    const handlePreview = async () => {
      if (!uploadForm.value.name) { ElMessage.warning('请输入数据集名称'); return }
      if (!uploadFile.value) { ElMessage.warning('请选择文件'); return }
      previewing.value = true
      try {
        const fd = new FormData()
        fd.append('file', uploadFile.value)
        const res = await datasetApi.previewFile(fd)
        previewData.value = res.data
        mappingFields.value = (res.data.suggestedMapping || []).map(m => ({
          fieldName: m.fieldName, displayName: m.displayName || m.fieldName,
          fieldType: m.fieldType || 'TEXT', role: m.role || 'CUSTOM',
          description: m.description || '', required: m.required || 0, sortOrder: m.sortOrder || 0
        }))
        uploadStep.value = 2
      } finally { previewing.value = false }
    }

    const handleUpload = async () => {
      uploading.value = true
      try {
        const fd = new FormData()
        fd.append('file', uploadFile.value)
        fd.append('name', uploadForm.value.name)
        fd.append('description', uploadForm.value.description || '')
        fd.append('hasReference', uploadForm.value.hasReference)
        fd.append('hasModelResponse', uploadForm.value.hasModelResponse)
        fd.append('schemaFields', JSON.stringify(mappingFields.value))
        fd.append('columnMapping', JSON.stringify(
          mappingFields.value.reduce((m, f) => { m[f.fieldName] = f.role; return m }, {})
        ))
        await datasetApi.upload(fd)
        ElMessage.success('上传成功')
        showUploadDialog.value = false
        uploadStep.value = 1
        uploadForm.value = { name: '', description: '', hasReference: 1 }
        uploadFile.value = null
        loadDatasets()
      } finally { uploading.value = false }
    }

    const handleDelete = async (row) => {
      await ElMessageBox.confirm('确定删除该数据集？将同时删除所有条目和字段定义', '提示', { type: 'warning' })
      await datasetApi.delete(row.id)
      ElMessage.success('删除成功')
      loadDatasets()
    }

    onMounted(() => { loadDatasets() })

    return {
      loading, datasets, page, size, total, loadDatasets, goDetail,
      showUploadDialog, uploadStep, uploading, previewing,
      uploadForm, uploadFile, uploadRef, previewData, mappingFields,
      handleFileChange, handleFileExceed, handlePreview, handleUpload, handleDelete, openNewVersion,
      formatFileSize,
      totalSamples, formatDate,
      showEditDialog, editing, editForm, openEdit, handleEditSave
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
.stat-icon.blue { background: rgba(99,102,241,0.12); color: #6366f1; }
.stat-icon.orange { background: rgba(245,158,11,0.12); color: #f59e0b; }
.stat-num { font-size: 22px; font-weight: 700; color: var(--text-prime); line-height: 1.1; }
.stat-label { font-size: 11px; color: var(--text-mute); margin-top: 2px; }
.ds-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 14px;
  align-content: start;
  overflow-y: auto;
  min-height: 0;
  flex: 1;
  padding-right: 4px;
}
.ds-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 16px;
  cursor: pointer;
  transition: all 0.2s;
  box-shadow: var(--shadow-sm);
  display: flex;
  flex-direction: column;
}
.ds-card:hover {
  border-color: var(--accent);
  box-shadow: 0 4px 16px rgba(0,0,0,0.08);
}
.ds-card:active {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-soft);
}
.ds-card-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.ds-type {
  font-size: 10px; font-weight: 700; letter-spacing: 0.5px;
  color: var(--accent);
  background: var(--accent-soft);
  padding: 2px 8px; border-radius: 4px;
}
.ds-name { font-size: 15px; font-weight: 600; color: var(--text-prime); margin-bottom: 4px; }
.ds-desc { font-size: 12px; color: var(--text-mute); margin-bottom: 10px; flex: 1; }
.ds-meta {
  display: flex; justify-content: space-between;
  font-size: 11px; color: var(--text-mute);
  padding-top: 8px; border-top: 1px solid var(--border);
}
.ds-meta span { display: flex; align-items: center; gap: 4px; }
.ds-card-footer { display: flex; justify-content: space-between; align-items: center; gap: 4px; margin-top: 10px; flex-wrap: wrap; }
.ds-card-actions { display: flex; gap: 4px; }
.ds-card-actions .el-button { margin-left: 0; }
.ds-card-footer .el-button { padding: 4px 8px; font-size: 11px; margin-left: 0; }
.upload-tip { font-size: 12px; color: var(--text-mute); line-height: 1.5; margin-top: 4px; }

/* 上传文件选择 */
.up-file-select { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.up-file-name { display: inline-flex; align-items: center; gap: 4px; font-size: 12px; color: var(--accent); font-weight: 600; }
.up-file-hint { font-size: 12px; color: var(--text-mute); }
.ds-empty { grid-column: 1 / -1; }
</style>
