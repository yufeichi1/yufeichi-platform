# Day6 生产部署操作说明

这里的原Day6指V1.0冲刺部署。2026-10-01的AI计划Day6增量发布证据见[AI Day6报告](../../docs/ai/Day6-执行与上线验收报告.md)。新增摘要助手和手动内容索引，尚未开放知识问答。

AI采用独立`ai-compose.yml`、PG持久卷和127.0.0.1:15432端口；MySQL/Redis不重建。`configure-ai.py`仅从mode600的受保护JSON导入模型变量，保留原业务配置，消费后移除输入文件；真实Key不在模板中。Java需同时开启AI_ENABLED、AI_VECTOR_ENABLED及完整Embedding/PG配置。

正常发布继续使用`activate-release.sh`，失败自动恢复原current链接和Nginx；MySQL V11与PG数据卷保留。已有V1—V10 checksum不得改动，禁止clean/repair或删除生产卷。正式`backup.sh`现在包含PG SQL、配置及原有业务备份，PG恢复必须单独验证，不能用MySQL恢复报告代替。

```bash
# 先安全提供临时root:root 600管理员JSON；脚本完成后移除该临时凭据。
sudo python3 /opt/yufeichi/deploy/ai-acceptance.py
# 额外1次真实Embedding调用，不写任何数据库；适用于公开内容为空时。
sudo python3 /opt/yufeichi/deploy/ai-embedding-smoke.py
sudo bash /opt/yufeichi/deploy/backup.sh
sudo python3 /opt/yufeichi/deploy/verify-ai-vector-backup.py /var/backups/yufeichi/<已校验备份目录>
```

浏览器验收从本地项目执行`node deploy/production/ai-browser-acceptance.mjs <受保护管理员JSON路径>`，实际生成一次摘要、不保存文章，截图不包含密码/Token，未录制trace。模型不可用时可关闭AI_ENABLED并重启应用；业务库与已有发布内容仍保留。生产公开内容为空时索引0来源/0片段是正常结果，后续发布真实内容后手动重建。

适用本项目的 Ubuntu 24.04 amd64 服务器。线上入口为 `https://yufeichi.com` 与 `https://www.yufeichi.com`，后台 `/login`。实际执行证据见 [Day6 报告](../../docs/Day6-执行与验收报告.md)。本目录只有模板和脚本，没有服务器真实环境文件、口令或私钥。

## 布局及权限

| 用途 | 服务器路径/规则 |
|---|---|
| 原静态站点 | `/var/www/yufeichi`，保留作为回退依据 |
| 后端 release | `/opt/yufeichi/backend/releases/<release>/yufeichi-server.jar` |
| 后端 current | `/opt/yufeichi/backend/current`，systemd 使用此符号链接 |
| 前端 release/current | `/var/www/yufeichi-app/releases/<release>`、`/var/www/yufeichi-app/current` |
| 部署脚本 | `/opt/yufeichi/deploy/`，root 所有、文件 644 |
| 应用进程 | `yufeichi` 非 root 用户，服务组 `www-data`；Java 21 |
| 图片 | `/var/lib/yufeichi/uploads`，目录 2750，发布图片 640，`yufeichi:www-data` |
| 日志 | `/var/log/yufeichi/backend`，应用可写；滚动日志，容器日志限额 |
| 服务环境 | `/etc/yufeichi/backend.env`，root:root 600；systemd 读取并注入子进程 |
| 容器秘密 | `/etc/yufeichi/secrets`，宿主目录 root:root 700；单文件只读挂载，容器降权后可读 |
| 持久卷 | `yufeichi-prod_mysql-data`、`yufeichi-prod_redis-data`，不得删除 |
| 备份 | `/var/backups/yufeichi/` 下受保护的归档，包含敏感配置，不能上传 Git |

Docker 端口只绑定 `127.0.0.1:3306/6379`，Java 只监听回环 8080。`TRUSTED_PROXIES=127.0.0.1` 与 Nginx 覆盖 `X-Real-IP` 配套，浏览器不能通过请求头伪造登录来源。镜像为明确补丁版本：MySQL 8.4.11、Redis 7.4.10；Compose `pull_policy: never` 要求事先加载或验证镜像。

Redis 使用持久卷、AOF、`appendfsync always`、`noeviction`，保留会话撤销状态。备份/恢复或意外丢失 Redis 状态后，应按安全处置流程轮换 JWT 密钥，不能静默恢复已撤销 Token。

## 首次部署顺序

以下操作均需要服务器 sudo 权限，应在已有站点和配置备份后进行。不要直接在未知服务器重复运行初始化。

