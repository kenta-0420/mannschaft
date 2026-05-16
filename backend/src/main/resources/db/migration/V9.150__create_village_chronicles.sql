-- F17.1 Phase 3-β: 村史（月次ダイジェスト）
-- 各村の月単位の活動サマリを月次バッチで生成して保持する。
-- LLM は使用せず、投稿数 + 新メンバー数 + TOP3 タグの統計のみを集計する。
--
-- 原則6 適用: PK = UUIDv7 BINARY(16)
-- 原則7 適用外: 全テナント横断ドメイン
--
-- 設計判断:
--   - year_month は対象月の 1 日（DATE 型、UTC 基準）
--   - 同一村×同一年月は 1 行のみ（UNIQUE 制約で保証、再生成は UPSERT で更新）
--   - TOP3 タグは title からの簡易頻度カウント結果（最小実装、後に拡張余地あり）

CREATE TABLE village_chronicles (
    id                      BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    village_id              BINARY(16)      NOT NULL                                COMMENT 'FK → villages.id（同一ドメイン CASCADE）',
    year_month              DATE            NOT NULL                                COMMENT '対象年月の1日（例: 2026-05-01）',
    generated_at            DATETIME(6)     NOT NULL                                COMMENT '本レコード生成時刻',
    post_count              INT UNSIGNED    NOT NULL DEFAULT 0                      COMMENT '当月の投稿数（bulletin_threads + timeline_posts の VILLAGE スコープ）',
    new_member_count        INT UNSIGNED    NOT NULL DEFAULT 0                      COMMENT '当月新規参加メンバー数',
    topic_1_name            VARCHAR(100)    NULL                                    COMMENT 'TOP1 トピック名',
    topic_1_count           INT UNSIGNED    NOT NULL DEFAULT 0                      COMMENT 'TOP1 出現数',
    topic_2_name            VARCHAR(100)    NULL                                    COMMENT 'TOP2 トピック名',
    topic_2_count           INT UNSIGNED    NOT NULL DEFAULT 0                      COMMENT 'TOP2 出現数',
    topic_3_name            VARCHAR(100)    NULL                                    COMMENT 'TOP3 トピック名',
    topic_3_count           INT UNSIGNED    NOT NULL DEFAULT 0                      COMMENT 'TOP3 出現数',
    created_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version                 BIGINT          NOT NULL DEFAULT 0                      COMMENT '楽観ロック',
    PRIMARY KEY (id),
    UNIQUE KEY uk_vc_village_month (village_id, year_month),
    KEY idx_vc_village_generated_at (village_id, generated_at),
    CONSTRAINT fk_vc_village FOREIGN KEY (village_id) REFERENCES villages(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='村史（月次ダイジェスト）F17.1 Phase 3-β';
