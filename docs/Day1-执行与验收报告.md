# Day1 执行与验收报告

日期：2026-09-27（Asia/Shanghai）。项目：`D:/Desktop/yufeichi-platform`。

执行依据仅为用户提供的 `D:/Desktop/Yufeichi-Platform-V1.0-审查与7天冲刺计划.md` 的 Day1 Checklist；未将旧 Word 文档、旧上下文中的其他阶段作为开发任务。

## 结论

**Day1「认证和构建基线」的代码修复及本地功能验收通过：完整 `clean verify`，50 项测试通过，0 失败、0 错误、0 跳过。**

补充只读核验（2026-09-27 14:24）：SSH、sudo、网站目录、Nginx 配置及证书续期任务已确认。用户确认尚未备份，检查范围内未发现项目备份；该现状已明确记录，实际创建备份仍属于后续部署工作。至此 Day1 的检查与风险登记已完成，不代表生产部署、备份恢复已完成。

初次验收时遵照用户要求未执行 Git add/commit；后续用户已明确授权本次 Day1 提交并推送，提交按认证修复、构建与测试、文档三个范围划分。没有开发 Day2 或后续业务，没有修改 V1—V9，没有 Flyway clean/repair，没有跳过测试，没有修改开发库数据或生产服务器。

## 开始状态与保护范围

- 分支 `dev`，HEAD `d5e89b1`。开始时已跟踪文件无修改；已有未跟踪 `yufeichi-web/.vscode/`，原样保留。
- 先检查项目结构、Git 状态、后端配置、认证链、查询、测试和当前依赖，再按 Day1 顺序修复。
- 后端原有 36 个 Java 源文件及现有分层保留；前端未改动，没有添加业务空类。
- 原冲刺计划正文 SHA-256 校验不变；V1—V9 的 SHA-256 逐文件校验不变。
- 开发 MySQL 仅执行只读版本、历史表查询和 Flyway `validateWithResult/info`；测试数据只写入独立临时 MySQL 容器。

## 按 Checklist 顺序的执行结果

| 顺序 | 项目 | 结果与证据 |
|---|---|---|
| 1 | 固定 JDK 21，核对 IDE/Maven | Maven Wrapper 3.9.16 实际运行 Temurin 21.0.11；IDE Project SDK/Maven Runner 均为 temurin-21；JDK24 下 validate 被 Enforcer 明确拒绝 |
| 2 | Lombok annotation processor | 显式指定 Lombok、Spring Boot configuration processor，release=21；实际编译、完整测试和打包通过 |
| 3 | 测试启动入口、test profile | 显式指定 YufeichiServerApplication；强制 test profile；MySQL/Redis 动态端口来自独立容器；断言 DATABASE()=yufeichi_test |
| 4 | 禁用用户旧 Token | 每次请求加载账户并检查 AccountStatus；真实登录取得 Token 后禁用测试账户，原 Token 返回401 |
| 5 | 删除用户、非法 subject、故障分类 | 删除/不存在用户、非法/溢出 subject、过期/篡改/缺少过期时间统一401；模拟数据库故障500，登录基础设施异常不再转成错误密码 |
| 6 | 两条 DISTINCT SQL | 按返回的 role_code/permission_code 排序；不再引用 DISTINCT 结果外的排序列 |
| 7 | Docker/MySQL/Redis/Flyway | 现有 MySQL8.4.11、Redis7.4.10 正常，Redis PONG；开发库 V1—V9 全SUCCESS、校验和有效、无待迁移 |
| 8 | 独立测试库权限查询 | 默认 ONLY_FULL_GROUP_BY 下旧SQL真实失败；新SQL通过，多角色权限去重、编码排序、停用/删除角色及权限过滤通过 |
| 9 | 真实 HTTP 状态 | 400/401/403/404/409/413/429/500 与业务码对应；另覆盖405、非法JSON、参数类型；成功仍code=0 |
| 10 | springdoc/Knife4j | springdoc三个模块统一2.8.17；保留Knife4j4.5.0静态UI；实际GET /v3/api-docs返回200且含openapi，/doc.html返回HTML，swagger-config正常 |
| 11 | 真实 API 认证场景 | 下表各场景均经嵌入式Tomcat真实HTTP验证，未用MockMvc冒充登录集成验收 |
| 12 | 运维准备 | DNS/HTTPS/证书、SSH/sudo、网站与Nginx路径、续期定时器已核对；确认项目尚未备份，标记Day6风险；生产秘密/初始化账号方案已写入README |
| 13 | README | 区分已实现与规划，说明JDK21、真实JAR名、Compose与Java变量映射、测试隔离及当前边界 |
| 14—15 | Git提交 | 初次验收保留供审查；后续按用户明确授权分组提交并推送，提交编号以Git历史为准 |

