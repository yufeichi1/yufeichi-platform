# Day7 恢复、回滚与 V1.0 交付验收报告

日期：2026-09-29

Day6 Git 基线：`7deb160`

生产 Release：`20260928T110832Z-7c24911`

## 正式备份

- 服务器：`/var/backups/yufeichi/day7-20260929T102727Z`
- Windows：`D:\Desktop\yufeichi-backups\day7-20260929T102727Z.tar.gz`
- 异机包 SHA-256：`d90c6d05a7fe82b73e5145d98018215b1e33b490447ebca4f60232e6c9bd1dcb`
- OFFSITE_BACKUP_VERIFY=PASS

## 独立恢复

- database dump 导入：PASS
- schema 表数量：18
- Flyway V1～V10：PASS
- 文章记录：2
- 文件记录：2
- 用户记录：2
- uploads 文件数量：2
- uploads 相对路径与逐文件内容哈希：PASS
- DAY7_INDEPENDENT_RESTORE=PASS

恢复使用独立 MySQL 8.4.11 临时容器和临时 uploads 目录，没有覆盖生产库。

## 回滚演练

从 Git `31a551c` 构建上一兼容版本并部署为历史 release。

旧版验证：

- backend health：HTTP 200
- HTTPS 前台：HTTP 200
- HTTPS API：HTTP 200
- 数据库仍为 Flyway V10

恢复当前版本后：

- backend health：HTTP 200
- HTTPS 前台：HTTP 200
- HTTPS API：HTTP 200
- MySQL / Redis healthy

- ROLLBACK_TO_31A551C=PASS
- ROLLBACK_TO_CURRENT=PASS
- DATABASE_SCHEMA_UNCHANGED=PASS
- DAY7_ROLLBACK_REHEARSAL=PASS

## 最终构建门禁

- Temurin 21.0.11
- Docker Desktop 29.6.2
- Maven clean verify：97 tests / 0 failures / 0 errors / 0 skipped
- BUILD SUCCESS
- JAR：56,077,868 bytes
- pnpm frozen install：PASS
- vue-tsc + Vite build：PASS
- dist：32 files / 1,609,123 bytes
- git diff --check：PASS

## 已知非阻塞警告

1. 当前 Flyway 会提示 MySQL 8.4 高于其声明测试到的 8.1；本项目已经实际通过 MySQL 8.4 空库迁移、完整测试与独立恢复。
2. Mockito/Byte Buddy 提示未来 JDK 将收紧动态 agent；当前 Java 21 测试通过。
3. Vite 主 chunk 约 766 KB，超过 500 KB 建议阈值，作为 V1.1 性能优化项。

## 结论

从代码、生产部署、备份恢复和兼容回滚的技术门禁看，Yufeichi Platform V1.0 已达到本轮定义的核心交付标准。

V1.1 延期项包括评论留言、完整用户/角色/权限后台、搜索和统计缓存，不应描述为 V1.0 已实现。

## 2026-09-30 收尾前进度复核补充

原结论适用于已记录的核心交付结果，不能替代原冲刺计划的全部最终门禁。当前 GitHub `dev` 和 `v1.0.0` 均指向 `931b909`；线上与备份状态已再次只读检查。

- 97 项后端测试的 Surefire XML：0 failures / 0 errors / 0 skipped，Java 21.0.11；本次未重跑测试。
- 正式服务器备份及 Windows 副本可读，内部文件校验通过。Windows 副本现位于 `D:\Desktop\yufeichi\yufeichi-backups\day7-20260929T102727Z.tar.gz`，原 SHA-256 未变。
- 仓库尚无正式 `backup.sh`、恢复/回滚操作脚本；服务器项目目录仅有迁移前备份脚本。检查的 systemd timer/cron 中未找到项目定时备份及失败反馈配置。
- 独立恢复报告记录的是导入、数量和文件哈希；尚缺恢复后文章/项目业务读取、账号关联和图片 HTTP 验证。当前在线库公开已发布文章为 0，项目为 0。
- 回滚报告及服务器保留的探针只证明旧版/当前版健康检查通过，尚缺旧版真实读写验收；容器重启已有记录，容器重建保持数据尚未核验。
- 线上 Nginx `/api` 实测返回 HTML 404；服务器配置没有仓库模板中的 JSON 404 和网关错误处理，需同步并验证。
- 干净检出按 README 启动，以及开发 OpenAPI 页面截图等交付证据仍需补齐。

**对齐后的状态：V1.0 核心功能已上线，恢复/回滚已做部分演练；按原计划逐项验收，仍有未完成和未核验项，不能将 Day7 全部门禁标为完成。** 本次详细证据和收尾顺序见 [V1.0 进度对齐](V1.0-进度对齐.md)。

## 2026-09-30 收尾后验收

上述缺项均已完成实际修复与验证：Nginx JSON 404/413/503、正式备份 service/timer/OnFailure、独立恢复后账号/文章/项目/关联/6 条文件链接及读写上传、生产容器重建、兼容候选完整回滚、干净检出构建与 12 项浏览器测试、开发 OpenAPI 截图、异机副本 ACL。

原始 `31a551c` 在新上传测试中失败（0600 导致 Nginx 403），没有将这个失败掩盖为通过；回移已有 `7c24911` 权限修复并重新执行 97 项测试后，兼容候选验证通过。最后恢复原生产前后端 current，清理标记的临时业务和管理员凭据，Flyway checksum 保持。

**当前技术验收：PASS。本轮运维实现提交为 `3cbf78a`，验收文档随其后续提交交付，GitHub 现有标签保持原引用。** 详细命令、文件、备份哈希、日志和边界见 [V1.0 收尾执行与验收报告](V1.0-收尾执行与验收报告.md)。
