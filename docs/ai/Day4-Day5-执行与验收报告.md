# Yufeichi AI Day4—Day5 执行与验收报告

执行日期：2026-09-30，Asia/Shanghai。执行依据：桌面的《Yufeichi-AI应用构建与实习准备计划书.md》。

## 1. 本轮结果与范围

**Day4、Day5开发验收已完成。**Day4已交付文章编辑页的摘要预览/人工采用及站内AI入口。Day5已交付独立Embedding/pgvector组件、独立schema和迁移、镜像锁定及本地验证环境；不等同于生产AI已上线。

真实DeepSeek编辑页验收1项通过；真实百炼Embedding→pgvector写入/检索1项通过。完整后端135项、组合浏览器26项、前端解析6项和生产构建通过，均0失败/错误/跳过。

本轮只到Day5：没有抽取真实文章或项目入库，没有Day6索引任务、MySQL V11、RAG、Agent、业务工具、长期记忆或线上发布。站内AI入口目前提供摘要，页面没有把未实现的知识问答包装成已可用功能。

## 2. 工作区与原业务保护

- 延续 `codex/ai-assistant`，HEAD为 `3e59b33660ac7e5c6870e5700f415c9d28903afb`，没有提交或推送。
- 延续Day1—Day3尚未提交的实现；原13个文档删除与 `.vscode` 保留，不恢复、不清理、不混入本轮变更。
- Java固定21.0.11，Spring Boot3.5.16、Spring AI1.1.8不升级。
- MySQL仍是业务DataSource，MyBatis与原Flyway配置保持。向量池、JdbcTemplate、Flyway均由向量组件内部管理，不注册会替换业务默认对象的Bean。
- MySQL V1—V10保持；PG新增独立 `db/vector/migration/V1__vector_baseline.sql`，不属于原MySQL迁移序列。
- 全部写入和删除验证只在新建、独立、临时的测试库内进行，没有连接开发/生产数据库。没有SSH、充值、部署、Git提交或推送。
- Compose验收只清理本次随机项目拥有的容器、网络和卷；已有MySQL/Redis容器与卷保留。本轮没有留下常驻PG或业务内容索引。
- 桌面原计划正文没有修改。

## 3. Day4：页面交付

### 3.1 文章编辑页

摘要面板放在原摘要框与正文之间，复用现有SSE接口，不改变原保存/发布流程。

- 正文为空、超过12000字符、无编辑权限或生成中，不发起新调用；不静默截断正文。重复触发也由函数状态检查拒绝。
- 流式预览独立于人工摘要框。未完成、停止、错误或正文变化时，半段结果不能采用。
- 开始生成时记录原正文与UTF-8 SHA-256，校验服务器meta/done。正文一旦变化，取消活动请求并使旧候选失效；即使改回原文也不能静默采用。
- 完成后点击“采用摘要”才填入摘要框；人工摘要非空时先确认替换，确认期间再次检查正文和权限。
- AI接口和预览/采用不保存文章；用户自行点击原“保存草稿/保存修改/发布文章”才写业务数据。生成不覆盖正文。
- 停止、组件卸载、离开页面和退出登录取消请求；登录失效后仍使用原草稿恢复流程。
- 使用原MarkdownContent净化展示，恶意javascript链接或图片不会执行。

### 3.2 站内AI入口

前台导航和工作台侧栏提供“AI助手”，进入 `/admin/ai`。匿名先登录，仅拥有article:add或article:update的账号可用，无权限403。

AI工作台提供当前可用的文章摘要试用：12000字符输入、Loading/等待/空态、错误、停止、预览与采用框。只保留本页面内存中的单次输入，离开清空，不持久化历史，不伪装成通用聊天或知识问答。

360px页面经过浏览器测试与实际截图检查，没有横向溢出，生成/采用按钮可操作。用户手动保存、上传、认证和原文章流程仍参与完整回归。

## 4. Day5：独立向量基线

### 4.1 依赖与镜像

| 项目 | 实际验证版本 |
|---|---|
| Spring AI模型/向量库 | 1.1.8 |
| PostgreSQL镜像运行版本 | 17.11，Debian 17.11-1.pgdg12+2 |
| pgvector扩展 | 0.8.6 |
| PostgreSQL JDBC | 42.7.11，由现有BOM解析 |
| pgvector Java客户端 | 0.1.6 |
| Flyway core/mysql/postgresql | 11.7.2，保持现有Flyway系列一致 |
| Testcontainers | 1.21.4 |

