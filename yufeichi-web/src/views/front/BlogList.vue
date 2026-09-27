<script setup lang="ts">
import { computed, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { publicApi } from "@/api/public";
import {
  positiveQuery,
  usePublicResource,
} from "@/composables/usePublicResource";
import PublicState from "@/components/PublicState.vue";
import ArticleCards from "@/components/ArticleCards.vue";
const route = useRoute(),
  router = useRouter();
const page = computed(() => positiveQuery(route.query.page) || 1),
  category = computed(() => positiveQuery(route.query.category)),
  tag = computed(() => positiveQuery(route.query.tag));
function setQuery(
  nextPage = 1,
  nextCategory: number | null | undefined = category.value,
  nextTag: number | null | undefined = tag.value,
) {
  return router.push({
    path: "/articles",
    query: {
      ...(nextPage > 1 ? { page: String(nextPage) } : {}),
      ...(nextCategory ? { category: String(nextCategory) } : {}),
      ...(nextTag ? { tag: String(nextTag) } : {}),
    },
  });
}
// Normalize malformed known parameters; URL is the only filter/pagination state.
watch(
  () => route.query,
  () => {
    if (route.path !== "/articles") return;
    const q = {
      ...(page.value > 1 ? { page: String(page.value) } : {}),
      ...(category.value ? { category: String(category.value) } : {}),
      ...(tag.value ? { tag: String(tag.value) } : {}),
    };
    if (JSON.stringify(route.query) !== JSON.stringify(q))
      void router.replace({ path: "/articles", query: q });
  },
  { immediate: true },
);
const { data, loading, error, load } = usePublicResource(
  () => route.fullPath,
  async () => {
    const [articles, categories, tags] = await Promise.all([
      publicApi.articles({
        pageNum: page.value,
        pageSize: 9,
        categoryId: category.value,
        tagId: tag.value,
      }),
      publicApi.categories(),
      publicApi.tags(),
    ]);
    return { articles, categories, tags };
  },
);
</script>
<template>
  <section class="public-section">
    <div class="public-heading">
      <p class="eyebrow">THE JOURNAL</p>
      <h1>思考，记录，分享。</h1>
      <p class="muted">在代码与日常之间，留下一些值得回看的文字。</p>
    </div>
    <form class="public-filters" @submit.prevent>
      <label
        >分类<select
          aria-label="筛选分类"
          :value="category || ''"
          @change="
            setQuery(
              1,
              positiveQuery(($event.target as HTMLSelectElement).value) || null,
              tag,
            )
          "
        >
          <option value="">全部分类</option>
          <option
            v-for="item in data?.categories"
            :key="item.id"
            :value="item.id"
          >
            {{ item.name }}
          </option>
          <option
            v-if="category && !data?.categories.some((c) => c.id === category)"
            :value="category"
          >
            分类 #{{ category }}
          </option>
        </select></label
      ><label
        >标签<select
          aria-label="筛选标签"
          :value="tag || ''"
          @change="
            setQuery(
              1,
              category,
              positiveQuery(($event.target as HTMLSelectElement).value) || null,
            )
          "
        >
          <option value="">全部标签</option>
          <option v-for="item in data?.tags" :key="item.id" :value="item.id">
            {{ item.name }}
          </option>
          <option
            v-if="tag && !data?.tags.some((t) => t.id === tag)"
            :value="tag"
          >
            标签 #{{ tag }}
          </option>
        </select></label
      ><RouterLink v-if="category || tag" to="/articles" class="clear-filter"
        >清除筛选</RouterLink
      >
    </form>
    <PublicState :loading="loading" :error="error" @retry="load"
      ><ArticleCards
        v-if="data?.articles.records.length"
        :articles="data.articles.records"
        :categories="data.categories" />
      <div v-else class="page-state">
        <h2>暂无匹配的文章</h2>
        <p class="muted">换个筛选条件，或稍后再来看看。</p>
        <RouterLink v-if="page > 1" to="/articles">回到第一页</RouterLink>
      </div>
      <div v-if="data?.articles.total" class="pagination">
        <span class="muted"
          >共 {{ data.articles.total }} 篇 · 第 {{ page }} 页</span
        ><el-pagination
          :current-page="page"
          :page-size="9"
          :total="data.articles.total"
          layout="prev,pager,next"
          :pager-count="5"
          @current-change="setQuery($event)"
        /></div
    ></PublicState>
  </section>
</template>
