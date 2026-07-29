-- Yufeichi Platform
-- V6：文章评论表

CREATE TABLE `comment` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '评论 ID',
    `article_id` BIGINT NOT NULL COMMENT '文章 ID',
    `parent_id` BIGINT NOT NULL DEFAULT 0 COMMENT '父评论 ID，0 表示一级评论',
    `nickname` VARCHAR(50) NOT NULL COMMENT '评论者昵称',
    `email` VARCHAR(100) DEFAULT NULL COMMENT '评论者邮箱',
    `content` VARCHAR(2000) NOT NULL COMMENT '评论内容',
    `ip` VARCHAR(64) DEFAULT NULL COMMENT '访问 IP',
    `user_agent` VARCHAR(500) DEFAULT NULL COMMENT '浏览器 User-Agent',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0 待审核，1 已通过，2 已拒绝',
    `like_count` INT NOT NULL DEFAULT 0 COMMENT '点赞数',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_comment_article_status_created` (`article_id`, `status`, `created_at`),
    KEY `idx_comment_parent_id` (`parent_id`),
    KEY `idx_comment_email` (`email`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='文章评论表';
