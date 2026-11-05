# Day12—Day14 执行与验收报告

日期：2026-11-05（Asia/Shanghai）。依据桌面《Yufeichi-AI应用构建与实习准备计划书.md》；没有修改计划正文。

## 范围与基线

- 起始分支codex/ai-assistant，HEAD `6694f896472f346a5c4c0c2fd08ec6a1d087d890`。工作区已有Day7—11修改，13项历史文档删除及.vscode；均保留，不自动恢复、暂存、提交或推送。
- Java21 / Maven3.9.16 / Spring Boot3.5.16 / Spring AI1.1.8；MySQL业务库与MVC架构不变。本轮没有升级依赖、训练模型或增加Memory/MCP/多Agent。
- Day14按原计划执行隔离预演与候选包；正式上线需后续明确要求。本轮没有SSH或操作生产，历史Day6线上证据不冒充本日复验。

## Day12：稳定性

新增模型500与关闭AI的HTTP回归，真实暂停/恢复本次Testcontainers Redis及PG；Redis故障拒绝新付费调用、Chat/Embedding零调用；PG中断不发Chat、有界错误、恢复后同池可用。两者故障期间公开文章读取仍200，不声称原JWT登录不依赖Redis。

保留并重跑既有模型429/超时/有限重试、权限/Token、源变更、非法工具/来源、断开取消、索引生命周期、上传与OpenAPI测试。追加Nginx下两条在途AI占满后第三条429、原内容仍响应及停止恢复的浏览器用例。

| 验收 | 结果 |
|---|---|
| JDK/Maven实际运行版本 | Java21.0.11 / Maven3.9.16；ai-day12-java21.log |
| 故障专项 | 42项通过，0失败/错误/跳过；ai-day12-faults.log |
| 最终完整后端 | **230项通过，0失败/错误/跳过**；2026-11-05 19:23:14 +08:00，ai-day12-final-full.log |
| 前端单元/类型/生产构建 | 6项单元通过；vue-tsc与生产build通过；保留既有大包提示 |
| 最终Nginx完整浏览器 | **47项通过，0失败/跳过**；完整真实Nginx HTTPS，ai-day14-nginx-browser.log |
| 迁移保护 | HEAD中11个MySQL+2个PG迁移逐字节一致；Day9新增V12保留；本轮无新迁移，无clean/repair |

## Day13：效果评测

固定8篇公开文章、2个公开项目（共10片段），另有私密草稿/隐藏项目负例；全部在独立MySQL/PG，只用合成验收内容。18条可回答+6条无答案+6条边界，Top-K=3/5各30条，只改一个因素。

最终两组均18/18召回、18/18必要事实与预期引用通过、6/6无答案、6/6边界；无请求错误/私密哨兵泄露。Top-K3完整耗时P50/P95=1073/1691ms，Top-K5=1004/1361ms，每组n=30串行；不代表容量或稳定性能差异。

初测B05安全拒绝删除却answered=true，按门槛判失败。补充RAG操作请求统一无答案规则后，保持原题重跑通过，初测记录保留。没有实际写工具、泄露或删除。必要事实自动规则之后，助手核对36条正例答案与原文、24条无答案/边界；不是独立人工盲评。

初测与复测各105次供应商预约，合计210（122次Embedding、88次Chat）；不自动充值。RAG没有保留token元数据，写不可得，不编0或费用。详见[evaluation.md](evaluation.md)、[最终JSON](evidence/day13-evaluation-final.json)、[CSV](evidence/day13-evaluation-final.csv)、[初测](evidence/day13-evaluation-initial.json)。

## Day14：发布交付

