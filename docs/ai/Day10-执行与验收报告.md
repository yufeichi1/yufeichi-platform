# Day10 执行与验收报告

日期：2026-10-04（Asia/Shanghai）。依据桌面《Yufeichi-AI应用构建与实习准备计划书》Day10：四个只读工具、参数和输出限界、公共业务服务复用、固定查询 Workflow。本次不改计划正文。

## 范围与基线

开始时分支为 `codex/ai-assistant`，HEAD 为 `6694f896472f346a5c4c0c2fd08ec6a1d087d890`。工作区已有 Day7—Day9 未提交成果、13 个文档删除和 `yufeichi-web/.vscode/extensions.json`；保留原状，不恢复、不清理、不暂存。本次没有提交、推送或操作线上服务，生产仍为 Day1—Day6。

Java 21.0.11、Maven 3.9.16、Spring Boot 3.5.16、Spring AI 1.1.8 保持不变。实际构建产物为 `yufeichi-server/target/yufeichi-server-0.0.1-SNAPSHOT.jar`。不升级依赖，不修改 MySQL V1—V12 或 PG V1/V2，不使用 skipTests、Flyway clean/repair。

## 已实现行为

登录用户访问 `/ai/tools`，输入关键词，选择文章、项目或两者。服务器逐类搜索并读取首项，返回原文摘录、站内来源和实际调用步骤；单次最多 4 次工具 callback、2 条详情。页面每类最多 3 项，接口允许每类 1—5 项。查询状态、结果和步骤仅属于当前请求。

| 工具 | 输入限制 | 业务服务与输出 |
|---|---|---|
| searchArticles | keyword 1—80；limit 整数 1—5 | ArticleService.list(publicOnly=true)，第一页，标题包含查询，最多 5 个预览 |
| getArticleDetail | id 整数 1—9007199254740991 | ArticleService.detail(publicOnly=true)，已发布且未删除文章，摘要与正文摘录 |
| searchProjects | keyword 1—80；limit 整数 1—5 | ProjectService.list(publicOnly=true)，第一页，名称包含查询，最多 5 个预览 |
| getProjectDetail | id 同上 | ProjectService.detail(publicOnly=true)，公开且未删除项目，说明与技术栈摘录 |

标题和预览各最多 200、详情最多 2000 个 Java UTF-16 单元，截断避免拆开完整代理对。详情附全文 hash 和 truncated 标志；输出只含公开文本、类型、ID、hash 和规范站内 URL，不输出账号、作者 ID、管理状态、统计字段或外部项目链接。

### 校验与工程边界

- Spring AI 的 `@Tool`、`@ToolParam` 和 `MethodToolCallbackProvider` 生成恰好四个工具。schema 声明长度、数值范围和 `additionalProperties=false`，callback 实际入口另验证类型、必填字段、额外字段、重复字段、尾随 JSON 和范围。直接 Java 方法也验证参数范围。
- 固定 HTTP 请求只接受 keyword、scope、limit，拒绝额外字段、非法类型、非法范围；scope 为 ALL/ARTICLE/PROJECT。工具不存在可由用户控制的 SQL、URL、publicOnly 或状态参数。
- `%`、`_`、反斜杠和控制字符被拒绝；普通 SQL 式或 URL 式文本只作为参数化关键词，不执行 SQL 文本或访问 URL。搜索只匹配标题/名称，不表述为全文或语义检索。
- 草稿、隐藏、删除与不存在详情统一 NOT_FOUND。其他业务错误和数据库错误继续向外传播，不伪装成正常无结果。空结果明确建议更换关键词。
- 完成前重新通过公开业务服务核对每个预览和详情；公开状态或已返回内容发生变化时清空本次结果，提示重新查询。内部复核不增加第五个模型工具；其数据库读取不计入四次 callback 上限。
- 两个 `/api/ai/tools/*` 接口均要求 JWT；普通启用用户可以查公开内容，匿名问答开关不放开工具接口。身份来自认证 principal，传给配额逻辑，不接受请求中的用户 ID。
- `AI_TOOLS_ENABLED=false` 映射 `app.ai.tools-enabled`；需与 `AI_ENABLED=true` 共同开启。独立用户配额 10 次/分钟、100 次/UTC 天，复用 AI 并发上限；Redis 故障返回 503 并释放许可，不预约付费供应商预算。
- 本流程只需要现有 MySQL、Redis，不需要 Chat Key、Embedding 服务或 PG；没有调用外部模型、写业务服务、索引任务或任意网络工具。现有摘要/RAG 没有得到这些工具。
- 日志只记 requestId、操作、步骤数量、结果类别和耗时，不记关键词、正文、原始 callback 参数或 Token。页面文本插值展示，来源校验为 `/articles/id` 或 `/projects/id`；拒绝伪造外链。
- 浏览器沿用 15 秒请求超时，支持停止和离页清空；取消不会撤销已开始的只读 SQL。本阶段没有服务端多轮模型循环或其总时限，不能将其表述为 Agent 已完成。

