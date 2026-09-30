# Yufeichi AI Day1—Day3 执行与验收报告

执行日期：2026-09-30。依据：桌面的《Yufeichi-AI应用构建与实习准备计划书.md》。本轮仅实施 Day1—Day3，不开展 Day4 产品页面、RAG、向量库、业务工具或线上部署。

## 1. 结论与真实边界

| 阶段 | 已实际完成 | 尚未完成 |
|---|---|---|
| Day1 | Java21/Maven、业务基线、Spring AI版本、可关闭配置、隔离环境；真实Chat/JSON/SSE与1024维Embedding四项通过；当日公开价格核对 | 精确账户剩余额度与服务商侧费用硬上限未核对，归发布预算准备 |
| Day2 | 摘要接口、权限、校验、超时、错误码、Redis配额、不写业务数据；模型桩及真实DeepSeek网站摘要通过 | Day4编辑页摘要预览/采用UI尚未实施 |
| Day3 | SSE、前端模块、心跳/总时限、错误与取消；真实DeepSeek浏览器多段输出、取消及恢复通过 | 生产Nginx/HTTPS验证归后续发布阶段 |

**Day1—Day3开发验收已通过，原先的真实模型阻塞已补齐；网站AI尚未上线。**默认回归使用本地模型桩；显式启用的真实模型验收单独记录，不将两者混为模型效果证据。

最终后端复核120项通过（21:39:13完成），前端解析6项和生产构建通过；真实供应商smoke4项、真实模型浏览器2项通过；最新组合浏览器18项通过（54.0秒）。全部0失败、0错误、0跳过。真实验收不混入默认构建数量。

## 2. 基线与保护情况

- 开始分支 `dev`，HEAD `3e59b33660ac7e5c6870e5700f415c9d28903afb`。
- 新建本地开发分支 `codex/ai-assistant`，HEAD未改变，没有提交或推送。
- 原有13个文档删除状态保留；原有 `yufeichi-web/.vscode/` 保留；没有恢复、删除或改写这些文件。
- V1—V10 migration的SHA-256与开始时完全一致；没有新migration，没有clean/repair数据库操作。
- 启动了本机Docker Desktop以运行隔离测试，测试只使用新建临时MySQL/Redis容器、随机端口和测试profile。
- 没有连接网站服务器、生产数据库或操作线上部署，没有充值。21:34—21:40使用用户提供的本机环境变量调用真实模型服务：Chat共7条、Embedding1条，包含1条中途取消的Chat。桌面原计划没有修改。

## 3. 实现内容

### Day1：依赖、配置与基线

- Maven实际输出：Java21.0.11 / Eclipse Adoptium，Maven3.9.16。
- Spring Boot3.5.16不变；新增Spring AI BOM1.1.8、`spring-ai-openai`和测试用`reactor-test`。
- 使用明确的模型构建，不引入会在AI关闭时要求Key的模型starter自动配置。
- Spring AI引入的WebClient只作为外部模型调用客户端；网站仍由Spring MVC/Tomcat提供接口。
- Java21 HTTP客户端使用独立、有界执行器；模型工作线程、定时器与并发许可独立于普通业务。
- `AI_ENABLED=false`默认关闭；启用但缺少地址/Key/模型名时也不创建模型传输，摘要返回503，核心启动可用。
- 模型参数安全校验；不接受请求中任意指定URL、Key或模型。正式供应商仅HTTPS，本地协议桩允许loopback HTTP。
- 独立test profile显式关闭AI并清空供应商配置；AI集成测试通过显式测试参数启用本地模型桩。
- 新增真实模型smoke入口，正常构建不执行、不产生API费用。具体变量与运行方法见本目录README。
- 实际模型固定为DeepSeek官方 `deepseek-flash`；Embedding为北京百炼 `qwen3.7-text-embedding-flash`、1024维。两套Key独立，均不记录实际值；Embedding仅能力验证，尚未建设知识索引。
- Windows用户级环境变量已存在，但当前终端未继承；为验收进程加载，不写持久私密文件、不修改用户环境。检查发现持久 `AI_ENABLED` 未设置，应用仍默认关闭，隔离真实验收显式开启。
- 官方DeepSeek默认思考，本任务按官方协议显式发送 `reasoning_effort=none`。其他供应商保持不发送；`AI_REASONING_EFFORT`可覆盖。

### Day2：文章摘要

