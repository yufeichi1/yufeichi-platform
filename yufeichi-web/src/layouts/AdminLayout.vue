<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useUserStore } from "@/stores/user";
import { ElMessage } from "element-plus";
import { errorMessage } from "@/api/http";
const user = useUserStore(),
  route = useRoute(),
  router = useRouter();
const leaving = ref(false);
const menus = [
  {
    path: "/admin/articles",
    name: "文章管理",
    permission: "article:list",
    mark: "01",
  },
  {
    path: "/admin/categories",
    name: "分类管理",
    permission: "category:list",
    mark: "02",
  },
  { path: "/admin/tags", name: "标签管理", permission: "tag:list", mark: "03" },
  {
    path: "/admin/projects",
    name: "项目管理",
    permission: "project:list",
    mark: "04",
  },
];
async function logout() {
  if (leaving.value) return;
  leaving.value = true;
  try {
    await user.logout();
  } catch (e: unknown) {
    ElMessage.warning(errorMessage(e));
  } finally {
    leaving.value = false;
    await router.replace("/login");
  }
}
</script>
<template>
  <div class="admin-shell">
    <aside class="sidebar">
      <RouterLink to="/" class="brand"
        >yufeichi<span>CONTENT STUDIO</span></RouterLink
      >
      <p class="nav-caption">内容工作台</p>
      <nav>
        <template v-for="item in menus" :key="item.path"
          ><RouterLink
            v-if="user.hasPermission(item.permission)"
            :to="item.path"
            :class="{ active: route.path.startsWith(item.path) }"
            ><small>{{ item.mark }}</small
            >{{ item.name }}</RouterLink
          ></template
        >
      </nav>
      <div class="sidebar-note">记录思考<br />让每一次创作都有迹可循。</div>
    </aside>
    <div class="workspace">
      <header class="workspace-header">
        <span
          >工作台 <span class="muted">/ {{ route.meta.title }}</span></span
        >
        <div class="account">
          <span>{{ user.user?.nickname || user.user?.username }}</span
          ><el-button text :loading="leaving" @click="logout"
            >退出登录</el-button
          >
        </div>
      </header>
      <main class="workspace-main"><RouterView :key="route.path" /></main>
    </div>
  </div>
</template>
