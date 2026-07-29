-- Yufeichi Platform
-- V8：访问日志和后台操作日志

CREATE TABLE `visit_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '访问日志 ID',
    `request_path` VARCHAR(500) NOT NULL COMMENT '请求路径',
    `request_method` VARCHAR(10) NOT NULL COMMENT '请求方法',
    `ip` VARCHAR(64) DEFAULT NULL COMMENT '访问 IP',
    `user_agent` VARCHAR(1000) DEFAULT NULL COMMENT '浏览器 User-Agent',
    `referer` VARCHAR(1000) DEFAULT NULL COMMENT '来源页面',
    `response_status` INT DEFAULT NULL COMMENT '响应状态码',
    `duration_ms` BIGINT NOT NULL DEFAULT 0 COMMENT '耗时，毫秒',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '访问时间',
    PRIMARY KEY (`id`),
    KEY `idx_visit_log_created_at` (`created_at`),
    KEY `idx_visit_log_path_created` (`request_path`, `created_at`),
    KEY `idx_visit_log_ip_created` (`ip`, `created_at`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='前台访问日志表';


CREATE TABLE `operation_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '操作日志 ID',
    `operator_id` BIGINT DEFAULT NULL COMMENT '操作用户 ID',
    `operator_name` VARCHAR(50) DEFAULT NULL COMMENT '操作用户名',
    `module` VARCHAR(100) NOT NULL COMMENT '操作模块',
    `operation_type` VARCHAR(50) NOT NULL COMMENT '操作类型',
    `request_path` VARCHAR(500) NOT NULL COMMENT '请求路径',
    `request_method` VARCHAR(10) NOT NULL COMMENT '请求方法',
    `request_params` LONGTEXT DEFAULT NULL COMMENT '请求参数，禁止记录密码明文',
    `response_result` LONGTEXT DEFAULT NULL COMMENT '响应结果',
    `ip` VARCHAR(64) DEFAULT NULL COMMENT '操作 IP',
    `duration_ms` BIGINT NOT NULL DEFAULT 0 COMMENT '耗时，毫秒',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0 失败，1 成功',
    `error_message` VARCHAR(2000) DEFAULT NULL COMMENT '错误信息',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_operation_log_operator_created` (`operator_id`, `created_at`),
    KEY `idx_operation_log_module_created` (`module`, `created_at`),
    KEY `idx_operation_log_created_at` (`created_at`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='后台操作日志表';
