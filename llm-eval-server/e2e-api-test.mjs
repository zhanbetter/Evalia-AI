#!/usr/bin/env node
/**
 * Evalia-AI 全流程接口集成测试
 * ─────────────────────────────
 * 覆盖：注册登录 → 模型管理 → 数据集上传 → 评估器 → 评测任务 → 结果查询 → 人工标注 → 金标准标注
 *
 * 用法：
 *   node e2e-api-test.mjs                          # 默认 http://localhost:8080/api
 *   BASE_URL=http://10.0.0.5:8080/api node e2e-api-test.mjs
 *
 * 零依赖：仅用 Node 18+ 原生 fetch。
 */

const BASE = process.env.BASE_URL || 'http://localhost:8080/api';
const INVITE_CODE = process.env.INVITE_CODE || 'evalia80';
const TS = Date.now(); // 随机后缀避免同名冲突

// ─── Helpers ────────────────────────────────────────────────────────────
let token = '';
const authHeaders = () => token ? { Authorization: `Bearer ${token}` } : {};

const req = async (method, path, { body, headers: extra, form } = {}) => {
  const url = BASE + path;
  const headers = { ...authHeaders(), ...extra };
  const opts = { method, headers };
  if (form) {
    opts.body = form;
    // fetch 会自动设置 multipart boundary，但需去掉 Content-Type 让浏览器/Node 自动补
    delete headers['Content-Type'];
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(body);
  }
  const res = await fetch(url, opts);
  const json = await res.json().catch(() => null);
  return { status: res.status, json };
};

let pass = 0, fail = 0;
const ok = (label) => { pass++; console.log(`  ✅ ${label}`); };
const ng = (label, msg) => { fail++; console.error(`  ❌ ${label}: ${msg}`); };
const expect = (label, cond, msg) => cond ? ok(label) : ng(label, msg);

// ─── Phase 1: Auth ─────────────────────────────────────────────────────
console.log('\n🔐 Phase 1 — 注册 / 登录');

const reg = await req('POST', '/auth/register', {
  body: { username: `e2e_${TS}`, password: 'Test@1234', nickname: 'E2E', inviteCode: INVITE_CODE }
});
expect('注册成功 (200)', reg.json?.code === 200, `code=${reg.json?.code} msg=${reg.json?.message}`);
expect('返回 token', !!reg.json?.data?.token, JSON.stringify(reg.json?.data));
token = reg.json?.data?.token || '';

const me = await req('GET', '/auth/me');
expect('获取当前用户', me.json?.code === 200 && me.json?.data?.username === `e2e_${TS}`,
  `username=${me.json?.data?.username}`);

// 重复注册应失败
const regDup = await req('POST', '/auth/register', {
  body: { username: `e2e_${TS}`, password: 'Test@1234', inviteCode: INVITE_CODE }
});
expect('重复注册拒绝', regDup.json?.code !== 200, `code=${regDup.json?.code}`);

// 错误密码登录
const loginBad = await req('POST', '/auth/login', {
  body: { username: `e2e_${TS}`, password: 'wrong' }
});
expect('错误密码拒绝', loginBad.status === 401 || loginBad.json?.code !== 200, `status=${loginBad.status}`);

// 正确登录
const loginOk = await req('POST', '/auth/login', {
  body: { username: `e2e_${TS}`, password: 'Test@1234' }
});
expect('正确登录', loginOk.json?.code === 200 && !!loginOk.json?.data?.token);
token = loginOk.json?.data?.token;

// ─── Phase 2: Models ───────────────────────────────────────────────────
console.log('\n🤖 Phase 2 — 模型管理');

const judgeModel = await req('POST', '/models', {
  body: {
    name: `e2e-judge-${TS}`, provider: 'openai', apiBase: 'https://api.openai.com/v1',
    apiKey: 'sk-test-placeholder', modelId: 'gpt-4o-mini', modelType: 'judge',
    temperature: 0.1, maxTokens: 1024
  }
});
expect('创建裁判模型', judgeModel.json?.code === 200, JSON.stringify(judgeModel.json));
const judgeId = judgeModel.json?.data?.id;

