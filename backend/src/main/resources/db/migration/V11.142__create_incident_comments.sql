-- インシデントコメントテーブル
CREATE TABLE incident_comments (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    incident_id BIGINT UNSIGNED NOT NULL,
    user_id     BIGINT UNSIGNED NOT NULL,
    body        TEXT       NOT NULL,
    is_internal TINYINT(1) NOT NULL DEFAULT 0 COMMENT '内部コメント=ADMIN/担当者のみ可視',
    version     BIGINT     NOT NULL DEFAULT 0,
    created_at  DATETIME   NOT NULL,
    updated_at  DATETIME   NOT NULL,
    deleted_at  DATETIME   NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ico_incident FOREIGN KEY (incident_id) REFERENCES incidents (id),
    CONSTRAINT fk_ico_user     FOREIGN KEY (user_id)     REFERENCES users (id),
    INDEX idx_ico_incident (incident_id)
);
