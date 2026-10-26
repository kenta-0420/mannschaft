-- =================================================================
-- F17.1 Phase 3-β-E: 村ニュースレター設定 + opt-out + 配信履歴
--
-- 設計方針:
--   - デフォルト opt-in（村人は何もしなくても受信対象）。
--   - ユーザー単位の opt-out レコードを別テーブルに保持。
--   - 配信頻度は WEEKLY / MONTHLY の 2 種類。
--   - village ドメイン内に閉じる（CASCADE は village 直系のみ）。
--   - 主キーは BINARY(16)（UUIDv7、原則6）。
--
-- 採番: VILLAGE_078〜080（VillageErrorCode）。
-- =================================================================

-- -----------------------------------------------------------------
-- 1. village_newsletters: 村ごとのニュースレター設定（村×頻度で 1 行）
-- -----------------------------------------------------------------
CREATE TABLE village_newsletters (
    id BINARY(16) NOT NULL,
    village_id BINARY(16) NOT NULL,
    frequency VARCHAR(20) NOT NULL COMMENT 'WEEKLY / MONTHLY',
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '村単位で配信全体を停止するときに FALSE',
    last_sent_at DATETIME(6) NULL COMMENT '直近の配信実行時刻（バッチ更新）',
    next_scheduled_at DATETIME(6) NULL COMMENT '次回配信予定時刻（運用観測用、必須ではない）',
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_vn_village_freq (village_id, frequency),
    KEY idx_vn_enabled (is_enabled, deleted_at),
    CONSTRAINT fk_vn_village FOREIGN KEY (village_id) REFERENCES villages(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='村ニュースレター設定（F17.1 Phase 3-β-E）';

-- -----------------------------------------------------------------
-- 2. village_newsletter_opt_outs: ユーザー単位 opt-out
--   - デフォルト opt-in のため、レコードが「ある」=「受信しない」
--   - user_id は他ドメイン（user）参照だが FK は張らない（原則1）
-- -----------------------------------------------------------------
CREATE TABLE village_newsletter_opt_outs (
    id BINARY(16) NOT NULL,
    village_id BINARY(16) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    opted_out_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_vno_village_user (village_id, user_id),
    KEY idx_vno_user (user_id),
    CONSTRAINT fk_vno_village FOREIGN KEY (village_id) REFERENCES villages(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ニュースレター opt-out レコード（F17.1 Phase 3-β-E）';

-- -----------------------------------------------------------------
-- 3. village_newsletter_send_logs: 配信履歴（運用観測用）
--   - newsletter_id 単位で時系列を残す。配信件数・成功・失敗を集計。
-- -----------------------------------------------------------------
CREATE TABLE village_newsletter_send_logs (
    id BINARY(16) NOT NULL,
    newsletter_id BINARY(16) NOT NULL,
    sent_at DATETIME(6) NOT NULL,
    recipient_count INT UNSIGNED NOT NULL DEFAULT 0,
    success_count INT UNSIGNED NOT NULL DEFAULT 0,
    failure_count INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_vnsl_newsletter (newsletter_id, sent_at DESC),
    CONSTRAINT fk_vnsl_newsletter FOREIGN KEY (newsletter_id) REFERENCES village_newsletters(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ニュースレター配信履歴（F17.1 Phase 3-β-E）';
