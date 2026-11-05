# Day14 发布预演与正式上线步骤

日期：2026-11-05。本文保留最初的隔离预演与发布步骤。后续用户已明确授权Git提交/推送及生产发布，现已完成，见[正式发布与线上验收报告](Day14-正式发布与线上验收报告.md)。历史Day6记录与本次真实生产验收分别保留。

## 可复用的发布边界

- JDK固定21，实际构建JAR `yufeichi-server-0.0.1-SNAPSHOT.jar`，服务器沿用 `yufeichi-server.jar`。
- MySQL保留业务数据源；V1—V11不变，V12为Day9已新增同步表。本轮不增加/编辑migration，不执行clean/repair、不降级schema、不删除卷。
- Chat/Embedding密钥仅由后端受保护环境读取；百炼专属Base URL同样不进入Git。Java不自动加载.env，systemd读取root:root 600的backend.env。
- 所有开关默认false：`AI_ENABLED`、`AI_KNOWLEDGE_ENABLED`、`AI_PUBLIC_ENABLED`、`AI_TOOLS_ENABLED`、`AI_AGENT_ENABLED`、`AI_INDEX_SYNC_ENABLED`。总开关false先验原业务；再逐项开启。向量开关、URL、账号密码仍单独配置。
- 新映射 `AI_KNOWLEDGE_TOP_K`→`app.ai.knowledge-top-k`，范围1—10，兼容默认6。本次只比较3和5，正式开启可显式选择其一；相似度仍0.5。受控工具保持JWT登录，匿名问答默认关闭。

## 隔离Nginx预演

入口 `scripts/test-web.mjs` 的 `E2E_NGINX=1` 模式从真实production/nginx.conf派生配置，只替换测试域名、上游地址、证书路径，移除本机没有的Let's Encrypt辅助文件。使用两日有效自签localhost证书、随机回环HTTPS端口、只读挂载dist与uploads；仅浏览器测试上下文忽略该证书错误，不改系统信任。测试镜像固定摘要，尚未批准将该镜像替换线上Nginx。测试环境额外允许https://localhost和https://127.0.0.1的回环Origin；只有test且不含prod时生效，生产域名白名单完全不变。

Nginx三条SSE端点为摘要、知识问答和Agent；HTTP/1.1、禁缓冲/缓存/gzip、130秒读时限。普通API保留30秒；OpenAPI被代理隐藏。修改不自动复制到线上。

```powershell
# 先完成Java clean verify和前端build；以下不调用真实模型。
$env:E2E_AI='1'; $env:E2E_PREVIEW='1'; $env:E2E_NGINX='1'
node scripts/test-web.mjs
```

真实三场景演示需先安全加载已有Chat/Embedding变量；独立数据库、明确合成公开内容，最多12个供应商请求名额，不写线上：

```powershell
$env:E2E_AI_DEMO='1'; $env:E2E_NGINX='1'; $env:E2E_PREVIEW='1'
Remove-Item Env:E2E_AI -ErrorAction SilentlyContinue
node scripts/test-web.mjs ai-showcase-live.spec.ts
```

演示记录真实摘要预览/人工采用/手动保存、公开索引、RAG原文链接、模型搜索→详情→回答、375px页面；trace关闭、API登录不在录像输入密码。视频/截图在本机临时证据目录，不把测试夹具写成个人真实经历。

演示成功后同一入口导出非空MySQL/PG，校验哈希，再恢复到无网络/无端口的两个新容器，比较迁移checksum、文章数、向量数、维度和上传文件字节。临时备份只含已知测试账号与内容，**不能替代正式发布前的生产备份**。容器与子进程由入口finally清理；临时证据和自签证书不进入候选包或Git。

## 关闭和回退

1. 紧急停AI：受保护backend.env设置AI_ENABLED=false，必要时同时关闭六个子开关；由运维重启应用。验证health、公开文章/项目、真实登录、上传；AI端点清晰503，不删除索引/业务数据。
2. 局部降级：保持摘要、关闭问答或Agent；固定只读Workflow不产生Chat费用，但仍需AI/Tool开关及认证配额。不要把关键词查询标成向量RAG。
3. 应用回退：先关闭AI与自动同步，保留新schema、PG卷与配置备份，将前后端current原子切回已保留的旧release，恢复匹配的Nginx配置，重启后验原业务。
4. 旧JAR新schema必须实测。本轮从HEAD 6694f89（Day6代码与报告）只读导出独立目录，不修改当前工作区，完整142测试后用该JAR连接新应用已迁移的隔离V12库，验证登录、写/读文章及全部迁移checksum保持不变。它证明该历史版本兼容，不等于任意旧JAR都可回滚。
5. `activate-release.sh`只覆盖current链接、保留release，失败自动回退；它依赖既有服务器布局、Java21、ops锁和备份路径。正式发布前必须复核这些前提，不能直接对未知服务器执行。

## 候选包与正式发布门槛

`scripts/build-ai-release.py`只打包现有验证产物，不部署。它检查完整构建、评测、47项Nginx浏览器、旧JAR预演记录，并扫描实际Key、专属Embedding URL、私钥头；输出tmp/ai-day14-candidate-<sourceHash>.zip及SHA256。包含后端JAR、前端dist、模板、部署脚本、交付文档和manifest。manifest保留base HEAD、dirty状态、源文件树hash及逐文件hash；**未提交候选包不冒充某个已发布Git SHA**。

本次正式发布已获用户明确要求并完成；后续版本沿用以下次序：

1. 审查本次diff；保留原13项文档删除与.vscode，不混入提交。明确暂存范围、分支、远程和GitHub登录，不强推；记录真实提交SHA再生成正式发布包。
2. 只读核对当前线上release、CPU/内存/磁盘、PG空间、端口、防火墙和证书；本机资源快照不能代替生产容量检查。
3. 取得并校验**本次发布前**生产MySQL、uploads、配置、PG备份，PG与业务库均独立恢复；校验受保护异机副本。历史Day6备份不冒充今天的新备份。
4. 上传并核对哈希；先AI关闭发布，验health/首页/文章/项目/登录/上传；再受控开启向量与问答/Agent，手动重建真实公开内容。没有真实内容时明确空索引，禁止插入虚构履历文章凑验收。
5. 通过真实域名HTTPS检查SSE分段/心跳/停止、RAG原文、普通用户与匿名开关/可信代理配额、工具受控、关闭AI与回退；生产文档/DEBUG继续关闭。
6. 记录实际release/SHA/JARhash、模型与索引版本、资源和烟测，才写“已上线”。未执行上述生产步骤时状态始终为“本地开发/部署预演验收，待正式上线”。

官方依据于2026-11-05核对：[Nginx代理缓冲](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_buffering)、[读超时](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_read_timeout)。read_timeout是两次上游读之间的时限，不能代替应用总截止时间；关闭缓冲也不能替代浏览器实际分段检查。
