import { test, expect, type Page } from "@playwright/test";
test.beforeAll(() => { if (process.env.E2E_ISOLATED !== "1" || process.env.E2E_AI !== "1") throw new Error("Use isolated AI fixture runner"); });
async function login(page: Page, username = "admin") {
  const response = await page.request.post("/api/auth/login", { data: { username, password: "Admin@123456" } }); expect(response.ok()).toBeTruthy();
  const token = (await response.json()).data.token; await page.goto("/"); await page.evaluate(token => localStorage.setItem("yufeichi.access-token", token), token);
  return { Authorization: `Bearer ${token}` };
}
test("真实网页到Spring AI工具协议：三轮两工具、公开来源与原文跳转", async ({ page, request }) => {
  const headers = await login(page), ids: number[] = [];
  try {
    const created = await page.request.post("/api/admin/articles", { headers, data: { title: "AgentBrowser公开测试", content: "公开测试构建标识是 AGENT_BROWSER_742。", tagIds: [] } });
    expect(created.ok()).toBeTruthy(); const id = (await created.json()).data.id; ids.push(id);
    expect((await page.request.post(`/api/admin/articles/${id}/publish`, { headers })).ok()).toBeTruthy();
    const privateArticle = await page.request.post("/api/admin/articles", { headers, data: { title: "AgentBrowser私密草稿", content: "PRIVATE_AGENT_BROWSER", tagIds: [] } });
    expect(privateArticle.ok()).toBeTruthy(); ids.push((await privateArticle.json()).data.id);
    await request.get(process.env.E2E_AI_PROVIDER + "/__fixture?mode=normal");
    await page.goto("/ai/assistant"); await page.getByLabel("你的任务", { exact: true }).fill("查询 AgentBrowser 文章并读取详情说明构建标识");
    await page.getByRole("button", { name: "运行助手", exact: true }).click();
    const result = page.getByRole("article", { name: "助手结果" }); await expect(result).toContainText("AGENT_BROWSER_742"); await expect(result).not.toContainText("PRIVATE_AGENT_BROWSER");
    await page.getByText("查看查询步骤", { exact: true }).click(); await expect(result).toContainText("模型请求 3 轮；只读工具 2 次");
    expect((await (await request.get(process.env.E2E_AI_PROVIDER + "/__fixture")).json()).calls).toBe(3);
    await page.setViewportSize({ width: 360, height: 780 }); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
    await page.getByLabel("助手来源").getByRole("link").click(); await expect(page).toHaveURL(new RegExp(`/articles/${id}$`));
    await expect(page.getByRole("heading", { name: "AgentBrowser公开测试", exact: true })).toBeVisible();
    await page.goto("/ai/assistant"); await page.reload(); await expect(page.getByLabel("你的任务", { exact: true })).toHaveValue("");
  } finally { for (const id of ids) expect((await page.request.delete(`/api/admin/articles/${id}`, { headers })).ok()).toBeTruthy(); }
});
test("普通账号无结果可解释，模型请求写工具被服务器终止", async ({ page, request }) => {
  await login(page, "day3-reader"); await request.get(process.env.E2E_AI_PROVIDER + "/__fixture?mode=normal");
  await page.goto("/ai/assistant"); await page.getByLabel("你的任务", { exact: true }).fill("没有对应公开内容的任务");
  await page.getByRole("button", { name: "运行助手", exact: true }).click(); await expect(page.getByRole("article", { name: "助手结果" })).toContainText("暂无足够依据");
  await expect(page.getByLabel("助手来源")).toHaveCount(0);
  await request.get(process.env.E2E_AI_PROVIDER + "/__fixture?mode=agent-write");
  await page.getByRole("button", { name: "运行助手", exact: true }).click(); await expect(page.getByRole("alert")).toContainText("未完成有效回答");
  await expect(page.getByRole("article", { name: "助手结果" })).toHaveCount(0);
});
test("匿名登录回跳、助手关闭仍有固定查询入口", async ({ page }) => {
  await page.goto("/ai/assistant"); await expect(page).toHaveURL(/\/login\?redirect=/);
  await page.getByLabel("用户名", { exact: true }).fill("admin"); await page.getByLabel("密码", { exact: true }).fill("Admin@123456"); await page.getByRole("button", { name: "登录工作台" }).click();
  await expect.poll(() => new URL(page.url()).pathname).toBe("/ai/assistant");
  await page.route("**/api/ai/agent/status", route => route.fulfill({ json: { code: 0, data: { available: false } } }));
  await page.reload(); await expect(page.getByText(/只读助手尚未启用/)).toBeVisible(); await expect(page.getByRole("button", { name: "运行助手", exact: true })).toBeDisabled();
  await expect(page.getByRole("link", { name: "按关键词查询" })).toHaveAttribute("href", "/ai/tools");
});
test("停止助手取消真实模型桩连接并清除未完成结果", async ({ page, request }) => {
  await login(page); await request.get(process.env.E2E_AI_PROVIDER + "/__fixture?mode=agent-slow"); await page.goto("/ai/assistant");
  await page.getByLabel("你的任务", { exact: true }).fill("查询 AgentBrowser"); await page.getByRole("button", { name: "运行助手", exact: true }).click();
  await expect.poll(async () => (await (await request.get(process.env.E2E_AI_PROVIDER + "/__fixture")).json()).active).toBe(1);
  await page.getByRole("button", { name: "停止助手" }).click(); await expect(page.getByRole("alert")).toContainText("已停止");
  await expect.poll(async () => (await (await request.get(process.env.E2E_AI_PROVIDER + "/__fixture")).json()).active).toBe(0);
  await expect(page.getByRole("article", { name: "助手结果" })).toHaveCount(0);
  await page.getByRole("link", { name: "浏览文章" }).click(); await page.goto("/ai/assistant"); await expect(page.getByLabel("你的任务", { exact: true })).toHaveValue("");
});
test("伪造来源和超限结果不作为有效回答展示", async ({ page }) => {
  await login(page); let forged = true;
  await page.route("**/api/ai/agent/chat", route => {
    const rounds = forged ? 3 : 4;
    const value = { requestId: "fake", mode: "site-agent", answered: true, answer: "无效夹具", sources: [{ id: "S1", sourceType: "article", sourceId: 1,
      title: "夹具", url: forged ? "https://evil.invalid" : "/articles/1", contentHash: "a".repeat(64) }], modelRounds: rounds, steps: [],
      usage: Array.from({ length: rounds }, (_, i) => ({ round: i + 1, inputTokens: 1, outputTokens: 1 })) };
    return route.fulfill({ contentType: "text/event-stream", body: 'event: meta\ndata: {"requestId":"fake","mode":"site-agent"}\n\nevent: done\ndata: ' + JSON.stringify(value) + "\n\n" });
  });
  await page.goto("/ai/assistant"); await page.getByLabel("你的任务", { exact: true }).fill("Java");
  for (let i = 0; i < 2; i++) {
    await page.getByRole("button", { name: "运行助手", exact: true }).click();
    await expect(page.getByRole("alert")).toContainText("格式不符合要求"); await expect(page.getByRole("article", { name: "助手结果" })).toHaveCount(0);
    forged = false;
  }
});
