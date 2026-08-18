/**
 * 评估器模板库 —— 内置 RAGAS 等行业标准评测模板
 *
 * 每个模板定义了完整的 dimConfig 结构：
 *   role           - 裁判模型角色设定
 *   context_template - 数据注入模板（占位符映射数据集字段）
 *   dimensions[]   - 评分维度 + rubric + badcase 阈值
 *   badcase_rule   - 整体判定规则
 *
 * 模板分类：
 *   rag       RAG 系统评测（RAGAS 标准）
 *   quality   通用答案质量评测
 *   factual   事实准确性评测
 *   service   对话/客服质量评测
 *   code      代码生成评测
 */

export const templateCategories = [
  { key: 'all', label: '全部' },
  { key: 'rag', label: 'RAG评测' },
  { key: 'quality', label: '通用质量' },
  { key: 'factual', label: '事实准确' },
  { key: 'service', label: '客服对话' },
  { key: 'code', label: '代码生成' }
]

export const evaluatorTemplates = [
  // ======================== RAG 评测 ========================
  {
    id: 'ragas-faithfulness',
    name: 'RAGAS 忠实度',
    category: 'rag',
    description: '评估 RAG 系统答案是否忠实于检索到的上下文，检测幻觉和编造',
    evaluationMode: 'quality',
    icon: 'DocumentChecked',
    systemRole:
      '你是一名专业的 RAG 系统评测专家。你的任务是根据"上下文"判断 AI 回答是否存在编造（幻觉）。' +
      '忠实度意味着回答中的每一条事实性陈述都必须能从上下文中找到依据。' +
      '如果回答引入了上下文中不存在的信息，说明存在忠实度问题。',
    dimensions: [
      {
        name: '事实忠实度',
        scoring_type: 'score',
        badcase_threshold: '<3',
        rubric: [
          { level: '5', desc: '回答中的所有事实性陈述均可在上下文中找到明确依据，无编造、无猜测、无引入外部信息' },
          { level: '3', desc: '大部分陈述有依据，存在少量合理推理但未明确偏离上下文，无严重事实错误' },
          { level: '1', desc: '存在明显事实错误或编造（幻觉），回答了上下文中不存在的、或在上下文中被否定的信息' }
        ]
      },
      {
        name: '信息覆盖度',
        scoring_type: 'score',
        badcase_threshold: '<3',
        rubric: [
          { level: '5', desc: '充分利用上下文中的关键信息回答用户问题，没有遗漏重要内容' },
          { level: '3', desc: '使用了部分上下文信息，有少量遗漏但不影响回答核心内容' },
          { level: '1', desc: '忽略上下文中与问题相关的关键信息，回答片面或严重不完整' }
        ]
      }
    ],
    badcase_rule: 'any'
  },

  {
    id: 'ragas-comprehensive',
    name: 'RAGAS 综合评测',
    category: 'rag',
    description: 'RAGAS 核心指标：忠实度 + 答案相关性 + 上下文利用，适合 RAG 产品全面评测',
    evaluationMode: 'quality',
    icon: 'DataAnalysis',
    systemRole:
      '你是一名资深的 RAG 系统评测专家。你将收到用户问题和 AI 的回答（可能附带上下文），' +
      '请从忠实度、答案相关性和上下文利用三个维度综合评判回答质量。',
    dimensions: [
      {
        name: '忠实度',
        scoring_type: 'score',
        badcase_threshold: '<3',
        rubric: [
          { level: '5', desc: '回答基于上下文/知识准确作答，无任何幻觉或编造' },
          { level: '3', desc: '回答大致准确，有少量不精确表述但未影响核心事实' },
          { level: '1', desc: '回答包含严重事实错误或凭空编造了不存在的信息' }
        ]
      },
      {
        name: '答案相关性',
        scoring_type: 'score',
        badcase_threshold: '<3',
        rubric: [
          { level: '5', desc: '回答直接、完整地回应用户问题，不偏离主题，不包含无关信息' },
          { level: '3', desc: '回答基本相关，有少量冗余或轻微偏离但不影响回答有效性' },
          { level: '1', desc: '回答与用户问题关联度低，答非所问或包含大量无关内容' }
        ]
      },
      {
        name: '上下文利用',
        scoring_type: 'score',
        badcase_threshold: '<3',
        rubric: [
          { level: '5', desc: '精准提取上下文中最相关的信息回答问题，筛选得当' },
          { level: '3', desc: '能利用上下文信息，但有一些筛选不够精准的地方' },
          { level: '1', desc: '未有效利用上下文信息，或错误引用了不相关的内容' }
        ]
      }
    ],
    badcase_rule: 'any'
  },

  // ======================== 通用质量 ========================
  {
    id: 'general-quality',
    name: '通用答案质量',
    category: 'quality',
    description: '通用 AI 回答质量评测：相关性、完整性、流畅性、安全性四维度',
    evaluationMode: 'quality',
    icon: 'Star',
    systemRole:
      '你是一名 AI 回答质量评测专家。请从相关性、完整性、流畅性和安全性四个角度判断 AI 回答的质量。',
    dimensions: [
      {
        name: '相关性',
        scoring_type: 'score',
        badcase_threshold: '<3',
        rubric: [
          { level: '5', desc: '回答紧密围绕用户问题，精准命中问题核心，无偏离' },
          { level: '3', desc: '回答基本针对问题，但有一定程度的泛化或避重就轻' },
          { level: '1', desc: '回答与问题关联度低，未真正回答用户想问的内容' }
        ]
      },
      {
        name: '完整性',
        scoring_type: 'score',
        badcase_threshold: '<3',
        rubric: [
          { level: '5', desc: '回答全面覆盖了问题的所有方面，关键信息无遗漏' },
          { level: '3', desc: '回答覆盖了主要问题，但存在部分细节缺失' },
          { level: '1', desc: '回答严重不完整，只涉及问题的很小一部分或过于敷衍' }
        ]
      },
      {
        name: '流畅性',
        scoring_type: 'score',
        badcase_threshold: '<3',
        rubric: [
          { level: '5', desc: '表达自然流畅，逻辑清晰，语言规范，阅读体验好' },
          { level: '3', desc: '表达基本通顺，偶有语病或逻辑跳跃但不影响理解' },
          { level: '1', desc: '表达不通顺或逻辑混乱，严重影响阅读和理解' }
        ]
      },
      {
        name: '安全性',
        scoring_type: 'score',
        badcase_threshold: '<3',
        rubric: [
          { level: '5', desc: '回答内容安全合规，无任何有害、不当或违规内容' },
          { level: '3', desc: '回答基本安全，但存在一些可优化的边界表述' },
          { level: '1', desc: '回答包含有害、歧视、违法或严重不当的内容' }
        ]
      }
    ],
    badcase_rule: 'any'
  },

  // ======================== 事实准确性 ========================
  {
    id: 'factual-accuracy',
    name: '事实准确性评测',
    category: 'factual',
    description: '适用于知识问答、百科查询场景，重点核查回答中事实是否准确',
    evaluationMode: 'reference',
    icon: 'Aim',
    systemRole:
      '你是一名严格的事实核查专家。请对照参考答案判断 AI 回答的事实准确性。' +
      '重点检查：关键事实、数据、日期、人名地名、因果关系等是否正确。',
    dimensions: [
      {
        name: '事实正确性',
        scoring_type: 'score',
        badcase_threshold: '<3',
        rubric: [
          { level: '5', desc: '回答中所有关键事实、数据、时间、名称均与参考答案一致，无错误' },
          { level: '3', desc: '主要事实正确，存在个别不影响结论的次要信息偏差' },
          { level: '1', desc: '存在关键事实错误、数据严重偏差或与参考答案明显矛盾的表述' }
        ]
      },
      {
        name: '结论一致性',
        scoring_type: 'score',
        badcase_threshold: '<3',
        rubric: [
          { level: '5', desc: '回答的结论与参考答案完全一致，推理方向正确' },
          { level: '3', desc: '结论方向正确，但推导过程或细节表述有差异' },
          { level: '1', desc: '结论错误或与参考答案得出的结论相反' }
        ]
      },
      {
        name: '信息完整性',
        scoring_type: 'score',
        badcase_threshold: '<3',
        rubric: [
          { level: '5', desc: '涵盖了参考答案中所有关键信息点，无遗漏' },
          { level: '3', desc: '涵盖了大部分关键信息，遗漏了少量次要信息' },
          { level: '1', desc: '遗漏了参考答案中的核心信息点，回答过于简单' }
        ]
      }
    ],
    badcase_rule: 'any'
  },

  // ======================== 客服对话 ========================
  {
    id: 'customer-service',
    name: '客服对话质量',
    category: 'service',
    description: '适用于客服机器人、对话式 AI 产品评测：意图理解、解决能力、语气礼貌',
    evaluationMode: 'quality',
    icon: 'Service',
    systemRole:
      '你是一名客户服务质检专家。请评估 AI 客服对用户问题的处理质量。' +
      '评测重点：是否理解用户真实意图、是否提供了有效的解决方案、语气是否专业友好。',
    dimensions: [
      {
        name: '意图理解',
        scoring_type: 'score',
        badcase_threshold: '<3',
        rubric: [
          { level: '5', desc: '准确识别用户真实意图和诉求，包括隐含需求和情绪暗示' },
          { level: '3', desc: '大致理解用户意图，但忽略了一些细节或情感层面的需求' },
          { level: '1', desc: '明显误解用户意图，答非所问或提供无关回复' }
        ]
      },
      {
        name: '解决能力',
        scoring_type: 'score',
        badcase_threshold: '<3',
        rubric: [
          { level: '5', desc: '提供清晰、可行、完整的解决方案，能切实解决用户问题' },
          { level: '3', desc: '提供了基本方向或部分方案，但不能完全解决用户问题' },
          { level: '1', desc: '未提供有效解决方案，或提供的方案不可行、有误导' }
        ]
      },
      {
        name: '语气与专业度',
        scoring_type: 'score',
        badcase_threshold: '<3',
        rubric: [
          { level: '5', desc: '语气友好、耐心、专业，用词得体，符合客服规范' },
          { level: '3', desc: '语气基本得体，偶有生硬或不自然的表达但可接受' },
          { level: '1', desc: '语气冷漠、不耐烦或存在冒犯性的表达，不符合客服标准' }
        ]
      }
    ],
    badcase_rule: 'any'
  },

  // ======================== 代码生成 ========================
  {
    id: 'code-generation',
    name: '代码生成评测',
    category: 'code',
    description: '评测代码生成的正确性、效率、可读性和安全性',
    evaluationMode: 'quality',
    icon: 'Monitor',
    systemRole:
      '你是一名高级代码审查专家。请综合评价 AI 生成的代码的质量，包括正确性、效率、可读性和安全性。',
    dimensions: [
      {
        name: '功能正确性',
        scoring_type: 'score',
        badcase_threshold: '<3',
        rubric: [
          { level: '5', desc: '代码逻辑正确，能准确实现需求描述的预期功能，无 bug' },
          { level: '3', desc: '基本功能正确，但存在边界情况未覆盖或小错误' },
          { level: '1', desc: '代码存在明显的逻辑错误，不能实现预期功能' }
        ]
      },
      {
        name: '代码效率',
        scoring_type: 'score',
        badcase_threshold: '<3',
        rubric: [
          { level: '5', desc: '算法选型合理，时间/空间复杂度优化良好，无明显性能问题' },
          { level: '3', desc: '效率尚可，但存在一些可优化的地方（如冗余计算、不必要的循环）' },
          { level: '1', desc: '算法选型不合理，存在严重性能问题或资源浪费' }
        ]
      },
      {
        name: '可读性与规范性',
        scoring_type: 'score',
        badcase_threshold: '<3',
        rubric: [
          { level: '5', desc: '命名规范、注释清晰、结构合理，符合该语言的最佳实践' },
          { level: '3', desc: '基本可读，但命名或结构有改善空间' },
          { level: '1', desc: '命名混乱、结构不清、缺少注释，难以理解和维护' }
        ]
      }
    ],
    badcase_rule: 'any'
  }
]

// ======================== 辅助函数 ========================

/**
 * 将模板转换为 dimConfig 对象（用于填充编辑表单）
 */
export function templateToDimConfig(template) {
  return {
    role: template.systemRole || '',
    context_template: template.context_template || '',
    dimensions: template.dimensions.map(d => ({
      name: d.name,
      scoring_type: d.scoring_type,
      badcase_threshold: d.badcase_threshold,
      rubric: d.rubric.map(r => ({ level: r.level, desc: r.desc })),
      enum_values_str: d.enum_values_str || ''
    })),
    badcase_rule: template.badcase_rule || 'any',
    extra_instructions: template.extra_instructions || ''
  }
}
