import { request, ApiError } from "./http";
export type Scope = "ALL" | "ARTICLE" | "PROJECT";
export interface ReadItem { sourceType: "article" | "project"; sourceId: number; title: string; excerpt: string; url: string; snapshotHash: string }
export interface ReadDetail { sourceType: "article" | "project"; sourceId: number; title: string; text: string; url: string; contentHash: string; truncated: boolean }
export interface ReadResult { requestId: string; mode: "fixed-query"; message: string; matches: ReadItem[]; details: ReadDetail[]; steps: { tool: string; outcome: string; resultCount: number }[] }
export function toolsStatus(signal: AbortSignal): Promise<{ available: boolean }> {
  return request({ url: "/ai/tools/status", signal });
}
export async function queryTools(keyword: string, scope: Scope, signal: AbortSignal): Promise<ReadResult> {
  const result = await request<ReadResult>({ url: "/ai/tools/query", method: "POST", data: { keyword, scope, limit: 3 }, signal });
  const validSource = (s: ReadItem | ReadDetail) => !!s && typeof s === "object" && ["article", "project"].includes(s.sourceType) && Number.isSafeInteger(s.sourceId)
    && s.sourceId > 0 && typeof s.title === "string" && s.title.length <= 200
    && s.url === `/${s.sourceType === "article" ? "articles" : "projects"}/${s.sourceId}`;
  if (!result || result.mode !== "fixed-query" || typeof result.requestId !== "string" || typeof result.message !== "string"
    || !Array.isArray(result.matches) || result.matches.length > 6 || !result.matches.every(s => validSource(s) && typeof s.excerpt === "string" && s.excerpt.length <= 200 && /^[a-f0-9]{64}$/.test(s.snapshotHash))
    || !Array.isArray(result.details) || result.details.length > 2 || !result.details.every(s => validSource(s) && typeof s.text === "string" && s.text.length <= 2000 && typeof s.truncated === "boolean" && /^[a-f0-9]{64}$/.test(s.contentHash) && result.matches.some(m => m.url === s.url))
    || !Array.isArray(result.steps) || result.steps.length > 4 || !result.steps.every(s => !!s && ["searchArticles", "searchProjects", "getArticleDetail", "getProjectDetail"].includes(s.tool)
      && ["FOUND", "NOT_FOUND"].includes(s.outcome) && Number.isInteger(s.resultCount) && s.resultCount >= 0 && s.resultCount <= 5))
    throw new ApiError("查询结果格式不符合要求，请重试", 502);
  return result;
}
