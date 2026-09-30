// Test entry bundles the real modules without introducing a production test route/page.
export { generateSummary, streamSummary, cancelAiRequests } from "../../src/api/ai";
export { onUnauthorized } from "../../src/api/http";
import { createPinia, setActivePinia } from "pinia";
import { useUserStore } from "../../src/stores/user";

export async function logout() {
  setActivePinia(createPinia());
  await useUserStore().logout();
}
