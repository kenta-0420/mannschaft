-- F06.4 下書き対応: 活動記録に status（DRAFT / PUBLISHED）を追加する。
--
-- 採番根拠: origin/main の全 migration 最大 major は 140（V140.001）。その次の major = 141 を採用。
--
-- 後方互換の要点:
--   1) status 列は NOT NULL DEFAULT 'PUBLISHED'。
--      → 既存の全行は自動的に 'PUBLISHED' で backfill される（下書き扱いで消えない）。
--         Entity 側も @Builder.Default = PUBLISHED のため、従来の「作成即公開」経路は挙動不変。
--   2) template_id を NULL 許容に緩和する。
--      → DRAFT（下書き）は「title + activity_date のみ」の最小項目で作成できる必要があり、
--         テンプレート未指定を許容するため。既存行は値が入っているため影響なし。
--         FK 制約（fk_ar_template）は元々 ON DELETE RESTRICT で、NULL 許容にしても
--         非 NULL 値の参照整合性はそのまま維持される。

-- (1) status 列を追加（既存行は 'PUBLISHED' で backfill）
ALTER TABLE activity_results
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED'
        COMMENT 'ライフサイクル状態（DRAFT / PUBLISHED）' AFTER visibility;

-- (2) template_id を NULL 許容に緩和（DRAFT 最小作成のため）
ALTER TABLE activity_results
    MODIFY COLUMN template_id BIGINT UNSIGNED NULL;

-- (3) スコープ一覧の PUBLISHED 絞り込み用インデックス（DRAFT 除外クエリの効率化）
CREATE INDEX idx_ar_scope_status
    ON activity_results (scope_type, scope_id, status, activity_date DESC);
