# Day5 安全与发布候选执行报告

日期：2026-09-28。唯一任务范围为原《Yufeichi-Platform-V1.0-审查与7天冲刺计划.md》的 Day5。原计划正文未修改；没有开展 Day6 线上部署。

## Git 与保护范围

- 开始时为 `dev` 分支，已有 Day4 提交 `6942a79c863ed2d5a38e59d17b84d3dee498101a`。本轮已正常推送至 `origin/dev`（GitHub 仓库 `yufeichi1/yufeichi-platform`），无强推。
- 初始仅有未跟踪 `yufeichi-web/.vscode/`，保留且不提交。没有读取真实环境文件内容、提交生产密码/私钥或操作服务器。
- V1–V9 无修改；仅新增 V10。所有新迁移、初始化和 HTTP 写操作使用临时容器测试库；没有连接开发或生产数据库，也没有执行 clean/repair。
- Day5 按原计划拆成 `feat(security): 完善会话撤销限流与权限验证` 与 `test(core): 添加核心流程回归` 两笔本地提交，本轮仅推送 Day4。
- 安全实现提交：`5088c14`；核心回归与本报告位于紧随其后的提交，可用 `git log -2 --oneline` 查看。

## Checklist 与证据

| Day5 项目 | 实现与实际验证 |
|---|---|
| `/me` permissions | 原 Day3 已实现，不重复开发；HTTP `/me` 与登录结构回归通过 |
| Token 30–60 分钟 | 默认 45，构造与 prod 校验拒绝越界；jti 保证同秒独立会话；已有浏览器过期 Token 回登录用例通过 |
| Redis 撤销 | SHA-256 键，PXAT 使用原 JWT 绝对到期时间；退出后旧 Token 401，另一会话仍 200；重复撤销不延长 TTL |
| 原子登录失败计数 | Lua 同时 INCR 并设置固定窗口过期；40 次并发计数无丢失；短窗口真实过期；大小写/空格/全角/重音变体共享账号计数，第六次 429 |
| 可信 IP | 默认连接对端；仅明确配置的代理可提供单个数字 X-Real-IP，拒绝伪造链和主机名，忽略 X-Forwarded-For；成功登录不清共享 IP 计数 |
| Redis 故障 | 真实 pause/unpause 独立 Redis 容器：有效 Token `/me`、退出、登录返回 503；公开文章/项目/分类/标签 GET 即使带非法可选 Token 仍 200；恢复后认证可用；无 Redis 确认响应也拒绝 |
| 后台写接口权限 | 文章新增/修改/删除/发布/下架；分类、标签新增/修改/删除；项目新增/修改/删除/展示状态；文件上传：管理员正例、只读 403、匿名 401 均覆盖 |
| 一次性账号初始化 | V10 禁用公开种子，真实旧账号登录 401；实际调用主程序 `--bootstrap`，非 Web 初始化生成 BCrypt 管理员；重复执行拒绝；生产初始化前启动失败，之后新账号登录成功 |
| V10/生产开放门禁 | V1–V10 在新库迁移并校验 checksum、无 pending；prod 检查初始化标记和种子禁用状态。真实服务器的首次初始化仍须在 Day6 开放流量前执行 |
| 公开内容隔离 | 草稿、下架文章、隐藏项目、逻辑删除文章/项目按公开 ID 返回 404 |
| 文件边界 | 真实 HTTP 超限 413、无权限 403/匿名 401；拒绝 SVG/HTML、伪造扩展名、路径穿越；新增 MIME 与扩展名匹配校验，仍解码重编码、过滤尾随载荷 |
| 最小审计 | 登录、发布/下架、删除、上传及退出记录固定事件/结果/用户 ID/资源 ID；事务完成后记录结果；日志捕获断言不含 Token、Authorization、密码、原文件名和正文 |
| prod/env 模板 | JAR 内无秘密 prod 占位配置、backend.env.example、离线说明；缺必要变量、弱密钥、root、相对上传路径、混用 dev/test 均拒绝；真实 prod HTTP 文档端点 403，SQL 打印关闭，日志目录可配置 |
| 完整测试/构建 | Java 21 `clean verify` 无跳过；冻结前端依赖安装、build、测试代码 TypeScript 检查及 dist 浏览器回归 |
| Git | 安全实现与核心回归分别提交；未强推、未自动发布 Day5 |

