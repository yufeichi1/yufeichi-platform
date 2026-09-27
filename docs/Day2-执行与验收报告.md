# Day2 执行与验收报告

## 结论

**Day2「核心API和上传」实现及本地验收完成。** Java21执行完整 `clean verify` 成功，79项测试全部通过，0失败、0错误、0跳过。最终构建完成时间：2026-09-27 14:59:47（Asia/Shanghai）。额度中断后核对测试XML、构建日志和源文件时间，源码未在最终构建后发生变化。

执行依据为用户提供的《Yufeichi-Platform-V1.0-审查与7天冲刺计划.md》Day2 Checklist。沿用现有 Controller→Service→Mapper 分层及V2权限码，没有开发Day3页面或部署远端服务器。

## 起点与保护范围

- 起始分支dev、提交82c889d；已有未跟踪的 yufeichi-web/.vscode/ 保留，不纳入提交。
- V3/V4/V7满足字段需求，不新增V10。V1—V9的SHA-256与Day1基线逐一相同，原冲刺计划校验值也未改变。
- 数据写入仅在Testcontainers独立测试库完成，图片仅写入临时测试目录；没有修改开发库数据、开发账号或生产服务器。
- 原Day1集成测试也补充临时上传目录，避免新文件服务写入开发目录。

## Checklist完成情况

| 顺序 | 要求 | 实际结果 |
|---|---|---|
| 1 | V3实体与Mapper | Article、Category、Tag、ArticleTag逐字段对应原表，保留逻辑删除和时间填充 |
| 2 | DTO验证 | 必填、长度、正数ID、分页、状态、URL协议校验；pageSize最大100 |
| 3 | 分类/标签 | 新增、编辑、列表、逻辑删除；唯一冲突409，仍被未删除文章引用时409 |
| 4 | 文章管理 | 后台分页、详情、新增、修改、删除、发布、下架；作者取当前登录用户 |
| 5 | 标签事务 | 去重、存在/启用校验、批量插入；文章和关系更新同一事务 |
| 6 | 公开文章 | 强制已发布且未删除；草稿/下架/删除/不存在详情均404 |
| 7 | 项目 | V4对应CRUD，默认隐藏，支持展示/隐藏；公开只返回展示记录 |
| 8 | 权限 | 22个后台端点均有权限注解；只读角色读取成功、写入403，匿名401 |
| 9 | 图片验证 | FileInfo及对应DTO/VO/Mapper/Service/Controller；解码JPEG/PNG/WebP、枚举目录、UUID、大小/像素限制 |
| 10 | 补偿及信息隔离 | 临时文件→元数据→移动；事务回滚补偿；返回相对URL，不返回磁盘路径 |
| 11 | 公开读取及持久性 | WebMvc uploads映射，路径白名单和符号链接检查；真实应用关闭/重启后仍可读取 |
| 12 | 测试 | 文章标签回滚、公开过滤、权限、伪图片/路径/超限/像素、上传回滚均通过 |
| 13 | 迁移 | 无实际结构变更需求，不新增迁移，不修改历史SQL |
| 14 | Git | 按Day2清单分为内容API及图片上传两次本地提交，编号以Git历史为准；未扩大为远程推送 |

## 接口与实现约定

新增28个API端点：后台22个、公开内容6个；另有静态图片映射。完整路径、权限、DTO和错误合同见 [Day2 API说明](api/Day2-核心API与上传.md)。

