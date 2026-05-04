-- F08.7 Phase 9-γ: shift_budget_allocations.project_id への FK 追加
-- 設計書 F08.7 (v1.2) §5.2 / §5.7 に準拠。
--
-- 9-β V11.030 で project_id BIGINT UNSIGNED NULL を案A（事前配置）として既に投入済。
-- 同 V11.030 で `idx_sba_project` インデックスも既に張られている。
-- 本マイグレーションでは:
--   - project_id への FK 制約 (fk_sba_project) を追加する
--   - INDEX は既存（V11.030）のため再作成不要
--
-- マスター御裁可 Q3:
--   project_id は NULLABLE 維持（NOT NULL 化はしない）
--   理由: NULL = 「通常の月×費目×team 割当」であり、project_id は
--         プロジェクト専用割当のときのみ非NULLとなる NULLABLE 仕様が正しい
--   防衛線: 同一スコープ重複作成は ShiftBudgetAllocationService.findLiveByScope の
--         SELECT FOR UPDATE で確定（NULL を含む UNIQUE は MySQL 仕様で機能しないため）

ALTER TABLE shift_budget_allocations
    ADD CONSTRAINT fk_sba_project FOREIGN KEY (project_id)
        REFERENCES projects(id) ON DELETE RESTRICT;
