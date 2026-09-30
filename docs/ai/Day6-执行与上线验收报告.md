# AI Day6：内容索引与发布验收

执行日期：2026-10-01（北京时间）。依据桌面《Yufeichi-AI应用构建与实习准备计划书》Day6，另按用户明确授权提交、推送、上线；本报告不把Day7知识问答标为完成。

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
| Java21 clean verify | 141项，0失败/错误/跳过，00:35:06完成 |
| 前端类型检查和生产构建 | 通过；保留原有大chunk提示 |
| SSE解析单测 | 6项通过 |
| 完整浏览器验收 | 28项通过，包含原业务与新增索引界面 |
| 索引专项 | 10项向量集成测试及1项分段单测通过；覆盖草稿/下架/隐藏/删除、重复、部分失败、可见性变化、配额、恢复和权限 |
| 真实Embedding索引 | LiveKnowledgeSmoke 1项通过，00:44:18完成；2个公开本地来源/1次Embedding批量调用，重复任务不新增行 |
| 当前生产旧JAR兼容V11 | 独立MySQL/Redis中启动、登录、文章读写通过；迁移历史不变；旧JAR SHA256与服务器一致 |

专项测试发现默认Token分批会将一次配额对应多次模型请求，已指定业务有界批次对应单次HTTP调用。完整回归中的旧迁移断言固定10条，已更新为V11；没有通过修改旧迁移绕过校验。模型夹具的超长响应原本逐段延迟，偶发先触发总超时，改为及时发送以独立验证非法输出，保留原超时用例。

本地样本是测试明确构造的8篇文章、2个项目，外加草稿、下架、删除、隐藏项目负例；只进入随机独立测试数据库。真实Embedding用两条短公开验收文本，私密负例不会发送。它们不是线上真实文章、知识质量评测或个人经历。

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
- [ ] 审查并提交、推送GitHub（完成后记录编号）。
- [ ] 新版本上线、真实HTTPS摘要/索引、PG备份恢复（完成后记录证据）。

公开知识问答、自动内容生命周期同步、Tool/Agent和后续评测尚未开发，保持原计划范围。
