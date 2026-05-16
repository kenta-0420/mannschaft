-- F09.17 Phase 11-a: キャンペーンターゲティング条件 (INCLUDE/EXCLUDE)
-- INCLUDE を AND 合成し、その結果から EXCLUDE を AND NOT する
CREATE TABLE ad_audience_segments (
    id              BINARY(16) NOT NULL,
    campaign_id     BINARY(16) NOT NULL COMMENT 'ad_messaging_campaigns.id (FK CASCADE)',
    segment_type    ENUM('AGE_RANGE','GENDER','REGION_PREFECTURE','REGION_CITY','INTEREST_TAG','ORG_TYPE','LOCALE','DEVICE')
                              NOT NULL COMMENT 'セグメント種別',
    segment_value   JSON       NOT NULL COMMENT '条件値 (F09.2 SegmentEvaluator スキーマ準拠)',
    inclusion_mode  ENUM('INCLUDE','EXCLUDE') NOT NULL DEFAULT 'INCLUDE' COMMENT '包含/除外',
    created_at      DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_aas_camp (campaign_id, inclusion_mode),
    CONSTRAINT fk_aas_campaign FOREIGN KEY (campaign_id)
        REFERENCES ad_messaging_campaigns (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F09.17 ターゲティング条件';
