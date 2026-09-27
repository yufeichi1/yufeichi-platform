# Day4 前台与项目管理执行报告

执行日期：2026-09-27。唯一功能范围依据为桌面原版《Yufeichi-Platform-V1.0-审查与7天冲刺计划.md》的 Day4 Checklist。

## Git 与范围

- 先完成 Day3 推送：dev 远程从 ff9bfd8 前进到 efea7e7a11d7c318fcd37991117094d87c55b68e，普通 fast-forward，未强制推送。
- 本轮实现 Day4，复用已有项目/文章/分类/标签/上传 API，未修改后端代码、数据库结构或 V1—V9。
- 原计划未修改，SHA256 仍为 1EAE7CADEAE7BD914DF110092F5D92FB710142BCE7D823C7BB0BE5CF446956E0。
- 保留原有 .vscode，未提交本地环境配置、真实凭据、私钥或测试日志。没有操作服务器。
- Day5 的限流、服务端退出撤销、生产配置，以及 Day6 的实际部署仍未开发。

## Checklist 对照

| 任务 | 实现与验收 |
|---|---|
| ProjectManage | 新增、修改、删除、排序、展示/隐藏、封面上传；网页操作后公开页面状态同步 |
| 首页 | 固定公开身份介绍、实际文章与项目卡片，无虚构统计；各区独立加载/空态/失败重试 |
| 文章列表 | 分页、分类和标签选择，URL 是状态来源；切换筛选回第一页、刷新/后退恢复、无效参数规范化 |
| 文章详情 | 标题、发布时间、分类、标签、封面、正文；草稿与未公开 ID 返回内容不存在 |
| Markdown | html:false；输出经 DOMPurify 标签/属性白名单净化；协议白名单与站内图片限制 |
| 项目公开页 | 列表、详情、排序、技术栈、GitHub/Demo 安全外链；隐藏或删除后 404 |
| About | 固定公开介绍，不编造个人履历，不增加系统配置后台 |
| 错误状态 | 403、未知路由 404、内容 404、500 重试、空态；请求开始清旧数据，忽略过时响应 |
| 360px | 公开导航、卡片、正文、代码块和图片适配；后台新增第四个菜单后允许换行 |
| 模板清理 | 移除 HelloWorld、vue.svg、vite.svg、hero.png、模板 icons.svg/favicon.svg；保留业务代码 |
| 构建与部署说明 | vue-tsc + Vite build；生产 dist 预览深层路由验收；Nginx 路由约定单独成文 |
| Git | 验收后按计划生成本地 feat(web) 提交，本轮推送对象为先前的 Day3 提交 |

## 文件与实现

- src/views/ProjectManage.vue、src/api/projects.ts、src/types/api.ts：项目管理与类型化接口。
- src/components/UploadImage.vue、src/api/content.ts：上传目录支持 article/project；保留原文章行为，项目使用自己的封面提示。
- src/api/public.ts：公开查询不附带管理员 Token。
- src/composables/usePublicResource.ts：公开页面加载/失败/404 和响应先后顺序保护，切换内容时清空旧值。
- src/utils/urls.ts：HTTP/HTTPS 外链、公开站内路径、本站规范上传图片地址校验。
- src/components/MarkdownContent.vue：唯一的 v-html 入口，输入先关闭 HTML 解析并在最终输出前净化。
- src/components/ArticleCards.vue、ProjectCards.vue、SafeImage.vue、PublicState.vue：卡片、图片占位、公共内容状态。
- src/views/front/BlogList.vue、ArticleDetail.vue、ProjectList.vue、ProjectDetail.vue、AboutView.vue：公开页面。
- src/views/HomeView.vue、StatusView.vue、src/layouts/FrontLayout.vue、AdminLayout.vue、src/router/index.ts、src/style.css、index.html：网站导航、布局、错误返回入口、响应式样式与页面标题。
- package.json、pnpm-lock.yaml：锁定 markdown-it 15.0.2、DOMPurify 3.4.16；未升级其他已有依赖。markdown-it 自带类型，无需额外 @types 包。
- tests/front.spec.ts：项目管理与公开流程、URL、分页、可见性、攻击样例、网络故障、小屏断言。
- scripts/test-web.mjs：新增 E2E_PREVIEW=1 支持，使用构建 dist 运行原有隔离验收。
- README.md、deploy/Day4-前端构建与Nginx路由说明.md、本文：更新实现范围、运行方式、生产路由条件与证据。

以上 src、tests、package 等路径均相对于 yufeichi-web。

## 关键处理与原因

1. 原上传组件固定 bizType=article，项目上传会进入错误业务目录：增加有限的 article/project 类型参数，默认保持 article。
2. 项目封面原复用文案为文章封面：根据业务类型提供正确替代文本，通用占位提示不再写死文章。
3. 分类/标签筛选清空时，JavaScript 默认参数会把 undefined 解释成旧值：显式使用 null 表示清空，避免看似清空却沿用原条件。
4. 列表 URL 规范化的 watcher 在离开页面时也可能触发：限定对应列表路径，避免把用户导航拉回列表。
5. 异步请求旧响应可能覆盖新页面：统一请求序号与卸载失效处理，加载前置空，失败后不显示旧详情。
6. 数据库保存的 Markdown、外链、图片均不能直接信任：禁用原生 HTML，限制可点击协议及路径，图片仅取受控上传地址，最后统一净化 HTML。
7. 仅桌面布局无法覆盖 360px 后台的四个菜单：小屏侧栏导航允许换行，表格在自己的滚动区域内展示。
8. 不虚构资料：关于页仅使用 Yufeichi 身份与已知的个人全栈项目说明，没有添加职业经历、联系邮箱或统计数字。

