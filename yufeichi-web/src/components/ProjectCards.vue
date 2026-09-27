<script setup lang="ts">
import type { Project } from "@/types/api";
import SafeImage from "./SafeImage.vue";
defineProps<{ projects: Project[] }>();
</script>
<template>
  <div class="project-cards">
    <article v-for="item in projects" :key="item.id" class="public-card">
      <RouterLink
        :to="'/projects/' + item.id"
        class="card-image"
        :aria-label="'查看 ' + item.name"
        ><SafeImage :src="item.coverUrl" :alt="item.name"
      /></RouterLink>
      <div class="card-body">
        <p class="eyebrow">PROJECT / {{ item.techStack || "实践记录" }}</p>
        <h2>
          <RouterLink :to="'/projects/' + item.id">{{ item.name }}</RouterLink>
        </h2>
        <RouterLink :to="'/projects/' + item.id" class="read-link"
          >查看项目 ↗</RouterLink
        >
      </div>
    </article>
  </div>
</template>
