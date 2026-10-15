-- F08.10 / 01 §B.2: match_events（時系列イベント）。
--
-- 得点・アシスト・交代・カード等の時系列イベント。match ドメイン内 → 親 matches へ CASCADE 可（原則2）。
--
-- 原則準拠（CLAUDE.md・01 §A.4/§B.2）:
--   - 新規テーブルゆえ主キーは UUIDv7（原則6）。id は BINARY(16)。
--   - organization_id / deleted_at は【持たない】。テナント分離は親 matches で担保し、子は match_id
--     スコープでのみアクセスする二段アクセス（01 §A.4・IDOR 根絶）。子の削除は親 matches の CASCADE に従う。
--   - match_id → matches(id) は同一 match ドメイン内ゆえ FK＋ON DELETE CASCADE 可（原則2）。
--   - linked_event_id → match_events(id) は同一テーブル自己参照（同一 match ドメイン）ゆえ FK＋
--     ON DELETE SET NULL 可（連鎖相手を消しても残イベントは保持・原則2）。
--   - player_user_id/related_player_user_id/recorded_by_team_id はクロスドメイン ID 参照（原則1・FK なし）。
--
-- 設計書: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §B.2
-- ※採番はマージ直前に origin/main の最大 Flyway 番号を再確認しリネームすること。

CREATE TABLE match_events (
    id                     BINARY(16)        NOT NULL              COMMENT 'UUIDv7（原則6）',
    match_id               BINARY(16)        NOT NULL              COMMENT 'matches(id)（同一ドメイン → FK CASCADE）',
    minute                 SMALLINT UNSIGNED NULL                  COMMENT '経過分（タイマー連動・手動訂正可・NULL=分不明）',
    stoppage_minute        SMALLINT UNSIGNED NULL                  COMMENT 'アディショナルタイム（例 45+2 の "2"・NULL=なし）',
    period                 VARCHAR(24)       NOT NULL              COMMENT 'PeriodType（器は競技非依存・具体値は競技別＝サッカーは sports/01 §3）',
    event_type             VARCHAR(24)       NOT NULL              COMMENT 'MatchEventType（器は競技非依存・許容値は競技別カタログ＝サッカーは sports/01 §2）',
    card_reason_code       VARCHAR(8)        NULL                  COMMENT '警告/退場の標準理由コード（競技別カタログの列挙値・サッカーは C1〜C8/S1〜S6/CS・非カード系は NULL）',
    custom_label           VARCHAR(64)       NULL                  COMMENT 'event_type=OTHER 時の自由ラベル名',
    team_side              ENUM('HOME','AWAY') NOT NULL            COMMENT 'どちらのチームのイベントか',
    player_user_id         BIGINT            NULL                  COMMENT '主体選手（user ドメイン ID 参照・未登録は NULL・FK なし）',
    player_name            VARCHAR(128)      NULL                  COMMENT '未登録選手名（player_user_id NULL のとき）',
    jersey_number          SMALLINT UNSIGNED NULL                  COMMENT '背番号（未登録選手の同一性キーの一部）',
    related_player_user_id BIGINT            NULL                  COMMENT '関連選手（アシスト者/交代相手・user ドメイン ID 参照・FK なし）',
    related_player_name    VARCHAR(128)      NULL                  COMMENT '関連未登録選手名',
    note                   VARCHAR(255)      NULL                  COMMENT '理由・メモ自由記述（入力検証・XSS/CRLF サニタイズ対象）',
    linked_event_id        BINARY(16)        NULL                  COMMENT '時系列連鎖の相手イベント（同一テーブル自己参照・例 アシスト⤵得点）',
    detail                 JSON              NULL                  COMMENT '拡張属性（競技別の追加情報・最大 4KB・サーバー検証）',
    recorded_by_team_id    BIGINT            NULL                  COMMENT '記録したチーム（共同記録の権限判定・team ドメイン ID 参照・NULL=記録係記録・FK なし）',
    sort_seq               INT               NOT NULL DEFAULT 0    COMMENT '同分内の表示順（タイムライン安定ソート）',
    created_at             DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    KEY idx_match_events_match (match_id, period, minute, sort_seq),
    KEY idx_match_events_player (player_user_id),
    KEY idx_match_events_linked (linked_event_id),
    CONSTRAINT fk_match_events_match FOREIGN KEY (match_id)
        REFERENCES matches (id) ON DELETE CASCADE,
    CONSTRAINT fk_match_events_linked FOREIGN KEY (linked_event_id)
        REFERENCES match_events (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.10/01 時系列イベント（match ドメイン内・テナント分離は親 matches）';