## 修改文件

| 范围 | Day10 文件与作用 |
|---|---|
| 后端新增 | `SiteReadTools.java` 四工具；`SiteReadToolCatalog.java` schema/参数校验；`SiteReadWorkflow.java` 固定流程与完成复核；`ToolQueryRequest.java` 严格请求 DTO；`SiteReadController.java` 登录入口 |
| 后端配置增量 | `AiProperties.java`、`application.yml`、`application-test.yml`、`.env.example` 增加默认关闭开关；`AiRequestGuard.java` 增加独立查询限额 |
| 后端验收 | 新增 `SiteReadToolsTest.java`、`SiteReadWorkflowTest.java`、`SiteReadIntegrationTests.java`；扩展 `AiRequestGuardTest.java`，共新增 20 个后端用例 |
| 前端新增 | `src/api/tools.ts` 请求/来源/输出校验；`src/views/front/ToolsView.vue` 查询与结果页；`tests/ai-tools.spec.ts` 五个浏览器用例 |
| 前端入口与验证运行器 | `src/router/index.ts` 登录路由与安全回跳；`AiWorkspace.vue`、`KnowledgeView.vue` 查询入口；`scripts/test-web.mjs` 在独立验收环境开启工具功能 |
| 文档与证据 | `docs/ai/README.md` 更新已实现范围；本报告；`evidence/day10-tool-schemas.json`、`evidence/day10-workflow.json` |

后端文件位于 `yufeichi-server/src/main/java/com/yufeichi/server/ai`，测试位于对应 test 目录；前端文件位于 `yufeichi-web`。表中部分文件已有 Day7—Day9 未提交修改，累计 git diff 不等于 Day10 独立 diff。业务 ArticleService/ProjectService、认证架构、数据库迁移和部署配置没有 Day10 新改动。

## 验收结果

后端集成测试使用独立 Testcontainers MySQL 8.4、Redis 7；完整回归中的向量用例使用临时 PG。浏览器运行器另创建独立 MySQL、Redis、Java 和本地模型桩，结束后清理本次运行资源。不连接开发/生产库，不写线上内容。

| 验证 | 结果与记录 |
|---|---|
| Maven 实际 JDK | Java 21.0.11 / Maven 3.9.16；`ai-day10-java21.log` |
| 完整后端 clean verify | **193 通过，0 失败/错误/跳过**；`ai-day10-full.log`，2026-10-04 02:01:17 +08:00 完成 |
| 前端单元测试 | **6 通过，0 失败/跳过**；`ai-day10-web-unit.log` |
| 前端类型检查与生产构建 | 通过；`ai-day10-web-build.log`；现有大包体积提示不阻断构建 |
| 完整浏览器回归 | **40 通过，0 失败/跳过**，含五项 Day10 用例；`ai-day10-final-browser.log` |
| OpenAPI | 独立验收环境 JSON/UI 检查通过，包含 openapi 字段 |
| 密钥与 diff | 实际本机 API Key、私有 Embedding URL、私钥头在变更源码/文档、dist、应用 JAR 和本次日志中均未检出；git diff --check 通过，暂存区空、HEAD 不变 |

