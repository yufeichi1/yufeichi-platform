<script setup lang="ts">
import { computed, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { publicApi } from "@/api/public";
import {
  positiveQuery,
  usePublicResource,
} from "@/composables/usePublicResource";
import PublicState from "@/components/PublicState.vue";
import ProjectCards from "@/components/ProjectCards.vue";
const route = useRoute(),
  router = useRouter(),
  page = computed(() => positiveQuery(route.query.page) || 1);
watch(
  () => route.query,
  () => {
    if (route.path !== "/projects") return;
    const q = page.value > 1 ? { page: String(page.value) } : {};
    if (JSON.stringify(route.query) !== JSON.stringify(q))
      void router.replace({ path: "/projects", query: q });
  },
  { immediate: true },
);
const { data, loading, error, load } = usePublicResource(
  () => route.fullPath,
  () => publicApi.projects(page.value),
);
</script>
<template>
  <section class="public-section">
    <div class="public-heading">
      <p class="eyebrow">SELECTED WORK</p>
      <h1>把想法，做出来。</h1>
      <p class="muted">用实际的项目，记录探索与实现的过程。</p>
    </div>
    <PublicState :loading="loading" :error="error" @retry="load"
      ><ProjectCards v-if="data?.records.length" :projects="data.records" />
      <div v-else class="page-state">
        <h2>项目正在整理中</h2>
        <p class="muted">新的作品会在这里分享。</p>
        <RouterLink v-if="page > 1" to="/projects">回到第一页</RouterLink>
      </div>
      <div v-if="data?.total" class="pagination">
        <span class="muted">共 {{ data.total }} 个项目 · 第 {{ page }} 页</span
        ><el-pagination
          :current-page="page"
          :total="data.total"
          :page-size="9"
          layout="prev,pager,next"
          :pager-count="5"
          @current-change="
            router.push({
              path: '/projects',
              query: $event > 1 ? { page: String($event) } : {},
            })
          "
        /></div
    ></PublicState>
  </section>
</template>
