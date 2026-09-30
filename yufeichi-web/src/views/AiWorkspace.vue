<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from "vue";
import AiSummaryPanel from "@/components/AiSummaryPanel.vue";
import AiIndexPanel from "@/components/AiIndexPanel.vue";
import { useUserStore } from "@/stores/user";
const user = useUserStore();
const canIndex = computed(() => user.user?.roles?.some(role => role.toLowerCase() === "super_admin"));
const content = ref(""), summary = ref("");
// A single bounded input, held in memory only; no persistent conversation history.
onBeforeUnmount(() => { content.value = ""; summary.value = ""; });
</script>
<template>
  <section>
    <div class="page-heading"><div><h1>AI 助手</h1><p class="muted">为写作提供帮助，由你决定如何采用。</p></div></div>
    <div class="panel ai-workspace">
      <h2>文章摘要</h2>
      <p class="muted">粘贴正文试用，或在文章编辑页直接生成。这里只保留当前输入，离开后清空。</p>
      <label for="ai-source-content">待摘要正文</label>
      <el-input id="ai-source-content" v-model="content" type="textarea" :rows="10" maxlength="12000"
        show-word-limit placeholder="输入正文，最多 12000 字符…" />
      <AiSummaryPanel :content="content" :existing-summary="summary" @adopt="summary = $event" />
      <label for="ai-adopted-summary">已采用摘要</label>
      <el-input id="ai-adopted-summary" v-model="summary" type="textarea" :rows="3" maxlength="500"
        show-word-limit placeholder="确认采用后显示在这里，不会写入文章。" />
      <p class="muted">站内知识问答将在内容索引与来源校验完成后开放；当前提供文章摘要。</p>
      <RouterLink to="/admin/articles">前往文章管理 →</RouterLink>
    </div>
    <AiIndexPanel v-if="canIndex" />
  </section>
</template>
<style scoped>.ai-workspace { max-width: 900px; } h2 { margin-top: 0; }</style>
