-- Phase 3-C: クロスドメインFK撤廃 第三陣C — event / cms（blog）ドメインの SET NULL 監査FK7件を撤廃（撤廃only）
--
-- 1000万ユーザー耐久DB再構築 クロスドメインFK撤廃キャンペーン Phase 3-C。
-- CLAUDE.md §1「クロスドメインFKは作らない」/ §2「CASCADE/SET NULL のクロスドメイン削除連鎖は禁止」
-- 原則に従い、event / cms（blog）ドメインの「users を親とする ON DELETE SET NULL の監査/操作者カラム」FK を撤廃する。
--
-- ━━━ 対象一覧（7件・すべて user 親 ON DELETE SET NULL のクロスドメインFK・すべて明示名）━━━
--  event ドメイン（5件）:
--   1. event_checkins             / fk_event_checkins_checked_by             (checked_in_by → users SET NULL)
--   2. event_guest_invite_tokens  / fk_event_guest_invite_tokens_created_by  (created_by    → users SET NULL)
--   3. event_registrations        / fk_event_registrations_approved_by       (approved_by   → users SET NULL)
--   4. event_registrations        / fk_event_registrations_user              (user_id       → users SET NULL)
--   5. events                     / fk_events_created_by                     (created_by    → users SET NULL)
--  cms（blog）ドメイン（2件）:
--   6. blog_post_series           / fk_bps_created_by                        (created_by    → users SET NULL)
--   7. blog_posts                 / fk_bp_author                             (author_id     → users SET NULL)
--
-- ━━━ なぜ「撤廃only・孤児 user_id 保持」が安全か（監査履歴温存・マスター御裁可）━━━
--
-- これら7件はいずれも「誰がチェックインしたか / 誰が招待トークンを作成したか / 誰が参加を承認したか /
-- 誰が（どの会員として）参加登録したか / 誰がイベントを作成したか / 誰が連載シリーズを作ったか /
-- 誰が記事を書いたか（著者）」を表す監査・操作者カラムである。運用証跡として保持価値があり、
-- 退会後も「操作者の id（孤児値）」を残したい。
--
-- 退会フローは2段階モデル（CLAUDE.md「PII 消去のタイミング §13.12」）:
--   ・退会受付直後: user 本体を匿名化（UserEntity.anonymize()）するが行は残す。
--   ・退会受付から最大30日後: AccountPurgeService.purgeUser → userRepository.delete で
--     はじめて users 行を物理削除する。
--
-- すなわち ON DELETE SET NULL が発火しうるのは「30日後の物理削除」のときだけだが、
-- 現状のままだと物理削除の瞬間に上記の監査列が NULL に書き換えられ、「誰が作成/承認/登録/著作したか」
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
-- 結論: 7件とも CREATE INDEX 追加は不要。
--   ・checked_in_by / created_by（token・events・blog_post_series）/ approved_by はいずれも「冷たい監査列」で、
--     当該列を先頭に持つ非FK index は存在せず、当該列で逆引きする repository finder も存在しない
--     （実クエリされる証跡なし）。FK の暗黙バッキング index のみであり、撤廃で消えても影響なし。
--   ・event_registrations.user_id（fk_event_registrations_user）は逆引きされうる列だが、
--     UNIQUE KEY uq_er_user_event (user_id, event_id) と INDEX idx_er_user_event (user_id, event_id) が
--     いずれも user_id を先頭に持つため、FK 撤廃後も user_id で引ける（両 index は FK 撤廃後も残存）→ index 追加不要。
--   ・blog_posts.author_id（fk_bp_author）は INDEX idx_bp_author (author_id) が author_id を先頭に持つため、
--     FK 撤廃後も author_id で引ける（この名前付き index は FK 撤廃後も残存）→ index 追加不要。
--
-- ━━━ 対象外（本 migration では一切触らない）━━━
--   ・events → schedules（fk_events_schedule RESTRICT）。
--   ・events → surveys（fk_events_pre_survey SET NULL）= 親が他ドメインテーブル参照のため第四陣群2の範疇。
--   ・blog_posts → visibility_templates（fk_blog_posts_vt SET NULL）/ organizations / teams 等。
--   ・各テーブルの team_id → teams / organization_id → organizations FK は既に撤廃済（V62.004 等）。
--   ・event_registrations → events（CASCADE）/ → event_ticket_types（RESTRICT）等、同一ドメイン・他参照の FK 全て。

