-- 通知 fan-out 抜本改修 P2: 耐久ジョブ表 notification_fanout_jobs 作成
-- 設計台帳: .claude/campaigns/2026-07-29-fanout-redesign-500k.md（P2＝耐久ジョブ表＋裏ワーカー）
--
-- 採用方針（CLAUDE.md アーキテクチャ思想／email_outbox 前例に倣う）:
--   - 主キーは UUIDv7（原則6）。id は BINARY(16)。
--   - クロスドメイン FK は張らない（原則1）。scope_id / organization_id / actor_id / source_id は
--     いずれも論理参照（インデックスのみ）で、他ドメインテーブルへの FK は持たない。
--   - status は VARCHAR(16) で保持し Java 側で enum 検証（Flyway ALTER 容易性・email_outbox と同方針）。
--   - ワーカーの取り合いは (status, next_attempt_at) 索引 + `FOR UPDATE SKIP LOCKED` で行う
--     （email_outbox の findReadyForSending と同一パターン。AC-4）。
--   - 冪等キーは (scope_type, scope_id, notification_type, source_event_uuid) の複合ユニーク（AC-1）。
--
-- テナント例外の判断: organization_id は NULL 許容（SYSTEM スコープの村行事通知等は org 非依存）のため、
-- 本表は AbstractTenantAwareRepository の適用対象外（email_outbox と同じ判断）。

CREATE TABLE notification_fanout_jobs (
    id                  BINARY(16)    NOT NULL          COMMENT 'UUIDv7 主キー',

    -- fan-out の同定・冪等キー
    source_event_uuid   BINARY(16)    NOT NULL          COMMENT '発生元イベント UUID（村行事 UUID 等・冪等キーの一部）',
    scope_type          VARCHAR(20)   NOT NULL          COMMENT '受信者解決の戦略キー（VILLAGE / TEAM / ... FanoutRecipientSource.scopeType）',
    scope_id            BIGINT        NOT NULL          COMMENT '受信者解決に渡すスコープID（論理参照・FK なし）',
    notification_type   VARCHAR(50)   NOT NULL          COMMENT '通知種別',
    organization_id     BIGINT        NULL              COMMENT 'テナント（論理参照・FK なし。SYSTEM 通知は NULL）',

    -- 通知テンプレート（受信者ごとに同一。per-row の user_id はワーカーが充填）
    title               VARCHAR(200)  NOT NULL          COMMENT '通知タイトル',
    body                VARCHAR(1000) NULL              COMMENT '通知本文',
    priority            VARCHAR(10)   NOT NULL DEFAULT 'NORMAL'
                                                        COMMENT 'LOW/NORMAL/HIGH/URGENT',
    source_type         VARCHAR(50)   NULL              COMMENT 'ソース種別（VILLAGE_EVENT 等）',
    source_id           BIGINT        NULL              COMMENT 'ソースID（論理参照・FK なし）',
    action_url          VARCHAR(500)  NULL              COMMENT '通知タップ先の相対 URL',
    actor_id            BIGINT        NULL              COMMENT '実行者ID（論理参照・FK なし・システム発火は NULL）',

    -- ジョブ状態（耐久・再開・リトライ）
    status              VARCHAR(16)   NOT NULL DEFAULT 'PENDING'
                                                        COMMENT 'PENDING/RUNNING/DONE/FAILED/DEAD_LETTER',
    cursor_subject_id   BIGINT        NOT NULL DEFAULT 0
                                                        COMMENT 'キーセット再開カーソル（処理済み受信者 subject_id 上端。クラッシュ再開の要・AC-2）',
    inserted_count      BIGINT        NOT NULL DEFAULT 0
                                                        COMMENT '生成済み通知行数（可観測性・再開時の重複検知補助）',
    retry_count         INT           NOT NULL DEFAULT 0
                                                        COMMENT 'リトライ回数（上限超で DEAD_LETTER・AC-3）',
    next_attempt_at     DATETIME(3)   NOT NULL          COMMENT '次回実行時刻（enqueue 時＝NOW(3)。リトライで指数バックオフ）',
    last_error          VARCHAR(500)  NULL              COMMENT '直近エラー（例外クラス名＋メッセージ冒頭）',

    created_at          DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                       ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_fanout_idempotency (scope_type, scope_id, notification_type, source_event_uuid)
        COMMENT '同一 fan-out の二重 enqueue を DB レベルで拒否（AC-1）',
    KEY idx_fanout_status_next (status, next_attempt_at)
        COMMENT 'ワーカーの findReady メインクエリ用（FOR UPDATE SKIP LOCKED・AC-4）'
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='通知 fan-out 耐久ジョブ表（P2・裏ワーカーが受信者をチャンク配信）';
