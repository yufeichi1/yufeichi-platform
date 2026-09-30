import { ApiError, TOKEN_KEY, notifyUnauthorized } from "@/api/http";
import { readSse } from "./sse";

export interface SummaryResponse {
  summary: string;
  contentHash: string;
  requestId: string;
}
export interface SummaryStreamHandlers {
  onMeta?: (meta: { requestId: string; contentHash: string; mode: string }) => void;
  onDelta: (text: string) => void;
  onDone: (result: SummaryResponse) => void;
}

const activeRequests = new Set<AbortController>();
export function cancelAiRequests(): void {
  for (const controller of activeRequests) controller.abort();
}
if (typeof window !== "undefined") window.addEventListener("pagehide", cancelAiRequests);

function result(value: unknown): SummaryResponse {
  if (!value || typeof value !== "object") throw new ApiError("AI 返回格式不符合要求", 502);
  const object = value as Record<string, unknown>;
  if (typeof object.summary !== "string" || !object.summary.trim() || object.summary.length > 500
      || typeof object.contentHash !== "string" || !/^[a-f0-9]{64}$/.test(object.contentHash)
      || typeof object.requestId !== "string") throw new ApiError("AI 返回格式不符合要求", 502);
  return object as unknown as SummaryResponse;
}

async function postSummary(content: string, stream: boolean, signal?: AbortSignal,
                           handlers?: SummaryStreamHandlers): Promise<SummaryResponse | void> {
  const token = localStorage.getItem(TOKEN_KEY);
  if (!token) throw new ApiError("请先登录", 401, 40100);
  if (!content.trim() || content.length > 12000) throw new ApiError("正文为空或超过 AI 输入限制", 400, 40000);
  const controller = new AbortController();
  const abort = () => controller.abort();
  signal?.addEventListener("abort", abort, { once: true });
  if (signal?.aborted) controller.abort();
  activeRequests.add(controller);
  let timedOut = false;
  const timeout = setTimeout(() => { timedOut = true; controller.abort(); }, 65000);
  try {
    const response = await fetch(`/api/admin/ai/summary${stream ? "/stream" : ""}`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: stream ? "text/event-stream" : "application/json",
        Authorization: `Bearer ${token}` },
      body: JSON.stringify({ content }), signal: controller.signal,
    });
    if (!response.ok) {
      // Error bodies are bounded and displayed only through trusted messages.
      const error = await response.json().catch(() => ({}));
      if (response.status === 401) notifyUnauthorized(token);
      throw new ApiError(typeof error.message === "string" ? error.message : "AI 请求失败，请重试",
        response.status, typeof error.code === "number" ? error.code : response.status);
    }
    if (!stream) {
      const envelope = await response.json();
      if (envelope.code !== 0) throw new ApiError("AI 请求失败，请重试", response.status, envelope.code);
      return result(envelope.data);
    }
    if (!response.headers.get("content-type")?.includes("text/event-stream") || !response.body)
      throw new ApiError("AI 数据流格式错误", 502);
    let done = false;
    let receivedMeta = false;
    let length = 0;
    let requestId = "";
    let contentHash = "";
    let accumulated = "";
    await readSse(response.body, (event) => {
      if (event.event === "heartbeat") return;
      const value = JSON.parse(event.data) as Record<string, unknown>;
      if (event.event === "error") {
        const status = value.code === 63002 ? 504 : value.code === 63003 ? 502 : value.code === 42900 ? 429 : 503;
        throw new ApiError(status === 504 ? "AI 请求超时，请重试" : "AI 生成未完成，请重试", status,
          typeof value.code === "number" ? value.code : status);
      }
      if (event.event === "meta") {
        if (receivedMeta || typeof value.requestId !== "string" || typeof value.contentHash !== "string"
            || !/^[a-f0-9]{64}$/.test(value.contentHash) || value.mode !== "article-summary")
          throw new ApiError("AI 数据流格式错误", 502);
        receivedMeta = true;
        requestId = value.requestId;
        contentHash = value.contentHash;
        handlers?.onMeta?.({ requestId, contentHash, mode: "article-summary" });
      } else if (event.event === "delta") {
        if (!receivedMeta || typeof value.text !== "string") throw new ApiError("AI 数据流格式错误", 502);
        length += value.text.length;
        if (length > 500) throw new ApiError("摘要超过长度限制", 502);
        accumulated += value.text;
        handlers?.onDelta(value.text);
      } else if (event.event === "done") {
        const final = result(value);
        if (!receivedMeta || final.requestId !== requestId || final.contentHash !== contentHash
            || final.summary !== accumulated.trim()) throw new ApiError("AI 数据流格式错误", 502);
        done = true;
        handlers?.onDone(final);
        return false;
      } else throw new ApiError("未知 AI 事件", 502);
    }, controller.signal);
    if (!done) throw new ApiError("AI 生成中断，结果尚未完成", 502);
  } catch (error) {
    if (timedOut) throw new ApiError("AI 请求超时，请重试", 504, 63002);
    if (error instanceof ApiError) throw error;
    if (controller.signal.aborted) throw new DOMException("请求已停止", "AbortError");
    throw new ApiError("AI 连接或数据流异常，请重试", 502);
  } finally {
    clearTimeout(timeout);
    signal?.removeEventListener("abort", abort);
    activeRequests.delete(controller);
  }
}

export async function generateSummary(content: string, signal?: AbortSignal): Promise<SummaryResponse> {
  return await postSummary(content, false, signal) as SummaryResponse;
}
export async function streamSummary(content: string, handlers: SummaryStreamHandlers, signal?: AbortSignal): Promise<void> {
  await postSummary(content, true, signal, handlers);
}
