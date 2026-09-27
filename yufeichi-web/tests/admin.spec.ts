import { test, expect, type Page } from "@playwright/test";
import { createHmac } from "node:crypto";
import { resolve } from "node:path";

test.beforeAll(() => {
  if (process.env.E2E_ISOLATED !== "1")
    throw new Error(
      "Use node scripts/test-web.mjs: writes require a disposable Day3 database",
    );
});
async function login(page: Page, username = "admin") {
  await page.goto("/login");
  await page.getByLabel("用户名", { exact: true }).fill(username);
  await page.getByLabel("密码", { exact: true }).fill("Admin@123456");
  await page.getByRole("button", { name: "登录工作台" }).click();
  await expect(page).toHaveURL(/\/admin\/articles$/);
  await expect(page.getByRole("heading", { name: /文章管理/ })).toBeVisible();
}
async function createTaxonomy(
  page: Page,
  kind: "分类" | "标签",
  name: string,
  slug: string,
) {
  await page.getByRole("link", { name: new RegExp(kind + "管理") }).click();
  await page.getByRole("button", { name: "新建" + kind }).click();
  await page.getByLabel("名称", { exact: true }).fill(name);
  await page.getByLabel("别名", { exact: true }).fill(slug);
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await expect(page.getByRole("dialog")).not.toBeVisible();
  await expect(page.getByRole("cell", { name, exact: true })).toBeVisible();
}
async function token(page: Page) {
  return page.evaluate(() => localStorage.getItem("yufeichi.access-token"));
}
test("网页完整闭环：分类标签、封面、草稿、发布、刷新、深链、下架、删除", async ({
  page,
  context,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await login(page);
  await createTaxonomy(page, "分类", "浏览器验收分类", "browser-category");
  await createTaxonomy(page, "标签", "浏览器验收标签", "browser-tag");
  await page.getByRole("link", { name: /文章管理/ }).click();
  await page.getByRole("button", { name: "新建文章" }).click();
  await page.getByLabel("文章标题").fill("从一个想法，到一篇作品");
  await page
    .getByLabel("摘要", { exact: true })
    .fill("通过真实浏览器完成从草稿到发布的内容闭环。");
  await page
    .getByLabel("正文")
    .fill(
      "# 创作记录\n\n这是通过网页保存的文章。\n\n<script>window.compromised=true</script>",
    );
  await page
    .locator(".el-select")
    .filter({ has: page.locator("#article-category") })
    .click();
  await page
    .getByRole("option", { name: "浏览器验收分类", exact: true })
    .click();
  await page
    .locator(".el-select")
    .filter({ has: page.locator("#article-tags") })
    .click();
  await page
    .getByRole("option", { name: "浏览器验收标签", exact: true })
    .click();
  await page.getByRole("heading", { name: "新建文章" }).click();
  await page
    .getByLabel("选择封面", { exact: true })
    .setInputFiles(
      resolve("../yufeichi-server/src/test/resources/fixtures/sample.webp"),
    );
  await expect(page.getByAltText("文章封面")).toHaveAttribute(
    "src",
    /^\/uploads\/article\/.+\.png$/,
  );
  await expect
    .poll(() =>
      page
        .getByAltText("文章封面")
        .evaluate((img: HTMLImageElement) => img.naturalWidth),
    )
    .toBeGreaterThan(0);
  await page.getByRole("button", { name: "保存草稿", exact: true }).click();
  await expect(page).toHaveURL(/\/admin\/articles\/\d+\/edit$/);
  const detail = page.url(),
    id = detail.match(/articles\/(\d+)/)![1];
  expect((await page.request.get("/api/articles/" + id)).status()).toBe(404);
  await page.getByRole("button", { name: "发布文章", exact: true }).click();
  await page.getByRole("button", { name: "确认发布", exact: true }).click();
  await expect(
    page.getByText("已发布 · 保存修改会立即更新公开内容"),
  ).toBeVisible();
  const publicArticle = await (
    await page.request.get("/api/articles/" + id)
  ).json();
  expect(publicArticle.data.tagIds).toHaveLength(1);
  expect(publicArticle.data.coverUrl).toMatch(/^\/uploads\//);
  await page.reload();
  await expect(page.getByLabel("文章标题")).toHaveValue(
    "从一个想法，到一篇作品",
  );
  const copied = await context.newPage();
  await copied.goto(detail);
  await expect(copied.getByLabel("文章标题")).toHaveValue(
    "从一个想法，到一篇作品",
  );
  await copied.close();
  await page.screenshot({
    path: "test-results/day3-editor.png",
    fullPage: true,
  });
  await page.getByRole("link", { name: "← 文章管理" }).click();
  await expect(
    page.getByRole("link", { name: "从一个想法，到一篇作品" }),
  ).toBeVisible();
  await page.screenshot({
    path: "test-results/day3-articles.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "下架", exact: true }).click();
  await page.getByRole("button", { name: "确认下架", exact: true }).click();
  await expect(
    page.getByRole("cell", { name: "已下架", exact: true }),
  ).toBeVisible();
  expect((await page.request.get("/api/articles/" + id)).status()).toBe(404);
  await page.getByRole("button", { name: "删除", exact: true }).click();
  await page.getByRole("button", { name: "取消", exact: true }).click();
  await expect(
    page.getByRole("link", { name: "从一个想法，到一篇作品" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "删除", exact: true }).click();
  await page.getByRole("button", { name: "确认删除", exact: true }).click();
  await expect(page.getByText("这里还没有内容")).toBeVisible();
  expect(errors).toEqual([]);
});
test("错误密码与匿名深链不会形成 401 循环", async ({ page }) => {
  await page.goto("/admin/articles/999/edit");
  await expect(page).toHaveURL(/\/login\?redirect=/);
  await page.getByLabel("用户名", { exact: true }).fill("admin");
  await page.getByLabel("密码", { exact: true }).fill("wrong-password");
  await page.getByRole("button", { name: "登录工作台" }).click();
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(page).toHaveURL(/\/login\?redirect=/);
  expect(await token(page)).toBeNull();
});
test("真实过期 Token 由服务端拒绝并可重新登录", async ({ page }) => {
  const header = Buffer.from(
    JSON.stringify({ alg: "HS256", typ: "JWT" }),
  ).toString("base64url");
  const body = Buffer.from(
    JSON.stringify({ sub: "1", exp: Math.floor(Date.now() / 1000) - 60 }),
  ).toString("base64url");
  const unsigned = header + "." + body;
  const expired =
    unsigned +
    "." +
    createHmac(
      "sha256",
      "day1-test-only-secret-never-use-in-production-0123456789",
    )
      .update(unsigned)
      .digest("base64url");
  await page.goto("/login");
  await page.evaluate(
    (value) => localStorage.setItem("yufeichi.access-token", value),
    expired,
  );
  const failed = page.waitForResponse((r) => r.url().endsWith("/api/auth/me"));
  await page.goto("/admin/articles");
  expect((await failed).status()).toBe(401);
  await expect(page).toHaveURL(/\/login/);
  expect(await token(page)).toBeNull();
  await login(page);
});
test("只读账号刷新后没有写按钮，越权路由 403、真实写接口 403", async ({
  page,
}) => {
  await login(page, "day3-reader");
  await page.reload();
  await expect(page.getByRole("heading", { name: /文章管理/ })).toBeVisible();
  await expect(page.getByRole("button", { name: "新建文章" })).toHaveCount(0);
  const result = await page.request.post("/api/admin/articles", {
    headers: { Authorization: "Bearer " + (await token(page)) },
    data: { title: "forbidden", content: "forbidden" },
  });
  expect(result.status()).toBe(403);
  await page.goto("/admin/articles/new");
  await expect(
    page.getByRole("heading", { name: "没有访问权限" }),
  ).toBeVisible();
  expect(await token(page)).toBeTruthy();
});
test("保存网络失败保留正文，上传失败可重试，业务冲突保留对话框", async ({
  page,
}) => {
  await login(page);
  await page.getByRole("button", { name: "新建文章" }).click();
  await page.getByLabel("文章标题").fill("失败后保留");
  await page.getByLabel("正文").fill("不能丢失的草稿");
  await page.route("**/api/admin/articles", (route) =>
    route.abort("connectionfailed"),
  );
  await page.getByRole("button", { name: "保存草稿", exact: true }).click();
  await expect(page.getByText("无法连接服务，请检查网络后重试")).toBeVisible();
  await expect(page.getByLabel("正文")).toHaveValue("不能丢失的草稿");
  await page.unroute("**/api/admin/articles");
  await page.route("**/api/admin/files/upload", (route) =>
    route.abort("connectionfailed"),
  );
  await page
    .getByLabel("选择封面", { exact: true })
    .setInputFiles(
      resolve("../yufeichi-server/src/test/resources/fixtures/sample.webp"),
    );
  await expect(page.getByRole("button", { name: "重试上传" })).toBeVisible();
  await page.unroute("**/api/admin/files/upload");
  await page.getByRole("button", { name: "重试上传" }).click();
  await expect(page.getByAltText("文章封面")).toBeVisible();
  await page.getByRole("button", { name: "保存草稿", exact: true }).click();
  await expect(page).toHaveURL(/articles\/\d+\/edit$/);
  await createTaxonomy(page, "分类", "冲突检查", "conflict-check");
  await page.getByRole("button", { name: "新建分类" }).click();
  await page.getByLabel("名称", { exact: true }).fill("冲突检查");
  await page.getByLabel("别名", { exact: true }).fill("conflict-check");
  const response = page.waitForResponse(
    (r) =>
      r.request().method() === "POST" && r.url().endsWith("/admin/categories"),
  );
  await page.getByRole("button", { name: "保存", exact: true }).click();
  expect((await response).status()).toBe(409);
  await expect(page.getByRole("dialog")).toBeVisible();
  await expect(page.getByLabel("名称", { exact: true })).toHaveValue(
    "冲突检查",
  );
});
test("恢复登录遇到网络故障保留 Token 并可重试；退出移除 Token", async ({
  page,
}) => {
  await login(page);
  await page.route("**/api/auth/me", (route) =>
    route.abort("connectionfailed"),
  );
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "暂时无法恢复登录" }),
  ).toBeVisible();
  expect(await token(page)).toBeTruthy();
  await page.unroute("**/api/auth/me");
  await page.getByRole("link", { name: "重新连接" }).click();
  await expect(page).toHaveURL(/\/admin\/articles$/);
  await page.getByRole("button", { name: "退出登录" }).click();
  await expect(page).toHaveURL(/\/login$/);
  expect(await token(page)).toBeNull();
});

test("编辑中认证失效重新登录后恢复未保存内容", async ({ page }) => {
  await login(page);
  await page.getByRole("button", { name: "新建文章" }).click();
  await page.getByLabel("文章标题").fill("认证过期时的标题");
  await page.getByLabel("正文").fill("重新登录后必须保留这段内容");
  await page.evaluate(() =>
    localStorage.setItem("yufeichi.access-token", "invalid-token"),
  );
  await page.getByRole("button", { name: "保存草稿", exact: true }).click();
  await expect(page).toHaveURL(/\/login.*expired=1/);
  await page.getByLabel("用户名", { exact: true }).fill("admin");
  await page.getByLabel("密码", { exact: true }).fill("Admin@123456");
  await page.getByRole("button", { name: "登录工作台" }).click();
  await expect(page).toHaveURL(/\/admin\/articles\/new$/);
  await expect(page.getByLabel("正文")).toHaveValue(
    "重新登录后必须保留这段内容",
  );
  await expect(page.getByLabel("文章标题")).toHaveValue("认证过期时的标题");
});

test("网页分页、筛选、修改文章与分类标签维护", async ({ page }) => {
  test.setTimeout(90000);
  await login(page);
  for (let index = 1; index <= 11; index++) {
    await page.getByRole("button", { name: "新建文章" }).click();
    await page.getByLabel("文章标题").fill("分页文章 " + index);
    await page.getByLabel("正文").fill("分页内容 " + index);
    await page.getByRole("button", { name: "保存草稿", exact: true }).click();
    await expect(page).toHaveURL(/articles\/\d+\/edit$/);
    await page.getByRole("link", { name: "← 文章管理" }).click();
    await expect(
      page.getByRole("link", { name: "分页文章 " + index, exact: true }),
    ).toBeVisible();
  }
  await page.getByLabel("搜索文章").fill("分页文章");
  await page.getByRole("button", { name: "搜索", exact: true }).click();
  await expect(page.getByText("共 11 篇文章")).toBeVisible();
  await expect(page.locator(".el-table__body tbody tr")).toHaveCount(10);
  await page.locator(".el-pager").getByText("2", { exact: true }).click();
  await expect(page.locator(".el-table__body tbody tr")).toHaveCount(1);
  await page.getByRole("link", { name: "分页文章 1", exact: true }).click();
  await page.getByLabel("文章标题").fill("修改后的文章");
  const updated = page.waitForResponse(
    (response) =>
      response.request().method() === "PUT" &&
      /\/api\/admin\/articles\/\d+$/.test(response.url()),
  );
  await page.getByRole("button", { name: "保存草稿", exact: true }).click();
  expect((await updated).status()).toBe(200);
  await expect(page.getByText(/内容已同步/)).toBeVisible();
  await page.reload();
  await expect(page.getByLabel("文章标题")).toHaveValue("修改后的文章");
  await page.getByRole("link", { name: "← 文章管理" }).click();
  await page
    .locator(".el-radio-button")
    .filter({ hasText: /^已发布$/ })
    .click();
  await expect(page.getByText("这里还没有内容")).toBeVisible();
  await page
    .locator(".el-radio-button")
    .filter({ hasText: /^全部$/ })
    .click();
  await expect(page.locator(".el-table__body tbody tr")).toHaveCount(10);
  await page.getByLabel("搜索文章").fill("修改后的文章");
  await page.getByRole("button", { name: "搜索", exact: true }).click();
  await expect(
    page.getByRole("link", { name: "修改后的文章", exact: true }),
  ).toBeVisible();
  for (const kind of ["分类", "标签"] as const) {
    await createTaxonomy(
      page,
      kind,
      "待修改" + kind,
      kind === "分类" ? "edit-category" : "edit-tag",
    );
    let row = page.getByRole("row").filter({
      has: page.getByRole("cell", { name: "待修改" + kind, exact: true }),
    });
    await row.getByRole("button", { name: "编辑", exact: true }).click();
    await page.getByLabel("名称", { exact: true }).fill("已修改" + kind);
    await page.getByRole("button", { name: "保存", exact: true }).click();
    await expect(page.getByRole("dialog")).not.toBeVisible();
    row = page.getByRole("row").filter({
      has: page.getByRole("cell", { name: "已修改" + kind, exact: true }),
    });
    await row.getByRole("button", { name: "删除", exact: true }).click();
    await page.getByRole("button", { name: "确认删除", exact: true }).click();
    await expect(row).toHaveCount(0);
  }
});
