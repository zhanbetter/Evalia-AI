<template>
  <div class="page-card">
    <div class="page-header">
      <div>
        <h2>评估器管理</h2>
        <p class="page-sub">编写评估器，定义维度、标准与 badcase 规则</p>
      </div>
      <div class="header-right">
        <el-input v-model="keyword" placeholder="搜索评估器..." clearable style="width: 200px" @keyup.enter="loadPrompts(1)" @clear="loadPrompts(1)" />
        <el-select v-model="searchMode" placeholder="模式" clearable style="width: 120px" @change="loadPrompts(1)">
          <el-option label="结构化" value="structured" />
          <el-option label="自由文本" value="free" />
        </el-select>
        <el-button type="primary" @click="$router.push('/prompt/editor')">
          <el-icon style="margin-right:4px"><Plus /></el-icon>添加评估器
        </el-button>
      </div>
    </div>

    <!-- 统计卡片行 -->
    <div class="stats-row">
      <div class="stat-card">
        <div class="stat-icon green"><el-icon :size="18"><ChatLineSquare /></el-icon></div>
        <div><div class="stat-num">{{ prompts.length }}</div><div class="stat-label">评估器总数</div></div>
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

    <!-- 评估器卡片网格 -->
    <div class="prompt-grid" v-loading="loading">
      <div v-for="p in prompts" :key="p.id" class="prompt-card">
        <div class="prompt-card-top">
          <span class="mode-tag" :class="p.dimensionsConfig ? 'struct' : 'free'">
            <el-icon :size="12" style="margin-right:3px"><component :is="p.dimensionsConfig ? 'SetUp' : 'Document'" /></el-icon>
            {{ p.dimensionsConfig ? '结构化' : '自由文本' }}
          </span>
          <span class="card-right-tags">
            <el-tag size="small" type="warning" v-if="p.version">{{ p.version > 1 ? `v${p.version} ` : 'v1' }}</el-tag>
            <el-tag :type="p.status === 1 ? 'success' : 'info'" size="small">
              {{ p.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </span>
        </div>
        <h3 class="prompt-name">{{ p.name }}</h3>
        <p class="prompt-desc">{{ p.description || '暂无描述' }}</p>
        <div class="prompt-meta" v-if="p.dimensionsConfig && dimCount(p)">
          <span class="meta-chip"><el-icon :size="11"><Grid /></el-icon> {{ dimCount(p) }} 个维度</span>
        </div>
        <div class="prompt-card-footer">
          <el-button size="small" type="primary" plain @click="$router.push(`/prompt/editor/${p.id}`)">
            <el-icon style="margin-right:3px"><Edit /></el-icon>编辑
          </el-button>
          <el-button v-if="p.version && p.version > 1" size="small" plain @click="viewVersions(p)">
            <el-icon style="margin-right:3px"><Clock /></el-icon>v{{ p.version }}
          </el-button>
          <el-button size="small" type="danger" plain @click="handleDelete(p)">
            <el-icon style="margin-right:3px"><Delete /></el-icon>删除
          </el-button>
        </div>
      </div>
    </div>

    <el-pagination
      v-if="total > pageSize"
      background layout="prev, pager, next"
      :total="total" :page-size="pageSize" :current-page="currentPage"
      @current-change="loadPrompts"
      style="margin-top: 18px; justify-content: center;"
    />

    <!-- 版本历史弹窗 -->
    <el-dialog v-model="showVersions" :title="`版本历史 - ${selectedPrompt?.name || ''}`" width="720px">
      <el-table :data="versions" v-loading="versionsLoading" size="small">
        <el-table-column label="版本" prop="version" width="80" />
        <el-table-column label="名称" prop="name" show-overflow-tooltip />
        <el-table-column label="说明" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.changeNote || (row.version === selectedPrompt?.version ? '当前版本' : '—') }}
          </template>
        </el-table-column>
        <el-table-column label="保存时间" width="160">
          <template #default="{ row }">{{ formatDate(row.createdAt || row.savedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button
              text type="primary" size="small"
              :disabled="row.version === selectedPrompt?.version"
              @click="goVersion(row)">预览</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 版本预览弹窗 -->
    <el-dialog v-model="showVersionPreview" title="版本预览" width="800px">
      <div v-if="versionPreviewData" style="display:flex;gap:12px">
        <div style="flex:1;min-width:0">
          <div style="font-size:13px;font-weight:600;margin-bottom:8px;color:var(--text-sec)">维度配置</div>
          <pre style="font-size:12px;line-height:1.6;white-space:pre-wrap;word-break:break-all;padding:12px;background:var(--bg-input);border:1px solid var(--border);border-radius:8px;max-height:400px;overflow-y:auto">{{ formatJson(versionPreviewData.dimensionsConfig) }}</pre>
        </div>
        <div style="width:300px;flex-shrink:0">
          <div style="font-size:13px;font-weight:600;margin-bottom:8px;color:var(--text-sec)">Prompt</div>
          <div style="padding:12px;background:var(--bg-input);border:1px solid var(--border);border-radius:8px;font-size:12px;line-height:1.6;white-space:pre-wrap;max-height:400px;overflow-y:auto">{{ versionPreviewData.prompt }}</div>
        </div>
      </div>
      <template #footer>
        <el-button @click="showVersionPreview = false">关闭</el-button>
        <el-button type="primary" @click="restoreVersion" :loading="restoring">恢复此版本</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import axios from 'axios'
import { promptApi } from '../../api'
import { ElMessage, ElMessageBox } from 'element-plus'

