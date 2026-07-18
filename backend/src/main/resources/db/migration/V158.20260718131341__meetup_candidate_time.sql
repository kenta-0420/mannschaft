-- Issue #2357: 村寄合の候補日に「時刻」を保存できるようにする（DATE + 任意 TIME 分離）
--
-- 御裁可済の設計方式:
--   - 時刻は任意（終日 = NULL）。既存の「日付のみ」データは candidate_time = NULL で無損失移行する。
--   - 候補日 = (candidate_date, candidate_time) のペアで一意。同日別時刻の候補を共存させる。
--   - 確定時は候補の (date, time) を寄合本体の (confirmed_date, confirmed_time) へ転記する。
--
-- Expand/Migrate/Contract のうち本移行は Expand + Migrate（列追加 + UNIQUE 張り替え）。
-- 既存制約の変更は「DROP → 整合 → 再作成」順で行う（番人テストに配慮・CLAUDE.md 障害対応原則）。
--
-- ⚠️ MySQL の UNIQUE は TIME NULL を重複許容する（NULL 同士は等しくない扱い）ため、
--    終日候補（time = NULL）の重複は DB だけでは弾けない。アプリ層でも (date, time) ペアで
--    重複チェックを行うこと（VillageMeetupService の重複検査を拡張済み）。

-- 1) 候補日に任意の時刻列を追加（終日 = NULL）
--    MySQL の ADD COLUMN では COMMENT は column_definition の一部であり、位置指定 AFTER より前に置く。
ALTER TABLE village_meetup_candidate_dates
    ADD COLUMN candidate_time TIME NULL COMMENT '候補の時刻（任意・NULL は終日）' AFTER candidate_date;

-- 2) 寄合本体に確定時刻列を追加（CONFIRMED 時のみセット・終日 = NULL）
ALTER TABLE village_meetups
    ADD COLUMN confirmed_time TIME NULL COMMENT '確定時刻（CONFIRMED 時のみセット・NULL は終日）' AFTER confirmed_date;

-- 3) UNIQUE を (meetup_id, candidate_date) から (meetup_id, candidate_date, candidate_time) へ張り替える。
--    既存データは candidate_time = NULL なので (date, NULL) の組で従来どおり一意性が保たれる（無損失）。
ALTER TABLE village_meetup_candidate_dates
    DROP INDEX uk_vmcd_meetup_date;

ALTER TABLE village_meetup_candidate_dates
    ADD UNIQUE KEY uk_vmcd_meetup_date_time (meetup_id, candidate_date, candidate_time);
