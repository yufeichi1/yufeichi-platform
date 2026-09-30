<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { ElMessageBox } from "element-plus";
import { streamSummary, type SummaryResponse } from "@/api/ai";
import { ApiError, errorMessage } from "@/api/http";
import MarkdownContent from "@/components/MarkdownContent.vue";

const props = defineProps<{ content: string; disabled?: boolean; existingSummary?: string }>();
const emit = defineEmits<{ adopt: [summary: string] }>();
const state = ref<"idle" | "running" | "completed" | "stopped" | "failed">("idle");
const text = ref(""), message = ref(""), snapshot = ref(""), stale = ref(false);
const candidate = ref<SummaryResponse | null>(null);
let controller: AbortController | undefined;
let disposed = false;
const running = computed(() => state.value === "running");
const canGenerate = computed(() => !running.value && !props.disabled && !!props.content.trim() && props.content.length <= 12000);
const canAdopt = computed(() => state.value === "completed" && !stale.value && !props.disabled
  && !!candidate.value && props.content === snapshot.value);
const label = computed(() => ({ idle: "等待生成", running: "正在生成", completed: "生成完成，等待你确认",
  stopped: "生成已停止，部分内容不能采用", failed: "生成未完成，部分内容不能采用" }[state.value]));

watch(() => props.content, () => {
  if (state.value === "idle") return;
  stale.value = true;
  message.value = "正文已变化，请重新生成摘要。";
  controller?.abort();
}, { flush: "sync" });
watch(() => props.disabled, disabled => { if (disabled) controller?.abort(); });
function stop() { controller?.abort(); }
async function generate() {
  if (!canGenerate.value) return;
  const current = new AbortController();
  controller = current;
  snapshot.value = props.content;
  stale.value = false;
  candidate.value = null;
  text.value = "";
  message.value = "";
  state.value = "running";
  try {
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(snapshot.value));
    const expectedHash = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, "0")).join("");
    if (current.signal.aborted) throw new DOMException("请求已停止", "AbortError");
    await streamSummary(snapshot.value, {
      onMeta(meta) { if (meta.contentHash !== expectedHash) throw new ApiError("摘要正文快照不匹配，请重试", 502); },
      onDelta(delta) { if (!disposed && !current.signal.aborted) text.value += delta; },
      onDone(result) {
        if (result.contentHash !== expectedHash) throw new ApiError("摘要正文快照不匹配，请重试", 502);
        if (!disposed && !current.signal.aborted) { candidate.value = result; text.value = result.summary; }
      },
    }, current.signal);
    if (current.signal.aborted) throw new DOMException("请求已停止", "AbortError");
    if (!disposed) state.value = "completed";
  } catch (failure) {
    if (!disposed) {
      state.value = failure instanceof DOMException && failure.name === "AbortError" ? "stopped" : "failed";
      if (!stale.value) message.value = state.value === "stopped" ? "已停止生成，可以重新尝试。" : errorMessage(failure);
      candidate.value = null;
    }
  } finally { if (controller === current) controller = undefined; }
}
async function adopt() {
  if (!canAdopt.value) return;
  if (props.existingSummary?.trim()) {
    try {
      await ElMessageBox.confirm("摘要框已有内容，是否用当前 AI 摘要替换？", "采用摘要", {
        confirmButtonText: "确认替换", cancelButtonText: "保留原摘要", type: "warning",
      });
    } catch { return; }
  }
  // The user may edit the body or leave while the confirmation dialog is open.
  if (!disposed && canAdopt.value && candidate.value) emit("adopt", candidate.value.summary);
}
onBeforeUnmount(() => { disposed = true; controller?.abort(); });
</script>
<template>
  <section class="ai-summary-panel" aria-label="AI 摘要助手" :aria-busy="running">
    <div class="ai-summary-heading">
      <div><h3>AI 摘要助手</h3><p class="muted">根据当前正文生成候选；不会自动保存或发布文章。</p></div>
      <div class="ai-summary-actions">
        <el-button :disabled="!canGenerate" @click="generate">{{ running ? "正在生成…" : "生成摘要" }}</el-button>
        <el-button v-if="running" @click="stop">停止生成</el-button>
      </div>
    </div>
    <p v-if="!content.trim()" class="muted">先填写正文，再生成摘要。</p>
    <p v-else-if="content.length > 12000" role="alert">正文超过 12000 字符，请缩短后再生成；系统不会静默截断。</p>
    <p class="ai-summary-status" role="status">{{ label }}</p>
    <el-alert v-if="message" :title="message" :type="state === 'failed' ? 'error' : 'warning'" :closable="false" />
    <div v-if="text" class="ai-summary-preview" data-testid="ai-summary-preview">
      <MarkdownContent :content="text" />
    </div>
    <p v-else-if="running" class="muted">正在等待模型响应，可随时停止。</p>
    <div v-if="text" class="ai-summary-actions">
      <el-button type="primary" :disabled="!canAdopt" @click="adopt">采用摘要</el-button>
      <span class="muted">{{ stale ? "正文已变化，请重新生成。" : "请核对内容，采用后仍需自行保存。" }}</span>
    </div>
  </section>
</template>
<style scoped>
.ai-summary-panel { margin: 18px 0; padding: 16px; border: 1px solid #dce2de; border-radius: 12px; background: #f8faf8; }
.ai-summary-heading, .ai-summary-actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.ai-summary-heading { justify-content: space-between; }
h3 { margin: 0; }
p { font-size: 13px; }
.ai-summary-preview { margin: 14px 0; overflow-wrap: anywhere; max-height: 320px; overflow: auto; }
.ai-summary-status { font-weight: 600; }
@media (max-width: 650px) { .ai-summary-panel { padding: 12px; } .ai-summary-heading { align-items: flex-start; } }
</style>
