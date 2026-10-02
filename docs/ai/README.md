# Yufeichi AI：Day1—Day6 开发说明

已实现受文章管理权限保护的摘要 API/SSE、编辑页预览与人工采用，以及 `/admin/ai` 摘要工作台。另提供独立Embedding/pgvector和管理员手动重建站内内容索引。当前没有站内RAG或通用聊天；站内问答从Day7开始。

## 运行基线

- Java 21（本次 Maven 实测 21.0.11）；Spring Boot 3.5.16；Spring AI BOM 1.1.8。
- Maven 在 `yufeichi-server` 模块运行，Windows 入口为根目录 `scripts/mvn21.ps1`。
- 实际 JAR：`yufeichi-server/target/yufeichi-server-0.0.1-SNAPSHOT.jar`。
- 后端保持 Spring MVC/Tomcat；Spring AI 的 WebClient 使用 Java 21 HTTP 客户端，不切换为 WebFlux Web 服务。
- MySQL/Redis 使用原配置，V1—V10 migration不变；PostgreSQL采用独立连接池和独立向量迁移，不替换业务数据源。
- Day6只新增MySQL V11索引状态/任务表，以及PG V2版本查询索引，既有迁移不变。
- `AI_ENABLED=false` 为默认值。关闭时不创建模型或 HTTP 客户端，不要求 API Key/模型地址，也不尝试外部网络调用。
- 即使开关为 true，地址、Key、模型名不完整时也不创建模型传输；核心应用仍可启动，摘要接口返回503。完整但不安全的URL或无效限额会被配置校验拒绝。

## 已实现接口

两个接口均要求有效 JWT，且拥有 `article:add` 或 `article:update`。当前权限是 V1.0 的全站内容管理权限，并非作者行权限。

| 接口 | 内容与响应 |
|---|---|
| `POST /api/admin/ai/summary` | JSON `{"content":"当前正文"}`；成功返回原 `Result<SummaryResponse>` |
| `POST /api/admin/ai/summary/stream` | 同一输入；POST SSE；认证、参数、开关、配额错误在建流前返回 HTTP 错误 |

`SummaryResponse` 包含 `summary`、`contentHash` 和 `requestId`。`contentHash` 是原始输入 UTF-8 的 SHA-256；摘要接口不写入数据库。页面核对正文快照和hash，只有生成完成、正文未变化时才能采用；文章仍由用户手动保存。

输入默认最多 12000 个 Java/JavaScript 字符单位，服务端 DTO 硬上限 20000；输出最多 500，符合现有摘要字段。JSON 模式检查字段类型、长度、额外字段、重复字段和尾随数据；流式输出收到供应商有效结束标记并完成校验后才发送 `done`。

## SSE 协议

每个事件以空行分隔；`data` 为 JSON，不能将它当普通 `Result<T>` 的多次响应。

| 事件 | `data` | 行为 |
|---|---|---|
| `meta` | `requestId/contentHash/mode/sources` | 首帧，`mode=article-summary`；`sources=[]`，不伪装成来源问答 |
| `delta` | `{"text":"摘要片段"}` | 可临时展示，但未完成时不能标记成功或自动采用 |
| `heartbeat` | `requestId` | 保持链路和检测断开，默认每 10 秒 |
| `done` | `SummaryResponse` | 校验后的完整结果；客户端结束读取 |
| `error` | 安全 `Result` 错误码和消息 | 已建流后的失败；不存在后续 `done`，不得显示“生成完成” |

前端 `src/api/ai.ts` 提供 `generateSummary`、`streamSummary` 和 `cancelAiRequests`，通过独立 `fetch` 实现 65 秒客户端总时限。支持外部 `AbortSignal`；摘要面板在正文变化、停止和组件卸载时取消。浏览器离开页面、用户退出或清理登录状态也会取消请求。Token 只在 Authorization Header 中传输，不能放入 URL。

`src/api/sse.ts` 使用流式 UTF-8 解码、SSE 帧解析和 reader 取消，覆盖 CRLF 跨包、中文跨字节、多帧合包、半帧 EOF 和超大事件。

## 配置映射

变量显式映射至 `application.yml` 的 `app.ai.*`，不是供应商自动识别的环境变量。`.env.example` 只是模板，Java 不自动加载 `.env`。

