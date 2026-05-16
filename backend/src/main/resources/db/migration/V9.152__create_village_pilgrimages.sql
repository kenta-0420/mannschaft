-- F17.1 Phase 3-β: 巡礼（おすすめ村ローテーション）
-- 日次バッチでユーザー毎に 1 村推薦を生成 → ユーザーが訪問すると visited_at を記録。
-- ルール: 自分が所属している村のカテゴリと一致 + 未参加 + 未ピンの村から 1 つ選定。
-- 原則1: user_id / recommended_village_id のうちユーザー側は FK を張らない。
-- 原則6: PK = UUIDv7 BINARY(16)
-- 原則7 適用外: 全テナント横断ドメイン

CREATE TABLE village_pilgrimage_recommendations (
    id                          BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    user_id                     BIGINT UNSIGNED NOT NULL                                COMMENT '推薦先ユーザーID（FK 張らない・原則1）',
    recommended_village_id      BINARY(16)      NOT NULL                                COMMENT 'FK → villages.id（同一ドメイン CASCADE）',
    recommended_date            DATE            NOT NULL                                COMMENT '推薦日（日次バッチが生成）',
    reason                      VARCHAR(100)    NULL                                    COMMENT '推薦理由（カテゴリ一致など）',
    visited_at                  DATETIME(6)     NULL                                    COMMENT '訪問時に記録',
    created_at                  DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_vpr_user_date (user_id, recommended_date),
    KEY idx_vpr_village (recommended_village_id),
    KEY idx_vpr_user_visited (user_id, visited_at),
    CONSTRAINT fk_vpr_village FOREIGN KEY (recommended_village_id) REFERENCES villages(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='巡礼推薦（F17.1 Phase 3-β）';
