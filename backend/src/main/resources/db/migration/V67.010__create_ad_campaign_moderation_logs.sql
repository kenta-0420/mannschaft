-- F09.17 Phase 11-a: キャンペーン審査履歴・自動 NG 検知ログ
-- 保持期間 3 年。moderator_user_id は自動検知の場合 NULL
CREATE TABLE ad_campaign_moderation_logs (
    id                  BINARY(16)      NOT NULL,
    campaign_id         BINARY(16)      NOT NULL COMMENT 'ad_messaging_campaigns.id (FK CASCADE)',
    moderator_user_id   BIGINT UNSIGNED NULL     COMMENT 'レビュアー user_id (自動検知は NULL・FKなし)',
    action              ENUM('APPROVED','BLOCKED','UNBLOCKED','AUTO_FLAGGED','AUTO_PASSED') NOT NULL COMMENT '操作種別',
    reason              TEXT            NULL     COMMENT '理由',
    ng_words_detected   JSON            NULL     COMMENT '検知された NG ワード配列',
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_acml_camp_created (campaign_id, created_at),
    INDEX idx_acml_action (action, created_at),
    CONSTRAINT fk_acml_campaign FOREIGN KEY (campaign_id)
        REFERENCES ad_messaging_campaigns (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F09.17 キャンペーン審査ログ';