| 环境变量 | 应用属性 | 默认值 |
|---|---|---|
| `AI_ENABLED` | `enabled` | false |
| `AI_PROVIDER_BASE_URL` | `base-url` | 空；启用时必填 |
| `AI_COMPLETIONS_PATH` | `completions-path` | `/v1/chat/completions` |
| `AI_API_KEY` | `api-key` | 空；仅后端 |
| `AI_CHAT_MODEL` | `chat-model` | 空；启用时必填 |
| `AI_JSON_MODE` | `json-mode` | true；是否向兼容 API 请求 json_object |
| `AI_REASONING_EFFORT` | `reasoning-effort` | 空；官方 `api.deepseek.com` 自动使用 `none`，其他供应商不发送该字段；显式值优先 |
| `AI_REQUEST_TIMEOUT_SECONDS` | `request-timeout-seconds` | 60，包含重试的整条生成时限 |
| `AI_INDEX_TIMEOUT_SECONDS` | `index-timeout-seconds` | 300，1—300；跨过时限的批次不得激活索引 |
| `AI_CONNECT_TIMEOUT_SECONDS` | `connect-timeout-seconds` | 5 |
| `AI_HEARTBEAT_SECONDS` | `heartbeat-seconds` | 10 |
| `AI_MAX_OUTPUT_TOKENS` | `max-output-tokens` | 800 |
| `AI_MAX_INPUT_CHARS` | `max-input-chars` | 12000 |
| `AI_MAX_CONCURRENT_REQUESTS` | `max-concurrent-requests` | 2，同一 JVM |
| `AI_USER_MINUTE_LIMIT` | `user-minute-limit` | 3 |
| `AI_USER_DAILY_LIMIT` | `user-daily-limit` | 10 |
| `AI_DAILY_REQUEST_LIMIT` | `daily-request-limit` | 100，全局含重试 |
| `AI_EMBEDDING_BASE_URL` | `embedding.base-url` | 空；与Chat独立 |
| `AI_EMBEDDING_API_KEY` | `embedding.api-key` | 空；仅后端 |
| `AI_EMBEDDING_MODEL` | `embedding.model` | 空 |
| `AI_EMBEDDING_DIMENSIONS` | `embedding.dimensions` | 1024；与实际返回和库列一致 |
| `AI_EMBEDDING_PATH` | `embedding.path` | 空；基础地址末尾/v1时追加/embeddings，否则/v1/embeddings |
| `AI_VECTOR_ENABLED` | `vector.enabled` | false；需同时AI_ENABLED=true |
| `AI_VECTOR_URL` | `vector.url` | 空；独立jdbc:postgresql://地址 |
| `AI_VECTOR_USERNAME` | `vector.username` | 空；独立PG账号 |
| `AI_VECTOR_PASSWORD` | `vector.password` | 空；不得进入URL、前端或Git |

配置 URL 应是服务商 origin 或经验证的基础路径；完整补全路径由 `AI_COMPLETIONS_PATH` 明确提供，避免重复 `/v1`。只接受 HTTPS；本机 loopback HTTP 为隔离测试保留。不接受用户请求指定 URL、模型或 Key。

当前适配器使用 OpenAI 兼容协议，但没有认定所有服务商都兼容。需要真实验证 JSON、SSE、结束原因与认证。即使关闭 json_object 选项，返回内容仍必须符合 JSON 结构校验。暂未提供多供应商路由。

## 配额、故障与日志

- Redis `ai:*` 使用原子 Lua 预留用户/全局计数，与原 `security:*` 隔离。每日配额按 UTC 日期；分钟窗口从首次计数开始，约 60 秒。
- 失败尝试也消耗请求配额，不自动退还。瞬态 502/503/504 或连接故障在尚未收到任何片段时最多重试 1 次，另占全局额度；401/400/429不重试。
- 输出以后不重放。供应商 429 可能是账户额度不足，不能误当成用户登录失败。
- AI 并发、线程、HTTP 执行队列有界；超时、取消、错误和完成释放许可。单 JVM 并发数不等于多实例分布式并发数，扩实例前需要额外设计。
- Redis 配额失败时 AI 拒绝新付费调用。公开文章/项目查询延续原行为；原认证本身仍按 V1.0 依赖 Redis，不能宣称 Redis 故障时所有旧功能都不受影响。
- AI HTTP 错误：参数 400、未认证 401、权限 403、配额/并发 429、模型配置或供应商故障 503、总超时 504、无效模型结果 502。
- SSE 开始后用 `error` 事件传递失败，不能改变已经发出的 200。安全链允许容器 ASYNC 完成分派，原 REQUEST 的 JWT/权限检查仍然执行。
- 只记录请求 ID、操作、结果与耗时。供应商异常消息/正文被丢弃，模型库的可能包含 Prompt 的日志被关闭。API Key、JWT 和正文不得进入日志或前端。
- 尚未实现人民币账单硬上限；请求数/输入/输出限制是目前的控制手段。发布前核对真实价格与账户预算，不把请求配额宣称为精确费用控制。

