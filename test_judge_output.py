#!/usr/bin/env python3
"""
测试 Judge Prompt 完整输出格式：
验证 AI 按照新格式（score 用 rubric 离散值、boolean 用 good/bad/unknown、overall 三态）输出
"""
import requests
import json
import sys
import re
import time

# ===== 配置 =====
API_BASE = "https://api.deepseek.com/v1/chat/completions"
API_KEY = "sk-9f9d3652d3fd489cb9705050f5726b83"
MODEL = "deepseek-chat"
TEMPERATURE = 0.7
MAX_TOKENS = 2048

# ===== 完整 Judge Prompt（模拟 PromptGenerator.generatePrompt 输出） =====
FULL_SYSTEM_PROMPT = """你是一名专业的 AI 回答质量评测专家。

以下是本次评测的具体配置：

【评测维度说明】
请根据用户提供的评测标准，对「模型回答」进行逐维度评分。
最终以「任一维度 badcase 即整体 badcase」规则汇总（any 规则）。

【数据注入模板】
以下是裁判模型在评测时会收到的输入数据（运行时替换为真实值）：
${question}：用户问题
${model_response}：模型回答
${reference_answer}：参考答案（如有）

【评测维度】

维度 1：准确性
评分方式：分数 1/3/5
badcase 阈值：低于 3 分判定为 badcase
评分细则：
- 5分：回答完全准确，无任何事实错误
- 3分：回答基本准确，有少量细节偏差但不影响核心结论
- 1分：回答有严重事实错误或完全不准确

维度 2：可用性
评分方式：采纳/不采纳
badcase 阈值：=不采纳 时判定为 badcase
评分细则：
- 采纳：回答对用户有实际帮助，可以采纳使用
- 不采纳：回答无帮助、答非所问或存在误导
（AI 无法判断时可输出 unknown）

维度 3：完整性
评分方式：分级 G/S/B
badcase 阈值：=B 时判定为 badcase
评分细则：
- G：全面覆盖问题各方面，信息充分
- S：覆盖了主要方面但有遗漏
- B：回答不完整，遗漏了关键信息

【评测示例】

示例 1：
用户问题：什么是机器学习？
模型回答：机器学习是人工智能的一个分支，它使计算机系统能够从数据中自动学习和改进。
期望输出：
{"dimensions":{"准确性":{"score":5,"reason":"定义准确无误"},"可用性":{"result":"good","reason":"直接回答了用户问题"},"完整性":{"result":"S","reason":"覆盖了核心定义但缺少方法分类"}},"overall":"good","reason":"回答准确可用，完整性略有不足但整体良好"}

【输出格式】
严格按照以下JSON格式输出，不得输出多余内容：

```json
{
  "dimensions": {
    "准确性": { "score": 1/3/5, "reason": "简要说明" },
    "可用性": { "result": "good/bad/unknown", "reason": "简要说明" },
    "完整性": { "result": "G/S/B", "reason": "简要说明" }
  },
  "overall": "good/bad/unknown",
  "reason": "整体判定理由"
}
```

注意：
- 准确性 score 只能取 1、3、5 三个整数值
- 可用性 result 只能取 good、bad、unknown 三个值
- 完整性 result 只能取 G、S、B 三个值
- overall 只能取 good、bad、unknown 三个值
- 如果数据不足以判断某个维度，该维度可输出 unknown

【思维链引导】
在输出最终JSON之前，请先逐步分析：
1. 逐个分析各评分维度，给出每个维度的判断依据
2. 综合各维度分析，给出整体判定理由
3. 最后输出符合格式要求的JSON"""


# ===== 测试用例 =====
TEST_CASES = [
    {
        "name": "测试1：正常问答（应该 good）",
        "user": "用户问题：什么是机器学习？\n模型回答：机器学习是人工智能的一个分支，它使计算机系统能够从数据中自动学习和改进，而无需被明确编程。常见的机器学习方法包括监督学习、无监督学习和强化学习。",
    },
    {
        "name": "测试2：明显错误回答（应该 bad）",
        "user": "用户问题：地球到太阳的距离是多少？\n模型回答：地球到太阳的距离大约是100万公里。",
    },
    {
        "name": "测试3：不相关回答（应该 bad）",
        "user": "用户问题：请推荐几本Python入门书籍\n模型回答：今天天气不错，适合出去散步。我建议你去公园走走，呼吸一下新鲜空气。",
    },
    {
        "name": "测试4：模糊回答（可能 unknown）",
        "user": "用户问题：这个药的副作用有哪些？\n模型回答：药物副作用因人而异，建议咨询专业医生或查看药品说明书获取详细信息。我无法为您提供具体的医疗建议。",
    },
    {
        "name": "测试5：乱码回答（应该 bad）",
        "user": "用户问题：如何学好英语？\n模型回答：啊啊啊啊！！！！##@@%%  乱码 test 123 abc def",
    },
    {
        "name": "测试6：过于简短（可能 bad）",
        "user": "用户问题：请详细介绍一下量子计算的原理和应用前景\n模型回答：量子计算就是用量子力学来计算。",
    },
    {
        "name": "测试7：英文问答",
        "user": "User question: What is the time complexity of binary search?\nModel answer: The time complexity of binary search is O(log n), because it halves the search space with each comparison.",
    },
    {
        "name": "测试8：带参考答案",
        "user": "用户问题：中国的首都是哪里？\n参考答案：北京\n模型回答：中国的首都是北京。",
    },
]


