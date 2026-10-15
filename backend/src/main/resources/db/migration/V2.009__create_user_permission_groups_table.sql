-- ユーザー−パーミッショングループ割当テーブル
CREATE TABLE user_permission_groups (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    group_id BIGINT UNSIGNED NOT NULL,
    assigned_by BIGINT UNSIGNED NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_upg_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_upg_group FOREIGN KEY (group_id) REFERENCES permission_groups (id),
    CONSTRAINT fk_upg_assigned_by FOREIGN KEY (assigned_by) REFERENCES users (id)
);
