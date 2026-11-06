-- 通知 push 予約の transactional outbox（team ドメイン）。設計: docs/architecture/notification_outbox.md
-- 業務の書き込みと同じトランザクションで1行 INSERT し、通知ドメインの relay がコミット後に取り込む。
-- クロスドメイン FK は張らない（organization_id は運用用の写しで、索引のみ）。
CREATE TABLE team_notification_outbox (
    id                BINARY(16)        NOT NULL,
    idempotency_key   BINARY(16)        NOT NULL COMMENT 'fan-out の冪等キー（notification_fanout_jobs.source_event_uuid と同じ値）',
    message_kind      VARCHAR(32)       NOT NULL COMMENT 'FANOUT / FANOUT_WITH_AUDIENCE',
    payload_version   SMALLINT UNSIGNED NOT NULL COMMENT 'payload_json の版（reader を先、writer を後に展開する）',
    payload_json      JSON              NOT NULL,
    notification_type VARCHAR(64)       NOT NULL COMMENT '運用・監視用の写し',
    organization_id   BIGINT UNSIGNED   NULL     COMMENT 'テナント（運用用。クロスドメインFKなし）',
    status            VARCHAR(16)       NOT NULL DEFAULT 'PENDING',
    attempt_count     INT UNSIGNED      NOT NULL DEFAULT 0,
    next_attempt_at   DATETIME(6)       NOT NULL,
    claim_token       BINARY(16)        NULL     COMMENT 'claim の世代。mark はこの値が一致するときだけ当たる',
    claimed_at        DATETIME(6)       NULL,
    relayed_at        DATETIME(6)       NULL,
    dead_at           DATETIME(6)       NULL,
    last_error        VARCHAR(500)      NULL,
    created_at        DATETIME(6)       NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    updated_at        DATETIME(6)       NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (id),
    UNIQUE KEY uq_team_notification_outbox_idempotency_key (idempotency_key),
    KEY idx_team_notification_outbox_status_next_attempt_at (status, next_attempt_at),
    KEY idx_team_notification_outbox_status_claimed_at (status, claimed_at),
    KEY idx_team_notification_outbox_status_relayed_at (status, relayed_at),
    KEY idx_team_notification_outbox_status_dead_at (status, dead_at),
    KEY idx_team_notification_outbox_organization_id (organization_id),
    CONSTRAINT chk_team_notification_outbox_status CHECK (status IN ('PENDING','RELAYING','RELAYED','DEAD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='team ドメインの通知送信待ち（transactional outbox。通知ドメインの relay が取り込む）';
