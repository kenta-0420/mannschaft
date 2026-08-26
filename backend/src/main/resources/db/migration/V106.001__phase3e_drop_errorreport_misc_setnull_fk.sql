-- Phase 3-E: クロスドメインFK撤廃 第三陣E — errorreport / resident_registry / survey / todo / notification ドメインの SET NULL 監査FK8件を撤廃（撤廃only）
--
-- 1000万ユーザー耐久DB再構築 クロスドメインFK撤廃キャンペーン Phase 3-E。
-- CLAUDE.md §1「クロスドメインFKは作らない」/ §2「CASCADE/SET NULL のクロスドメイン削除連鎖は禁止」
-- 原則に従い、error_reports（FEエラー追跡）/ resident_registry（住民台帳）/ surveys（アンケート）/
-- todo_status_labels（TODOステータスラベル）/ notifications（通知）の
-- 「users を親とする ON DELETE SET NULL の監査/操作者カラム」FK を撤廃する。
--
-- ━━━ 対象一覧（8件・すべて user 親 ON DELETE SET NULL のクロスドメインFK・すべて明示名）━━━
--  error_reports（FEエラー追跡）ドメイン（3件）:
--   1. error_reports     / fk_error_reports_assignee_id (assignee_id → users SET NULL)
--   2. error_reports     / fk_error_reports_resolved_by (resolved_by → users SET NULL)
--   3. error_reports     / fk_error_reports_user_id     (user_id     → users SET NULL)
--  resident_registry（住民台帳）ドメイン（2件）:
--   4. resident_registry / fk_rr_user                   (user_id     → users SET NULL)
--   5. resident_registry / fk_rr_verified_by            (verified_by → users SET NULL)
--  surveys（アンケート）ドメイン（1件）:
--   6. surveys           / fk_surveys_created_by        (created_by  → users SET NULL)
--  todo（TODO）ドメイン（1件）:
--   7. todo_status_labels/ fk_tsl_created_by            (created_by  → users SET NULL)
--  notification（通知）ドメイン（1件）:
--   8. notifications     / fk_notifications_actor       (actor_id    → users SET NULL)
--
-- ━━━ なぜ「撤廃only・孤児 user_id 保持」が安全か（監査履歴温存・マスター御裁可）━━━
--
-- これら8件はいずれも「誰が報告/担当/解決したか（error_reports）/ 誰が居住者か・誰が確認したか（resident_registry）/
-- 誰がアンケートを作成したか（surveys）/ 誰がステータスラベルを作成したか（todo_status_labels）/
-- 誰が通知を引き起こした操作者か（notifications.actor_id）」を表す監査・操作者カラムである。
-- 運用証跡として保持価値があり、退会後も「操作者の id（孤児値）」を残したい。
--
-- 退会フローは2段階モデル（CLAUDE.md「PII 消去のタイミング §13.12」）:
--   ・退会受付直後: user 本体を匿名化（UserEntity.anonymize()）するが行は残す。
--   ・退会受付から最大30日後: AccountPurgeService.purgeUser → userRepository.delete で
--     はじめて users 行を物理削除する。
--
-- すなわち ON DELETE SET NULL が発火しうるのは「30日後の物理削除」のときだけだが、
-- 現状のままだと物理削除の瞬間に上記の監査列が NULL に書き換えられ、「誰が報告/担当/作成/操作したか」
-- の証跡が失われる。本 migration で SET NULL FK を撤廃すると、users 物理削除後も子行の監査列は
-- 孤児 user_id（もう users には存在しない値）を保持し続け、監査履歴が温存される（＝マスター御裁可の狙い）。
-- 参照整合性はアプリ層で保証する（CLAUDE.md §1）。
--
-- ━━━ SET NULL ゆえ行削除リスクは元々ゼロ（CASCADE との違い・リスナー不要）━━━
--
-- 第二陣の CASCADE 撤廃は「物理削除で子行が連鎖削除される」リスクの根治だったが、本対象は
-- すべて ON DELETE SET NULL であり、もともと子行が削除されることはない（列が NULL 化されるだけ）。
-- したがって本 migration は極めて低リスクで、リスナーもデータ操作も NULL 化処理も一切伴わない、
-- 純粋な FK 撤廃のみである。番人テストが見る不変条件も CASCADE 版の「行が消えない」ではなく
-- 「親 users 物理削除後も監査列が NULL 化されず孤児 user_id 値を保持する」である。
--
-- ━━━ index 判定（FK 撤廃でバッキングインデックスが消えても CREATE INDEX 追加が必要か）━━━
--
-- 結論: 8件とも CREATE INDEX 追加は不要。
--   ・error_reports.assignee_id : V12.013 で別途 CREATE INDEX idx_error_reports_assignee_id(assignee_id) が
--       存在し FK 撤廃後も残存する → 逆引きは既存 index でカバー → 追加不要。
--   ・error_reports.user_id     : V12.006 で別途 CREATE INDEX idx_error_reports_user_id(user_id) が
--       存在し FK 撤廃後も残存する → 逆引きは既存 index でカバー → 追加不要。
--   ・error_reports.resolved_by : 冷たい監査列。resolved_by を先頭に持つ非FK index なし・finder なし。
--       FK の暗黙バッキング index のみで撤廃で消えても影響なし → 追加不要。
--   ・resident_registry.user_id : CREATE 時に INDEX idx_rr_user(user_id) が定義済で FK 撤廃後も残存 → 追加不要。
--   ・resident_registry.verified_by : 冷たい監査列。verified_by を先頭に持つ非FK index なし・finder なし → 追加不要。
--   ・surveys.created_by         : CREATE 時に INDEX idx_surveys_created_by(created_by) が定義済で FK 撤廃後も残存 → 追加不要。
--   ・todo_status_labels.created_by : 冷たい監査列。created_by を先頭に持つ非FK index なし・finder なし → 追加不要。
--   ・notifications.actor_id     : 冷たい監査列。actor_id を先頭に持つ非FK index なし・finder なし → 追加不要。
--
-- ━━━ 対象外（本 migration では一切触らない）━━━
--   ・error_reports → organizations（fk_error_reports_organization_id）は第一陣 V95.001 で撤廃済（本 migration では触らない）。
--   ・resident_registry → dwelling_units（fk_rr_dwelling_unit CASCADE・同一 F09.1 住民台帳ドメイン）。
--   ・notifications → users（fk_notifications_user CASCADE）は第二陣 V100.001 で撤廃済（再 DROP しない）。
--   ・todo_handoffs → users（fk_handoff_from_user RESTRICT）/ todo_status_labels への各 SET NULL FK 等。
--   ・各テーブルの上記8つの SET NULL → users 以外の FK 全て。

