# Day3 后台闭环执行与验收报告

日期：2026-09-27。执行依据：桌面原版《Yufeichi-Platform-V1.0-审查与7天冲刺计划.md》的 Day3 Checklist。

## 范围与计划调整

- 本轮先将 Day2 两次提交正常推送至 GitHub 的 dev 分支：d9f9369、ff9bfd8。GitHub API 已核对远程头为 ff9bfd89fa86363102dfa7263b9c38d86591c5d1。未强制推送、未改远程历史。
- Day3 实现文章后台纵向闭环。未开发项目管理网页、完整公开内容页面、Markdown HTML 渲染、登录限流或服务端退出撤销。
- 真实代码的 /me 没有 permissions，与 Day3 “刷新后恢复权限”存在前置依赖冲突。因此仅提前补充 UserInfoVO.permissions、AuthServiceImpl 映射及回归断言。Day5 的其他安全任务不变。
- 原计划正文未改动，SHA256：1EAE7CADEAE7BD914DF110092F5D92FB710142BCE7D823C7BB0BE5CF446956E0。
- V1—V9 未修改。保留已有 .vscode、HelloWorld 和模板素材；模板清理按 Day4 处理。

## 实现清单

| Checklist | 实现与验证 |
|---|---|
| 工程入口 | 注册 Pinia、Router、Element Plus 与中文语言包；App 使用 RouterView |
| 代理和别名 | Vite /api、/uploads 原样代理后端；@ 与 TS paths 一致 |
| 类型与请求 | Result、PageResult、业务 DTO/VO；Axios 超时、Bearer、业务码、401/403、网络错误；业务代码无显式 any |
| 用户状态 | 登录、退出、去重 /me 恢复、hasPermission；权限取服务器响应 |
| 守卫 | 登录页可独立访问；后台等待恢复；过期回登录；恢复网络失败可重试且不删除 Token |
| 双布局 | FrontLayout 提供入口/登录/错误页；AdminLayout 仅显示已实现且有权限的菜单 |
| 文章 | 分页、关键词/状态筛选、详情编辑、保存草稿、发布、下架、删除确认 |
| 分类/标签 | 列表、新增/修改对话框、删除；服务端 409 明确展示且保留输入 |
| 图片 | 文件选择、上传进度、等待校验、失败重试、相对 fileUrl 回填与实际图片读取 |
| 失败保护 | Loading、防重复提交；失败保留表单；401 时内存暂存编辑内容，同账号重新登录恢复 |
| 网页验收 | 登录 → 创建分类/标签 → 创建文章 → 上传封面 → 草稿 → 发布，全程真实 API |
| 深层路由/权限 | 刷新、复制编辑 URL、新标签页、过期 Token、只读权限、真实 API 403 |
| Git | 完成验收后按计划生成本地 feat(admin) 提交；本轮推送范围为已有 Day2 提交 |

## 修改文件与作用

- yufeichi-web/src/main.ts、App.vue：应用启动与统一认证失效处理。
- yufeichi-web/vite.config.ts、tsconfig.app.json、index.html：代理、别名、中文入口和标题。
- yufeichi-web/src/types/api.ts：接口返回与业务字段类型。
- yufeichi-web/src/api/http.ts、content.ts：请求处理和内容 API。
- yufeichi-web/src/stores/user.ts、editorDraft.ts：用户恢复与当前标签页的编辑中断保护。
- yufeichi-web/src/router/index.ts：静态路由、权限守卫、安全内部回跳。
- yufeichi-web/src/layouts/FrontLayout.vue、AdminLayout.vue：前台入口与后台布局。
- yufeichi-web/src/views/HomeView.vue、LoginView.vue、StatusView.vue：入口、登录、403/404/恢复失败。
- yufeichi-web/src/views/ArticleList.vue、ArticleEditor.vue、TaxonomyView.vue：文章及分类标签业务页面。
- yufeichi-web/src/components/PageState.vue、UploadImage.vue：加载/空态/错误与封面上传。
- yufeichi-web/src/style.css：内容工作台样式与小屏基本布局。
- yufeichi-web/package.json、pnpm-lock.yaml：添加锁定的 Playwright 1.63.0 与 test:e2e 命令；未升级原有依赖。
- yufeichi-web/playwright.config.ts、tests/admin.spec.ts：真实浏览器验收与故障场景。
- scripts/test-web.mjs：一次性测试容器、Java 21 后端、Vite、浏览器和回收流程。
- yufeichi-web/.gitignore：排除截图、浏览器报告和测试服务日志。
- yufeichi-server 的 UserInfoVO.java、AuthServiceImpl.java、YufeichiServerApplicationTests.java：/me 权限字段及断言。
- README.md、本文：准确记录已实现、运行命令和未实现边界。

## 问题原因与处理