-- ===== event_checkins（event ドメイン）=====
-- fk_event_checkins_checked_by: checked_in_by → users (SET NULL) クロスドメイン監査列
-- → 撤廃only。退会30日後の users 物理削除でも「誰がチェックインを記録したか」の孤児 user_id を保持＝監査履歴温存。
--   冷たい監査列・checked_in_by 先頭の専用 index なし・finder なし → index 追加不要。
ALTER TABLE event_checkins DROP FOREIGN KEY fk_event_checkins_checked_by;

-- ===== event_guest_invite_tokens（event ドメイン）=====
-- fk_event_guest_invite_tokens_created_by: created_by → users (SET NULL) クロスドメイン監査列
-- → 撤廃only。退会30日後の users 物理削除でも「誰が招待トークンを作成したか」の孤児 user_id を保持＝監査履歴温存。
--   冷たい監査列・created_by 先頭の専用 index なし・finder なし → index 追加不要。
ALTER TABLE event_guest_invite_tokens DROP FOREIGN KEY fk_event_guest_invite_tokens_created_by;

-- ===== event_registrations（event ドメイン）=====
-- fk_event_registrations_approved_by: approved_by → users (SET NULL) クロスドメイン監査列
-- → 撤廃only。退会30日後の users 物理削除でも「誰が参加を承認したか」の孤児 user_id を保持＝承認操作者証跡温存。
--   冷たい監査列・approved_by 先頭の専用 index なし・finder なし → index 追加不要。
ALTER TABLE event_registrations DROP FOREIGN KEY fk_event_registrations_approved_by;

-- fk_event_registrations_user: user_id → users (SET NULL) クロスドメイン
-- → 撤廃only。退会30日後の users 物理削除でも「誰が（どの会員として）参加登録したか」の孤児 user_id を保持＝登録者証跡温存。
--   user_id は uq_er_user_event (user_id, event_id) / idx_er_user_event (user_id, event_id) でカバー済（FK 撤廃後も残存）→ index 追加不要。
ALTER TABLE event_registrations DROP FOREIGN KEY fk_event_registrations_user;

-- ===== events（event ドメイン）=====
-- fk_events_created_by: created_by → users (SET NULL) クロスドメイン監査列
-- → 撤廃only。退会30日後の users 物理削除でも「誰がイベントを作成したか」の孤児 user_id を保持＝監査履歴温存。
--   冷たい監査列・created_by 先頭の専用 index なし・finder なし → index 追加不要。
ALTER TABLE events DROP FOREIGN KEY fk_events_created_by;

-- ===== blog_post_series（cms ドメイン）=====
-- fk_bps_created_by: created_by → users (SET NULL) クロスドメイン監査列
-- → 撤廃only。退会30日後の users 物理削除でも「誰が連載シリーズを作成したか」の孤児 user_id を保持＝監査履歴温存。
--   冷たい監査列・created_by 先頭の専用 index なし・finder なし → index 追加不要。
ALTER TABLE blog_post_series DROP FOREIGN KEY fk_bps_created_by;

-- ===== blog_posts（cms ドメイン）=====
-- fk_bp_author: author_id → users (SET NULL) クロスドメイン
-- → 撤廃only。退会30日後の users 物理削除でも「誰が記事を書いたか（著者）」の孤児 user_id を保持＝著者証跡温存。
--   author_id は idx_bp_author (author_id) でカバー済（FK 撤廃後も残存）→ index 追加不要。
ALTER TABLE blog_posts DROP FOREIGN KEY fk_bp_author;