## Bug 原因与修改

1. 退出原来仅清当前请求和浏览器 Token，已签发 JWT 仍可用；现在每次受保护认证检查 Redis 撤销摘要，退出必须确认撤销成功。前端原 finally 无条件清状态会把 503 当作退出，改为保留会话供重试。
2. Token 默认 1440 分钟且同秒签发可能相同；改为 45 分钟并强制 30–60 范围、增加随机 jti。
3. 登录没有计数、用户名变体可绕过简单键；归一化同时用于认证查询和限流，Lua 原子写计数与过期。IP 只信任明确代理，关闭 Spring 自动转发头解析。
4. 没有 Redis 故障策略；新增 503 业务错误，真实故障与缺少确认均拒绝管理认证。公共阅读跳过认证过滤器，从而不依赖管理员的可选凭据或 Redis。
5. V2 种子口令公开且缺少受控初始化；保留原 migration，V10 禁用种子，单行锁加事务提供一次性离线初始化，无新生产密码/哈希入库脚本。
6. prod 模板缺失，日志路径固定，不具备初始化门禁；新增占位配置、必要变量与状态校验、可配置 LOG_PATH、文档拒绝规则。dev 同样关闭 SQL 明细；禁用 Flyway baseline/clean，开启校验。
7. 原图片实现只依据扩展名和解码格式，不拒绝不一致的显式 MIME；新增 MIME 一致性检查。未声明或通用 octet-stream 仍须通过实际解码，不能靠头部放行恶意内容。
8. 缺少审计，通用异常完整堆栈可能包含数据库参数；新增不记录参数的审计切面，事务成功/失败分别记录。通用错误只记录异常类型，密码字段排除 Lombok toString，去除登录文档的默认密码示例。

## 修改文件范围

- 后端认证：`security/JwtTokenProvider.java`、`JwtAuthenticationFilter.java`、新增 `RedisSecurityStore.java`、`SecurityUnavailableException.java`、`ClientAddress.java`；`service/impl/AuthServiceImpl.java`、`common/error/ErrorCode.java`、`config/SecurityConfig.java`。
- 账号初始化：`YufeichiServerApplication.java`；新增 `config/BootstrapConfiguration.java`、`ProductionSafetyConfiguration.java`、`security/BootstrapService.java`、`db/migration/V10__disable_seed_and_bootstrap.sql`。
- 审计与安全输入：新增 `security/AuditAction.java`、`AuditAspect.java`、`AuditEvents.java`；`service/ArticleService.java`、`ProjectService.java`、`FileService.java`、`service/upload/ImageStorage.java`、`exception/GlobalExceptionHandler.java`、`dto/LoginDTO.java`、`entity/User.java`。
- 配置：`application.yml`、`application-dev.yml`、新增 `application-prod.yml`、`logback-spring.xml`、`.gitignore`；新增 `deploy/backend.env.example`、`deploy/Day5-生产配置与账号初始化.md`。
- 前端：`yufeichi-web/src/stores/user.ts`、`src/layouts/AdminLayout.vue`、`tests/admin.spec.ts`；`scripts/test-web.mjs` 的默认账号启用仅作用于脚本自行创建的 `day3_test`。
- 回归：新增 `Day5IntegrationTests.java`、`ProductionSafetyConfigurationTest.java`、`ClientAddressTest.java`、`RedisSecurityStoreTest.java`；更新 Day1/Day2 集成测试、JwtAuthenticationFilterTest、AuthServiceTest、ImageStorageTest、application-test.yml；README 和本报告。

