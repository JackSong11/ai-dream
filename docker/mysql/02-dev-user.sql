-- Development-only account used by the Docker Compose environment.
-- Login: admin / admin123
INSERT INTO biz_user (
    id, user_id, password_hash, role, status, delete_flag, creator, editor
) VALUES (
    1, 'admin', 'e10adc3949ba59abbe56e057f20f883e', 'ADMIN', 1, 0, 'docker', 'docker'
) ON DUPLICATE KEY UPDATE user_id = VALUES(user_id);
