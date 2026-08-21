<template>
  <div class="page-card" v-loading="loading">
    <!-- 返回按钮 + 操作栏 -->
    <div class="detail-header">
      <el-button text @click="$router.push('/knowledge')">
        <el-icon><ArrowLeft /></el-icon>返回列表
      </el-button>
      <div class="header-right">
        <el-button v-if="article" type="primary" plain :loading="summarizing" @click="doSummarize">
          <el-icon><MagicStick /></el-icon>AI 一键总结
        </el-button>
        <el-button v-if="article" plain :loading="repairing" @click="doRepair">
          <el-icon><Brush /></el-icon>清理入库
        </el-button>
        <el-button v-if="article?.sourceUrl" type="info" plain @click="openOriginal">
          <el-icon><Link /></el-icon>查看原文
        </el-button>
      </div>
    </div>

    <!-- 文章内容 -->
    <div v-if="article" class="detail-body">
      <div class="detail-meta">
        <span class="meta-source" v-if="article.sourceName">{{ article.sourceName }}</span>
        <span class="meta-author" v-if="article.author">{{ article.author }}</span>
        <span class="meta-date" v-if="article.publishedAt">{{ formatDate(article.publishedAt) }}</span>
      </div>

      <h1 class="detail-title">{{ article.title }}</h1>

      <!-- AI 摘要 -->
      <div v-if="article.summary" class="summary-box">
        <div class="summary-label">
          <el-icon><MagicStick /></el-icon>AI 摘要
        </div>
        <div class="summary-content">{{ article.summary }}</div>
      </div>
      <div v-else class="summary-box empty">
        <div class="summary-label">
          <el-icon><MagicStick /></el-icon>AI 摘要
        </div>
        <div class="summary-content placeholder">暂无摘要，点击右上角「AI 一键总结」生成</div>
      </div>

      <!-- 正文 -->
      <div v-if="article.content" class="article-content" v-html="sanitizeHtml(article.content)"></div>
      <div v-else class="article-content empty">
        <el-empty description="暂无正文内容" />
      </div>

      <!-- 标签 -->
      <div v-if="article.tags" class="detail-tags">
        <el-tag v-for="tag in article.tags.split(',')" :key="tag" type="info">{{ tag.trim() }}</el-tag>
      </div>
    </div>

    <!-- 模型选择弹窗 -->
    <el-dialog v-model="showModelDialog" title="选择总结模型" width="400px">
      <el-select v-model="selectedModelId" placeholder="选择用于总结的模型" style="width: 100%">
        <el-option v-for="m in models" :key="m.id" :label="m.modelName || m.modelId" :value="m.id" />
      </el-select>
      <template #footer>
        <el-button @click="showModelDialog = false">取消</el-button>
        <el-button type="primary" :loading="summarizing" @click="confirmSummarize">开始总结</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import request from '../../api/request'
import { ElMessage } from 'element-plus'