const evalModel = await req('POST', '/models', {
  body: {
    name: `e2e-evaluated-${TS}`, provider: 'openai', apiBase: 'https://api.openai.com/v1',
    apiKey: 'sk-test-placeholder', modelId: 'gpt-4o-mini', modelType: 'evaluated',
    temperature: 0.7, maxTokens: 2048
  }
});
expect('创建被测模型', evalModel.json?.code === 200);
const evalId = evalModel.json?.data?.id;

const modelList = await req('GET', '/models?page=1&size=100');
expect('模型列表非空', modelList.json?.data?.records?.length > 0,
  `count=${modelList.json?.data?.records?.length}`);

// 名称唯一性检验
const nameCheck = await req('GET', `/models?name=${encodeURIComponent(`e2e-judge-${TS}`)}`);
expect('按名查模型存在', nameCheck.json?.code === 200);

// ─── Phase 3: Dataset ──────────────────────────────────────────────────
console.log('\n📦 Phase 3 — 数据集');

// 上传 JSON 文件（multipart/form-data）
const datasetPayload = [
  { question: '北京是中国的首都吗？', reference_answer: '是的', context: '' },
  { question: '太阳从哪个方向升起？', reference_answer: '东方', context: '' },
  { question: '1+1等于多少？', reference_answer: '2', context: '' },
  { question: '水的化学式是什么？', reference_answer: 'H₂O', context: '' },
  { question: '地球有几大洲？', reference_answer: '七大洲', context: '' },
];
const jsonBlob = new Blob([JSON.stringify(datasetPayload)], { type: 'application/json' });

const form = new FormData();
form.append('file', jsonBlob, 'e2e-dataset.json');
form.append('name', `e2e-dataset-${TS}`);
form.append('description', 'E2E测试数据集');
form.append('hasReference', '1');
form.append('hasModelResponse', '0');

const dsUpload = await req('POST', '/datasets/upload', { form });
expect('上传数据集', dsUpload.json?.code === 200, JSON.stringify(dsUpload.json));
const dsId = dsUpload.json?.data?.id;

if (dsId) {
  // 检查条目数
  const dsItems = await req('GET', `/datasets/${dsId}/items?page=1&size=100`);
  expect('条目数=5', dsItems.json?.data?.records?.length === 5,
    `got=${dsItems.json?.data?.records?.length}`);

  // 获取 Schema
  const dsSchema = await req('GET', `/datasets/${dsId}/schema`);
  expect('Schema 存在', dsSchema.json?.code === 200 && Array.isArray(dsSchema.json?.data));

  // 数据集详情
  const dsDetail = await req('GET', `/datasets/${dsId}`);
  expect('数据集详情', dsDetail.json?.code === 200 && dsDetail.json?.data?.name === `e2e-dataset-${TS}`);

  // 数据集列表
  const dsList = await req('GET', '/datasets?page=1&size=10');
  expect('数据集列表含新条目', dsList.json?.data?.records?.some(d => d.id === dsId));

  // 名称检查
  const dsNameCheck = await req('GET', `/datasets/check-name?name=${encodeURIComponent(`e2e-dataset-${TS}`)}`);
  expect('重名检测', dsNameCheck.json?.code === 200);

  // 手动添加条目
  const addItem = await req('POST', `/datasets/${dsId}/items`, {
    body: { question: '手动添加的问题？', reference_answer: '手动答案', context: '' }
  });
  expect('手动添加条目', addItem.json?.code === 200 && addItem.json?.data?.id);
  const newItemId = addItem.json?.data?.id;

  // 编辑条目
  if (newItemId) {
    const editItem = await req('PUT', `/datasets/items/${newItemId}`, {
      body: { question: '编辑后的问题', reference_answer: '编辑后答案' }
    });
    expect('编辑条目', editItem.json?.code === 200);
  }

  // 金标准标注（条目级）
  const firstItemId = dsItems.json?.data?.records?.[0]?.id;
  if (firstItemId) {
    const gold1 = await req('POST', `/datasets/${dsId}/gold-annotations/annotate`, {
      body: { datasetItemId: firstItemId, annotator: 'annotator_A', role: 'ANNOTATOR', isBadcase: 1, comment: '测试标注' }
    });
    expect('金标准标注 (标注者A)', gold1.json?.code === 200);

    const gold2 = await req('POST', `/datasets/${dsId}/gold-annotations/annotate`, {
      body: { datasetItemId: firstItemId, annotator: 'annotator_B', role: 'EXPERT', isBadcase: 0 }
    });
    expect('金标准标注 (标注者B)', gold2.json?.code === 200);

    const goldStats = await req('GET', `/datasets/${dsId}/gold-annotations/stats`);
    expect('金标准统计', goldStats.json?.code === 200, JSON.stringify(goldStats.json?.data));

    const goldList = await req('GET', `/datasets/${dsId}/gold-annotations?page=1&size=20`);
    expect('金标准列表', goldList.json?.code === 200);
  }
}

