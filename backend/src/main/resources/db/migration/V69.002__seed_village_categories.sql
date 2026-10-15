-- 村カテゴリ初期データ投入
-- ルート10件 + 子4件（スポーツ・エンタメ配下各2件）
-- UUID_TO_BIN(UUID()) を使い発番（Hibernate の UUIDv7 スタイルと同じく BINARY(16) 格納）

-- ルートカテゴリ
SET @cat_sports    = UUID_TO_BIN(UUID());
SET @cat_hobby     = UUID_TO_BIN(UUID());
SET @cat_learning  = UUID_TO_BIN(UUID());
SET @cat_tech      = UUID_TO_BIN(UUID());
SET @cat_music     = UUID_TO_BIN(UUID());
SET @cat_food      = UUID_TO_BIN(UUID());
SET @cat_local     = UUID_TO_BIN(UUID());
SET @cat_business  = UUID_TO_BIN(UUID());
SET @cat_travel    = UUID_TO_BIN(UUID());
SET @cat_lifestyle = UUID_TO_BIN(UUID());

INSERT INTO village_categories (id, name, parent_id, display_order, created_at, updated_at)
VALUES
    (@cat_sports,    'スポーツ・フィットネス', NULL, 10,  NOW(6), NOW(6)),
    (@cat_hobby,     '趣味・エンタメ',         NULL, 20,  NOW(6), NOW(6)),
    (@cat_learning,  '学習・教育',             NULL, 30,  NOW(6), NOW(6)),
    (@cat_tech,      'テクノロジー・IT',        NULL, 40,  NOW(6), NOW(6)),
    (@cat_music,     '音楽・アート',           NULL, 50,  NOW(6), NOW(6)),
    (@cat_food,      '食・グルメ',             NULL, 60,  NOW(6), NOW(6)),
    (@cat_local,     '地域・コミュニティ',     NULL, 70,  NOW(6), NOW(6)),
    (@cat_business,  'ビジネス・仕事',         NULL, 80,  NOW(6), NOW(6)),
    (@cat_travel,    '旅行・アウトドア',       NULL, 90,  NOW(6), NOW(6)),
    (@cat_lifestyle, 'ライフスタイル・健康',   NULL, 100, NOW(6), NOW(6));

-- 子カテゴリ（スポーツ・フィットネス配下）
INSERT INTO village_categories (id, name, parent_id, display_order, created_at, updated_at)
VALUES
    (UUID_TO_BIN(UUID()), 'サッカー',    @cat_sports, 10, NOW(6), NOW(6)),
    (UUID_TO_BIN(UUID()), '野球',        @cat_sports, 20, NOW(6), NOW(6));

-- 子カテゴリ（趣味・エンタメ配下）
INSERT INTO village_categories (id, name, parent_id, display_order, created_at, updated_at)
VALUES
    (UUID_TO_BIN(UUID()), 'ゲーム',          @cat_hobby, 10, NOW(6), NOW(6)),
    (UUID_TO_BIN(UUID()), 'アニメ・マンガ',  @cat_hobby, 20, NOW(6), NOW(6));