-- ===== error_reports（FEエラー追跡ドメイン）=====
-- fk_error_reports_assignee_id: assignee_id → users (SET NULL) クロスドメイン監査列
-- → 撤廃only。退会30日後の users 物理削除でも「誰が担当か」の孤児 user_id を保持＝監査履歴温存。
--   別途 idx_error_reports_assignee_id(assignee_id)（V12.013）が残存 → index 追加不要。
ALTER TABLE error_reports DROP FOREIGN KEY fk_error_reports_assignee_id;

-- fk_error_reports_resolved_by: resolved_by → users (SET NULL) クロスドメイン監査列
-- → 撤廃only。退会30日後の users 物理削除でも「誰が解決したか」の孤児 user_id を保持＝監査履歴温存。
--   冷たい監査列・resolved_by 先頭の専用 index なし・finder なし → index 追加不要。
ALTER TABLE error_reports DROP FOREIGN KEY fk_error_reports_resolved_by;

-- fk_error_reports_user_id: user_id → users (SET NULL) クロスドメイン（報告者）
-- → 撤廃only。退会30日後の users 物理削除でも「誰が報告したか」の孤児 user_id を保持＝監査履歴温存。
--   別途 idx_error_reports_user_id(user_id)（V12.006）が残存 → index 追加不要。
ALTER TABLE error_reports DROP FOREIGN KEY fk_error_reports_user_id;

-- ===== resident_registry（住民台帳ドメイン）=====
-- fk_rr_user: user_id → users (SET NULL) クロスドメイン（居住者の紐づくユーザー）
-- → 撤廃only。退会30日後の users 物理削除でも「どの居住者がどのユーザーか」の孤児 user_id を保持＝台帳証跡温存。
--   CREATE 時定義の idx_rr_user(user_id) が残存 → index 追加不要。
ALTER TABLE resident_registry DROP FOREIGN KEY fk_rr_user;

-- fk_rr_verified_by: verified_by → users (SET NULL) クロスドメイン監査列
-- → 撤廃only。退会30日後の users 物理削除でも「誰が確認したか」の孤児 user_id を保持＝監査履歴温存。
--   冷たい監査列・verified_by 先頭の専用 index なし・finder なし → index 追加不要。
ALTER TABLE resident_registry DROP FOREIGN KEY fk_rr_verified_by;

-- ===== surveys（アンケートドメイン）=====
-- fk_surveys_created_by: created_by → users (SET NULL) クロスドメイン監査列
-- → 撤廃only。退会30日後の users 物理削除でも「誰がアンケートを作成したか」の孤児 user_id を保持＝監査履歴温存。
--   CREATE 時定義の idx_surveys_created_by(created_by) が残存 → index 追加不要。
ALTER TABLE surveys DROP FOREIGN KEY fk_surveys_created_by;

-- ===== todo_status_labels（TODOドメイン）=====
-- fk_tsl_created_by: created_by → users (SET NULL) クロスドメイン監査列
-- → 撤廃only。退会30日後の users 物理削除でも「誰がステータスラベルを作成したか」の孤児 user_id を保持＝監査履歴温存。
--   冷たい監査列・created_by 先頭の専用 index なし・finder なし → index 追加不要。
ALTER TABLE todo_status_labels DROP FOREIGN KEY fk_tsl_created_by;

-- ===== notifications（通知ドメイン）=====
-- fk_notifications_actor: actor_id → users (SET NULL) クロスドメイン（通知を引き起こした操作者）
-- → 撤廃only。退会30日後の users 物理削除でも「誰が通知を引き起こしたか」の孤児 user_id を保持＝監査履歴温存。
--   冷たい監査列・actor_id 先頭の専用 index なし・finder なし → index 追加不要。
--   注: fk_notifications_user（user_id → users CASCADE）は第二陣 V100.001 で撤廃済（本 migration では触らない）。
ALTER TABLE notifications DROP FOREIGN KEY fk_notifications_actor;
