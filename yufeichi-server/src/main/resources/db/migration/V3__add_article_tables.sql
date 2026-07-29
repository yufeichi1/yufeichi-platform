-- Yufeichi Platform
-- V3：文章、分类、标签及文章标签关联表

CREATE TABLE `blog_category` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '分类 ID',
    `name` VARCHAR(50) NOT NULL COMMENT '分类名称',
    `slug` VARCHAR(80) NOT NULL COMMENT '分类英文标识',
    `description` VARCHAR(255) DEFAULT NULL COMMENT '分类说明',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序值',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0 禁用，1 启用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_blog_category_name` (`name`),
    UNIQUE KEY `uk_blog_category_slug` (`slug`),
    KEY `idx_blog_category_status_sort` (`status`, `sort_order`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='文章分类表';


CREATE TABLE `blog_tag` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '标签 ID',
    `name` VARCHAR(50) NOT NULL COMMENT '标签名称',
    `slug` VARCHAR(80) NOT NULL COMMENT '标签英文标识',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0 禁用，1 启用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_blog_tag_name` (`name`),
    UNIQUE KEY `uk_blog_tag_slug` (`slug`),
    KEY `idx_blog_tag_status` (`status`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='文章标签表';


CREATE TABLE `blog_article` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '文章 ID',
    `title` VARCHAR(200) NOT NULL COMMENT '文章标题',
    `summary` VARCHAR(500) DEFAULT NULL COMMENT '文章摘要',
    `content` LONGTEXT NOT NULL COMMENT '文章正文 Markdown',
    `cover_url` VARCHAR(500) DEFAULT NULL COMMENT '封面地址',
    `category_id` BIGINT DEFAULT NULL COMMENT '分类 ID',
    `author_id` BIGINT NOT NULL COMMENT '作者用户 ID',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0 草稿，1 已发布，2 已下架',
    `is_top` TINYINT NOT NULL DEFAULT 0 COMMENT '是否置顶',
    `is_featured` TINYINT NOT NULL DEFAULT 0 COMMENT '是否精选',
    `view_count` BIGINT NOT NULL DEFAULT 0 COMMENT '浏览量',
    `published_at` DATETIME DEFAULT NULL COMMENT '发布时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_blog_article_status_created` (`status`, `created_at`),
    KEY `idx_blog_article_category_id` (`category_id`),
    KEY `idx_blog_article_author_id` (`author_id`),
    KEY `idx_blog_article_published_at` (`published_at`),
    FULLTEXT KEY `ft_blog_article_title_summary` (`title`, `summary`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='文章表';


CREATE TABLE `blog_article_tag` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '关联 ID',
    `article_id` BIGINT NOT NULL COMMENT '文章 ID',
    `tag_id` BIGINT NOT NULL COMMENT '标签 ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_blog_article_tag` (`article_id`, `tag_id`),
    KEY `idx_blog_article_tag_tag_id` (`tag_id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='文章标签关联表';
