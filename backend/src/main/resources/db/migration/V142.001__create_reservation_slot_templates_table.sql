-- F03.4.2 機能F: 週間テンプレート（reservation_slot_templates）を新設する。
--
-- 1行 = 1曜日 × 1連続時間帯 × 1ライン（または共通）。生成時に 30 分セルへ分割される（§5.2）。
-- 主キーは UUIDv7 (BINARY(16)) — アーキ原則6（UuidV7Entity 継承）。
-- team_id / staff_user_id / created_by はクロスドメイン参照のため FK なし（アーキ原則1）。
-- line_id は同一 reservation ドメイン内のため FK あり（ON DELETE RESTRICT — 運用は論理削除）。
-- day_of_week は正準3文字大文字 'MON'..'SUN'（reservation_business_hours.day_of_week=VARCHAR(3) と
-- 完全同一表現。営業時間突合が文字列一致で成立する根拠 — §3.2）。
-- 論理削除は持たない（is_active で無効化・物理削除可。生成済み枠は V142.002 の SET NULL で独立残置）。

CREATE TABLE reservation_slot_templates (
    id              BINARY(16)       NOT NULL,
    team_id         BIGINT UNSIGNED  NOT NULL,
    name            VARCHAR(100)     NULL,
    line_id         BIGINT UNSIGNED  NULL,
    day_of_week     VARCHAR(3)       NOT NULL,
    start_time      TIME             NOT NULL,
    end_time        TIME             NOT NULL,
    capacity        INT              NOT NULL DEFAULT 1,
    staff_user_id   BIGINT UNSIGNED  NULL,
    title           VARCHAR(200)     NULL,
    price           DECIMAL(10,2)    NULL,
    approval_mode   VARCHAR(10)      NULL,
    is_active       BOOLEAN          NOT NULL DEFAULT TRUE,
    created_by      BIGINT UNSIGNED  NULL,
    created_at      DATETIME(6)      NOT NULL,
    updated_at      DATETIME(6)      NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT fk_rst_line
        FOREIGN KEY (line_id) REFERENCES reservation_lines (id) ON DELETE RESTRICT,
    INDEX idx_rst_team (team_id, day_of_week, start_time),
    INDEX idx_rst_line (line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F03.4.2 週間テンプレート（曜日×時間帯×ラインの枠生成定義・上限500行/チーム）';
