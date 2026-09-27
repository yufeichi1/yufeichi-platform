# Yufeichi Platform

基于 Spring Boot 3 + Vue 3 的个人博客、项目展示与后台管理项目。当前完成范围是 **Day1 认证和构建基线**，尚未完成内容网站或生产部署。

## 已实现

- 后端：Spring Boot 3.5.16、Spring Security、JWT、MyBatis-Plus、Flyway V1—V9。
- 实际接口：`GET /api/health`、`POST /api/auth/login`、`GET /api/auth/me`、`POST /api/auth/logout`。
- 登录查询用户、角色、权限；每次携带 Token 时重新检查账户。禁用、删除账户的旧 Token 返回 401；数据库故障返回 500。
- 统一 `Result`：成功 `code=0`；错误同时返回正确的 HTTP 状态，不再用 HTTP 200 包装所有错误。
- OpenAPI：`/v3/api-docs`；Knife4j 静态界面：`/doc.html`；Swagger UI：`/swagger-ui/index.html`。
- 独立 test profile、真实 MySQL 8.4/Redis 7 容器测试、认证和异常回归测试。

`logout` 当前只清理本次请求的认证上下文，客户端须删除本地 Token；服务端 Token 撤销属于 Day5。登录返回 permissions，当前 `/me` 返回基本信息和 roles，尚未返回 permissions。429 已有错误映射，登录限流尚未实现。

## 规划，尚未实现

- Day2：文章、分类、标签、项目和图片上传业务接口。
- Day3—Day4：前台和后台页面、路由、登录状态恢复、内容管理闭环。
- Day5：Redis 登录限流、退出撤销、生产配置模板和权限完整回归。
- Day6—Day7：真实生产部署、备份恢复、回滚及交付。
- 留言、评论、完整日志管理等后续能力。

现有数据库表、依赖和 Security 白名单不代表业务功能已实现。前端仍为 Vue/Vite 脚手架；Element Plus、Axios、Pinia、Router 已安装，不等于已集成业务页面。

## 固定 Java 21

必须使用 **JDK 21** 编译、测试、运行；不使用 Java 24/25。`.java-version` 记录版本，Maven Enforcer 拒绝非 21 的 Maven 运行环境；POM 显式配置 Lombok 和 Spring Boot annotation processors。

Windows 从仓库根目录运行：

```powershell
# 若脚本无法找到本机 JDK 21，设置为自己的安装目录。
$env:YUFEICHI_JAVA_HOME = 'C:\path\to\jdk-21'
.\scripts\mvn21.ps1 -version
.\scripts\mvn21.ps1 clean verify
```

`mvn21.ps1` 依次尝试 `YUFEICHI_JAVA_HOME`、当前用户 `.jdks\temurin-21.0.11`、`JAVA_HOME`，只接受 21；仅为该次命令调整环境变量。已有本机安装可直接执行脚本，不必设置示例路径。IDE Project SDK 和 Maven Runner 也必须选择 JDK 21；仅设置编译目标 `release=21` 无法改变 Maven 实际运行 JDK。

Linux/macOS 将 `JAVA_HOME` 和 `PATH` 指向 JDK 21，然后在 `yufeichi-server` 下运行 `./mvnw -version` 和 `./mvnw clean verify`。不要用跳过测试的打包结果代替验收。

## 本地依赖与环境变量

Docker Desktop/Engine 必须运行。已有容器可用 `docker start yufeichi-mysql yufeichi-redis` 启动。首次初始化时，在 `deploy/.env` 中按 `.env.example` 填写本地秘密，再运行：

```powershell
docker compose -f deploy/docker-compose.yml up -d
```

保留已有数据卷和凭据；修改 `.env` 不会自动修改已有 MySQL 数据卷内的密码。不要删除数据卷或执行 Flyway clean/repair 来掩盖迁移问题。开发 Compose 当前映射 3306/6379 到宿主机所有网卡，生产收紧端口属于 Day6。

**`deploy/.env` 只供 Compose 插值，不会自动注入宿主机 Java 进程。** 后端 dev profile 的变量如下：

| Java 进程变量 | 映射/用途 | dev 默认值 |
|---|---|---|
| `DB_HOST` | MySQL 地址 | `localhost` |
| `DB_PORT` | MySQL 端口 | `3306` |
| `DB_USERNAME` | 与 Compose 的 `MYSQL_USER` 对应 | `yufeichi` |
| `DB_PASSWORD` | 与 Compose 的 `MYSQL_PASSWORD` 对应 | `yufeichi` |
| `REDIS_HOST` | Redis 地址 | `localhost` |
| `REDIS_PORT` | Redis 端口 | `6379` |
| `REDIS_PASSWORD` | 与 Compose 同名变量对应 | `yufeichi` |
| `JWT_SECRET` | 至少 32 个 UTF-8 字节 | 配置内公开的开发值，仅本地使用 |
| `JWT_EXPIRE_MINUTES` | Token 有效分钟数 | `1440` |
| `SPRING_PROFILES_ACTIVE` | Spring profile | `dev` |

