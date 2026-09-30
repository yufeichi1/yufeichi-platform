import { test, expect, type Page, type APIRequestContext } from "@playwright/test";

test.beforeAll(() => {
  if (process.env.E2E_ISOLATED !== "1" || process.env.E2E_AI !== "1") throw new Error("Use the isolated AI fixture runner");
});
async function scenario(request: APIRequestContext, mode = "normal") {
  await request.get(process.env.E2E_AI_PROVIDER + "/__fixture?mode=" + mode);
}
async function login(page: Page, username = "admin") {
  const response = await page.request.post("/api/auth/login", { data: { username, password: "Admin@123456" } });
  expect(response.ok()).toBeTruthy();
  const token = (await response.json()).data.token;
  await page.goto("/");
  await page.evaluate(token => localStorage.setItem("yufeichi.access-token", token), token);
  return { Authorization: "Bearer " + token };
}
const panel = (page: Page) => page.getByRole("region", { name: "AI 摘要助手", exact: true });

test("编辑页预览不自动保存，确认采用后手动保存才写入", async ({ page, request }) => {
  await scenario(request);
  const headers = await login(page);
  const created = await page.request.post("/api/admin/articles", { headers,
    data: { title: "Day4摘要验收夹具", content: "原始验收正文", summary: "人工摘要", tagIds: [], isTop: 0, isFeatured: 0 } });
  expect(created.ok()).toBeTruthy();
  const id = (await created.json()).data.id;
  await page.goto(`/admin/articles/${id}/edit`);
  await page.getByRole("button", { name: "生成摘要", exact: true }).click();
  await expect(panel(page).getByRole("status")).toHaveText("生成完成，等待你确认");
  await expect(page.getByLabel("摘要", { exact: true })).toHaveValue("人工摘要");
  await page.getByRole("button", { name: "采用摘要", exact: true }).click();
  await page.getByRole("button", { name: "保留原摘要", exact: true }).click();
  await expect(page.getByLabel("摘要", { exact: true })).toHaveValue("人工摘要");
  await page.getByRole("button", { name: "采用摘要", exact: true }).click();
  await page.getByRole("button", { name: "确认替换", exact: true }).click();
  await expect(page.getByLabel("摘要", { exact: true })).toHaveValue("这是浏览器测试摘要。");
  expect((await (await page.request.get(`/api/admin/articles/${id}`, { headers })).json()).data.summary).toBe("人工摘要");
  await page.getByRole("button", { name: "保存草稿", exact: true }).click();
  await expect(page.getByText("保存成功", { exact: true })).toBeVisible();
  const article = (await (await page.request.get(`/api/admin/articles/${id}`, { headers })).json()).data;
  expect(article.summary).toBe("这是浏览器测试摘要。");
  expect(article.content).toBe("原始验收正文");
  expect(article.status).toBe(0);
  await page.request.delete(`/api/admin/articles/${id}`, { headers });
});
test("生成期间修改正文会停止，完成后修改又恢复也不能采用旧候选", async ({ page, request }) => {
  await scenario(request, "slow");
  await login(page);
  await page.goto("/admin/ai");
  await page.getByLabel("待摘要正文").fill("快照正文");
  await page.getByRole("button", { name: "生成摘要", exact: true }).click();
  await expect(panel(page).getByTestId("ai-summary-preview")).toBeVisible();
  await expect(page.getByRole("button", { name: "正在生成…", exact: true })).toBeDisabled();
  await page.getByRole("button", { name: "正在生成…", exact: true }).dispatchEvent("click");
  expect((await (await request.get(process.env.E2E_AI_PROVIDER + "/__fixture")).json()).calls).toBe(1);
  await page.getByLabel("待摘要正文").fill("已修改正文");
  await expect(panel(page).getByRole("status")).toContainText("生成已停止");
  await expect.poll(async () => (await (await request.get(process.env.E2E_AI_PROVIDER + "/__fixture")).json()).active).toBe(0);
  await expect(page.getByRole("button", { name: "采用摘要", exact: true })).toBeDisabled();
  await scenario(request);
  await page.getByRole("button", { name: "生成摘要", exact: true }).click();
  await expect(panel(page).getByRole("status")).toContainText("生成完成");
  await page.getByLabel("待摘要正文").fill("另一次修改");
  await page.getByLabel("待摘要正文").fill("已修改正文");
  await expect(page.getByRole("button", { name: "采用摘要", exact: true })).toBeDisabled();
  await expect(panel(page).getByText("正文已变化，请重新生成摘要。", { exact: true })).toBeVisible();
});
test("停止和离开页面取消流，临时输入不会跨页面保存", async ({ page, request }) => {
  await scenario(request, "slow");
  await login(page);
  await page.goto("/admin/ai");
  await page.getByLabel("待摘要正文").fill("临时验收正文");
  await page.getByRole("button", { name: "生成摘要", exact: true }).click();
  await expect(panel(page).getByTestId("ai-summary-preview")).toBeVisible();
  await page.getByRole("button", { name: "停止生成", exact: true }).click();
  await expect(panel(page).getByRole("status")).toContainText("生成已停止");
  await expect.poll(async () => (await (await request.get(process.env.E2E_AI_PROVIDER + "/__fixture")).json()).active).toBe(0);
  await page.getByRole("button", { name: "生成摘要", exact: true }).click();
  await expect(panel(page).getByTestId("ai-summary-preview")).toBeVisible();
  await page.getByRole("link", { name: "前往文章管理 →" }).click();
  await expect.poll(async () => (await (await request.get(process.env.E2E_AI_PROVIDER + "/__fixture")).json()).active).toBe(0);
  await page.goto("/admin/ai");
  await expect(page.getByLabel("待摘要正文")).toHaveValue("");
});
test("空正文、超长、无权限与匿名入口不发起模型请求", async ({ page, request }) => {
  await scenario(request);
  await page.goto("/");
  await page.getByRole("link", { name: "AI 助手", exact: true }).click();
  await expect(page).toHaveURL(/\/login\?redirect=/);
  await login(page, "day3-reader");
  await page.goto("/admin/ai");
  await expect(page).toHaveURL(/\/403$/);
  await login(page);
  await page.goto("/admin/articles/new");
  await expect(page.getByRole("button", { name: "生成摘要", exact: true })).toBeDisabled();
  await page.locator("#article-content").fill("长".repeat(12001));
  await expect(panel(page).getByText(/正文超过 12000/)).toBeVisible();
  await expect(page.getByRole("button", { name: "生成摘要", exact: true })).toBeDisabled();
  expect((await (await request.get(process.env.E2E_AI_PROVIDER + "/__fixture")).json()).calls).toBe(0);
});
test("明确HTTP故障模拟：错误保留正文和人工摘要，成功后可重试", async ({ page, request }) => {
  await scenario(request);
  await login(page);
  await page.goto("/admin/articles/new");
  await page.locator("#article-content").fill("保留正文");
  await page.getByLabel("摘要", { exact: true }).fill("保留人工摘要");
  await page.route("**/api/admin/ai/summary/stream", route => route.fulfill({ status: 503, contentType: "application/json",
    body: JSON.stringify({ code: 63001, message: "AI 暂时不可用，请稍后重试", data: null }) }));
  await page.getByRole("button", { name: "生成摘要", exact: true }).click();
  await expect(panel(page).getByRole("status")).toContainText("生成未完成");
  await expect(page.locator("#article-content")).toHaveValue("保留正文");
  await expect(page.getByLabel("摘要", { exact: true })).toHaveValue("保留人工摘要");
  await page.unroute("**/api/admin/ai/summary/stream");
  await page.getByRole("button", { name: "生成摘要", exact: true }).click();
  await expect(panel(page).getByRole("status")).toContainText("生成完成");
});
test("退出工作台取消正在生成的摘要并清理登录", async ({ page, request }) => {
  await scenario(request, "slow");
  await login(page);
  await page.goto("/admin/ai");
  await page.getByLabel("待摘要正文").fill("退出测试正文");
  await page.getByRole("button", { name: "生成摘要", exact: true }).click();
  await expect(panel(page).getByTestId("ai-summary-preview")).toBeVisible();
  await page.getByRole("button", { name: "退出登录", exact: true }).click();
  await expect(page).toHaveURL(/\/login$/);
  await expect.poll(async () => (await (await request.get(process.env.E2E_AI_PROVIDER + "/__fixture")).json()).active).toBe(0);
  expect(await page.evaluate(() => localStorage.getItem("yufeichi.access-token"))).toBeNull();
});
test("明确401模拟：重新登录后恢复编辑正文和人工摘要", async ({ page, request }) => {
  await scenario(request);
  await login(page);
  await page.goto("/admin/articles/new");
  await page.getByLabel("文章标题").fill("登录恢复夹具");
  await page.locator("#article-content").fill("待恢复正文");
  await page.getByLabel("摘要", { exact: true }).fill("待恢复人工摘要");
  await page.route("**/api/admin/ai/summary/stream", route => route.fulfill({ status: 401, contentType: "application/json",
    body: JSON.stringify({ code: 40100, message: "登录已失效", data: null }) }));
  await page.getByRole("button", { name: "生成摘要", exact: true }).click();
  await expect(page).toHaveURL(/\/login\?.*expired=1/);
  await page.getByLabel("用户名", { exact: true }).fill("admin");
  await page.getByLabel("密码", { exact: true }).fill("Admin@123456");
  await page.getByRole("button", { name: "登录工作台" }).click();
  await expect(page).toHaveURL(/\/admin\/articles\/new$/);
  await expect(page.locator("#article-content")).toHaveValue("待恢复正文");
  await expect(page.getByLabel("摘要", { exact: true })).toHaveValue("待恢复人工摘要");
});
test("安全Markdown与360px手机页面：恶意链接不执行，按钮可操作", async ({ page, request }) => {
  await scenario(request, "markdown");
  await page.setViewportSize({ width: 360, height: 800 });
  await login(page);
  await page.goto("/admin/ai");
  await page.getByLabel("待摘要正文").fill("安全渲染验收正文");
  await page.getByRole("button", { name: "生成摘要", exact: true }).click();
  await expect(panel(page).getByRole("status")).toContainText("生成完成");
  expect(await panel(page).locator("a[href^='javascript:'], img[src^='javascript:'], script").count()).toBe(0);
  expect(await page.evaluate(() => (window as any).__aiXss)).toBeUndefined();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
  await page.getByRole("button", { name: "采用摘要", exact: true }).click();
  await expect(page.getByLabel("已采用摘要")).not.toHaveValue("");
  await page.screenshot({ path: "test-results/day4-ai-mobile.png", fullPage: true });
});
