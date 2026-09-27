<script setup lang="ts">
import { ref } from "vue";
import { uploadImage } from "@/api/content";
import { errorMessage } from "@/api/http";
const props = defineProps<{
  modelValue: string | null;
  disabled?: boolean;
  bizType?: "article" | "project";
}>();
const emit = defineEmits<{
  "update:modelValue": [value: string | null];
  busy: [value: boolean];
}>();
const busy = ref(false),
  progress = ref(0),
  error = ref(""),
  selected = ref<File | null>(null);
async function upload() {
  if (!selected.value || busy.value || props.disabled) return;
  busy.value = true;
  emit("busy", true);
  error.value = "";
  progress.value = 0;
  try {
    const result = await uploadImage(
      selected.value,
      (value) => {
        progress.value = value;
      },
      props.bizType,
    );
    if (
      !/^\/uploads\/(avatar|article|project|other)\/[a-zA-Z0-9.-]+$/.test(
        result.fileUrl,
      )
    )
      throw new Error("服务器返回了无效图片地址");
    emit("update:modelValue", result.fileUrl);
    selected.value = null;
  } catch (e: unknown) {
    error.value = errorMessage(e);
  } finally {
    busy.value = false;
    emit("busy", false);
  }
}
function choose(event: Event) {
  const input = event.target as HTMLInputElement;
  selected.value = input.files?.[0] || null;
  input.value = "";
  if (!selected.value) return;
  if (selected.value.size > 5 * 1024 * 1024) {
    error.value = "图片不能超过 5 MB";
    selected.value = null;
    return;
  }
  void upload();
}
</script>
<template>
  <div class="upload-box">
    <img
      v-if="modelValue"
      :src="modelValue"
      :alt="bizType === 'project' ? '项目封面' : '文章封面'"
      class="cover-preview"
    />
    <div v-else class="cover-placeholder">选择一张封面图片</div>
    <label class="file-picker"
      >选择封面<input
        aria-label="选择封面"
        type="file"
        accept="image/jpeg,image/png,image/webp"
        :disabled="disabled || busy"
        @change="choose" /></label
    ><el-button
      v-if="modelValue"
      text
      :disabled="disabled || busy"
      @click="emit('update:modelValue', null)"
      >移除封面</el-button
    >
    <p class="muted help">JPG、PNG 或 WebP · 最大 5 MB</p>
    <el-progress v-if="busy" :percentage="progress" />
    <p v-if="busy" role="status">
      {{ progress === 100 ? "上传完成，正在校验图片…" : "正在上传…" }}
    </p>
    <p v-if="error" role="alert" class="error-text">{{ error }}</p>
    <el-button v-if="error && selected" :disabled="disabled" @click="upload"
      >重试上传</el-button
    >
  </div>
</template>
