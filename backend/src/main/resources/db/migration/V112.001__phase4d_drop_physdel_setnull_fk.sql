-- Phase 4-D（第四陣D・ラスト）: クロスドメインFK撤廃 — proxy_input_records / timetable_slots /
-- timetable_changes / activity_template_fields を「参照先テーブル」とする SET NULL 構造FK 7件を撤廃
-- （撤廃only・孤児保持）。第四陣（A=V109.001 / B=V110.001 / C=V111.001 / D=V112.001）を完結させる。
--
-- 1000万ユーザー耐久DB再構築 クロスドメインFK撤廃キャンペーン Phase 4-D。
-- CLAUDE.md §1「クロスドメインFKは作らない」/ §2「CASCADE/SET NULL のクロスドメイン削除連鎖は禁止」
-- 原則に従い、他ドメインの「テーブル」（users ではない）を ON DELETE SET NULL で参照する
-- 群2＝構造参照のクロスドメインFK 7件を撤廃する。
--
-- ━━━ 群2（本陣の対象）の定義 ━━━
-- 群1（users 親の監査列 SET NULL）と異なり、群2は「他ドメインの実テーブルの行が削除された時に
-- SET NULL される構造参照」である。退会フローとは無関係で、参照先テーブルの行削除がトリガになる。
--
-- ━━━ 本 PR-4d の特殊性（他の第四陣 PR との決定的な違い）━━━
-- 第四陣 A/B/C の参照先（schedules / todos / timeline_posts / visibility_templates / surveys 等）は
-- いずれも論理削除 or status 遷移のみで「物理削除されない」＝ ON DELETE SET NULL が発火する契機が
-- 存在しないため撤廃が無条件に安全であった。
-- これに対し本 PR-4d の参照先 4 テーブルは「実際に物理削除される運用がある」:
--   ・proxy_input_records     : 保持期限ジョブ / 退会 purge による物理 DELETE。
--   ・timetable_slots         : 時間割再構築時の deleteAll による物理 DELETE。
--   ・timetable_changes       : 臨時変更取消時の delete による物理 DELETE。
--   ・activity_template_fields: テンプレート編集（フィールド削除）時の delete による物理 DELETE。
-- すなわち ON DELETE SET NULL の「発火契機」が現に存在する。
--
-- ━━━ それでも「撤廃only・孤児値保持・リスナー/データ操作/NULL化なし」が安全な理由 ━━━
-- 家老偵察で参照元の外部キー列が「write-only / 不活性」であることを裏取り済（getter/JOIN/query 0件）:
--   ・announcement_read_status.proxy_input_record_id : 代理確認の証跡列。記録時に書くのみで、この列を
--       条件 / JOIN / 集計に使うクエリは存在しない。孤児化しても漏洩・NPE・誤集計なし。
--   ・circulation_recipients.proxy_input_record_id   : 同上（代理確認押印の証跡列・write-only）。
--   ・parking_applications.proxy_input_record_id     : 同上（代理申請の証跡列・write-only）。
--   ・shift_requests.proxy_input_record_id           : 同上（代理シフト希望の証跡列・write-only）。
--   ・period_attendance_records.timetable_slot_id    : 出欠記録時に「どのコマか」を紐づけた参照列。記録後は
--       subject_name / teacher_name 等のスナップショット列で履歴を保持しており、この id 列を
--       読む / JOIN するクエリは存在しない（出欠は date/period/student 軸で引かれる）。
--   ・period_attendance_records.timetable_change_id  : 同上（臨時変更時の紐づけ・write-only）。
--   ・performance_metrics.linked_activity_field_id   : 活動テンプレフィールドとの連携 id。連携元として
--       前向きに解決するのみで、この列を条件 / 集計に使う逆引きクエリは存在しない（write-only / 不活性）。
-- したがって参照先が物理削除されて孤児値が残っても、参照元の読み取り経路が存在しないため
-- 漏洩・NPE・誤集計のいずれも発生しない。撤廃only（孤児保持）が安全である。
--
-- 本 migration は「純粋な FK 撤廃のみ」である。リスナー・データ操作・NULL 化処理は一切伴わない。
-- 参照整合性は今後アプリ層で保証する（CLAUDE.md §1）。番人テストが守る不変条件は
-- 「参照先テーブルの行を（テスト内で意図的に）物理 DELETE しても、参照元の外部キー列が NULL 化されず
--  孤児値を保持し続ける」ことであり、SET NULL 撤廃only の肝を直接検証する。
--
-- ━━━ 対象一覧（7件・すべて明示名・すべて SET NULL → 他ドメインのテーブル）━━━
--  1. announcement_read_status  / fk_announcement_read_status_proxy (proxy_input_record_id    → proxy_input_records      SET NULL)
--  2. circulation_recipients    / fk_circulation_recipients_proxy   (proxy_input_record_id    → proxy_input_records      SET NULL)
--  3. parking_applications      / fk_parking_applications_proxy     (proxy_input_record_id    → proxy_input_records      SET NULL)
--  4. shift_requests            / fk_shift_requests_proxy           (proxy_input_record_id    → proxy_input_records      SET NULL)
--  5. period_attendance_records / fk_par_timetable_slot             (timetable_slot_id        → timetable_slots          SET NULL)
--  6. period_attendance_records / fk_par_timetable_change           (timetable_change_id      → timetable_changes        SET NULL)
--  7. performance_metrics       / fk_pm_linked_field                (linked_activity_field_id → activity_template_fields SET NULL)
--
-- ━━━ index 判定（FK 撤廃で暗黙バッキング index が消えても CREATE INDEX 追加が必要か）━━━
-- 結論: 7件とも CREATE INDEX 追加は不要。
--  ・当該列を先頭に持つ非FK index が既存で FK の暗黙 index に依存しないもの（1件）:
--    7. performance_metrics.linked_activity_field_id : INDEX idx_pm_linked_field (linked_activity_field_id) 既存（先頭=当該列）→ 追加不要。
--  ・冷たい関連列（write-only / 不活性）で当該列を先頭にした非FK index も逆引き finder も存在しないもの（6件）:
--    1. announcement_read_status.proxy_input_record_id : 既存 index は idx_announcement_read_status_proxy (is_proxy_confirmed)・idx_ars_user (user_id, …) で当該列先頭の index なし。逆引き finder なし → 追加不要。
--    2. circulation_recipients.proxy_input_record_id   : 既存 index は idx_circulation_recipients_proxy (is_proxy_confirmed)。当該列先頭の index なし・逆引き finder なし → 追加不要。
--    3. parking_applications.proxy_input_record_id     : 既存 index は idx_parking_applications_proxy (is_proxy_input) 他。当該列先頭の index なし・逆引き finder なし → 追加不要。
--    4. shift_requests.proxy_input_record_id           : 既存 index は idx_shift_requests_proxy (is_proxy_input) 他。当該列先頭の index なし・逆引き finder なし → 追加不要。
--    5. period_attendance_records.timetable_slot_id    : 既存 index は date/period・student/date・teacher 軸のみ。当該列先頭の index なし・逆引き finder なし → 追加不要。
--    6. period_attendance_records.timetable_change_id  : 同上。当該列先頭の index なし・逆引き finder なし → 追加不要。
--
-- ━━━ 対象外（本 migration では一切触らない）━━━
--   ・announcement_read_status → announcement_feeds（fk_ars_feed CASCADE）/ → users（fk_ars_user CASCADE）。
--   ・circulation_recipients   → circulation_documents（fk_circulation_recipients_document CASCADE）/ → users（fk_circulation_recipients_user CASCADE）。
--   ・parking_applications      : 他にクロスドメインFKは存在しない（space_id/user_id/vehicle_id は FK制約なしの素のカラム）。
--   ・shift_requests           → shift_schedules（fk_sr_schedule CASCADE・同一 shift ドメイン）/ → users（fk_sr_user CASCADE）/ → shift_slots（fk_sr_slot CASCADE・同一ドメイン）。
--   ・period_attendance_records → teams（fk_par_team CASCADE）/ → users（fk_par_student/fk_par_recorded_by/fk_par_teacher_user）。
--   ・performance_metrics       → teams（fk_pm_team・第一陣 V62.007 で撤廃済の関連は除く / 本 migration 対象外）。
--   上記7つの「SET NULL → proxy_input_records / timetable_slots / timetable_changes / activity_template_fields」のみを DROP する。

