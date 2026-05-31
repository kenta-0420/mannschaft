-- F22.1 市（Market）Phase 1 実機 E2E 用シードデータ
-- mysql -umannschaft -pmannschaft mannschaft < market-seed.sql
--
-- 固定の高位 ID（90000 番台）を使い、冪等に再投入できる。
-- 市は recruitment_listings の論理ビュー（visibility='PUBLIC' AND status IN (OPEN,FULL)）。
-- created_by には PII を持つ専用ユーザー (90001) を割り当て、公開レスポンスに漏れないことを検証する。

-- ========== 既存シード分を掃除（FK 順: listings → user の順で削除） ==========
DELETE FROM recruitment_listings WHERE id BETWEEN 90001 AND 90099;
DELETE FROM users WHERE id = 90001;

-- ========== PII 検証用ユーザー（公開画面に出てはならない情報を保持） ==========
INSERT INTO users
  (id, email, password_hash, last_name, first_name, display_name,
   handle_searchable, contact_approval_required, online_visibility, is_searchable,
   locale, timezone, status, created_at, updated_at)
VALUES
  (90001, 'market-pii-leak@example.com', NULL,
   '漏洩太郎LastName', '漏洩太郎FirstName', '市PII検証ユーザー',
   1, 1, 'NOBODY', 1, 'ja', 'Asia/Tokyo', 'ACTIVE', NOW(), NOW());

-- 共通の固定値
--   participation_type=INDIVIDUAL / min_capacity=1 / scope_type=TEAM
--   start_at は未来日、application_deadline/auto_cancel_at も未来日

-- L1: PUBLIC × OPEN × 別府市(44202) × cat9(practice_match) × team1
INSERT INTO recruitment_listings
  (id, scope_type, scope_id, category_id, title, participation_type,
   start_at, end_at, application_deadline, auto_cancel_at,
   capacity, min_capacity, confirmed_count, payment_enabled,
   visibility, status, location, prefecture_code, city_code, created_by, created_at, updated_at)
VALUES
  (90001, 'TEAM', 1, 9, 'U-12 練習試合の相手を募集（別府市）', 'INDIVIDUAL',
   '2026-11-03 09:00:00', '2026-11-03 12:00:00', '2026-11-01 23:59:59', '2026-11-01 23:59:59',
   4, 1, 0, 0, 'PUBLIC', 'OPEN', '別府市総合運動公園', '44', '44202', 90001, NOW(), NOW());

-- L2: PUBLIC × OPEN × 大分市(44201) × cat10(tournament) × team2
INSERT INTO recruitment_listings
  (id, scope_type, scope_id, category_id, title, participation_type,
   start_at, end_at, application_deadline, auto_cancel_at,
   capacity, min_capacity, confirmed_count, payment_enabled,
   visibility, status, location, prefecture_code, city_code, created_by, created_at, updated_at)
VALUES
  (90002, 'TEAM', 2, 10, 'フットサル大会 参加チーム募集（大分市）', 'INDIVIDUAL',
   '2026-12-01 10:00:00', '2026-12-01 18:00:00', '2026-11-25 23:59:59', '2026-11-25 23:59:59',
   8, 2, 3, 0, 'PUBLIC', 'OPEN', '大分市営グラウンド', '44', '44201', 90001, NOW(), NOW());

-- L3: PUBLIC × FULL × 大分市(44201) × cat9 × team1（定員到達済み）
INSERT INTO recruitment_listings
  (id, scope_type, scope_id, category_id, title, participation_type,
   start_at, end_at, application_deadline, auto_cancel_at,
   capacity, min_capacity, confirmed_count, payment_enabled,
   visibility, status, location, prefecture_code, city_code, created_by, created_at, updated_at)
VALUES
  (90003, 'TEAM', 1, 9, '満員御礼の練習試合（大分市）', 'INDIVIDUAL',
   '2026-11-10 09:00:00', '2026-11-10 12:00:00', '2026-11-08 23:59:59', '2026-11-08 23:59:59',
   2, 1, 2, 0, 'PUBLIC', 'FULL', '大分スポーツ公園', '44', '44201', 90001, NOW(), NOW());

-- L4: PUBLIC × OPEN × 地域なし(NULL) × cat10 × team2（include_region_none トグル検証）
INSERT INTO recruitment_listings
  (id, scope_type, scope_id, category_id, title, participation_type,
   start_at, end_at, application_deadline, auto_cancel_at,
   capacity, min_capacity, confirmed_count, payment_enabled,
   visibility, status, location, prefecture_code, city_code, created_by, created_at, updated_at)
