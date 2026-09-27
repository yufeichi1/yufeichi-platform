<script setup lang="ts">
defineProps<{ loading?: boolean; error?: string; empty?: boolean }>();
defineEmits<{ retry: [] }>();
</script>
<template>
  <div v-if="loading" class="page-state" role="status">正在加载内容…</div>
  <div v-else-if="error" class="page-state">
    <p role="alert" class="error-text">{{ error }}</p>
    <el-button @click="$emit('retry')">重新加载</el-button>
  </div>
  <div v-else-if="empty" class="page-state">
    <div class="empty-mark">✧</div>
    <h3>这里还没有内容</h3>
    <p class="muted">新建第一条记录，开始整理你的创作。</p>
  </div>
  <slot v-else />
</template>