镜像：`pgvector/pgvector:0.8.6-pg17@sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f`。

独立Compose只绑定127.0.0.1，默认端口15432，独立卷，pg_isready健康检查，512MiB/0.5CPU本地配置。实际以随机项目/端口启动并确认healthy；不是生产容量评估，也没有改生产Compose。

### 4.2 组件与schema

- Chat/Embedding的地址、Key、模型独立映射。百炼基础地址包含 `/compatible-mode/v1` 时只追加 `/embeddings`，显式float与1024维。
- `AI_VECTOR_ENABLED=false`默认关闭，须同时 `AI_ENABLED=true`且PG/Embedding必要配置齐全才创建组件；配置不完整不会强制核心网站连接PG。
- PG池最多2连接，构造时不连接数据库；独立连接/SQL时限。首次显式使用向量组件，由独立Flyway校验并执行PG迁移，历史表为 `yufeichi_ai.vector_schema_history`。
- `initializeSchema=false`、`removeExistingVectorStoreTable=false`；不让Spring AI自动重建表，不使用Flyway clean/repair。
- schema `yufeichi_ai`，表 `vector_store` 包含uuid、content、jsonb metadata、固定维度vector。`embedding_profile`记录模型名与维度，拒绝不同模型/维度静默复用旧库。
- 初期使用cosine精确检索、PgIndexType.NONE，无HNSW。测试验证写入、Top-K、metadata过滤、同ID upsert与指定ID删除。
- 输入每批最多20条、每条受字符上限约束。结果条数/索引顺序、固定维度、有限数值和非零向量均校验；错误返回安全业务异常，不保留供应商正文或私密cause。
- 没有对外向量搜索/入库接口。索引任务的批量预算、任务总时限、已发布内容过滤、版本切换与下架同步从Day6—Day9实现，当前不能绕过这些边界直接开放检索。

PG故障验证实际连接不可达地址，向量初始化失败；同一应用的MySQL查询、health、公开文章和项目接口继续200。默认AI关闭测试不需要真实模型Key或PG。

## 5. 验收结果

| 验证 | 实际结果 |
|---|---|
| Day4页面专项 | 8项通过：人工采用/手动保存、快照失效、停止/离开、空/超长/权限、503模拟重试、退出取消、401模拟草稿恢复、安全Markdown/360px |
| Day5配置与模型契约 | 10项通过：独立配置、关闭/缺配置/lazy池、Chat与Embedding并存、地址路径、维度/非有限/零向量/结果索引/输入限界/异常脱敏 |
| Day5真实PG集成+模型桩 | 5项通过：版本/迁移/数据源隔离、写入检索过滤upsert删除、供应商维度不符、旧schema模型/维度不符、PG故障原业务可用 |
| 完整后端 | clean verify：135项，0失败/错误/跳过，22:34:06完成并重新打包JAR |
| 前端解析 | 6项，0失败/跳过 |
| 前端生产构建 | vue-tsc+Vite通过，原大chunk提示仍存在，没有为此重构打包 |
| 最终组合浏览器 | 26项通过，0失败/跳过，约1.4分钟；原业务12项+原AI协议6项+Day4页面8项 |
| 真实DeepSeek编辑页 | 1项通过，4.1秒：页面生成→预览→人工采用→手动保存草稿；只有1条Chat，输入是公开验收夹具 |
| 真实百炼→pgvector | 1项通过，22:29:50完成：2条公开文本批量写入、1条语义查询；共2次Embedding；1024维且命中预期Java文本 |
| 独立Compose | config通过、实际healthy、PG17.11/vector0.8.6、回环监听、镜像digest锁定；随机项目清理完成 |
| 迁移/私密值/diff | MySQL V1—V10 SHA-256全部匹配；扫描358个源码/文档/dist/报告/日志，两套真实Key及私人Embedding地址0匹配；git diff --check通过；暂存区为空 |

真实编辑页后端日志：22:36:44.111，requestId `d9e74c23-a4c6-44e1-9c39-b64a89062024`，`ON_COMPLETE durationMs=1056`。这是单次调用记录，不宣称P50/P95或生产性能。

真实向量使用Java后台实际配置构建的Embedding组件，并非直接绕过Spring AI调用API；PG为独立临时库。2条文本明确标为公开验收夹具，不冒充真实网站内容、召回评测集或已经完成的RAG质量验证。

