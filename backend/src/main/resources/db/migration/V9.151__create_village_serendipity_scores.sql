-- F17.1 Phase 3-β — ご縁スコア (village_serendipity_scores)
--
-- 村人同士の出会い頻度・交流度を集計するスコアテーブル。
-- 日次バッチ (VillageSerendipityBatchService) が前日分の井戸端会議返信ペア等から
-- encounter_count / interaction_score を加算的に更新する。
--
-- 原則:
--   - 原則1: user_id への FK は張らない（クロスドメイン回避、アプリ層で整合）。
--   - 原則2: village_id のみ同一ドメイン内 CASCADE。
--   - 原則6: UUIDv7 主キー（BINARY(16)）。
--   - 原則7: village ドメインは organization スコープ外のためテナント対応不要。

CREATE TABLE village_serendipity_scores (
    id                  BINARY(16)       NOT NULL,
    village_id          BINARY(16)       NOT NULL,
    user_id             BIGINT UNSIGNED  NOT NULL,
    encounter_count     INT UNSIGNED     NOT NULL DEFAULT 0,
    interaction_score   INT UNSIGNED     NOT NULL DEFAULT 0,
    last_updated_at     DATETIME(6)      NOT NULL,
    created_at          DATETIME(6)      NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)      NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version             BIGINT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_vss_village_user (village_id, user_id),
    KEY idx_vss_village_score (village_id, interaction_score DESC),
    CONSTRAINT fk_vss_village
        FOREIGN KEY (village_id) REFERENCES villages(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F17.1 ご縁スコア（村人同士の交流度集計）';
