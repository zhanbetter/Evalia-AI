<template>
  <div class="not-found">
    <div class="not-found-content">
      <h1>404</h1>
      <p>页面不存在</p>
      <el-button type="primary" @click="$router.push('/dataset')">返回首页</el-button>
    </div>
  </div>
</template>

<script setup>
</script>

<style scoped>
.not-found {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  min-height: 0;
}
.not-found-content {
  text-align: center;
}
.not-found-content h1 {
  font-size: 72px;
  font-weight: 700;
  color: var(--text-muted, #9ca3af);
  margin: 0 0 8px;
}
.not-found-content p {
  font-size: 16px;
  color: var(--text-secondary, #6b7280);
  margin: 0 0 24px;
}
</style>
