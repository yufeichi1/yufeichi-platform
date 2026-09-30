import { test, expect, type Page } from "@playwright/test";
import { resolve } from "node:path";

test.beforeAll(() => {
  if (process.env.E2E_ISOLATED !== "1" || process.env.E2E_AI !== "1")
    throw new Error("AI browser writes require E2E_AI=1 and the isolated test-web runner");
});
async function client(page: Page) {
  const response = await page.request.post("/api/auth/login", { data: { username: "admin", password: "Admin@123456" } });
  expect(response.ok()).toBeTruthy();
  const token = (await response.json()).data.token;
  await page.goto("/");
  await page.evaluate(token => localStorage.setItem("yufeichi.access-token", token), token);
  await page.addScriptTag({ path: resolve(".e2e-logs/ai-client.js") });
}
test("真实浏览器→Java→本地模型桩：JSON与多段SSE完成", async ({ page, request }) => {
  await request.get(process.env.E2E_AI_PROVIDER + "/__fixture?mode=normal");
  await client(page);
  const result = await page.evaluate(async () => {
    const api = (window as any).YufeichiAiClient;
    const json = await api.generateSummary("浏览器验收正文");
    const deltas: string[] = [];
    let final: any;
    await api.streamSummary("浏览器验收正文", { onDelta: (text: string) => deltas.push(text), onDone: (value: any) => { final = value; } });
    return { json, deltas, final };
  });
  expect(result.deltas.length).toBeGreaterThan(1);
  expect(result.deltas.join("")).toBe("这是浏览器测试摘要。");
  expect(result.final.summary).toBe(result.json.summary);
  expect(result.final.contentHash).toBe(result.json.contentHash);
  expect((await (await request.get(process.env.E2E_AI_PROVIDER + "/__fixture")).json()).calls).toBe(2);
});
test("浏览器停止→后端取消上游并释放占用，随后请求可用", async ({ page, request }) => {
  await request.get(process.env.E2E_AI_PROVIDER + "/__fixture?mode=slow");
  await client(page);
  const result = await page.evaluate(async () => {
    const controller = new AbortController();
    let deltas = 0, done = false;
    try {
      await (window as any).YufeichiAiClient.streamSummary("停止验收正文", {
        onDelta() { deltas++; controller.abort(); }, onDone() { done = true; },
      }, controller.signal);
      return { deltas, done, error: "" };
    } catch (error) { return { deltas, done, error: (error as Error).name }; }
  });
  expect(result).toEqual({ deltas: 1, done: false, error: "AbortError" });
  await expect.poll(async () => (await (await request.get(process.env.E2E_AI_PROVIDER + "/__fixture")).json()).active).toBe(0);
  await request.get(process.env.E2E_AI_PROVIDER + "/__fixture?mode=normal");
  expect(await page.evaluate(async () => (await (window as any).YufeichiAiClient.generateSummary("恢复验收正文")).summary))
    .toBe("这是浏览器测试摘要。");
});
test("缺失模型完成标记的流不能触发onDone", async ({ page, request }) => {
  await request.get(process.env.E2E_AI_PROVIDER + "/__fixture?mode=unfinished");
  await client(page);
  const result = await page.evaluate(async () => {
    let done = false;
    try {
      await (window as any).YufeichiAiClient.streamSummary("截断验收正文", { onDelta() {}, onDone() { done = true; } });
      return { done, status: 200 };
    } catch (error) { return { done, status: (error as any).status }; }
  });
  expect(result).toEqual({ done: false, status: 502 });
});
test("明确标记的HTTP错误模拟：429状态正确传递", async ({ page }) => {
  await client(page);
  await page.route("**/api/admin/ai/summary/stream", route => route.fulfill({ status: 429, contentType: "application/json",
    body: JSON.stringify({ code: 42900, message: "请求过于频繁", data: null }) }));
  expect(await page.evaluate(async () => {
    try { await (window as any).YufeichiAiClient.streamSummary("限流测试", { onDelta() {}, onDone() {} }); return 0; }
    catch (error) { return (error as any).status; }
  })).toBe(429);
});
test("真实退出登录会取消进行中的流，并撤销Token", async ({ page, request }) => {
  await request.get(process.env.E2E_AI_PROVIDER + "/__fixture?mode=slow");
  await client(page);
  const result = await page.evaluate(async () => {
    const api = (window as any).YufeichiAiClient;
    const oldToken = localStorage.getItem("yufeichi.access-token");
    let started!: () => void;
    const first = new Promise<void>(resolve => { started = resolve; });
    const stream = api.streamSummary("退出验收正文", { onDelta() { started(); }, onDone() {} })
      .then(() => "completed", (error: Error) => error.name);
    await first;
    await api.logout();
    const revoked = await fetch("/api/auth/me", { headers: { Authorization: "Bearer " + oldToken } });
    return { stream: await stream, tokenCleared: localStorage.getItem("yufeichi.access-token") === null,
      revokedStatus: revoked.status };
  });
  expect(result).toEqual({ stream: "AbortError", tokenCleared: true, revokedStatus: 401 });
  await expect.poll(async () => (await (await request.get(process.env.E2E_AI_PROVIDER + "/__fixture")).json()).active).toBe(0);
});
test("明确标记的HTTP错误模拟：401触发当前Token的失效回调", async ({ page }) => {
  await client(page);
  await page.route("**/api/admin/ai/summary/stream", route => route.fulfill({ status: 401, contentType: "application/json",
    body: JSON.stringify({ code: 40100, message: "登录已失效", data: null }) }));
  const result = await page.evaluate(async () => {
    const api = (window as any).YufeichiAiClient;
    let notified = false;
    api.onUnauthorized(() => { notified = true; api.cancelAiRequests(); });
    try { await api.streamSummary("失效测试", { onDelta() {}, onDone() {} }); return { status: 0, notified }; }
    catch (error) { return { status: (error as any).status, notified }; }
  });
  expect(result).toEqual({ status: 401, notified: true });
});
