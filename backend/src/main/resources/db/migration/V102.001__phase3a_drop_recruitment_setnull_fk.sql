-- Phase 3-A: クロスドメインFK撤廃 第三陣A — recruitment ドメインの SET NULL 監査FK6件を撤廃（撤廃only）
--
-- 1000万ユーザー耐久DB再構築 クロスドメインFK撤廃キャンペーン Phase 3-A。
-- CLAUDE.md §1「クロスドメインFKは作らない」/ §2「CASCADE/SET NULL のクロスドメイン削除連鎖は禁止」
-- 原則に従い、recruitment ドメインの「users を親とする ON DELETE SET NULL の監査/操作者カラム」FK を撤廃する。
--
-- ━━━ 対象一覧（6件・すべて user 親 ON DELETE SET NULL のクロスドメインFK・すべて明示名）━━━
--  1. recruitment_cancellation_records  / fk_rcr_cancelled_by (cancelled_by → users SET NULL)
--  2. recruitment_cancellation_records  / fk_rcr_user         (user_id      → users SET NULL)
--  3. recruitment_listings              / fk_rl_cancelled_by  (cancelled_by → users SET NULL)
--  4. recruitment_participant_history   / fk_rph_changed_by   (changed_by   → users SET NULL)
--  5. recruitment_participants          / fk_rp_applied_by    (applied_by   → users SET NULL)
--  6. recruitment_participants          / fk_rp_cancelled_by  (cancelled_by → users SET NULL)
--
-- ━━━ なぜ「撤廃only・孤児 user_id 保持」が安全か（監査履歴温存）━━━
--
-- これら6件はいずれも「誰がキャンセルしたか / 誰がステータスを変更したか / 誰が代理申込したか」を表す
-- 監査・操作者カラム（cancelled_by / changed_by / applied_by）と、キャンセル記録本体の user_id である。
-- 料金請求・紛争対応・参加履歴の証跡として保持価値があり、退会後も「操作者の id（孤児値）」を残したい。
--
-- 退会フローは2段階モデル（CLAUDE.md「PII 消去のタイミング §13.12」）:
--   ・退会受付直後: user 本体を匿名化（UserEntity.anonymize()）するが行は残す。
--   ・退会受付から最大30日後: AccountPurgeService.purgeUser → userRepository.delete で
--     はじめて users 行を物理削除する。
--
-- すなわち ON DELETE SET NULL が発火しうるのは「30日後の物理削除」のときだけだが、
-- 現状のままだと物理削除の瞬間に上記の監査列が NULL に書き換えられ、「誰がキャンセル/変更/申込したか」
-- の証跡が失われる。本 migration で SET NULL FK を撤廃すると、users 物理削除後も子行の監査列は
-- 孤児 user_id（もう users には存在しない値）を保持し続け、監査履歴が温存される（＝マスター御裁可の狙い）。
-- 参照整合性はアプリ層で保証する（CLAUDE.md §1）。
--
-- ━━━ SET NULL ゆえ行削除リスクは元々ゼロ（CASCADE との違い）━━━
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
--   ・cancelled_by / changed_by / applied_by はいずれも「冷たい監査列」で、当該列を先頭に持つ
--     非FK index は存在せず、repository finder（findByCancelledBy / findByChangedBy / findByAppliedBy 等）も
--     存在しない（実クエリされる証跡なし）。FK の暗黙バッキング index のみであり、撤廃で消えても影響なし。
--   ・recruitment_cancellation_records.user_id（fk_rcr_user）だけはホット列だが、
--     INDEX idx_rcr_user_history (user_id, cancelled_at) / INDEX idx_rcr_unpaid (user_id, payment_status, deleted_at)
--     が user_id を先頭に持つため、FK 撤廃後も user_id で引ける → index 追加不要。
--
-- ━━━ 対象外（本 migration では一切触らない）━━━
--   ・RESTRICT 系: fk_rl_created_by / fk_rp_user / fk_rp_team / fk_rcr_participant / fk_rcr_listing /
--                  fk_rp_listing / fk_rcr_team（V95.001 で撤廃済）等
--   ・SET NULL だが今回対象外: fk_rl_subcategory / fk_rl_reservation_line / fk_rl_cancellation_policy /
--                  fk_recruitment_listings_vt / fk_rcr_applied_tier 等（users 親ではない or 監査列でない）
--   ・他ドメイン・同一ドメインの FK 全て

-- ===== recruitment_cancellation_records（recruitment ドメイン）=====
-- fk_rcr_cancelled_by: cancelled_by → users (SET NULL) クロスドメイン監査列
-- → 撤廃only。退会30日後の users 物理削除でも「誰がキャンセルしたか」の孤児 user_id を保持＝監査履歴温存。
--   冷たい監査列・専用 index なし・finder なし → index 追加不要。
ALTER TABLE recruitment_cancellation_records DROP FOREIGN KEY fk_rcr_cancelled_by;

-- fk_rcr_user: user_id → users (SET NULL) クロスドメイン
-- → 撤廃only。退会30日後の users 物理削除でもキャンセル記録の user_id を孤児値で保持＝キャンセル統計/証跡温存。
--   user_id はホット列だが idx_rcr_user_history (user_id, ...) / idx_rcr_unpaid (user_id, ...) でカバー済 → index 追加不要。
ALTER TABLE recruitment_cancellation_records DROP FOREIGN KEY fk_rcr_user;

-- ===== recruitment_listings（recruitment ドメイン）=====
-- fk_rl_cancelled_by: cancelled_by → users (SET NULL) クロスドメイン監査列
-- → 撤廃only。退会30日後の users 物理削除でも「誰が募集枠をキャンセルしたか」の孤児 user_id を保持＝監査履歴温存。
--   冷たい監査列・専用 index なし・finder なし → index 追加不要。
ALTER TABLE recruitment_listings DROP FOREIGN KEY fk_rl_cancelled_by;

-- ===== recruitment_participant_history（recruitment ドメイン）=====
-- fk_rph_changed_by: changed_by → users (SET NULL) クロスドメイン監査列
-- → 撤廃only。退会30日後の users 物理削除でも「誰がステータスを変更したか」の孤児 user_id を保持＝遷移履歴の操作者証跡温存。
--   冷たい監査列・専用 index なし・finder なし → index 追加不要。
ALTER TABLE recruitment_participant_history DROP FOREIGN KEY fk_rph_changed_by;

-- ===== recruitment_participants（recruitment ドメイン）=====
-- fk_rp_applied_by: applied_by → users (SET NULL) クロスドメイン監査列
-- → 撤廃only。退会30日後の users 物理削除でも「誰が代理申込したか」の孤児 user_id を保持＝申込の操作者証跡温存。
--   冷たい監査列・専用 index なし・finder なし → index 追加不要。
ALTER TABLE recruitment_participants DROP FOREIGN KEY fk_rp_applied_by;

-- fk_rp_cancelled_by: cancelled_by → users (SET NULL) クロスドメイン監査列
-- → 撤廃only。退会30日後の users 物理削除でも「誰が参加をキャンセルしたか」の孤児 user_id を保持＝監査履歴温存。
--   冷たい監査列・専用 index なし・finder なし → index 追加不要。
ALTER TABLE recruitment_participants DROP FOREIGN KEY fk_rp_cancelled_by;
