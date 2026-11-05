# AI Day8—Day9 执行与验收报告

执行日期：2026-10-03。依据桌面《Yufeichi-AI应用构建与实习准备计划书.md》的Day8、Day9，增量建立在未提交的Day7实现上。固定Java21 / Spring Boot3.5.16 / Spring AI1.1.8，不升级框架，不开发Day10/11工具或Agent。

## 修改与原因

| 文件 | 原因与修复 |
|---|---|
| AiProperties.java、application.yml、application-test.yml、.env.example | 新增匿名与自动同步独立开关，默认false；持久任务退避默认60秒，范围1—300。测试显式隔离。 |
| SecurityConfig.java、AiKnowledgeChatController.java | 仅精确POST问答入口可按开关匿名访问，后台和摘要权限不变；GET状态只返回三项布尔值。复用ClientAddress，不直接信任转发头。 |
| AiRequestGuard.java | 新增哈希IP原子配额3次/分钟、10次/UTC天，共用全局和并发；Redis失败安全关闭调用，及时释放并发许可。 |
| AiKnowledgeService.java | 检索与模型运行之间存在排队窗口；在Chat调用前再次复核所有材料，完成时也复核所有材料而非仅引用项。严格来源允许集与缓冲校验不变；无工具定义或写入能力。 |
| AiContentChanged.java、ArticleService.java、ProjectService.java | 公开内容创建/发布/修改/下架/删除在业务事务内发仅含类型/ID的事件；提交后监听，回滚不触发。草稿/隐藏内容的普通编辑不触发同步。 |
| V12__ai_index_sync.sql | 新增持久generation/completed_generation、失败次数/下次时间/对账时间，任务增加origin/sync_generation。既有迁移不改。 |
| AiKnowledgeIndex.java | AFTER_COMMIT用REQUIRES_NEW短事务保存信号；后台2秒防抖/轮询，5分钟对账；最多3次总尝试、退避、暂停、手动恢复；新generation不会被旧任务覆盖，连接锁保证单任务执行。 |
| AiVectorStore.java | 每轮最多扫描1000行并轮转分页，每次删除最多20条。只清理确定性ID与metadata证明由本系统创建的失效来源和旧版本；foreign/无法确认归属的数据不删除。 |
| api/knowledge.ts、KnowledgeView.vue、router/index.ts | 页面可匿名打开，按服务状态显示登录/限额/不可用提示；无Token时不发送Bearer；纯文本和站内来源卡片展示，取消/超时保持原有行为。 |
| AiIndexPanel.vue | 显示自动同步待处理、失败次数、暂停和安全原因，保留管理员手动重建入口。 |
| AiKnowledgeIntegrationTests.java、AiRequestGuardTest.java | 增加匿名入口/配额/私密来源/注入边界、事务提交与回滚、两个业务生命周期、失败恢复、重启恢复及对账测试。 |
| AiVectorIntegrationTests.java、YufeichiServerApplicationTests.java | 迁移数更新至12；构建失败的旧版本保留规则改为保留未变化公开片段，已变化的旧片段可以先清理。 |
| LiveLifecycleSmoke.java | 显式付费、独立临时数据库的真实模型闭环验证，最多10次全局预约；默认完整测试不会运行。 |
| ai-knowledge.spec.ts | 匿名关闭/开放/服务关闭、来源展示、停止与后台手动恢复浏览器验收。 |
| scripts/test-web.mjs、deploy/production/verify-host.py、docs/ai/README.md | 新代码的迁移检查更新到V12，当前开发说明与开关、同步和清理行为对齐；未运行生产部署脚本。 |

以上Java文件位于yufeichi-server/src/main/java/com/yufeichi/server的对应ai/config/service目录；测试位于src/test/java，迁移位于src/main/resources/db/migration。前端位于yufeichi-web/src及tests。

## Day8 验收范围

