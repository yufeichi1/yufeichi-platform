# Yufeichi Platform

基于 Spring Boot 3 + Vue 3 的个人博客、项目展示与后台管理项目。当前已完成 **Day1—Day7：认证安全、核心业务、前后台、真实生产部署、备份恢复与兼容回滚**。V1.0 已部署至 `https://yufeichi.com` 并通过本轮核心交付门禁。

## 已实现

- 后端：Spring Boot 3.5.16、Spring Security、JWT、MyBatis-Plus、Flyway V1—V10（V1—V9 未修改）。
- 基础接口：`GET /api/health`、`POST /api/auth/login`、`GET /api/auth/me`、`POST /api/auth/logout`。
- 内容 API：分类/标签维护、文章草稿/发布/下架、项目展示/隐藏；后台写接口均检查对应权限，公开查询过滤非公开内容。
- 图片 API：`POST /api/admin/files/upload`，接收 JPEG/PNG/WebP；解码规范化、UUID 文件名、5MB/像素限制、数据库事务失败清理文件，`/uploads/**` 公开读取。
- 详细端点、请求字段和行为约定见 [Day2 API 说明](docs/api/Day2-核心API与上传.md)。
- 登录查询用户、角色、权限；受保护接口重新检查 Token 和账户。禁用、删除账户及退出后的旧 Token 返回 401；数据库故障返回 500，Redis 认证故障返回 503。公开 GET 阅读忽略可选 Token，不依赖 Redis。
- 统一 `Result`：成功 `code=0`；错误同时返回正确的 HTTP 状态，不再用 HTTP 200 包装所有错误。
- 开发 OpenAPI：`/v3/api-docs`；Knife4j：`/doc.html`；Swagger UI：`/swagger-ui/index.html`。prod 禁用并拒绝文档访问。
- 独立 test profile、真实 MySQL 8.4/Redis 7 容器测试、认证和异常回归测试。
- 后台网页：登录与刷新恢复、权限菜单、文章分页/编辑/草稿/发布/下架/删除、分类标签维护、封面上传与失败重试。
- 项目后台：新增、修改、删除、排序、展示/隐藏、独立 project 目录封面上传。
- 公开网页：首页、文章分页与分类/标签筛选、文章详情、项目列表/详情、固定关于介绍；地址栏同步筛选与页码，支持刷新深层地址。
- Markdown：关闭原生 HTML，DOMPurify 白名单净化；安全外链，站内上传图片，长代码可横向滚动。
- 网页验收：Playwright 通过真实 Vite 代理连接独立 Java 21/MySQL 8.4/Redis 7 环境；支持完整业务闭环、401/403 和失败恢复检查。

登录与 `/me` 均返回基本信息、roles 和 permissions。Token 默认 45 分钟，无 Refresh Token；退出在 Redis 保存 SHA-256 撤销记录至原到期时间。登录失败按归一化账号和可信来源 IP 原子计数（10 分钟内账号 5 次/IP 20 次，超限 429）。退出撤销失败时前端保留会话并允许重试。V10 禁用公开默认管理员，新账号通过一次性离线 bootstrap 初始化；审计记录登录、发布、删除、上传结果，不记录秘密或正文。

## 规划，尚未实现

- V1.0 已完成真实生产部署、正式备份、Windows 异机副本、独立恢复和上一兼容版本回滚演练。
- 留言、评论、完整日志管理等后续能力。

现有数据库表、依赖和 Security 白名单不代表业务功能已实现。前端目前提供公开网站与文章、分类、标签、项目后台；未将用户、角色等未实现的管理页面加入菜单。

## 固定 Java 21

必须使用 **JDK 21** 编译、测试、运行；不使用 Java 24/25。`.java-version` 记录版本，Maven Enforcer 拒绝非 21 的 Maven 运行环境；POM 显式配置 Lombok 和 Spring Boot annotation processors。

Windows 从仓库根目录运行：

```powershell
# 若脚本无法找到本机 JDK 21，设置为自己的安装目录。
$env:YUFEICHI_JAVA_HOME = 'C:\path\to\jdk-21'
.\scripts\mvn21.ps1 -version
.\scripts\mvn21.ps1 clean verify
```

`mvn21.ps1` 依次尝试 `YUFEICHI_JAVA_HOME`、当前用户 `.jdks\temurin-21.0.11`、`JAVA_HOME`，只接受 21；仅为该次命令调整环境变量。已有本机安装可直接执行脚本，不必设置示例路径。IDE Project SDK 和 Maven Runner 也必须选择 JDK 21；仅设置编译目标 `release=21` 无法改变 Maven 实际运行 JDK。

