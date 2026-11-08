-- Additive permission migration. Existing migrations and role/user assignments are preserved.
INSERT INTO sys_permission (permission_code, permission_name, permission_type, path, status, sort_order)
VALUES ('ai:index:manage', 'AI知识索引管理', 3, '/api/admin/ai/knowledge', 1, 1301);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p ON p.permission_code = 'ai:index:manage'
WHERE r.role_code = 'super_admin' AND r.deleted = 0 AND r.status = 1;