dev 数据库名固定为 `yufeichi`。`MYSQL_ROOT_PASSWORD` 只供 MySQL 容器初始化，后端不得使用 root 账户连接。启动前在当前终端设置与已有容器一致的 `DB_USERNAME`、`DB_PASSWORD`、`REDIS_PASSWORD` 和本地 `JWT_SECRET`，再执行：

```powershell
.\scripts\mvn21.ps1 spring-boot:run
```

默认地址 `http://localhost:8080`。V2 的 `admin` 初始账户仅限本地首次开发使用；本次验收只使用临时测试库的种子账户，没有修改开发库账号。

## 完整 Day1 验收

```powershell
.\scripts\mvn21.ps1 clean verify
```

必须先启动 Docker，并能够取得测试依赖镜像。`YufeichiServerApplicationTests` 显式指定启动类并强制 test profile，启动独立 `mysql:8.4`、`redis:7` 容器和随机宿主机端口，数据库为 `yufeichi_test`。动态属性来自这些容器，不使用 dev/prod 的连接；test 配置没有开发连接回退。容器结束时自动停止，V1—V9 仅在空的测试库正常迁移。

验收包括真实 HTTP 登录、错误密码、空 JSON、无 Token、过期/篡改/非法 Token、禁用/删除账户旧 Token、正常 `/me`、OpenAPI，以及 MySQL 默认 SQL 模式下权限去重和排序。单元测试验证基础设施故障 500 与 400/401/403/404/409/413/429/500 映射。

本次机器访问 Docker Hub 出现 TLS EOF，不能下载 Ryuk 清理辅助镜像。仅本次本地执行临时设置以下进程环境变量，使用已缓存的 MySQL/Redis 镜像；所有业务测试仍执行，JUnit 容器生命周期负责正常结束时回收，执行后已检查没有测试容器遗留：

```powershell
$env:TESTCONTAINERS_RYUK_DISABLED = 'true'
$env:TESTCONTAINERS_CHECKS_DISABLE = 'true'
.\scripts\mvn21.ps1 clean verify
Remove-Item Env:TESTCONTAINERS_RYUK_DISABLED, Env:TESTCONTAINERS_CHECKS_DISABLE
```

网络正常时不需要此临时绕过；禁用 Ryuk 后异常中断可能留下测试容器，应按测试标签核对，不能删除开发容器或卷。Maven 若需代理，使用本机 Maven settings 配置；项目不保存机器代理地址或秘密。

## 构建产物

实际可执行 JAR：

```text
yufeichi-server/target/yufeichi-server-0.0.1-SNAPSHOT.jar
```

`.jar.original` 是重打包前产物。部署脚本不得假定文件名是 `yufeichi-server.jar`。用 **Java 21** 运行可执行 JAR，运行前注入相应 profile 和环境变量；当前默认 dev，不能直接视为生产配置。

## Day1 明确的生产准备方案

Day5 补生产模板，Day6 落地：显式启用 prod；DB/Redis/JWT 秘密从受保护的外置环境文件注入，缺失即启动失败；非 root 服务账户、回环监听、关闭 API 文档和 SQL 明细日志。Compose 与 Java 进程分别注入各自变量。

V1—V9 保持不变。公开服务前通过后续增量迁移禁用固定种子账号，并使用一次性受保护 bootstrap 凭据初始化真实管理员，完成后移除 bootstrap 凭据。此处只明确方案，没有提前实现 Day5/Day6 功能。

2026-09-27 公开网络检查：`yufeichi.com` 与 `www.yufeichi.com` 均解析至 `122.51.218.155`，HTTPS 返回 200，TLS 域名及信任校验通过，证书到期时间为 2026-12-24。随后经授权SSH只读检查：sudo可用；站点目录为 `/var/www/yufeichi`，配置为 `/etc/nginx/sites-available/yufeichi`；Certbot续期定时器已启用且最近执行成功。当前仅有静态站点，未配置后端API代理。用户确认项目尚未备份，检查范围内亦未发现项目备份，备份建立与恢复验证仍是Day6/Day7待办。

详细修改、命令、验证结果及边界见 [Day1 执行与验收报告](docs/Day1-执行与验收报告.md)。
