-- F04.10: 委員会伝達処理ログテーブル（監査目的兼用）
CREATE TABLE committee_distribution_logs (
    id                              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    committee_id                    BIGINT UNSIGNED NOT NULL            COMMENT 'FK → committees（ON DELETE CASCADE）',
    content_type                    VARCHAR(30)     NOT NULL            COMMENT '伝達対象種別: SURVEY_RESULT / ACTIVITY_RECORD / CIRCULATION_RESULT / CUSTOM_MESSAGE',
    content_id                      BIGINT UNSIGNED NULL                COMMENT '伝達対象エンティティ ID（CUSTOM_MESSAGE の場合 NULL）',
    custom_title                    VARCHAR(200)    NULL                COMMENT 'CUSTOM_MESSAGE 時のタイトル',
    custom_body                     TEXT            NULL                COMMENT 'CUSTOM_MESSAGE 時の本文',
    target_scope                    VARCHAR(30)     NOT NULL            COMMENT '配信先: COMMITTEE_ONLY / PARENT_ORG / PARENT_ORG_AND_CHILDREN',
    announcement_enabled            BOOLEAN         NOT NULL            COMMENT 'お知らせ投下したかどうか',
    confirmation_mode               VARCHAR(10)     NOT NULL            COMMENT '確認ボタン設定: NONE / OPTIONAL / REQUIRED',
    confirmable_notification_id     BIGINT UNSIGNED NULL                COMMENT 'FK → confirmable_notifications（ON DELETE SET NULL）REQUIRED/OPTIONAL 時に生成された確認通知',
    announcement_feed_ids           JSON            NULL                COMMENT '生成された announcement_feeds レコードの ID 配列',
    created_by                      BIGINT UNSIGNED NULL                COMMENT 'FK → users（ON DELETE SET NULL）伝達操作者',
    created_at                      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_committee_distribution_logs_committee (committee_id, created_at DESC),
    INDEX idx_committee_distribution_logs_content (content_type, content_id),
    CONSTRAINT fk_cdl_committee FOREIGN KEY (committee_id) REFERENCES committees (id) ON DELETE CASCADE,
    CONSTRAINT fk_cdl_confirmable FOREIGN KEY (confirmable_notification_id) REFERENCES confirmable_notifications (id) ON DELETE SET NULL,
    CONSTRAINT fk_cdl_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='F04.10: 委員会伝達処理ログ（監査目的兼用）';