VALUES
  (90004, 'TEAM', 2, 10, 'オンライン交流会 参加者募集（地域なし）', 'INDIVIDUAL',
   '2026-12-15 19:00:00', '2026-12-15 21:00:00', '2026-12-10 23:59:59', '2026-12-10 23:59:59',
   20, 1, 5, 0, 'PUBLIC', 'OPEN', NULL, NULL, NULL, 90001, NOW(), NOW());

-- L5: FRIEND_TEAMS_ONLY × OPEN × 別府市 → 市には出ない（404 存在秘匿）
INSERT INTO recruitment_listings
  (id, scope_type, scope_id, category_id, title, participation_type,
   start_at, end_at, application_deadline, auto_cancel_at,
   capacity, min_capacity, confirmed_count, payment_enabled,
   visibility, status, location, prefecture_code, city_code, created_by, created_at, updated_at)
VALUES
  (90005, 'TEAM', 1, 9, 'フレンド限定の練習試合（非公開）', 'INDIVIDUAL',
   '2026-11-20 09:00:00', '2026-11-20 12:00:00', '2026-11-18 23:59:59', '2026-11-18 23:59:59',
   4, 1, 0, 0, 'FRIEND_TEAMS_ONLY', 'OPEN', '別府市', '44', '44202', 90001, NOW(), NOW());

-- L6: SCOPE_ONLY × OPEN → 市には出ない（404）
INSERT INTO recruitment_listings
  (id, scope_type, scope_id, category_id, title, participation_type,
   start_at, end_at, application_deadline, auto_cancel_at,
   capacity, min_capacity, confirmed_count, payment_enabled,
   visibility, status, location, prefecture_code, city_code, created_by, created_at, updated_at)
VALUES
  (90006, 'TEAM', 1, 9, 'スコープ限定の募集（非公開）', 'INDIVIDUAL',
   '2026-11-21 09:00:00', '2026-11-21 12:00:00', '2026-11-19 23:59:59', '2026-11-19 23:59:59',
   4, 1, 0, 0, 'SCOPE_ONLY', 'OPEN', '大分市', '44', '44201', 90001, NOW(), NOW());

-- L7: PUBLIC × CANCELLED → 市には出ない（404）
INSERT INTO recruitment_listings
  (id, scope_type, scope_id, category_id, title, participation_type,
   start_at, end_at, application_deadline, auto_cancel_at,
   capacity, min_capacity, confirmed_count, payment_enabled,
   visibility, status, location, prefecture_code, city_code, created_by, created_at, updated_at)
VALUES
  (90007, 'TEAM', 1, 9, 'キャンセル済みの公開募集', 'INDIVIDUAL',
   '2026-11-22 09:00:00', '2026-11-22 12:00:00', '2026-11-20 23:59:59', '2026-11-20 23:59:59',
   4, 1, 0, 0, 'PUBLIC', 'CANCELLED', '別府市', '44', '44202', 90001, NOW(), NOW());

-- L8: PUBLIC × COMPLETED → 市には出ない（404）
INSERT INTO recruitment_listings
  (id, scope_type, scope_id, category_id, title, participation_type,
   start_at, end_at, application_deadline, auto_cancel_at,
   capacity, min_capacity, confirmed_count, payment_enabled,
   visibility, status, location, prefecture_code, city_code, created_by, created_at, updated_at)
VALUES
  (90008, 'TEAM', 1, 9, '完了済みの公開募集', 'INDIVIDUAL',
   '2026-10-01 09:00:00', '2026-10-01 12:00:00', '2026-09-28 23:59:59', '2026-09-28 23:59:59',
   4, 1, 4, 0, 'PUBLIC', 'COMPLETED', '別府市', '44', '44202', 90001, NOW(), NOW());

-- L9: PUBLIC × OPEN × deleted_at セット → 市には出ない（404・論理削除）
INSERT INTO recruitment_listings
  (id, scope_type, scope_id, category_id, title, participation_type,
   start_at, end_at, application_deadline, auto_cancel_at,
   capacity, min_capacity, confirmed_count, payment_enabled,
   visibility, status, location, prefecture_code, city_code, created_by, created_at, updated_at, deleted_at)
VALUES
  (90009, 'TEAM', 1, 9, '論理削除済みの公開募集', 'INDIVIDUAL',
   '2026-11-25 09:00:00', '2026-11-25 12:00:00', '2026-11-23 23:59:59', '2026-11-23 23:59:59',
   4, 1, 0, 0, 'PUBLIC', 'OPEN', '別府市', '44', '44202', 90001, NOW(), NOW(), NOW());

SELECT '=== seeded market listings ===' AS info;
SELECT id, visibility, status, prefecture_code, city_code, deleted_at
FROM recruitment_listings WHERE id BETWEEN 90001 AND 90099 ORDER BY id;