实际 schema 来自 Spring AI callback，实际流程响应来自独立数据库 HTTP 集成测试：[工具定义](./evidence/day10-tool-schemas.json)、[固定查询响应](./evidence/day10-workflow.json)。后者查到公开文章 101 和公开项目 201，依次执行 searchArticles → getArticleDetail → searchProjects → getProjectDetail，返回原文、hash 和站内来源。所有正文都是验收夹具，不是线上文章或真实模型答案，不以该证据声称模型已选择工具。

专项覆盖草稿/隐藏/删除、普通用户、匿名 401、字段与类型错误 400、配额 429、功能关闭 503、Redis 故障、无结果、SQL 式/URL 式关键词、内容更新和下架、查询中变化、跨请求隔离、外链拒绝、纯文本 HTML、停止/离页、360px 页面及来源跳转。真实固定查询浏览器用例确认本地模型桩调用数为零。

实际命令（Maven 脚本退出当前 shell，各条分别执行）：

```powershell
.\scripts\mvn21.ps1 -v
.\scripts\mvn21.ps1 '-Dtest=SiteReadToolsTest,SiteReadIntegrationTests' test
.\scripts\mvn21.ps1 '-Dtest=SiteReadToolsTest,SiteReadWorkflowTest,SiteReadIntegrationTests,AiRequestGuardTest' test
.\scripts\mvn21.ps1 clean verify
pnpm --dir yufeichi-web test:unit
pnpm --dir yufeichi-web build
$env:E2E_AI='1'; $env:E2E_PREVIEW='1'; node scripts/test-web.mjs
git diff --check
git diff --cached --name-only
```

中间失败如实保留：首次专项因 Docker Desktop 未运行无法启动容器，启动后解决；一条新测试错误地禁止整个响应出现 status（正常工具结果本来含 FOUND/NOT_FOUND），修正为检查业务 detail 内不输出管理 status，完整后端复验通过。首轮浏览器 37 通过、3 失败：Day10 测试的公开项目夹具遗留影响后续排序/数量断言，已在 finally 中通过业务 API 清理且只操作本测试创建的临时 ID，完整重跑 40 项全部通过；没有放宽原业务断言。

## 进度与后续

Day10 达到本地开发验收标准：四个工具、参数/输出限界、公开业务服务复用、固定 Workflow 及其安全负例均已实际验证。

- [x] 四个只读 Tool 和实际 schema，无写工具或任意 SQL/URL 执行。
- [x] 草稿、隐藏与删除过滤；非法参数拒绝；无结果解释。
- [x] 固定 search → detail 流程，最多四次 callback、两条详情。
- [x] 普通登录用户可用、匿名拒绝、关闭有效、独立配额与 Redis 故障处理。
- [x] 公开状态/摘录版本复核、来源校验、跨请求隔离、纯文本展示。
- [x] 完整后端、前端构建、浏览器与原业务回归通过。
- [x] 密钥检查、迁移/依赖保留、暂存区空，未提交/推送/上线。

工具默认关闭，当前工作留在本地；没有提交、推送或上线。Day11 再验证真实模型 Tool Calling、显式身份、受控轮次/工具次数/总时限和任务集；Day12—Day14 的组合故障回归、质量评测和发布交付仍待执行。本次不将固定查询页写成已完成多步骤 AI 助手。

工具接口使用锁定版本的 [Spring AI 1.1 官方 Tool Calling 文档](https://docs.spring.io/spring-ai/reference/1.1/api/tools.html)，查询日期 2026-10-04；以当前项目编译、实际 schema 和集成测试为准，未根据最新版 2.x 文档升级依赖。
