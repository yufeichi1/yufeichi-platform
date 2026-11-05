# AI Day7：站内知识问答执行与验收报告

执行：2026-10-02开始，跨午夜至2026-10-03（北京时间）。依据桌面《Yufeichi-AI应用构建与实习准备计划书》Day7。起点分支codex/ai-assistant，HEAD 6694f896472f346a5c4c0c2fd08ec6a1d087d890。保留原有13项文档删除及未跟踪.vscode，未修改计划正文、既有迁移或线上服务。

## 已实现

- `POST /api/ai/knowledge/chat`：最多500字符问题，有效JWT、任意启用的登录用户、独立AI_KNOWLEDGE_ENABLED默认关闭。未扩大匿名白名单或摘要管理权限。
- 活动索引版本限定的pgvector精确余弦检索，TopK6、阈值默认0.5（可配置），最多3个来源/6段，每段1024字符。
- 按类型/ID只读MySQL当前公开内容，复核status=1/deleted=0、hash、模型/维度、段号、确定性文档ID与完整片段正文。草稿、隐藏、删除、修改后的旧内容与失败暂存版本不会进入Chat。
- 有候选才生成回答。模型只返回严格JSON answered/answer/sourceIds，输出最多1600字符；只有本次候选S1—S3可作为来源。无来源、外部链接、HTML、伪造或重复ID、尾随JSON不当作成功回答。
- 标题和站内链接从当前MySQL原文生成，忽略向量metadata中的标题/网址。完成时再次复核活动版本与被引用原文；变化则丢弃已缓冲的回答，返回无依据。
- 无活动索引、活动索引为空、无有效片段直接返回明确无依据，不调用Chat；前两者也不调用Embedding。
- 共用摘要/索引的用户配额、全局预算和并发。每次非空检索一次Embedding，有有效资料时再预约一次Chat；每个问题占一次用户配额。默认总时限60秒，没有自动付费重试，取消/超时释放租约。日志仅请求ID、操作、耗时、状态，不含正文/问题/原始模型响应。
- 页面`/ai/knowledge`：输入、等待、停止、无答案、错误、纯文本回答及来源卡片，375px可操作。登录后返回正确页面；摘要工作台保留问答入口。只保留当前问题/结果，刷新或离页清空，退出登录统一取消AI请求。
- 增加此问答接口专用Nginx SSE模板位置，未安装到生产；部署时仍需实测代理。MySQL V1—V11和PG V1/V2不变，无新数据库表。

## 与计划/真实代码的对齐

匿名公开开关、可信IP配额和完整提示注入评测属Day8，所以本次维持登录受控试用；没有通过匿名通配白名单提前开放。索引继续沿用Day6真实接口和super_admin权限。

为防止未校验的引用或已失效内容成为中间答案，模型输出先在后端缓冲，JSON与原文复核后才发delta/sources/done。meta和心跳即时返回，前端完整done后展示。这是“校验后分段交付”，不能写成逐token实时预览。答案用纯文本展示，原文只通过服务端来源卡片访问；没有为了使用Markdown而保留模型生成链接。

活动索引不含新修改的内容时，返回无依据或使用其他仍有效的来源；不会使用旧片段补全。自动更新索引与旧版本清理仍待Day9；目前管理员手动重建。

## 验收结果

| 检查 | 结果与证据 |
|---|---|
| 首轮Java21完整回归 | 155项通过，0失败/错误/跳过；2026-10-02 23:55:33完成 |
| 最终Java21完整回归 | 156项通过，0失败/错误/跳过；2026-10-03 00:05:43完成，ai-day7-final-full.log |
| RAG专项 | 首轮12项通过；最终增加空活动索引和伪造metadata链接，计14项 |
| 前端类型检查/生产构建 | 通过，保留原有大chunk提示 |
| SSE单测 | 6项通过 |
| 完整浏览器 | 最终32项全部通过，包括原有28项与新增4项；日志ai-day7-browser-final.log |
| 引用页面 | 后端来源文章/项目实际详情200；浏览器引用卡片打开真实独立数据库中的公开文章并核对标题/正文 |
| 真实模型 | LiveRagSmoke 1项通过；2026-10-03 00:00:09完成；独立MySQL/Redis/PG，不访问生产业务数据 |

专项覆盖：已知文章、已知项目、没有索引、空活动索引、无关问题、非法输出、修改/删除后的旧片段、PG正文篡改、PG标题/URL篡改、模型生成期间下架、期间版本切换、暂存版本排除、总超时/取消释放并发、HTTP401/400/503、无文章管理权限的正常用户能只读问答。HTTP流开始后错误以安全error事件结束，不能将流内错误伪装成第二次HTTP状态。

浏览器RAG响应使用明确标注的UI夹具，实际登录、业务来源、页面导航和原业务接口走真实本地Java；后端专项覆盖真实MySQL/PG检索与模型桩，真实烟测覆盖外部Chat/Embedding。三类证据分别记录，没有将UI桩当成真实模型演示。

## 第一版质量记录

样本均为明确标注的公开本地验收文本，不是网站真实文章或个人经历。1条公开来源、1条私密负例，仅进入随机临时数据库。Chat deepseek-flash，Embedding qwen3.7-text-embedding-flash，1024维。

