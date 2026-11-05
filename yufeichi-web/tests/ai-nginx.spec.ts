import { test, expect } from "@playwright/test";
test.beforeAll(() => { if (process.env.E2E_NGINX !== "1" || process.env.E2E_AI !== "1") throw new Error("Use isolated Nginx fixture runner"); });
test("Nginx真实HTTPS流：首片段提前到达、心跳、完成，文档保持关闭", async ({ page, request }) => {
  const login = await request.post("/api/auth/login", { data: { username: "admin", password: "Admin@123456" } });
  expect(login.ok()).toBeTruthy(); const token = (await login.json()).data.token;
  await request.get(process.env.E2E_AI_PROVIDER + "/__fixture?mode=slow");
  await page.goto("/");
  const result = await page.evaluate(async token => {
    const start = performance.now();
    const response = await fetch("/api/admin/ai/summary/stream", { method: "POST", headers: {
      Authorization: "Bearer " + token, "Content-Type": "application/json" }, body: JSON.stringify({ content: "公开HTTPS代理流式验收正文" }) });
    const reader = response.body!.getReader(); const decoder = new TextDecoder();
    let text = "", firstDeltaMs: number | null = null;
    while (true) {
      const next = await reader.read(); if (next.done) break; text += decoder.decode(next.value, { stream: true });
      if (firstDeltaMs === null && text.includes("event:delta")) firstDeltaMs = performance.now() - start;
    }
    return { status: response.status, text, firstDeltaMs, totalMs: performance.now() - start };
  }, token);
  expect(result.status).toBe(200); expect(result.firstDeltaMs).not.toBeNull(); expect(result.firstDeltaMs!).toBeLessThan(2000);
  expect(result.totalMs).toBeGreaterThan(result.firstDeltaMs! + 500);
  expect(result.text).toContain("event:heartbeat"); expect(result.text).toContain("event:done"); expect(result.text).not.toContain("event:error");
  expect((await request.get("/v3/api-docs")).status()).toBe(404);
  console.log("NGINX_SSE_TIMING " + JSON.stringify({ firstDeltaMs: result.firstDeltaMs, totalMs: result.totalMs }));
});
test("两条AI连接占满时第三条429，原业务仍响应，停止后恢复", async ({ page, request }) => {
  const login = await request.post("/api/auth/login", { data: { username: "admin", password: "Admin@123456" } });
  expect(login.ok()).toBeTruthy(); const token = (await login.json()).data.token;
  await request.get(process.env.E2E_AI_PROVIDER + "/__fixture?mode=agent-slow"); await page.goto("/");
  await page.evaluate(token => {
    const holder = window as any; holder.faultControllers = [new AbortController(), new AbortController()];
    holder.faultTasks = holder.faultControllers.map(async (c: AbortController) => {
      try { const r = await fetch("/api/ai/agent/chat", { method: "POST", signal: c.signal,
        headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" }, body: JSON.stringify({ question: "查询 AgentBrowser" }) });
        await r.text(); } catch { /* Expected cancellation, no success result displayed. */ }
    });
  }, token);
  try {
    await expect.poll(async () => (await (await request.get(process.env.E2E_AI_PROVIDER + "/__fixture")).json()).active).toBe(2);
    const rejected = await request.post("/api/ai/agent/chat", { headers: { Authorization: "Bearer " + token }, data: { question: "第三条请求" } });
    expect(rejected.status()).toBe(429);
    const start = Date.now(); expect((await request.get("/api/articles")).ok()).toBeTruthy();
    expect(Date.now() - start).toBeLessThan(2000);
  } finally {
    await page.evaluate(async () => { const h = window as any; h.faultControllers.forEach((c: AbortController) => c.abort()); await Promise.all(h.faultTasks); });
  }
  await expect.poll(async () => (await (await request.get(process.env.E2E_AI_PROVIDER + "/__fixture")).json()).active).toBe(0);
  await request.get(process.env.E2E_AI_PROVIDER + "/__fixture?mode=normal");
  const recovered = await request.post("/api/ai/agent/chat", { headers: { Authorization: "Bearer " + token }, data: { question: "不存在的公开内容" } });
  expect(recovered.status()).toBe(200); expect(await recovered.text()).toContain("event:done");
});
