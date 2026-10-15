-- F09.18 Phase 18-a: メール配信 outbox テーブル作成
-- 設計書: docs/features/F09.18_email_delivery_infrastructure.md §4.2
--
-- 採用方針:
--   - 主キーは UUIDv7 (CLAUDE.md 原則6)
--   - クロスドメイン FK は張らない (CLAUDE.md 原則1) → user_id / organization_id はインデックスのみ
--   - 個人情報は AES-256-GCM 暗号化、検索用に HMAC-SHA-256 ハッシュを別鍵で保持
--   - status は VARCHAR(16) で保持し、Java 側で enum 検証 (Flyway での ALTER 容易性確保)

CREATE TABLE email_outbox (
    id                  BINARY(16)    NOT NULL          COMMENT 'UUIDv7 主キー',

    -- テンプレート識別
    template_kind       VARCHAR(64)   NOT NULL          COMMENT 'VERIFICATION / PASSWORD_RESET / ANALYTICS_KPI_MONTHLY 等 (§11 マトリクス参照)',
    locale              VARCHAR(8)    NOT NULL          COMMENT 'ja/en/zh/ko/es/de',

    -- 宛先（暗号化）
    to_address          VARBINARY(512) NOT NULL         COMMENT 'AES-256-GCM 暗号化済メールアドレス',
    to_address_hash     BINARY(32)    NOT NULL          COMMENT 'HMAC-SHA-256 (検索用、別鍵)',

    -- 本文変数（暗号化、≤8000B 制約）
    payload_json        VARBINARY(8192) NULL            COMMENT 'AES-256-GCM 暗号化済変数 JSON (Map<String,String> 制約)',

    -- ソース追跡
    source_domain       VARCHAR(32)   NOT NULL          COMMENT 'auth/billing/event-watch/chat/reservation/pointcard/organization/repair-plan/advertising',
    source_event_id     VARCHAR(128)  NULL              COMMENT '業務側の冪等キー (token.id, batch_run_id 等)',
    user_id             BIGINT        NULL              COMMENT '宛先ユーザー (論理参照、FK なし)',
    organization_id     BIGINT        NULL              COMMENT '所属組織 (論理参照、FK なし。認証メールは NULL)',

    -- 冪等性
    idempotency_key     CHAR(32)      NOT NULL          COMMENT 'sha256(user_id:template_kind:nonce)[:32]',

    -- 状態
    status              VARCHAR(16)   NOT NULL DEFAULT 'PENDING'
                                                       COMMENT 'PENDING/SENDING/SENT/DEAD_LETTER/FAILED/CANCELLED',
    retry_count         INT           NOT NULL DEFAULT 0
                                                       COMMENT '0..5',
    next_attempt_at     DATETIME(3)   NOT NULL          COMMENT '次回試行時刻 (enqueue 時=NOW())',
    sent_at             DATETIME(3)   NULL              COMMENT '送信成功時刻',
    ses_message_id      VARCHAR(64)   NULL              COMMENT 'SES MessageId (DEAD_LETTER 調査用)',
    last_error          VARCHAR(512)  NULL              COMMENT '例外クラス名 + メッセージ冒頭500文字',

    -- 監査
    created_at          DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                       ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_email_outbox_idempotency (idempotency_key),
    KEY idx_email_outbox_status_next (status, next_attempt_at)    COMMENT 'Worker のメインクエリ用',
    KEY idx_email_outbox_to_hash (to_address_hash)                COMMENT 'SYSTEM_ADMIN 検索用',
    KEY idx_email_outbox_user_id (user_id)                        COMMENT 'GDPR 匿名化用',
    KEY idx_email_outbox_organization_id (organization_id),
    KEY idx_email_outbox_source (source_domain, source_event_id)  COMMENT '業務側からの逆引き',
    KEY idx_email_outbox_created_at (created_at)                  COMMENT '保持期間バッチ用',
    KEY idx_email_outbox_sent_at (sent_at)                        COMMENT '集計用'
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='メール配信 outbox (F09.18)';
