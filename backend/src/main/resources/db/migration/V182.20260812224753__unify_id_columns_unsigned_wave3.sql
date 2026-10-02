-- 符号揃え 第三波（issue #2545）:
-- 第二波（V181.20260812030534）で除外した attendance_requirement_evaluations の
-- FK 2列を是正する。除外理由は「参照先の主キー自体が符号付きのままで、
-- 子を先に符号なしへ変えると FK の型不一致で ALTER が失敗する」だったため、
-- 本波では「親（参照先の主キー）→ 子（参照元）」の順で是正する。
--
-- 手順:
--   1. FK 制約を一時的に落とす（InnoDB は FK 制約が張られたまま親の PK 型を
--      変更させない。foreign_key_checks=0 で回避する流儀もあるが、
--      DDL 中の型不一致は checks の有無に関わらず拒否されるため、
--      制約そのものを外すのが唯一確実な手段）。
--   2. 親テーブルの主キー（attendance_requirement_rules.id /
--      student_attendance_summaries.id）を BIGINT UNSIGNED へ是正する。
--   3. 子テーブル（attendance_requirement_evaluations）の FK 列
--      requirement_rule_id / summary_id を BIGINT UNSIGNED へ是正する。
--   4. FK 制約を型が揃った状態で張り直す。
--
-- 追加で発見した2件（新設した MigrationIdColumnUnsignedGuardTest の初回実行で検出。
-- FK 依存とは無関係で、単に UNSIGNED を書き落とした/失った回帰）:
--   * notifications.organization_id … V65.001 の ADD COLUMN が UNSIGNED を書き落としたまま
--     （新規列追加。「_id 列は BIGINT UNSIGNED」原則を初出時点で満たしていなかった）
--   * ad_invoice_items.campaign_id … V10.063 では BIGINT UNSIGNED だったが、
--     V67.023 の MODIFY COLUMN（NULL 許容化）が UNSIGNED を落として上書きした
--     （MODIFY COLUMN は定義を丸ごと置き換える罠の実例）
-- いずれも FK 制約は張られていない（V10.063/V65.001 系ヘッダに明記のとおり）ため、
-- 単純な MODIFY COLUMN で是正できる。番人を「例外なし」で有効化する前提として、
-- ここで残件をゼロにする。
--
-- Entity 側（Java）は Long のまま変更不要。
-- 本マイグレーションは新規ファイルであり、Flyway チェックサム不一致は起きない。

-- ---------------------------------------------------------------------
-- 番人 — 負値が存在すると UNSIGNED 変換で値が破壊されるため、変換前に検査して中断する
-- （V177/V180/V181 の先例に倣う。SIGNAL MESSAGE_TEXT は128文字上限）
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS w3_assert_no_negative_id_columns;

CREATE PROCEDURE w3_assert_no_negative_id_columns()
BEGIN
    DECLARE negative_count BIGINT DEFAULT 0;

    SELECT COUNT(*) INTO negative_count FROM attendance_requirement_rules WHERE id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'attendance_requirement_rules.idに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM student_attendance_summaries WHERE id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'student_attendance_summaries.idに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM attendance_requirement_evaluations
     WHERE requirement_rule_id < 0 OR summary_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'attendance_requirement_evaluationsに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM notifications WHERE organization_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'notifications.organization_idに負値'; END IF;

    SELECT COUNT(*) INTO negative_count FROM ad_invoice_items WHERE campaign_id < 0;
    IF negative_count > 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ad_invoice_items.campaign_idに負値'; END IF;
END;

CALL w3_assert_no_negative_id_columns();

DROP PROCEDURE w3_assert_no_negative_id_columns;

-- ---------------------------------------------------------------------
-- 1. FK 制約を一時的に落とす（V18.019__create_attendance_requirement_evaluations.sql の
--    fk_are_rule / fk_are_summary。fk_are_student / fk_are_resolver は本波の対象外
--    〔student_user_id・resolver_user_id は wave1/2 以前から BIGINT UNSIGNED 済み〕なので触らない）
-- ---------------------------------------------------------------------
ALTER TABLE attendance_requirement_evaluations
    DROP FOREIGN KEY fk_are_rule,
    DROP FOREIGN KEY fk_are_summary;

-- ---------------------------------------------------------------------
-- 2. 親テーブルの主キーを是正する（元の宣言 = V18.008 / V18.009 の
--    `id BIGINT AUTO_INCREMENT PRIMARY KEY` を一字一句転記の上 UNSIGNED を追加）
-- ---------------------------------------------------------------------
ALTER TABLE attendance_requirement_rules
    MODIFY COLUMN id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT;

ALTER TABLE student_attendance_summaries
    MODIFY COLUMN id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT;

-- ---------------------------------------------------------------------
-- 3. 子テーブルの FK 列を是正する（元の宣言 = V18.019 の COMMENT を転記）
-- ---------------------------------------------------------------------
ALTER TABLE attendance_requirement_evaluations
    MODIFY COLUMN requirement_rule_id BIGINT UNSIGNED NOT NULL COMMENT 'FK→attendance_requirement_rules.id',
    MODIFY COLUMN summary_id          BIGINT UNSIGNED NOT NULL COMMENT 'FK→student_attendance_summaries.id';

-- ---------------------------------------------------------------------
-- 4. FK 制約を型が揃った状態で張り直す（制約名・参照先は V18.019 のまま）
-- ---------------------------------------------------------------------
ALTER TABLE attendance_requirement_evaluations
    ADD CONSTRAINT fk_are_rule    FOREIGN KEY (requirement_rule_id) REFERENCES attendance_requirement_rules(id),
    ADD CONSTRAINT fk_are_summary FOREIGN KEY (summary_id)          REFERENCES student_attendance_summaries(id);

-- ---------------------------------------------------------------------
-- 5. 番人の初回実行で追加検出した2件（FK 非依存・単純是正）
-- ---------------------------------------------------------------------
ALTER TABLE notifications
    MODIFY COLUMN organization_id BIGINT UNSIGNED NULL;

ALTER TABLE ad_invoice_items
    MODIFY COLUMN campaign_id BIGINT UNSIGNED NULL
        COMMENT 'F09.7 ad_campaigns.id (NULL=F09.17 messaging_campaign_id 経由)';