本轮真实调用共1条Chat、2次Embedding；默认clean verify和普通浏览器回归均不调用真实模型。真实浏览器必须显式选择一个live专项，避免默认全量运行意外扩大费用；模型Key不进入Vite/Playwright，真实模式关闭trace。没有查询账单或充值，请求配额不是货币硬上限。

## 6. 修复与实施中发现的问题

| 问题 | 原因 | 处理与验证 |
|---|---|---|
| 正文变化后可能采用旧候选 | 摘要生成有延迟，生成开始的正文与当前正文不同 | 记录快照/hash；正文变化取消并永久使本次候选失效，采用前再检查；真实页面负例通过 |
| 预览可能覆盖人工摘要或被误当完整结果 | 流式片段尚未校验完成 | 预览/完成候选分离，半段不可采用；覆盖人工摘要先确认；实际业务库在手动保存前不变 |
| 多数据源可能改变MySQL自动配置 | 全局注册PG DataSource/JdbcTemplate/Flyway会触发既有自动配置退让 | PG连接对象内部持有，不新增这些Bean；真实Spring Boot+双数据库验证业务JDBC仍是MySQL，原迁移仍10个 |
| 默认维度探测可能意外调用API | EmbeddingModel的默认dimensions()可能触发模型 | 包装模型直接返回固定配置，首次响应逐条校验；单测无provider probe，真实响应1024维 |
| 更换模型/维度可能混用旧向量 | 同维度不同模型也不代表同一向量空间 | schema profile与列维度一起校验，模型/维度不符拒绝，保留旧记录；不clean/repair或自动重建 |
| 初次两项页面测试超时 | 新测试对正文标签使用exact，原标签含必填星号 | 根据真实页面使用已有article-content定位；没有为测试删除用户的原标签；8项复测通过 |
| 初次PG测试编译失败 | 两个Testcontainers包通配导入都含Container | 改为具体类导入；后续编译与135项验收通过 |
| 初次迁移数量断言失败 | Flyway历史还有自动建schema记录，不能全算版本迁移 | 断言只统计version非空的成功SQL迁移；数据库记录不修改，15项复测通过 |
| plain JUnit真实smoke不加载应用日志配置 | Provider内部重试诊断可能输出私密正文 | live smoke显式关闭供应商诊断logger，安全异常丢弃cause；真实与默认回归分开 |

## 7. 文件范围与diff摘要

本轮新增17个文件，修改15个文件；其中11个修改文件是Day1—Day3已有实现，4个是原有前端页面/路由。累计AI工作区为16个已跟踪文件修改、43个新增文件；原13个删除与IDE文件不属于AI变更。

| 范围 | 新增/修改文件 |
|---|---|
| 页面与入口 | 新增 `src/components/AiSummaryPanel.vue`、`src/views/AiWorkspace.vue`；修改 `src/views/ArticleEditor.vue`、`src/router/index.ts`、`src/layouts/AdminLayout.vue`、`src/layouts/FrontLayout.vue` |
| 前端验证 | 新增 `tests/ai-editor.spec.ts`、`tests/ai-editor-live.spec.ts`；修改 `playwright.config.ts` |
| 依赖/映射 | 修改 `yufeichi-server/pom.xml`、`src/main/resources/application.yml`、`src/test/resources/application-test.yml`、`.env.example`、`ai/AiProperties.java` |
| PG/Embedding内部组件 | 新增 `ai/AiEmbeddingModel.java`、`AiVectorConfiguration.java`、`AiVectorStore.java` |
| PG迁移 | 新增 `src/main/resources/db/vector/migration/V1__vector_baseline.sql`，与MySQL迁移目录分开 |
| 本地依赖与验证 | 新增 `deploy/ai/compose.yml`、`deploy/ai/.env.example`、`scripts/test-vector-compose.mjs`；修改 `scripts/test-web.mjs`、`scripts/ai-test-provider.mjs` |
| 后端验证 | 新增 `AiVectorIntegrationTests.java`、`server/ai/AiEmbeddingFixture.java`、`AiEmbeddingModelTest.java`、`AiVectorConfigurationTest.java`、`LiveVectorSmoke.java`；修改 `YufeichiServerApplicationTests.java`、`server/ai/LiveAiSmoke.java` |
| 文档 | 新增本报告；更新 `docs/ai/README.md` 的当前功能、映射、运行和边界 |

前端源码简写位于 `yufeichi-web/`；后端ai源码简写位于 `yufeichi-server/src/main/java/com/yufeichi/server/`；后端测试简写位于 `yufeichi-server/src/test/java/com/yufeichi/`。详细文件无需依赖恢复根README查看。

