-- F03.12 ケア対象者見守り通知: チーム単位の通知設定上書き（オプション）
CREATE TABLE team_care_notification_overrides (
    id                         BIGINT UNSIGNED    NOT NULL AUTO_INCREMENT,
    scope_type                 ENUM('TEAM','ORGANIZATION') NOT NULL COMMENT 'スコープ種別',
    scope_id                   BIGINT UNSIGNED    NOT NULL COMMENT 'チームIDまたは組織ID',
    care_link_id               BIGINT UNSIGNED    NOT NULL COMMENT 'FK → user_care_links.id',
    notify_on_rsvp             BOOLEAN            NULL COMMENT 'NULL=デフォルト使用、TRUE/FALSE=上書き',
    notify_on_checkin          BOOLEAN            NULL COMMENT '同上',
    notify_on_checkout         BOOLEAN            NULL COMMENT '同上',
    notify_on_absent_alert     BOOLEAN            NULL COMMENT '同上',
    notify_on_dismissal        BOOLEAN            NULL COMMENT '同上',
    disabled                   BOOLEAN            NOT NULL DEFAULT FALSE COMMENT 'TRUE=このチームでの通知を完全停止',
    created_by                 BIGINT UNSIGNED    NOT NULL COMMENT 'FK → users.id',
    created_at                 DATETIME           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 DATETIME           NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT uq_tcno UNIQUE (scope_type, scope_id, care_link_id),
    CONSTRAINT fk_tcno_care_link  FOREIGN KEY (care_link_id)  REFERENCES user_care_links(id) ON DELETE CASCADE,
    CONSTRAINT fk_tcno_created_by FOREIGN KEY (created_by)    REFERENCES users(id) ON DELETE RESTRICT,
    INDEX idx_tcno_link (care_link_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='チーム単位のケア通知設定上書き（オプション）';
