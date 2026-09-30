import { test, expect, type Page } from "@playwright/test";

async function login(page: Page) {
  if (process.env.E2E_ISOLATED !== "1") throw new Error("Use isolated runner");
  const response = await page.request.post("/api/auth/login", { data: { username: "admin", password: "Admin@123456" } });
  expect(response.ok()).toBeTruthy();
  const token = (await response.json()).data.token;
  await page.goto("/");
  await page.evaluate(token => localStorage.setItem("yufeichi.access-token", token), token);
}
test("未启用索引服务时入口明确禁用，原摘要页可用", async ({ page }) => {
  await login(page); await page.goto("/admin/ai");
  await expect(page.getByRole("button", { name: "重建站内索引" })).toBeDisabled();
  await expect(page.getByText("索引服务尚未启用。", { exact: true })).toBeVisible();
  await expect(page.getByLabel("待摘要正文")).toBeVisible();
});
test("任务状态与失败提示可见，处理期间禁止重复提交", async ({ page }) => {
  await login(page);
  let submitted = 0, status = "idle";
  // UI state fixture only; real MySQL/PG indexing and permissions have backend integration coverage.
  await page.route("**/api/admin/ai/knowledge**", async route => {
    if (route.request().method() === "POST") {
      submitted++; status = "running";
      await route.fulfill({ json: { code: 0, data: { jobId: "fixture-job" } } }); return;
    }
    await route.fulfill({ json: { code: 0, data: { available: true,
      state: { active_version: "existing-verified-version", running_job: status === "running" ? "fixture-job" : null },
      jobs: status === "idle" ? [] : [{ id: "fixture-job", status: status === "running" ? "RUNNING" : "FAILED",
        source_count: 10, chunk_count: 26, completed_chunks: 20, error_code: status === "failed" ? "DEPENDENCY_UNAVAILABLE" : null }] } } });
  });
  await page.goto("/admin/ai");
  const button = page.getByRole("button", { name: "重建站内索引" });
  await expect(button).toBeEnabled(); await button.click();
  await expect(page.getByText(/正在索引 · 10 个来源/)).toBeVisible();
  await expect(button).toBeDisabled(); expect(submitted).toBe(1);
  status = "failed";
  await expect(page.getByText("模型或索引服务不可用，旧版本继续保留。")).toBeVisible();
  await expect(button).toBeEnabled(); await expect(page.getByText("existing-verified-version")).toBeVisible();
  await page.setViewportSize({ width: 375, height: 812 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
});
