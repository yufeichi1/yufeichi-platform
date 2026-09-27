<script setup lang="ts">
import { useRoute } from "vue-router";
import { safeRedirect } from "@/router";
defineProps<{
  code: string;
  title: string;
  description: string;
  retry?: boolean;
}>();
const route = useRoute();
</script>
<template>
  <section class="status-page">
    <p class="eyebrow">{{ code }}</p>
    <h1>{{ title }}</h1>
    <p>{{ description }}</p>
    <RouterLink
      class="primary-link"
      :to="
        retry
          ? safeRedirect(route.query.redirect)
          : code === '404'
            ? '/'
            : '/admin/articles'
      "
      >{{
        retry ? "重新连接" : code === "404" ? "返回首页" : "返回文章管理"
      }}</RouterLink
    ><RouterLink v-if="code !== '404'" to="/login">切换账号</RouterLink>
  </section>
</template>
