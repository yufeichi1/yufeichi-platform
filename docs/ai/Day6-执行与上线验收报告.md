# AI Day6：内容索引与发布验收

执行日期：2026-10-01—2026-10-02（北京时间）。依据桌面《Yufeichi-AI应用构建与实习准备计划书》Day6，另按用户明确授权提交、推送、上线；本报告不把Day7知识问答标为完成。

## 开发结果

- 新增公开文章/项目抽取、段落切片、来源metadata和SHA256 hash。只读取status=1且deleted=0的业务内容，链接由服务端生成。
- 新增MySQL V11任务/状态表；既有V1—V10原文件不变。PG只新增V2版本查询索引，原PG V1不变。
- 新增受现有super_admin保护的重建/状态接口和AI工作台按钮。排队、进度、成功、失败可见；最多一个任务。
- 新版本先写入，逐项校验ID/正文/metadata并重读来源，完整成功才切换；失败保留旧版本。同内容重复执行复用版本，不增加片段、不再调用模型。
- 模型调用在业务事务外执行。每批20片段/1024字符，共享Chat全局配额与并发；索引另限UTC每日10批/100000字符，最多100片段/任务，单任务300秒、每次HTTP60秒，无自动付费重试。
- MySQL连接锁防多实例重复执行；进程中断后任务标记INTERRUPTED，管理员可以重建。业务保存与自动索引联动、旧版本清理仍属Day9。

## 验证及发现的原因

| 检查 | 实际结果 |
|---|---|
| Java21 clean verify | 142项，0失败/错误/跳过，2026-10-01 01:13:03完成 |
| 前端类型检查和生产构建 | 通过；保留原有大chunk提示 |
| SSE解析单测 | 6项通过 |
| 完整浏览器验收 | 28项通过，包含原业务与新增索引界面 |
| 索引专项 | 11项向量集成测试及1项分段单测通过；覆盖草稿/下架/隐藏/删除、重复、部分失败、可见性变化、配额、恢复和权限 |
| 真实Embedding索引 | LiveKnowledgeSmoke 1项通过，00:44:18完成；2个公开本地来源/1次Embedding批量调用，重复任务不新增行 |
| 当前生产旧JAR兼容V11 | 独立MySQL/Redis中启动、登录、文章读写通过；迁移历史不变；旧JAR SHA256与服务器一致 |

专项测试发现默认Token分批会将一次配额对应多次模型请求，已指定业务有界批次对应单次HTTP调用。完整回归中的旧迁移断言固定10条，已更新为V11；没有通过修改旧迁移绕过校验。模型夹具的超长响应原本逐段延迟，偶发先触发总超时，改为及时发送以独立验证非法输出，保留原超时用例。

本地样本是测试明确构造的8篇文章、2个项目，外加草稿、下架、删除、隐藏项目负例；只进入随机独立测试数据库。真实Embedding用两条短公开验收文本，私密负例不会发送。它们不是线上真实文章、知识质量评测或个人经历。

最终审查还发现最后一批Embedding返回时若已超过总时限，原流程仍可激活。已在批次返回后、完整性校验后和最终切换前检查总时限，并新增单批延迟1.5秒、总时限1秒的真实数据库集成用例：任务失败TIMEOUT且没有激活版本。新增AI_INDEX_TIMEOUT_SECONDS（默认300，范围1—300）；进行中的HTTP仍受60秒请求超时约束。

## 命令记录

```powershell
git status --short
git ls-remote --heads origin
git fetch origin
ssh -T -o BatchMode=yes git@github.com
.\scripts\mvn21.ps1 '-Dtest=AiKnowledgeSourcesTest,AiVectorIntegrationTests' test
.\scripts\mvn21.ps1 clean verify
pnpm --dir yufeichi-web test:unit
pnpm --dir yufeichi-web build
# 单独进程加载用户Embedding环境变量，AI_SMOKE_ENABLED=true
.\scripts\mvn21.ps1 '-Dtest=LiveKnowledgeSmoke' test
# E2E_ROLLBACK_JAR指向与当前服务器哈希一致的旧JAR；模型关闭
node scripts/test-web.mjs
# 另一个进程E2E_AI=1、E2E_PREVIEW=1，无回滚/真实开关
node scripts/test-web.mjs
git diff --check
```

