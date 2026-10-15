-- Phase 2-E（第二陣・最終）: クロスドメインFK撤廃 — notifications / 物件問い合わせ /
--                            緊急休業確認 / タイムラインブックマーク の user 親 CASCADE を撤廃
--
-- 1000万ユーザー耐久DB再構築 クロスドメインFK撤廃キャンペーン Phase 2-E（第二陣の最終陣・4テーブル束ね）。
-- CLAUDE.md §1「クロスドメインFKは作らない」/ §2「CASCADE DELETE は同一ドメイン内のみ」原則に従い撤廃。
--
-- ━━━ 対象一覧（4件・すべて user 親 ON DELETE CASCADE のクロスドメインFK・ドメイン別）━━━
--  1. notifications                    / fk_notifications_user (user_id → users CASCADE) … notification ドメイン
--  2. property_listing_inquiries       / fk_pli_user           (user_id → users CASCADE) … resident ドメイン
--  3. emergency_closure_confirmations  / fk_ecc_user           (user_id → users CASCADE) … reservation ドメイン
--  4. timeline_bookmarks               / fk_bookmarks_user     (user_id → users CASCADE) … timeline ドメイン
--
-- ※ 以下のクロスドメイン／同一ドメイン FK は本 migration の対象外（触らない・残す）:
--    ・notifications.fk_notifications_actor (actor_id → users ON DELETE SET NULL)
--        → user CASCADE ではない（SET NULL）。SET NULL クロスドメインは別陣（第三陣）で扱う。
--    ・property_listing_inquiries.fk_pli_listing (listing_id → property_listings CASCADE)
--        → 同一 resident ドメイン内 CASCADE。CLAUDE.md §2 で許可されるため残す。
--    ・emergency_closure_confirmations.fk_ecc_closure (emergency_closure_id → emergency_closures CASCADE)
--        → 同一 reservation ドメイン内 CASCADE。残す。
--    ・timeline_bookmarks.fk_bookmarks_post (timeline_post_id → timeline_posts CASCADE)
--        → 同一 timeline ドメイン内 CASCADE。残す。
--
-- ━━━ なぜ安全か（退会フローでリスナーが先行削除＝CASCADE 冗長化）━━━
--
-- 退会フローは2段階モデル（CLAUDE.md「PII 消去のタイミング §13.12」）:
--   ・退会受付直後: UserAnonymizedEvent 発火（即時匿名化）。
--   ・退会受付から最大30日後: AccountPurgeService.purgeUser → users 物理削除 → AccountPurgedEvent 発火。
--
-- 本 migration と同時に投入する各ドメインの匿名化リスナーが、退会のたびに当該行を
-- 「users 本体削除より前に」先行削除する。よって ON DELETE CASCADE が発火しうる
-- 「30日後の users 物理削除」の時点では既に子行は存在せず、CASCADE は完全に冗長になる。
-- この「リスナー先行削除 → CASCADE 冗長化 → FK 撤廃」は第一陣 notification（preferences/push）・
-- 第二陣 pointcard / search / actionmemo と同一の論法。参照整合性はアプリ層（リスナー）で保証する。
--
-- ━━━ なぜ即時/30日で削除タイミングを分けるか（§13.12 二層削除）━━━
--
--  ・notifications（通知本体）= 退会時【即時削除】（UserAnonymizedEvent 購読）:
--      title / body は宛先ユーザー向けに作られた個人の内容（PII）であり、再設定で復旧する性質でもない。
--      漏洩リスク最小化のため、退会受付直後に即時消去する。
--      実装は新規リスナーを作らず、既存 NotificationAnonymizationEventListener
--      （push_subscriptions / preferences を削除済み）の UserAnonymizedEvent ハンドラに本体削除を追記。
--  ・property_listing_inquiries（物件問い合わせ）= 退会時【即時削除】（UserAnonymizedEvent 購読）:
--      message は自由記述の問い合わせ内容＝個人の発話（PII）。即時消去する。
--  ・emergency_closure_confirmations（緊急休業確認）= 退会時【即時削除】（UserAnonymizedEvent 購読）:
--      予約紐づきの確認トラッキングで appointment_at 等の来院（予約）情報を含む個人データ。即時消去する。
--  ・timeline_bookmarks（タイムラインブックマーク）= 退会30日後の物理削除時【削除】（AccountPurgedEvent 購読）:
--      ブックマーク（お気に入り）はユーザーが意図的に登録した個人「設定」で、退会撤回時に復元価値がある。
--      30日撤回ウィンドウを保持してから削除する。
--
-- ━━━ index 状況（FK 撤廃でバッキングインデックスが消えないか確認。消える場合は CREATE INDEX 追加）━━━
--
-- MySQL は FK 作成時に user_id 用の暗黙 index を自動生成するが、user_id を「先頭」に持つ既存 index が
-- 別途あればそれが backing を兼ね、FK 撤廃後も user_id ルックアップは index で守られる。
-- 4件のうち2件は user_id 先頭の独立 index が存在せず（複合 UNIQUE の2列目）、FK 撤廃で user_id index が
-- 消えてフルスキャン化する → CREATE INDEX を追加する。
--
--  ・notifications.user_id                       : INDEX idx_notifications_user_read_created (user_id, is_read, created_at DESC)
--                                                  ＋ idx_notifications_user_created (user_id, created_at DESC) … 先頭=user_id → 既存 index でカバー済み → 追加不要
--  ・property_listing_inquiries.user_id          : UNIQUE KEY uq_pli_listing_user (listing_id, user_id) のみ … 先頭=listing_id で user_id 非カバー → CREATE INDEX 追加が必要
--  ・emergency_closure_confirmations.user_id     : UNIQUE KEY uk_ecc_closure_user (emergency_closure_id, user_id) のみ … 先頭=emergency_closure_id で user_id 非カバー → CREATE INDEX 追加が必要
--  ・timeline_bookmarks.user_id                  : UNIQUE KEY uk_bookmarks (user_id, timeline_post_id) … 先頭=user_id → 既存 UNIQUE でカバー済み → 追加不要

