<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { imageUrl } from "@/utils/urls";
const props = defineProps<{ src: string | null | undefined; alt: string }>();
const failed = ref(false),
  source = computed(() => imageUrl(props.src));
watch(source, () => {
  failed.value = false;
});
</script>
<template>
  <img
    v-if="source && !failed"
    :src="source"
    :alt="alt"
    loading="lazy"
    referrerpolicy="no-referrer"
    @error="failed = true"
  />
  <div
    v-else
    class="image-placeholder"
    role="img"
    :aria-label="alt + '（暂无封面）'"
  >
    <span>YUFEICHI</span><span>NOTES & WORKS</span>
  </div>
</template>
