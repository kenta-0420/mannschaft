-- F08.7: activity_results テーブルに source_type/source_id カラムを追加
-- 大会試合結果から活動記録を自動作成する際のポリモーフィック参照用
ALTER TABLE activity_results
    ADD COLUMN source_type VARCHAR(30) NULL COMMENT 'ソース種別（TOURNAMENT_MATCH等）',
    ADD COLUMN source_id BIGINT UNSIGNED NULL COMMENT 'ソースID';

CREATE UNIQUE INDEX uq_ar_source ON activity_results (source_type, source_id);
