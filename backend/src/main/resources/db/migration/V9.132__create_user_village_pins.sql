-- F17.1 Phase 1: お気に入り村ピン留めテーブル

CREATE TABLE user_village_pins (
    id                       BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    user_id                  BIGINT UNSIGNED NOT NULL                                COMMENT 'ユーザーID（FK 張らない）',
    village_id               BINARY(16)      NOT NULL,
    sort_order               BIGINT UNSIGNED NOT NULL DEFAULT 0                      COMMENT '並び順（小さいほど上）',
    pinned_at                DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_uvp_user_village (user_id, village_id),
    KEY idx_uvp_user_sort (user_id, sort_order),
    CONSTRAINT fk_uvp_village FOREIGN KEY (village_id) REFERENCES villages(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='お気に入り村ピン留め（F17.1）';
