import {
  test,
  expect,
  type Page,
  type APIRequestContext,
} from "@playwright/test";
import { resolve } from "node:path";
import { readFileSync } from "node:fs";
test.beforeAll(() => {
  if (process.env.E2E_ISOLATED !== "1")
    throw new Error("Use the isolated test-web.mjs runner");
});
async function login(page: Page) {
  await page.goto("/login");
  await page.getByLabel("用户名", { exact: true }).fill("admin");
  await page.getByLabel("密码", { exact: true }).fill("Admin@123456");
  await page.getByRole("button", { name: "登录工作台" }).click();
  await expect(page).toHaveURL(/admin\/articles$/);
}
async function api(request: APIRequestContext) {
  const response = await request.post("/api/auth/login", {
    data: { username: "admin", password: "Admin@123456" },
  });
  expect(response.ok()).toBeTruthy();
  const login = await response.json();
  return async (path: string, data: unknown, method = "POST") => {
    const result = await request.fetch("/api" + path, {
      method,
      data,
      headers: { Authorization: "Bearer " + login.data.token },
    });
    expect(result.ok()).toBeTruthy();
    return (await result.json()).data;
  };
}

test("项目网页 CRUD、封面、排序、公开详情、展示隐藏和安全外链", async ({
  page,
  context,
}) => {
  test.setTimeout(90000);
  await login(page);
  await page.getByRole("link", { name: /项目管理/ }).click();
  async function create(name: string, sort: number) {
    await page.getByRole("button", { name: "新建项目", exact: true }).click();
    await page.getByLabel("项目名称", { exact: true }).fill(name);
    await page
      .getByLabel("项目描述", { exact: true })
      .fill("## 项目介绍\n\n这是通过管理网页创建的项目。");
    await page
      .getByLabel("技术栈", { exact: true })
      .fill("Spring Boot 3 · Vue 3");
    await page
      .getByLabel("GitHub 地址", { exact: true })
      .fill("https://github.com/yufeichi1/yufeichi-platform");
    await page
      .getByLabel("Demo 地址", { exact: true })
      .fill("https://example.com/demo");
    await page.getByRole("spinbutton", { name: "项目排序" }).fill(String(sort));
    await page
      .getByLabel("选择封面", { exact: true })
      .setInputFiles(
        resolve("../yufeichi-server/src/test/resources/fixtures/sample.webp"),
      );
    await expect(page.getByAltText("项目封面")).toHaveAttribute(
      "src",
      /^\/uploads\/project\//,
    );
    const saved = page.waitForResponse(
      (r) =>
        r.request().method() === "POST" &&
        r.url().endsWith("/api/admin/projects"),
    );
    await page.getByRole("button", { name: "保存项目", exact: true }).click();
    const data = (await (await saved).json()).data;
    await expect(page.getByRole("dialog")).not.toBeVisible();
    return data.id as number;
  }
  const first = await create("Day4 项目作品", 20),
    second = await create("Day4 排序优先", 1);
  const visitor = await context.newPage();
  await visitor.goto("/projects/" + first);
  await expect(
    visitor.getByRole("heading", { name: "内容不存在" }),
  ).toBeVisible();
  for (const name of ["Day4 项目作品", "Day4 排序优先"]) {
    const row = page
      .getByRole("row")
      .filter({ has: page.getByRole("cell", { name, exact: true }) });
    await row.getByRole("button", { name: "展示", exact: true }).click();
    await page.getByRole("button", { name: "确认展示", exact: true }).click();
    await expect(
      row.getByRole("cell", { name: "展示中", exact: true }),
    ).toBeVisible();
  }
  await visitor.goto("/projects");
  await expect(visitor.locator(".public-card h2").first()).toHaveText(
    "Day4 排序优先",
  );
  await visitor
    .getByRole("heading", { name: "Day4 项目作品", exact: true })
    .getByRole("link")
    .click();
  await expect(
    visitor.getByRole("heading", { name: "项目介绍" }),
  ).toBeVisible();
  await expect(
    visitor.getByRole("link", { name: "GitHub ↗", exact: true }),
  ).toHaveAttribute("href", "https://github.com/yufeichi1/yufeichi-platform");
  await expect(
    visitor.getByRole("link", { name: "在线演示 ↗" }),
  ).toHaveAttribute("rel", "noopener noreferrer");
  await visitor.reload();
  await expect(
    visitor.getByRole("heading", { name: "Day4 项目作品" }),
  ).toBeVisible();
  let row = page.getByRole("row").filter({
    has: page.getByRole("cell", { name: "Day4 项目作品", exact: true }),
  });
  await row.getByRole("button", { name: "编辑", exact: true }).click();
  await page.getByLabel("项目名称", { exact: true }).fill("Day4 修改后的项目");
  await page.getByRole("button", { name: "保存项目", exact: true }).click();
  await expect(page.getByRole("dialog")).not.toBeVisible();
  await visitor.reload();
  await expect(
    visitor.getByRole("heading", { name: "Day4 修改后的项目" }),
  ).toBeVisible();
  row = page.getByRole("row").filter({
    has: page.getByRole("cell", { name: "Day4 修改后的项目", exact: true }),
  });
  await row.getByRole("button", { name: "隐藏", exact: true }).click();
  await page.getByRole("button", { name: "确认隐藏", exact: true }).click();
  await expect(
    row.getByRole("cell", { name: "已隐藏", exact: true }),
  ).toBeVisible();
  await visitor.reload();
  await expect(
    visitor.getByRole("heading", { name: "内容不存在" }),
  ).toBeVisible();
  await row.getByRole("button", { name: "删除", exact: true }).click();
  await page.getByRole("button", { name: "确认删除", exact: true }).click();
  await expect(row).toHaveCount(0);
  expect((await visitor.request.get("/api/projects/" + first)).status()).toBe(
    404,
  );
  await visitor.goto("/projects/" + second);
  await expect(
    visitor.getByRole("heading", { name: "Day4 排序优先" }),
  ).toBeVisible();
  await visitor.close();
  await page.setViewportSize({ width: 360, height: 800 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "test-results/day4-mobile-admin.png",
    fullPage: true,
    animations: "disabled",
  });
});

