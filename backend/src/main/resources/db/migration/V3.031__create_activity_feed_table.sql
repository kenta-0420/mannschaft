-- F02.2: アクティビティフィードテーブル
CREATE TABLE activity_feed (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type     VARCHAR(20)     NOT NULL COMMENT 'TEAM / ORGANIZATION',
    scope_id       BIGINT UNSIGNED NOT NULL,
    actor_id       BIGINT UNSIGNED NOT NULL,
    activity_type  VARCHAR(30)     NOT NULL COMMENT 'POST_CREATED / EVENT_CREATED / MEMBER_JOINED / TODO_COMPLETED / BULLETIN_CREATED / POLL_CREATED / FILE_UPLOADED',
    target_type    VARCHAR(30)     NOT NULL COMMENT 'TIMELINE_POST / SCHEDULE / TODO / BULLETIN_THREAD / POLL / FILE',
    target_id      BIGINT UNSIGNED NOT NULL,
    summary        VARCHAR(200)    NOT NULL,
    created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_af_scope (scope_type, scope_id, created_at DESC),
    INDEX idx_af_actor (actor_id, created_at DESC),
    INDEX idx_af_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
