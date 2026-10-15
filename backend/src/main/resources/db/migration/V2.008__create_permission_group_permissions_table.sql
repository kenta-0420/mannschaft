-- パーミッショングループ−パーミッション関連テーブル
CREATE TABLE permission_group_permissions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    group_id BIGINT UNSIGNED NOT NULL,
    permission_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_pgp_group FOREIGN KEY (group_id) REFERENCES permission_groups (id),
    CONSTRAINT fk_pgp_permission FOREIGN KEY (permission_id) REFERENCES permissions (id),
    CONSTRAINT uq_pgp UNIQUE (group_id, permission_id)
);
