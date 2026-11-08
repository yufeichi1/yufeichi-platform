import { request, ApiError, TOKEN_KEY, notifyUnauthorized } from "./http";
import { trackAiRequest } from "./ai";
import { readSse } from "./sse";
export interface AgentSource { id: string; sourceType: "article" | "project"; sourceId: number; title: string; url: string; contentHash: string }
export interface AgentResult { requestId: string; mode: "site-agent"; answered: boolean; answer: string; sources: AgentSource[];
  modelRounds: number; steps: { round: number; tool: string; outcome: string; resultCount: number }[];
  usage: { round: number; inputTokens: number | null; outputTokens: number | null }[] }
export function agentStatus(signal: AbortSignal): Promise<{ available: boolean }> { return request({ url: "/ai/agent/status", signal }); }
function invalid(): never { throw new ApiError("助手结果或来源格式不符合要求，请重试", 502); }
function validate(v: AgentResult, id: string): AgentResult {
  if (!v || v.requestId !== id || v.mode !== "site-agent" || typeof v.answered !== "boolean" || typeof v.answer !== "string"
    || !v.answer.trim() || v.answer.length > 1600 || /https?:\/\/|www\.|\]\(|<|>/i.test(v.answer)
    || !Number.isInteger(v.modelRounds) || v.modelRounds < 1 || v.modelRounds > 3 || !Array.isArray(v.sources) || v.sources.length > 4
    || v.answered !== (v.sources.length > 0) || !Array.isArray(v.steps) || v.steps.length > 4 || !Array.isArray(v.usage) || v.usage.length !== v.modelRounds) return invalid();
  const ids = new Set<string>();
  for (const s of v.sources) {
    if (!s || !/^S[1-4]$/.test(s.id) || ids.has(s.id) || !["article", "project"].includes(s.sourceType) || !Number.isSafeInteger(s.sourceId)
      || s.sourceId < 1 || typeof s.title !== "string" || s.title.length > 200 || !/^[a-f0-9]{64}$/.test(s.contentHash)
      || s.url !== `/${s.sourceType === "article" ? "articles" : "projects"}/${s.sourceId}`) return invalid();
    ids.add(s.id);
  }
  for (const s of v.steps) if (!s || !Number.isInteger(s.round) || s.round < 1 || s.round >= v.modelRounds
    || !["searchArticles", "searchProjects", "getArticleDetail", "getProjectDetail"].includes(s.tool) || !["FOUND", "NOT_FOUND"].includes(s.outcome)
    || !Number.isInteger(s.resultCount) || s.resultCount < 0 || s.resultCount > 5) return invalid();
  const validTokens = (n: number | null) => n === null || Number.isInteger(n) && n >= 0;
  for (const [i, u] of v.usage.entries()) if (!u || u.round !== i + 1 || !validTokens(u.inputTokens)
    || !validTokens(u.outputTokens)) return invalid();
  return v;
}
export async function askAgent(question: string, signal: AbortSignal): Promise<AgentResult> {
  const token = localStorage.getItem(TOKEN_KEY), controller = new AbortController(), untrack = trackAiRequest(controller);
  const abort = () => controller.abort(); signal.addEventListener("abort", abort, { once: true }); if (signal.aborted) abort();
  let timedOut = false;
  const timeout = setTimeout(() => { timedOut = true; abort(); }, 125000);
  try {
    const response = await fetch("/api/ai/agent/chat", { method: "POST", headers: { "Content-Type": "application/json", Accept: "text/event-stream",
      ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify({ question }), signal: controller.signal });
    if (!response.ok) {
      const value = await response.json().catch(() => ({})); if (response.status === 401 && token) notifyUnauthorized(token);
      throw new ApiError(response.status === 401 ? "请登录后重试" : response.status === 429 ? "请求较多，请稍后重试" : "助手尚未启用或请求失败，请重试", response.status, value.code);
    }
    if (!response.headers.get("content-type")?.includes("text/event-stream") || !response.body) return invalid();
    let requestId = "", result: AgentResult | undefined;
    await readSse(response.body, event => {
      if (event.event === "heartbeat") return;
      const value = JSON.parse(event.data);
      if (event.event === "error") {
        const messages: Record<number, string> = { 63002: "助手执行超时，请重试", 63003: "助手未完成有效回答，请重试", 63004: "已达到本次执行上限，请缩小问题后重试", 63005: "内容已变化，请重新提问", 42900: "请求较多，请稍后重试" };
        throw new ApiError(messages[value.code] || "助手暂不可用，请稍后重试", 503, value.code);
      }
      if (event.event === "meta") {
        if (requestId || value.mode !== "site-agent" || typeof value.requestId !== "string" || !value.requestId) return invalid();
        requestId = value.requestId;
      } else if (event.event === "done") { if (!requestId) return invalid(); result = validate(value, requestId); return false; }
      else return invalid();
    }, controller.signal);
    if (!result) return invalid(); return result;
  } catch (error) {
    if (timedOut) throw new ApiError("助手执行超时，请重试", 504);
    if (error instanceof ApiError) throw error;
    if (controller.signal.aborted) throw new DOMException("请求已停止", "AbortError");
    return invalid();
  } finally { clearTimeout(timeout); signal.removeEventListener("abort", abort); untrack(); }
}