## 隔离验收命令

从仓库根目录执行：

```powershell
.\scripts\mvn21.ps1 -version
.\scripts\mvn21.ps1 clean verify
pnpm --dir yufeichi-web install --frozen-lockfile
pnpm --dir yufeichi-web test:unit
pnpm --dir yufeichi-web build
$env:E2E_AI = '1'
$env:E2E_PREVIEW = '1'
node scripts/test-web.mjs
Remove-Item Env:E2E_AI
Remove-Item Env:E2E_PREVIEW
```

后端与浏览器均使用新建的 MySQL 8.4/Redis 7 临时容器及随机端口，不接受开发/生产库作为替代。向量集成测试另建独立PG容器。模型桩仅监听loopback；协议专项注入实际模块，Day4页面专项直接操作构建后的编辑页与AI工作台。HTTP错误模拟用例明确标记。

默认构建不执行真实模型或产生 API 费用。真实 smoke 类 `LiveAiSmoke` 不匹配 Surefire 默认测试命名，只有显式 `-Dtest=LiveAiSmoke` 才执行，并额外要求 `AI_SMOKE_ENABLED=true`。

真实验收在安全设置所有模型环境变量后执行：

```powershell
# 仅在明确启用少量真实调用、已核对供应商价格后运行；不要把 Key 写入命令或文档。
$env:AI_SMOKE_ENABLED = 'true'
.\scripts\mvn21.ps1 '-Dtest=LiveAiSmoke' test
Remove-Item Env:AI_SMOKE_ENABLED
```

`LiveAiSmoke`验证普通Chat、结构化输出、SSE、Embedding各一次，不自动重试。Embedding另需独立环境变量，路径可用AI_EMBEDDING_PATH指定。该smoke仅验证模型能力；`LiveVectorSmoke`另验证Day5向量闭环。Tool Calling真模型验证留到工具任务。

已在2026-09-30通过Spring AI实际验证：DeepSeek官方 `deepseek-flash`（`https://api.deepseek.com`）与北京百炼 `qwen3.7-text-embedding-flash`、1024维。百炼基础地址使用用户自己的业务空间地址，保存在后端环境变量中，不在仓库记录。该地址已包含 `/compatible-mode/v1`，smoke默认只追加 `/embeddings`，显式请求1024维及float编码。

Windows用户环境变量不会自动进入已经打开的终端。重新打开终端，或在当前验收进程内加载；以下示例不会输出Key，也不修改用户或系统的持久配置：

```powershell
$aiNames = @('AI_PROVIDER_BASE_URL','AI_CHAT_MODEL','AI_API_KEY',
  'AI_EMBEDDING_BASE_URL','AI_EMBEDDING_MODEL','AI_EMBEDDING_DIMENSIONS','AI_EMBEDDING_API_KEY')
foreach ($aiName in $aiNames) {
  if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($aiName,'Process'))) {
    $aiValue = [Environment]::GetEnvironmentVariable($aiName,'User')
    if (-not [string]::IsNullOrWhiteSpace($aiValue)) {
      [Environment]::SetEnvironmentVariable($aiName,$aiValue,'Process')
    }
  }
}
$env:AI_SMOKE_ENABLED = 'true'
.\scripts\mvn21.ps1 '-Dtest=LiveAiSmoke' test
```

网站真实模型验收有独立开关，默认浏览器测试不会执行它。先完成后端打包与前端build，再使用已经加载Chat环境变量的终端运行：

```powershell
$env:E2E_AI_LIVE = '1'
$env:E2E_PREVIEW = '1'
node scripts/test-web.mjs ai-live.spec.ts
Remove-Item Env:E2E_AI_LIVE
Remove-Item Env:E2E_PREVIEW
```