- `POST /api/admin/ai/summary`，返回原`Result<T>`封装，包含摘要、正文hash与请求ID。
- 有效JWT且拥有`article:add`或`article:update`；没有扩大V1.0内容管理权限或虚构作者行权限。
- 默认正文上限12000，DTO硬上限20000；摘要最多500。空值、空白、超长请求在调用模型前拒绝。
- JSON结构严格检查：单一summary字符串、重复字段、尾随数据、空值、长度和明显HTML均受控。
- 只生成候选，不写文章表、不保存或发布。hash为原始输入UTF-8 SHA-256，供Day4检查正文是否变化。
- Redis原子预留用户分钟/每日及全局每日配额；新前缀`ai:*`与认证安全状态分离。
- 默认单JVM并发2，用户3次/分钟、10次/日、全局100次/日；有限重试单独占用全局配额。
- Redis失败拒绝新AI调用；原公开文章/项目读取规则保留。原认证本身仍有既有Redis依赖，不夸大故障可用性。

### Day3：流式与前端读取

- `POST /api/admin/ai/summary/stream`复用摘要权限与配额。没有提前添加公开聊天或无依据的知识问答接口。
- 实现meta/delta/heartbeat/done/error事件，输入快照和请求ID由服务器提供。
- 收到供应商合法完成原因且摘要校验通过后才能发送done；缺失完成标记、输出截断、超长或格式错误发送error。
- 总时限60秒，不被不断出现的delta重置；心跳默认10秒。尚未收到内容时最多一次瞬态重试，输出后不重放。
- 前端独立POST fetch和SSE parser，处理UTF-8跨包、CRLF跨包、多事件合包、半帧EOF、大小限制与错误状态。
- 可传入AbortSignal；停止、页面离开、退出登录或清理登录状态取消流。Day4页面组件仍需在卸载时取消。
- 401仍触发既有认证失效回调，且不会因回调取消请求而丢失真实401状态。
- 浏览器注入的是单独打包的实际请求/用户状态模块，只用作测试；没有添加生产测试页面或提前改编辑器UI。

## 4. 实际发现与修复的问题

| 问题 | 原因 | 修复与证据 |
|---|---|---|
| 初次原业务验收集成测试无法启动 | Docker引擎未运行，不是代码编译故障 | 启动本机Docker Desktop后重新clean verify，原97项全部通过 |
| 异步成功/错误响应被变成401，SSE连接异常关闭 | 容器ASYNC完成分派没有原REQUEST的认证上下文，被安全链重复认证 | 仅允许内部ASYNC分派；原REQUEST继续JWT与方法权限检查；实际HTTP验证200/502/503/504与流结束 |
| SSE接口的建流前错误存在媒体类型风险 | 请求Accept为text/event-stream，但错误实体是普通JSON | GlobalExceptionHandler明确JSON媒体类型；真实Accept:SSE下400/403/429仍正确 |
| 供应商截断可能被当作完整摘要 | HTTP流关闭不等于模型完整生成 | 检查完成原因STOP，缺失/截断返回安全error；后端与浏览器负例通过 |
| 登录失效回调可能把401误变成AbortError | 清理登录状态会取消活动AI请求 | 保留已收到的ApiError，再处理主动取消；浏览器401+取消回调负例通过 |
| 有界流的清理断言存在时序差异 | Reactor finally在终止通知后执行，测试线程可能先观察到错误 | 验证最终清理使用短有界等待；真实停止、退出与后续请求进一步证明资源释放 |
| Windows测试模块导入失败 | Node动态import不接受裸盘符路径 | 使用pathToFileURL转换；不修改用户机器全局设置 |
| 测试客户端构建失败/未注册全局对象 | Vite8输出为数组；library模式保留process.env.NODE_ENV | 正确处理构建结果，定义测试bundle的生产环境和Vue编译标志；6项专项重新通过 |
| 一次clean验收被JAR占用中断 | 浏览器临时Java进程还在使用JAR，验收编排有误 | 修正为后端完整打包结束后启动浏览器；不强删占用文件、不跳过测试 |
| 新DeepSeek默认思考与短摘要额度不匹配 | 官方新模型默认开启thinking，可能在有限max_tokens内无法产生正文 | 官方DeepSeek默认发送reasoning_effort=none；配置单测、四项真实smoke及真实网站摘要通过；这是接入前发现的兼容风险，未声称发生过线上故障 |
| 百炼OpenAI SDK Base URL已含版本路径 | 直接追加Spring AI默认/v1/embeddings会重复/v1 | smoke根据基础地址末尾/v1选择/embeddings；明确float与1024维，实际向量长度及全部有限数值验证通过 |

## 5. 文件范围与diff摘要

本次修改12个已有文件，新增26个文件（包括本报告与真实模型浏览器专项）；原有13个文档删除不属于本次AI实现，不应混入以后提交。