## 8. 实际执行命令与证据位置

```powershell
git status --short
git branch --show-current
git rev-parse HEAD
docker pull pgvector/pgvector:0.8.6-pg17
docker image inspect pgvector/pgvector:0.8.6-pg17 --format '{{json .RepoDigests}}'
node scripts/test-vector-compose.mjs
.\scripts\mvn21.ps1 dependency:resolve
.\scripts\mvn21.ps1 test-compile
.\scripts\mvn21.ps1 '-Dtest=AiEmbeddingModelTest,AiVectorConfigurationTest' test
.\scripts\mvn21.ps1 '-Dtest=AiVectorIntegrationTests,AiEmbeddingModelTest,AiVectorConfigurationTest' test
# 只为当前进程加载用户已有Embedding变量，不打印Key
$env:AI_SMOKE_ENABLED='true'
.\scripts\mvn21.ps1 '-Dtest=LiveVectorSmoke' test
# 完整回归没有加载真实模型Key
.\scripts\mvn21.ps1 clean verify
.\scripts\mvn21.ps1 '-Dincludes=org.springframework.ai:*,org.postgresql:postgresql,com.pgvector:pgvector,org.flywaydb:*' dependency:tree
pnpm --dir yufeichi-web test:unit
pnpm --dir yufeichi-web build
$env:E2E_AI='1'
$env:E2E_PREVIEW='1'
node scripts/test-web.mjs ai-editor.spec.ts
# 独立进程，仅真实Chat变量；不与模型桩开关同时设置
$env:E2E_AI_LIVE='1'
node scripts/test-web.mjs ai-editor-live.spec.ts
# 后续普通模型桩回归进程E2E_AI_LIVE未设置
node scripts/test-web.mjs
git diff --check
```

这些命令按隔离进程、互斥开关和构建完成后启动浏览器的顺序执行；示例是执行记录，不能把两个AI开关复制到同一进程同时启用。安全变量加载方法见README。没有使用skipTests。

日志在Git忽略范围：`ai-day4-build.log`、`ai-day4-browser.log`、`ai-day4-live-editor.log`、`ai-day5-compile.log`、`ai-day5-unit.log`、`ai-day5-focused.log`、`ai-day5-live-vector.log`、`ai-day5-compose.log`、`ai-day5-dependencies.log`、`ai-day5-tree.log`、`ai-day4-day5-full.log`、`ai-day4-day5-browser.log`。

当前页面截图：`yufeichi-web/test-results/day4-ai-mobile.png`，是明确的模型桩安全渲染测试。真实页面验收截图曾写入day4-ai-live-editor.png，但随后的完整回归已重建test-results，当前保留真实验收日志和请求记录，不提供不存在的截图。原始trace含测试Token，公开前需脱敏，不能直接当求职交付材料；最终求职录屏在后续交付阶段单独录制。

## 9. Checklist与下一阶段

- [x] 摘要编辑页与AI入口有实际功能，不自动保存/发布。
- [x] 正文快照/hash、人工替换确认、重复点击、半段不可采用通过。
- [x] 停止、页面离开、退出、登录失效恢复通过。
- [x] 空态/Loading/错误、Markdown安全、手机页面通过。
- [x] 独立PG/pgvector镜像、Compose、健康与回环监听验证。
- [x] Chat/Embedding分离、1024维、schema profile与独立迁移验证。
- [x] 实际向量写入/检索/过滤/upsert/删除与维度拒绝通过。
- [x] 自动测试无需真实Key；AI关闭/缺配置/PG故障不替换业务数据库。
- [x] 真实DeepSeek编辑页与真实百炼向量闭环通过。
- [x] 最终26项组合浏览器回归、迁移哈希、私密值和diff检查。

下一阶段是Day6的已发布文章/公开项目抽取、分段、metadata/hash、持久化索引任务与重建；尚未开始。上线Nginx、服务器容量、备份与费用预算为后续发布工作，本轮没有宣称网站AI已经上线。

官方查询日期2026-09-30：[pgvector版本与精确检索](https://github.com/pgvector/pgvector)、[Spring AI 1.1.8 PgVectorStore源码](https://github.com/spring-projects/spring-ai/blob/v1.1.8/vector-stores/spring-ai-pgvector-store/src/main/java/org/springframework/ai/vectorstore/pgvector/PgVectorStore.java)。运行版本、digest与检索结果以本机实际验证为依据。
