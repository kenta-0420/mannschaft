-- F03.10: 年間行事計画 - schedulesテーブルに行事カテゴリ・年度・コピー元カラムを追加
ALTER TABLE schedules
    ADD COLUMN event_category_id  BIGINT UNSIGNED NULL COMMENT '行事カテゴリ（NULL=カテゴリ未設定）' AFTER comment_option,
    ADD COLUMN source_schedule_id BIGINT UNSIGNED NULL COMMENT '前年度トレースのコピー元スケジュールID（ソフト参照・FK制約なし）' AFTER event_category_id,
    ADD COLUMN academic_year      SMALLINT        NULL COMMENT '年度（例: 2026）。年度ビュー用' AFTER source_schedule_id;

-- FK: event_category_id → schedule_event_categories（ON DELETE SET NULL）
ALTER TABLE schedules
    ADD CONSTRAINT fk_sch_event_category
        FOREIGN KEY (event_category_id) REFERENCES schedule_event_categories (id) ON DELETE SET NULL;

-- source_schedule_id は FK 制約なし（ソフトリファレンス）

-- インデックス追加
ALTER TABLE schedules
    ADD INDEX idx_sch_category (event_category_id),
    ADD INDEX idx_sch_academic_year (team_id, academic_year, start_at),
    ADD INDEX idx_sch_org_academic_year (organization_id, academic_year, start_at);
