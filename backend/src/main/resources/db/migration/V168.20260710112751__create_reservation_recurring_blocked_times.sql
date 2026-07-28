-- F03.4.5 §4 W2-2: 定期予約不可枠（reservation_recurring_blocked_times）を新設する。
--
-- 「毎週火曜19-20時は研修」のような週次繰り返しの予約不可を1回の登録で恒久化する（機能B の単発
-- reservation_blocked_times は日付ごとに行が要るため毎週の登録作業が発生していた・§4.1）。
-- 主キーは UUIDv7 (BINARY(16)) — アーキ原則6（UuidV7Entity 継承）。
-- team_id / created_by はクロスドメイン参照のため FK なし（アーキ原則1）。
-- line_id は同一 reservation ドメイン内のため FK あり（ON DELETE RESTRICT — 運用は論理削除・§4.1）。
-- day_of_week は正準3文字大文字 'MON'..'SUN'（reservation_slot_templates.day_of_week と完全同一表現）。
-- 全日型（start/end NULL）は許可しない（NOT NULL・§4.3「終日休みは営業時間の定休日で」）。
-- 論理削除は持たない（DELETE は物理削除・is_active で一時停止・§4.6）。
-- 上限: 1チームあたり50行（Service層で担保・RESERVATION_052）。

CREATE TABLE reservation_recurring_blocked_times (
    id            BINARY(16)       NOT NULL,
    team_id       BIGINT UNSIGNED  NOT NULL,
    line_id       BIGINT UNSIGNED  NULL,
    day_of_week   VARCHAR(3)       NOT NULL,
    start_time    TIME             NOT NULL,
    end_time      TIME             NOT NULL,
    reason        VARCHAR(100)     NOT NULL,
    is_public     BOOLEAN          NOT NULL DEFAULT FALSE,
    is_active     BOOLEAN          NOT NULL DEFAULT TRUE,
    created_by    BIGINT UNSIGNED  NULL,
    created_at    DATETIME(6)      NOT NULL,
    updated_at    DATETIME(6)      NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT fk_rrbt_line
        FOREIGN KEY (line_id) REFERENCES reservation_lines (id) ON DELETE RESTRICT,
    INDEX idx_rrbt_team (team_id, day_of_week),
    INDEX idx_rrbt_line (line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F03.4.5 §4 定期予約不可枠（週次繰り返し・事由ラベル・公開可否・上限50行/チーム）';
