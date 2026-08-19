<template>
  <div class="dataset-detail">
    <div class="detail-header">
      <div class="header-left">
        <el-button size="small" @click="$router.push('/dataset')"><el-icon><ArrowLeft /></el-icon> 返回列表</el-button>
        <h2>{{ dataset.name }} <el-tag size="small" type="info">v{{ dataset.version }}</el-tag></h2>
        <p class="desc">{{ dataset.description || '暂无描述' }}</p>
      </div>
      <div class="header-stats">
        <div class="stat-item"><span class="stat-val">{{ dataset.totalCount || 0 }}</span><span class="stat-label">总条目</span></div>
        <div class="stat-item"><span class="stat-val">{{ dataset.fileType }}</span><span class="stat-label">格式</span></div>
        <div class="stat-item"><span class="stat-val">{{ schemaFields.length }}</span><span class="stat-label">字段数</span></div>
      </div>
    </div>

    <el-tabs v-model="activeTab" type="border-card">
      <!-- Schema Tab -->
      <el-tab-pane label="字段定义" name="schema">
        <div style="margin-bottom: 12px">
          <el-button type="primary" size="small" @click="showAddSchemaField = true">添加字段</el-button>
          <el-button size="small" @click="saveSchema" :loading="savingSchema">保存修改</el-button>
        </div>
        <el-table :data="schemaFields" stripe border size="small">
          <el-table-column prop="fieldName" label="原始字段名" width="140" />
          <el-table-column label="显示名称" width="140">
            <template #default="{ row }"><el-input v-model="row.displayName" size="small" /></template>
          </el-table-column>
          <el-table-column label="角色" width="150">
            <template #default="{ row }">
              <el-select v-model="row.role" size="small" :teleported="true" style="width: 100%">
                <el-option label="问题(QUESTION)" value="QUESTION" />
                <el-option label="参考答案(REFERENCE)" value="REFERENCE" />
                <el-option label="上下文(CONTEXT)" value="CONTEXT" />
                <el-option label="分类(CATEGORY)" value="CATEGORY" />
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
          <el-table-column label="操作" width="80">
            <template #default="{ $index }"><el-button size="small" type="danger" link @click="schemaFields.splice($index, 1)">删除</el-button></template>
          </el-table-column>
        </el-table>
        <el-dialog v-model="showAddSchemaField" title="添加字段" width="450px">
          <el-form :model="newField" label-width="80px">
            <el-form-item label="字段名"><el-input v-model="newField.fieldName" /></el-form-item>
            <el-form-item label="显示名"><el-input v-model="newField.displayName" /></el-form-item>
            <el-form-item label="角色"><el-select v-model="newField.role"><el-option label="问题(QUESTION)" value="QUESTION" /><el-option label="参考答案(REFERENCE)" value="REFERENCE" /><el-option label="上下文(CONTEXT)" value="CONTEXT" /><el-option label="分类(CATEGORY)" value="CATEGORY" /><el-option label="自定义(CUSTOM)" value="CUSTOM" /></el-select></el-form-item>
            <el-form-item label="描述"><el-input v-model="newField.description" /></el-form-item>
          </el-form>
          <template #footer><el-button @click="showAddSchemaField = false">取消</el-button><el-button type="primary" @click="addSchemaField">添加</el-button></template>
        </el-dialog>
      </el-tab-pane>

      <!-- 数据 Tab：根据 Schema 动态生成列 -->
      <el-tab-pane label="数据条目" name="data" class="data-tab-pane">
        <div class="data-tab-toolbar" style="margin-bottom: 14px; display: flex; align-items: center; gap: 12px">
          <el-button type="primary" size="small" @click="openAddItem">添加条目</el-button>
          <el-button size="small" type="warning" plain @click="showDuplicateDialog = true; dupDetectResult = null">
            <el-icon style="margin-right:4px"><Connection /></el-icon>重复检测
          </el-button>
          <el-input v-model="searchKeyword" placeholder="搜索..." size="small" style="width: 240px" clearable @clear="loadItems" @keyup.enter="loadItems" />
          <el-button size="small" type="primary" plain @click="loadItems">搜索</el-button>
        </div>
        <div class="data-tab-table">
          <el-table :data="items" stripe border size="small" v-loading="itemsLoading" style="width:100%" height="100%">
            <el-table-column prop="seqNo" label="#" width="72" fixed align="center">
              <template #default="{ row }">
                <span style="font-family: monospace; color: var(--text-sec);">{{ row.seqNo }}</span>
              </template>
            </el-table-column>
            <!-- 动态列：根据 schema 生成 -->
            <el-table-column v-for="sf in schemaFields" :key="sf.id" :label="sf.displayName || sf.fieldName" :min-width="getColWidth(sf)">
              <template #default="{ row }">
                <div class="cell-wrap">{{ getItemFieldValue(row, sf) }}</div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button size="small" link @click="editItem(row)">编辑</el-button>
                <el-button size="small" link type="danger" @click="deleteItem(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <el-pagination v-model:current-page="itemPage" v-model:page-size="itemSize" :total="itemTotal"
          layout="total, prev, pager, next" @current-change="loadItems" />

        <!-- 添加/编辑条目弹窗：根据 Schema 动态生成表单 -->
        <el-dialog v-model="showAddItem" :title="editingItem ? '编辑条目' : '添加条目'" width="700px">
          <el-form :model="itemForm" label-width="100px">
            <el-form-item v-for="sf in schemaFields" :key="sf.id" :label="sf.displayName || sf.fieldName" :required="sf.required === 1">
              <el-input v-if="sf.fieldType === 'TEXT'" v-model="itemForm[sf.fieldName]" type="textarea" :rows="2" :placeholder="sf.description || ''" />
              <el-input v-else v-model="itemForm[sf.fieldName]" :placeholder="sf.description || ''" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="showAddItem = false; editingItem = null">取消</el-button>
            <el-button type="primary" @click="saveItem" :loading="savingItem">保存</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>

      <!-- 版本 Tab -->
      <el-tab-pane label="版本历史" name="version">
        <div style="margin-bottom: 12px; display: flex; gap: 10px">
          <el-button type="primary" size="small" @click="showNewVersionDialog = true">提交新版本</el-button>
        </div>
        <el-table :data="versions" stripe size="small" v-loading="versionsLoading">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="version" label="版本号" width="80">
            <template #default="{ row }"><el-tag size="small" :type="row.id === dataset.id ? 'success' : 'info'">v{{ row.version }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="totalCount" label="条目数" width="90" />
          <el-table-column prop="fileType" label="格式" width="80" />
          <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
          <el-table-column prop="createdAt" label="创建时间" width="170" :formatter="formatTime" />
          <el-table-column label="操作" width="140">
            <template #default="{ row }">
              <el-button v-if="row.id !== dataset.id" size="small" link type="primary" @click="switchVersion(row)">切换</el-button>
              <el-tag v-else size="small" type="success">当前</el-tag>
            </template>
          </el-table-column>
        </el-table>

        <!-- 新版本弹窗 -->
        <el-dialog v-model="showNewVersionDialog" title="提交新版本" width="600px" :close-on-click-modal="false">
          <el-steps :active="newVersionStep" finish-status="success" simple style="margin-bottom: 20px">
            <el-step title="上传文件" />
            <el-step title="字段映射" />
            <el-step title="确认" />
          </el-steps>
          <!-- Step 1 -->
          <div v-if="newVersionStep === 0">
            <el-form label-width="80px">
              <el-form-item label="描述"><el-input v-model="newVersionDesc" type="textarea" :rows="2" placeholder="新版本说明（可选）" /></el-form-item>
              <el-form-item label="文件">
                <el-upload ref="nvUploadRef" :auto-upload="false" :limit="1" :show-file-list="false" accept=".json,.csv,.xlsx,.xls" :on-change="handleNvFileChange" :on-exceed="handleNvFileExceed" :on-remove="() => nvFile = null">
                  <template #trigger>
                    <div class="nv-file-select">
                      <el-button size="small">
                        <el-icon style="margin-right:4px"><Document /></el-icon>{{ nvFile ? '重新选择' : '选择文件' }}
                      </el-button>
                      <span v-if="nvFile" class="nv-file-name"><el-icon><File /></el-icon> {{ nvFile.name }}（{{ formatFileSize(nvFile.size) }}）</span>
                      <span v-else class="nv-file-hint">未选择文件</span>
                    </div>
                  </template>
                  <template #tip><div class="el-upload__tip">JSON / CSV / XLSX</div></template>
                </el-upload>
              </el-form-item>
            </el-form>
            <div style="text-align:right"><el-button type="primary" @click="previewNewVersion" :loading="nvPreviewing">下一步</el-button></div>
          </div>
          <!-- Step 2 -->
          <div v-if="newVersionStep === 1">
            <el-table :data="nvMappingFields" stripe border size="small">
              <el-table-column prop="fieldName" label="字段名" width="130" />
              <el-table-column label="显示名" width="130"><template #default="{ row }"><el-input v-model="row.displayName" size="small" /></template></el-table-column>
              <el-table-column label="角色" width="150"><template #default="{ row }"><el-select v-model="row.role" size="small"><el-option label="问题" value="QUESTION" /><el-option label="参考答案" value="REFERENCE" /><el-option label="上下文" value="CONTEXT" /><el-option label="分类" value="CATEGORY" /><el-option label="自定义" value="CUSTOM" /></el-select></template></el-table-column>
              <el-table-column label="描述" min-width="120"><template #default="{ row }"><el-input v-model="row.description" size="small" /></template></el-table-column>
            </el-table>
            <div style="text-align:right; margin-top: 16px">
              <el-button @click="newVersionStep = 0">上一步</el-button>
              <el-button type="primary" @click="submitNewVersion" :loading="nvUploading">提交新版本</el-button>
            </div>
          </div>
        </el-dialog>
      </el-tab-pane>

      <!-- 重复检测弹窗 -->
      <el-dialog v-model="showDuplicateDialog" title="重复检测" width="860px" :close-on-click-modal="false" top="5vh">
        <div class="dup-config">
          <div class="dup-config-row">
            <span class="dup-label">检测字段</span>
            <el-select v-model="dupFieldName" size="small" style="width: 200px">
              <el-option v-for="sf in schemaFields" :key="sf.fieldName" :label="sf.displayName || sf.fieldName" :value="dupFieldNameValue(sf)" :disabled="sf.role === 'CUSTOM'" />
            </el-select>
            <span class="dup-label" style="margin-left:20px">相似度阈值</span>
            <el-slider v-model="dupThreshold" :min="0.5" :max="1" :step="0.05" style="width: 200px; margin-left: 8px" :format-tooltip="v => v.toFixed(2)" />
            <span class="dup-thresh-val">{{ dupThreshold.toFixed(2) }}</span>
            <el-button type="primary" size="small" :loading="dupLoading" @click="runDuplicateDetect" style="margin-left: 16px">
              <el-icon style="margin-right:3px"><VideoPlay /></el-icon>开始检测
            </el-button>
          </div>
        </div>
        <div v-if="dupDetectResult" class="dup-result">
          <div class="dup-summary">
            <span>共检测 <b>{{ dupDetectResult.totalItems }}</b> 条数据</span>
            <span style="margin-left:16px">发现 <b style="color:var(--red)">{{ dupDetectResult.groups?.length || 0 }}</b> 组重复</span>
            <span style="margin-left:16px">涉及 <b style="color:var(--orange)">{{ dupDetectResult.duplicateCount || 0 }}</b> 条数据</span>
          </div>
          <div v-if="!dupDetectResult.groups?.length" class="dup-empty">未发现重复数据</div>
          <div v-else class="dup-groups">
            <div v-for="(group, gi) in dupDetectResult.groups" :key="gi" class="dup-group-card">
              <div class="dup-group-header">
                <span class="dup-group-title">重复组 {{ gi + 1 }}（{{ group.items.length }} 条，最高相似度 {{ (group.maxSimilarity * 100).toFixed(0) }}%）</span>
                <el-checkbox v-model="group._allSelected" :indeterminate="group._indeterminate" @change="(val) => toggleGroupAll(group, val)">全选删除</el-checkbox>
              </div>
              <div class="dup-group-items">
                <div v-for="(item, ii) in group.items" :key="item.id" class="dup-item-row" :class="{ 'dup-item-keep': ii === 0 && !item._selected }">
                  <el-checkbox v-model="item._selected" @change="updateGroupState(group)" />
                  <span class="dup-item-seq">#{{ item.seqNo }}</span>
                  <span class="dup-item-text">{{ item.fieldValue }}</span>
                  <span v-if="ii === 0" class="dup-item-badge keep">保留（代表）</span>
                  <span v-else class="dup-item-badge sim">{{ (item.similarity * 100).toFixed(0) }}% 相似</span>
                </div>
              </div>
            </div>
          </div>
        </div>
        <template #footer v-if="dupDetectResult?.groups?.length">
          <span class="dup-del-hint">已选 <b>{{ dupSelectedCount }}</b> 条待删除</span>
          <el-button @click="showDuplicateDialog = false">取消</el-button>
          <el-button type="danger" :loading="dupDeleting" :disabled="dupSelectedCount === 0" @click="batchDeleteDuplicates">
            <el-icon style="margin-right:3px"><Delete /></el-icon>删除选中（{{ dupSelectedCount }}）
          </el-button>
        </template>
      </el-dialog>

      <!-- 评测历史 Tab -->
      <el-tab-pane label="评测历史" name="eval-history">
        <div class="eval-history-hint" v-if="evalHistory.length">
          该数据集被以下任务评测过，点击任务名可查看对应结果分析
        </div>
        <el-table :data="evalHistory" stripe size="small" v-loading="evalHistoryLoading" empty-text="该数据集还没有被任何任务评测过">
          <el-table-column label="任务" min-width="200">
            <template #default="{ row }">
              <el-button link type="primary" @click="goEvalHistory(row.taskId)">{{ row.taskName }} <el-tag size="small" type="info">v{{ row.taskVersion }}</el-tag></el-button>
            </template>
          </el-table-column>
          <el-table-column prop="modelName" label="被测模型" width="150" show-overflow-tooltip />
          <el-table-column prop="promptName" label="评估器" width="150" show-overflow-tooltip />
          <el-table-column label="Badcase 率" width="140">
            <template #default="{ row }">
              <span v-if="row.badcaseRate !== null && row.badcaseRate !== undefined" :style="{ color: row.badcaseRate > 30 ? 'var(--red)' : row.badcaseRate > 15 ? 'var(--yellow)' : 'var(--accent)', fontWeight: 700 }">
                {{ row.badcaseRate.toFixed(1) }}%
              </span>
              <span v-else class="eval-na">-</span>
            </template>
          </el-table-column>
          <el-table-column label="Badcase" width="90">
            <template #default="{ row }"><span v-if="row.badcaseCount !== null">{{ row.badcaseCount }}</span><span v-else>-</span></template>
          </el-table-column>
          <el-table-column label="总样本" width="80">
            <template #default="{ row }"><span v-if="row.totalCount !== null">{{ row.totalCount }}</span><span v-else>-</span></template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <span :class="['eval-status', row.status?.toLowerCase()]">{{ statusLabel(row.status) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="评测时间" width="160" :formatter="formatTime" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { datasetApi } from '../../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'

