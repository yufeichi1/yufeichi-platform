import { ApiError, TOKEN_KEY, notifyUnauthorized, request } from "@/api/http";
import { trackAiRequest } from "./ai";
import { readSse } from "./sse";

export interface KnowledgeSource { id: string; sourceType: "article" | "project"; sourceId: number; title: string; url: string }
export interface KnowledgeResponse { answer: string; answered: boolean; sources: KnowledgeSource[]; indexVersion: string | null; requestId: string }
export interface KnowledgeStatus { available: boolean; publicEnabled: boolean; loginRequired: boolean }
export function knowledgeStatus(signal?: AbortSignal): Promise<KnowledgeStatus> {
  return request({ url: "/ai/knowledge/status", signal }, false);
}
function invalid(): never { throw new ApiError("问答结果或来源格式不符合要求，请重试", 502); }
function sources(value: unknown): KnowledgeSource[] {
  if (!Array.isArray(value) || value.length > 3) return invalid();
  const ids = new Set<string>();
  return value.map(source => {
    if (!source || typeof source !== "object") return invalid();
    const s = source as KnowledgeSource;
    if (!/^S[1-3]$/.test(s.id) || ids.has(s.id) || !["article", "project"].includes(s.sourceType)
        || !Number.isSafeInteger(s.sourceId) || s.sourceId < 1 || typeof s.title !== "string" || s.title.length > 255
        || s.url !== `/${s.sourceType === "article" ? "articles" : "projects"}/${s.sourceId}`) return invalid();
    ids.add(s.id); return { id: s.id, sourceType: s.sourceType, sourceId: s.sourceId, title: s.title, url: s.url };
  });
}
export async function streamKnowledge(question: string, onDone: (value: KnowledgeResponse) => void, signal?: AbortSignal): Promise<void> {
  const token = localStorage.getItem(TOKEN_KEY);
  if (!question.trim() || question.length > 500) throw new ApiError("问题为空或超过500字符", 400);
  const controller = new AbortController(), untrack = trackAiRequest(controller);
  const abort = () => controller.abort();
  signal?.addEventListener("abort", abort, { once: true });
  if (signal?.aborted) controller.abort();
  let timedOut = false;
  const timeout = setTimeout(() => { timedOut = true; controller.abort(); }, 65000);
  try {
    const response = await fetch("/api/ai/knowledge/chat", { method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify({ question }), signal: controller.signal });
    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      if (response.status === 401 && token) notifyUnauthorized(token);
      throw new ApiError(response.status === 401 ? "请登录后重试" : response.status === 503 ? "站内问答尚未启用或暂不可用" : response.status === 429 ? "请求较多，请稍后重试" : "问答请求失败，请重试", response.status, error.code);
    }
    if (!response.headers.get("content-type")?.includes("text/event-stream") || !response.body) return invalid();
    let requestId = "", text = "", references: KnowledgeSource[] | undefined, done = false;
    await readSse(response.body, event => {
      if (event.event === "heartbeat") return;
      const value = JSON.parse(event.data);
      if (event.event === "error") {
        const status = value.code === 63002 ? 504 : value.code === 63003 ? 502 : value.code === 42900 ? 429 : 503;
        throw new ApiError(status === 504 ? "问答超时，请重试" : status === 429 ? "请求较多，请稍后重试" : "问答未完成，请重试", status, value.code);
      }
      if (event.event === "meta") {
        if (requestId || typeof value.requestId !== "string" || value.mode !== "site-knowledge") return invalid();
        requestId = value.requestId;
      } else if (event.event === "delta") {
        if (!requestId || references || typeof value.text !== "string" || text.length + value.text.length > 1600) return invalid();
        text += value.text;
      } else if (event.event === "sources") {
        if (!requestId || references) return invalid();
        references = sources(value);
      } else if (event.event === "done") {
        if (!requestId || !references || value.requestId !== requestId || value.answer !== text || !text.trim()
            || typeof value.answered !== "boolean" || (value.indexVersion !== null && (typeof value.indexVersion !== "string" || !/^[a-f0-9-]{36}$/.test(value.indexVersion)))) return invalid();
        const finalSources = sources(value.sources);
        if (JSON.stringify(finalSources) !== JSON.stringify(references) || value.answered !== (references.length > 0)) return invalid();
        done = true;
        onDone({ answer: text, answered: value.answered, sources: references, indexVersion: value.indexVersion, requestId });
        return false;
      } else return invalid();
    }, controller.signal);
    if (!done) return invalid();
  } catch (error) {
    if (timedOut) throw new ApiError("问答超时，请重试", 504);
    if (error instanceof ApiError) throw error;
    if (controller.signal.aborted) throw new DOMException("请求已停止", "AbortError");
    return invalid();
  } finally { clearTimeout(timeout); signal?.removeEventListener("abort", abort); untrack(); }
}