// ─── Phase 4: Prompt (评估器) ──────────────────────────────────────────
console.log('\n📝 Phase 4 — 评估器');

const prompt = await req('POST', '/prompts', {
  body: {
    name: `e2e-prompt-${TS}`,
    description: 'E2E测试评估器',
    promptTemplate: '评估以下回复的质量。\n\n问题：{question}\n参考答案：{reference_answer}\n模型回复：{model_response}\n\n请输出JSON：{"is_badcase": false, "reason": ""}',
    evaluationMode: 'reference'
  }
});
expect('创建评估器', prompt.json?.code === 200, JSON.stringify(prompt.json));
const promptId = prompt.json?.data?.id;

if (promptId) {
  // 名称唯一性
  const pCheck = await req('GET', `/prompts/check-name?name=${encodeURIComponent(`e2e-prompt-${TS}`)}`);
  expect('评估器重名检测', pCheck.json?.code === 200);

  // 评估器列表
  const pList = await req('GET', '/prompts?page=1&size=100');
  expect('评估器列表含新条目', pList.json?.data?.records?.some(p => p.id === promptId));

  // 评估器详情
  const pDetail = await req('GET', `/prompts/${promptId}`);
  expect('评估器详情', pDetail.json?.code === 200 && pDetail.json?.data?.name === `e2e-prompt-${TS}`);

  // 版本历史
  const pVersions = await req('GET', `/prompts/${promptId}/versions`);
  expect('评估器版本历史', pVersions.json?.code === 200);
}

// ─── Phase 5: Task (评测任务) ──────────────────────────────────────────
console.log('\n🚀 Phase 5 — 评测任务');

const taskBody = {
  name: `e2e-task-${TS}`,
  datasetId: dsId,
  judgeModelId: judgeId,
  answerSource: 'dataset',
  modelConfigIds: [evalId],
  promptIds: [promptId]
};
const taskCreate = await req('POST', '/tasks', { body: taskBody });
expect('创建任务', taskCreate.json?.code === 200, JSON.stringify(taskCreate.json));
const taskId = taskCreate.json?.data?.id;

if (taskId) {
  // 任务详情
  const tDetail = await req('GET', `/tasks/${taskId}`);
  expect('任务详情', tDetail.json?.code === 200 && tDetail.json?.data?.status === 'PENDING');

  // 任务列表
  const tList = await req('GET', '/tasks?page=1&size=100');
  expect('任务列表含新条目', tList.json?.data?.records?.some(t => t.id === taskId));

  // 启动任务（需要 Kafka + MinIO，可能失败，记录但不阻断）
  const tStart = await req('POST', `/tasks/${taskId}/start`);
  if (tStart.json?.code === 200) {
    ok('任务启动成功');
  } else {
    console.log(`  ⚠️  任务启动失败（预期：缺少 Kafka/MinIO）: ${tStart.json?.message || tStart.status}`);
  }

  // 查进度
  const tProgress = await req('GET', `/tasks/${taskId}/progress`);
  expect('查询任务进度', tProgress.json?.code === 200, `progress=${tProgress.json?.data}`);
}