## 实际验证

- JDK：沿用固定 Java 21 的 mvn21.ps1 与 Enforcer；本轮 clean verify 为 79 tests，0 failures、0 errors、0 skipped，BUILD SUCCESS。
- 前端：生产构建通过，源码及两份 Playwright 测试代码的 TypeScript 检查通过。
- 开发服务浏览器验收：Day3 + Day4 共 11 passed（约 1.3 分钟）。
- 生产 dist 预览验收：11 passed（53.7 秒），包含新增合法正文图片与管理员 Token 不发送到公开 API 的断言。
- 最后的共享上传组件文案与小屏后台菜单调整：针对文章完整闭环及项目完整闭环在生产预览中定向复验，2 passed（20.0 秒），新增 360px 后台宽度断言通过。
- 浏览器真实业务写流程：项目新增 → project 目录图片上传 → 排序 → 展示 → 公开详情 → 修改 → 隐藏 → 删除；文章 Day3 完整网页闭环亦回归。
- 隔离测试 API 建立 10 篇公开文章和未公开草稿，用于公开分页、筛选与攻击样例；这部分是测试数据准备，不声称全部通过手工网页创建。
- 实际验证草稿/隐藏/删除详情不可读、404 不保留正文、刷新深层 URL、筛选后退/刷新、空态、500 重试、无权限路由。
- 恶意输入包括 script、事件属性、SVG、javascript/data 链接、协议相对地址、带账号密码的外链、远程图片和路径穿越；未执行脚本或发出远程图片请求。
- 合法上传图片可以显示，360px 图片不撑宽页面，长代码块内部横向滚动；浏览器未记录页面脚本错误。
- 测试沿用脚本新建的 day3_test 临时 MySQL 8.4/Redis 7 容器、随机端口/凭据和临时图片目录，没有使用开发或生产数据。

## 执行命令

~~~powershell
git status --short
git branch --show-current
git remote -v
git push origin HEAD:dev
pnpm --dir yufeichi-web add -E markdown-it dompurify
pnpm --dir yufeichi-web build
pnpm --dir yufeichi-web exec tsc --ignoreConfig --noEmit --types node --module nodenext --target es2023 --skipLibCheck tests/admin.spec.ts tests/front.spec.ts playwright.config.ts
node scripts/test-web.mjs
$env:TESTCONTAINERS_RYUK_DISABLED='true'
$env:TESTCONTAINERS_CHECKS_DISABLE='true'
.\scripts\mvn21.ps1 -B -s "$env:TEMP/yufeichi-day1-maven-settings.xml" clean verify
$env:E2E_PREVIEW='1'
node scripts/test-web.mjs
node scripts/test-web.mjs --grep '网页完整闭环|项目网页'
git diff --check
~~~

Git 使用当前用户已配置的 SSH 身份，经 ssh.github.com:443 临时连接设置推送；无新增真实秘密。Maven 的临时代理 settings 与 Ryuk 镜像拉取问题沿用 Day1/Day2 的本机方案，不写入仓库，没有 skipTests。

## 证据与边界

- 完整运行日志保存在系统临时目录：yufeichi-day4-browser.log、yufeichi-day4-preview.log、yufeichi-day4-verify.log、yufeichi-day4-web-build.log。
- 最后定向验证日志：yufeichi-day4-final-targeted.log。
- 首页/360px 文章截图保留在 yufeichi-web/.e2e-logs/day4-home.png、day4-mobile-article.png；小屏后台截图由最后一次运行生成于 test-results/day4-mobile-admin.png。截图含测试数据，不代表线上内容，均不提交 Git。
- 最后运行的 HTML 报告在 yufeichi-web/playwright-report/。Playwright 每次会清理 test-results，所以整套验收以各次日志记录为准。
- Vite 仍提示原有 Element Plus 全量注册带来的约 766.6 kB 主 JS chunk；构建成功，没有调高阈值掩盖提示。
- Markdown 图片只支持本站规范 PNG/JPEG 上传路径，WebP 上传会转成 PNG。已有外部图片不会加载，显示占位或替代文字。
- Vite preview 只验证前端构建产物，不等于生产部署；本轮没有修改线上 Nginx 或发布服务器。
- 生产 SPA HTML 请求可能返回 200 后由客户端显示 404；不存在/未公开的内容 API 仍返回真实 404。SSR/SEO HTTP 状态优化不在 Day4 清单内。

## 完成结论

Day4 达到原计划完成标准，无未完成的 Day4 必须项。测试容器和测试服务已回收，仅原有开发 MySQL/Redis 保留运行。按计划生成本地提交后供审查，Day4 尚未推送；先前 Day3 已成功推送 GitHub。生产部署、服务端 Token 撤销和限流仍分别属于后续 Day6、Day5。
