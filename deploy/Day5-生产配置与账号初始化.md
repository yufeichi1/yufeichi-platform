# Day5 生产配置与一次性账号初始化

本文件是可执行配置约定，不代表已经部署服务器。上线前备份、端口收紧、Nginx、服务用户、Redis 持久化属于 Day6；本轮只在独立测试库验证。

## 配置与秘密

使用 Java 21。产物为 `yufeichi-server/target/yufeichi-server-0.0.1-SNAPSHOT.jar`。
JAR 包含 `application-prod.yml`，其中只有环境变量占位符，没有生产口令回退。将 `backend.env.example` 复制到仓库外的受保护文件，例如 `/etc/yufeichi/backend.env`，填写随机秘密，文件权限 600，服务用户只读。不要把实际文件提交 Git。

- Java 环境变量 `DB_USERNAME/DB_PASSWORD` 对应 Compose 的 `MYSQL_USER/MYSQL_PASSWORD`；`DB_NAME` 指定已有数据库。禁止 root。
- `REDIS_PASSWORD` 必须与 Redis 实际配置一致。Compose 的 `.env` 不会自动传给 Java。
- `JWT_SECRET` 使用独立密码学随机数（至少 64 个 UTF-8 字节）；例如在受保护终端生成 `openssl rand -hex 64` 并存入环境文件。不要在共享终端、日志或聊天中粘贴秘密。
- `JWT_EXPIRE_MINUTES` 默认 45，只允许 30–60。没有 Refresh Token，过期后重新登录。
- `UPLOAD_PATH` 必须是可写的绝对路径；模板 `/var/lib/yufeichi/uploads` 适用于 Linux，Windows 本地初始化请改为自己的绝对路径。
- `LOG_PATH` 默认 `/var/log/yufeichi/backend`，服务账户必须可写；Windows 本地初始化同样改为自己的绝对路径。生产配置测试将日志定向到独立临时目录。
- `SPRING_PROFILES_ACTIVE=prod`；不能与 dev/test 混用。必要变量为空、弱 JWT 密钥、root 数据库账号、相对上传路径均会导致启动失败。
- 生产监听 `127.0.0.1:8080`，文档端点禁用且安全层拒绝，SQL 日志关闭。
- `TRUSTED_PROXIES` 默认空。只有经过核实的精确代理 IP 可加入逗号分隔列表。应用仅在连接对端属于此列表时使用单个数字 `X-Real-IP`，忽略 `X-Forwarded-For`。Nginx 必须覆盖该头，不能透传用户值；Spring 转发头自动解析关闭。未配可信代理时登录 IP 限流会按代理本身聚合。

## 首次初始化顺序

1. 上线前先备份已有数据库；不要修改 V1–V9，不执行 clean/repair。V10 禁用 V2 的 `id=1, username=admin` 并创建一次性初始化状态。所有迁移按 Flyway 正常校验执行。
2. 将上述变量安全注入 **Java 进程环境**。Linux Bash 下若环境文件使用正确 shell 引号，可用 `set -a; source /etc/yufeichi/backend.env; set +a`；systemd 则使用 `EnvironmentFile=`，不要假设两种文件语法完全相同。
3. 在未对外开放的环境执行以下命令。用户名须为新的小写 ASCII 账号，3–50 字符（字母开头，后续可用数字、下划线、短横线），不能为 admin。密码至少 16 字符，UTF-8 不超过 72 字节；建议密码管理器生成。

```bash
# JAVA_HOME 必须指向 JDK 21；秘密只通过交互读取，不作为命令行参数。
read -r -p 'New administrator: ' BOOTSTRAP_USERNAME
read -r -s -p 'New password: ' BOOTSTRAP_PASSWORD; printf '\n'
export BOOTSTRAP_USERNAME BOOTSTRAP_PASSWORD
"$JAVA_HOME/bin/java" -jar yufeichi-server/target/yufeichi-server-0.0.1-SNAPSHOT.jar --bootstrap
bootstrap_status=$?
unset BOOTSTRAP_USERNAME BOOTSTRAP_PASSWORD
test "$bootstrap_status" -eq 0
```

`--bootstrap` 强制 prod,bootstrap profile，运行非 Web 应用，不开放初始化 HTTP 接口；事务锁住唯一状态行，创建 BCrypt 管理员及超级管理员关系，再标记完成。重复执行拒绝，不覆盖已有用户/密码。若失败，先解决原因；不要手工重置状态来重复创建管理员。

4. 确认上一步退出码 0，移除初始化变量后，使用相同后端环境启动：

```bash
"$JAVA_HOME/bin/java" -jar yufeichi-server/target/yufeichi-server-0.0.1-SNAPSHOT.jar
```

生产服务拒绝未初始化数据库或重新启用公开种子的状态。先在回环地址验证新账号登录、`/api/auth/me`、退出和旧 Token 401，再由 Day6 开放正式反向代理。开发库首次迁移后同样没有可用默认管理员；可对自己的本地库按上述离线流程初始化，然后用 dev profile 和该新账号开发。不要运行测试夹具来启用真实环境的种子账号。

## 会话、限流与运维边界

- 每个 Token 含独立 jti；撤销键只含 SHA-256 摘要，Redis PXAT 到原 JWT 到期时间，重复退出不会延长 TTL。
- 同一归一化账号 10 分钟内失败 5 次，或同一来源 IP 失败 20 次，之后返回 429。账号归一化与查询一起处理大小写、全角与常见重音；Lua 原子计数并设置固定窗口过期。成功登录清账号计数，不清共享 IP 计数。
- Redis 故障时有效 Token 的受保护认证、登录、退出返回 503；无凭据仍为 401。公开 GET 内容/图片及健康检查不依赖 Redis，也不使用可选 Token 识别身份。恢复后可重试退出，前端不会把撤销失败当作成功。
- Redis 会话状态必须避免驱逐及丢失：Day6 需配置持久卷、AOF、适当内存策略与备份恢复方案；Redis 数据丢失会丢失撤销记录，必要时轮换 JWT 密钥使现有 Token 全部失效。不能在生产 flush 清理限流键。
- 审计日志仅记录固定事件、结果、操作者 ID 和资源 ID。事务回滚记录 failure；不包含密码、Token、上传原文件名或正文。日志检索/归档管理页面不在 Day5 范围。