- 真实Nginx 1.28.0（固定镜像摘要）、本机自签HTTPS和实际构建dist/JAR：完整47项浏览器通过；之后在最终日志/资源采集代码下重跑2项代理专项通过。普通API/登录/上传/Markdown/文章项目、SSE心跳/分段、停止、满并发429与恢复均验证。
- 真模型三场景浏览器1项通过（约15秒用例，另有加载/录屏尾部）：摘要人工采用与保存、公开索引、RAG原文链接、Agent三轮模型/两次工具、375px页面。截图和约1.1MB短视频已保存；不是5分钟讲解录像。
- 成功演示预留7次供应商调用。此前一次证据捕获失败：翻页后读取流响应副本导致Playwright错误，已改为保存实际页面结果/步骤，保留失败日志；该次精确预约数未留存，上限12。合计本轮真实调用可确认210+7，另一次失败演示≤12，总量≤229次，不冒充精确费用或token账单。
- 演示后的备份阶段初次失败：MySQL临时初始化服务接受Unix-socket SELECT 1，但目标库未就绪。改为等待正式TCP服务及目标库，并在创建恢复容器前落盘manifest。复用原演示SQL到新隔离夹具后，重新快照/导出与恢复，全程未增加模型调用。最终MySQL V1—V12 success/checksum、1篇文章、1条PG向量、1024维、上传字节及文件哈希通过；恢复容器无网络/无端口，已回收。该恢复不是生产备份；原导出与复验来源保留provenance。
- 本机资源快照来自最终模型桩HTTPS专项（并非真实模型负载/生产）：Java工作集约373.9MiB、私有内存约444.7MiB；另保存MySQL/Redis/Nginx、PG恢复容器和本机磁盘快照。不宣称峰值、容量或线上资源已核验。
- 从HEAD只读导出并完整142测试构建的历史Day6 JAR，在当前应用已迁移的同一隔离V12库上启动、登录、写/读文章通过，迁移success/checksum逐行保持一致；ai-day14-rollback.log与day14-historical-build.json。
- 最终秘密扫描覆盖180个变更/新增文件、dist、Day12—14日志和最新浏览器日志，另查JAR中BOOT-INF/classes；实际两枚Key、专属Embedding Base URL与私钥头均未发现。扫描之后新增的收尾文档也由打包器再次扫描。Git HEAD未变、暂存为空、13项原有删除/.vscode保留。
- 本地候选包由build-ai-release.py在本报告收尾后生成；绝对路径、包SHA256、JAR SHA256及源树hash写入tmp/ai-day14-release-result.json，并附.zip.sha256。它是未提交工作区的可审查产物，不冒充Git正式release或线上部署。

## 文件与修复原因

| 范围 | 本轮实际改动及原因 |
|---|---|
| AiKnowledgeService / AiProperties / application.yml / .env.example | 将硬编码Top-K=6改为1—10可校验配置（默认仍6），实现单变量比较；明确操作类请求无答案协议 |
| SecurityConfig | 仅test且不含prod时允许HTTPS回环Origin；本机自签HTTPS原先被CORS拒绝；生产域名白名单不变 |
| AiWireFixture / AiIntegrationTests / AiKnowledgeIntegrationTests / AiConfigurationTest | 500、关闭AI、真实依赖中断与恢复、Top-K参数边界；默认无真实模型调用 |
| LiveAiEvaluation / ai-eval-v1.json | 显式付费、125调用上限、固定30题、逐条失败仍落盘、阶段耗时/候选/hash/来源/版本证据 |
| deploy/production/nginx.conf | 两处SSE错误页名称引用不存在的location，改用已有名称；Agent增加专用SSE代理，普通API不改超时 |
| scripts/test-web.mjs / playwright.config.ts | 可选真实Nginx与自签HTTPS、真实三场景演示、12调用上限、私密环境不进前端/trace、备份和资源证据 |
| ai-nginx.spec.ts / ai-showcase-live.spec.ts | HTTPS真实分段/心跳/取消/并发隔离；真实模型截图和短视频，不用桩冒充真实AI |
| verify-isolated-ai-backup.mjs | 本次非空MySQL/PG备份哈希与独立无网络恢复；上传字节验证；不接收生产URL |
| build-ai-release.py / .gitignore | 校验验收记录、实际秘密扫描、产物/源树/逐文件hash，创建本地候选包；忽略Python生成缓存 |
| docs/ai | 架构、评测、测试、发布回退、两版求职描述、执行报告及安全证据；不恢复用户已删除根README |

## 执行入口与中间失败

```powershell
.\scripts\mvn21.ps1 -version
.\scripts\mvn21.ps1 '-Dtest=AiIntegrationTests,AiKnowledgeIntegrationTests' test
# 显式加载已有模型变量后运行，两轮分别保存日志/原始证据。
$env:AI_EVAL_ENABLED='true'
.\scripts\mvn21.ps1 '-Dtest=LiveAiEvaluation' test
.\scripts\mvn21.ps1 clean verify
pnpm --dir yufeichi-web test:unit
pnpm --dir yufeichi-web build
$env:E2E_AI='1'; $env:E2E_PREVIEW='1'; $env:E2E_NGINX='1'
node scripts/test-web.mjs
# 其余演示/回退/打包实际命令在下方最终记录。
```

初版评测类将Source字段写为type()导致测试编译失败，修正为真实sourceType()后才发起付费评测。初次HTTPS回归暴露test只允许HTTP回环Origin，生产白名单未改；增加test专用HTTPS来源重跑。期间一次clean因运行中Java占用JAR失败，没有跳过测试或强删文件；结束旧入口、确认并清理该次自建容器后重新完整clean verify。随后一轮完整HTTPS回归在第37项登录遇到一次代理503，trace等待约5秒，36通过/1失败/10未执行；保留ai-day14-nginx-browser-connect-timeout.log。未改生产超时、未增加隐藏重试，增加Nginx日志留存后整套47项通过。该次代理连接失败的根因尚未证明，不冒充已修复生产问题；真实生产链路仍需正式发布时复验。保留相关日志，最终通过后才写完成。

