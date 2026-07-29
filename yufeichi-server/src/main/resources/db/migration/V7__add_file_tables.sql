-- Yufeichi Platform
-- V7：文件信息表

CREATE TABLE `file_info` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '文件 ID',
    `original_name` VARCHAR(255) NOT NULL COMMENT '原始文件名',
    `file_name` VARCHAR(255) NOT NULL COMMENT '存储文件名',
    `file_url` VARCHAR(500) NOT NULL COMMENT '文件访问地址',
    `file_path` VARCHAR(1000) NOT NULL COMMENT '文件磁盘路径',
    `file_type` VARCHAR(100) DEFAULT NULL COMMENT 'MIME 类型',
    `file_ext` VARCHAR(30) DEFAULT NULL COMMENT '文件扩展名',
    `file_size` BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小，字节',
    `biz_type` VARCHAR(50) NOT NULL COMMENT '业务类型：avatar/article/project/other',
    `uploader_id` BIGINT DEFAULT NULL COMMENT '上传用户 ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_info_file_name` (`file_name`),
    KEY `idx_file_info_biz_type_created` (`biz_type`, `created_at`),
    KEY `idx_file_info_uploader_id` (`uploader_id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='文件信息表';
