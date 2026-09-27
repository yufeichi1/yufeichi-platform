<script setup lang="ts">
import type { ArticleSummary, Taxonomy } from "@/types/api";
import SafeImage from "./SafeImage.vue";
defineProps<{ articles: ArticleSummary[]; categories?: Taxonomy[] }>();
</script>
<template>
  <div class="article-cards">
    <article v-for="item in articles" :key="item.id" class="public-card">
      <RouterLink
        :to="'/articles/' + item.id"
        class="card-image"
        :aria-label="'阅读 ' + item.title"
        ><SafeImage :src="item.coverUrl" :alt="item.title"
      /></RouterLink>
      <div class="card-body">
        <p class="card-meta">
          <span>{{
            categories?.find((c) => c.id === item.categoryId)?.name || "随笔"
          }}</span
          ><time>{{ item.publishedAt?.slice(0, 10) }}</time>
        </p>
        <h2>
          <RouterLink :to="'/articles/' + item.id">{{ item.title }}</RouterLink>
        </h2>
        <p class="muted">{{ item.summary || "打开文章，继续阅读。" }}</p>
        <RouterLink class="read-link" :to="'/articles/' + item.id"
          >阅读全文 ↗</RouterLink
        >
      </div>
    </article>
  </div>
</template>