## 验收 Checklist

- [x] Java21固定、框架与MySQL保持，已有修改与迁移保护。
- [x] 默认构建不调用真实模型；完整230后端、6前端单元、前端构建通过。
- [x] 故障注入、恢复、取消与资源隔离，完整47浏览器及最终2项代理专项通过。
- [x] 固定30题/两组真实评测、原始JSON/CSV、失败和来源复核记录。
- [x] 三场景真实截图、短录屏与可追踪工具步骤；材料不冒充个人独立掌握。
- [x] 非空隔离备份恢复、hash/迁移/维度/上传检查；生产新备份未操作。
- [x] 旧JAR/V12兼容最终结果记录，登录与业务写读通过。
- [x] 最终秘密扫描通过；候选包生成入口校验测试/评测/真实演示/恢复/实际产物hash，结果以tmp/ai-day14-release-result.json为准。

## 最终结论

**Day12故障回归、Day13固定题集真实评测、Day14隔离部署预演与交付材料已完成。**正式生产发布、生产最新备份/容量/真实内容验收、Git提交与推送不在本次执行范围，仍待明确安排。简历素材基于真实完成，不宣称独立掌握、生产容量或实习经历。


## 最终命令与材料

除上文命令外，实际执行了：

```powershell
# 真实环境变量只在当前子进程加载，不在命令正文填写值。
$env:E2E_AI_DEMO='1'; $env:E2E_NGINX='1'; $env:E2E_PREVIEW='1'
node scripts/test-web.mjs ai-showcase-live.spec.ts --max-failures=1
# 仅代理专项，模型为loopback桩；同时采集本机资源。
$env:E2E_AI='1'
node scripts/test-web.mjs ai-nginx.spec.ts --max-failures=1
# 独立导出HEAD后，在该临时目录运行其mvn21.ps1 clean verify，142项全部通过。
# 回退测试必须移除AI演示/模型桩/Nginx标志，指定上述历史JAR绝对路径。
$env:E2E_ROLLBACK_JAR='<独立历史构建目录>/yufeichi-server/target/yufeichi-server-0.0.1-SNAPSHOT.jar'
node scripts/test-web.mjs
# 已有模型变量仅用于精确值秘密扫描，不发模型请求。
python scripts/build-ai-release.py
```

浏览器真实三场景成功后，入口的备份阶段曾非零退出；修复就绪判定后，复用同一批已保存SQL创建新隔离源库，调用verifyIsolatedAiBackup完整重新导出并恢复，退出0。没有把浏览器通过写成原备份入口首次全部通过，也没有为重跑恢复再消耗模型API。

- [三场景短录屏](evidence/day14-three-scenes.webm)、[摘要截图](evidence/day14-summary.png)、[RAG截图](evidence/day14-rag.png)、[Agent截图](evidence/day14-agent.png)、[手机截图](evidence/day14-mobile.png)。录屏无旁白，已检查公开页面，无密码输入/模型Key/Token展示。
- [真实演示元数据](evidence/day14-demo.json)、[实际工具步骤文本](evidence/day14-agent-display.txt)、[实际模型/产物版本](evidence/day14-runtime.json)、[成功演示7次预约](evidence/day14-provider-reservations.json)。
- [非空恢复](evidence/day14-backup-restore.json)、[恢复来源说明](evidence/day14-backup-provenance.json)、[历史JAR兼容](evidence/day14-historical-build.json)、[迁移不变](evidence/day12-migration-check.json)。
- [230后端原始套件计数](evidence/day12-backend-tests.json)、[47浏览器与代理计时](evidence/day14-nginx-tests.json)、[秘密/Git审计](evidence/day14-delivery-audit.json)。
- [累计Git差异快照](evidence/day14-git-diff-summary.json)：包含Day7—14全部尚未提交内容，不只本轮。原有13个文档删除贡献大量删除行，未恢复或混入暂存；不要盲目git add -A。
- [架构](architecture.md)、[评测](evaluation.md)、[测试](testing.md)、[部署与关闭/回退](deployment.md)、[两版求职描述](求职项目描述与演示.md)。

## 仍未执行与限制

1. 正式生产发布、最新生产备份/异机恢复、当前线上资源/证书/真实内容验收、Git提交/推送/PR均未执行；这是计划条件与本次授权范围，不写“新AI版本已上线”。
2. 一次本机代理503的精确根因未证明；完整47项和后续2项HTTPS专项均重跑通过，失败记录保留。上线前需要真实服务器链路复验，不能仅用本机结果承诺稳定性。
3. RAG token元数据不可得、短文本合成数据规模小、非独立人工盲评、无生产容量测量；不虚构指标或效果。真实内容增加后应扩充评测集。
4. 个人独立理解与面试讲解仍需后续学习，不把本次AI辅助实现等同于已独立掌握。
