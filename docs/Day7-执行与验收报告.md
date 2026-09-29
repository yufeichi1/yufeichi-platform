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
