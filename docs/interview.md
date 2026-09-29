# Yufeichi Platform V1.0 演示与面试说明

## 3 分钟演示

1. 打开 `https://yufeichi.com`，展示首页、文章列表/详情和项目列表。
2. 登录后台，展示文章、分类、标签和项目管理。
3. 展示封面上传、草稿/发布以及前台可见性。
4. 说明 JWT 45 分钟、Redis Token 撤销、登录限流和固定 RBAC。
5. 说明生产链路：Nginx → Spring Boot → MySQL/Redis。
6. 展示 Day7 备份恢复和兼容回滚结果。

## 推荐讲清楚

- JWT 请求为什么重新查用户和权限：禁用、删除、撤权立即生效。
- logout 为什么需要 Redis：无状态 JWT 本身无法主动失效。
- 为什么 V1 不做 Refresh Token：控制首版安全复杂度。
- 文章与标签为什么需要事务。
- 文件系统为什么不能被数据库事务自动回滚。
- 为什么 Flyway 历史迁移不可修改。
- 为什么代码 release 回滚不等于数据库 schema 回滚。
- 为什么“有备份”还必须做独立恢复演练。

## 不夸大

- 不宣称微服务、MQ、Kubernetes 或高并发。
- 不编造 QPS 或性能提升比例。
- 留言、评论、搜索、统计、完整用户/角色/权限后台属于后续版本。
- Flyway/MySQL 8.4、Mockito agent 和 Vite 大 chunk 是已知技术债。
