<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from "vue";
import { agentStatus, askAgent, type AgentResult } from "@/api/agent";
import { errorMessage } from "@/api/http";
const question = ref(""), available = ref(false), ready = ref(false), running = ref(false), message = ref(""), result = ref<AgentResult>();
const canAsk = computed(() => available.value && !running.value && !!question.value.trim() && question.value.length <= 500);
const labels: Record<string, string> = { searchArticles: "搜索公开文章", searchProjects: "搜索公开项目", getArticleDetail: "读取文章详情", getProjectDetail: "读取项目详情" };
const statusController = new AbortController(); let controller: AbortController | undefined, disposed = false;
onMounted(async () => { try { const value = await agentStatus(statusController.signal); if (!disposed) available.value = value.available; }
  catch (error) { if (!disposed) message.value = errorMessage(error); } finally { if (!disposed) ready.value = true; } });
async function ask() {
  if (!canAsk.value) return; result.value = undefined; message.value = ""; running.value = true;
  const current = new AbortController(); controller = current;
  try { const value = await askAgent(question.value, current.signal); if (!disposed && !current.signal.aborted) result.value = value; }
  catch (error) { if (!disposed) message.value = current.signal.aborted ? "已停止，可以修改问题后重试。" : errorMessage(error); }
  finally { if (!disposed) running.value = false; if (controller === current) controller = undefined; }
}
onBeforeUnmount(() => { disposed = true; statusController.abort(); controller?.abort(); question.value = ""; result.value = undefined; });
</script>
<template>
  <section class="agent-page" :aria-busy="running">
    <h1>站内只读助手</h1>
    <p class="muted">模型查找公开文章或项目、读取详情并整理回答，附原文来源供你核对。</p>
    <div class="panel">
      <p class="muted">问题和所需公开原文会发送至外部模型。请勿输入个人敏感信息。每次最多三轮模型请求、四次只读工具调用，不保存对话历史。</p>
      <p v-if="ready && !available && !message" role="status">只读助手尚未启用，你仍可按关键词查询或浏览文章。</p>
      <form @submit.prevent="ask">
        <label for="agent-question">你的任务</label>
        <el-input id="agent-question" v-model="question" type="textarea" :rows="3" maxlength="500" show-word-limit :disabled="running"
          placeholder="例如：查找 Java 相关文章，读取详情后介绍其中的项目技术栈。" />
        <div class="actions"><el-button type="primary" native-type="submit" :disabled="!canAsk">运行助手</el-button><el-button v-if="running" @click="controller?.abort()">停止助手</el-button></div>
      </form>
      <p v-if="running" role="status">正在查询并核对来源，完成后展示回答…</p>
      <el-alert v-if="message" :title="message" type="warning" :closable="false" />
      <article v-if="result" aria-label="助手结果">
        <p class="answer">{{ result.answer }}</p>
        <div v-if="result.sources.length" aria-label="助手来源"><h2>原文来源</h2><p class="muted">请核对原文；引用有效不代表模型的每个细节都正确。</p>
          <RouterLink v-for="source in result.sources" :key="source.id" :to="source.url" class="source">{{ source.id }} · {{ source.title }} · 阅读原文 →</RouterLink>
        </div>
        <details><summary>查看查询步骤</summary><p>模型请求 {{ result.modelRounds }} 轮；只读工具 {{ result.steps.length }} 次。</p>
          <ol><li v-for="(step, index) in result.steps" :key="index">第 {{ step.round }} 轮 · {{ labels[step.tool] }}：{{ step.resultCount ? `找到 ${step.resultCount} 项` : "没有可读内容" }}</li></ol>
        </details>
      </article>
      <p><RouterLink to="/ai/tools">按关键词查询 →</RouterLink> · <RouterLink to="/articles">浏览文章 →</RouterLink></p>
    </div>
  </section>
</template>
<style scoped>
.agent-page { max-width: 900px; margin: auto; } .actions { display: flex; flex-wrap: wrap; gap: 12px; margin: 16px 0; }
.answer { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.9; } .source { display: block; padding: 12px 0; overflow-wrap: anywhere; }
h2 { font-size: 18px; } details { padding: 12px 0; overflow-wrap: anywhere; }
</style>
