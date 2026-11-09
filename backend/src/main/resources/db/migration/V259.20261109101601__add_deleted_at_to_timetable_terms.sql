-- CMP-261105-1507: 学期を論理削除できるようにする。
-- timetables.term_id は NOT NULL かつ fk_tm_term が ON DELETE RESTRICT のため、論理削除済みの時間割だけが残る学期は
-- 物理削除できず、削除が行き止まりになっていた。学期側も論理削除にして参照を保ったまま一覧から外す。
ALTER TABLE timetable_terms
    ADD COLUMN deleted_at DATETIME NULL COMMENT '論理削除' AFTER updated_at;
