# Day14 正式发布与线上验收报告

日期：2026-11-05（Asia/Shanghai）。本报告记录在最初Day12—14本地验收之后，用户明确要求Git最终审查、提交、推送，以及生产备份、资源检查、正式部署和线上验收的结果。

**结论：本次正式发布已完成，现有生产内容范围内的HTTP和真实浏览器验收通过。** 用户要求不继续单独调查此前本机代理503，已按该范围执行；没有把该503写成“已定位修复”。本次生产验收没有发现对应故障。

## Git审查、提交与产物

- 分支：`codex/ai-assistant`；远程 `git@github.com:yufeichi1/yufeichi-platform.git`。GitHub SSH登录为yufeichi1，分支未受保护，起始本地/远程均为6694f89；采用正常快进推送，无强推、覆盖远程、合并main或移动V1.0标签。
- [ab5fafd：功能与测试](https://github.com/yufeichi1/yufeichi-platform/commit/ab5fafd)：73文件，4003行新增/42行删除，覆盖公开内容RAG、索引同步、只读工具/Agent、隔离/故障测试及发布预演工具。
- [7ce1cef：文档与证据](https://github.com/yufeichi1/yufeichi-platform/commit/7ce1ceff140ba1076cbaf64218ae94fb9a3ee0a8)：43文件，保存Day7—14报告、真实评测及安全演示材料。CSV仅规范多余CR换行，逐项比较61条记录/全部字段不变。
- 原有13项文档删除和未跟踪`.vscode`保留，均未暂存/提交。没有提交本地环境配置、口令、私钥、模型Key或生产备份。
- 核对实际实施源文件与本地已测试候选包的源树hash一致；运行JAR与前端字节没有变更。本轮未重复完整本地回归；最近一次完整结果为后端230、浏览器47、前端单元6全部通过，另有最终2项代理专项。

| 项目 | 实际记录 |
|---|---|
| 正式发布源码 | `7ce1ceff140ba1076cbaf64218ae94fb9a3ee0a8`；功能提交ab5fafd |
| release | `20261105T120255Z-7ce1cef`，北京时间20:02:55创建；前后端current均指向此release |
| 上一release | `20261002T153303Z-11b70f0`，完整保留 |
| 原始JAR名 | `yufeichi-server-0.0.1-SNAPSHOT.jar`；服务器统一为`yufeichi-server.jar` |
| JAR SHA256 | `ca0bad79580f2d52be7a837129b16ec734654107b1c056b091af32bdcffe1237` |
| 正式发布包SHA256 | `bf2f478b2995cae6f7fad9a8ffa38735db086d0f0030050fd35826e8393a9e4f` |
| Java | 本机Maven21.0.11；服务器原有OpenJDK21.0.12.1，本次没有安装/升级JDK |
| 其他运行依赖 | Boot3.5.16 / Spring AI1.1.8 / MySQL8.4.11 / Redis7.4.10 / PG17.11、pgvector0.8.6；保持原部署 |

本报告随后单独提交，不改变已上线运行产物或其源码版本。

## 发布前资源与备份

生产原服务active、NRestarts=0；约3723MiB总内存、2030MiB可用，磁盘26GiB可用。MySQL/Redis/PG持续运行约5周且healthy，绑定回环3306/6379/15432。Nginx配置检查通过、HTTPS正常、备份timer active。

| 备份 | 归档名 | 整体SHA256 |
|---|---|---|
| 发布前 | `backup-20261105T115458Z-756119.tar.gz` | `fb152d986ad5aad17995ddbb2dad8ab5a65e87aa55fdea591792d970d3fc116e` |
| 发布后 | `backup-20261105T121012Z-769956.tar.gz` | `84495b50f7b1200d9e7b16603d1ed439e9bfd0bcfc1db8e500b1da29f912365d` |

两包均位于`/var/backups/yufeichi/`，覆盖MySQL、PG、uploads、受保护配置、迁移及manifest；整体和6项内部hash均通过。通过受信SSH复制至本机`D:/Desktop/yufeichi/yufeichi-backups/release-20261105/`的predeploy.tar.gz/postdeploy.tar.gz，再次逐项验证一致。Windows目录ACL仅本人、SYSTEM、Administrators；备份包含生产秘密，不进入Git。

发布前、后分别将SQL导入本次新建的无网络/无端口MySQL容器，比较账号/角色关系、文章/项目/文件数量和迁移success/checksum，逐项核对8/9条上传元数据对应的归档文件字节，配置归档可读。PG分别独立恢复，2条迁移、1024维和0行向量通过。所有恢复容器已回收，未向生产库恢复任何数据。

## 实际发布次序

1. 校验本机正式发布包与服务器受保护副本SHA256；解包只接受普通文件和受控相对路径，再按manifest校验逐文件hash。
2. 新建release目录，安装JAR/dist和Nginx模板，保留旧release；备份原环境、Nginx及current目标，放在root-only审计目录。
3. 将AI及子开关关闭，使用既有activate-release.sh切换并重启应用；先验证HTTPS健康、文章/项目读取、登录，以及AI端点503。启动轮询期间短暂连接拒绝属于应用重启过程，最终健康通过。
4. 开启AI、向量、知识问答、匿名公开问答、只读工具/Agent、索引同步，设置已评测Top-K=5，再重启并验收。并发上限2、已有全局100次/UTC天等预算保持；匿名3次/分钟、10次/天，工具/Agent要求登录，摘要沿用文章管理权限。
5. MySQL仅应用新增V12；V1—V11的success/checksum逐行与发布前备份一致。PG原独立V1/V2和1024维空间保持，未执行Flyway clean/repair、数据库倒退、容器重建或删除卷。
6. 真实模型、HTTP、浏览器、停止取消及上传验收通过后，制作发布后备份并完成独立恢复/异机校验。

## 线上验收结果

| 范围 | 实际通过内容 |
|---|---|
| 基础业务与认证 | 公开health/articles/projects、正确登录、错误密码401、空JSON400、me；篡改/过期JWT401，退出后旧Token401 |
| AI权限与参数 | 摘要缺Token401、空输入400；未登录Agent/Tool401；合法管理员可访问索引和摘要 |
| 关闭AI | 新版本AI关闭阶段原业务正常，摘要503；随后受控开启 |
| 真Chat / HTTPS SSE | 摘要65帧、62个delta；首帧约0.081秒、首个delta约1.189秒、完整约1.474秒；收到done，无error。单样本，不是性能承诺 |
| 真实Embedding | 服务器1次公开验收文本调用返回1024维，无业务/向量写入；供应商错误细节不输出 |
| 索引 | 2次手动重建成功且版本/片段数保持一致，0公开源/0片段不调用Embedding |
| 固定Tool | 查询真实生产业务服务，正确返回没有公开结果，无模型调用 |
| 真Agent | 2轮模型、1次搜索工具；无公开结果时answered=false/sources=[]；实际步骤与供应商可得token元数据见原始JSON |
| 匿名问答与配额 | 3次空索引问题返回无依据；第4次429；每次伪造不同X-Real-IP/X-Forwarded-For仍不能绕过真实Nginx覆盖和可信代理规则 |
| 生产边界 | OpenAPI/Swagger/doc.html均404，外部Origin预检403；Nginx配置正常，数据库端口保持回环 |
| 实际上传 | 上传1张8×8验收PNG，经Nginx读取尺寸/Content-Type正确；无文章/项目写入。没有文件删除API，保留该张标记用途的运维图片，未直接删生产数据 |
| 真实浏览器 | 登录、摘要预览/采用不保存、Agent查询与步骤、固定工具、375px、匿名问答无虚构来源、刷新清空、退出撤销、无页面JS错误 |
| 真实停止 | 浏览器收到首个delta后取消，requestId `ef40639a-8e1f-4fff-b68e-1cdb97be9f01`；生产app.log记录summary/CANCEL，约1063ms结束，随后health正常。并非只检查客户端提示 |

生产日志由Logback写入`/var/log/yufeichi/backend/app.log`，不是journal；取消证据按精确requestId从实际日志匹配。只保留请求ID、操作、结果和耗时，没有Prompt、Token或Key。

验收后服务active、NRestarts=0，cgroup内存约384MiB，可用内存约2243MiB；HTTPS证书验证通过，有效期至2026-12-24 07:06:24 GMT。以上是当次快照，不代表容量测试。

临时服务器管理员文件和传输中转副本已移除；正式备份、旧release和受保护回退环境快照保留。管理员账号密码未重置，供应商Key仍仅在后端受保护配置。

## 证据、入口与已知边界

- [生产原始安全证据](evidence/production-20261105.json)：发布版本、HTTP/模型/取消、前后备份恢复/异机验证、浏览器结果。
- [生产摘要截图](evidence/production-summary.png)、[助手截图](evidence/production-agent.png)、[匿名问答截图](evidence/production-public-knowledge.png)、[手机截图](evidence/production-mobile.png)。登录密码页未截图，没有认证trace。
- [站内问答](https://yufeichi.com/ai/knowledge)、[只读助手](https://yufeichi.com/ai/assistant)、[固定查询](https://yufeichi.com/ai/tools)、[摘要工作台](https://yufeichi.com/admin/ai)。后三者需要对应登录/权限。
- 日志：production-browser.log、production-cancel.log；服务器安全审计`/var/lib/yufeichi/release-audit/`，只有root可读；正式备份恢复日志未公开真实配置。

**当前公开文章与项目均为0。** 因而本次生产RAG只能验空索引/无答案/配额，生产Agent只能验证搜索后无结果。非空“检索→模型回答→原文引用”和“搜索→详情→回答”已在隔离真实模型验收通过，但不能冒充非空生产验收；需站长发布真实内容并同步索引后补验。未插入虚构履历文章，也没有启用私密/草稿来凑数据。

生产没有创建普通测试账号、禁用真实用户或注入PG/Redis故障；这些边界沿用已通过的独立回归。此前本机503根因依用户要求不继续调查，结论仍为未定位；本次真实生产链路全部上述检查通过，不承诺永不发生故障。

回退保留`20261002T153303Z-11b70f0`。需要回退时先做新的正式备份，关闭AI/自动同步，再恢复对应受保护环境、前后端current和Nginx快照；重启验原业务，保留V12与PG数据卷，不反向执行migration。旧Day6代码对V12的启动/登录/读写已在隔离库实测，不将其推广到任意历史版本。
