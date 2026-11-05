# AI 测试入口与证据边界

更新：2026-11-05。最终数量、时间和结果见[Day12—14执行报告](Day12-Day14-执行与验收报告.md)。

## 免费的默认验收

```powershell
.\scripts\mvn21.ps1 -version
.\scripts\mvn21.ps1 clean verify
pnpm --dir yufeichi-web test:unit
pnpm --dir yufeichi-web build
$env:E2E_AI='1'; $env:E2E_PREVIEW='1'; $env:E2E_NGINX='1'
node scripts/test-web.mjs
```

默认后端测试连接新建Testcontainers MySQL8.4、Redis7，向量专项另建PG/pgvector。模型全部使用loopback桩，不需要真实Key，也不连接开发/生产数据库。第一次拉取镜像和Maven依赖可能需要网络，不能把“不调用外部模型”说成完全不需要任何网络。测试不使用skipTests，不因缺Docker而标成通过。

浏览器使用已构建dist、真正Java应用和独立MySQL/Redis。`E2E_AI=1`的供应商是本机协议桩，真实HTTP/SSE、工具参数分片与权限仍实际执行；模拟结果不当成模型质量证据。新增Nginx模式使用真正自签HTTPS代理，并检查首个摘要delta早于结束、心跳、文档隐藏、停止、并发满时429和公开内容仍响应。

Windows上浏览器运行时Java会持有JAR，必须先结束该入口再clean/repackage；发现锁定错误时保留日志并重跑，不手工删JAR或使用跳过测试。中断测试若未能finally清理，仅根据该次随机名称/标签确认归属后清理自己创建的容器，不枚举删除用户已有容器。

## 故障矩阵

| 故障/边界 | 实际验证位置 | 断言 |
|---|---|---|
| 模型401/429/500 | AiIntegrationTests + AiWireFixture | 安全503、不回传私有正文；401/429不重试，500有界；恢复可用 |
| 瞬态503 | 原摘要协议/服务测试 | 未输出前最多重试1次、额外计全局预算；已输出不重放 |
| 模型总超时 | 摘要/RAG/Agent集成 | 504或已建流error、无done，取消后下一次可用 |
| PG真实暂停 | AiKnowledgeIntegrationTests | 有界失败（socket上限30秒）、不发Chat；公开原文200；解除暂停后原连接池恢复 |
| Redis真实暂停 | AiKnowledgeIntegrationTests | 新AI调用拒绝、Chat/Embedding零调用；公开文章查询200；恢复后可用；不声称登录不依赖Redis |
| 配额/并发 | guard单测、HTTP集成、ai-nginx.spec.ts | 原子预算、超额429、两条在途AI请求占满时原内容查询仍响应；停止后恢复 |
| 用户停止/离页/退出 | Agent真实TCP测试、摘要/RAG服务与浏览器 | 上游连接关闭、许可释放、未完成答案不作为成功 |
| AI总开关关闭 | 配置/HTTP/浏览器 | 无模型配置也能启动；端点503、原浏览与编辑保存保持原规则 |
| 无权限/旧Token | 原认证和各AI集成/浏览器 | HTTP401/403；模型之前拒绝；不扩大内容权限 |
| 内容改动/隐藏/删除/重启 | Day8—9集成与现有真实smoke记录 | 旧片段不发模型、提交不回滚、有限补偿与对账、清理仅限可识别自身片段 |
| 伪造来源/注入/非法工具 | Day8—11自动测试、Day13真实题集 | 不接受外部网址、未知引用和写工具；边界无答案，语义质量另行评估 |

PG/Redis注入只暂停/恢复本次新建容器，不操作本机长期服务或生产；finally解除暂停。默认所有旧登录、JWT撤销、文章/项目、上传、Markdown与OpenAPI回归保留。

## 真实模型与发布预演

- `LiveAiEvaluation`：显式AI_EVAL_ENABLED=true；固定30题、两组Top-K、125调用名额硬上限，逐条落盘，未过门槛退出失败；正常构建不执行。
- `ai-showcase-live.spec.ts`：显式E2E_AI_DEMO=1且Nginx隔离模式，12请求名额上限；真实三场景短录屏/截图，禁止trace，所有内容为夹具。
- 同入口验证非空备份恢复、迁移checksum、1024维和上传文件字节；只还原到无网络/无端口的新容器。
- `E2E_ROLLBACK_JAR`：只在AI关闭模式，当前JAR先应用V12，再停止并替换为实际历史JAR，对同一个隔离库验证登录、写/读和checksum不变。
- 最终候选包逐文件SHA256和秘密扫描由build-ai-release.py执行；扫描使用环境变量实际值，不把值写入日志。

以上都不代替真实生产HTTPS、生产非空内容、最新备份、容量、账户预算及域名证书验收，也不证明个人已经独立掌握全部实现。
