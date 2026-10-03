-- Phase 2-A: クロスドメインFK撤廃 第二陣A — 統計保持価値のある本体テーブル4件の user 親 CASCADE を撤廃（撤廃only）
--
-- 1000万ユーザー耐久DB再構築 クロスドメインFK撤廃キャンペーン Phase 2-A。
-- CLAUDE.md §1「クロスドメインFKは作らない」/ §2「CASCADE DELETE は同一ドメイン内のみ」原則に従い撤廃。
--
-- ━━━ 対象一覧（4件・すべて user 親 ON DELETE CASCADE のクロスドメインFK）━━━
--  1. blog_posts            / fk_bp_user (user_id → users CASCADE)  ※cms ドメイン
--  2. schedules             / fk_sch_user (user_id → users CASCADE) ※schedule ドメイン
--  3. coupon_distributions  / fk_cd_user (user_id → users CASCADE)  ※promotion ドメイン
--  4. promotion_deliveries  / fk_pd_user (user_id → users CASCADE)  ※promotion ドメイン
--
-- ━━━ なぜ「撤廃only」が安全か（孤児 user_id 保持＝統計温存）━━━
--
-- これら4件は「個人スコープの投稿/予定」「個人宛のクーポン配布/プロモ配信」を表す本体テーブルで、
-- 統計・履歴としての保持価値がある（誰が何件投稿したか・誰に何件配信したか等の集計母数）。
--
-- 退会フローは2段階モデル（CLAUDE.md「PII 消去のタイミング §13.12」）:
--   ・退会受付直後: user 本体を匿名化（UserEntity.anonymize()）するが行は残す。
--   ・退会受付から最大30日後: AccountPurgeService.purgeUser → userRepository.delete で
--     はじめて users 行を物理削除する。
--
-- すなわち ON DELETE CASCADE が発火しうるのは「30日後の物理削除」のときだけだが、
-- 現状のままだと物理削除の瞬間に blog_posts / schedules / coupon_distributions /
-- promotion_deliveries の当該行が連鎖削除され、統計の母数（投稿数・配信数）が破壊される。
--
-- 本 migration で CASCADE FK を撤廃すると、users 物理削除後も子行は生き残り、
-- user_id は「もう users には存在しない孤児値」として保持される。これにより
-- 退会後も投稿/予定/配信の統計が温存される（＝マスター御裁可の狙い）。
-- 参照整合性はアプリ層で保証する（CLAUDE.md §1）。
--
-- ━━━ なぜ NULL 付替（user_id を NULL にする）をしないか ━━━
--
-- 「FK 撤廃 + 孤児値保持」が正解であり、user_id を NULL に付け替えるのは誤り:
--   ・blog_posts には CHECK 制約 chk_bp_scope があり、(team_id / organization_id / user_id) の
--     ちょうど1つだけが非NULLでなければならない。user スコープ行で user_id を NULL にすると
--     全スコープ列が NULL になり CHECK 違反でクラッシュする。
--   ・schedules には CHECK 制約 ck_schedules_scope_xor（V9.086 で 4 カラム XOR に拡張）があり、
--     (team_id / organization_id / user_id / committee_id) のちょうど1つが非NULL必須。
--     user スコープ行で user_id を NULL にすると XOR=0 となり CHECK 違反でクラッシュする。
--   ・coupon_distributions / promotion_deliveries は user_id が NOT NULL（個人宛が本質）であり、
--     そもそも NULL に付け替えられない。
-- したがって本 migration はリスナーもデータ付替も NULL 化も一切行わず、純粋に FK を撤廃するのみ。
--
-- ━━━ index 状況（FK 撤廃後もバッキングインデックスが独立 index として残るか確認）━━━
--
-- 4件とも user_id を先頭カラムに含む既存 index が存在するため、撤廃後も index は残る → CREATE INDEX 追加不要。
--   blog_posts.user_id           : INDEX idx_bp_user_status (user_id, status, published_at) 既存（先頭=user_id）
--   schedules.user_id            : INDEX idx_sch_user_start (user_id, start_at) 既存（先頭=user_id）
--   coupon_distributions.user_id : INDEX idx_cd_user_status (user_id, status) 既存（先頭=user_id）
--   promotion_deliveries.user_id : INDEX idx_pd_user (user_id, created_at DESC) 既存（先頭=user_id）

-- ===== blog_posts（cms ドメイン）=====
-- fk_bp_user: user_id → users (CASCADE) クロスドメイン
-- → 撤廃only。退会30日後の users 物理削除でも個人ブログ/お知らせ行を孤児 user_id で保持＝投稿統計温存。
--   NULL 付替は chk_bp_scope（team/org/user の XOR）違反になるため不可。
-- INDEX idx_bp_user_status (user_id, ...) 既存（先頭=user_id）→ index 追加不要
ALTER TABLE blog_posts DROP FOREIGN KEY fk_bp_user;

-- ===== schedules（schedule ドメイン）=====
-- fk_sch_user: user_id → users (CASCADE) クロスドメイン
-- → 撤廃only。退会30日後の users 物理削除でも個人予定行を孤児 user_id で保持＝予定統計温存。
--   NULL 付替は ck_schedules_scope_xor（team/org/user/committee の XOR=1）違反になるため不可。
-- INDEX idx_sch_user_start (user_id, ...) 既存（先頭=user_id）→ index 追加不要
ALTER TABLE schedules DROP FOREIGN KEY fk_sch_user;

-- ===== coupon_distributions（promotion ドメイン）=====
-- fk_cd_user: user_id → users (CASCADE) クロスドメイン
-- → 撤廃only。退会30日後の users 物理削除でもクーポン配布行を孤児 user_id で保持＝配布統計温存。
--   user_id は NOT NULL（個人宛が本質）ゆえ NULL 付替は不可。
-- INDEX idx_cd_user_status (user_id, ...) 既存（先頭=user_id）→ index 追加不要
ALTER TABLE coupon_distributions DROP FOREIGN KEY fk_cd_user;

-- ===== promotion_deliveries（promotion ドメイン）=====
-- fk_pd_user: user_id → users (CASCADE) クロスドメイン
-- → 撤廃only。退会30日後の users 物理削除でもプロモ配信行を孤児 user_id で保持＝配信統計温存。
--   user_id は NOT NULL（個人宛が本質）ゆえ NULL 付替は不可。
-- INDEX idx_pd_user (user_id, ...) 既存（先頭=user_id）→ index 追加不要
ALTER TABLE promotion_deliveries DROP FOREIGN KEY fk_pd_user;
