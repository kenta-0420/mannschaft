-- Phase 3-B: クロスドメインFK撤廃 第三陣B — reservation / shift ドメインの SET NULL 監査FK6件を撤廃（撤廃only）
--
-- 1000万ユーザー耐久DB再構築 クロスドメインFK撤廃キャンペーン Phase 3-B。
-- CLAUDE.md §1「クロスドメインFKは作らない」/ §2「CASCADE/SET NULL のクロスドメイン削除連鎖は禁止」
-- 原則に従い、reservation / shift ドメインの「users を親とする ON DELETE SET NULL の監査/操作者カラム」FK を撤廃する。
--
-- ━━━ 対象一覧（6件・すべて user 親 ON DELETE SET NULL のクロスドメインFK・すべて明示名）━━━
--  reservation ドメイン:
--   1. reservation_blocked_times / fk_reservation_bt_created_by        (created_by           → users SET NULL)
--   2. reservation_lines         / fk_reservation_lines_default_staff  (default_staff_user_id → users SET NULL)
--   3. reservation_slots         / fk_reservation_slots_staff          (staff_user_id        → users SET NULL)
--   4. reservation_slots         / fk_reservation_slots_created_by     (created_by           → users SET NULL)
--  shift ドメイン:
--   5. shift_schedules           / fk_ss_created_by                    (created_by           → users SET NULL)
--   6. shift_schedules           / fk_ss_published_by                  (published_by         → users SET NULL)
--
-- ━━━ なぜ「撤廃only・孤児 user_id 保持」が安全か（監査履歴温存・マスター御裁可）━━━
--
-- これら6件はいずれも「誰がブロック時間を作ったか / どのスタッフを既定担当にしたか / 誰がスロットを作成・担当するか /
-- 誰がシフト表を作成・公開したか」を表す監査・操作者カラムである。運用証跡として保持価値があり、
-- 退会後も「操作者の id（孤児値）」を残したい。
--
-- 退会フローは2段階モデル（CLAUDE.md「PII 消去のタイミング §13.12」）:
--   ・退会受付直後: user 本体を匿名化（UserEntity.anonymize()）するが行は残す。
--   ・退会受付から最大30日後: AccountPurgeService.purgeUser → userRepository.delete で
--     はじめて users 行を物理削除する。
--
-- すなわち ON DELETE SET NULL が発火しうるのは「30日後の物理削除」のときだけだが、
-- 現状のままだと物理削除の瞬間に上記の監査列が NULL に書き換えられ、「誰が作成/公開/担当したか」
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
--   ・created_by / default_staff_user_id / published_by はいずれも「冷たい監査列」で、当該列を先頭に持つ
--     非FK index は存在せず、当該列で逆引きする repository finder も存在しない（実クエリされる証跡なし）。
--     FK の暗黙バッキング index のみであり、撤廃で消えても影響なし。
--   ・reservation_slots.staff_user_id（fk_reservation_slots_staff）だけはやや使われうる列だが、
--     INDEX idx_reservation_slots_staff_date (staff_user_id, slot_date) が staff_user_id を先頭に持つため、
--     FK 撤廃後も staff_user_id で引ける（この名前付き index は FK 撤廃後も残存） → index 追加不要。
--
-- ━━━ 対象外（本 migration では一切触らない）━━━
--   ・各テーブルの team_id → teams FK は既に撤廃済（reservation 系 = V95.001 / shift_schedules = V62.004）。
--   ・RESTRICT 系: fk_reservations_user / fk_reservation_slots_parent（自己参照 reservation_slots）等。
--   ・他ドメイン・同一ドメインの FK 全て。

-- ===== reservation_blocked_times（reservation ドメイン）=====
-- fk_reservation_bt_created_by: created_by → users (SET NULL) クロスドメイン監査列
-- → 撤廃only。退会30日後の users 物理削除でも「誰がブロック時間を作成したか」の孤児 user_id を保持＝監査履歴温存。
--   冷たい監査列・created_by 先頭の専用 index なし・finder なし → index 追加不要。
ALTER TABLE reservation_blocked_times DROP FOREIGN KEY fk_reservation_bt_created_by;

-- ===== reservation_lines（reservation ドメイン）=====
-- fk_reservation_lines_default_staff: default_staff_user_id → users (SET NULL) クロスドメイン監査列
-- → 撤廃only。退会30日後の users 物理削除でも「どのスタッフを既定担当にしていたか」の孤児 user_id を保持＝設定証跡温存。
--   冷たい監査列・default_staff_user_id 先頭の専用 index なし・finder なし → index 追加不要。
ALTER TABLE reservation_lines DROP FOREIGN KEY fk_reservation_lines_default_staff;

-- ===== reservation_slots（reservation ドメイン）=====
-- fk_reservation_slots_staff: staff_user_id → users (SET NULL) クロスドメイン
-- → 撤廃only。退会30日後の users 物理削除でも「どのスタッフが担当したスロットか」の孤児 user_id を保持＝担当証跡温存。
--   staff_user_id は idx_reservation_slots_staff_date (staff_user_id, slot_date) でカバー済（FK 撤廃後も残存）→ index 追加不要。
ALTER TABLE reservation_slots DROP FOREIGN KEY fk_reservation_slots_staff;

-- fk_reservation_slots_created_by: created_by → users (SET NULL) クロスドメイン監査列
-- → 撤廃only。退会30日後の users 物理削除でも「誰がスロットを作成したか」の孤児 user_id を保持＝監査履歴温存。
--   冷たい監査列・created_by 先頭の専用 index なし・finder なし → index 追加不要。
ALTER TABLE reservation_slots DROP FOREIGN KEY fk_reservation_slots_created_by;

-- ===== shift_schedules（shift ドメイン）=====
-- fk_ss_created_by: created_by → users (SET NULL) クロスドメイン監査列
-- → 撤廃only。退会30日後の users 物理削除でも「誰がシフト表を作成したか」の孤児 user_id を保持＝監査履歴温存。
--   冷たい監査列・created_by 先頭の専用 index なし・finder なし → index 追加不要。
ALTER TABLE shift_schedules DROP FOREIGN KEY fk_ss_created_by;

-- fk_ss_published_by: published_by → users (SET NULL) クロスドメイン監査列
-- → 撤廃only。退会30日後の users 物理削除でも「誰がシフト表を公開したか」の孤児 user_id を保持＝公開操作者証跡温存。
--   冷たい監査列・published_by 先頭の専用 index なし・finder なし → index 追加不要。
ALTER TABLE shift_schedules DROP FOREIGN KEY fk_ss_published_by;