Linux/macOS 将 `JAVA_HOME` 和 `PATH` 指向 JDK 21，然后在 `yufeichi-server` 下运行 `./mvnw -version` 和 `./mvnw clean verify`。不要用跳过测试的打包结果代替验收。

## 本地依赖与环境变量

Docker Desktop/Engine 必须运行。已有容器可用 `docker start yufeichi-mysql yufeichi-redis` 启动。首次初始化时，在 `deploy/.env` 中按 `.env.example` 填写本地秘密，再运行：

```powershell
docker compose -f deploy/docker-compose.yml up -d
```

保留已有数据卷和凭据；修改 `.env` 不会自动修改已有 MySQL 数据卷内的密码。不要删除数据卷或执行 Flyway clean/repair 来掩盖迁移问题。开发 Compose 当前映射 3306/6379 到宿主机所有网卡，生产收紧端口属于 Day6。

**`deploy/.env` 只供 Compose 插值，不会自动注入宿主机 Java 进程。** 后端 dev profile 的变量如下：

| Java 进程变量 | 映射/用途 | dev 默认值 |
|---|---|---|
| `DB_HOST` | MySQL 地址 | `localhost` |
| `DB_PORT` | MySQL 端口 | `3306` |
| `DB_USERNAME` | 与 Compose 的 `MYSQL_USER` 对应 | `yufeichi` |
| `DB_PASSWORD` | 与 Compose 的 `MYSQL_PASSWORD` 对应 | `yufeichi` |
| `REDIS_HOST` | Redis 地址 | `localhost` |
| `REDIS_PORT` | Redis 端口 | `6379` |
| `REDIS_PASSWORD` | 与 Compose 同名变量对应 | `yufeichi` |
| `JWT_SECRET` | 至少 32 个 UTF-8 字节 | 配置内公开的开发值，仅本地使用 |
| `JWT_EXPIRE_MINUTES` | Token 有效分钟数，只允许 30–60 | `45` |
| `SPRING_PROFILES_ACTIVE` | Spring profile | `dev` |
| `UPLOAD_PATH` | 图片持久化目录，Java 进程必须可写 | `D:/yufeichi/uploads`（dev） |

dev 数据库名固定为 `yufeichi`。`MYSQL_ROOT_PASSWORD` 只供 MySQL 容器初始化，后端不得使用 root 账户连接。启动前在当前终端设置与已有容器一致的 `DB_USERNAME`、`DB_PASSWORD`、`REDIS_PASSWORD` 和本地 `JWT_SECRET`，再执行：

```powershell
.\scripts\mvn21.ps1 spring-boot:run
```

默认地址 `http://localhost:8080`。V10 会禁用 V2 的公开 `admin` 种子。首次初始化新账号请按 [生产配置与账号初始化](deploy/Day5-生产配置与账号初始化.md) 的离线流程操作（也适用于自己的本地库）；不要用测试夹具启用真实环境的种子。本轮未连接或修改开发/生产数据库，迁移和新账号只在独立测试库验证。

## 完整后端验收（Day1—Day5）

```powershell
.\scripts\mvn21.ps1 clean verify
```

必须先启动 Docker，并能够取得测试依赖镜像。`YufeichiServerApplicationTests` 显式指定启动类并强制 test profile，启动独立 `mysql:8.4`、`redis:7` 容器和随机宿主机端口，数据库为 `yufeichi_test`。动态属性来自这些容器，不使用开发/生产连接；test 配置没有开发连接回退。容器结束时自动停止，V1—V10 仅在空的测试库正常迁移。历史业务测试只在自己的容器库中重新启用种子；`Day5IntegrationTests` 保持种子禁用，验证真实离线初始化和 prod 启动。

验收包括 Day1 认证和 OpenAPI 回归，以及 Day2 分类/标签冲突、文章与标签事务回滚、公开可见性、项目 CRUD、逐端点权限、图片格式/大小/像素/路径验证、落盘补偿和真实应用重新启动后的图片访问。Day2 使用独立 `day2_test` 数据库和临时上传目录，不写入开发库或开发上传目录。

本次机器访问 Docker Hub 出现 TLS EOF，不能下载 Ryuk 清理辅助镜像。仅本次本地执行临时设置以下进程环境变量，使用已缓存的 MySQL/Redis 镜像；所有业务测试仍执行，JUnit 容器生命周期负责正常结束时回收，执行后已检查没有测试容器遗留：

```powershell
$env:TESTCONTAINERS_RYUK_DISABLED = 'true'
$env:TESTCONTAINERS_CHECKS_DISABLE = 'true'
.\scripts\mvn21.ps1 clean verify
Remove-Item Env:TESTCONTAINERS_RYUK_DISABLED, Env:TESTCONTAINERS_CHECKS_DISABLE
```

