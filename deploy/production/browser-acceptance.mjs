// Run from this checkout: node deploy/production/browser-acceptance.mjs <private-admin.json>
// Login/logout only; the content write/restart checks are in acceptance.py.
import { createRequire } from "node:module";
import { readFile, mkdir } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
const require = createRequire(new URL("../../yufeichi-web/package.json", import.meta.url));
const { chromium, expect } = require("@playwright/test");
if (!process.argv[2]) throw new Error("Provide the protected administrator JSON file path");
const admin = JSON.parse(await readFile(process.argv[2], "utf8"));
const evidence = join(tmpdir(), "yufeichi-day6-browser");
await mkdir(evidence, { recursive: true });
const browser = await chromium.launch({ headless: true });
try {
  const context = await browser.newContext({ baseURL: "https://yufeichi.com", viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/", { waitUntil: "networkidle" });
  await expect(page.getByRole("heading", { name: /保持好奇/ })).toBeVisible();
  await page.screenshot({ path: join(evidence, "home-desktop.png"), fullPage: true });
  const script = await page.locator('script[src*="/assets/"]').first().getAttribute("src");
  const asset = await context.request.get(script);
  expect(asset.status()).toBe(200);
  expect(asset.headers()["cache-control"]).toContain("immutable");
  await page.setViewportSize({ width: 360, height: 800 });
  await page.reload({ waitUntil: "networkidle" });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: join(evidence, "home-mobile.png"), fullPage: true });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/login");
  await page.getByLabel("用户名", { exact: true }).fill(admin.username);
  await page.getByLabel("密码", { exact: true }).fill(admin.password);
  await page.getByRole("button", { name: "登录工作台" }).click();
  await expect(page).toHaveURL(/\/admin\/articles$/);
  await expect(page.getByRole("heading", { name: /文章管理/ })).toBeVisible();
  await page.reload({ waitUntil: "networkidle" });
  await expect(page.getByRole("heading", { name: /文章管理/ })).toBeVisible();
  await page.screenshot({ path: join(evidence, "admin-after-login.png"), fullPage: true });
  const token = await page.evaluate(() => localStorage.getItem("yufeichi.access-token"));
  await page.getByRole("button", { name: "退出登录" }).click();
  await expect(page).toHaveURL(/\/login$/);
  const revoked = await context.request.get("/api/auth/me", { headers: { Authorization: "Bearer " + token } });
  expect(revoked.status()).toBe(401);
  expect(errors).toEqual([]);
  console.log("PASS: public desktop/mobile, immutable assets, actual admin login, deep-link reload, logout and old-token 401; no browser JS errors");
  console.log("Screenshots:", evidence);
} catch (error) {
  console.error(String(error).replaceAll(admin.password, "[redacted]").replace(/Bearer\s+[A-Za-z0-9._-]+/g, "Bearer [redacted]"));
  process.exitCode = 1;
} finally {
  await browser.close();
}