1. 备份已有 `/var/www/yufeichi`、`/etc/nginx`、`/etc/letsencrypt`、`/opt/yufeichi` 和相关 systemd 单元。归档必须能 `tar -tzf` 读取，记录 SHA-256，保存在 root-only 目录。当前激活脚本检查 `/var/backups/yufeichi/day6-initial/pre-deploy.tar.gz`；其他服务器应按自己的实际备份位置调整门禁，不能创建空文件绕过。
2. 安装并确认 `openjdk-21-jre-headless`、Docker、Compose v2。当前 unit 使用 `/usr/lib/jvm/java-21-openjdk-amd64/bin/java`。不升级整个操作系统，不使用 Java 24/25。
3. 获取可信镜像。如果服务器可联网，可先按固定标签拉取并检查版本；离线环境从已验证环境 `docker save` 后传输，双方比对 SHA-256，再 `docker load`。本次镜像来源 digest：MySQL `sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb`；Redis `sha256:595cc6f2bb3af6e03347b90deb6123c6aa2c81dea05ce08128de8a174b6ac67b`。不要换用来源不明的镜像站。
4. 将本目录的服务器文件安装到 `/opt/yufeichi/deploy/`，root 所有、644；Python/Shell 脚本由解释器调用。先执行 `sudo python3 /opt/yufeichi/deploy/initialize-host.py`。它拒绝覆盖已有 backend.env，创建新服务用户、持久目录、随机 DB/Redis/JWT 秘密，以及一次性管理员交付文件；不会输出秘密。
5. 执行 `sudo docker compose -f /opt/yufeichi/deploy/compose.yml config --quiet`，再 `up -d --wait --wait-timeout 180`。确认两个容器 healthy。
6. 使用 Java 21 无跳过执行 `scripts/mvn21.ps1 clean verify`，前端 `pnpm install --frozen-lockfile`、`pnpm build`。将真实 `yufeichi-server-0.0.1-SNAPSHOT.jar` 显式复制成 release 内的 `yufeichi-server.jar`，把 dist 内容放入对应前端 release。校验传输前后 SHA-256，release ID 格式为 `YYYYMMDDTHHMMSSZ-<commit>`。
7. **迁移前**执行 `sudo bash /opt/yufeichi/deploy/pre-migration-backup.sh`。即使数据库为空也保留建库前状态；脚本开启 pipefail、先写临时文件、gzip/tar 校验成功才保留最终文件名。禁止 Flyway clean/repair。
8. 首次库执行 `sudo python3 /opt/yufeichi/deploy/bootstrap.py /opt/yufeichi/backend/releases/<release>/yufeichi-server.jar`。它以非 root 用户运行 `--bootstrap`，完成 V1–V10 及新管理员初始化，旧种子保持禁用，服务环境不保存 BOOTSTRAP 变量。已有初始化库不得重跑 bootstrap。
9. `sudo bash /opt/yufeichi/deploy/activate-release.sh <release>`：规范 release 权限，替换 current，启用并启动 systemd；回环健康通过后切换前端，备份 Nginx 配置，`nginx -t` 成功才 reload。原站点和旧 release 不删除。模板中的域名、证书路径须与实际已签发证书一致。
10. 验收 HTTPS、匿名阅读、后台登录、上传/发布以及错误边界。`acceptance.py before` 会创建标明用途的临时文章；`restart-acceptance.sh` 会短暂重启数据库/Redis并终止 Java 主进程，验证自动恢复和持久化，再调用 after 阶段下架删除临时文章。只能在允许短暂中断的部署验收时执行。正常日常排障只运行只读 `verify-host.py`。
11. `certbot renew --dry-run --cert-name yufeichi.com --non-interactive` 验证续期，检查实际 Certbot timer。不会为未配置域名申请证书。
12. 将 `/etc/yufeichi/initial-admin.json` 通过受信 SSH 安全交付给管理员，确认接收后删除运行目录和中转路径的明文副本；受保护备份可能仍含初始化凭据。真实密码不进入仓库、日志、命令行参数或共享截图。

`initialize-host.py` 使用部署约定变量直接生成受保护 backend.env，映射与 [Day5 模板](../backend.env.example) 一致；容器通过 secret 文件读取凭据，Compose 环境与 Java 环境分别配置。绝不能把 Compose `.env` 当作宿主 Java 已获得变量的证据。

## 验收与后续发布

```bash
sudo python3 /opt/yufeichi/deploy/verify-host.py
sudo systemctl status yufeichi --no-pager
sudo docker compose -f /opt/yufeichi/deploy/compose.yml ps
sudo nginx -t
curl -fsS https://yufeichi.com/api/health
```

真实浏览器只验证登录/退出和页面，不新增业务内容：

```text
node deploy/production/browser-acceptance.mjs <受保护的管理员JSON路径>
```

截图输出至系统临时目录 `yufeichi-day6-browser`，脚本不保存登录密码页或认证跟踪。服务器交付文件删除后，如需再次运行有写操作的验收，应由管理员临时提供 root-only 凭据文件，并在完成后移除；不要把初始密码永久留在自动化脚本中。

后续发布先备份数据库、配置和 uploads，再校验迁移兼容性；创建新 release 后切 current。应用回退不等于数据库回退。

2026-09-30 收尾已完成：Nginx JSON 配置已同步，正式定时备份/失败反馈、恢复后业务读取、兼容回滚读写及容器重建均已实测。详情见 [收尾验收报告](../../docs/V1.0-收尾执行与验收报告.md) 与 [V1.0 进度对齐](../../docs/V1.0-进度对齐.md)。

