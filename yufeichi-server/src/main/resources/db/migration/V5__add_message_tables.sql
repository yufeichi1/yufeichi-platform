-- Yufeichi Platform
-- V5：留言板表

CREATE TABLE `message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '留言 ID',
    `nickname` VARCHAR(50) NOT NULL COMMENT '访客昵称',
    `email` VARCHAR(100) DEFAULT NULL COMMENT '访客邮箱',
    `content` VARCHAR(1000) NOT NULL COMMENT '留言内容',
    `ip` VARCHAR(64) DEFAULT NULL COMMENT '访问 IP',
    `user_agent` VARCHAR(500) DEFAULT NULL COMMENT '浏览器 User-Agent',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0 待审核，1 已通过，2 已拒绝',
    `reply_content` VARCHAR(1000) DEFAULT NULL COMMENT '管理员回复',
    `replied_at` DATETIME DEFAULT NULL COMMENT '回复时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_message_status_created` (`status`, `created_at`),
    KEY `idx_message_email` (`email`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='留言表';
