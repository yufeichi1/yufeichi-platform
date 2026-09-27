import type { ArticleInput } from "@/types/api";

// Keep interrupted edits only in this tab's memory, separated by account and route.
// They survive an authentication redirect, but are never persisted to shared storage.
const drafts = new Map<string, ArticleInput>();
export function preserveDraft(key: string, value: ArticleInput) {
  drafts.set(key, { ...value, tagIds: [...value.tagIds] });
}
export function takeDraft(key: string) {
  const value = drafts.get(key);
  drafts.delete(key);
  return value;
}
export function clearDrafts() {
  drafts.clear();
}
