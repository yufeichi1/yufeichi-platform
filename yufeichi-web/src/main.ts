import { createApp } from "vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import zhCn from "element-plus/es/locale/lang/zh-cn";
import "element-plus/dist/index.css";
import "./style.css";
import App from "./App.vue";
import router from "./router";
import { onUnauthorized } from "./api/http";
import { useUserStore } from "./stores/user";

const app = createApp(App);
app.use(createPinia());
onUnauthorized(() => {
  useUserStore().clear();
  if (router.currentRoute.value.meta.auth) {
    void router.replace({
      path: "/login",
      query: { redirect: router.currentRoute.value.fullPath, expired: "1" },
    });
  }
});
app.use(router).use(ElementPlus, { locale: zhCn }).mount("#app");
