import { createRouter, createWebHistory } from "vue-router";
import { useUserStore } from "@/stores/user";
import { ApiError } from "@/api/http";
import FrontLayout from "@/layouts/FrontLayout.vue";
import AdminLayout from "@/layouts/AdminLayout.vue";

export function safeRedirect(value: unknown): string {
  return typeof value === "string" &&
    /^\/admin(?:\/|\?|$)/.test(value) &&
    !value.includes("\\")
    ? value
    : "/admin/articles";
}
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/",
      component: FrontLayout,
      children: [
        {
          path: "articles",
          component: () => import("@/views/front/BlogList.vue"),
        },
        {
          path: "articles/:id",
          component: () => import("@/views/front/ArticleDetail.vue"),
        },
        {
          path: "projects",
          component: () => import("@/views/front/ProjectList.vue"),
        },
        {
          path: "projects/:id",
          component: () => import("@/views/front/ProjectDetail.vue"),
        },
        {
          path: "about",
          component: () => import("@/views/front/AboutView.vue"),
        },
        { path: "", component: () => import("@/views/HomeView.vue") },
        { path: "login", component: () => import("@/views/LoginView.vue") },
        {
          path: "403",
          component: () => import("@/views/StatusView.vue"),
          props: {
            code: "403",
            title: "没有访问权限",
            description: "当前账号无权访问此页面，请联系管理员。",
          },
        },
        {
          path: "session-error",
          component: () => import("@/views/StatusView.vue"),
          props: {
            code: "连接失败",
            title: "暂时无法恢复登录",
            description: "你的登录信息已保留，服务恢复后可重试。",
            retry: true,
          },
        },
        {
          path: ":pathMatch(.*)*",
          component: () => import("@/views/StatusView.vue"),
          props: {
            code: "404",
            title: "页面不存在",
            description: "请检查地址，或返回工作台。",
          },
        },
      ],
    },
    {
      path: "/admin",
      component: AdminLayout,
      meta: { auth: true },
      children: [
        { path: "", redirect: "/admin/articles" },
        {
          path: "ai",
          component: () => import("@/views/AiWorkspace.vue"),
          meta: { anyPermissions: ["article:add", "article:update"], title: "AI 助手" },
        },
        {
          path: "projects",
          component: () => import("@/views/ProjectManage.vue"),
          meta: { permission: "project:list", title: "项目管理" },
        },
        {
          path: "articles",
          component: () => import("@/views/ArticleList.vue"),
          meta: { permission: "article:list", title: "文章管理" },
        },
        {
          path: "articles/new",
          component: () => import("@/views/ArticleEditor.vue"),
          meta: { permission: "article:add", title: "新建文章" },
        },
        {
          path: "articles/:id(\\d+)/edit",
          component: () => import("@/views/ArticleEditor.vue"),
          meta: { permission: "article:list", title: "编辑文章" },
        },
        {
          path: "categories",
          component: () => import("@/views/TaxonomyView.vue"),
          props: { kind: "categories" },
          meta: { permission: "category:list", title: "分类管理" },
        },
        {
          path: "tags",
          component: () => import("@/views/TaxonomyView.vue"),
          props: { kind: "tags" },
          meta: { permission: "tag:list", title: "标签管理" },
        },
      ],
    },
  ],
});
router.beforeEach(async (to) => {
  if (!to.meta.auth) return true;
  const store = useUserStore();
  try {
    await store.restore();
  } catch (error: unknown) {
    if (!(error instanceof ApiError && error.status === 401))
      return { path: "/session-error", query: { redirect: to.fullPath } };
  }
  if (!store.user) return { path: "/login", query: { redirect: to.fullPath } };
  if (Array.isArray(to.meta.anyPermissions) && !to.meta.anyPermissions.some(permission =>
    typeof permission === "string" && store.hasPermission(permission))) return "/403";
  if (
    typeof to.meta.permission === "string" &&
    !store.hasPermission(to.meta.permission)
  )
    return "/403";
  return true;
});
export default router;
router.afterEach((to) => {
  const title =
    typeof to.meta.title === "string"
      ? to.meta.title
      : {
          "/": "随笔与创造",
          "/articles": "文章",
          "/projects": "项目",
          "/about": "关于",
          "/login": "登录",
        }[to.path] || "随笔与创造";
  document.title = `${title} · Yufeichi`;
});