| 范围 | 文件与作用 |
|---|---|
| 依赖 | `yufeichi-server/pom.xml`：AI BOM、模型库、Reactor测试依赖 |
| 错误/安全 | `common/error/ErrorCode.java`：AI503/504/502；`config/SecurityConfig.java`：ASYNC完成分派；`exception/GlobalExceptionHandler.java`：错误JSON媒体类型 |
| 后端配置 | `src/main/resources/application.yml`：显式AI变量与安全日志；`src/test/resources/application-test.yml`：隔离、默认关闭AI；新增`yufeichi-server/.env.example` |
| 后端AI新增10文件 | `ai/AiProperties.java`、`AiConfiguration.java`、`AiModelGateway.java`、`SpringAiModelGateway.java`、`AiProviderException.java`、`AiRequestGuard.java`、`AiSummaryService.java`、`AiSummaryController.java`、`SummaryRequest.java`、`SummaryResponse.java` |
| 后端测试新增6文件 | `AiIntegrationTests.java`；`server/ai/AiConfigurationTest.java`、`AiRequestGuardTest.java`、`AiSummaryServiceTest.java`、`AiWireFixture.java`、`LiveAiSmoke.java` |
| 后端原测试扩展 | `YufeichiServerApplicationTests.java`：AI关闭且无Key时原公开查询可用 |
| 前端请求/状态 | 新增`src/api/ai.ts`、`src/api/sse.ts`；修改`src/api/http.ts`、`src/stores/user.ts` |
| 前端测试 | 新增`tests-unit/sse.test.ts`、`tests/ai.spec.ts`、`tests/ai-live.spec.ts`、`tests/fixtures/ai-client.ts`；修改`package.json`、`playwright.config.ts` |
| 测试入口 | 修改`scripts/test-web.mjs`，新增`scripts/ai-test-provider.mjs`；E2E_AI=1启用模型桩，E2E_AI_LIVE=1才启用真实模型；两种模式互斥 |
| 文档 | 新增本报告与`docs/ai/README.md`：范围、接口、变量、运行、故障和真实验收边界 |

后端`ai/`等源码简写位于`yufeichi-server/src/main/java/com/yufeichi/server/`；测试简写位于`yufeichi-server/src/test/java/com/yufeichi/`；前端简写位于`yufeichi-web/`。

没有改动编辑器产品UI、已有Service/Mapper、迁移、线上Nginx或生产部署配置。根README缺失状态保持，AI运行说明单独新增。

## 6. 命令与验证

本轮实际执行过：

```powershell
git status --short
git branch --show-current
git rev-parse HEAD
git switch -c codex/ai-assistant
.\scripts\mvn21.ps1 -version
docker desktop status
docker desktop start
docker info --format '{{.ServerVersion}}'
.\scripts\mvn21.ps1 clean verify
.\scripts\mvn21.ps1 dependency:resolve
.\scripts\mvn21.ps1 dependency:tree '-Dincludes=org.springframework.ai:*,org.springframework.boot:*,org.springframework:spring-webflux,io.projectreactor:*'
.\scripts\mvn21.ps1 test-compile
.\scripts\mvn21.ps1 '-Dtest=AiSummaryServiceTest,AiConfigurationTest,AiIntegrationTests' test
pnpm --dir yufeichi-web install --frozen-lockfile
pnpm --dir yufeichi-web build
pnpm --dir yufeichi-web test:unit
$env:E2E_AI='1'
$env:E2E_PREVIEW='1'
node scripts/test-web.mjs ai.spec.ts
node scripts/test-web.mjs
Remove-Item Env:E2E_AI
Remove-Item Env:E2E_PREVIEW
git diff --check
# 补验：仅在进程内加载用户已有环境变量，不输出Key
$env:AI_SMOKE_ENABLED='true'
.\scripts\mvn21.ps1 '-Dtest=LiveAiSmoke' test
# 普通回归未加载真实Key，且LiveAiSmoke不被默认发现
.\scripts\mvn21.ps1 verify
$env:E2E_AI_LIVE='1'
$env:E2E_PREVIEW='1'
node scripts/test-web.mjs ai-live.spec.ts
Remove-Item Env:E2E_AI_LIVE
$env:E2E_AI='1'
node scripts/test-web.mjs
```

同时使用只读文件哈希比较核对migration。没有执行`-DskipTests`、Git提交、push、数据库clean/repair、线上SSH或部署。