-- ===== proxy_input_records（参照先・proxy ドメイン / 物理削除あり: 保持期限ジョブ・退会 purge）を参照する SET NULL FK 4件 =====

-- 1. announcement_read_status.proxy_input_record_id → proxy_input_records (SET NULL) クロスドメイン構造参照
--    撤廃only。参照先は物理削除されるが当該列は代理確認の証跡（write-only / 不活性）で孤児化しても漏洩/NPEなし。冷たい関連列・逆引き finder なし → index 追加不要。
ALTER TABLE announcement_read_status DROP FOREIGN KEY fk_announcement_read_status_proxy;

-- 2. circulation_recipients.proxy_input_record_id → proxy_input_records (SET NULL) クロスドメイン構造参照
--    撤廃only。参照先は物理削除されるが当該列は代理確認押印の証跡（write-only / 不活性）。冷たい関連列・逆引き finder なし → index 追加不要。
ALTER TABLE circulation_recipients DROP FOREIGN KEY fk_circulation_recipients_proxy;

-- 3. parking_applications.proxy_input_record_id → proxy_input_records (SET NULL) クロスドメイン構造参照
--    撤廃only。参照先は物理削除されるが当該列は代理申請の証跡（write-only / 不活性）。冷たい関連列・逆引き finder なし → index 追加不要。
ALTER TABLE parking_applications DROP FOREIGN KEY fk_parking_applications_proxy;

