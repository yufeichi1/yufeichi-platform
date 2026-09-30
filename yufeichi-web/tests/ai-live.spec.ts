import { test, expect, type Page } from "@playwright/test";
import { resolve } from "node:path";

// Explicit paid smoke only. Four short Chat requests, fresh MySQL/Redis and public fixture text.
test.beforeAll(() => {
  if (process.env.E2E_ISOLATED !== "1" || process.env.E2E_AI_LIVE !== "1")
    throw new Error("Live AI browser acceptance requires the explicit isolated runner");
});
test.setTimeout(150000);
const content = "这是一段公开的验收夹具，并非私人文章。Yufeichi 是 Java 21 和 Vue 3 网站。文章摘要由作者预览后自行决定是否采用，AI 不会自动保存正文。AI 不可用时，网站原有登录和内容浏览仍应正常工作。";
async function client(page: Page) {
  const response = await page.request.post("/api/auth/login", { data: { username: "admin", password: "Admin@123456" } });
  expect(response.ok()).toBeTruthy();
  const token = (await response.json()).data.token;
  await page.goto("/");
  await page.evaluate(token => localStorage.setItem("yufeichi.access-token", token), token);
  await page.addScriptTag({ path: resolve(".e2e-logs/ai-client.js") });
}
test("真实DeepSeek：摘要JSON和浏览器SSE完整结束", async ({ page, request }) => {
  expect((await request.post("/api/admin/ai/summary", { data: { content } })).status()).toBe(401);
  await client(page);
  const result = await page.evaluate(async content => {
    const api = (window as any).YufeichiAiClient;
    const json = await api.generateSummary(content);
    let deltas = 0, streamed = "", final: any, mode = "";
    const started = performance.now();
    let firstDeltaMs = 0;
    await api.streamSummary(content, {
      onMeta(value: any) { mode = value.mode; },
      onDelta(text: string) { if (!deltas) firstDeltaMs = performance.now() - started; deltas++; streamed += text; },
      onDone(value: any) { final = value; },
    });
    return { jsonValid: typeof json.summary === "string" && json.summary.length > 0 && json.summary.length <= 500,
      deltas, streamValid: streamed.length > 0 && streamed.length <= 500 && final?.summary === streamed.trim(),
      sameHash: final?.contentHash === json.contentHash,
      mode,
      firstDeltaMs: Math.round(firstDeltaMs), totalMs: Math.round(performance.now() - started) };
  }, content);
  expect(result.jsonValid).toBe(true);
  expect(result.deltas).toBeGreaterThan(1);
  expect(result.streamValid).toBe(true);
  expect(result.sameHash).toBe(true);
  expect(result.mode).toBe("article-summary");
  expect(result.firstDeltaMs).toBeLessThan(result.totalMs);
  console.log("Live SSE evidence (single sample): " + JSON.stringify(result));
});
test("真实DeepSeek：首段后取消，随后摘要调用可用", async ({ page }) => {
  await client(page);
  const result = await page.evaluate(async content => {
    const api = (window as any).YufeichiAiClient;
    const controller = new AbortController();
    let deltas = 0, done = false, error = "";
    try {
      await api.streamSummary(content.repeat(5), {
        onDelta() { deltas++; controller.abort(); }, onDone() { done = true; },
      }, controller.signal);
    } catch (failure) { error = (failure as Error).name; }
    const next = await api.generateSummary(content);
    return { deltas, done, error, nextValid: typeof next.summary === "string" && next.summary.length > 0 };
  }, content);
  expect(result).toEqual({ deltas: 1, done: false, error: "AbortError", nextValid: true });
});