该入口创建独立MySQL/Redis，启用真正的Java摘要接口，浏览器调用实际前端模块，共发起4条短Chat请求（JSON、SSE、取消、取消后JSON）；全局配额4包含可能的重试。只发送明确标记的公开验收正文。后端Key通过环境变量占位符读取，Vite/Playwright进程会移除所有 `AI_*` 变量，真实模式关闭trace。取消可停止本地读取和请求；供应商是否立即停止推理及如何计费不由网站保证。

Day4真实页面验收改用 `node scripts/test-web.mjs ai-editor-live.spec.ts`，只发起1条Chat，直接检查编辑页预览、人工采用、手动保存草稿；两种专项入口的调用数分别记录。

## 页面与向量运行

- 文章编辑页新增摘要面板；空正文/超长/生成中禁止发起新请求，停止或失败的半段摘要不可采用。
- 正文变化会使旧候选失效，即使改回原正文也需重新生成。已有人工摘要采用前确认替换，确认期间正文变化也不能覆盖。
- `/admin/ai`是当前可用的摘要工作台，只允许article:add/update权限。当前输入限12000字符，仅保存在页面内存，离开清空，不建立长期记忆或聊天历史。
- 前台导航与内容工作台提供入口；匿名用户先登录，无权限返回403。AI关闭时显示明确失败，不影响原编辑和保存。
- AI候选复用原MarkdownContent净化组件，不使用未经净化的HTML。

向量镜像固定为 `pgvector/pgvector:0.8.6-pg17@sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f`，本机验证PG17.11/pgvector0.8.6。独立Compose文件 `deploy/ai/compose.yml` 只监听127.0.0.1，默认15432、512MiB内存/0.5 CPU配置，采用独立持久卷和pg_isready健康检查。该资源配置不是已证明的生产容量。

本地验证使用临时容器，运行结束清理。本地长期运行时，使用Git忽略的 `deploy/ai/.env` 或当前进程环境提供独立AI_VECTOR_PASSWORD，再执行：

```powershell
# 先安全配置独立PG密码，不要在命令正文填写或打印密码。
docker compose -f deploy/ai/compose.yml up -d --wait
# 若密码在Git忽略的deploy/ai/.env文件中，明确指定文件：
# docker compose --env-file deploy/ai/.env -f deploy/ai/compose.yml up -d --wait
# 临时项目验证无需密码/模型Key；仅创建、清理脚本拥有的随机项目。
node scripts/test-vector-compose.mjs
```

Java后端另需 `AI_ENABLED=true`、`AI_VECTOR_ENABLED=true`、`AI_VECTOR_URL=jdbc:postgresql://127.0.0.1:15432/yufeichi_ai`、`AI_VECTOR_USERNAME=ai_vector`、独立PG密码与Embedding变量。Compose变量不会自动成为Java环境变量；可按其启动方式从受保护配置加载，不写进Git。若使用.env文件，Compose路径为deploy/ai，Java仍不自动读取它。

`AiVectorStore`内部管理独立Hikari池（最多2连接），不注册第二个DataSource/JdbcTemplate/Flyway Bean，不影响MyBatis和MySQL Flyway自动配置。构造不连接PG；首次显式initialize/add/search时独立Flyway校验并执行 `db/vector/migration`，记录在 `yufeichi_ai.vector_schema_history`。禁用clean/repair、禁止自动覆盖表；模型名、实际返回维度、profile记录与vector列类型必须一致。变更模型/维度需未来的独立索引版本，不能静默复用旧库。

当前选择cosine精确检索、PgIndexType.NONE，无HNSW。Embedding固定float编码，输入每批最多20条，每条受长度上限约束；响应条数、顺序、维度、有限数值与非零向量均校验。后台提供手动索引入口，内部search组件仍不可直接对外开放。

## Day6 内容索引

`GET /api/admin/ai/knowledge`返回开关、当前版本和最近20个任务；`POST /api/admin/ai/knowledge/reindex`提交任务。二者都要求JWT和现有`super_admin`角色。后台AI页面有重建按钮、处理中状态和安全失败原因，普通作者无法访问任务记录。

