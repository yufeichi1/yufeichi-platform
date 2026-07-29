-- Yufeichi Platform
-- V2：插入默认管理员、超级管理员角色和基础权限
--
-- 当前本地初始化账号：
-- 用户名：admin
-- 初始密码：Admin@123456
--
-- 该密码仅供本地首次开发使用。
-- 正式上线前必须生成新的强密码 BCrypt 值，并用新的迁移文件更新，
-- 不要在 V2 已执行后直接修改本文件。

INSERT INTO `sys_user`
(`id`, `username`, `password`, `nickname`, `email`, `status`)
VALUES
(1, 'admin',
 '$2y$12$2Gye/MXocIVsnw6WEyk.ZeLZWxTux9frBWpYhV7GYrsdix1O1RU9y',
 '超级管理员', NULL, 1);


INSERT INTO `sys_role`
(`id`, `role_code`, `role_name`, `description`, `status`, `sort_order`)
VALUES
(1, 'super_admin', '超级管理员', '拥有系统全部权限', 1, 1);


INSERT INTO `sys_permission`
(`id`, `permission_code`, `permission_name`, `permission_type`, `status`, `sort_order`)
VALUES
(1,  'article:list',       '文章列表',       2, 1, 101),
(2,  'article:add',        '新增文章',       2, 1, 102),
(3,  'article:update',     '修改文章',       2, 1, 103),
(4,  'article:delete',     '删除文章',       2, 1, 104),
(5,  'article:publish',    '发布文章',       2, 1, 105),

(6,  'category:list',      '分类列表',       2, 1, 201),
(7,  'category:add',       '新增分类',       2, 1, 202),
(8,  'category:update',    '修改分类',       2, 1, 203),
(9,  'category:delete',    '删除分类',       2, 1, 204),

(10, 'tag:list',           '标签列表',       2, 1, 301),
(11, 'tag:add',            '新增标签',       2, 1, 302),
(12, 'tag:update',         '修改标签',       2, 1, 303),
(13, 'tag:delete',         '删除标签',       2, 1, 304),

(14, 'project:list',       '项目列表',       2, 1, 401),
(15, 'project:add',        '新增项目',       2, 1, 402),
(16, 'project:update',     '修改项目',       2, 1, 403),
(17, 'project:delete',     '删除项目',       2, 1, 404),

(18, 'user:list',          '用户列表',       2, 1, 501),
(19, 'user:add',           '新增用户',       2, 1, 502),
(20, 'user:update',        '修改用户',       2, 1, 503),
(21, 'user:delete',        '删除用户',       2, 1, 504),

(22, 'role:list',          '角色列表',       2, 1, 601),
(23, 'role:add',           '新增角色',       2, 1, 602),
(24, 'role:update',        '修改角色',       2, 1, 603),
(25, 'role:delete',        '删除角色',       2, 1, 604),

(26, 'permission:list',    '权限列表',       2, 1, 701),
(27, 'permission:add',     '新增权限',       2, 1, 702),
(28, 'permission:update',  '修改权限',       2, 1, 703),
(29, 'permission:delete',  '删除权限',       2, 1, 704),

(30, 'file:list',          '文件列表',       2, 1, 801),
(31, 'file:upload',        '上传文件',       2, 1, 802),
(32, 'file:delete',        '删除文件',       2, 1, 803),

(33, 'message:list',       '留言列表',       2, 1, 901),
(34, 'message:audit',      '审核留言',       2, 1, 902),
(35, 'message:delete',     '删除留言',       2, 1, 903),

(36, 'comment:list',       '评论列表',       2, 1, 1001),
(37, 'comment:audit',      '审核评论',       2, 1, 1002),
(38, 'comment:delete',     '删除评论',       2, 1, 1003),

(39, 'log:visit',          '访问日志',       2, 1, 1101),
(40, 'log:operation',      '操作日志',       2, 1, 1102),

(41, 'system:config',      '系统配置',       2, 1, 1201);


INSERT INTO `sys_user_role`
(`user_id`, `role_id`)
VALUES
(1, 1);


INSERT INTO `sys_role_permission`
(`role_id`, `permission_id`)
SELECT 1, `id`
FROM `sys_permission`
WHERE `deleted` = 0;