-- 4. shift_requests.proxy_input_record_id → proxy_input_records (SET NULL) クロスドメイン構造参照
--    撤廃only。参照先は物理削除されるが当該列は代理シフト希望の証跡（write-only / 不活性）。冷たい関連列・逆引き finder なし → index 追加不要。
ALTER TABLE shift_requests DROP FOREIGN KEY fk_shift_requests_proxy;

-- ===== timetable_slots（参照先・schedule/timetable ドメイン / 物理削除あり: 再構築 deleteAll）を参照する SET NULL FK 1件 =====

-- 5. period_attendance_records.timetable_slot_id → timetable_slots (SET NULL) クロスドメイン構造参照
--    撤廃only。参照先は再構築で物理削除されるが当該列は出欠記録時の紐づけ（write-only / 不活性・履歴は subject_name 等のスナップショット列で保持）。冷たい関連列・逆引き finder なし → index 追加不要。
ALTER TABLE period_attendance_records DROP FOREIGN KEY fk_par_timetable_slot;

-- ===== timetable_changes（参照先・schedule/timetable ドメイン / 物理削除あり: 臨時変更取消 delete）を参照する SET NULL FK 1件 =====

-- 6. period_attendance_records.timetable_change_id → timetable_changes (SET NULL) クロスドメイン構造参照
--    撤廃only。参照先は取消で物理削除されるが当該列は出欠記録時の紐づけ（write-only / 不活性）。冷たい関連列・逆引き finder なし → index 追加不要。
ALTER TABLE period_attendance_records DROP FOREIGN KEY fk_par_timetable_change;

-- ===== activity_template_fields（参照先・activity ドメイン / 物理削除あり: テンプレ編集 delete）を参照する SET NULL FK 1件 =====

-- 7. performance_metrics.linked_activity_field_id → activity_template_fields (SET NULL) クロスドメイン構造参照
--    撤廃only。参照先はテンプレ編集で物理削除されるが当該列は連携 id（write-only / 不活性）。INDEX idx_pm_linked_field 既存（先頭=当該列）→ index 追加不要。
ALTER TABLE performance_metrics DROP FOREIGN KEY fk_pm_linked_field;