- 默认匿名问答401；开关开启只放行精确问答POST，其他后台/摘要接口仍401/403。
- 未配置可信代理的直连来源即使连续伪造不同X-Real-IP也共用同一来源配额；可信代理的解析与非法头回退由现有ClientAddressTest验证。
- IP日配额、全局预算和并发沿用原子Redis脚本；Redis异常不调用付费模型、不泄露连接信息。
- 已发布文章与公开项目才可提供资料，草稿、隐藏/删除、旧hash、伪造正文和阶段版本不进入Chat。
- 排队期间可见性变化不调用Chat；生成期间任何上下文来源变化都会丢弃完整缓冲回答，包含未引用的来源。
- 提示注入不能调用写工具：本轮模型没有工具能力，也没有任意SQL/URL执行入口。严格JSON、来源ID允许集与服务端原文链接阻止伪造来源。
- 注入负例不等于对所有提示注入或全部事实正确性的证明；后续30条评测仍待Day13。

## Day9 验收范围

公开快照重建适合当前最多100片段的范围。每个来源按类型/ID/hash标识，片段含版本与序号形成确定性ID。业务提交只记录generation，不在业务事务调用模型；同一generation合并改动，完整成功后激活新版本。内容完全未变且向量验证通过时不调用Embedding。

暂时依赖故障会按60/120秒退避，最多3次总尝试；测试把退避设为1秒以验证同一流程。INPUT_LIMIT/QUOTA直接暂停，避免无效重试。新内容变更或管理员手动重建可恢复；持续故障不会无限付费。

业务写入成功但提交后信号记录失败时，返回的业务结果保持成功，5分钟manifest对账补偿。首次启用对账、关闭期间变化、持久RUNNING中断和未完成generation都可恢复。测试通过重新创建后台worker并保留MySQL任务状态验证恢复，不将它表述为实际生产进程重启。

下架/删除在MySQL提交后立即退出新查询；旧PG记录即使暂时残留也不送Chat。后台清理不调用Embedding；PG故障可以延后物理清理，实时MySQL校验仍阻断旧内容。新版本构建失败不替换活动指针，但已失效片段可以被提前移除；旧向量不作为长期回退档案。

## 实际验证结果

| 验证 | 结果 | 可复查记录 |
|---|---|---|
| Maven运行JDK | Java21.0.11 / Maven3.9.16 | ai-day8-day9-java21.log |
| 全量后端clean verify | **173通过，0失败/错误/跳过**，含30项问答与同步专项 | ai-day8-day9-final-full.log；2026-10-03 00:39:55 +08:00 |
| 前端SSE单元测试 | **6通过，0失败/跳过** | ai-day8-day9-web-unit.log |
| 前端类型检查与生产构建 | 通过；现有大包体积提示不阻断构建 | ai-day8-day9-final-web-build.log |
| 独立环境完整浏览器回归 | **35通过**，含7项问答/同步UI测试与原业务 | ai-day8-day9-final-browser.log |
| OpenAPI JSON和页面 | 通过，包含openapi字段 | 同上，独立浏览器环境 |
| 真实模型生命周期smoke | **1通过，0失败/跳过** | ai-day8-day9-live.log；2026-10-03 00:41:08 +08:00 |
| 密钥/私有服务空间URL/PEM扫描 | 源码、文档、dist、JAR检查通过 | 实际本机环境值比对，只输出PASS，未输出密钥 |
| Git diff检查 | 通过；暂存区空，HEAD不变 | 本次最终检查 |

真实服务使用deepseek-flash和qwen3.7-text-embedding-flash，1024维；MySQL、Redis、PG均为随机端口的临时Testcontainers，不连接开发或生产库。模型链路总计**8次全局预约**，最大允许10；预约是调用预算，不等于账单token数量。

实际公开测试文章先回答CONSTELLATION-427；保存修改并自动同步后回答CONSTELLATION-928，来源均为/articles/701且活动版本更新。下架后立即无答案、无来源，最终generation=completed_generation=2、失败次数0。注入问题返回无依据，用户数量和私密草稿状态没有变化；该问题没有消耗额外Chat预约，可能在检索阈值阶段被阻断，不将其表述为Chat已接收并抵抗该攻击。模型无写工具与私密资料不进入上下文另由代码边界和集成负例验证。

原始安全证据：[day8-day9-lifecycle-smoke.json](./evidence/day8-day9-lifecycle-smoke.json)。全部事实是测试文本，不是线上业务数据或真实项目性能指标。

