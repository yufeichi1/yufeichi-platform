<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { request } from "@/api/http";
type Job = { id: string; status: string; source_count: number; chunk_count: number; completed_chunks: number; error_code: string | null };
type Status = { available: boolean; state: { active_version: string | null; running_job: string | null }; jobs: Job[] };
const data = ref<Status>(), error = ref(""), busy = ref(false);
let disposed = false, timer: ReturnType<typeof setTimeout> | undefined;
const controller = new AbortController();
const active = computed(() => data.value?.state.running_job);
const labels: Record<string, string> = { QUEUED: "等待处理", RUNNING: "正在索引", SUCCEEDED: "索引成功", FAILED: "索引失败" };
const errors: Record<string, string> = { INPUT_LIMIT: "内容超过本轮限额（100 个片段），请核对内容规模。", QUOTA: "调用配额已用完，请稍后重试。", SOURCE_CHANGED: "处理期间内容发生变化，请重新构建。", INTERRUPTED: "上次任务已中断，可以重新构建。", TIMEOUT: "处理超时，可以重新构建。", VECTOR_VALIDATION: "索引校验失败，旧版本继续保留。", DEPENDENCY_UNAVAILABLE: "模型或索引服务不可用，旧版本继续保留。" };
async function load() {
  try {
    const value = await request<Status>({ url: "/admin/ai/knowledge", signal: controller.signal });
    if (!disposed) { data.value = value; error.value = ""; }
  } catch (e) { if (!disposed) error.value = e instanceof Error ? e.message : "状态读取失败"; }
  finally { if (!disposed) timer = setTimeout(load, active.value ? 2000 : 15000); }
}
async function rebuild() {
  if (busy.value || active.value || !data.value?.available) return;
  busy.value = true; error.value = "";
  try {
    await request({ url: "/admin/ai/knowledge/reindex", method: "POST", signal: controller.signal });
    if (!disposed) { clearTimeout(timer); await load(); }
  } catch (e) { if (!disposed) error.value = e instanceof Error ? e.message : "任务提交失败"; }
  finally { busy.value = false; }
}
onMounted(load);
onBeforeUnmount(() => { disposed = true; clearTimeout(timer); controller.abort(); });
</script>
<template>
  <div class="panel index-panel" role="region" aria-label="站内内容索引">
    <h2>站内内容索引</h2>
    <p class="muted">只处理已发布文章和公开项目。完整成功后才切换版本；内容未变时复用现有索引。</p>
    <p>当前版本：<span>{{ data?.state.active_version || "尚未建立" }}</span></p>
    <p v-if="data && !data.available" class="muted">索引服务尚未启用。</p>
    <el-button type="primary" :loading="busy" :disabled="!data?.available || !!active" @click="rebuild">重建站内索引</el-button>
    <p v-if="error" role="alert">{{ error }}</p>
    <ul aria-live="polite">
      <li v-for="job in data?.jobs" :key="job.id">
        {{ labels[job.status] || job.status }} · {{ job.source_count }} 个来源 · {{ job.completed_chunks }}/{{ job.chunk_count }} 个片段
        <p v-if="job.error_code">{{ errors[job.error_code] || "任务失败，请检查服务状态。" }}</p>
      </li>
    </ul>
    <p class="muted">每次最多 100 个片段；每日索引最多 10 次模型请求、100000 字符。当前尚未开放站内问答。</p>
  </div>
</template>
<style scoped>.index-panel { margin-top: 24px; max-width: 900px; } li { margin: 12px 0; overflow-wrap: anywhere; } span { overflow-wrap: anywhere; }</style>