Java 相对路径均以 `yufeichi-server/src/main/java/com/yufeichi/server/` 为起点；配置位于 `src/main/resources/`，测试位于 `src/test/`。

## 执行命令与结果

```powershell
# 已检查 status、分支、remote；Day4 正常推送（现有 SSH key 经 GitHub 443）。
git status --short
git branch --show-current
git remote -v
$env:GIT_SSH_COMMAND = 'ssh -o HostName=ssh.github.com -p 443 -o HostKeyAlias=github.com -o StrictHostKeyChecking=yes -o BatchMode=yes -o ConnectTimeout=20'
git push origin HEAD:dev

# Docker 启动；隔离测试不读开发环境文件。
docker desktop start
$env:TESTCONTAINERS_RYUK_DISABLED = 'true'
$env:TESTCONTAINERS_CHECKS_DISABLE = 'true'
.\scripts\mvn21.ps1 -B -s "$env:TEMP\yufeichi-day1-maven-settings.xml" clean verify
pnpm --dir yufeichi-web install --frozen-lockfile
pnpm --dir yufeichi-web build
pnpm --dir yufeichi-web exec tsc --ignoreConfig --noEmit --types node --module nodenext --target es2023 --skipLibCheck tests/admin.spec.ts tests/front.spec.ts playwright.config.ts
$env:E2E_PREVIEW = '1'
node scripts/test-web.mjs
git diff --check
```

使用本机临时 Maven 代理 settings，不提交其中内容。Ryuk 镜像下载环境限制延续 Day1，JUnit 正常回收自己创建的容器，没有跳过业务测试。

- 后端最终完整回归：**97 tests，0 failures，0 errors，0 skipped**；JDK **21.0.11**；实际产物 `yufeichi-server/target/yufeichi-server-0.0.1-SNAPSHOT.jar`。
- 冻结依赖安装、`vue-tsc -b && vite build`、测试 TypeScript 检查：通过。
- dist 浏览器验收：**12 passed**，包含所有原 Day3/Day4 用例及新的退出失败重试/旧 Token 拒绝。
- 最终 JAR 已检查包含 prod 模板，不包含 application-test.yml、测试类或真实 backend.env/.env。
- 首轮旧断言失败（迁移数量仍期望 9；健康接口非法可选 Token 仍期望 401）已根据 Day5 行为修正。新测试曾有 Container 导入歧义、重复 bootstrap 异常断言层级不一致，以及 Windows 临时日志文件仍被共享 Logback appender 占用的清理错误；分别修正导入/断言和测试结束时关闭自身文件 appender，并完整重跑，没有删除负例或跳过测试。
- 日志保留本机 `%TEMP%/yufeichi-day5-verify-final.log`、`yufeichi-day5-web-build.log`、`yufeichi-day5-browser.log`；浏览器报告位于 `yufeichi-web/playwright-report/`，均不提交。

## 真实代码与计划差异、未开展事项

- `/me` permissions 与主要权限、内容可见性和上传安全已在 Day2/Day3 落地，本次补齐负例和回归，没有重复开发架构。
- 为满足“公开阅读不依赖 Redis”，公共接口忽略可选 Token；受保护接口对非法 Token 的 401 行为保持。这与旧健康接口断言不同，已明确调整测试。
- V10 后历史测试默认账号必须在隔离库显式启用。真实 prod 则使用新 bootstrap 账号，并且不能启用公开种子。
- Day5 实现和本地验收完成。生产实际迁移/初始化、Nginx、安全组、服务账号、Redis 持久化/防驱逐、备份恢复尚未在服务器执行，分别属于 Day6/Day7。现有 Redis Compose 不能当作已完成生产安全验收；Redis 状态丢失后需密钥轮换使旧 JWT 失效。
- 保留既有警告：Vite 公共依赖 chunk 超过 500kB，Flyway 对 MySQL 8.4 提示支持版本警告；真实迁移/checksum 和业务测试通过。本次未为消除警告随意升级依赖或重构前端。