## 正式备份与日常检查

`backup.sh` 使用 single-transaction 数据库快照，再归档不可变 uploads、受保护配置、迁移和 manifest；内部校验成功且外层 tar 可读才形成正式 `.tar.gz`。失败保留 `.partial` 供诊断，不能把它当成功包。脚本不删除旧备份，需按磁盘容量和保留策略定期核对。发布与备份共享 `/run/lock/yufeichi-ops.lock`。

生产已安装 `yufeichi-backup.service`、`yufeichi-backup.timer`、`yufeichi-backup-failure@.service`，每天北京时间 03:30 加 0—5 分钟随机延迟；Persistent=true，机器停机期间错过的任务可补执行。失败反馈为 systemd failed 状态、journal 和 root-only `/var/lib/yufeichi/backup-status/last-failure`；最后成功包记录在 `last-success`。保留历史失败记录，需比较成功/失败时间，不要把失败演练记录当作当前任务仍失败。

```bash
sudo systemctl start yufeichi-backup.service
sudo systemctl show yufeichi-backup.service -p Result -p ExecMainStatus
sudo systemctl list-timers yufeichi-backup.timer
sudo journalctl -u yufeichi-backup.service -n 50 --no-pager
sudo cat /var/lib/yufeichi/backup-status/last-success /var/lib/yufeichi/backup-status/last-failure
```

备份位于 `/var/backups/yufeichi/backup-<UTC时间>-<PID>` 及同名 tar 包，目录 700、文件 600；包含真实 DB/Redis/JWT 配置和数据库内容。临时 `acceptance-admin.json`、`initial-admin.json` 已被新脚本排除。通过受信 SSH 传输包后比对整体与内部 SHA-256；如借用中转文件，设 600、放在 700 目录，确认接收后移除。Windows 本次副本存于 `D:/Desktop/yufeichi/yufeichi-backups`，ACL 限制至持有人、SYSTEM、Administrators。异机复制应随正式运维流程持续执行。

## 恢复、重建与兼容回滚验收

这些命令用于明确允许短暂中断的受控验收。先通过安全渠道临时提供 `/etc/yufeichi/acceptance-admin.json`，root:root 600；文件结构为 username/password，不能写到 CLI 或 Git。再用 `ops-probe.py prepare` 创建标明用途的文章、项目、分类、标签和图片；状态放在 root-only `/var/lib/yufeichi-ops/probe.json`，与应用用户可写目录分离。凭据仅发往固定生产域名或独立回环端口。

```bash
sudo python3 -B /opt/yufeichi/deploy/ops-probe.py prepare
sudo systemctl start yufeichi-backup.service
sudo python3 -B /opt/yufeichi/deploy/restore-verify.py <刚生成的已校验备份目录>
sudo python3 -B /opt/yufeichi/deploy/recreate-verify.py
sudo python3 -B /opt/yufeichi/deploy/rollback-rehearsal.py <兼容backend-release> --frontend <兼容frontend-release> --upload
sudo python3 -B /opt/yufeichi/deploy/ops-probe.py cleanup
```

恢复只导入新建的 `yufeichi-restore-*` 隔离容器，随机回环端口、独立 Redis、新 JWT key 与非 root Java；校验账号角色、文章标签、所有 file_info 链接、公开内容、写入和新上传。诊断文件位于受保护的 `restore-runs`，临时容器在 finally 中回收。真实灾难恢复若不保留原 Redis 撤销状态，必须生成新 JWT key 再开放认证；回放数据库或 config 不应恢复已撤销 Token 的效力。

重建使用 `up --force-recreate`，保留原 named volumes，绝不能使用 `down -v`。回滚演练验证后总会切回开始时的前后端 current；应用兼容回滚不回滚 schema。当前已验证候选为 `day7-compatible-31a551c-uploadfix`，源自 `31a551c` 回移 `7c24911` 权限修复。原始 `day7-rollback-31a551c` 会产生 Nginx 403 的新图片，不能作为生产回滚候选。

兼容候选可从独立检出构建：checkout `31a551c`，仅应用 `7c24911` 的 FileService 和现有回归断言补丁，Java21 `clean verify` 全通过后上传 JAR，校验 SHA-256，并保留 compatibility.txt。该候选与当前版的前端源码一致；演练覆盖两个 current 链接的切换，不承诺任意历史版本兼容。

`verify-backup-failure.sh` 通过只存在于 `/run/systemd/system` 的失败测试 unit，令备份读取不存在的 Compose 文件；校验 OnFailure 和没有正式归档，随后移除该临时 unit。`verify-nginx.py --outage` 会短暂停止应用并在 finally 中启动，验证网关 JSON 503。验收完成后移除临时管理员文件与状态；本次已执行清理。新生产脚本安装入口为 `install-ops.sh <上传模板目录>`，先校验并验收，再 enable timer。