export default {
  name: 'KnowledgeDetail',
  props: {
    id: { type: [String, Number], required: true }
  },
  data() {
    return {
      article: null,
      loading: true,
      summarizing: false,
      repairing: false,
      showModelDialog: false,
      selectedModelId: null,
      models: []
    }
  },
  mounted() {
    this.loadArticle()
    this.loadModels()
  },
  methods: {
    async loadArticle() {
      this.loading = true
      try {
        const res = await request.get(`/articles/${this.id}`)
        if (res.code === 200) {
          this.article = res.data
        } else {
          ElMessage.error(res.message || '文章不存在')
          this.$router.push('/knowledge')
        }
      } catch (e) {
        ElMessage.error('加载文章失败')
        this.$router.push('/knowledge')
      } finally {
        this.loading = false
      }
    },
    async loadModels() {
      try {
        const res = await request.get('/models', { params: { page: 1, size: 100, status: 1 } })
        if (res.code === 200) {
          this.models = res.data.records || []
        }
      } catch (e) { /* 忽略 */ }
    },
    sanitizeHtml(html) {
      if (!html) return ''
      // 允许的安全标签和属性
      const allowedTags = new Set(['p','br','h1','h2','h3','h4','h5','h6','ul','ol','li','strong','em','a','code','pre','blockquote','table','thead','tbody','tr','td','th','img','span','div','hr','figure','figcaption','dl','dt','dd','sub','sup','small','mark','del','ins'])
      const allowedAttributes = { 'a': ['href','title','target'], 'img': ['src','alt','width','height'], 'span': ['style'], 'div': ['style'] }
      const doc = new DOMParser().parseFromString(html, 'text/html')
      const sanitizeNode = (node) => {
        const children = Array.from(node.childNodes)
        for (const child of children) {
          if (child.nodeType === 1) {
            const tag = child.tagName.toLowerCase()
            if (!allowedTags.has(tag)) {
              child.replaceWith(...child.childNodes)
              continue
            }
            // 清理属性
            const allowed = allowedAttributes[tag] || []
            const attrs = Array.from(child.attributes)
            for (const attr of attrs) {
              if (!allowed.includes(attr.name.toLowerCase())) {
                child.removeAttribute(attr.name)
              }
            }
            // 禁止 javascript: URL
            if (child.hasAttribute('href')) {
              const href = child.getAttribute('href')
              if (/^\s*javascript:/i.test(href)) {
                child.removeAttribute('href')
              }
            }
            sanitizeNode(child)
          }
        }
      }
      sanitizeNode(doc.body)
      return doc.body.innerHTML
    },
    async doSummarize() {
      await this.loadModels()
      this.showModelDialog = true
    },
    async confirmSummarize() {
      this.summarizing = true
      try {
        const params = {}
        if (this.selectedModelId) params.modelConfigId = this.selectedModelId
        const res = await request.post(`/articles/${this.id}/summary`, null, { params })
        if (res.code === 200) {
          this.article.summary = res.data
          ElMessage.success('摘要生成成功')
          this.showModelDialog = false
        } else {
          ElMessage.error(res.message || '生成摘要失败')
        }
      } catch (e) {
        ElMessage.error('生成摘要失败：' + (e.response?.data?.message || e.message))
      } finally {
        this.summarizing = false
      }
    },
    openOriginal() {
      window.open(this.article.sourceUrl, '_blank')
    },
    async doRepair() {
      this.repairing = true
      try {
        const res = await request.post(`/articles/${this.id}/repair`)
        if (res.code === 200) {
          this.article.content = res.data || this.article.content
          ElMessage.success('正文已清理并写回数据库')
        } else {
          ElMessage.error(res.message || '清理失败')
        }
      } catch (e) {
        ElMessage.error('清理失败：' + (e.response?.data?.message || e.message))
      } finally {
        this.repairing = false
      }
    },
    formatDate(dt) {
      if (!dt) return ''
      return dt.substring(0, 10)
    }
  }
}
</script>

<style scoped>
.page-card { height: 100%; display: flex; flex-direction: column; overflow-y: auto; }

.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-shrink: 0;
  margin-bottom: 16px;
}
.header-right { display: flex; gap: 8px; }

.detail-body { flex: 1; }

.detail-meta {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 12px;
}
.meta-source {
  font-size: 12px;
  font-weight: 600;
  color: var(--accent);
  background: var(--accent-soft);
  padding: 2px 10px;
  border-radius: 4px;
}
.meta-author { font-size: 12px; color: var(--text-sec); }
.meta-date { font-size: 12px; color: var(--text-mute); }

.detail-title {
  font-size: 22px;
  font-weight: 700;
  color: var(--text-prime);
  line-height: 1.4;
  margin-bottom: 20px;
}

.summary-box {
  background: linear-gradient(135deg, rgba(16,185,129,0.06), rgba(16,185,129,0.02));
  border: 1px solid rgba(16,185,129,0.2);
  border-radius: 12px;
  padding: 18px;
  margin-bottom: 24px;
}
.summary-box.empty {
  border-style: dashed;
}
.summary-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--accent);
  margin-bottom: 10px;
}
.summary-content {
  font-size: 14px;
  color: var(--text-prime);
  line-height: 1.7;
  white-space: pre-wrap;
}
.summary-content.placeholder {
  color: var(--text-mute);
  font-style: italic;
}

.article-content {
  font-size: 15px;
  color: var(--text-prime);
  line-height: 1.8;
  padding: 0 0 20px;
  white-space: pre-wrap;
}
.article-content.empty {
  padding: 40px 0;
}
.article-content :deep(h1),
.article-content :deep(h2),
.article-content :deep(h3) {
  margin: 20px 0 10px;
  font-weight: 700;
}
.article-content :deep(p) { margin-bottom: 12px; }
.article-content :deep(code) {
  background: var(--bg-input);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 13px;
}
.article-content :deep(pre) {
  background: var(--bg-input);
  padding: 14px;
  border-radius: 8px;
  overflow-x: auto;
  margin-bottom: 14px;
}
.article-content :deep(img) {
  max-width: 100%;
  border-radius: 8px;
}
.article-content :deep(a) {
  color: var(--accent);
  text-decoration: none;
}
.article-content :deep(a):hover { text-decoration: underline; }

.detail-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding-top: 16px;
  border-top: 1px solid var(--border);
}
</style>