| 问题 | 实际回答 | 来源/判断 |
|---|---|---|
| 本演示项目的构建标识是什么？ | 本演示项目的构建标识是CONSTELLATION-427。 | S1，公开本地RAG验收夹具，独立库/articles/701；命中正确事实和链接 |
| 本站推荐报考哪所大学研究生，录取概率是多少？ | 站内暂无足够依据回答这个问题。请尝试更具体的文章或项目问题。 | answered=false，sources为空，无虚构大学或概率 |

最终正式烟测全局预约4次：索引Embedding批次1、已知查询Embedding1/Chat1、未知查询Embedding1。没有重试或额外Chat。此前首次阈值失败走了索引与查询，另显式执行一批3条公开文本的Embedding诊断；这是付费诊断，不属于默认自动测试，也不是精确账单统计。

原始脱敏结果见[evidence/day7-rag-smoke.json](./evidence/day7-rag-smoke.json)，其中索引UUID只属于已回收的测试数据库。这是2题烟测，通过不代表整体问答准确率或线上检索质量；Day12应扩展真实内容题集、引用支持性及未知问题评测。

## 发现的问题与修复

1. 初始检索阈值0.55挡住真实已知问题。单批诊断测得已知问题余弦0.542074、无关问题0.332755；改为可配置0.5，复跑真实模型通过。这个阈值只基于首轮样本，后续按真实题集调整，不将分数解释为可靠概率。
2. 首次新增测试编译时Testcontainers两个Container名称因通配导入歧义，改为显式导入；没有跳过测试。
3. 浏览器登录跳转断言正则匹配了login?redirect=/ai/knowledge的末尾，导致测试提前跳走并取消登录。改为检查实际pathname，32项完整重跑通过；没有修改认证逻辑绕过问题。
4. 活动空索引最初会做无用的查询Embedding，增加已成功任务chunk_count校验，空索引直接无依据；新增独立数据库测试验证0模型调用。

真实测试退出时临时Redis容器停止后出现Lettuce重连警告；发生在LIVE_RAG_PASS之后，测试通过，未连接开发/生产Redis。没有用隐藏异常代替验收。

## 执行命令与文件

```powershell
git status --short
.\scripts\mvn21.ps1 '-Dtest=AiKnowledgeIntegrationTests' test
.\scripts\mvn21.ps1 clean verify
pnpm --dir yufeichi-web test:unit
pnpm --dir yufeichi-web build
# 本进程从User环境加载已有模型配置；默认测试不会运行LiveRagSmoke
$env:AI_SMOKE_ENABLED='true'
.\scripts\mvn21.ps1 '-Dtest=LiveRagSmoke' test
# 独立模型桩/随机数据库、前端生产preview
$env:E2E_AI='1'
$env:E2E_PREVIEW='1'
node scripts/test-web.mjs
git diff --check
```

本次文件：后端新增AiKnowledgeService、AiKnowledgeChatController、KnowledgeRequest/Response；修改AiKnowledgeSources、AiProperties、application.yml、.env.example、application-test.yml；新增AiKnowledgeIntegrationTests与显式LiveRagSmoke。前端新增knowledge.ts、KnowledgeView、ai-knowledge.spec；修改ai.ts的统一取消登记、路由/前台导航/摘要工作台入口及原导航断言。文档更新AI README，新增本报告和真实烟测证据；生产仅修改Nginx模板。没有随意重构现有架构。

## Checklist与发布边界

- [x] 向量检索、实时源校验、上下文、模型与来源卡片串联。
- [x] 已知文章/项目命中正确来源；引用打开正确页面。
- [x] 未知或无内容问题明确无依据，无伪造来源。
- [x] 测试桩、浏览器与真实模型证据分开保留。
- [x] 最终156项完整回归、代码/日志及JAR/前端产物秘密扫描全部通过。

本次未提交、推送或部署Day7；工作区可供审查。线上仍为Day6代码11b70f0，Day7默认关闭。Day8匿名权限/边界强化、Day9自动同步及Tool/Agent未提前开发。本报告不宣称网站已公开开放知识问答，也不宣称用户已经独立掌握全部工程实现。

最终问答UI另复验4项，包含HTTP429与流内error 429的正确提示，日志ai-day7-browser-focused-final.log；与完整32项中的4项相同，不重复统计为36项。前端再次生产构建通过。新增代码、真实日志、JAR与前端dist扫描未发现真实Key、专属Embedding地址或私钥，git diff --check通过，暂存区为空。

最终JAR为`yufeichi-server/target/yufeichi-server-0.0.1-SNAPSHOT.jar`，SHA256 `bf650fdb15772ead89486e0574211311385f7b54243b1c6e255d0e8ae9679c8b`，未上传服务器。测试所用容器由Testcontainers/隔离浏览器runner回收，没有删除原有数据卷。Docker Desktop仅为本地隔离验收启动。

Day7开发与本地验收达到计划当日完成标准；上线验收、真实站内内容质量和后续计划独立记录。此次新增/修改23个实际文件（包含报告和烟测证据），未混入原有13项删除和.vscode。
