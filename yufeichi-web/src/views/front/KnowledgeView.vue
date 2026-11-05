<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { streamKnowledge, knowledgeStatus, type KnowledgeStatus, type KnowledgeResponse } from "@/api/knowledge";
import { errorMessage, TOKEN_KEY } from "@/api/http";
const question = ref(""), running = ref(false), result = ref<KnowledgeResponse>(), message = ref("");
const status = ref<KnowledgeStatus>(), hasToken = ref(!!localStorage.getItem(TOKEN_KEY));
const canAsk = computed(() => !!status.value?.available && (!status.value.loginRequired || hasToken.value) && !running.value && !!question.value.trim() && question.value.length <= 500);
let controller: AbortController | undefined, disposed = false;
async function ask() {
  if (!canAsk.value) return;
  result.value = undefined; message.value = ""; running.value = true;
  const current = new AbortController(); controller = current;
  try {
    await streamKnowledge(question.value, value => { if (!disposed) result.value = value; }, current.signal);
  } catch (error) {
    if (!disposed) { hasToken.value = !!localStorage.getItem(TOKEN_KEY); result.value = undefined; message.value = current.signal.aborted ? "已停止，可以修改问题后重试。" : errorMessage(error); }
  } finally { if (!disposed) running.value = false; if (controller === current) controller = undefined; }
}
const statusController = new AbortController();
onMounted(async () => {
  try { const value = await knowledgeStatus(statusController.signal); if (!disposed) status.value = value; }
  catch (error) { if (!disposed) message.value = errorMessage(error); }
});
onBeforeUnmount(() => { statusController.abort(); disposed = true; controller?.abort(); question.value = ""; result.value = undefined; });
</script>
<template>
  <section class="knowledge-page" :aria-busy="running">
    <div class="page-heading"><div><h1>站内知识问答</h1><p class="muted">查找已发布文章和公开项目，回答附原文来源。</p></div></div>
    <div class="panel">
      <p class="muted">只处理本站公开内容；问题会发送至外部模型服务，仅保留本页当前结果。试用次数有限，请勿输入个人敏感信息。</p>
      <p v-if="status && !status.available" role="status">站内问答暂不可用，你仍可以浏览文章和项目。</p>
      <p v-else-if="status?.loginRequired && !hasToken" role="status">当前需要登录后试用。
        <RouterLink to="/login?redirect=/ai/knowledge">登录试用 →</RouterLink></p>
      <p v-else-if="status?.publicEnabled && !hasToken" class="muted">匿名试用：每分钟最多3次，每天最多10次；本站还有总调用限额。</p>
      <form @submit.prevent="ask">
        <label for="knowledge-question">你的问题</label>
        <el-input id="knowledge-question" v-model="question" type="textarea" :rows="3" maxlength="500" show-word-limit
          :disabled="running" placeholder="例如：这个项目使用了哪些后端技术？" />
        <div class="actions"><el-button type="primary" native-type="submit" :disabled="!canAsk">提问</el-button>
          <el-button v-if="running" @click="controller?.abort()">停止问答</el-button></div>
      </form>
      <p v-if="running" role="status">正在检索资料并核对回答与来源…</p>
      <el-alert v-if="message" :title="message" type="warning" :closable="false" />
      <article v-if="result" aria-label="问答结果">
        <p class="answer" :role="result.answered ? undefined : 'status'">{{ result.answer }}</p>
        <div v-if="result.sources.length" aria-label="原文来源">
          <h2>原文来源</h2><p class="muted">请打开原文核对。来源存在并不代表回答的每个细节都正确。</p>
          <RouterLink v-for="source in result.sources" :key="source.id" :to="source.url" class="source">
            <span>{{ source.id }} · {{ source.sourceType === 'article' ? '文章' : '项目' }}</span><strong>{{ source.title }}</strong><span>阅读原文 →</span>
          </RouterLink>
        </div>
      </article>
      <p v-else-if="!running && !message" class="muted">输入问题开始。找不到足够依据时，会明确告诉你。</p>
      <p><RouterLink to="/ai/tools">按关键词查询文章和项目 →</RouterLink></p>
      <RouterLink to="/articles">浏览文章 →</RouterLink>
    </div>
  </section>
</template>
<style scoped>
.knowledge-page { max-width: 900px; margin: auto; }
.actions { display: flex; gap: 12px; margin: 16px 0; flex-wrap: wrap; }
.answer { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.9; }
.source { display: flex; flex-direction: column; gap: 6px; padding: 14px; margin: 12px 0; border: 1px solid #dce2de; border-radius: 10px; overflow-wrap: anywhere; }
.source span { font-size: 13px; } h2 { font-size: 18px; }
</style>
