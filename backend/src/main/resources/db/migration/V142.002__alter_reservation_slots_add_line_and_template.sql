-- F03.4.2 機能F: reservation_slots へライン軸（line_id）と生成元（template_id）を追加する。
--
-- 後方互換（§3.1）:
--   line_id / template_id とも NULL 許容・デフォルトなしのため既存行は NULL のまま
--   （NULL = 共通枠 / 手動作成枠 = 既存互換。backfill はしない — 枠の帰属を機械的に復元する
--    情報が存在せず、誤った backfill は既存予約の表示・グリッドを壊すため）。
--
-- FK はいずれも同一 reservation ドメイン内（アーキ原則1の対象外・原則2の CASCADE 不使用）:
--   line_id     → reservation_lines      ON DELETE RESTRICT（物理削除防止の番人。運用は論理削除）
--   template_id → reservation_slot_templates ON DELETE SET NULL（テンプレ物理削除後も生成済み枠は独立残置）
--
-- uq_rs_template_cell (template_id, slot_date, start_time) は生成冪等の最終防御（§5.3）。
--   1テンプレ行は 1 日に同じ start_time のセルを 1 つしか作らないため 3 つ組で生成セルが一意。
--   template_id IS NULL（手動枠）は MySQL の UNIQUE 仕様（NULL は distinct）により制約対象外
--   ＝手動枠の自由度を奪わない。

ALTER TABLE reservation_slots
    ADD COLUMN line_id BIGINT UNSIGNED NULL AFTER staff_user_id,
    ADD COLUMN template_id BINARY(16) NULL AFTER line_id,
    ADD CONSTRAINT fk_rs_line
        FOREIGN KEY (line_id) REFERENCES reservation_lines (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_rs_template
        FOREIGN KEY (template_id) REFERENCES reservation_slot_templates (id) ON DELETE SET NULL,
    ADD INDEX idx_rs_team_date_line (team_id, slot_date, line_id),
    ADD UNIQUE KEY uq_rs_template_cell (template_id, slot_date, start_time);
