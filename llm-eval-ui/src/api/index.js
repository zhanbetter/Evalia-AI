import request from './request'

// 数据集 API
export const datasetApi = {
  upload: (formData) => request.post('/datasets/upload', formData, { headers: { 'Content-Type': 'multipart/form-data' } }),
  previewFile: (formData) => request.post('/datasets/preview', formData, { headers: { 'Content-Type': 'multipart/form-data' } }),
  list: (page = 1, size = 10) => request.get('/datasets', { params: { page, size } }),
  getById: (id) => request.get(`/datasets/${id}`),
  updateInfo: (id, data) => request.put(`/datasets/${id}`, null, { params: data }),
  delete: (id) => request.delete(`/datasets/${id}`),
  listItems: (id, page = 1, size = 20) => request.get(`/datasets/${id}/items`, { params: { page, size } }),
  batchGetItems: (ids) => request.get('/datasets/items/batch', { params: { ids: ids.join(',') } }),
  getSchema: (id) => request.get(`/datasets/${id}/schema`),
  updateSchema: (id, fields) => request.put(`/datasets/${id}/schema`, fields),
  addItem: (id, data) => request.post(`/datasets/${id}/items`, data),
  updateItem: (itemId, data) => request.put(`/datasets/items/${itemId}`, data),
  deleteItem: (itemId) => request.delete(`/datasets/items/${itemId}`),
  detectDuplicates: (id, fieldName, threshold) => request.post(`/datasets/${id}/detect-duplicates`, null, { params: { fieldName, threshold } }),
  batchDeleteItems: (id, itemIds) => request.post(`/datasets/${id}/items/batch-delete`, itemIds),
  listVersions: (name) => request.get('/datasets/versions', { params: { name } }),
  checkName: (name, excludeId, excludeName, targetVersion) => request.get('/datasets/check-name', { params: { name, excludeId, excludeName, targetVersion } }),
  listEvalHistory: (id) => request.get(`/datasets/${id}/eval-history`),
  goldAnnotate: (id, data) => request.post(`/datasets/${id}/gold-annotations/annotate`, data),
  goldList: (id, page = 1, size = 20) => request.get(`/datasets/${id}/gold-annotations`, { params: { page, size } }),
  goldStats: (id) => request.get(`/datasets/${id}/gold-annotations/stats`),
  goldRemove: (id, itemId, annotator) => request.delete(`/datasets/${id}/gold-annotations/${itemId}`, { params: { annotator } })
}

// 模型 API
export const modelApi = {
  add: (data) => request.post('/models', data),
  list: (page = 1, size = 10, modelType, status) => request.get('/models', { params: { page, size, modelType, status } }),
  update: (id, data) => request.put(`/models/${id}`, data),
  delete: (id) => request.delete(`/models/${id}`),
  testConnection: (id) => request.post(`/models/${id}/test`)
}

// 评测Prompt API
export const promptApi = {
  add: (data) => request.post('/prompts', data),
  checkName: (name, excludeId) => request.get('/prompts/check-name', { params: { name, excludeId } }),
  list: (page = 1, size = 100) => request.get('/prompts', { params: { page, size } }),
  update: (id, data) => request.put(`/prompts/${id}`, data),
  delete: (id) => request.delete(`/prompts/${id}`),
  getById: (id) => request.get(`/prompts/${id}`),
  listVersions: (id) => request.get(`/prompts/${id}/versions`),
  getVersion: (id, version) => request.get(`/prompts/${id}/versions/${version}`),
  restoreVersion: (id, version) => request.post(`/prompts/${id}/versions/${version}/restore`),
  parsePlaceholders: (template) => request.get('/prompts/parse-placeholders', { params: { template } }),
  preview: (data) => request.post('/prompts/preview', data),
  polish: (modelId, dimensionsConfig) => request.post('/prompts/polish', { modelId, dimensionsConfig }),
  parseToDimensions: (modelId, text) => request.post('/prompts/parse-to-dimensions', { modelId, text })
}

// 评测任务 API
export const taskApi = {
  create: (data) => request.post('/tasks', data),
  list: (page = 1, size = 10) => request.get('/tasks', { params: { page, size } }),
  getById: (id) => request.get(`/tasks/${id}`),
  start: (id) => request.post(`/tasks/${id}/start`),
  cancel: (id) => request.post(`/tasks/${id}/cancel`),
  getProgress: (id) => request.get(`/tasks/${id}/progress`)
}

// 评测结果 API
export const resultApi = {
  list: (params) => request.get('/results', { params }),
  compare: (taskId) => request.get('/results/compare', { params: { taskId } }),
  compareWithJudge: (taskId) => request.get('/results/compare-with-judge', { params: { taskId } }),
  getSummary: (taskId) => request.get(`/results/${taskId}/summary`),
  listBadcases: (taskId, params) => request.get(`/results/${taskId}/badcases`, { params }),
  listJudgeResults: (taskId, params) => request.get(`/results/${taskId}/judge-results`, { params }),
  listDimensionResults: (taskId, params) => request.get(`/results/${taskId}/dimension-results`, { params }),
  // 人工校验
  submitHumanReview: (data) => request.post('/results/human-review', data),
  getHumanReviewStats: (taskId, params) => request.get(`/results/${taskId}/human-review/stats`, { params }),
  listReviewSamples: (taskId, params) => request.get(`/results/${taskId}/human-review/samples`, { params }),
  // 专家裁决
  listAdjudicationSamples: (taskId, params) => request.get(`/results/${taskId}/adjudication/samples`, { params }),
  adjudicate: (data) => request.post('/results/adjudicate', data),
  // 人机对比
  getHumanVsAiStats: (taskId, params) => request.get(`/results/${taskId}/human-vs-ai/stats`, { params }),
  listHumanVsAiSamples: (taskId, params) => request.get(`/results/${taskId}/human-vs-ai/samples`, { params })
}

// 报告 API
export const reportApi = {
  preview: (taskId) => request.get(`/reports/${taskId}/preview`),
  download: async (taskId) => {
    const token = localStorage.getItem('eval-token')
    const response = await fetch(`/api/reports/${taskId}/download`, {
      headers: { 'Authorization': `Bearer ${token}` }
    })
    if (!response.ok) throw new Error('下载失败')
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `eval_report_${taskId}.html`
    a.click()
    URL.revokeObjectURL(url)
  }
}

// Playground API
export const playgroundApi = {
  run: (data) => request.post('/playground/run', data)
}

// 异步任务 API（耗时操作改为异步提交+轮询）
export const asyncJobApi = {
  get: (id) => request.get(`/async-jobs/${id}`),
  getResult: (id) => request.get(`/async-jobs/${id}/result`)
}

// 登录/注册/鉴权 API
export const authApi = {
  login: (data) => request.post('/auth/login', data),
  register: (data) => request.post('/auth/register', data),
  me: () => request.get('/auth/me'),
  logout: () => request.post('/auth/logout')
}
