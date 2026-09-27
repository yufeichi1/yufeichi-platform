# Day2 核心 API 与图片上传

路径保留 `/api` 前缀。后台请求携带 `Authorization: Bearer <token>`，从已有登录接口取得Token。所有JSON响应沿用Result：成功HTTP200且code=0；错误同时有对应HTTP状态和业务码。

## 后台接口与权限

| 方法与路径 | 权限 | 行为 |
|---|---|---|
| GET /api/admin/categories | category:list | 全部未删除分类（包含停用） |
| POST /api/admin/categories | category:add | 新建分类 |
| PUT /api/admin/categories/{id} | category:update | 完整替换分类可编辑字段 |
| DELETE /api/admin/categories/{id} | category:delete | 未被文章引用时逻辑删除 |
| GET /api/admin/tags | tag:list | 全部未删除标签（包含停用） |
| POST /api/admin/tags | tag:add | 新建标签 |
| PUT /api/admin/tags/{id} | tag:update | 完整替换标签可编辑字段 |
| DELETE /api/admin/tags/{id} | tag:delete | 未被文章引用时逻辑删除 |
| GET /api/admin/articles | article:list | 文章分页 |
| GET /api/admin/articles/{id} | article:list | 文章详情，含正文、tagIds、tags |
| POST /api/admin/articles | article:add | 固定草稿，作者取当前用户 |
| PUT /api/admin/articles/{id} | article:update | 编辑正文及关联，保留作者和状态 |
| DELETE /api/admin/articles/{id} | article:delete | 逻辑删除文章并移除标签关系 |
| POST /api/admin/articles/{id}/publish | article:publish | 状态改1，首次发布记录publishedAt |
| POST /api/admin/articles/{id}/unpublish | article:publish | 状态改2 |
| GET /api/admin/projects | project:list | 项目分页 |
| GET /api/admin/projects/{id} | project:list | 项目详情 |
| POST /api/admin/projects | project:add | 新建，默认隐藏 |
| PUT /api/admin/projects/{id} | project:update | 完整替换项目可编辑字段 |
| PUT /api/admin/projects/{id}/status | project:update | JSON `{ "status": 0或1 }` |
| DELETE /api/admin/projects/{id} | project:delete | 逻辑删除 |
| POST /api/admin/files/upload | file:upload | multipart图片上传 |

角色使用现有V2权限码，无新权限迁移。匿名后台请求401，已登录但缺少对应权限403。拥有列表权限不自动拥有写权限。

## 公开接口

- `GET /api/categories`、`GET /api/tags`：只返回启用且未删除项。
- `GET /api/articles`、`GET /api/articles/{id}`：只返回已发布且未删除文章。
- `GET /api/projects`、`GET /api/projects/{id}`：只返回展示且未删除项目。
- `GET /uploads/{bizType}/{uuid}.png` 或 `.jpg`：公开规范化图片；不存在404，临时文件及任意路径不允许读取。

公开查询即使传入status=0也不会返回草稿/隐藏数据。公开详情对草稿、下架、隐藏、已删除及不存在ID统一404。公开文章tags仅含启用标签。

## JSON 请求字段

分类创建/更新：name必填≤50；slug必填≤80且只允许小写字母/数字及中间连字符；description≤255；sortOrder默认0、范围0—1000000；status默认1、范围0/1。

标签创建/更新：name、slug与分类相同；status默认1、范围0/1。

文章创建/更新：

```json
{
  "title": "第一篇文章",
  "summary": "简短介绍",
  "content": "# Markdown正文",
  "coverUrl": null,
  "categoryId": null,
  "tagIds": [],
  "isTop": 0,
  "isFeatured": 0
}
```

- title必填≤200，summary≤500，content必填≤200000字符，coverUrl≤500。
- categoryId可空，否则正数且必须为存在、启用分类；tagIds最多50项，每项正数，服务端去重并校验存在/启用。
- isTop/isFeatured默认0，只允许0/1。authorId/status/viewCount不在写DTO中，客户端无法直接指定。
- 文章新增、更新、标签替换在同一个数据库事务内；标签按ID排序加锁避免与删除并发产生无效关联。
- PUT采用完整字段替换；可选字段null清空，tagIds为空或缺省清空标签。不要将PUT用作仅发单字段的PATCH。

项目创建/更新：name必填≤100，description必填≤20000，coverUrl/githubUrl/demoUrl/techStack各≤500；sortOrder默认0、范围0—1000000；status默认0、范围0/1。githubUrl/demoUrl仅允许http(s)或空；coverUrl允许http(s)或本项目UUID上传URL。Markdown暂只存储，不输出已净化HTML，前端渲染净化属于Day4。

## 查询和错误

分页参数：pageNum默认1、范围1—1000000；pageSize默认10、范围1—100；keyword最多100字符。文章另支持categoryId/tagId（正数）及后台status=0/1/2；项目后台status=0/1。固定排序：文章置顶、发布时间、ID倒序；项目排序值升序、ID倒序。

分页返回data包含records、total、pageNum、pageSize。文章列表不读取或返回正文，详情返回正文与标签。

常见错误：参数400、未认证401、无权限403、不存在/不公开404、重名/重slug/有关联409、图片过大413、请求Content-Type不支持415、基础设施故障500。逻辑删除后的唯一名称仍保留，使用新名称/slug；本次未新增恢复接口。

## 上传合同

请求类型multipart/form-data；字段file必填，bizType默认为article，枚举avatar/article/project/other。filename不得包含路径分隔符、冒号、控制字符。仅接收可实际解码的JPEG/PNG/WebP，扩展名必须匹配实际格式，Content-Type不作为可信判断依据。

单文件及重编码结果≤5MiB，最长边≤8192，像素总量≤16000000。JPEG重编码为JPEG，PNG/WebP重编码为PNG；保留首帧像素，剥离原元数据及尾随内容。WebP读取由TwelveMonkeys ImageIO提供，服务端无需外部图像命令。

返回data：id、originalName、fileName、fileUrl、fileType、fileExt、fileSize、bizType。fileSize是最终存储字节数；不返回filePath/uploaderId。保存流程：验证→临时文件→数据库元数据→移动至UUID目标；事务回滚清理临时/目标文件，公开资源处理器不提供临时文件。

公开图片不依赖进程内存；重新启动应用后使用相同UPLOAD_PATH仍可读取。备份必须同时覆盖数据库和UPLOAD_PATH。本次不实现图片删除，避免引入尚未设计的封面/正文引用删除行为。
