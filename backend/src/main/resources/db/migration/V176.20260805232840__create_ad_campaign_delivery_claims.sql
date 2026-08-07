-- F09.17 Phase 11-c: キャンペーン配信の claim-then-act 用テーブル
-- (campaign_id, user_id, week_start) の一意制約により、同一週内の同一キャンペーン・同一ユーザーへの
-- 二重配信を DB 側で構造的に不可能にする（Valkey フリークエンシーキャップと二重の守り）。
-- week_start の定義は AdFrequencyCapService の週開始（ユーザー TZ の月曜 00:00）と厳密に一致させる。
CREATE TABLE ad_campaign_delivery_claims (
    id            BINARY(16)      NOT NULL,
    campaign_id   BINARY(16)      NOT NULL COMMENT 'ad_messaging_campaigns.id (FK CASCADE)',
    user_id       BIGINT UNSIGNED NOT NULL COMMENT '配信先ユーザー (FKなし・クロスドメイン参照)',
    week_start    DATE            NOT NULL COMMENT '消費週の月曜 (ユーザーTZ、AdFrequencyCapServiceと同一定義)',
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_acdc_campaign_user_week (campaign_id, user_id, week_start),
    INDEX idx_acdc_campaign_week (campaign_id, week_start),
    CONSTRAINT fk_acdc_campaign FOREIGN KEY (campaign_id)
        REFERENCES ad_messaging_campaigns (id) ON DELETE CASCADE
-- 照合順序は utf8mb4_0900_ai_ci（issue #2589 でスキーマ全体を統一した先）。
-- 本ファイルは統一 migration（V175）と並行して作られたため当初 utf8mb4_unicode_ci を
-- 宣言しており、V175 の直後に非統一の表を作る形になっていた。
-- MigrationCollationDeclarationGuardTest と SchemaCollationConsistencyIT の双方が
-- これを検出したため、宣言を統一先へ修正した。
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='F09.17 キャンペーン配信 claim（週内・同一ユーザーへの二重配信防止）';