1. 原前端只渲染 HelloWorld，已安装依赖没有形成业务应用：接入入口、路由、状态、布局、实际业务页面。
2. /me 缺少权限，刷新后不能重新获取按钮/路由权限：补充服务器权限字段；不信任客户端持久化的权限列表。
3. 登录失败和后台过期都可能返回 401：登录请求不触发全局认证失效回跳；后台失效仅处理当前 Token，避免循环跳转及旧响应误清新会话。
4. 服务故障不等于 Token 失效：只有 401 清会话；网络恢复失败转到可重试页面，保留 Token。
5. 上传进度达到 100% 不代表服务器已经解码验证成功：继续显示校验状态，收到成功响应后才回填 fileUrl；失败可重试同一个文件。
6. 编辑失败/登录重定向会丢失用户输入：普通失败保留表单，失效跳转前按账号与路由在内存暂存；主动退出清理。
7. 首轮浏览器测试点击了 Element Plus 被占位层遮挡的内部 input：改为点击实际选择框容器。分页测试中多个短暂成功提示导致严格定位冲突：改为等待实际 PUT 200 和同步状态，避免依赖浮动通知。分页末项改为按标题搜索已修改文章，纠正“更新旧文章会排到第一页”的测试假设；后端固定排序保持不变。
8. 第一次测试环境日志放在 Playwright 输出目录中会被运行器清理：改为独立、Git 忽略的 .e2e-logs。

## 实际命令

在仓库根目录（代理 settings 仅在本机临时目录，不入仓库）：

~~~powershell
git status --short
git branch --show-current
git remote -v
git push origin HEAD:dev
.\scripts\mvn21.ps1 -version
$env:TESTCONTAINERS_RYUK_DISABLED='true'
$env:TESTCONTAINERS_CHECKS_DISABLE='true'
.\scripts\mvn21.ps1 -B -s "$env:TEMP\yufeichi-day1-maven-settings.xml" clean verify
pnpm --dir yufeichi-web add -D -E @playwright/test@1.63.0
pnpm --dir yufeichi-web exec playwright install chromium
pnpm --dir yufeichi-web build
pnpm --dir yufeichi-web exec tsc --ignoreConfig --noEmit --types node --module nodenext --target es2023 --skipLibCheck tests/admin.spec.ts playwright.config.ts
node scripts/test-web.mjs
git diff --check
~~~

Git 推送使用已有 SSH 身份，通过 ssh.github.com:443 的临时 GIT_SSH_COMMAND 连接；第一次超时后重试成功，未修改持久 SSH 配置。Maven 测试临时禁用 Ryuk 的原因沿用 Day1/Day2 记录的镜像拉取 TLS EOF，未使用 skipTests。

## 验收结果

- Maven 实际运行 Java 21.0.11；clean verify：79 测试，0 失败、0 错误、0 跳过。
- 前端 vue-tsc 与 Vite build 通过；Playwright 测试代码单独 TypeScript 检查通过。
- 浏览器最终结果：8 passed（53.2 秒），0 失败。覆盖完整发布闭环、匿名/错误密码、真实过期 Token、刷新只读权限与真实 403、保存/上传故障恢复与 409、会话恢复网络故障与退出、编辑时认证失效后的正文恢复、11 篇文章的分页/筛选/修改与分类标签修改删除。
- 主流程真实 API；仅网络故障负例使用浏览器请求中断模拟。过期 Token 由 test profile 的测试密钥正确签名并实际过期，服务端真实返回 401。
- 独立数据库 day3_test，随机回环端口与一次性凭据；测试上传使用系统临时目录。未写入开发库、生产库或开发上传目录。
- 浏览器产物：yufeichi-web/playwright-report/index.html；test-results/day3-editor.png、day3-articles.png。日志 .e2e-logs/backend.log、vite.log。均为本地产物，不提交。
- 原计划及迁移保持不变；未执行 Flyway clean/repair。

## 边界

- 本轮未部署服务器。生产深层路由和代理配置需要 Day6 落地；README 已说明要求。
- 当前正文预览是 Vue 转义的纯文本，不运行 HTML。公开 Markdown 页面与净化留在 Day4。
- 主动退出清除本地 Token；服务端撤销与限流仍按 Day5 实现，不能声称旧 Token 已被服务端撤销。
- 未保存编辑暂存仅存在当前标签页内存；关闭或刷新标签页不保留，请先保存草稿。
- Element Plus 全量注册产生约 766.5 kB 主 JS chunk（gzip 约 242.8 kB），Vite 给出大于 500 kB 的体积提示；构建成功，后续可按需加载优化，不用调高阈值隐藏警告。

## 完成结论

Day3 达到原计划完成标准，无未完成的 Day3 必须项。Git diff 涉及 33 个文件，主要为前端工程、后台页面、隔离浏览器验收和文档；后端只增加权限返回及其断言。所有验收通过后生成本地提交，Day3 尚未推送。没有将服务器密码、私钥、本地环境配置、.vscode 或测试产物纳入提交。
