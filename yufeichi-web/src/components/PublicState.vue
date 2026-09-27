<script setup lang="ts">
import PageState from "./PageState.vue";
defineProps<{
  loading: boolean;
  error: string;
  notFound?: boolean;
  empty?: boolean;
}>();
defineEmits<{ retry: [] }>();
</script>
<template>
  <section v-if="notFound" class="public-not-found">
    <p class="eyebrow">404 / NOT FOUND</p>
    <h1>内容不存在</h1>
    <p class="muted">这条内容尚未公开、已下架或不存在。</p>
    <RouterLink to="/articles" class="primary-link">返回文章列表 →</RouterLink>
  </section>
  <PageState
    v-else
    :loading="loading"
    :error="error"
    :empty="empty"
    @retry="$emit('retry')"
    ><slot
  /></PageState>
</template>