def call_api(system_prompt, user_prompt):
    """调用 DeepSeek API"""
    headers = {
        "Authorization": f"Bearer {API_KEY}",
        "Content-Type": "application/json"
    }
    body = {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt}
        ],
        "temperature": TEMPERATURE,
        "max_tokens": MAX_TOKENS
    }
    resp = requests.post(API_BASE, headers=headers, json=body, timeout=60)
    resp.raise_for_status()
    return resp.json()["choices"][0]["message"]["content"]


def validate_response(raw_text, test_name):
    """校验 AI 输出格式是否符合预期"""
    print(f"\n{'='*60}")
    print(f"📋 {test_name}")
    print(f"{'='*60}")
    print(f"原始输出:\n{raw_text}\n")

    # 提取 JSON
    json_str = raw_text.strip()
    if "```" in json_str:
        json_str = re.sub(r"```json?\s*", "", json_str).rstrip("`").strip()

    start = json_str.find("{")
    end = json_str.rfind("}")
    if start < 0 or end < 0:
        print("❌ 未找到 JSON 对象")
        return False
    json_str = json_str[start:end+1]

    try:
        obj = json.loads(json_str)
    except json.JSONDecodeError as e:
        print(f"❌ JSON 解析失败: {e}")
        return False

    errors = []
    warnings = []

    # 1. 检查 dimensions
    dims = obj.get("dimensions")
    if not dims or not isinstance(dims, dict):
        errors.append("缺少 dimensions 对象")
    else:
        for dim_name, dim_val in dims.items():
            if not isinstance(dim_val, dict):
                errors.append(f"维度「{dim_name}」不是对象")
                continue
            reason = dim_val.get("reason", "")
            if not reason:
                warnings.append(f"维度「{dim_name}」缺少 reason")

            if "准确性" in dim_name:
                score = dim_val.get("score")
                if score not in [1, 3, 5]:
                    errors.append(f"维度「{dim_name}」score={score}，应为 1/3/5")
                else:
                    print(f"  ✅ {dim_name}: score={score}")
            elif "可用性" in dim_name:
                result = dim_val.get("result", "")
                if result not in ["good", "bad", "unknown"]:
                    errors.append(f"维度「{dim_name}」result=\"{result}\"，应为 good/bad/unknown")
                else:
                    print(f"  ✅ {dim_name}: result=\"{result}\"")
            elif "完整性" in dim_name:
                result = dim_val.get("result", "")
                if result not in ["G", "S", "B"]:
                    errors.append(f"维度「{dim_name}」result=\"{result}\"，应为 G/S/B")
                else:
                    print(f"  ✅ {dim_name}: result=\"{result}\"")

    # 2. 检查 overall
    overall = obj.get("overall")
    if overall is None:
        if "is_badcase" in obj:
            warnings.append("使用了旧格式 is_badcase 而非 overall")
        else:
            errors.append("缺少 overall 字段")
    elif overall not in ["good", "bad", "unknown"]:
        errors.append(f"overall=\"{overall}\"，应为 good/bad/unknown")
    else:
        print(f"  ✅ overall=\"{overall}\"")

    # 3. 检查 reason
    if not obj.get("reason"):
        warnings.append("缺少整体 reason")

    # 汇总
    if errors:
        print(f"\n❌ 发现 {len(errors)} 个错误:")
        for e in errors:
            print(f"   - {e}")
    if warnings:
        print(f"\n⚠️  {len(warnings)} 个警告:")
        for w in warnings:
            print(f"   - {w}")
    if not errors and not warnings:
        print(f"\n🎉 完全通过!")
    elif not errors:
        print(f"\n✅ 格式正确（有 {len(warnings)} 个警告）")

    return len(errors) == 0


def main():
    if not API_KEY:
        print("请先在脚本中填入 API_KEY")
        sys.exit(1)

    print("🧪 Judge Prompt 完整测试")
    print(f"模型: {MODEL}")
    print(f"API: {API_BASE}")
    print(f"\n完整 System Prompt:\n{'─'*60}")
    print(FULL_SYSTEM_PROMPT)
    print(f"{'─'*60}")

    results = []
    for i, tc in enumerate(TEST_CASES):
        try:
            if i > 0:
                time.sleep(1)
            raw = call_api(FULL_SYSTEM_PROMPT, tc["user"])
            ok = validate_response(raw, tc["name"])
            results.append((tc["name"], ok))
        except Exception as e:
            print(f"\n❌ {tc['name']} 调用失败: {e}")
            results.append((tc["name"], False))

    # 汇总
    print(f"\n{'='*60}")
    print("📊 测试汇总")
    print(f"{'='*60}")
    passed = sum(1 for _, ok in results if ok)
    for name, ok in results:
        print(f"  {'✅' if ok else '❌'} {name}")
    print(f"\n通过: {passed}/{len(results)}")


if __name__ == "__main__":
    main()
