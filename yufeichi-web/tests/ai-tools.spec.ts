import { test, expect, type Page } from "@playwright/test";
test.beforeAll(() => { if (process.env.E2E_ISOLATED !== "1" || process.env.E2E_AI !== "1") throw new Error("Use the isolated AI fixture runner"); });
async function login(page: Page, username = "admin") {
  const response = await page.request.post("/api/auth/login", { data: { username, password: "Admin@123456" } });
  expect(response.ok()).toBeTruthy(); const token = (await response.json()).data.token;
  await page.goto("/"); await page.evaluate(token => localStorage.setItem("yufeichi.access-token", token), token);
  return { Authorization: `Bearer ${token}` };
}
test("真实数据库固定查询：公开文章项目、四个步骤、原文跳转和移动端", async ({ page, request }) => {
  const headers = await login(page);
  const name = "Day10Query公开夹具";
  const createdArticles: number[] = [], createdProjects: number[] = [];
  try {
  const article = await page.request.post("/api/admin/articles", { headers, data: { title: name, content: "Java21查询测试正文", tagIds: [], isTop: 0, isFeatured: 0 } });
  expect(article.ok()).toBeTruthy(); const id = (await article.json()).data.id; createdArticles.push(id);
  expect((await page.request.post(`/api/admin/articles/${id}/publish`, { headers })).ok()).toBeTruthy();
  const draft = await page.request.post("/api/admin/articles", { headers, data: { title: name + "草稿", content: "PRIVATE_TOOL_DRAFT", tagIds: [] } }); expect(draft.ok()).toBeTruthy();
  createdArticles.push((await draft.json()).data.id);
  const project = await page.request.post("/api/admin/projects", { headers, data: { name, description: "只读项目测试", techStack: "Java", status: 1, sortOrder: 0 } }); expect(project.ok()).toBeTruthy();
  createdProjects.push((await project.json()).data.id);
  await request.get(process.env.E2E_AI_PROVIDER + "/__fixture?mode=normal");
  await page.goto("/ai/tools");
  await page.getByLabel("关键词", { exact: true }).fill("Day10Query");
  await page.getByRole("button", { name: "查询内容", exact: true }).click();
  const result = page.getByRole("article", { name: "内容查询结果" });
  await expect(result).toContainText("Java21查询测试正文"); await expect(result).toContainText("只读项目测试");
  await expect(result).not.toContainText("PRIVATE_TOOL_DRAFT"); await expect(page.getByLabel("查询步骤").locator("li")).toHaveCount(4);
  expect((await (await request.get(process.env.E2E_AI_PROVIDER + "/__fixture")).json()).calls).toBe(0);
  await page.setViewportSize({ width: 360, height: 780 }); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  await result.getByRole("link", { name: "阅读原文" }).first().click();
  await expect(page).toHaveURL(new RegExp(`/articles/${id}$`)); await expect(page.getByRole("heading", { name, exact: true })).toBeVisible();
  await page.goto("/ai/tools"); await page.reload(); await expect(page.getByLabel("关键词", { exact: true })).toHaveValue("");
  await expect(page.getByRole("article", { name: "内容查询结果" })).toHaveCount(0);
  } finally {
    // Remove only the IDs created by this test in the disposable fixture database.
    for (const id of createdArticles) expect((await page.request.delete(`/api/admin/articles/${id}`, { headers })).ok()).toBeTruthy();
    for (const id of createdProjects) expect((await page.request.delete(`/api/admin/projects/${id}`, { headers })).ok()).toBeTruthy();
  }
});
test("只读账号可查询公开内容，无结果解释且非法关键词不会展示旧结果", async ({ page }) => {
  await login(page, "day3-reader"); await page.goto("/ai/tools");
  await page.getByLabel("查询范围").selectOption("PROJECT");
  await page.getByLabel("关键词", { exact: true }).fill("NOTEXISTDAY10");
  await page.getByRole("button", { name: "查询内容", exact: true }).click();
  await expect(page.getByRole("article", { name: "内容查询结果" })).toContainText("未找到");
  await expect(page.getByLabel("查询步骤").locator("li")).toHaveCount(1);
  await page.getByLabel("关键词", { exact: true }).fill("%"); await page.getByRole("button", { name: "查询内容", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("关键词"); await expect(page.getByRole("article", { name: "内容查询结果" })).toHaveCount(0);
});
test("匿名跳转登录后回到查询页面，关闭功能仍可浏览文章", async ({ page }) => {
  await page.goto("/ai/tools"); await expect(page).toHaveURL(/\/login\?redirect=/);
  await page.getByLabel("用户名", { exact: true }).fill("admin"); await page.getByLabel("密码", { exact: true }).fill("Admin@123456");
  await page.getByRole("button", { name: "登录工作台" }).click(); await expect.poll(() => new URL(page.url()).pathname).toBe("/ai/tools");
  await page.route("**/api/ai/tools/status", route => route.fulfill({ json: { code: 0, data: { available: false } } }));
  await page.reload(); await expect(page.getByText(/内容查询尚未启用/)).toBeVisible();
  await expect(page.getByRole("button", { name: "查询内容", exact: true })).toBeDisabled();
  await expect(page.getByRole("link", { name: "浏览文章" })).toHaveAttribute("href", "/articles");
});
test("伪造外链来源被拒绝，原文HTML以纯文本展示", async ({ page }) => {
  await login(page); let forged = true;
  const id = 101, hash = "a".repeat(64);
  await page.route("**/api/ai/tools/query", route => route.fulfill({ json: { code: 0, data: {
    requestId: "fixture-tools", mode: "fixed-query", message: "固定查询夹具", steps: [{ tool: "searchArticles", outcome: "FOUND", resultCount: 1 }],
    matches: [{ sourceType: "article", sourceId: id, title: "纯文本夹具", excerpt: "预览", snapshotHash: hash, url: forged ? "https://evil.invalid" : "/articles/101" }],
    details: [{ sourceType: "article", sourceId: id, title: "纯文本夹具", text: '<img src=x onerror="window.__toolXss=1">', contentHash: hash, truncated: false, url: "/articles/101" }]
  } } }));
  await page.goto("/ai/tools"); await page.getByLabel("关键词", { exact: true }).fill("Java");
  await page.getByRole("button", { name: "查询内容", exact: true }).click(); await expect(page.getByRole("alert")).toContainText("格式不符合要求");
  forged = false; await page.getByRole("button", { name: "查询内容", exact: true }).click();
  const result = page.getByRole("article", { name: "内容查询结果" }); await expect(result).toContainText("<img");
  await expect(result.locator("img,script")).toHaveCount(0); expect(await page.evaluate(() => (window as any).__toolXss)).toBeUndefined();
});
test("停止查询及离页不会留下未完成结果", async ({ page }) => {
  await login(page); await page.route("**/api/ai/tools/query", async route => {
    await new Promise(resolve => setTimeout(resolve, 800)); await route.fulfill({ status: 503, json: { code: 63001, message: "已取消夹具" } });
  });
  await page.goto("/ai/tools"); await page.getByLabel("关键词", { exact: true }).fill("Java");
  await page.getByRole("button", { name: "查询内容", exact: true }).click(); await page.getByRole("button", { name: "停止查询" }).click();
  await expect(page.getByRole("alert")).toContainText("已停止查询"); await expect(page.getByRole("article", { name: "内容查询结果" })).toHaveCount(0);
  await page.getByRole("button", { name: "查询内容", exact: true }).click(); await page.getByRole("link", { name: "浏览文章" }).click();
  await page.goto("/ai/tools"); await expect(page.getByLabel("关键词", { exact: true })).toHaveValue("");
});
