-- V156.20260718070942__seed_module_definitions_wave2.sql
-- モジュールカタログ登録 Wave2（13モジュール・全OPTIONAL）
-- 方針: (a)遅延結線 = 定義+level+template_modules の行追加のみ。nav/enable判定へは結線しない。
-- Wave1（V155）で 50-55,57,68 を確保済み。Wave2 は 56,58-67,69,70 を確保し #50-70 を完結。
-- succession(66) のみ requires_paid_plan=1・trial_days=30（他は 0・14）。
-- family(65) は team_templates に 'family' slug が存在しないため template 紐付けなし（memo/contact/social/market も紐付けなし）。
-- 採番: origin/main 最大 major=155 の次として major=156 を確保（CLAUDE.md Flyway採番規則: major = origin/main 全体の最大 major + 1）。

INSERT INTO module_definitions
    (name, slug, description, module_type, module_number, requires_paid_plan, trial_days, is_active, created_at, updated_at)
VALUES
    ('求人・バイトマッチング', 'job_matching', '求人情報の掲載と応募者マッチング',         'OPTIONAL', 56, 0, 14, 1, NOW(), NOW()),
    ('プロモーション・クーポン', 'promotion',   'クーポン・キャンペーンの配信管理',         'OPTIONAL', 58, 0, 14, 1, NOW(), NOW()),
    ('ポイントカード',       'point_card',     'ポイントカードの発行と付与管理',           'OPTIONAL', 59, 0, 14, 1, NOW(), NOW()),
    ('ブログ・CMS',          'blog_cms',       'ブログ記事・コンテンツの作成と公開',       'OPTIONAL', 60, 0, 14, 1, NOW(), NOW()),
    ('メモ',                 'memo',           'メモ・クイックノートの記録',               'OPTIONAL', 61, 0, 14, 1, NOW(), NOW()),
    ('連絡先・問い合わせ',   'contact',        '連絡先管理と問い合わせ受付',               'OPTIONAL', 62, 0, 14, 1, NOW(), NOW()),
    ('ソーシャル',           'social',         'フォロー・タイムライン等のソーシャル機能', 'OPTIONAL', 63, 0, 14, 1, NOW(), NOW()),
    ('ふりかえり',           'reflection',     '活動のふりかえり記録',                     'OPTIONAL', 64, 0, 14, 1, NOW(), NOW()),
    ('家族・世帯',           'family',         '家族・世帯メンバーの管理',                 'OPTIONAL', 65, 0, 14, 1, NOW(), NOW()),
    ('事業承継',             'succession',     '事業承継の計画・手続き管理',               'OPTIONAL', 66, 1, 30, 1, NOW(), NOW()),
    ('マーケット',           'market',         '商品の出品・売買管理',                     'OPTIONAL', 67, 0, 14, 1, NOW(), NOW()),
    ('申請承認ワークフロー', 'workflow',       '汎用の申請・承認ワークフロー',             'OPTIONAL', 69, 0, 14, 1, NOW(), NOW()),
    ('回数券・チケット販売', 'ticket',         '回数券・チケットの販売と利用管理',         'OPTIONAL', 70, 0, 14, 1, NOW(), NOW());

INSERT INTO module_level_availability (module_id, level, is_available, note, created_at, updated_at)
SELECT md.id, v.level, v.is_available, NULL, NOW(), NOW()
FROM module_definitions md
JOIN (
    SELECT 'job_matching'      AS slug, 'ORGANIZATION' AS level, 0 AS is_available
    UNION ALL SELECT 'job_matching',      'TEAM',         1
    UNION ALL SELECT 'job_matching',      'PERSONAL',     1
    UNION ALL SELECT 'promotion',         'ORGANIZATION', 1
    UNION ALL SELECT 'promotion',         'TEAM',         1
    UNION ALL SELECT 'promotion',         'PERSONAL',     0
    UNION ALL SELECT 'point_card',        'ORGANIZATION', 1
    UNION ALL SELECT 'point_card',        'TEAM',         0
    UNION ALL SELECT 'point_card',        'PERSONAL',     1
    UNION ALL SELECT 'blog_cms',          'ORGANIZATION', 0
    UNION ALL SELECT 'blog_cms',          'TEAM',         1
    UNION ALL SELECT 'blog_cms',          'PERSONAL',     1
    UNION ALL SELECT 'memo',              'ORGANIZATION', 0
    UNION ALL SELECT 'memo',              'TEAM',         0
    UNION ALL SELECT 'memo',              'PERSONAL',     1
    UNION ALL SELECT 'contact',           'ORGANIZATION', 0
    UNION ALL SELECT 'contact',           'TEAM',         0
    UNION ALL SELECT 'contact',           'PERSONAL',     1
    UNION ALL SELECT 'social',            'ORGANIZATION', 0
    UNION ALL SELECT 'social',            'TEAM',         0
    UNION ALL SELECT 'social',            'PERSONAL',     1
    UNION ALL SELECT 'reflection',        'ORGANIZATION', 0
    UNION ALL SELECT 'reflection',        'TEAM',         0
    UNION ALL SELECT 'reflection',        'PERSONAL',     1
    UNION ALL SELECT 'family',            'ORGANIZATION', 0
    UNION ALL SELECT 'family',            'TEAM',         1
    UNION ALL SELECT 'family',            'PERSONAL',     0
    UNION ALL SELECT 'succession',        'ORGANIZATION', 1
    UNION ALL SELECT 'succession',        'TEAM',         0
    UNION ALL SELECT 'succession',        'PERSONAL',     0
    UNION ALL SELECT 'market',            'ORGANIZATION', 1
    UNION ALL SELECT 'market',            'TEAM',         1
    UNION ALL SELECT 'market',            'PERSONAL',     0
    UNION ALL SELECT 'workflow',          'ORGANIZATION', 1
    UNION ALL SELECT 'workflow',          'TEAM',         1
    UNION ALL SELECT 'workflow',          'PERSONAL',     0
    UNION ALL SELECT 'ticket',            'ORGANIZATION', 0
    UNION ALL SELECT 'ticket',            'TEAM',         1
    UNION ALL SELECT 'ticket',            'PERSONAL',     1
) v ON v.slug = md.slug;

INSERT INTO template_modules (template_id, module_id, created_at)
SELECT t.id, m.id, NOW()
FROM (
    SELECT 'job_matching'      AS module_slug, 'restaurant'   AS template_slug
    UNION ALL SELECT 'job_matching',      'gym'
    UNION ALL SELECT 'promotion',         'restaurant'
    UNION ALL SELECT 'promotion',         'salon'
    UNION ALL SELECT 'promotion',         'gym'
    UNION ALL SELECT 'point_card',        'restaurant'
    UNION ALL SELECT 'point_card',        'salon'
    UNION ALL SELECT 'point_card',        'gym'
    UNION ALL SELECT 'blog_cms',          'community'
    UNION ALL SELECT 'reflection',        'school'
    UNION ALL SELECT 'succession',        'apartment'
    UNION ALL SELECT 'workflow',          'company'
    UNION ALL SELECT 'workflow',          'apartment'
    UNION ALL SELECT 'ticket',            'gym'
    UNION ALL SELECT 'ticket',            'sports'
) map
JOIN module_definitions m ON m.slug = map.module_slug
JOIN team_templates     t ON t.slug = map.template_slug;