## Bug 原因与修复

| 问题 | 原因 | 修复 |
|---|---|---|
| Maven/JDK漂移、Lombok失败 | release=21只控制字节码；默认Maven可能使用JDK24，POM未显式指定处理器 | JDK21启动脚本、版本标记、Enforcer运行时门禁、显式annotationProcessorPaths |
| contextLoads找不到启动配置 | 测试位于com.yufeichi，启动类在子包com.yufeichi.server，自动向上搜索找不到 | 显式SpringBootTest(classes=...)；保留原包路径避免无必要搬迁 |
| 测试可能访问dev | 公共配置默认dev，原测试无独立数据库配置 | test资源配置强制隔离、Testcontainers动态连接、无开发连接回退；测试配置不打入应用JAR |
| 禁用账户旧Token继续认证 | 签名验证后直接构造已认证Authentication，绕过账户状态检查 | 构造认证前执行AccountStatusUserDetailsChecker |
| 删除账户、非法subject处理不统一 | 用户查找/数值解析异常缺少统一认证失败处理 | 缺失账户、JWT/subject异常统一由认证入口返回401；要求正数Long subject与expiration |
| DB故障被当成登录失败 | 宽泛捕获AuthenticationException会包含基础设施异常 | Filter中DataAccessException/AuthenticationServiceException单独500；AuthService优先重新抛出服务异常；Advice对应500 |
| Filter可能重复注册 | @Component Filter既被Servlet自动注册，又被加入Security链 | 禁用Servlet容器自动注册，只保留Security链注册 |
| MySQL DISTINCT排序失败 | 排序列sort_order/id不在DISTINCT投影里，与MySQL默认模式不兼容 | 两条查询按各自返回的编码排序，保持DISTINCT及原过滤条件 |
| 异常HTTP200/不正确500 | 原Advice只返回Result；请求格式及权限等错误落到通用异常 | ErrorCode显式HttpStatus映射，Advice返回ResponseEntity，增加相应异常处理，未知业务码默认500 |
| /v3/api-docs 500 | 旧springdoc2.3.0调用Spring6.2已删除方法；单独升级后Knife4j旧starter又调用不兼容getGroupConfigs方法 | 使用springdoc2.8.17提供生成与配置，Knife4j仅保留静态UI，不保留不兼容增强starter |
| README功能混淆 | 原文列出目标模块却未区分是否有实际代码 | 改为实际四个接口、已有基础能力及分日规划，明确未实现撤销/限流/业务页面 |

