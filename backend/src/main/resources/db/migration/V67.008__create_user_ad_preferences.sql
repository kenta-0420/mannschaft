-- F09.17 Phase 11-a: ユーザーごとの広告受信設定
-- user_id はクロスドメイン参照のため FK なし (UNIQUE 制約のみ)
CREATE TABLE user_ad_preferences (
    id                                BINARY(16)      NOT NULL,
    user_id                           BIGINT UNSIGNED NOT NULL COMMENT 'users.id (FKなし・UNIQUE)',
    accept_announcement_ads           BOOLEAN         NOT NULL DEFAULT TRUE COMMENT 'お知らせ広告 ON/OFF',
    accept_email_ads                  BOOLEAN         NOT NULL DEFAULT TRUE COMMENT 'メール広告 ON/OFF',
    accept_push_ads                   BOOLEAN         NOT NULL DEFAULT TRUE COMMENT 'プッシュ広告 ON/OFF',
    accept_banner_ads                 BOOLEAN         NOT NULL DEFAULT TRUE COMMENT 'バナー広告 ON/OFF',
    blocked_advertiser_account_ids    JSON            NOT NULL COMMENT 'ブロック広告主 ID 配列 (上限 100 件・Service 層で検証)',
    unsubscribe_token_version         INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT 'unsubscribe JWT バージョン (インクリメントで一括失効)',
    consented_at                      DATETIME        NULL     COMMENT '初回広告受信時の明示同意取得時刻',
    created_at                        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_uap_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F09.17 受信者ごとの広告受信設定';