```powershell
# 本地实际执行；mvn21脚本自身会结束当前shell，因此各条分开运行。
.\scripts\mvn21.ps1 -v
.\scripts\mvn21.ps1 '-Dtest=AiKnowledgeIntegrationTests,AiRequestGuardTest,AiVectorIntegrationTests' test
.\scripts\mvn21.ps1 clean verify
pnpm --dir yufeichi-web test:unit
pnpm --dir yufeichi-web build
$env:E2E_AI='1'; $env:E2E_PREVIEW='1'; node scripts/test-web.mjs
# 显式真实smoke：先从已有受保护本机环境加载供应商变量，不在命令里写Key。
$env:AI_SMOKE_ENABLED='true'
.\scripts\mvn21.ps1 '-Dtest=LiveLifecycleSmoke' test
git diff --check
git diff --cached --name-only
```

中间失败已处理并复验：新代码最初误在ProjectService只读详情插入事件（编译发现并移除）；桩模型在文章已下架但项目仍命中时必须返回无答案而非断言文章存在；旧测试的迁移数11更新为12；旧浏览器的直接跳登录预期更新为公开页面禁用提问、后台仍跳登录。最终完整后端与完整浏览器均重跑通过，没有skipTests或隐藏失败。

### 已通过Checklist

- [x] 精确匿名开关、关闭有效、其他AI与后台权限不放开。
- [x] 可信代理解析、伪造X-Real-IP无法变换配额身份、IP日配额与共享预算。
- [x] 私密/隐藏/删除/过期hash/阶段向量不进入Chat，来源来自当前MySQL。
- [x] Chat前和回答完成时复核所有上下文，未完成结果不预览为成功。
- [x] 无写工具/任意SQL/URL能力，结构化来源允许集及注入负例。
- [x] 业务回滚不记录同步；提交后记录，模型故障不回滚文章保存。
- [x] 文章发布/编辑/下架/删除及项目创建/编辑/隐藏/展示/删除同步。
- [x] 连续改动合并、内容hash/idempotency、未变化重建无Embedding。
- [x] 构建期间的新改动不丢失，失败不切换活动指针。
- [x] 有限重试/暂停/管理员恢复、持久状态的新worker恢复。
- [x] 五分钟对账补偿漏记，超限任务可见且不付费重试。
- [x] 仅清理可验证归属的过期向量，外部系统行保留。
- [x] 原业务、认证、SSE、OpenAPI、Java21完整验收。

**Day8、Day9达到本地开发验收标准。上线验收不属于本次执行，后续发布前仍需执行。**

## Git diff 摘要

当前分支codex/ai-assistant，HEAD仍为6694f896472f346a5c4c0c2fd08ec6a1d087d890。累计Day7—Day9工作包含24个已跟踪文件修改、16个新增文件；不计原有13个文档删除与原有.vscode文件。变更集中于问答安全、匿名边界、V12同步队列、业务事件、受控向量清理、前端状态与验收记录。没有新增Git提交或远程变更。


## 边界与进度

本次仅本地开发与独立数据库验证，生产仍是Day1—Day6。没有提交、推送或上线，没有改变生产开关、密码、服务或数据。Day7原有未提交代码保留；原有13个文档删除和yufeichi-web/.vscode保持原状，不属于本次新增清理。Git暂存区为空。

V1—V11、PG V1/V2保持原文；只新增V12。不执行clean/repair，不使用skipTests。Java21实际JAR文件名不变。密钥仅由环境加载，不进入前端或Git；证据仅保存临时公开测试文本、响应与安全状态，不保存Key和供应商私有业务空间URL。

Day10/11只读Tools/Agent、Day12故障组合回归、Day13质量评测、Day14发布预演按原计划后续执行。匿名及自动同步默认关闭；若将来上线，须在受保护后端配置明确开启并完成正式环境验收。本次不把本地成功表述为已上线。

提交后监听使用新事务的依据是Spring官方TransactionalEventListener对AFTER_COMMIT仍绑定旧事务资源的说明；实现以锁定框架版本编译及事务回滚/提交集成测试为准。[Spring事务事件官方说明](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html)，查询2026-10-03。
