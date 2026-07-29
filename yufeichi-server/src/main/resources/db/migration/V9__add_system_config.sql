-- Yufeichi Platform
-- V9：系统配置表及默认配置

CREATE TABLE `system_config` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '配置 ID',
    `config_key` VARCHAR(100) NOT NULL COMMENT '配置键',
    `config_value` LONGTEXT DEFAULT NULL COMMENT '配置值',
    `config_name` VARCHAR(100) NOT NULL COMMENT '配置名称',
    `description` VARCHAR(500) DEFAULT NULL COMMENT '配置说明',
    `config_type` VARCHAR(30) NOT NULL DEFAULT 'string'
        COMMENT '配置类型：string/number/boolean/json/text',
    `is_public` TINYINT NOT NULL DEFAULT 0 COMMENT '是否允许前台公开读取',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序值',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_system_config_key` (`config_key`),
    KEY `idx_system_config_public_sort` (`is_public`, `sort_order`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='系统配置表';


INSERT INTO `system_config`
(`config_key`, `config_value`, `config_name`, `description`,
 `config_type`, `is_public`, `sort_order`)
VALUES
('site_title', 'Yufeichi Platform', '网站标题', '浏览器及首页显示的网站标题',
 'string', 1, 1),
('site_description', '个人博客、项目展示与后台管理平台',
 '网站描述', '网站简介', 'string', 1, 2),
('site_keywords', 'Java,Spring Boot,Vue,个人博客',
 '网站关键词', 'SEO 关键词', 'string', 1, 3),
('icp_number', '', '备案号', '网站备案号', 'string', 1, 4),
('github_url', 'https://github.com/yufeichi1',
 'GitHub 地址', '个人 GitHub 地址', 'string', 1, 5),
('email', '', '联系邮箱', '公开联系邮箱', 'string', 1, 6),
('avatar_url', '', '头像地址', '个人头像地址', 'string', 1, 7),
('about_content', '', '个人介绍', '关于页面内容', 'text', 1, 8),
('home_notice', '', '首页公告', '首页公告内容', 'text', 1, 9),
('site_enabled', 'true', '站点开关', '是否允许前台正常访问',
 'boolean', 0, 10);