- 文章新建固定草稿，authorId来自认证上下文；客户端不能直接设置作者、状态或浏览量。发布/下架需要article:publish。
- PUT完整替换可编辑字段，nullable字段允许写NULL，标签缺省或空数组清空关联。修复了MyBatis-Plus默认忽略NULL会导致清空操作无效的问题。
- 分类/标签逻辑删除不释放name/slug唯一值，再次使用返回409；未引入额外恢复接口。
- 关联写入与分类/标签删除使用行锁，标签去重后按ID排序。关系写入失败会回滚文章内容和旧关系。
- 公开可见性由后端条件强制限制；文章列表不读取或返回正文，详情使用独立VO。
- 图片最长边8192、总像素1600万，上传及重编码结果最大5MiB。先检查尺寸再解码；JPEG重编码为JPEG，PNG/WebP重编码为PNG，剥离元数据及尾随内容，动画只保留首帧。
- WebP采用TwelveMonkeys ImageIO 3.15.0的imageio-webp模块，参考 [官方格式支持说明](https://github.com/haraldk/TwelveMonkeys)。未引入SVG或任意附件支持。
- 公开图片只允许枚举目录、UUID文件名和png/jpg扩展名，不提供临时文件、任意路径或目录列表；UPLOAD_PATH确定持久化根目录。
- 文件系统不受数据库事务自动回滚，因此注册事务完成回调：元数据失败清理临时文件，移动后事务回滚清理目标文件。清理IO失败记录日志；不声称进程崩溃具备分布式事务保证。
- multipart文件上限5MB、请求6MB，Tomcat有界max-swallow-size为8MB，确保常见超限请求能收到完整413。极大请求仍可能被断开，未设置无限丢弃预算。

## 验证结果

| 测试类 | 测试数 | 失败/错误/跳过 |
|---|---:|---|
| Day2IntegrationTests | 13 | 0/0/0 |
| ImageStorageTest | 16 | 0/0/0 |
| YufeichiServerApplicationTests | 17 | 0/0/0 |
| JwtAuthenticationFilterTest | 16 | 0/0/0 |
| GlobalExceptionHandlerTest | 16 | 0/0/0 |
| AuthServiceTest | 1 | 0/0/0 |
| 合计 | **79** | **0/0/0** |

Day2真实HTTP覆盖：分类/标签CRUD与409、文章创建→发布→公开读取→下架→删除、标签去重和清空、关联与参数400、项目隐藏/展示、后台写端点权限、真实PNG/JPEG/WebP上传及公开读取、伪图片/错误扩展名/空文件/目录穿越/超限拒绝，以及OpenAPI认证和分页合同。

事务故障在独立测试环境通过Mapper spy注入：关系插入失败后原标题和旧关系保留，新文章创建全部回滚；文件元数据失败后无临时文件残留；移动文件后主动回滚事务，元数据和目标文件同时消失。没有为测试停止或破坏开发数据库。

图片持久性测试使用同一临时目录和独立数据库，启动新的真实HTTP应用、关闭、再启动另一个新实例，两次都能读取并解码原图片。尺寸测试通过修改小PNG头部的尺寸声明，验证分配大图前拒绝；路径和尾随脚本内容也有测试。WebP夹具为本地生成的8×6纯色图片。

## 执行命令及产物

从项目根目录执行，Maven使用本机临时代理settings，未将机器秘密或代理配置写入仓库：

```powershell
git status --short
git branch --show-current
docker desktop start
.\scripts\mvn21.ps1 -B -s "$env:TEMP\yufeichi-day1-maven-settings.xml" compile
$env:TESTCONTAINERS_RYUK_DISABLED='true'
$env:TESTCONTAINERS_CHECKS_DISABLE='true'
.\scripts\mvn21.ps1 -B -s "$env:TEMP\yufeichi-day1-maven-settings.xml" '-Dtest=Day2IntegrationTests' test
.\scripts\mvn21.ps1 -B -s "$env:TEMP\yufeichi-day1-maven-settings.xml" '-Dtest=Day2IntegrationTests,ImageStorageTest' test
.\scripts\mvn21.ps1 -B -s "$env:TEMP\yufeichi-day1-maven-settings.xml" clean verify
git diff --check
```

Maven实际运行Java21。沿用Day1本机网络所需的Testcontainers辅助镜像绕过；MySQL8.4、Redis7均为真实独立容器，没有跳过业务测试。两组数据库分别为yufeichi_test/day2_test。Flyway已有的MySQL8.4兼容范围提示仍存在，但实际9个迁移及校验通过。

最终产物为 yufeichi-server/target/yufeichi-server-0.0.1-SNAPSHOT.jar，56,056,046字节。最终构建后仅补充文档和核查。

## 过程问题与边界

- 初次测试时Docker未启动，启动后继续，没有切换为开发数据库。
- 测试Container通配符导入歧义改为显式导入；生命周期确保容器先启动再加载应用。
- 真实超大请求起初导致连接中断，调整Tomcat有界丢弃预算后，413验收通过。
- 原.gitignore忽略storage目录，因此文件服务包放在service/upload；没有强制纳入运行数据目录。
- 补充OpenAPI Bearer及分页声明后重新执行完整79项验收。

Day2必需业务及验证没有未完成项。本轮未实现前端页面、文件管理/删除、生产部署、Token撤销、登录限流或备份恢复；这些不属于本次Day2清单。服务器尚未建立项目备份的Day1风险继续保留，部署前必须处理。

本轮按原计划执行本地提交；远程推送需用户明确指定。原有.vscode、秘密、私钥、本地.env和运行数据不纳入提交。

## 修改文件清单

共 56 个文件，排除原有前端.vscode。

- README.md
- docs/Day2-执行与验收报告.md
- docs/api/Day2-核心API与上传.md
- yufeichi-server/pom.xml
- yufeichi-server/src/main/java/com/yufeichi/server/common/error/ErrorCode.java
- yufeichi-server/src/main/java/com/yufeichi/server/config/WebMvcConfig.java
- yufeichi-server/src/main/java/com/yufeichi/server/controller/AdminArticleController.java
- yufeichi-server/src/main/java/com/yufeichi/server/controller/AdminCategoryController.java
- yufeichi-server/src/main/java/com/yufeichi/server/controller/AdminProjectController.java
- yufeichi-server/src/main/java/com/yufeichi/server/controller/AdminTagController.java
- yufeichi-server/src/main/java/com/yufeichi/server/controller/ArticleController.java
- yufeichi-server/src/main/java/com/yufeichi/server/controller/FileController.java
- yufeichi-server/src/main/java/com/yufeichi/server/controller/ProjectController.java
- yufeichi-server/src/main/java/com/yufeichi/server/controller/TaxonomyController.java
- yufeichi-server/src/main/java/com/yufeichi/server/dto/ArticleQuery.java
- yufeichi-server/src/main/java/com/yufeichi/server/dto/ArticleWriteRequest.java
- yufeichi-server/src/main/java/com/yufeichi/server/dto/CategoryWriteRequest.java
- yufeichi-server/src/main/java/com/yufeichi/server/dto/FileUploadRequest.java
- yufeichi-server/src/main/java/com/yufeichi/server/dto/PageQuery.java
- yufeichi-server/src/main/java/com/yufeichi/server/dto/ProjectQuery.java
- yufeichi-server/src/main/java/com/yufeichi/server/dto/ProjectStatusRequest.java
- yufeichi-server/src/main/java/com/yufeichi/server/dto/ProjectWriteRequest.java
- yufeichi-server/src/main/java/com/yufeichi/server/dto/TagWriteRequest.java
- yufeichi-server/src/main/java/com/yufeichi/server/entity/Article.java
- yufeichi-server/src/main/java/com/yufeichi/server/entity/ArticleTag.java
- yufeichi-server/src/main/java/com/yufeichi/server/entity/Category.java
- yufeichi-server/src/main/java/com/yufeichi/server/entity/FileInfo.java
- yufeichi-server/src/main/java/com/yufeichi/server/entity/Project.java
- yufeichi-server/src/main/java/com/yufeichi/server/entity/Tag.java
- yufeichi-server/src/main/java/com/yufeichi/server/exception/GlobalExceptionHandler.java
- yufeichi-server/src/main/java/com/yufeichi/server/mapper/ArticleMapper.java
- yufeichi-server/src/main/java/com/yufeichi/server/mapper/ArticleTagMapper.java
- yufeichi-server/src/main/java/com/yufeichi/server/mapper/CategoryMapper.java
- yufeichi-server/src/main/java/com/yufeichi/server/mapper/FileInfoMapper.java
- yufeichi-server/src/main/java/com/yufeichi/server/mapper/ProjectMapper.java
- yufeichi-server/src/main/java/com/yufeichi/server/mapper/TagMapper.java
- yufeichi-server/src/main/java/com/yufeichi/server/security/CurrentUser.java
- yufeichi-server/src/main/java/com/yufeichi/server/service/ArticleService.java
- yufeichi-server/src/main/java/com/yufeichi/server/service/FileService.java
- yufeichi-server/src/main/java/com/yufeichi/server/service/ProjectService.java
- yufeichi-server/src/main/java/com/yufeichi/server/service/TaxonomyService.java
- yufeichi-server/src/main/java/com/yufeichi/server/service/upload/ImageStorage.java
- yufeichi-server/src/main/java/com/yufeichi/server/service/upload/UploadBizType.java
- yufeichi-server/src/main/java/com/yufeichi/server/vo/ArticleSummaryVO.java
- yufeichi-server/src/main/java/com/yufeichi/server/vo/ArticleVO.java
- yufeichi-server/src/main/java/com/yufeichi/server/vo/CategoryVO.java
- yufeichi-server/src/main/java/com/yufeichi/server/vo/FileVO.java
- yufeichi-server/src/main/java/com/yufeichi/server/vo/ProjectVO.java
- yufeichi-server/src/main/java/com/yufeichi/server/vo/TagVO.java
- yufeichi-server/src/main/resources/application-dev.yml
- yufeichi-server/src/main/resources/application.yml
- yufeichi-server/src/test/java/com/yufeichi/Day2IntegrationTests.java
- yufeichi-server/src/test/java/com/yufeichi/YufeichiServerApplicationTests.java
- yufeichi-server/src/test/java/com/yufeichi/server/service/ImageStorageTest.java
- yufeichi-server/src/test/resources/application-test.yml
- yufeichi-server/src/test/resources/fixtures/sample.webp
