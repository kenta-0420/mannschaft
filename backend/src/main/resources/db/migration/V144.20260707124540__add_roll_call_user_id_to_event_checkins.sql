-- F03.12 §14 主催者点呼: event_checkins へ点呼対象ユーザーID列を追補する。
--
-- 経緯: EventCheckinEntity.rollCallUserId（commit bd0a74fc1 で追加）に対応する ALTER TABLE が
-- 作り忘れられており、実マイグレーションから構築した DB では roll_call_user_id 列が存在しなかった。
-- そのため GET /api/v1/events/{id}/checkins が呼ぶ
-- EventCheckinRepository.findByEventIdOrderByCheckedInAtDesc（Hibernate 全列 SELECT）が
-- 「Unknown column 'ece1_0.roll_call_user_id' in 'field list'」で 500 になっていた。本 V144 で根治する。
--
-- roll_call_user_id は点呼（ROLL_CALL / ROLL_CALL_BATCH）チェックイン時の対象ユーザーID。
-- チケット式（STAFF_SCAN / SELF）では NULL（ticket_id の代替）。
-- EventCheckinRepository の点呼系クエリ（existsByEventIdAndUserId /
-- findCheckedInUserIdsByEventIdAndUserIdIn / findByEventIdAndRollCallSessionIdAndUserId /
-- findRollCallByEventIdAndUserId）が event_id + roll_call_user_id で絞るため索引を併設する。
-- users へのクロスドメイン FK は張らない（DB設計原則1）。参照整合性はアプリ層で保証する。
ALTER TABLE event_checkins
  ADD COLUMN roll_call_user_id BIGINT UNSIGNED NULL
    COMMENT '点呼チェックイン対象ユーザーID（ROLL_CALL系のみ。ticket_idの代替）',
  ADD INDEX idx_event_checkins_event_roll_call_user (event_id, roll_call_user_id);