export default {
  name: 'PromptIndex',
  data() {
    return {
      prompts: [], total: 0, currentPage: 1, pageSize: 20, loading: false,
      keyword: '', searchMode: '',
      structuredCount: 0, freeCount: 0,
      // 版本历史
      showVersions: false, selectedPrompt: null, versions: [], versionsLoading: false,
      showVersionPreview: false, versionPreviewData: null, versionActionId: null, restoring: false
    }
  },
  mounted() { this.loadPrompts(1) },
  methods: {
    async loadPrompts(page) {
      this.currentPage = page; this.loading = true
      try {
        const params = { page, size: this.pageSize }
        if (this.keyword) params.keyword = this.keyword
        if (this.searchMode) params.mode = this.searchMode
        const { data } = await axios.get('/api/prompts', { params })
        if (data.code === 200) {
          this.prompts = data.data.records || []; this.total = data.data.total || 0
          this.structuredCount = this.prompts.filter(p => p.dimensionsConfig).length
          this.freeCount = this.prompts.filter(p => !p.dimensionsConfig).length
        }
      } catch { ElMessage.error('加载列表失败') }
      finally { this.loading = false }
    },

    async handleDelete(p) {
      try {
        await ElMessageBox.confirm(`确定删除「${p.name}」？`, '确认删除', { type: 'warning' })
        const { data } = await axios.delete(`/api/prompts/${p.id}`)
        if (data.code === 200) { ElMessage.success('删除成功'); this.loadPrompts(this.currentPage) }
        else { ElMessage.error(data.message || '删除失败') }
      } catch {}
    },

    dimCount(p) {
      if (!p.dimensionsConfig) return 0
      try { return (JSON.parse(p.dimensionsConfig).dimensions || []).length }
      catch { return 0 }
    },

    async viewVersions(p) {
      this.selectedPrompt = p; this.versions = []; this.showVersions = true; this.versionsLoading = true
      try {
        const res = await promptApi.listVersions(p.id)
        this.versions = res.data || []
      } catch { ElMessage.error('加载版本历史失败') }
      finally { this.versionsLoading = false }
    },

    async goVersion(v) {
      try {
        const res = await promptApi.getVersion(this.selectedPrompt.id, v.version)
        if (res.data) {
          this.versionPreviewData = res.data
          this.versionActionId = v.version
          this.showVersions = false; this.showVersionPreview = true
        }
      } catch { ElMessage.error('加载版本详情失败') }
    },

    async restoreVersion() {
      this.restoring = true
      try {
        await promptApi.restoreVersion(this.selectedPrompt.id, this.versionActionId)
        ElMessage.success('版本已恢复')
        this.showVersionPreview = false
        this.loadPrompts(this.currentPage)
      } catch (e) { ElMessage.error(e.response?.data?.message || '恢复失败') }
      finally { this.restoring = false }
    },

    formatDate(dt) { if (!dt) return '-'; return dt.length > 16 ? dt.substring(0, 16).replace('T', ' ') : dt },
    formatJson(s) { if (!s) return ''; try { return JSON.stringify(JSON.parse(s), null, 2) } catch { return s } }
  }
}
</script>

<style scoped>
.page-card { height: 100%; display: flex; flex-direction: column; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 14px; flex-shrink: 0; }
.page-header h2 { font-size: 18px; font-weight: 700; color: var(--text-prime); }
.page-sub { font-size: 13px; color: var(--text-mute); margin-top: 2px; }
.header-right { display: flex; gap: 10px; align-items: center; }

.stats-row { display: flex; gap: 12px; margin-bottom: 16px; flex-shrink: 0; }
.stat-card {
  flex: 1; display: flex; align-items: center; gap: 12px;
  background: var(--bg-card); border: 1px solid var(--border); border-radius: 10px; padding: 14px 16px;
}
.stat-icon {
  width: 36px; height: 36px; border-radius: 10px; display: flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.stat-icon.green { background: rgba(16, 185, 129, 0.1); color: var(--accent); }
.stat-icon.indigo { background: rgba(99, 102, 241, 0.1); color: #6366f1; }
.stat-icon.orange { background: rgba(249, 115, 22, 0.1); color: #f97316; }
.stat-num { font-size: 20px; font-weight: 700; color: var(--text-prime); }
.stat-label { font-size: 12px; color: var(--text-mute); margin-top: 1px; }

.prompt-grid {
  flex: 1; min-height: 0; overflow-y: auto;
  display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 14px; align-content: start;
}
.prompt-card {
  background: var(--bg-card); border: 1px solid var(--border); border-radius: 12px;
  padding: 18px; display: flex; flex-direction: column; gap: 6px; transition: all 0.2s ease;
}
.prompt-card:hover { border-color: var(--accent); box-shadow: 0 4px 16px rgba(16, 185, 129, 0.1); }
.prompt-card-top { display: flex; justify-content: space-between; align-items: center; }
.card-right-tags { display: flex; gap: 4px; align-items: center; }
.mode-tag {
  display: inline-flex; align-items: center; padding: 3px 8px; border-radius: 6px;
  font-size: 11px; font-weight: 600; line-height: 1;
}
.mode-tag.struct { background: rgba(16, 185, 129, 0.1); color: var(--accent); }
.mode-tag.free { background: rgba(99, 102, 241, 0.1); color: #6366f1; }
.prompt-name { font-size: 15px; font-weight: 600; color: var(--text-prime); }
.prompt-desc { font-size: 12px; color: var(--text-mute); line-height: 1.5; }
.prompt-meta { display: flex; gap: 8px; margin-top: 4px; }
.meta-chip { display: inline-flex; align-items: center; gap: 3px; font-size: 11px; color: var(--text-sec); background: var(--bg-input); padding: 2px 8px; border-radius: 4px; }
.prompt-card-footer { display: flex; gap: 4px; margin-top: 4px; }
</style>
