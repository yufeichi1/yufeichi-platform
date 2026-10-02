// Explicit live HTTPS browser smoke: one paid summary, no article/project writes, no trace recording.
import { createRequire } from "node:module";
import { readFile, mkdir } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
const require = createRequire(new URL("../../yufeichi-web/package.json", import.meta.url));
const { chromium, expect } = require("@playwright/test");
if (!process.argv[2]) throw new Error("Provide protected administrator JSON file path");
const admin = JSON.parse(await readFile(process.argv[2], "utf8"));
const evidence = join(tmpdir(), "yufeichi-ai-day6-browser");
await mkdir(evidence, { recursive: true });
const browser = await chromium.launch({ headless: true });
try {
  const context = await browser.newContext({ baseURL: "https://yufeichi.com", viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage(), errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.goto("/login");
  await page.getByLabel("用户名", { exact: true }).fill(admin.username);
  await page.getByLabel("密码", { exact: true }).fill(admin.password);
  await page.getByRole("button", { name: "登录工作台" }).click();
  await expect(page).toHaveURL(/\/admin\/articles$/);
  await page.goto("/admin/ai");
  await page.getByLabel("待摘要正文").fill("公开上线验收文本：Java21和Spring Boot构建网站后端，MySQL保存业务数据，Redis保存认证撤销状态。AI文章摘要先预览，由管理员确认采用后再手动保存。站内内容索引只处理公开文章和项目。");
  await page.getByRole("button", { name: "生成摘要", exact: true }).click();
  await expect(page.getByRole("status")).toHaveText("生成完成，等待你确认", { timeout: 70000 });
  await page.getByRole("button", { name: "采用摘要", exact: true }).click();
  await expect(page.getByLabel("已采用摘要")).not.toHaveValue("");
  const index = page.getByRole("region", { name: "站内内容索引" });
  await expect(index.getByText("尚未建立", { exact: true })).not.toBeVisible();
  await expect(index.getByRole("button", { name: "重建站内索引" })).toBeEnabled();
  await expect(index.getByText(/索引成功/).first()).toBeVisible();
  await page.screenshot({ path: join(evidence, "ai-live-desktop.png"), fullPage: true });
  await page.setViewportSize({ width: 375, height: 812 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: join(evidence, "ai-live-mobile.png"), fullPage: true });
  await page.reload();
  await expect(page.getByLabel("待摘要正文")).toHaveValue("");
  await expect(index.getByText(/索引成功/).first()).toBeVisible();
  const token = await page.evaluate(() => localStorage.getItem("yufeichi.access-token"));
  await page.getByRole("button", { name: "退出登录" }).click();
  await expect(page).toHaveURL(/\/login$/);
  expect((await context.request.get("/api/auth/me", { headers: { Authorization: "Bearer " + token } })).status()).toBe(401);
  expect(errors).toEqual([]);
  console.log("AI_BROWSER_LIVE_PASS: real login, HTTPS summary, adopt without business save, index status, mobile, reload and logout");
  console.log("Evidence:", evidence);
} catch (error) {
  console.error(String(error).replaceAll(admin.password, "[redacted]").replace(/Bearer\s+[A-Za-z0-9._-]+/g, "Bearer [redacted]"));
  process.exitCode = 1;
} finally { await browser.close(); }