// ─── Phase 6: Results (结果查询) ───────────────────────────────────────
console.log('\n📊 Phase 6 — 结果查询');

if (taskId) {
  // 汇总统计
  const summary = await req('GET', `/results/${taskId}/summary`);
  expect('结果汇总', summary.json?.code === 200, `count=${summary.json?.data?.length}`);

  // 全部结果（Judge Results）
  const jr = await req('GET', `/results/${taskId}/judge-results`);
  expect('Judge 结果查询', jr.json?.code === 200, `count=${jr.json?.data?.length}`);

  // Badcase 列表
  const bc = await req('GET', `/results/${taskId}/badcases?page=1&size=20`);
  expect('Badcase 列表', bc.json?.code === 200, `total=${bc.json?.data?.total}`);

  // 维度结果
  const dim = await req('GET', `/results/${taskId}/dimension-results`);
  expect('维度结果', dim.json?.code === 200, `count=${dim.json?.data?.length}`);

  // 人机对比统计
  const hvAi = await req('GET', `/results/${taskId}/human-vs-ai/stats`);
  expect('人机对比统计', hvAi.json?.code === 200);

  // 版本对比
  const cmp = await req('GET', `/results/compare-with-judge?taskId=${taskId}`);
  expect('版本对比', cmp.json?.code === 200);
}

// ─── Phase 7: Human Review (人工标注) ──────────────────────────────────
console.log('\n👥 Phase 7 — 人工标注');

if (taskId) {
  // 提交人工标注
  const hrPayload = {
    taskId, modelConfigId: evalId, promptId, datasetItemId: 1,
    isBadcaseHuman: 1, reviewer: 'e2e_reviewer', role: 'normal', comment: 'E2E测试标注'
  };
  // datasetItemId=1 可能不存在，记录结果
  const hrSubmit = await req('POST', '/results/human-review', { body: hrPayload });
  if (hrSubmit.json?.code === 200) {
    ok('提交人工标注');
  } else {
    console.log(`  ⚠️  人工标注提交: ${hrSubmit.json?.message || hrSubmit.status}`);
  }

  // 人工审核统计
  const hrStats = await req('GET', `/results/${taskId}/human-review/stats`);
  expect('人工审核统计', hrStats.json?.code === 200, JSON.stringify(hrStats.json?.data));

  // 人工审核样本列表
  const hrSamples = await req('GET', `/results/${taskId}/human-review/samples?page=1&size=20`);
  expect('人工审核样本列表', hrSamples.json?.code === 200, `count=${hrSamples.json?.data?.length}`);

  // 专家裁决样本
  const adjSamples = await req('GET', `/results/${taskId}/adjudication/samples?page=1&size=20`);
  expect('专家裁决样本列表', adjSamples.json?.code === 200);
}

// ─── Phase 8: Cleanup (清理) ───────────────────────────────────────────
console.log('\n🧹 Phase 8 — 清理测试数据');

const delPrompt = await req('DELETE', `/prompts/${promptId}`);
expect('删除评估器', delPrompt.json?.code === 200, delPrompt.json?.message);

const delEvalModel = await req('DELETE', `/models/${evalId}`);
expect('删除被测模型', delEvalModel.json?.code === 200, delEvalModel.json?.message);

const delJudgeModel = await req('DELETE', `/models/${judgeId}`);
expect('删除裁判模型', delJudgeModel.json?.code === 200, delJudgeModel.json?.message);

const delDs = await req('DELETE', `/datasets/${dsId}`);
expect('删除数据集', delDs.json?.code === 200, delDs.json?.message);

// 验证已删除
const dsAfterDel = await req('GET', `/datasets/${dsId}`);
expect('数据集已删（查不到）', dsAfterDel.status >= 400 || dsAfterDel.json?.code !== 200);

// ─── Summary ───────────────────────────────────────────────────────────
console.log('\n' + '═'.repeat(50));
console.log(`  结果：✅ ${pass} 通过  ❌ ${fail} 失败`);
console.log('═'.repeat(50));
if (fail > 0) process.exit(1);