本地日志在Git忽略范围：ai-day6-focused/full/web-unit/web-build/browser/rollback/live-index.log。最初Docker未启动、旧断言及夹具延迟的失败已修正并重新验收；未使用skipTests。

## 发布准备与边界

已只读核对现有服务器，MySQL8.4/Redis7、Java21、网站正常。服务器3723MiB内存、约2160MiB可用、27GB磁盘空闲。独立PG限制512MiB/0.5CPU、最多20连接，仅绑定127.0.0.1:15432，应用池2连接。

发布前正式备份：`backup-20260930T161425Z-843791.tar.gz`，内部数据库、uploads、配置和迁移校验全部通过。服务器无法直接访问Docker官方仓库，传输已验证镜像，双方文件哈希一致；不是换用未知镜像。

增加生产PG Compose、受保护配置导入、HTTPS烟测、PG备份和独立SSE Nginx配置。激活失败会恢复前后端current与Nginx，应用回退保留V11/PG卷。生产恢复脚本接受新增PG备份，但MySQL恢复不能冒充PG恢复验证。

管理员凭据最初登录失败，核验旧admin被禁用，实际角色账号为yufeichi-owner。仅在用户明确授权后重置这个账号的密码；本机admin-credentials.json已更新并限制ACL，真实登录成功。报告不保存密码，原禁用admin未启用。密码更改没有轮换全站JWT密钥，未宣称所有之前签发的Token立即失效。

## Checklist

- [x] 公开来源抽取、段落切片、metadata/hash。
- [x] 任务/状态持久化与管理员入口。
- [x] 草稿/隐藏/删除不送Embedding。
- [x] 同内容重建不新增片段。
- [x] 失败可见，新版本失败不切换。
- [x] 独立环境完整回归及真实Embedding。
- [x] 既有迁移与.vscode保留；原有13项文档删除不混入本次提交。
- [x] 审查并提交、推送GitHub；代码提交6583af6、部署提交3ee9349、超时修复及验证脚本11b70f0。
- [x] 新版本上线、真实HTTPS摘要/索引、PG备份恢复及异机备份校验。

公开知识问答、自动内容生命周期同步、Tool/Agent和后续评测尚未开发，保持原计划范围。

## 最终上线记录（2026-10-02，北京时间）

Day6全部验收通过。代码已推送origin/codex/ai-assistant，没有强推、合并主分支、移动V1.0标签或覆盖远程历史。

| 项目 | 实际证据 |
|---|---|
| 最终生产源码 | 11b70f0bdd146c4dc95f7483692d8da0356958d1 |
| 最终release | 20261002T153303Z-11b70f0（北京时间23:33构建、23:35完成验收） |
| 实际JAR | yufeichi-server-0.0.1-SNAPSHOT.jar；发布目录统一名yufeichi-server.jar；Java21 |
| JAR SHA256 | bde969f212fe00213be6a616659fa06ab65f532e151b4c2d10ef4f1cb4487649；上传安装后与本机一致 |
| 发布包SHA256 | 3e789fee5b126b18a9a86d1460d489dccf5226785260e585f403fa27cff9fe85 |
| 前后端current | 均指向最终release；前一AI release 20260930T165015Z-3ee9349和V1.0 release保留 |
| 真实HTTPS接口 | AI_PRODUCTION_ACCEPTANCE_PASS；登录/me、缺Token401、空摘要400、公开health/articles/projects、退出通过 |
| 真实Chat SSE | 最终验收30帧/27个delta、meta/done齐全、无error、首帧小于3秒；没有业务内容写入 |
| 索引重建 | 两次均成功，active_version=d8455461-fc81-491f-9058-f0a3e0493511，重复不切换/不新增 |
| 线上来源现状 | 0篇公开文章、0个公开项目，0片段；没有为了验收插入假文章 |
| 真实浏览器 | AI_BROWSER_LIVE_PASS；真实登录、摘要生成/采用、索引状态、375px移动端、刷新清空输入、退出旧Token401、无JS异常 |
| 真实Embedding | 此次Day6本地索引1次批量调用通过；服务器直接1次公开文本调用返回1024维，无数据库写入。线上空索引未调用Embedding |
| 数据迁移 | MySQL V1—V11全部成功；V1—V10 checksum与发布前完全一致；PG独立schema V1/V2与1024维一致 |
| 运行状态 | 应用active，NRestarts=0，Nginx正常，备份timer active；PG只在127.0.0.1:15432；最终约2064MiB可用内存 |

