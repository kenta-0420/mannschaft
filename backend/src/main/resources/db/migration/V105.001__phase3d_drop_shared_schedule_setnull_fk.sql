-- Phase 3-D: クロスドメインFK撤廃 第三陣D — shared / schedule / timetable / team_templates ドメインの SET NULL 監査FK6件を撤廃（撤廃only）
--
-- 1000万ユーザー耐久DB再構築 クロスドメインFK撤廃キャンペーン Phase 3-D。
-- CLAUDE.md §1「クロスドメインFKは作らない」/ §2「CASCADE/SET NULL のクロスドメイン削除連鎖は禁止」
-- 原則に従い、shared（ファイル共有）/ schedule / timetable / team_templates の
-- 「users を親とする ON DELETE SET NULL の監査/操作者カラム」FK を撤廃する。
--
-- ━━━ 対象一覧（6件・すべて user 親 ON DELETE SET NULL のクロスドメインFK・すべて明示名）━━━
--  shared（ファイル共有）ドメイン（3件）:
--   1. shared_file_versions / fk_file_versions_uploaded    (uploaded_by → users SET NULL)
--   2. shared_folders        / fk_shared_folders_user       (user_id     → users SET NULL)
--   3. shared_folders        / fk_shared_folders_created    (created_by  → users SET NULL)
--  schedule ドメイン（1件）:
--   4. schedules             / fk_sch_created_by            (created_by  → users SET NULL)
--  timetable ドメイン（1件）:
--   5. timetables            / fk_tm_created_by             (created_by  → users SET NULL)
--  team_templates（1件）:
--   6. team_templates        / fk_team_templates_created_by (created_by  → users SET NULL)
--
-- ━━━ なぜ「撤廃only・孤児 user_id 保持」が安全か（監査履歴温存・マスター御裁可）━━━
--
-- これら6件はいずれも「誰がファイルをアップロードしたか / 誰が（どの会員として）個人フォルダを所有するか /
-- 誰がフォルダを作成したか / 誰が予定を作成したか / 誰が時間割を作成したか / 誰がチームテンプレートを作成したか」
-- を表す監査・操作者カラムである。運用証跡として保持価値があり、退会後も「操作者の id（孤児値）」を残したい。
--
-- 退会フローは2段階モデル（CLAUDE.md「PII 消去のタイミング §13.12」）:
--   ・退会受付直後: user 本体を匿名化（UserEntity.anonymize()）するが行は残す。
--   ・退会受付から最大30日後: AccountPurgeService.purgeUser → userRepository.delete で
--     はじめて users 行を物理削除する。
--
-- すなわち ON DELETE SET NULL が発火しうるのは「30日後の物理削除」のときだけだが、
-- 現状のままだと物理削除の瞬間に上記の監査列が NULL に書き換えられ、「誰が作成/所有/アップロードしたか」
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
-- 結論: 6件とも CREATE INDEX 追加は不要。
--   ・shared_file_versions.uploaded_by / shared_folders.created_by / schedules.created_by /
--     timetables.created_by / team_templates.created_by はいずれも「冷たい監査列」で、
--     当該列を先頭に持つ非FK index は存在せず、当該列で逆引きする repository finder も存在しない。
--     FK の暗黙バッキング index のみであり、撤廃で消えても影響なし。
--   ・shared_folders.user_id（fk_shared_folders_user）も同様に user_id を先頭に持つ非FK index は存在せず
--     （idx_shared_folders_team_id / idx_shared_folders_org / idx_shared_folders_tournament いずれも user_id 先頭でない）、
--     user_id で逆引きする実クエリも無い → index 追加不要。
--
-- ━━━ 対象外（本 migration では一切触らない）━━━
--   ・shared_folders → teams（fk_shared_folders_team CASCADE）/ → organizations（fk_shared_folders_org CASCADE）。
--   ・shared_folders → shared_folders（fk_shared_folders_parent 自己参照 SET NULL・クロスドメインでない）。
--   ・shared_file_versions → shared_files（fk_file_versions_file CASCADE 同一ドメイン）。
--   ・schedules → teams / organizations（CASCADE）/ → users（fk_sch_user は第二陣で撤廃済）/
--     → schedules（fk_sch_parent RESTRICT 同一ドメイン）/ → committees（fk_schedules_committee CASCADE）。
--   ・timetables → teams（fk_tm_team CASCADE）/ → timetable_terms（fk_tm_term RESTRICT 同一ドメイン）。
--   ・各テーブルの上記6つの SET NULL → users 以外の FK 全て。

-- ===== shared_file_versions（shared ドメイン）=====
-- fk_file_versions_uploaded: uploaded_by → users (SET NULL) クロスドメイン監査列
-- → 撤廃only。退会30日後の users 物理削除でも「誰がファイルをアップロードしたか」の孤児 user_id を保持＝監査履歴温存。
--   冷たい監査列・uploaded_by 先頭の専用 index なし・finder なし → index 追加不要。
ALTER TABLE shared_file_versions DROP FOREIGN KEY fk_file_versions_uploaded;

-- ===== shared_folders（shared ドメイン）=====
-- fk_shared_folders_user: user_id → users (SET NULL) クロスドメイン
-- → 撤廃only。退会30日後の users 物理削除でも「誰が（どの会員として）個人フォルダを所有するか」の孤児 user_id を保持＝所有者証跡温存。
--   user_id 先頭の専用 index なし（team_id/org/tournament の各 index はいずれも user_id 先頭でない）・finder なし → index 追加不要。
ALTER TABLE shared_folders DROP FOREIGN KEY fk_shared_folders_user;

-- fk_shared_folders_created: created_by → users (SET NULL) クロスドメイン監査列
-- → 撤廃only。退会30日後の users 物理削除でも「誰がフォルダを作成したか」の孤児 user_id を保持＝監査履歴温存。
--   冷たい監査列・created_by 先頭の専用 index なし・finder なし → index 追加不要。
ALTER TABLE shared_folders DROP FOREIGN KEY fk_shared_folders_created;

-- ===== schedules（schedule ドメイン）=====
-- fk_sch_created_by: created_by → users (SET NULL) クロスドメイン監査列
-- → 撤廃only。退会30日後の users 物理削除でも「誰が予定を作成したか」の孤児 user_id を保持＝監査履歴温存。
--   冷たい監査列・created_by 先頭の専用 index なし・finder なし → index 追加不要。
ALTER TABLE schedules DROP FOREIGN KEY fk_sch_created_by;

-- ===== timetables（timetable ドメイン）=====
-- fk_tm_created_by: created_by → users (SET NULL) クロスドメイン監査列
-- → 撤廃only。退会30日後の users 物理削除でも「誰が時間割を作成したか」の孤児 user_id を保持＝監査履歴温存。
--   冷たい監査列・created_by 先頭の専用 index なし・finder なし → index 追加不要。
ALTER TABLE timetables DROP FOREIGN KEY fk_tm_created_by;

-- ===== team_templates（team_templates）=====
-- fk_team_templates_created_by: created_by → users (SET NULL) クロスドメイン監査列
-- → 撤廃only。退会30日後の users 物理削除でも「誰がチームテンプレートを作成したか」の孤児 user_id を保持＝監査履歴温存。
--   冷たい監査列・created_by 先頭の専用 index なし・finder なし → index 追加不要。
ALTER TABLE team_templates DROP FOREIGN KEY fk_team_templates_created_by;
