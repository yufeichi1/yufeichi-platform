<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount } from "vue";
import { toolsStatus, queryTools, type Scope, type ReadResult } from "@/api/tools";
import { errorMessage } from "@/api/http";
const keyword = ref(""), scope = ref<Scope>("ALL"), available = ref(false), loading = ref(false), ready = ref(false);
const result = ref<ReadResult>(), error = ref("");
const canQuery = computed(() => available.value && !loading.value && !!keyword.value.trim() && keyword.value.length <= 80);
const labels: Record<string, string> = { searchArticles: "搜索公开文章", searchProjects: "搜索公开项目", getArticleDetail: "读取首篇文章", getProjectDetail: "读取首个项目" };
const statusController = new AbortController();
let controller: AbortController | undefined, disposed = false;
onMounted(async () => {
  try { const status = await toolsStatus(statusController.signal); if (!disposed) available.value = status.available; }
  catch (e) { if (!disposed) error.value = errorMessage(e); }
  finally { if (!disposed) ready.value = true; }
});
async function query() {
  if (!canQuery.value) return;
  result.value = undefined; error.value = ""; loading.value = true;
  const current = new AbortController(); controller = current;
  try { const value = await queryTools(keyword.value, scope.value, current.signal); if (!disposed && !current.signal.aborted) result.value = value; }
  catch (e) { if (!disposed) error.value = current.signal.aborted ? "已停止查询。" : errorMessage(e); }
  finally { if (!disposed) loading.value = false; if (controller === current) controller = undefined; }
}
onBeforeUnmount(() => { disposed = true; statusController.abort(); controller?.abort(); keyword.value = ""; result.value = undefined; });
</script>
<template>
  <section class="tools-page" :aria-busy="loading">
    <h1>站内内容查询</h1>
    <p class="muted">按文章标题和项目名称查找公开内容，展示每类首项的原文摘录。这是固定查询流程，不由模型生成答案。</p>
    <div class="panel">
      <p v-if="ready && !available && !error" role="status">内容查询尚未启用，你仍可浏览文章和项目。</p>
      <form @submit.prevent="query">
        <label for="tool-keyword">关键词</label>
        <el-input id="tool-keyword" v-model="keyword" maxlength="80" show-word-limit :disabled="loading" placeholder="例如：Java" />
        <label for="tool-scope">查询范围</label>
        <select id="tool-scope" v-model="scope" :disabled="loading"><option value="ALL">文章和项目</option><option value="ARTICLE">仅文章</option><option value="PROJECT">仅项目</option></select>
        <div class="actions"><el-button type="primary" native-type="submit" :disabled="!canQuery">查询内容</el-button>
          <el-button v-if="loading" @click="controller?.abort()">停止查询</el-button></div>
      </form>
      <el-alert v-if="error" :title="error" type="warning" :closable="false" />
      <article v-if="result" aria-label="内容查询结果">
        <p role="status">{{ result.message }}</p>
        <ol aria-label="查询步骤"><li v-for="(step, index) in result.steps" :key="index">{{ labels[step.tool] }}：{{ step.resultCount ? `找到 ${step.resultCount} 项` : "没有可读内容" }}</li></ol>
        <div v-for="detail in result.details" :key="detail.url" class="detail">
          <h2>{{ detail.title }}</h2><p class="excerpt">{{ detail.text }}</p>
          <p v-if="detail.truncated" class="muted">摘录已截短，请打开原文阅读完整内容。</p>
          <RouterLink :to="detail.url">阅读原文 →</RouterLink>
        </div>
        <div v-if="result.matches.length"><h2>找到的公开内容</h2>
          <div v-for="match in result.matches" :key="match.url" class="match"><RouterLink :to="match.url">{{ match.title }}</RouterLink><p>{{ match.excerpt }}</p></div>
        </div>
      </article>
      <p class="muted">每类最多展示3项匹配，只匹配标题或名称；不保存查询历史，不请求外部模型，不修改内容。</p>
      <RouterLink to="/articles">浏览文章 →</RouterLink> · <RouterLink to="/ai/knowledge">站内知识问答 →</RouterLink> · <RouterLink to="/ai/assistant">站内只读助手 →</RouterLink>
    </div>
  </section>
</template>
<style scoped>
.tools-page { max-width: 900px; margin: auto; } label { display: block; margin: 12px 0 6px; }
select { padding: 8px; max-width: 100%; } .actions { display: flex; flex-wrap: wrap; gap: 12px; margin: 16px 0; }
.excerpt { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; } .detail,.match { border-top: 1px solid #ddd; padding: 12px 0; overflow-wrap: anywhere; } h2 { font-size: 18px; }
</style>