只读取`status=1 AND deleted=0`的文章和项目。文章取标题/摘要/正文，项目取名称/说明/技术栈，不读取账号、作者信息或请求指定URL。按段落合并为最多1024字符的片段，长段按Unicode边界拆开；本轮限制100片段/任务。metadata包括服务端生成的站内链接、sourceType/sourceId、SHA256内容hash、indexVersion、片段序号、分段策略、模型和维度。

MySQL V11持久化任务和活动版本，单一待处理任务；专属后台线程通过MySQL连接锁防止多个进程重复调用。外部模型不在业务事务内运行。每批20片段只对应一次Embedding请求，共享Chat并发和全局日配额，另加UTC每日10批/100000字符上限；失败也计入预算，不自动付费重试。任务时限300秒，单次HTTP最多60秒，边界检查会在当前有界调用结束后生效。

完整新版本写入、片段ID/正文/metadata验证、事务内重读公开来源并确认hash未变后才切换活动指针。相同内容完整重建复用版本、不新增片段、不调用Embedding；新版本或模型失败保留旧指针。崩溃中的RUNNING任务在数据库连接锁释放后的下一次轮询标为INTERRUPTED，排队任务重新启动后继续处理。旧版本和失败片段保留供诊断与回退，本轮没有自动清理。

空公开内容允许建立空索引而不调用模型。内容修改/下架/删除后的自动同步与旧版本清理属于Day9；Day7的检索必须限定活动indexVersion并实时校验来源，不可直接使用旧向量。模型/维度变更仍须独立schema/存储空间，当前明确拒绝在旧向量空间静默换模型。

生产配置见`deploy/production/ai-compose.yml`及`configure-ai.py`：独立PG持久卷、回环端口、受保护secret与backend.env。每日正式备份包含PG SQL并校验哈希；应用回退保留V11和PG卷，不能运行clean/repair/down -v。SSE使用单独Nginx location，关闭缓冲、130秒读时限，原业务API配置保持原值。

真实向量验收使用新建临时PG，只调用两次Embedding（2条公开文本一批写入、1条查询），不调用Chat：

```powershell
# 先为本进程加载已有Embedding变量；默认完整构建不会运行此类。
$env:AI_SMOKE_ENABLED='true'
.\scripts\mvn21.ps1 '-Dtest=LiveVectorSmoke' test
```

当日公开价格参考：DeepSeek Flash每百万token峰值输入缓存未命中$0.30、命中$0.006、输出$1.20，非高峰半价；北京Embedding每千输入token0.000125元。以1000输入、800输出token的摘要估算，按峰值未命中约$0.00126/次，这是测算而非本次账单。请求数量上限不是人民币硬上限；账户剩余额度、优惠和服务商侧预算策略需在后续发布前核对。[DeepSeek价格](https://api-docs.deepseek.com/quick_start/pricing/)、[百炼Embedding接口及价格](https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api/)。

## 官方依据

查询日期：2026-09-30。Spring AI 官方稳定版本列表仍列出 1.1.8；未使用 1.1.9-SNAPSHOT 或 2.x。

- [稳定版本列表](https://docs.spring.io/spring-ai/reference/spring-projects.html)
- [1.1.8 发布记录](https://spring.io/blog/2026/06/12/spring-ai-1-1-8-1-0-9-avaialble-now/)
- [1.1.8 OpenAiChatModel 源码](https://github.com/spring-projects/spring-ai/blob/v1.1.8/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatModel.java)
- [1.1.8 OpenAiApi 源码](https://github.com/spring-projects/spring-ai/blob/v1.1.8/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/api/OpenAiApi.java)
- [DeepSeek Chat协议：reasoning_effort与JSON/SSE](https://api-docs.deepseek.com/api/create-chat-completion/)
- [pgvector官方版本、精确检索与Docker](https://github.com/pgvector/pgvector)
- [Spring AI 1.1.8 PgVectorStore实现](https://github.com/spring-projects/spring-ai/blob/v1.1.8/vector-stores/spring-ai-pgvector-store/src/main/java/org/springframework/ai/vectorstore/pgvector/PgVectorStore.java)

本地模型桩证明接口与工程行为，不证明真实供应商质量、价格、可达性或 Embedding 维度。真实模型记录见本阶段执行报告。

- [Day1—Day3报告](./Day1-Day3-执行与验收报告.md)
- [Day4—Day5报告](./Day4-Day5-执行与验收报告.md)
