<script setup lang="ts">
import { useRoute } from "vue-router";
import { publicApi } from "@/api/public";
import { usePublicResource, requireId } from "@/composables/usePublicResource";
import { externalUrl } from "@/utils/urls";
import PublicState from "@/components/PublicState.vue";
import SafeImage from "@/components/SafeImage.vue";
import MarkdownContent from "@/components/MarkdownContent.vue";
const route = useRoute();
const { data, loading, error, notFound, load } = usePublicResource(
  () => String(route.params.id),
  async () => publicApi.project(requireId(route.params.id)),
);
</script>
<template>
  <section class="public-section">
    <RouterLink to="/projects" class="back-link">← 所有项目</RouterLink
    ><PublicState
      :loading="loading"
      :error="error"
      :not-found="notFound"
      @retry="load"
      ><article v-if="data" class="reading-layout">
        <header class="reading-header">
          <p class="eyebrow">PROJECT / {{ data.techStack || "实践记录" }}</p>
          <h1>{{ data.name }}</h1>
          <div class="project-links">
            <a
              v-if="externalUrl(data.githubUrl)"
              :href="externalUrl(data.githubUrl)"
              target="_blank"
              rel="noopener noreferrer"
              >GitHub ↗</a
            ><a
              v-if="externalUrl(data.demoUrl)"
              :href="externalUrl(data.demoUrl)"
              target="_blank"
              rel="noopener noreferrer"
              >在线演示 ↗</a
            >
          </div>
        </header>
        <SafeImage
          v-if="data.coverUrl"
          class="detail-cover"
          :src="data.coverUrl"
          :alt="data.name"
        /><MarkdownContent :content="data.description" /></article
    ></PublicState>
  </section>
</template>
