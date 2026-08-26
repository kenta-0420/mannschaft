-- my_scope_folder_items.scope_id のみが符号付き BIGINT になっており、
-- 同じ意味（team_id or organization_id）を持つ他テーブルの scope_id 系カラム
-- （notifications, content_reports, feedback_submissions, recruitment_listings 等）
-- が軒並み BIGINT UNSIGNED であることと不統一。
-- 同一テーブル内でも id / folder_id は BIGINT UNSIGNED であり、scope_id だけが浮いている。
-- 符号性を UNSIGNED に揃えて統一する（issue #2545。
-- MyScopeFolderItemRepository の javadoc がこの不統一を「本 PR では扱わない」と自認していたが、
-- 本 migration で解消する）。

-- ---------------------------------------------------------------------
-- 番人 — 負値が存在すると UNSIGNED 変換で値が破壊されるため、変換前に検査して中断する
-- ---------------------------------------------------------------------
-- SIGNAL / IF は stored program の外では使えないため、手続きを一時的に作って CALL し、
-- 直ちに破棄する。flyway-mysql プラグインは BEGIN ... END ブロックを自動認識するため
-- DELIMITER 命令は不要（V13.045__create_repair_simulation_scenarios.sql の先例）。
DROP PROCEDURE IF EXISTS msfi_assert_no_negative_scope_id;

CREATE PROCEDURE msfi_assert_no_negative_scope_id()
BEGIN
    DECLARE negative_count BIGINT DEFAULT 0;

    SELECT COUNT(*) INTO negative_count
      FROM my_scope_folder_items
     WHERE scope_id < 0;

    -- MESSAGE_TEXT は 128 文字で切り詰められるため簡潔に保つ
    IF negative_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'my_scope_folder_items.scope_id に負値あり。UNSIGNED変換中断';
    END IF;
END;

CALL msfi_assert_no_negative_scope_id();

DROP PROCEDURE msfi_assert_no_negative_scope_id;

-- ---------------------------------------------------------------------
-- 本体変更 — COMMENT を失わないよう MODIFY COLUMN で全定義を明示する
-- ---------------------------------------------------------------------
ALTER TABLE my_scope_folder_items
  MODIFY COLUMN scope_id BIGINT UNSIGNED NOT NULL COMMENT 'team_id or organization_id';
