import { test, expect } from "@playwright/test";
import { join } from "node:path";
import { writeFileSync, copyFileSync } from "node:fs";
test.beforeAll(() => {
  if (process.env.E2E_ISOLATED !== "1" || process.env.E2E_AI_DEMO !== "1" || process.env.E2E_NGINX !== "1")
    throw new Error("Use explicit isolated real-model Nginx demo runner");
});
test.setTimeout(240000);
test.afterEach(async ({ page }) => {
  const video = page.video(); await page.context().close();
  if (video) copyFileSync(await video.path(), join(process.env.E2E_VIDEO_DIR!, "three-scenes-real-model.webm"));
});
test("真实模型三场景：摘要人工采用、向量问答引用、模型选择只读工具", async ({ page }) => {
  const dir = process.env.E2E_VIDEO_DIR!;
  const login = await page.request.post("/api/auth/login", { data: { username: "admin", password: "Admin@123456" } });
  expect(login.ok()).toBeTruthy(); const token = (await login.json()).data.token;
  const headers = { Authorization: "Bearer " + token };
  await page.goto("/"); await page.evaluate(t => localStorage.setItem("yufeichi.access-token", t), token);
  await page.goto("/admin/articles/new");
  await page.getByLabel("文章标题").fill("公开隔离演示：Lighthouse");
  const content = "公开隔离验收夹具：Lighthouse 的构建标记为 LIGHTHOUSE-957。使用Java21与Vue3。文章摘要由作者预览后人工采用，再手动保存。此内容仅用于测试，不代表线上业务事实。";
  await page.locator("#article-content").fill(content);
  const frames: { event: string; ms: number }[] = []; const started = Date.now();
  page.on("response", async response => {
    if (!response.url().endsWith("/api/admin/ai/summary/stream")) return;
    // Wire timing is verified separately in the local protocol suite; this records final real-model completion.
    frames.push({ event: "http-response", ms: Date.now() - started });
  });
  await page.getByRole("button", { name: "生成摘要", exact: true }).click();
  await expect(page.getByRole("region", { name: "AI 摘要助手" }).getByRole("status")).toContainText("生成完成", { timeout: 65000 });
  await expect(page.getByLabel("摘要", { exact: true })).toHaveValue("");
  await page.getByRole("button", { name: "采用摘要", exact: true }).click();
  expect((await page.getByLabel("摘要", { exact: true }).inputValue()).length).toBeGreaterThan(0);
  await page.screenshot({ path: join(dir, "01-summary.png"), fullPage: true });
  await page.getByRole("button", { name: "保存草稿", exact: true }).click();
  await expect(page).toHaveURL(/\/admin\/articles\/\d+\/edit$/); const id = page.url().match(/articles\/(\d+)/)![1];
  expect((await page.request.post(`/api/admin/articles/${id}/publish`, { headers })).ok()).toBeTruthy();
  const indexing = await page.request.post("/api/admin/ai/knowledge/reindex", { headers }); expect(indexing.ok()).toBeTruthy();
  await expect.poll(async () => {
    const status = (await (await page.request.get("/api/admin/ai/knowledge", { headers })).json()).data;
    return status.jobs[0]?.status;
  }, { timeout: 90000 }).toBe("SUCCEEDED");
  await page.goto("/ai/knowledge"); await page.getByLabel("你的问题", { exact: true }).fill("Lighthouse 的构建标记是什么？");
  await page.getByRole("button", { name: "提问", exact: true }).click();
  await expect(page.getByRole("article", { name: "问答结果" })).toContainText("LIGHTHOUSE-957", { timeout: 65000 });
  await expect(page.getByLabel("原文来源").getByRole("link")).toHaveAttribute("href", `/articles/${id}`);
  await page.screenshot({ path: join(dir, "02-rag.png"), fullPage: true });
  writeFileSync(join(dir, "rag-display.txt"), await page.getByRole("article", { name: "问答结果" }).innerText());
  await page.getByLabel("原文来源").getByRole("link").click(); await expect(page.getByRole("heading", { name: "公开隔离演示：Lighthouse", exact: true })).toBeVisible();
  await page.goto("/ai/assistant"); await page.getByLabel("你的任务", { exact: true }).fill("请搜索 Lighthouse 文章，读取详情后告诉我构建标记。");
  await page.getByRole("button", { name: "运行助手", exact: true }).click();
  await expect(page.getByRole("article", { name: "助手结果" })).toContainText("LIGHTHOUSE-957", { timeout: 65000 });
  await page.getByText("查看查询步骤", { exact: true }).click();
  await expect(page.getByRole("article", { name: "助手结果" })).toContainText("只读工具 2 次");
  await page.screenshot({ path: join(dir, "03-agent.png"), fullPage: true });
  writeFileSync(join(dir, "agent-display.txt"), await page.getByRole("article", { name: "助手结果" }).innerText());
  await page.setViewportSize({ width: 375, height: 812 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBeTruthy();
  await page.screenshot({ path: join(dir, "04-mobile.png"), fullPage: true });
  writeFileSync(join(dir, "demo.json"), JSON.stringify({ kind: "real-model-on-isolated-synthetic-content", clientDate: "2026-11-05",
    summary: "preview-adopt-manual-save", rag: "source-link-opened", agent: "search-detail-answer", production: false, frames }, null, 2));
  console.log("LIVE_THREE_SCENE_DEMO_PASS; evidence=" + dir);
});
