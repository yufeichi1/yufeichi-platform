import { defineStore } from "pinia";
import { ref } from "vue";
import { ApiError, request, TOKEN_KEY } from "@/api/http";
import type { LoginResult, UserInfo } from "@/types/api";
import { clearDrafts } from "./editorDraft";

export const useUserStore = defineStore("user", () => {
  const user = ref<UserInfo | null>(null);
  let restoring: Promise<void> | null = null;
  function clear() {
    localStorage.removeItem(TOKEN_KEY);
    user.value = null;
  }
  function hasPermission(permission: string) {
    return user.value?.permissions?.includes(permission) ?? false;
  }
  async function login(username: string, password: string) {
    const result = await request<LoginResult>(
      { url: "/auth/login", method: "POST", data: { username, password } },
      false,
    );
    localStorage.setItem(TOKEN_KEY, result.token);
    user.value = { ...result.userInfo, permissions: result.permissions };
  }
  async function restore() {
    if (user.value || !localStorage.getItem(TOKEN_KEY)) return;
    if (!restoring) {
      const token = localStorage.getItem(TOKEN_KEY);
      restoring = request<UserInfo>({ url: "/auth/me" })
        .then((result) => {
          if (token === localStorage.getItem(TOKEN_KEY)) user.value = result;
        })
        .catch((error: unknown) => {
          if (
            error instanceof ApiError &&
            error.status === 401 &&
            token === localStorage.getItem(TOKEN_KEY)
          )
            clear();
          throw error;
        })
        .finally(() => {
          restoring = null;
        });
    }
    return restoring;
  }
  async function logout() {
    try {
      await request<void>({ url: "/auth/logout", method: "POST" });
    } catch (error: unknown) {
      // An unavailable revocation store did not invalidate this session. Keep it for retry.
      if (!(error instanceof ApiError && error.status === 401)) throw error;
    }
    clearDrafts();
    clear();
  }
  return { user, clear, hasPermission, login, restore, logout };
});
