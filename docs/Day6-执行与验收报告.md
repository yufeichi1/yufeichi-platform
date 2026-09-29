# Day6 生产部署与验收报告

日期：2026-09-29
分支：`dev`
生产 Release：`20260928T110832Z-7c24911`

## 1. Day6 目标

完成 Yufeichi Platform V1.0 的真实生产部署，并验证 MySQL/Redis 持久化、生产秘密外置、管理员安全初始化、systemd、Nginx、HTTPS、端口隔离、服务重启恢复、证书续期和线上核心业务冒烟。

## 2. 本地与发布基线

Day6 前已完成后端 JDK 21 构建验证与前端生产构建。
生产 JAR 发布时统一为 `yufeichi-server.jar`。

本次新增修复：

- `7c24911 fix(upload): 允许Nginx只读访问Linux上传图片`

## 3. 生产环境

- Java：21
- Spring Boot：3.5.16
- MySQL：8.4.11
- Redis：7.4.10
- Web Server：Nginx
- HTTPS：Let's Encrypt / Certbot
- 后端：systemd
- MySQL / Redis：Docker Compose

当前 Release：

```text
Backend:  /opt/yufeichi/backend/releases/20260928T110832Z-7c24911
Frontend: /var/www/yufeichi-app/releases/20260928T110832Z-7c24911
```

## 4. 数据库与 Redis

Flyway V1～V10 全部执行成功。

MySQL：

- 8.4.11
- healthy
- 监听 `127.0.0.1:3306`
- 公网 3306 不可达

Redis：

- 7.4.10
- healthy
- 监听 `127.0.0.1:6379`
- 公网 6379 不可达
- `appendonly yes`
- `appendfsync always`
- `maxmemory-policy noeviction`

## 5. systemd 与生产秘密

`yufeichi.service`：

```text
ActiveState=active
UnitFileState=enabled
Restart=always
User=yufeichi
Group=www-data
```

生产秘密文件权限：

```text
/etc/yufeichi/backend.env  0600
/etc/yufeichi              0700
/etc/yufeichi/secrets      0700
```

Spring Boot 监听 `127.0.0.1:8080`，公网 8080 不可达。

## 6. Java 崩溃恢复

生产验收中主动向 Java 主进程发送 `SIGKILL`。

日志证据：

```text
2026-09-29 00:17:19  Sent signal SIGKILL to main process
2026-09-29 00:17:19  Main process exited, status=9/KILL
2026-09-29 00:17:29  Scheduled restart job, restart counter is at 2
2026-09-29 00:17:29  Started yufeichi.service
```

结论：systemd 能在 Java 异常退出后自动拉起服务，验收 PASS。

## 7. MySQL / Redis 重启与持久化

生产验收中 MySQL 与 Redis 均发生真实重启。

MySQL 完整关闭后重新启动，最终重新进入 `ready for connections`。

Redis 重启后从 AOF base / incr 文件重新加载数据，并重新进入 `Ready to accept connections`。

重启后：

- Day6 临时业务记录仍可查询
- Day6 probe 图片实体文件仍存在
- 后端健康接口正常
- MySQL / Redis 当前均为 healthy

两张 probe 图片仍存在：

```text
/uploads/article/6261eb22-cb79-4162-9916-d361cfdb807c.png
/uploads/article/06e30b81-b773-4f67-b292-33551d2083b5.png
```

Day6 临时文章已按验收流程下架并逻辑删除。probe 图片后续可作为非阻塞清理项处理。

## 8. 公网端口与 Nginx

外部实测：

```text
80    REACHABLE
443   REACHABLE
3306  BLOCKED/UNREACHABLE
6379  BLOCKED/UNREACHABLE
8080  BLOCKED/UNREACHABLE
```

HTTP 到 HTTPS：

```text
http://yufeichi.com/      -> 301 -> https://yufeichi.com/
http://www.yufeichi.com/  -> 301 -> https://www.yufeichi.com/
```

HTTPS 首页：

```text
https://yufeichi.com/      HTTP 200
https://www.yufeichi.com/  HTTP 200
```

API 健康检查：

```text
GET https://yufeichi.com/api/health
HTTP 200
Content-Type: application/json
{"code":0,"message":"success","data":"ok"}
```

不存在的未授权 API 返回 JSON 401，没有错误回退为 SPA `index.html`。
不存在的 uploads 返回 HTTP 404。

Nginx 最终 `nginx -t` 通过。

## 9. HTTPS 与自动续期

证书覆盖：

- `yufeichi.com`
- `www.yufeichi.com`

证书有效期至 2026-12-24。

执行：

```bash
certbot renew --dry-run
```

结果：

```text
Congratulations, all simulated renewals succeeded
/etc/letsencrypt/live/yufeichi.com/fullchain.pem (success)
```

使用 Snap 安装 Certbot。自动续期：

```text
snap.certbot.renew.timer    enabled
snap.certbot.renew.service  static
```

定时器已实际调度。验收 PASS。

## 10. 线上业务冒烟

Day6 已完成生产环境真实链路验收，包括：

- 管理员登录
- 图片上传
- 创建临时文章
- 发布文章
- 匿名读取已发布文章
- 退出登录
- Token 撤销
- 上传大小边界 413
- uploads 不存在文件 404
- HTTPS 健康检查

说明：本报告不额外声称“同一条已撤销 Token 在 Redis 重启后再次 401”已保留直接输出证据；Redis AOF 重启恢复本身已有独立日志证据。

## 11. Day6 新增生产部署文件

```text
deploy/production/
├── README.md
├── compose.yml
├── nginx.conf
├── yufeichi.service
├── initialize-host.py
├── bootstrap.py
├── pre-migration-backup.sh
├── activate-release.sh
├── acceptance.py
├── browser-acceptance.mjs
├── restart-acceptance.sh
└── verify-host.py
```

真实数据库密码、Redis 密码、JWT Secret、管理员密码、TLS 私钥等秘密不得提交到仓库。

## 12. Day6 结论

Day6 生产部署目标完成：

- [x] 原服务器状态备份
- [x] MySQL / Redis 生产部署与健康检查
- [x] 回环端口绑定与公网隔离
- [x] Redis AOF 持久化
- [x] 生产秘密外置
- [x] 安全管理员初始化
- [x] Flyway V1～V10 生产迁移
- [x] Java 21 + systemd 非 root 运行
- [x] 后端 / 前端 release-current 发布
- [x] Nginx API / SPA / uploads 路由
- [x] HTTPS 与 HTTP → HTTPS
- [x] Certbot renew dry-run
- [x] Certbot 自动续期
- [x] 线上核心业务冒烟
- [x] MySQL / Redis 重启恢复
- [x] Java 崩溃自动恢复
- [x] 数据和图片跨服务重启保持

**Day6 验收：PASS**

Day7 尚未开始，下一阶段为：

- DB + uploads 正式备份
- 独立恢复演练
- 上一兼容 Release 回滚演练
- 全套构建与回归
- README / ER 图 / 截图 / V1.0 最终发布门禁