springdoc版本选择参考[官方兼容矩阵](https://springdoc.org/v2/#_what_is_the_compatibility_matrix_of_springdoc_openapi_with_spring_boot)：Boot3.5对应2.8系列；最终以本项目实际HTTP验收为准。没有升级Spring Boot大版本或改变项目架构。

## 测试及真实 HTTP 结果

| 测试类 | 项数 | 失败/错误/跳过 |
|---|---:|---|
| JwtAuthenticationFilterTest | 16 | 0/0/0 |
| AuthServiceTest | 1 | 0/0/0 |
| GlobalExceptionHandlerTest | 16 | 0/0/0 |
| YufeichiServerApplicationTests | 17 | 0/0/0 |
| 合计 | **50** | **0/0/0** |

| 实际请求场景 | HTTP | code/检查 |
|---|---:|---|
| 正确密码登录 | 200 | 0；返回Token和permissions |
| 正常Token调用 /api/auth/me | 200 | 0；admin与super_admin正确，无password字段 |
| 错误密码 | 401 | 50001，保留原业务码 |
| 空JSON登录 | 400 | 40000 |
| 无Token访问me | 401 | 40100 |
| 过期Token | 401 | 40100 |
| 修改payload但保留原签名 | 401 | 40100 |
| 非数字/溢出subject | 401 | 40100 |
| 无expiration/不存在用户 | 401 | 40100 |
| 禁用账户的旧Token | 401 | 40100；禁用前相同Token返回200 |
| 禁用账户重新登录 | 401 | 50002 |
| 已逻辑删除账户旧Token | 401 | 40100；删除前相同Token返回200 |
| /v3/api-docs | 200 | 有openapi、登录path和BearerAuth定义 |
| /doc.html、/v3/api-docs/swagger-config | 200 | HTML与正确的文档URL |

认证异常Token还在公开health路径验证了统一401。数据库中断使用单元测试故障注入，确认500、无敏感异常信息返回、认证链不继续；没有为了测试停止开发MySQL。403/409/413/429等异常映射通过独立MVC测试端点验证，未新增生产业务端点，未提前实现上传或限流。

## Flyway 证据

现有开发库只读 `validateWithResult` 成功，9条历史记录均成功，`info().pending()`为空。隔离测试库从空库正常应用同一套V1—V9，再次校验通过。

| 版本 | checksum | 状态 |
|---|---:|---|
| V1 | 1457891991 | SUCCESS |
| V2 | -2112776796 | SUCCESS |
| V3 | -663793275 | SUCCESS |
| V4 | 2115728188 | SUCCESS |
| V5 | 1792074382 | SUCCESS |
| V6 | 1697554631 | SUCCESS |
| V7 | -1290070349 | SUCCESS |
| V8 | -728566509 | SUCCESS |
| V9 | -1751204810 | SUCCESS |

Flyway11.7.2仍打印“MySQL8.4超出其声明测试范围”的兼容性提示；本次真实迁移和校验成功，没有为消除提示改变历史SQL或执行repair。

## 实际执行的主要命令

均在当前项目执行。Maven命令使用本机临时代理settings；该文件不写入仓库，以下变量仅在执行进程生效。

```powershell
git status --short
git branch --show-current
git rev-parse --short HEAD
.\scripts\mvn21.ps1 -version
.\scripts\mvn21.ps1 -B -s "$env:TEMP\yufeichi-day1-maven-settings.xml" compile
.\scripts\mvn21.ps1 -B -s "$env:TEMP\yufeichi-day1-maven-settings.xml" test-compile
.\scripts\mvn21.ps1 -B -s "$env:TEMP\yufeichi-day1-maven-settings.xml" '-Dtest=JwtAuthenticationFilterTest,AuthServiceTest' test
.\scripts\mvn21.ps1 -B -s "$env:TEMP\yufeichi-day1-maven-settings.xml" '-Dtest=GlobalExceptionHandlerTest' test
docker desktop start
docker ps
# 定向数据库、文档、HTTP验收和最终全套验收：
$env:TESTCONTAINERS_RYUK_DISABLED='true'
$env:TESTCONTAINERS_CHECKS_DISABLE='true'
.\scripts\mvn21.ps1 -B -s "$env:TEMP\yufeichi-day1-maven-settings.xml" '-Dtest=YufeichiServerApplicationTests' test
.\scripts\mvn21.ps1 -B -s "$env:TEMP\yufeichi-day1-maven-settings.xml" '-Dtest=YufeichiServerApplicationTests#openApiAndKnife4jAreAvailableOverHttp' test
.\scripts\mvn21.ps1 -B -s "$env:TEMP\yufeichi-day1-maven-settings.xml" clean verify
.\scripts\mvn21.ps1 -B -s "$env:TEMP\yufeichi-day1-maven-settings.xml" dependency:tree '-Dincludes=org.springdoc:*,com.github.xiaoymin:*'
docker ps -a --filter label=org.testcontainers=true --format '{{.Names}} {{.Image}} {{.Status}}'
Resolve-DnsName yufeichi.com -Type A
Resolve-DnsName www.yufeichi.com -Type A
Invoke-WebRequest https://yufeichi.com -Method Head
Invoke-WebRequest https://www.yufeichi.com -Method Head
git diff --check
git diff --stat
```

此外：在后端目录使用默认JDK24执行 `mvnw.cmd -B -o -s <同一临时settings> validate`，确认被JDK21门禁拒绝；通过 `docker exec` 查询MySQL版本/迁移历史以及Redis PING/INFO；临时Java程序`Day1FlywayCheck.java`只调用Flyway validate/info验证已有开发库，凭据仅在内存环境变量传递，未写入报告；用SslStream默认信任/域名验证读取TLS证书；Get-FileHash比对迁移和计划；ZipFile读取最终JAR清单及class major version。

过程问题均处理后复测：初次Ryuk镜像拉取遇Docker Hub TLS EOF；单独升级springdoc暴露Knife4j增强组件二进制不兼容；测试清理时直接关闭Spring context导致afterTestClass异常，改为只停止Redis连接工厂，由框架管理context。上述失败没有被隐藏或通过跳过测试处理，最终clean verify全部通过。

本机Docker Hub不可达，因此临时关闭Ryuk及辅助预检，用已缓存的真实mysql:8.4/redis:7执行测试；JUnit正常回收容器，最终按Testcontainers标签检查无残留。开发的yufeichi-mysql、yufeichi-redis保留并正常运行。未将此绕过作为全局配置提交。

## 修改文件与 git diff 摘要

已跟踪文件11个，`git diff --stat`：**568行新增、107行删除**。另新增7个文件；未跟踪文件不计入普通git diff统计。此处统计为初次验收时的未提交差异；后续用户已授权分组提交并推送。

| 文件（仓库相对路径） | 变更 |
|---|---|
| README.md | 已实现/规划、运行测试、JAR名、变量映射、生产准备及外部风险 |
| yufeichi-server/pom.xml | JDK21门禁、编译处理器、隔离测试依赖、兼容文档依赖 |
| yufeichi-server/src/main/java/com/yufeichi/server/common/error/ErrorCode.java | HTTP状态映射 |
| yufeichi-server/src/main/java/com/yufeichi/server/config/SecurityConfig.java | 禁止Filter重复注册 |
| yufeichi-server/src/main/java/com/yufeichi/server/exception/GlobalExceptionHandler.java | 真实状态码及安全错误响应 |
| yufeichi-server/src/main/java/com/yufeichi/server/mapper/UserMapper.java | 两处排序修复 |
| yufeichi-server/src/main/java/com/yufeichi/server/security/JwtAuthenticationFilter.java | 账户状态及认证/基础设施异常分类 |
| yufeichi-server/src/main/java/com/yufeichi/server/security/JwtTokenProvider.java | subject/expiration有效性校验 |
| yufeichi-server/src/main/java/com/yufeichi/server/service/impl/AuthServiceImpl.java | 保留基础设施异常语义 |
| yufeichi-server/src/main/resources/application-dev.yml | 去除不再使用的Knife4j增强开关 |
| yufeichi-server/src/test/java/com/yufeichi/YufeichiServerApplicationTests.java | 启动入口、独立容器、SQL/Flyway/HTTP集成验收 |
| .java-version（新增） | 21 |
| scripts/mvn21.ps1（新增） | 固定本次Maven命令的JDK21 |
| yufeichi-server/src/test/resources/application-test.yml（新增） | 无dev回退的独立测试配置 |
| yufeichi-server/src/test/java/com/yufeichi/server/security/JwtAuthenticationFilterTest.java（新增） | JWT认证回归 |
| yufeichi-server/src/test/java/com/yufeichi/server/service/AuthServiceTest.java（新增） | 登录基础设施异常回归 |
| yufeichi-server/src/test/java/com/yufeichi/server/exception/GlobalExceptionHandlerTest.java（新增） | HTTP状态和成功协议回归 |
| docs/Day1-执行与验收报告.md（新增） | 本报告 |

另有已被Git忽略的本机IDE workspace.xml补充Maven Runner为temurin-21；Project SDK原已是temurin-21。未改动已有前端.vscode文件，不将其混入本次改动。

最终JAR为 `D:/Desktop/yufeichi-platform/yufeichi-server/target/yufeichi-server-0.0.1-SNAPSHOT.jar`，55,383,952字节；Manifest记录Build-Jdk-Spec=21、正确Start-Class，应用class major=65；不存在application-test.yml，打包内只有springdoc2.8.17及Knife4j4.5.0静态UI。

## 未完成的核验与边界

- 已按用户授权连接 ubuntu@122.51.218.155，已知SSH主机密钥严格校验通过，sudo可用。仅执行只读检查，未修改服务器配置、部署应用或创建备份。用户确认尚未备份；/var/backups 中可见的是系统包管理备份，不能代替项目备份。
- yufeichi.com与www.yufeichi.com解析122.51.218.155，HTTPS均200；默认TLS校验通过。证书由Let's Encrypt YE2签发，有效期2026-09-25 15:06:25至2026-12-24 15:06:24（北京时间）。已确认 snap.certbot.renew.timer 为 enabled/active；最近一次续期检查服务退出码0、Result=success。未执行续期演练或远程部署；任务成功不等于已经验证未来实际签发和恢复。
- Day1已明确prod秘密和管理员初始化方案，但生产模板、Bootstrap、Token撤销、登录限流、业务功能仍按Day5及后续安排，没有提前实现。
- 原计划正文完全未修改。Git操作以用户后续明确授权为准；本次允许分组提交及普通推送，禁止强制推送。

因此：**Day1代码、本地验收和运维准备现状核对已完成；项目备份尚未建立，保留为Day6风险。** 不将“编译成功”视作完整功能通过，也不将本次Day1验收等同于V1生产发布门禁通过。

## 服务器只读核验补充（2026-09-27 14:24，Asia/Shanghai）

用户明确授权密码SSH只读检查。密码未写入命令行参数、项目或本报告；使用SSH密码提示输入。未读取私钥、.env内容，未执行reload/restart、软件安装、证书签发、数据库操作或备份创建。

| 检查项 | 实际结果 |
|---|---|
| SSH及权限 | ubuntu@122.51.218.155 可连接；严格使用已有known_hosts验证；ubuntu在sudo组，sudo -n可用 |
| Nginx | 服务active；nginx -T通过 |
| 主配置 | /etc/nginx/nginx.conf |
| 站点配置 | /etc/nginx/sites-enabled/yufeichi → /etc/nginx/sites-available/yufeichi |
| 网站根目录 | /var/www/yufeichi；首页home.html，另有beian.png及releases目录 |
| 当前路由 | HTTP跳转HTTPS；静态文件try_files $uri $uri/ =404；当前站点配置未发现proxy_pass/API后端代理 |
| 后端预留目录 | /opt/yufeichi/backend、/opt/yufeichi/deploy、/opt/yufeichi/scripts已存在；限定深度3检查未发现文件 |
| 监听端口 | 22、80、443及本机DNS；未见8080、3306、6379监听 |
| Docker/Java命令 | root审计命令未取得运行中Docker清单；ubuntu PATH中command -v docker/java无结果，不据此断言磁盘上绝无安装 |
| 证书配置 | /etc/letsencrypt/renewal/yufeichi.com.conf；authenticator/installer均为nginx |
| 证书公开文件 | /etc/letsencrypt/live/yufeichi.com/fullchain.pem；有效期与公开TLS检查一致 |
| 自动续期 | snap.certbot.renew.timer已启用且active；最近执行2026-09-27 01:48:01—01:48:03，Result=success，ExecMainStatus=0；检查时下次时间16:01 |
| 项目备份 | 用户确认未备份；指定站点/项目目录与/var/backups检查未发现项目配置备份。未扫描整个服务器，不将系统apt/dpkg备份当项目备份 |

主要只读命令：`id`、`hostname`、`sudo -n true`、`nginx -T`（只输出路径和路由白名单字段）、`systemctl list-units/list-timers/show`、`ss -lnt`、`readlink -f`、限定目录/深度的文件名清单、`openssl x509 -noout -subject -issuer -dates`。没有执行 certbot renew --dry-run，因为本轮只授权只读检查。

后续部署前应建立项目配置备份并确定恢复位置；可选规划目录为 `/var/backups/yufeichi`，但当前没有创建，也没有声称备份或恢复演练已通过。Day1要求的是核对现状并登记风险，备份实施仍按Day6/Day7安排。