export default {
  name: 'DatasetDetail',
  components: { ArrowLeft },
  setup() {
    const route = useRoute()
    const router = useRouter()
    const datasetId = ref(Number(route.params.id))
    const dataset = ref({})
    const activeTab = ref('data')

    // Schema
    const schemaFields = ref([])
    const savingSchema = ref(false)
    const showAddSchemaField = ref(false)
    const newField = ref({ fieldName: '', displayName: '', fieldType: 'TEXT', role: 'CUSTOM', description: '', required: 0, sortOrder: 0 })

    // Items
    const items = ref([])
    const itemsLoading = ref(false)
    const itemPage = ref(1)
    const itemSize = ref(20)
    const itemTotal = ref(0)
    const searchKeyword = ref('')
    const showAddItem = ref(false)
    const editingItem = ref(null)
    const savingItem = ref(false)
    const itemForm = ref({})

    // Versions
    const versions = ref([])
    const versionsLoading = ref(false)
    const showNewVersionDialog = ref(false)
    const newVersionStep = ref(0)
    const newVersionDesc = ref('')
    const nvFile = ref(null)
    const nvUploadRef = ref(null)
    const nvPreviewing = ref(false)
    const nvUploading = ref(false)
    const nvMappingFields = ref([])
    const nvPreviewData = ref({})

    // 评测历史
    const evalHistory = ref([])
    const evalHistoryLoading = ref(false)

    const loadEvalHistory = async () => {
      evalHistoryLoading.value = true
      try {
        const res = await datasetApi.listEvalHistory(datasetId.value)
        evalHistory.value = res.data || []
      } finally { evalHistoryLoading.value = false }
    }

    const statusLabel = (s) => {
      const map = { PENDING: '待启动', RUNNING: '运行中', COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消' }
      return map[s] || s || '-'
    }
    const goEvalHistory = (taskId) => {
      router.push({ path: '/analysis', query: { taskId, view: 'overview' } })
    }

    // ===== 重复检测 =====
    const showDuplicateDialog = ref(false)
    const dupFieldName = ref('question')
    const dupThreshold = ref(0.8)
    const dupLoading = ref(false)
    const dupDetectResult = ref(null)
    const dupDeleting = ref(false)

    const dupFieldNameValue = (sf) => {
      const map = { QUESTION: 'question', REFERENCE: 'referenceAnswer', CONTEXT: 'context', CATEGORY: 'category' }
      return map[sf.role] || ''
    }

    const runDuplicateDetect = async () => {
      if (!dupFieldName.value) { ElMessage.warning('请选择检测字段'); return }
      dupLoading.value = true
      try {
        const res = await datasetApi.detectDuplicates(datasetId.value, dupFieldName.value, dupThreshold.value)
        dupDetectResult.value = res.data
        // 初始化选中状态：每组第一条默认保留，其余默认选中删除
        if (res.data.groups) {
          res.data.groups.forEach(g => {
            g._allSelected = false
            g._indeterminate = false
            g.items.forEach((item, i) => { item._selected = i > 0 })
          })
        }
      } catch (e) { ElMessage.error('检测失败') }
      finally { dupLoading.value = false }
    }

    const toggleGroupAll = (group, val) => {
      group.items.forEach(item => { item._selected = val })
      group._indeterminate = false
    }

    const updateGroupState = (group) => {
      const sel = group.items.filter(i => i._selected).length
      group._allSelected = sel === group.items.length
      group._indeterminate = sel > 0 && sel < group.items.length
    }

    const dupSelectedCount = computed(() => {
      if (!dupDetectResult.value?.groups) return 0
      let count = 0
      dupDetectResult.value.groups.forEach(g => {
        g.items.forEach(item => { if (item._selected) count++ })
      })
      return count
    })

    const batchDeleteDuplicates = async () => {
      const count = dupSelectedCount.value
      if (count === 0) { ElMessage.warning('请至少选择一条数据删除'); return }
      const ids = []
      dupDetectResult.value.groups.forEach(g => {
        g.items.forEach(item => { if (item._selected) ids.push(item.id) })
      })
      await ElMessageBox.confirm(`确定删除选中的 ${ids.length} 条重复数据？此操作不可恢复。`, '批量删除', { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' })
      dupDeleting.value = true
      try {
        await datasetApi.batchDeleteItems(datasetId.value, ids)
        ElMessage.success(`已删除 ${ids.length} 条数据`)
        showDuplicateDialog.value = false
        loadItems()
        loadDataset()
      } catch (e) { ElMessage.error('删除失败') }
      finally { dupDeleting.value = false }
    }

    const loadDataset = async () => {
      const res = await datasetApi.getById(datasetId.value)
      dataset.value = res.data || {}
      if (dataset.value.name) {
        versionsLoading.value = true
        try { const vres = await datasetApi.listVersions(dataset.value.name); versions.value = vres.data || [] } finally { versionsLoading.value = false }
      }
    }

    const loadSchema = async () => {
      const res = await datasetApi.getSchema(datasetId.value)
      schemaFields.value = (res.data || []).map(s => ({ ...s }))
    }

    const loadItems = async () => {
      itemsLoading.value = true
      try {
        const res = await datasetApi.listItems(datasetId.value, itemPage.value, itemSize.value)
        items.value = res.data.records
        itemTotal.value = res.data.total
      } finally { itemsLoading.value = false }
    }

    // ===== Schema helpers =====
    const roleLabel = (r) => ({ QUESTION: '问题', REFERENCE: '参考答案', CONTEXT: '上下文', CATEGORY: '分类', CUSTOM: '自定义' }[r] || r)
    const formatTime = (row, column, cellValue) => {
      if (!cellValue) return '-'
      const d = new Date(cellValue)
      if (isNaN(d.getTime())) return cellValue
      return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`
    }
    const roleTagType = (r) => ({ QUESTION: 'danger', REFERENCE: 'success', CONTEXT: 'warning', CATEGORY: '', CUSTOM: 'info' }[r] || 'info')

    const addSchemaField = () => {
      if (!newField.value.fieldName) { ElMessage.warning('字段名不能为空'); return }
      schemaFields.value.push({ ...newField.value, sortOrder: schemaFields.value.length })
      showAddSchemaField.value = false
      newField.value = { fieldName: '', displayName: '', fieldType: 'TEXT', role: 'CUSTOM', description: '', required: 0, sortOrder: 0 }
    }

    const saveSchema = async () => {
      savingSchema.value = true
      try { await datasetApi.updateSchema(datasetId.value, schemaFields.value); ElMessage.success('Schema已保存') }
      finally { savingSchema.value = false }
    }

    // ===== Data Tab: 动态字段展示 =====
    /** 根据 schema 的 role 获取数据条目中对应字段的值 */
    const getItemFieldValue = (row, sf) => {
      const role = sf.role
      const fieldName = sf.fieldName
      if (role === 'QUESTION') return row.question || ''
      if (role === 'REFERENCE') return row.referenceAnswer || ''
      if (role === 'CONTEXT') return row.context || ''
      if (role === 'CATEGORY') return row.category || ''
      // CUSTOM: 从 extra_fields JSON 中取
      if (row.extraFields) {
        try {
          const extra = JSON.parse(row.extraFields)
          if (extra[fieldName] !== undefined) return extra[fieldName]
        } catch (e) {}
      }
      return ''
    }

    const getColWidth = (sf) => {
      if (sf.role === 'CATEGORY') return 100
      if (sf.role === 'QUESTION' || sf.role === 'REFERENCE') return 280
      if (sf.role === 'CONTEXT') return 240
      return 180
    }

    // ===== Item CRUD =====
    const openAddItem = () => {
      editingItem.value = null
      itemForm.value = {}
      // 初始化所有 schema 字段
      schemaFields.value.forEach(sf => { itemForm.value[sf.fieldName] = '' })
      showAddItem.value = true
    }

    const editItem = (row) => {
      editingItem.value = row
      // 初始化表单：核心字段 + extra_fields
      const form = {}
      schemaFields.value.forEach(sf => {
        const val = getItemFieldValue(row, sf)
        form[sf.fieldName] = val
      })
      itemForm.value = form
      showAddItem.value = true
    }

    const saveItem = async () => {
      savingItem.value = true
      try {
        // 从动态表单中拆出核心字段 + extraFields
        const dto = { question: '', referenceAnswer: '', context: '', category: '', extraFields: '{}' }
        const extra = {}
        for (const sf of schemaFields.value) {
          const val = itemForm.value[sf.fieldName] || ''
          if (sf.role === 'QUESTION') dto.question = val
          else if (sf.role === 'REFERENCE') dto.referenceAnswer = val
          else if (sf.role === 'CONTEXT') dto.context = val
          else if (sf.role === 'CATEGORY') dto.category = val
          else extra[sf.fieldName] = val
        }
        dto.extraFields = Object.keys(extra).length > 0 ? JSON.stringify(extra) : ''

        if (editingItem.value) {
          await datasetApi.updateItem(editingItem.value.id, dto)
          ElMessage.success('条目已更新')
        } else {
          await datasetApi.addItem(datasetId.value, dto)
          ElMessage.success('条目已添加')
        }
        showAddItem.value = false
        editingItem.value = null
        loadItems()
        loadDataset()
      } finally { savingItem.value = false }
    }

    const deleteItem = async (row) => {
      await ElMessageBox.confirm('确定删除该条目？', '提示', { type: 'warning' })
      await datasetApi.deleteItem(row.id)
      ElMessage.success('已删除')
      loadItems()
      loadDataset()
    }

    // ===== Version =====
    const switchVersion = (row) => {
      router.push(`/dataset/${row.id}`)
    }

    const handleNvFileChange = (file) => { nvFile.value = file.raw }

    const handleNvFileExceed = (files) => {
      if (files && files.length) {
        nvFile.value = files[0]
        if (nvUploadRef.value) nvUploadRef.value.clearFiles()
        nvUploadRef.value.handleStart(files[0])
      }
    }

    const formatFileSize = (bytes) => {
      if (!bytes) return ''
      if (bytes < 1024) return bytes + ' B'
      if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
      return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
    }

    const previewNewVersion = async () => {
      if (!nvFile.value) { ElMessage.warning('请选择文件'); return }
      nvPreviewing.value = true
      try {
        const fd = new FormData()
        fd.append('file', nvFile.value)
        const res = await datasetApi.previewFile(fd)
        nvPreviewData.value = res.data
        // 如果当前数据集有 schema，复用旧 schema 做推荐映射
        nvMappingFields.value = (res.data.suggestedMapping || []).map(m => {
          // 尝试复用旧 schema 的配置
          const old = schemaFields.value.find(sf => sf.fieldName === m.fieldName)
          return {
            fieldName: m.fieldName,
            displayName: old ? old.displayName : (m.displayName || m.fieldName),
            fieldType: old ? old.fieldType : (m.fieldType || 'TEXT'),
            role: old ? old.role : (m.role || 'CUSTOM'),
            description: old ? old.description : (m.description || ''),
            required: old ? old.required : (m.required || 0),
            sortOrder: m.sortOrder || 0
          }
        })
        newVersionStep.value = 1
      } finally { nvPreviewing.value = false }
    }

    const submitNewVersion = async () => {
      nvUploading.value = true
      try {
        const fd = new FormData()
        fd.append('file', nvFile.value)
        fd.append('name', dataset.value.name)
        fd.append('description', newVersionDesc.value || '')
        // 继承当前数据集的评测类型和模型结果
        fd.append('hasReference', dataset.value.hasReference !== undefined && dataset.value.hasReference !== null ? dataset.value.hasReference : 1)
        fd.append('hasModelResponse', dataset.value.hasModelResponse !== undefined && dataset.value.hasModelResponse !== null ? dataset.value.hasModelResponse : 0)
        fd.append('schemaFields', JSON.stringify(nvMappingFields.value))
        fd.append('columnMapping', JSON.stringify(
          nvMappingFields.value.reduce((m, f) => { m[f.fieldName] = f.role; return m }, {})
        ))
        const res = await datasetApi.upload(fd)
        ElMessage.success(`新版本 v${res.data.version} 创建成功`)
        showNewVersionDialog.value = false
        newVersionStep.value = 0
        newVersionDesc.value = ''
        nvFile.value = null
        // 留在当前页面并刷新，让用户看到新版本出现在版本历史里
        datasetId.value = res.data.id
        loadDataset(); loadSchema(); loadItems(); loadEvalHistory()
        activeTab.value = 'version'
      } finally { nvUploading.value = false }
    }

    onMounted(() => { loadDataset(); loadSchema(); loadItems(); loadEvalHistory() })

    watch(() => route.params.id, (newId) => {
      if (newId) { datasetId.value = Number(newId); loadDataset(); loadSchema(); loadItems(); loadEvalHistory() }
    })

    return {
      dataset, datasetId, activeTab,
      schemaFields, savingSchema, showAddSchemaField, newField, addSchemaField, saveSchema, roleLabel, roleTagType, formatTime,
      items, itemsLoading, itemPage, itemSize, itemTotal, searchKeyword, loadItems,
      showAddItem, editingItem, savingItem, itemForm, openAddItem, editItem, saveItem, deleteItem,
      getItemFieldValue, getColWidth,
      versions, versionsLoading, switchVersion,
      showNewVersionDialog, newVersionStep, newVersionDesc, nvFile, nvUploadRef, nvPreviewing, nvUploading,
      nvMappingFields, nvPreviewData, handleNvFileChange, handleNvFileExceed, previewNewVersion, submitNewVersion,
      formatFileSize,
      evalHistory, evalHistoryLoading, statusLabel, goEvalHistory,
      showDuplicateDialog, dupFieldName, dupThreshold, dupLoading, dupDetectResult, dupDeleting,
      dupFieldNameValue, runDuplicateDetect, toggleGroupAll, updateGroupState, dupSelectedCount, batchDeleteDuplicates
    }
  }
}
</script>

<style scoped>
.dataset-detail { background: var(--bg-card); border-radius: 14px; padding: 24px; border: 1px solid var(--border); box-shadow: var(--shadow-card); height: 100%; display: flex; flex-direction: column; min-height: 0; }
.detail-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 20px; padding-bottom: 16px; border-bottom: 1px solid var(--border); flex-shrink: 0; }
/* tabs 区域占满剩余空间，内部滚动 */
.dataset-detail .el-tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.dataset-detail .el-tabs__content {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.dataset-detail .el-tabs__content .el-tab-pane {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
/* 数据条目 Tab：工具条固定、表格占满剩余空间并内部滚动 */
.data-tab-pane {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.data-tab-toolbar {
  flex-shrink: 0;
}
.data-tab-table {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.data-tab-table :deep(.el-table) {
  height: 100%;
}
.data-tab-table :deep(.el-table__inner-wrapper) {
  height: 100%;
}
.data-tab-table :deep(.el-table__body-wrapper) {
  height: calc(100% - 40px); /* 减去表头高度 */
}
/* 单元格内容自动换行，显示完整 */
.cell-wrap {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.5;
  max-height: 120px;
  overflow-y: auto;
}
.header-left h2 { font-size: 20px; color: var(--text-prime); margin: 8px 0 4px; }

/* 字段定义表格：单元格内容垂直居中（仅单元格，不影响内部组件结构） */
.dataset-detail :deep(.el-table .el-table__cell) {
  vertical-align: middle;
}

.header-left .desc { color: var(--text-mute); font-size: 13px; }
.header-stats { display: flex; gap: 24px; }
.stat-item { text-align: center; }
.stat-val { display: block; font-size: 22px; font-weight: 600; color: var(--accent); }
.stat-label { display: block; font-size: 12px; color: var(--text-mute); margin-top: 2px; }

/* 评测历史 */
.eval-history-hint { font-size: 12px; color: var(--text-mute); margin-bottom: 12px; }
.eval-na { color: var(--text-mute); }
.eval-status { font-size: 11px; font-weight: 600; padding: 2px 8px; border-radius: 20px; }
.eval-status.completed { background: var(--accent-soft); color: var(--accent); }
.eval-status.running { background: var(--yellow-soft); color: var(--yellow); }
.eval-status.pending { background: rgba(148,163,184,0.12); color: var(--text-sec); }
.eval-status.failed { background: var(--red-soft); color: var(--red); }

/* 新版本文件选择 */
.nv-file-select { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.nv-file-name { display: inline-flex; align-items: center; gap: 4px; font-size: 12px; color: var(--accent); font-weight: 600; }
.nv-file-hint { font-size: 12px; color: var(--text-mute); }

/* 重复检测 */
.dup-config { margin-bottom: 16px; }
.dup-config-row { display: flex; align-items: center; gap: 0; flex-wrap: wrap; }
.dup-label { font-size: 13px; font-weight: 600; color: var(--text-sec); white-space: nowrap; }
.dup-thresh-val { font-size: 13px; font-weight: 700; color: var(--accent); min-width: 32px; text-align: center; }
.dup-result { border-top: 1px solid var(--border); padding-top: 14px; }
.dup-summary { font-size: 13px; color: var(--text-sec); margin-bottom: 12px; }
.dup-summary b { font-weight: 700; }
.dup-empty { text-align: center; padding: 32px 0; color: var(--text-mute); font-size: 14px; }
.dup-groups { max-height: 460px; overflow-y: auto; display: flex; flex-direction: column; gap: 10px; }
.dup-group-card { border: 1px solid var(--border); border-radius: 10px; padding: 12px 14px; }
.dup-group-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.dup-group-title { font-size: 13px; font-weight: 600; color: var(--text-prime); }
.dup-group-items { display: flex; flex-direction: column; gap: 4px; }
.dup-item-row { display: flex; align-items: center; gap: 8px; padding: 6px 10px; border-radius: 6px; background: var(--bg-input); font-size: 12px; }
.dup-item-row.dup-item-keep { background: rgba(16, 185, 129, 0.06); border: 1px solid rgba(16, 185, 129, 0.2); }
.dup-item-seq { font-family: monospace; color: var(--text-mute); min-width: 40px; }
.dup-item-text { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; color: var(--text-prime); }
.dup-item-badge { font-size: 11px; font-weight: 600; padding: 2px 8px; border-radius: 10px; white-space: nowrap; flex-shrink: 0; }
.dup-item-badge.keep { background: var(--accent-soft); color: var(--accent); }
.dup-item-badge.sim { background: rgba(249, 115, 22, 0.1); color: #f97316; }
.dup-del-hint { margin-right: 12px; font-size: 13px; color: var(--text-sec); }
.dup-del-hint b { color: var(--red); font-weight: 700; }
</style>
