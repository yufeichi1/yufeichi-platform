-- Yufeichi Platform
-- V4：项目展示表

CREATE TABLE `project` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '项目 ID',
    `name` VARCHAR(100) NOT NULL COMMENT '项目名称',
    `description` TEXT NOT NULL COMMENT '项目说明',
    `cover_url` VARCHAR(500) DEFAULT NULL COMMENT '项目封面地址',
    `github_url` VARCHAR(500) DEFAULT NULL COMMENT 'GitHub 地址',
    `demo_url` VARCHAR(500) DEFAULT NULL COMMENT '演示地址',
    `tech_stack` VARCHAR(500) DEFAULT NULL COMMENT '技术栈，逗号分隔',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序值',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0 隐藏，1 展示',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_project_status_sort` (`status`, `sort_order`),
    KEY `idx_project_created_at` (`created_at`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='项目展示表';
