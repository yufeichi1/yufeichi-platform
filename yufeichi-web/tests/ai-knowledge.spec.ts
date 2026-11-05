import { test, expect, type Page } from "@playwright/test";

test.beforeEach(async ({ page }) => {
  // UI fixture: backend visibility, quota and anonymous switch have separate real HTTP integration tests.
  await page.route("**/api/ai/knowledge/status", route => route.fulfill({ json: { code: 0, data: { available: true, publicEnabled: false, loginRequired: true } } }));
});
async function login(page: Page) {
  if (process.env.E2E_ISOLATED !== "1") throw new Error("Use isolated runner");
  const response = await page.request.post("/api/auth/login", { data: { username: "admin", password: "Admin@123456" } });
  expect(response.ok()).toBeTruthy();
  const token = (await response.json()).data.token;
  await page.goto("/");
  await page.evaluate(token => localStorage.setItem("yufeichi.access-token", token), token);
  return { Authorization: "Bearer " + token };
}
const source = { id: "S1", sourceType: "article", sourceId: 101, title: "已发布的验收文章", url: "/articles/101" };
function stream(answer: string, sources: unknown[] = [source], mode = "normal") {
  const frame = (event: string, data: unknown) => `event:${event}\ndata:${JSON.stringify(data)}\n\n`;
  const done = { answer, answered: sources.length > 0, sources, indexVersion: "00000000-0000-0000-0000-000000000001", requestId: "fixture-request" };
  return frame("meta", { requestId: done.requestId, mode: "site-knowledge" })
    + frame("heartbeat", { requestId: done.requestId }) + frame("delta", { text: answer })
    + frame("sources", sources) + (mode === "unfinished" ? "" : frame("done", done));
}
test("问答只读展示、结构化来源、原文路由、移动端和刷新清空", async ({ page }) => {
  const headers = await login(page);
  const created = await page.request.post("/api/admin/articles", { headers,
    data: { title: source.title, content: "验收项目采用Java21。", summary: "公开来源验收", tagIds: [], isTop: 0, isFeatured: 0 } });
  expect(created.ok()).toBeTruthy();
  const id = (await created.json()).data.id;
  expect((await page.request.post(`/api/admin/articles/${id}/publish`, { headers })).ok()).toBeTruthy();
  const actualSource = { ...source, sourceId: id, url: `/articles/${id}` };
  // Only the RAG response is a UI fixture; citation navigation reads the real isolated published article.
  await page.route("**/api/ai/knowledge/chat", route => route.fulfill({ contentType: "text/event-stream", body: stream("验收项目采用Java21。", [actualSource]) }));
  await page.goto("/ai/knowledge");
  await page.getByLabel("你的问题", { exact: true }).fill("项目使用哪些技术？");
  await page.getByRole("button", { name: "提问", exact: true }).click();
  await expect(page.getByRole("article", { name: "问答结果" })).toContainText("Java21");
  const reference = page.getByRole("link", { name: /已发布的验收文章/ });
  await expect(reference).toHaveAttribute("href", `/articles/${id}`);
  await page.setViewportSize({ width: 375, height: 812 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await reference.click();
  await expect(page.getByRole("heading", { name: source.title, exact: true })).toBeVisible();
  await expect(page.getByText("验收项目采用Java21。", { exact: true })).toBeVisible();
  await page.goto("/ai/knowledge");
  await page.reload();
  await expect(page.getByLabel("你的问题", { exact: true })).toHaveValue("");
  await expect(page.getByRole("article", { name: "问答结果" })).toHaveCount(0);
});
test("无答案、伪造来源链接和中断流不能显示为完成结果", async ({ page }) => {
  await login(page);
  let mode = "empty";
  await page.route("**/api/ai/knowledge/chat", route => route.fulfill({ contentType: "text/event-stream", body:
    mode === "empty" ? stream("站内暂无足够依据回答这个问题。", []) : mode === "bad" ? stream("伪造回答", [{ ...source, url: "https://evil.invalid" }]) : stream("中断回答", [source], "unfinished") }));
  await page.goto("/ai/knowledge");
  for (const scenario of ["empty", "bad", "unfinished"]) {
    mode = scenario;
    await page.getByLabel("你的问题", { exact: true }).fill("测试问题");
    await page.getByRole("button", { name: "提问", exact: true }).click();
    if (mode === "empty") {
      await expect(page.getByRole("article", { name: "问答结果" })).toContainText("暂无足够依据");
      await expect(page.getByRole("link", { name: /阅读原文/ })).toHaveCount(0);
    } else {
      await expect(page.getByRole("alert")).toContainText("来源格式不符合要求");
      await expect(page.getByRole("article", { name: "问答结果" })).toHaveCount(0);
    }
  }
});
test("问答关闭和配额不足可见，停止后没有残留回答", async ({ page }) => {
  await login(page);
  let status = 503;
  await page.route("**/api/ai/knowledge/chat", async route => {
    if (status === 4290) {
      await route.fulfill({ contentType: "text/event-stream", body: 'event:meta\ndata:{"requestId":"fixture-request","mode":"site-knowledge"}\n\nevent:error\ndata:{"code":42900}\n\n' });
      return;
    }
    if (status === 200) await new Promise(resolve => setTimeout(resolve, 1500));
    await route.fulfill(status === 200 ? { contentType: "text/event-stream", body: stream("不应显示") }
      : { status, json: { code: status, message: "PRIVATE_PROVIDER_DETAILS" } });
  });
  await page.goto("/ai/knowledge");
  await page.getByLabel("你的问题", { exact: true }).fill("Java项目");
  for (const code of [503, 429, 4290]) {
    status = code;
    await page.getByRole("button", { name: "提问", exact: true }).click();
    await expect(page.getByRole("alert")).toContainText(code === 503 ? "尚未启用" : "请求较多");
    await expect(page.getByText("PRIVATE_PROVIDER_DETAILS")).toHaveCount(0);
  }
  status = 200;
  await page.getByRole("button", { name: "提问", exact: true }).click();
  await page.getByRole("button", { name: "停止问答" }).click();
  await expect(page.getByRole("alert")).toContainText("已停止");
  await expect(page.getByRole("article", { name: "问答结果" })).toHaveCount(0);
});
test("匿名先登录且登录后返回问答，摘要工作台入口保留", async ({ page }) => {
  if (process.env.E2E_ISOLATED !== "1") throw new Error("Use isolated runner");
  await page.goto("/ai/knowledge");
  await expect(page).toHaveURL(/\/ai\/knowledge$/);
  await expect(page.getByRole("button", { name: "提问", exact: true })).toBeDisabled();
  await page.getByRole("link", { name: "登录试用" }).click();
  await expect(page).toHaveURL(/\/login\?redirect=/);
  await page.getByLabel("用户名", { exact: true }).fill("admin");
  await page.getByLabel("密码", { exact: true }).fill("Admin@123456");
  await page.getByRole("button", { name: "登录工作台" }).click();
  await expect.poll(() => new URL(page.url()).pathname).toBe("/ai/knowledge");
  await page.goto("/admin/ai");
  await expect(page.getByLabel("待摘要正文")).toBeVisible();
  await expect(page.getByRole("link", { name: /试用带来源/ })).toHaveAttribute("href", "/ai/knowledge");
});

test("公开开关开启时匿名问答不发送认证信息", async ({ page }) => {
  await page.route("**/api/ai/knowledge/status", route => route.fulfill({ json: { code: 0, data: { available: true, publicEnabled: true, loginRequired: false } } }));
  await page.route("**/api/ai/knowledge/chat", route => {
    expect(route.request().headers().authorization).toBeUndefined();
    return route.fulfill({ contentType: "text/event-stream", body: stream("匿名验收回答") });
  });
  await page.goto("/ai/knowledge");
  await expect(page.getByText(/每分钟最多3次/)).toBeVisible();
  await page.getByLabel("你的问题", { exact: true }).fill("Java公开文章");
  await page.getByRole("button", { name: "提问", exact: true }).click();
  await expect(page.getByRole("article", { name: "问答结果" })).toContainText("匿名验收回答");
});
test("服务关闭时匿名页面保留文章入口且不发送问答请求", async ({ page }) => {
  await page.route("**/api/ai/knowledge/status", route => route.fulfill({ json: { code: 0, data: { available: false, publicEnabled: false, loginRequired: true } } }));
  let calls = 0;
  page.on("request", r => { if (r.url().endsWith("/api/ai/knowledge/chat")) calls++; });
  await page.goto("/ai/knowledge");
  await expect(page.getByText(/问答暂不可用/)).toBeVisible();
  await page.getByLabel("你的问题", { exact: true }).fill("Java项目");
  await expect(page.getByRole("button", { name: "提问", exact: true })).toBeDisabled();
  await expect(page.getByRole("link", { name: "浏览文章" })).toHaveAttribute("href", "/articles");
  expect(calls).toBe(0);
});

test("后台显示自动同步暂停原因且可以手动恢复", async ({ page }) => {
  await login(page);
  await page.route("**/api/admin/ai/knowledge", route => route.fulfill({ json: { code: 0, data: {
    available: true, syncEnabled: true, state: { active_version: null, running_job: null },
    sync: { generation: 2, completed_generation: 1, failed_attempts: 3, last_error: "DEPENDENCY_UNAVAILABLE", next_attempt_at: null }, jobs: []
  } } }));
  let submitted = false;
  await page.route("**/api/admin/ai/knowledge/reindex", route => {
    submitted = true;
    expect(route.request().method()).toBe("POST");
    expect(route.request().headers().authorization).toMatch(/^Bearer /);
    return route.fulfill({ json: { code: 0, data: { jobId: "fixture-retry" } } });
  });
  await page.goto("/admin/ai");
  await expect(page.getByText(/已暂停重试/)).toBeVisible();
  await expect(page.getByText(/模型或索引服务不可用/)).toBeVisible();
  await page.getByRole("button", { name: "重建站内索引" }).click();
  await expect.poll(() => submitted).toBe(true);
});