test("公开文章：筛选 URL、分页、详情刷新、Markdown 安全、360px与首页", async ({
  page,
  request,
}) => {
  test.setTimeout(90000);
  const call = await api(request);
  const loginResponse = await request.post("/api/auth/login", {
    data: { username: "admin", password: "Admin@123456" },
  });
  const testToken = (await loginResponse.json()).data.token;
  await page.addInitScript(
    (value) => localStorage.setItem("yufeichi.access-token", value),
    testToken,
  );
  const uploadResponse = await request.post("/api/admin/files/upload", {
    headers: { Authorization: "Bearer " + testToken },
    multipart: {
      bizType: "article",
      file: {
        name: "cover.webp",
        mimeType: "image/webp",
        buffer: readFileSync(
          resolve("../yufeichi-server/src/test/resources/fixtures/sample.webp"),
        ),
      },
    },
  });
  expect(uploadResponse.ok()).toBeTruthy();
  const coverUrl = (await uploadResponse.json()).data.fileUrl as string;
  const category = await call("/admin/categories", {
    name: "Day4 开发记录",
    slug: "day4-development",
  });
  const tag = await call("/admin/tags", {
    name: "Day4 标签",
    slug: "day4-tag",
  });
  const content = [
    `![合法图片](${coverUrl})`,
    "## 安全正文",
    "普通 **加粗** 与 [安全链接](https://example.com/read)。",
    '<script>window.day4Xss=1</script><img src=x onerror="window.day4Xss=1"><svg onload="window.day4Xss=1"></svg>',
    "[脚本](javascript:alert(1)) [数据](data:text/html,evil) [协议相对](//example.com) [凭据](https://user:pass@example.com)",
    "![远程图片](https://example.com/tracker.png) ![SVG](data:image/svg+xml;base64,PHN2Zz4=) ![穿越](/uploads/article/../../api/auth/me)",
    "~~~js",
    'const longLine = "' + "x".repeat(250) + '";',
    "~~~",
  ].join("\n\n");
  let id = 0;
  for (let index = 1; index <= 10; index++) {
    const article = await call("/admin/articles", {
      title: "Day4 公开文章 " + index,
      content,
      summary: "公开文章的测试摘要",
      coverUrl,
      categoryId: category.id,
      tagIds: [tag.id],
    });
    await call("/admin/articles/" + article.id + "/publish", {});
    id = article.id;
  }
  const draft = await call("/admin/articles", {
    title: "Day4 未公开草稿",
    content: "不能公开",
  });
  await page.goto("/articles");
  await page.getByLabel("筛选分类").selectOption(String(category.id));
  await page.getByLabel("筛选标签").selectOption(String(tag.id));
  await expect(page).toHaveURL(
    new RegExp("category=" + category.id + "&tag=" + tag.id),
  );
  await expect(page.locator(".article-cards .public-card")).toHaveCount(9);
  await page.locator(".el-pager").getByText("2", { exact: true }).click();
  await expect(page).toHaveURL(/page=2/);
  await expect(page.locator(".article-cards .public-card")).toHaveCount(1);
  await page.reload();
  await expect(page.getByLabel("筛选分类")).toHaveValue(String(category.id));
  await expect(page.locator(".article-cards .public-card")).toHaveCount(1);
  await page.getByLabel("筛选分类").selectOption("");
  await expect(page).not.toHaveURL(/category=/);
  await expect(page).not.toHaveURL(/page=2/);
  await page.getByLabel("筛选标签").selectOption("");
  await expect(page).toHaveURL(/\/articles$/);
  await page.goBack();
  await expect(page.getByLabel("筛选标签")).toHaveValue(String(tag.id));
  const failures: string[] = [];
  page.on("pageerror", (e) => failures.push(e.message));
  const remoteImages: string[] = [];
  const publicAuthHeaders: string[] = [];
  page.on("request", (r) => {
    if (
      /\/api\/(articles|projects|categories|tags)(?:[/?]|$)/.test(r.url()) &&
      r.headers().authorization
    )
      publicAuthHeaders.push(r.url());
    if (
      r.resourceType() === "image" &&
      r.url().startsWith("https://example.com")
    )
      remoteImages.push(r.url());
  });
  await page.goto("/articles/" + id);
  await expect(page.getByRole("heading", { name: "安全正文" })).toBeVisible();
  await expect(page.locator(".markdown-content strong")).toHaveText("加粗");
  await expect(
    page.locator(
      ".markdown-content script,.markdown-content svg,.markdown-content iframe",
    ),
  ).toHaveCount(0);
  await expect(page.locator(".markdown-content img")).toHaveCount(1);
  await expect(page.getByAltText("合法图片")).toHaveAttribute("src", coverUrl);
  await expect
    .poll(() =>
      page
        .getByAltText("合法图片")
        .evaluate((img: HTMLImageElement) => img.naturalWidth),
    )
    .toBeGreaterThan(0);
  await expect(
    page.locator(
      '.markdown-content a[href^="javascript:"],.markdown-content a[href^="data:"],.markdown-content a[href^="//"]',
    ),
  ).toHaveCount(0);
  await expect(page.getByRole("link", { name: "安全链接" })).toHaveAttribute(
    "rel",
    "noopener noreferrer",
  );
  expect(await page.evaluate(() => Object.hasOwn(window, "day4Xss"))).toBe(
    false,
  );
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Day4 公开文章 10", exact: true }),
  ).toBeVisible();
  await page.setViewportSize({ width: 360, height: 800 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  expect(
    await page
      .locator(".markdown-content pre")
      .evaluate((el) => el.scrollWidth > el.clientWidth),
  ).toBe(true);
  await page.screenshot({
    path: "test-results/day4-mobile-article.png",
    fullPage: true,
    animations: "disabled",
  });
  await page.goto("/articles/" + draft.id);
  await expect(page.getByRole("heading", { name: "内容不存在" })).toBeVisible();
  await expect(page.getByText("安全正文", { exact: true })).toHaveCount(0);
  await page.goto("/articles/not-a-number");
  await expect(page.getByRole("heading", { name: "内容不存在" })).toBeVisible();
  await page.goto("/articles?page=wrong&category=-1");
  await expect(page).toHaveURL(/\/articles$/);
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "最新文章" })).toBeVisible();
  await expect(page.locator(".article-cards .public-card")).toHaveCount(3);
  await expect(page.locator(".project-cards .public-card")).toHaveCount(1);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.screenshot({
    path: "test-results/day4-home.png",
    fullPage: true,
    animations: "disabled",
  });
  await page
    .getByRole("navigation", { name: "主导航" })
    .getByRole("link", { name: "关于", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "你好，我是 Yufeichi。" }),
  ).toBeVisible();
  expect(remoteImages).toEqual([]);
  expect(publicAuthHeaders).toEqual([]);
  expect(failures).toEqual([]);
});