网络正常时不需要此临时绕过；禁用 Ryuk 后异常中断可能留下测试容器，应按测试标签核对，不能删除开发容器或卷。Maven 若需代理，使用本机 Maven settings 配置；项目不保存机器代理地址或秘密。

## 构建产物

实际可执行 JAR：

```text
yufeichi-server/target/yufeichi-server-0.0.1-SNAPSHOT.jar
```

`.jar.original` 是重打包前产物。部署脚本不得假定文件名是 `yufeichi-server.jar`。用 **Java 21** 运行可执行 JAR，运行前注入相应 profile 和环境变量；当前默认 dev，不能直接视为生产配置。

## 生产部署与恢复（Day6—Day7 已完成）

已提供 JAR 内的无秘密 `application-prod.yml` 和 [backend.env 模板](deploy/backend.env.example)，显式启用 prod；DB/Redis/JWT 秘密从受保护外置环境文件注入，缺失启动失败，回环监听、关闭文档和 SQL 日志。生产的 JWT 密钥至少 64 字节，DB_NAME 必填，不能使用 root 数据库账号。Compose 与 Java 进程分别注入变量；Day6 已完成非 root systemd、回环端口、MySQL/Redis 持久化、Nginx 与 HTTPS 的真实生产落地。

V1—V9 保持不变，新增 V10 禁用固定种子；上线前必须完成一次性离线 bootstrap，移除初始化凭据，再启动生产服务。具体步骤、环境变量、可信代理和 Redis 持久化边界见 [Day5 生产配置与账号初始化](deploy/Day5-生产配置与账号初始化.md)。未初始化或重新启用种子的生产库不能启动应用。

截至 2026-09-29，`yufeichi.com` 与 `www.yufeichi.com` 已切换至 V1.0：HTTPS 首页和 `/api/health` 均为 200，HTTP 301 跳转 HTTPS；MySQL 3306、Redis 6379、Spring Boot 8080 仅监听回环地址。Spring Boot 由 systemd 非 root 运行，MySQL/Redis 使用生产 Compose；Certbot dry-run 和 Snap 自动续期均已验证。Day7 已生成 DB+uploads 正式备份、Windows 异机副本，并完成独立恢复和上一兼容 release 回滚演练。

详细修改、命令、验证结果及边界见 [Day1 执行与验收报告](docs/Day1-执行与验收报告.md)。

## Day2 使用约定

- 文章始终以草稿创建，`authorId` 取登录用户；修改正文不接受客户端直接设置作者、状态或浏览量。发布/下架需要 `article:publish`。
- PUT 完整替换可编辑字段；清空分类、封面、简介和链接时提交 null；标签传空数组清空关系。分页默认1/10，上限100；排序固定，客户端不能拼入排序SQL。
- 分类/标签被未删除文章引用时删除返回409。逻辑删除不释放name/slug唯一值；重复使用仍返回409。
- 项目默认隐藏；公开列表和详情只显示status=1；文章公开接口只显示status=1。未公开ID与不存在ID都返回404。
- 上传业务目录仅avatar/article/project/other；单文件及规范化后结果最大5MiB、最长边8192、最多1600万像素。JPEG保存为JPEG；PNG/WebP解码重编码为PNG，剥离元数据及尾随内容；动画仅保留首帧。
- multipart文件上限5MB，请求上限6MB；Tomcat丢弃已拒绝请求体的上限8MB，以便常见超限请求返回完整413，极大请求仍可能被服务器断开。
- 图片返回相对fileUrl，不返回磁盘路径。配置UPLOAD_PATH可持久保存，生产 uploads 已在 Day6 部署，并在 Day7 与数据库一起完成正式备份和独立恢复校验。
- 当前不提供文件删除或文件管理后台；文章/项目编辑页提供封面上传，移除封面只清空引用。公开详情使用安全 Markdown 渲染，文章编辑器保留纯文本预览。应用崩溃或磁盘清理失败不具备分布式事务保证，部署前仍需备份及孤儿文件运维策略。

Day2 验收记录见 [Day2 执行与验收报告](docs/Day2-执行与验收报告.md)。

## Day3 前端运行与浏览器验收

需要 Node.js 20.19+ 或 22.12+、pnpm。先按上文启动后端，再运行：

```powershell
cd yufeichi-web
pnpm install --frozen-lockfile
pnpm dev
pnpm build
```

打开 Vite 输出的本地地址，进入 `/login` 或 `/admin/articles`。Vite 默认将 `/api` 和 `/uploads` 原样代理至 `http://127.0.0.1:8080`，不会删除 `/api` 前缀。若后端使用其他端口，在启动 Vite 的终端设置 `$env:API_PROXY_TARGET = 'http://127.0.0.1:其他端口'`；此变量只在 Vite 服务端使用，无需提交本地环境配置。

