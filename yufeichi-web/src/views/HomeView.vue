<script setup lang="ts">
import { publicApi } from "@/api/public";
import { usePublicResource } from "@/composables/usePublicResource";
import PublicState from "@/components/PublicState.vue";
import ArticleCards from "@/components/ArticleCards.vue";
import ProjectCards from "@/components/ProjectCards.vue";
const articles = usePublicResource(
  () => "",
  async () => {
    const [page, categories] = await Promise.all([
      publicApi.articles({ pageNum: 1, pageSize: 3 }),
      publicApi.categories(),
    ]);
    return { page, categories };
  },
);
const projects = usePublicResource(
  () => "",
  () => publicApi.projects(1, 3),
);
</script>
<template>
  <section class="public-hero">
    <div>
      <p class="eyebrow">NOTES, CODE & EVERYDAY DISCOVERIES</p>
      <h1>保持好奇，<br />把想法写成作品。</h1>
      <p class="hero-intro">
        你好，我是 Yufeichi。<br />在这里记录开发中的思考，分享正在构建的项目。
      </p>
      <div>
        <RouterLink to="/articles" class="primary-link">开始阅读 →</RouterLink
        ><RouterLink to="/about" class="read-link">关于我 ↗</RouterLink>
      </div>
    </div>
    <div class="hero-note" aria-hidden="true">
      <span>FIELD NOTES</span>
      <div class="note-circle">Y.</div>
      <p>思考 · 实践 · 记录</p>
      <small>A PERSONAL SPACE<br />FOR THINGS WORTH MAKING.</small>
    </div>
  </section>
  <section class="home-section">
    <div class="section-title">
      <div>
        <p class="eyebrow">FROM THE JOURNAL</p>
        <h2>最新文章</h2>
      </div>
      <RouterLink to="/articles">全部文章 ↗</RouterLink>
    </div>
    <PublicState
      :loading="articles.loading.value"
      :error="articles.error.value"
      @retry="articles.load"
      ><ArticleCards
        v-if="articles.data.value?.page.records.length"
        :articles="articles.data.value.page.records"
        :categories="articles.data.value.categories"
      />
      <div v-else class="page-state">
        <h3>还没有公开文章</h3>
        <p class="muted">新的思考会在这里留下记录。</p>
      </div></PublicState
    >
  </section>
  <section class="home-section">
    <div class="section-title">
      <div>
        <p class="eyebrow">BUILT WITH CURIOSITY</p>
        <h2>项目与实践</h2>
      </div>
      <RouterLink to="/projects">全部项目 ↗</RouterLink>
    </div>
    <PublicState
      :loading="projects.loading.value"
      :error="projects.error.value"
      @retry="projects.load"
      ><ProjectCards
        v-if="projects.data.value?.records.length"
        :projects="projects.data.value.records"
      />
      <div v-else class="page-state">
        <h3>项目正在整理中</h3>
        <p class="muted">每一个作品，都从一个小想法开始。</p>
      </div></PublicState
    >
  </section>
</template>