test("公开故障重试、空态与未知页面；后台项目无权限访问", async ({ page }) => {
  await page.route("**/api/projects?*", (r) =>
    r.fulfill({
      status: 500,
      contentType: "application/json",
      body: JSON.stringify({
        code: 500,
        message: "测试服务暂不可用",
        data: null,
      }),
    }),
  );
  await page.goto("/projects");
  await expect(page.getByRole("alert")).toHaveText("测试服务暂不可用");
  await page.unroute("**/api/projects?*");
  await page.getByRole("button", { name: "重新加载" }).click();
  await expect(page.locator(".project-cards .public-card")).toHaveCount(1);
  await page.goto("/articles?category=2147483647");
  await expect(
    page.getByRole("heading", { name: "暂无匹配的文章" }),
  ).toBeVisible();
  await page.goto("/unknown-page");
  await expect(page.getByRole("heading", { name: "页面不存在" })).toBeVisible();
  await page.getByRole("link", { name: "返回首页", exact: true }).click();
  await expect(page).toHaveURL(/\/$/);
  await page.goto("/login");
  await page.getByLabel("用户名", { exact: true }).fill("day3-reader");
  await page.getByLabel("密码", { exact: true }).fill("Admin@123456");
  await page.getByRole("button", { name: "登录工作台" }).click();
  await expect(page).toHaveURL(/admin\/articles$/);
  await expect(page.getByRole("link", { name: /项目管理/ })).toHaveCount(0);
  await page.goto("/admin/projects");
  await expect(
    page.getByRole("heading", { name: "没有访问权限" }),
  ).toBeVisible();
});