完整网页验收必须使用独立数据库，仓库根目录执行：

```powershell
.\scripts\mvn21.ps1 clean verify
pnpm --dir yufeichi-web install --frozen-lockfile
pnpm --dir yufeichi-web exec playwright install chromium
node scripts/test-web.mjs
```

脚本创建独立随机端口的 MySQL 8.4/Redis 7 容器，运行 test profile 的 Java 21 JAR 和 Vite。仅在 `day3_test` 中初始化测试角色；账号来自迁移中的公开开发种子，数据库/Redis 密码每次随机生成。所有业务写操作通过网页完成，权限负例会直接请求 API 验证服务端 403。脚本结束会停止自己创建的进程和容器，保留临时图片证据；不读取 `deploy/.env`，不连接开发/生产库。异常中断后只能按脚本日志中的 `yufeichi-day3-*` 容器名称核对回收。

浏览器报告在 `yufeichi-web/playwright-report/`，截图在 `yufeichi-web/test-results/`，服务日志在 `yufeichi-web/.e2e-logs/`，均不提交 Git。测试前端代码以外也校验测试脚本类型：`pnpm --dir yufeichi-web exec tsc --ignoreConfig --noEmit --types node --module nodenext --target es2023 --skipLibCheck tests/admin.spec.ts playwright.config.ts`。

生产构建输出 `yufeichi-web/dist/`。Vite 代理只用于开发；部署时 Nginx 需分别代理 `/api/`、映射 `/uploads/`，其余前端深层路由使用 `try_files $uri $uri/ /index.html`。生产 Nginx 已在 Day6 实际部署并完成 `/api/`、`/uploads/`、SPA fallback 与 HTTPS 验收。

编辑失败保留表单，认证失效时在当前标签页内存中暂存未保存内容，同一账号重新登录后恢复；主动退出会清除暂存。关闭或刷新标签页不会持久保存未提交草稿，请使用“保存草稿”。Token 存于 localStorage，权限从 `/me` 获取，浏览器权限控制不能替代后端注解。

Day3 验收记录见 [Day3 执行与验收报告](docs/Day3-执行与验收报告.md)。

## Day4 公开网站与项目管理

- 公开地址：`/`、`/articles`、`/articles/:id`、`/projects`、`/projects/:id`、`/about`。
- 文章筛选：`/articles?category=分类ID&tag=标签ID&page=2`。筛选改变会回到第一页；浏览器前进/后退及刷新恢复相同状态。非法已知参数会规范化；无匹配结果显示空态。
- 项目管理：`/admin/projects`。排序值越小越靠前；项目默认隐藏，展示后才可公开读取，隐藏/已删除项目详情返回 404。
- 公开请求不发送管理员 Token。首页数量来自实际接口，不使用虚构统计；关于页只提供固定公开介绍。
- 公开图片仅加载本站 `/uploads/` 下规范 PNG/JPEG 地址；远程图片、data URI、SVG、路径穿越显示文字或占位图。上传 WebP 由服务器规范化为 PNG，不影响使用。
- Markdown 外链仅允许 HTTP/HTTPS；拒绝带账号密码的 URL，并设置 `noopener noreferrer`。不允许 Markdown 原生 HTML、任意 iframe 或脚本执行。
- 页面截图及桌面/360px、安全输入、项目操作验收见 [Day4 执行与验收报告](docs/Day4-执行与验收报告.md)。

`node scripts/test-web.mjs` 现在同时执行 Day3 + Day4 浏览器用例，仍使用原脚本创建的独立 `day3_test` 测试库和随机凭据。项目业务闭环通过真实网页操作；分页与恶意 Markdown 数据通过独立测试 API 建立。故障负例使用请求拦截模拟，主流程连接真实后端。

构建后设置 `$env:E2E_PREVIEW = '1'` 再运行相同脚本，可以针对 dist 生产产物完成浏览器验收。运行结束后移除该环境变量。Nginx 的 SPA fallback、API 前缀、图片与静态资源处理见 [前端构建与 Nginx 路由说明](deploy/Day4-前端构建与Nginx路由说明.md)。Day6 已完成线上 Nginx、SPA 深层路由、API 和 uploads 路由的真实部署验收。

## Day5 安全验收

完整记录见 [Day5 执行与验收报告](docs/Day5-执行与验收报告.md)：后端 97 项测试无跳过，前端生产构建通过，dist 浏览器 12 项通过。覆盖 Redis 真实故障、退出撤销、限流并发与过期、一次性初始化、生产文档关闭、逐端点权限与敏感日志负例。初始化操作和环境映射见 [生产配置与账号初始化](deploy/Day5-生产配置与账号初始化.md)。Day6 已完成实际线上部署；Day7 已完成正式备份、独立恢复和兼容回滚演练。
