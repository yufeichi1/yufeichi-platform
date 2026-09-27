import { shallowRef, ref, computed, watch, onScopeDispose } from "vue";
import { ApiError, errorMessage } from "@/api/http";
export function usePublicResource<T>(
  key: () => string,
  fetcher: () => Promise<T>,
) {
  const data = shallowRef<T | null>(null),
    loading = ref(true),
    error = ref(""),
    notFound = ref(false);
  let generation = 0;
  async function load() {
    const current = ++generation;
    data.value = null;
    loading.value = true;
    error.value = "";
    notFound.value = false;
    try {
      const result = await fetcher();
      if (current === generation) data.value = result;
    } catch (e: unknown) {
      if (current === generation) {
        error.value = errorMessage(e);
        notFound.value = e instanceof ApiError && e.status === 404;
      }
    } finally {
      if (current === generation) loading.value = false;
    }
  }
  watch(key, () => void load(), { immediate: true });
  onScopeDispose(() => {
    generation++;
  });
  return {
    data,
    loading,
    error,
    notFound,
    load,
    ready: computed(() => !loading.value && !error.value),
  };
}
export function positiveQuery(value: unknown): number | undefined {
  if (typeof value !== "string" || !/^[1-9]\d*$/.test(value)) return undefined;
  const number = Number(value);
  return Number.isSafeInteger(number) && number <= 2147483647
    ? number
    : undefined;
}
export function requireId(id: unknown): string {
  if (
    typeof id !== "string" ||
    !/^[1-9]\d*$/.test(id) ||
    !Number.isSafeInteger(Number(id))
  )
    throw new ApiError("内容不存在", 404);
  return id;
}