-- ===== notifications（notification ドメイン）=====
-- fk_notifications_user: user_id → users (CASCADE) クロスドメイン
-- → 撤廃。通知本体は退会即時（UserAnonymizedEvent）で NotificationAnonymizationEventListener が先行削除済み＝CASCADE 冗長。
--   fk_notifications_actor（actor_id → users SET NULL）は対象外（残す）。
-- INDEX idx_notifications_user_read_created / idx_notifications_user_created（先頭=user_id）既存 → index 追加不要
ALTER TABLE notifications DROP FOREIGN KEY fk_notifications_user;

-- ===== property_listing_inquiries（resident ドメイン）=====
-- fk_pli_user: user_id → users (CASCADE) クロスドメイン
-- → 撤廃。問い合わせ message（PII）は退会即時（UserAnonymizedEvent）で ResidentAnonymizationEventListener が先行削除済み＝CASCADE 冗長。
--   fk_pli_listing（listing_id → property_listings CASCADE）は同一 resident ドメイン内のため対象外（残す）。
-- UNIQUE KEY uq_pli_listing_user は (listing_id, user_id) で先頭=listing_id → user_id 非カバー。
-- FK 撤廃で user_id index が消えるため、user_id 先頭の独立 index を新設する。
ALTER TABLE property_listing_inquiries DROP FOREIGN KEY fk_pli_user;
CREATE INDEX idx_pli_user ON property_listing_inquiries (user_id);

-- ===== emergency_closure_confirmations（reservation ドメイン）=====
-- fk_ecc_user: user_id → users (CASCADE) クロスドメイン
-- → 撤廃。緊急休業確認（個人の予約情報）は退会即時（UserAnonymizedEvent）で ReservationAnonymizationEventListener が先行削除済み＝CASCADE 冗長。
--   fk_ecc_closure（emergency_closure_id → emergency_closures CASCADE）は同一 reservation ドメイン内のため対象外（残す）。
-- UNIQUE KEY uk_ecc_closure_user は (emergency_closure_id, user_id) で先頭=emergency_closure_id → user_id 非カバー。
-- FK 撤廃で user_id index が消えるため、user_id 先頭の独立 index を新設する。
ALTER TABLE emergency_closure_confirmations DROP FOREIGN KEY fk_ecc_user;
CREATE INDEX idx_ecc_user ON emergency_closure_confirmations (user_id);

-- ===== timeline_bookmarks（timeline ドメイン）=====
-- fk_bookmarks_user: user_id → users (CASCADE) クロスドメイン
-- → 撤廃。ブックマーク（個人設定・復元価値あり）は退会30日後（AccountPurgedEvent）で TimelineBookmarkAnonymizationEventListener が先行削除済み＝CASCADE 冗長。
--   fk_bookmarks_post（timeline_post_id → timeline_posts CASCADE）は同一 timeline ドメイン内のため対象外（残す）。
-- UNIQUE KEY uk_bookmarks (user_id, timeline_post_id)（先頭=user_id）既存 → index 追加不要
ALTER TABLE timeline_bookmarks DROP FOREIGN KEY fk_bookmarks_user;