最终发布前备份：backup-20261002T153435Z-2921204.tar.gz。发布后备份：`/var/backups/yufeichi/backup-20261002T153558Z-2922792.tar.gz`，整体SHA256 `25fb449c59c506451fcacebfffd25f62af0666cc7e9566bbdcf78b018cce09e4`。数据库、uploads、配置、迁移记录、PG dump及全部内部文件哈希通过。PG dump实际导入独立、无网络、无端口、256MiB的临时容器，2条版本迁移/1024维校验通过，0行与线上空索引相符，临时容器已回收。这证明当前备份恢复流程，不冒充非空知识质量评测。

异机副本位于`D:/Desktop/yufeichi/yufeichi-backups/backup-20261002T153558Z-2922792.tar.gz`，整体及内部文件哈希全部一致；ACL仅本人/SYSTEM/Administrators。之前backup-20260930T170158Z-878082的异机与独立PG恢复也已通过。

实际服务器操作使用受信SSH与sudo执行，凭据通过受保护文件传输：

```bash
sudo bash /home/ubuntu/yufeichi-ai-staging/prepare-final.sh
sudo bash /opt/yufeichi/deploy/activate-release.sh 20261002T153303Z-11b70f0
sudo python3 /opt/yufeichi/deploy/ai-acceptance.py
sudo bash /opt/yufeichi/deploy/backup.sh
sudo python3 /opt/yufeichi/deploy/verify-ai-vector-backup.py /var/backups/yufeichi/backup-20261002T153558Z-2922792
```

```powershell
node deploy/production/ai-browser-acceptance.mjs D:/Desktop/admin-credentials.json
git push origin codex/ai-assistant
```

发布操作在短暂应用重启后健康通过；准备及激活均退出0。最终受保护backend.env为root:root 600，模型Key没有写到前端、Git或发布包。新增暂存文件、JAR解压内容与前端产物均通过实际Key/专属Embedding URL扫描，git diff --cached --check通过。

本机临时providers/admin/reset副本及服务器临时admin文件/备份中转副本已移除。用户原`D:/Desktop/admin-credentials.json`和正式备份保留；无需再次重置管理员。临时凭据不进入正式备份。未修改原有13项文档删除，未提交或删除.vscode。

浏览器日志：ai-day6-production-browser-final.log；截图位于`C:/Users/yu/AppData/Local/Temp/yufeichi-ai-day6-browser/ai-live-desktop.png`及`ai-live-mobile.png`。完整142项回归日志：ai-day6-final-full.log。所有测试均未跳过。

截至代码提交11b70f0，相对3e59b33，本轮累计82个文件，4484行新增、17行删除，包含之前AI Day1—5与Day6改动；最终报告随后单独文档提交，不影响运行产物。GitHub分支：https://github.com/yufeichi1/yufeichi-platform/tree/codex/ai-assistant 。

尚未完成的是后续计划：Day7带引用知识问答、Day8边界强化、Day9自动同步、Day10/11只读工具助手。线上非空索引需有真实公开内容后再验证；目前Day6的非空管线已在独立数据库实测通过。
