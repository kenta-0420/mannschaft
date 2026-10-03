-- F予約認可ゲート基盤: チームごとの予約設定テーブルを新設する。
-- 主キーは UUIDv7 (BINARY(16)) — アーキ原則6に従い時刻順ソート可能UUID。
-- team_id は他ドメインへの参照なのでFKなし、インデックスのみ（アーキ原則1）。

CREATE TABLE reservation_team_settings (
    id              BINARY(16)          NOT NULL,
    team_id         BIGINT              NOT NULL,
    allow_public_reservation BOOLEAN    NOT NULL DEFAULT FALSE,
    created_at      DATETIME(6)         NOT NULL,
    updated_at      DATETIME(6)         NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uq_reservation_team_settings_team_id (team_id),
    INDEX idx_reservation_team_settings_team_id (team_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='チームごとの予約公開設定（1チーム1行）';
