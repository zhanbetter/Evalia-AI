<template>
  <div class="page-card">
    <div class="page-header">
      <h2>评测知识</h2>
      <div class="header-actions">
        <el-input v-model="keyword" placeholder="搜索文章..." clearable style="width: 220px" @keyup.enter="loadArticles(1)" @clear="loadArticles(1)" />
        <el-select v-model="sourceFilter" placeholder="来源筛选" clearable style="width: 160px" @change="loadArticles(1)">
          <el-option v-for="s in sources" :key="s" :label="s" :value="s" />
        </el-select>
        <el-button type="primary" @click="openSourceDialog">
          <el-icon><Connection /></el-icon>RSS 源管理
        </el-button>
        <el-button plain @click="showImportDialog = true">
          <el-icon><Link /></el-icon>导入文章链接
        </el-button>
      </div>
    </div>

    <!-- 文章卡片网格 -->
    <div v-loading="loading" class="article-grid">
      <div v-if="articles.length === 0 && !loading" class="empty-state">
        <el-empty description="暂无文章，去「RSS 源管理」订阅几个 AI / 评测源试试？" />
      </div>
      <div v-for="article in articles" :key="article.id" class="article-card" @click="goDetail(article.id)">
        <div class="article-card-header">
          <span class="article-source" v-if="article.sourceName">{{ article.sourceName }}</span>
          <span class="article-date" v-if="article.publishedAt">{{ formatDate(article.publishedAt) }}</span>
        </div>
        <h3 class="article-title">{{ article.title }}</h3>
        <p class="article-author" v-if="article.author">{{ article.author }}</p>
        <p class="article-summary" v-if="article.summary">{{ truncate(article.summary, 120) }}</p>
        <p class="article-summary placeholder" v-else>暂无摘要</p>
        <div class="article-tags" v-if="article.tags">
          <el-tag v-for="tag in article.tags.split(',')" :key="tag" size="small" type="info">{{ tag.trim() }}</el-tag>
        </div>
      </div>
    </div>

    <el-pagination
      v-if="total > pageSize"
      background
      layout="prev, pager, next"
      :total="total"
      :page-size="pageSize"
      :current-page="currentPage"
      @current-change="loadArticles"
      style="margin-top: 18px; justify-content: center;"
    />

    <!-- ====== RSS 源管理弹窗 ====== -->
    <el-dialog v-model="showSourceDialog" title="RSS 源管理" width="760px" @open="loadSources">
      <el-alert type="info" :closable="false" style="margin-bottom: 14px"
        title="每天 08:30 自动拉取全部启用源（后端可配置 eval.rss.cron 修改）；也可手动点击单源「拉取」或「拉取全部」。仅收录 AI / Agent / 评测相关内容。" />

      <!-- 新增订阅源 -->
      <div class="source-add">
        <el-input v-model="newSource.sourceName" placeholder="来源名称（如：美团技术团队）" style="width: 170px" />
        <el-input v-model="newSource.feedUrl" placeholder="https://example.com/rss.xml" style="flex: 1" />
        <el-input v-model="newSource.description" placeholder="描述（可选）" style="width: 150px" />
        <el-button type="primary" :loading="addingSource" @click="addSource">新增订阅</el-button>
      </div>

      <!-- 订阅源列表 -->
      <el-table v-loading="sourcesLoading" :data="rssSources" size="small" style="margin-top: 12px">
        <el-table-column label="来源" min-width="160">
          <template #default="{ row }">
            <div class="src-name">{{ row.sourceName }}</div>
            <div class="src-desc" v-if="row.description">{{ row.description }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="feedUrl" label="订阅地址" min-width="220" show-overflow-tooltip />
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.status === 1"
              @change="(val) => toggleSource(row, val)"
            />
          </template>
        </el-table-column>
        <el-table-column label="上次拉取" width="130">
          <template #default="{ row }">
            <span class="src-last">{{ row.lastFetchedAt ? formatDateTime(row.lastFetchedAt) : '从未' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" align="center">
          <template #default="{ row }">
            <el-button
              type="primary" link size="small"
              :loading="fetchingSourceId === row.id"
              :disabled="row.status !== 1"
              @click="fetchSource(row)">拉取</el-button>
            <el-popconfirm title="确认删除该订阅源？" width="200" @confirm="deleteSource(row)">
              <template #reference>
                <el-button type="danger" link size="small">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>

      <div class="source-footer">
        <span class="src-hint">共 {{ rssSources.length }} 个订阅源</span>
        <el-button :loading="fetchingAll" @click="fetchAllSources">
          <el-icon style="margin-right: 4px"><Refresh /></el-icon>拉取全部
        </el-button>
      </div>
    </el-dialog>

    <!-- 导入单篇文章弹窗 -->
    <el-dialog v-model="showImportDialog" title="导入文章链接" width="500px">
      <el-alert type="info" :closable="false" style="margin-bottom: 16px"
        title="适合没有 RSS 的技术团队：粘贴文章链接，系统会自动抓取正文并按主题过滤后入库" />
      <el-form label-width="90px">
        <el-form-item label="文章链接">
          <el-input v-model="importForm.url" placeholder="https://example.com/xxx.html" />
        </el-form-item>
        <el-form-item label="来源名称">
          <el-input v-model="importForm.sourceName" placeholder="如：美团技术团队（可空，默认手工导入）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showImportDialog = false">取消</el-button>
        <el-button type="primary" :loading="importing" @click="doImport">导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import axios from 'axios'
import { ElMessage } from 'element-plus'

export default {
  name: 'KnowledgeIndex',
  data() {
    return {
      articles: [],
      total: 0,
      currentPage: 1,
      pageSize: 12,
      keyword: '',
      sourceFilter: '',
      // 文章来源筛选（首页顶部下拉：文章 source_name 字符串列表）
      sources: [],
      // RSS 源管理表格数据（对象数组，与 sources 分开，避免数据串用）
      rssSources: [],
      loading: false,

      // RSS 源管理
      showSourceDialog: false,
      sourcesLoading: false,
      addingSource: false,
      fetchingSourceId: null,
      fetchingAll: false,
      newSource: { sourceName: '', feedUrl: '', description: '' },

      showImportDialog: false,
      importing: false,
      importForm: { url: '', sourceName: '' }
    }
  },
  mounted() {
    this.loadArticles(1)
  },
  methods: {
    async loadArticles(page) {
      this.currentPage = page
      this.loading = true
      try {
        const params = { page, size: this.pageSize }
        if (this.keyword) params.keyword = this.keyword
        if (this.sourceFilter) params.source = this.sourceFilter
        const { data } = await axios.get('/api/articles', { params })
        if (data.code === 200) {
          this.articles = data.data.records || []
          this.total = data.data.total || 0
          // 提取来源列表（从当前数据中去重）
          const srcSet = new Set(this.articles.map(a => a.sourceName).filter(Boolean))
          if (srcSet.size > 0) {
            this.sources = [...new Set([...this.sources, ...srcSet])]
          }
        }
      } catch (e) {
        ElMessage.error('加载文章列表失败')
      } finally {
        this.loading = false
      }
    },

    // ---------- RSS 源管理 ----------
    openSourceDialog() {
      this.showSourceDialog = true
      this.loadSources()
    },
    async loadSources() {
      this.sourcesLoading = true
      try {
        const { data } = await axios.get('/api/rss-sources')
        if (data.code === 200) {
          this.rssSources = data.data || []
        }
      } catch (e) {
        ElMessage.error('加载订阅源失败')
      } finally {
        this.sourcesLoading = false
      }
    },
    async addSource() {
      if (!this.newSource.sourceName || !this.newSource.feedUrl) {
        ElMessage.warning('请填写来源名称和订阅地址')
        return
      }
      this.addingSource = true
      try {
        const { data } = await axios.post('/api/rss-sources', this.newSource)
        if (data.code === 200) {
          ElMessage.success('订阅成功')
          this.newSource = { sourceName: '', feedUrl: '', description: '' }
          this.loadSources()
        } else {
          ElMessage.error(data.message || '订阅失败')
        }
      } catch (e) {
        ElMessage.error('订阅失败：' + (e.response?.data?.message || e.message))
      } finally {
        this.addingSource = false
      }
    },
    async toggleSource(row, enabled) {
      try {
        await axios.put(`/api/rss-sources/${row.id}/enabled`, { enabled })
        row.status = enabled ? 1 : 0
        ElMessage.success(enabled ? '已启用' : '已停用')
      } catch (e) {
        ElMessage.error('操作失败：' + (e.response?.data?.message || e.message))
        // 回滚开关状态
        row.status = enabled ? 0 : 1
      }
    },
    async deleteSource(row) {
      try {
        const { data } = await axios.delete(`/api/rss-sources/${row.id}`)
        if (data.code === 200) {
          ElMessage.success('已删除')
          this.rssSources = this.rssSources.filter(s => s.id !== row.id)
        }
      } catch (e) {
        ElMessage.error('删除失败：' + (e.response?.data?.message || e.message))
      }
    },
    async fetchSource(row) {
      this.fetchingSourceId = row.id
      try {
        const { data } = await axios.post(`/api/rss-sources/${row.id}/fetch`)
        if (data.code === 200) {
          ElMessage.success(`「${row.sourceName}」拉取完成，新增 ${data.data} 条`)
          this.loadSources()
          this.loadArticles(1)
        } else {
          ElMessage.error(data.message || '拉取失败')
        }
      } catch (e) {
        ElMessage.error('拉取失败：' + (e.response?.data?.message || e.message))
      } finally {
        this.fetchingSourceId = null
      }
    },
    async fetchAllSources() {
      this.fetchingAll = true
      try {
        const { data } = await axios.post('/api/rss-sources/fetch-all')
        if (data.code === 200) {
          ElMessage.success(`全部源拉取完成，共新增 ${data.data} 条`)
          this.loadSources()
          this.loadArticles(1)
        } else {
          ElMessage.error(data.message || '拉取失败')
        }
      } catch (e) {
        ElMessage.error('拉取失败：' + (e.response?.data?.message || e.message))
      } finally {
        this.fetchingAll = false
      }
    },

    async doImport() {
      if (!this.importForm.url) {
        ElMessage.warning('请输入文章链接')
        return
      }
      this.importing = true
      try {
        const { data } = await axios.post('/api/articles/import', null, {
          params: { url: this.importForm.url, sourceName: this.importForm.sourceName }
        })
        if (data.code === 200) {
          if (data.data) {
            ElMessage.success('文章已导入')
            this.showImportDialog = false
            this.importForm = { url: '', sourceName: '' }
            this.loadArticles(1)
          } else {
            ElMessage.warning('导入未成功：可能文章已存在、正文抓取失败，或与评测主题不相关')
          }
        } else {
          ElMessage.error(data.message || '导入失败')
        }
      } catch (e) {
        ElMessage.error('导入失败：' + (e.response?.data?.message || e.message))
      } finally {
        this.importing = false
      }
    },
    goDetail(id) {
      this.$router.push(`/knowledge/${id}`)
    },
    formatDate(dt) {
      if (!dt) return ''
      return dt.substring(0, 10)
    },
    formatDateTime(dt) {
      if (!dt) return ''
      return dt.replace('T', ' ').substring(0, 16)
    },
    truncate(text, len) {
      if (!text) return ''
      return text.length > len ? text.substring(0, len) + '...' : text
    }
  }
}
</script>

<style scoped>
.page-card { height: 100%; display: flex; flex-direction: column; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; flex-shrink: 0; }
.page-header h2 { font-size: 18px; font-weight: 700; color: var(--text-prime); }
.header-actions { display: flex; gap: 10px; align-items: center; }

.article-grid {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
  align-content: start;
}

.empty-state {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 200px;
}

.article-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 18px;
  cursor: pointer;
  transition: all 0.2s ease;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.article-card:hover {
  border-color: var(--accent);
  box-shadow: 0 4px 16px rgba(16, 185, 129, 0.1);
  transform: translateY(-2px);
}

.article-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.article-source {
  font-size: 11px;
  font-weight: 600;
  color: var(--accent);
  background: var(--accent-soft);
  padding: 2px 8px;
  border-radius: 4px;
}
.article-date {
  font-size: 11px;
  color: var(--text-mute);
}

.article-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-prime);
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.article-author {
  font-size: 12px;
  color: var(--text-sec);
}

.article-summary {
  font-size: 13px;
  color: var(--text-sec);
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  margin-top: 4px;
}
.article-summary.placeholder {
  color: var(--text-mute);
  font-style: italic;
}

.article-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 4px;
}

.source-add {
  display: flex;
  gap: 8px;
  align-items: center;
}
.src-name { font-weight: 600; font-size: 13px; color: var(--text-prime); }
.src-desc { font-size: 11px; color: var(--text-mute); margin-top: 2px; }
.src-last { font-size: 12px; color: var(--text-mute); }
.source-footer {
  margin-top: 14px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.src-hint { font-size: 12px; color: var(--text-mute); }
</style>