| 验证 | 实际结果 |
|---|---|
| 原业务基线 | 97项后端通过，0失败/错误/跳过 |
| 完整后端 | 前轮clean verify119项通过；兼容调整后最终verify120项通过，0失败/错误/跳过；21:39:13完成并重新打包JAR |
| AI新增后端覆盖 | 22项：配置4、配额3、服务7、集成8；另增加原测试中的AI关闭回归1项 |
| 前端解析 | 6项通过，0失败/跳过 |
| 前端生产构建 | vue-tsc与Vite通过；原有大chunk提示仍存在，本轮未进行界面/打包重构 |
| AI浏览器专项 | 6项通过：真实JSON/SSE、取消恢复、截断、429模拟、真实退出撤销、401模拟 |
| 组合浏览器 | 兼容调整及入口修改后再次18项通过（原业务12项+AI6项），0失败/跳过，耗时54.0秒 |
| OpenAPI | 隔离Java真实返回openapi字段，浏览器UI通过；非生产验证 |
| 迁移与diff | V1—V10 SHA-256不变，git diff --check通过 |
| 私密值检查 | 扫描322个已有/新增源码、文档、前端dist、测试报告及日志文件；两套实际Key及私人Embedding基础地址均0处匹配；不输出匹配原文 |
| 真实供应商smoke | 4项通过，0失败/错误/跳过，21:34:37完成；普通Chat、JSON、SSE、Embedding1024维 |
| 真实模型浏览器 | 2项通过，0失败/跳过，5.7秒；JSON+SSE、首段取消+后续JSON；真实HTTP无Token401；21:40完成 |

`LiveAiSmoke`不在默认测试命名集合中，默认clean verify未运行它。这是分离真实付费验收，不是把缺失凭据的测试标成通过或静默跳过。

安全日志保存在Git忽略范围，例如根目录`ai-full-verify.log`、`ai-browser-tests.log`、`ai-browser-focused.log`、`ai-web-build.log`、`ai-dependency-tree.log`；补验为`ai-final-verify.log`、`ai-live-browser.log`、`ai-final-browser.log`和`yufeichi-server/target/ai-live-smoke.log`。原始Playwright trace和测试Token不应作为公开求职材料，需脱敏后再分享。真实模式关闭trace，Vite/Playwright移除所有AI环境变量。

### 真实模型证据与费用边界

- 使用明确标注的公开验收夹具，无私人文章、开发数据或生产数据。真实模型不是本地桩。
- 一条浏览器SSE样本收到50段delta；首段581ms、完整721ms，done摘要与累计内容和hash匹配。仅一个样本，不用作P50/P95或性能承诺。
- 后端取消记录：21:40:05.716，requestId `9e448a9e-857a-4305-867c-b0302aa15c90`，`outcome=CANCEL durationMs=645`；随后21:40:06.311的摘要 `ON_COMPLETE durationMs=604`。本地取消已传递；供应商是否立即停止推理或免收费用不由网站保证。
- 真实浏览器入口全局请求配额4，含重试；正常完成使用4条短Chat，smoke另3条Chat/1条Embedding且不重试。没有默认付费测试、充值、改模型名或静默切换供应商。
- 当日DeepSeek Flash峰值每百万token：未命中输入$0.30、缓存命中$0.006、输出$1.20，非高峰半价。每次1000输入/800输出的峰值未命中测算约$0.00126，非实际账单。[官方价格](https://api-docs.deepseek.com/quick_start/pricing/)。
- 北京Embedding每千输入token0.000125元；当前实际1024维通过。[官方接口与价格](https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api/)。查询日期2026-09-30。
- 本次成功调用证明当前账户可调用；未读取精确剩余额度、账单或免费额度，未设供应商侧费用硬上限。后续发布准备核对预算，不将请求配额等同人民币上限。

## 7. Checklist与待办

- [x] Java21与Maven实际运行版本确认。
- [x] Spring Boot版本保持、AI版本和依赖已解析。
- [x] AI关闭或供应商关键配置缺失时不调用模型，配置与隔离测试已建立。
- [x] 摘要权限、输入/输出、hash、不写业务库和错误状态通过本地模型桩验证。
- [x] 流式总超时、有限重试、不重放、取消与有界占用验证。
- [x] 6项AI浏览器专项与6项解析测试通过。
- [x] 最终配置调整后的120项完整后端与18项组合浏览器复核。
- [x] 已完成私密值检查，临时验收容器及进程退出，已有MySQL/Redis容器保留。
- [x] 真实服务商/模型、当日公开价格、API网络与当前可调用性已核对。
- [x] 真实Chat、结构化输出、SSE与Embedding1024维smoke四项通过。
- [x] 使用真实供应商通过摘要接口及前端流模块、取消与恢复验证。

用户已提供供应商、模型、地址约定、维度及本机环境变量名；真实验收已补齐，无需再发送Key。后续终端需继承用户环境，应用启用由AI_ENABLED控制，不能将本次隔离验收视为生产开关已开启。

当前人民币预算硬上限、多实例分布式并发、知识索引和生产SSE代理尚未实现，不能写成已交付。Day4的摘要预览/采用UI仍是下一阶段。
