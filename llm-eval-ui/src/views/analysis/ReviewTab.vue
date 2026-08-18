<template>
  <div class="review-tab">
    <!-- 角色切换 + 身份 -->
    <div class="reviewer-bar">
      <span class="reviewer-label">我的身份：</span>
      <el-input v-model="myName" placeholder="名字/工号" size="small" style="width: 160px" clearable />
      <span class="role-chip" :class="{ active: myRole === 'normal' }" @click="setRole('normal')">普通评委</span>
      <span class="role-chip" :class="{ active: myRole === 'expert' }" @click="setRole('expert')">专家</span>
      <span class="reviewer-hint" v-if="myRole === 'expert'">（可看所有人判定并裁决）</span>
    </div>

    <!-- 子 Tab：人工标注 / 人机对比 -->
    <el-tabs v-model="reviewSubTab" class="review-sub-tabs">
      <el-tab-pane name="annotation">
        <template #label><span class="tab-label"><el-icon :size="13"><User /></el-icon> 人工标注</span></template>
        <annotation-tab :task-id="taskId" :my-name="myName" :my-role="myRole"
          :models="models" :prompts="prompts" :model-map="modelMap" :prompt-map="promptMap"
          :focus-sample="focusSample" />
      </el-tab-pane>
      <el-tab-pane name="compare">
        <template #label><span class="tab-label"><el-icon :size="13"><Cpu /></el-icon> 人机对比</span></template>
        <compare-tab :task-id="taskId" :models="models" :prompts="prompts"
          :model-map="modelMap" :prompt-map="promptMap" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script>
import { ref, watch } from 'vue'
import AnnotationTab from '../review/AnnotationTab.vue'
import CompareTab from '../review/CompareTab.vue'

export default {
  name: 'ReviewTab',
  components: { AnnotationTab, CompareTab },
  props: {
    taskId: Number, models: Array, prompts: Array, modelMap: Object, promptMap: Object,
    focusSample: Object
  },
  setup(props) {
    const myName = ref(localStorage.getItem('eval-reviewer') || '')
    const myRole = ref(localStorage.getItem('eval-reviewer-role') || 'normal')
    const reviewSubTab = ref('annotation')

    const setRole = (r) => {
      myRole.value = r
      localStorage.setItem('eval-reviewer-role', r)
    }

    // 当从失败案例跳转过来且指定了 focusSample，切到人工标注
    watch(() => props.focusSample, (s) => {
      if (s) reviewSubTab.value = 'annotation'
    })

    return {
      myName, myRole, setRole, reviewSubTab
    }
  }
}
</script>

<style scoped>
.review-tab { }
.reviewer-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; padding: 8px 12px; background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; flex-wrap: wrap; }
.reviewer-label { font-size: 12px; font-weight: 600; color: var(--text-sec); }
.reviewer-hint { font-size: 11px; color: var(--text-mute); }
.role-chip { padding: 3px 12px; border-radius: 20px; font-size: 11px; cursor: pointer; border: 1px solid var(--border); background: var(--bg-input); color: var(--text-sec); user-select: none; }
.role-chip:hover { border-color: #8b5cf6; color: #8b5cf6; }
.role-chip.active { background: rgba(139,92,246,0.12); border-color: #8b5cf6; color: #8b5cf6; font-weight: 600; }
.review-sub-tabs :deep(.el-tabs__header) { margin-bottom: 12px; }
.review-sub-tabs :deep(.el-tabs__item.is-active) { color: #8b5cf6; }
.review-sub-tabs :deep(.el-tabs__active-bar) { background-color: #8b5cf6; }
.tab-label { display: inline-flex; align-items: center; gap: 4px; }
</style>
