<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useUserStore } from "@/stores/user";
import { safeRedirect } from "@/router";
import { errorMessage } from "@/api/http";
const username = ref(""),
  password = ref(""),
  busy = ref(false),
  error = ref("");
const route = useRoute(),
  router = useRouter(),
  user = useUserStore();
async function submit() {
  if (busy.value) return;
  if (!username.value.trim() || !password.value) {
    error.value = "请输入用户名和密码";
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    await user.login(username.value.trim(), password.value);
    await router.replace(safeRedirect(route.query.redirect));
  } catch (e: unknown) {
    error.value = errorMessage(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <section class="login-grid">
    <div class="login-story">
      <p class="eyebrow">YOUR SPACE TO CREATE</p>
      <h1>欢迎回来。<br />继续写下<br />你的下一章。</h1>
      <p>从草稿到发布，<br />让值得分享的想法被看见。</p>
      <span class="story-index">01 — 创作，从这里开始</span>
    </div>
    <div class="login-card">
      <p class="eyebrow">YUFEICHI WORKSPACE</p>
      <h2>登录工作台</h2>
      <p class="muted">使用你的账号管理内容。</p>
      <el-alert
        v-if="route.query.expired"
        title="登录已过期，请重新登录"
        type="warning"
        :closable="false"
      />
      <form @submit.prevent="submit">
        <label for="username">用户名</label
        ><el-input
          id="username"
          v-model="username"
          autocomplete="username"
          placeholder="请输入用户名"
          :disabled="busy"
        /><label for="password">密码</label
        ><el-input
          id="password"
          v-model="password"
          type="password"
          show-password
          autocomplete="current-password"
          placeholder="请输入密码"
          :disabled="busy"
        />
        <p v-if="error" role="alert" class="error-text">{{ error }}</p>
        <el-button
          class="full-width"
          type="primary"
          native-type="submit"
          :loading="busy"
          >登录工作台 →</el-button
        >
      </form>
      <p class="login-footnote">仅授权账号可以进入内容管理后台。</p>
    </div>
  </section>
</template>
