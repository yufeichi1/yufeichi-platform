<script setup lang="ts">
import { useRoute } from "vue-router";
import { publicApi } from "@/api/public";
import { usePublicResource, requireId } from "@/composables/usePublicResource";
import PublicState from "@/components/PublicState.vue";
import SafeImage from "@/components/SafeImage.vue";
import MarkdownContent from "@/components/MarkdownContent.vue";
const route = useRoute();
const { data, loading, error, notFound, load } = usePublicResource(
  () => String(route.params.id),
  async () => {
    const [article, categories] = await Promise.all([
      publicApi.article(requireId(route.params.id)),
      publicApi.categories(),
    ]);
    return {
      article,
      category: categories.find((c) => c.id === article.categoryId),
    };
  },
);
</script>
<template>
  <section class="public-section">
    <RouterLink to="/articles" class="back-link">← 所有文章</RouterLink
    ><PublicState
      :loading="loading"
      :error="error"
      :not-found="notFound"
      @retry="load"
      ><article v-if="data" class="reading-layout">
        <header class="reading-header">
          <p class="eyebrow">
            THE JOURNAL / {{ data.category?.name || "随笔" }}
          </p>
          <h1>{{ data.article.title }}</h1>
          <p class="reading-meta">
            <time>{{
              data.article.publishedAt?.replace("T", " ").slice(0, 16)
            }}</time
            ><RouterLink
              v-for="tag in data.article.tags"
              :key="tag.id"
              :to="{ path: '/articles', query: { tag: tag.id } }"
              >#{{ tag.name }}</RouterLink
            >
          </p>
          <p v-if="data.article.summary" class="reading-summary">
            {{ data.article.summary }}
          </p>
        </header>
        <SafeImage
          v-if="data.article.coverUrl"
          class="detail-cover"
          :src="data.article.coverUrl"
          :alt="data.article.title"
        /><MarkdownContent :content="data.article.content" /><RouterLink
          v-if="data.category"
          class="read-link"
          :to="{ path: '/articles', query: { category: data.category.id } }"
          >更多「{{ data.category.name }}」文章 →</RouterLink
        >
      </article></PublicState
    >
  </section>
</template>
