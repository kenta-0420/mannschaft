-- V155.20260717114354__seed_module_definitions_wave1.sql
-- モジュールカタログ登録 Wave1（8モジュール・全OPTIONAL・requires_paid_plan=0・trial_days=14）
-- 方針: (a)遅延結線 = 定義+level+template_modules の行追加のみ。nav/enable判定へは結線しない。
-- 既存最大 module_number = 49（village / V9.135）。Wave1 は 50,51,52,53,54,55,57,68 を確保
-- （56 と 58-67 は Wave2 予約のため意図的に空ける。module_number は UNIQUE 制約なし）。
--
-- 採番注記: 軍議指示は V154 だったが、その後 origin/main に
-- V154.20260717011838__village_newsletter_issues_and_tags.sql（村ニュースレター / PR #2331）が
-- マージされ major=154 を先取した。CLAUDE.md の Flyway 採番規則（major = origin/main 全体の最大 major + 1）
-- および「1 major = 1機能バッチ」の慣習に従い、本 Wave1 は独自 major=155 を確保する。

INSERT INTO module_definitions
    (name, slug, description, module_type, module_number, requires_paid_plan, trial_days, is_active, created_at, updated_at)
VALUES
    ('大会・トーナメント', 'tournament',       '大会・トーナメントの運営管理',       'OPTIONAL', 50, 0, 14, 1, NOW(), NOW()),
    ('イベント',           'event',            'イベントの企画・運営・参加管理',     'OPTIONAL', 51, 0, 14, 1, NOW(), NOW()),
    ('予算管理',           'budget',           '予算計画・執行・実績管理',           'OPTIONAL', 52, 0, 14, 1, NOW(), NOW()),
    ('出欠・学校管理',     'school_attendance','出欠・学校生活の管理',               'OPTIONAL', 53, 0, 14, 1, NOW(), NOW()),
    ('時間割',             'timetable',        '時間割の作成・管理',                 'OPTIONAL', 54, 0, 14, 1, NOW(), NOW()),
    ('募集・応募',         'recruitment',      'メンバー・参加者の募集と応募管理',   'OPTIONAL', 55, 0, 14, 1, NOW(), NOW()),
    ('委員会',             'committee',        '委員会・役員会の運営管理',           'OPTIONAL', 57, 0, 14, 1, NOW(), NOW()),
    ('帳票・申請フォーム', 'form',             '帳票・申請フォームの作成と受付',     'OPTIONAL', 68, 0, 14, 1, NOW(), NOW());

INSERT INTO module_level_availability (module_id, level, is_available, note, created_at, updated_at)
SELECT md.id, v.level, v.is_available, NULL, NOW(), NOW()
FROM module_definitions md
JOIN (
    SELECT 'tournament'        AS slug, 'ORGANIZATION' AS level, 1 AS is_available
    UNION ALL SELECT 'tournament',        'TEAM',         1
    UNION ALL SELECT 'tournament',        'PERSONAL',     0
    UNION ALL SELECT 'event',             'ORGANIZATION', 1
    UNION ALL SELECT 'event',             'TEAM',         1
    UNION ALL SELECT 'event',             'PERSONAL',     0
    UNION ALL SELECT 'budget',            'ORGANIZATION', 1
    UNION ALL SELECT 'budget',            'TEAM',         1
    UNION ALL SELECT 'budget',            'PERSONAL',     0
    UNION ALL SELECT 'school_attendance', 'ORGANIZATION', 0
    UNION ALL SELECT 'school_attendance', 'TEAM',         1
    UNION ALL SELECT 'school_attendance', 'PERSONAL',     1
    UNION ALL SELECT 'timetable',         'ORGANIZATION', 1
    UNION ALL SELECT 'timetable',         'TEAM',         1
    UNION ALL SELECT 'timetable',         'PERSONAL',     1
    UNION ALL SELECT 'recruitment',       'ORGANIZATION', 1
    UNION ALL SELECT 'recruitment',       'TEAM',         1
    UNION ALL SELECT 'recruitment',       'PERSONAL',     1
    UNION ALL SELECT 'committee',         'ORGANIZATION', 1
    UNION ALL SELECT 'committee',         'TEAM',         0
    UNION ALL SELECT 'committee',         'PERSONAL',     0
    UNION ALL SELECT 'form',              'ORGANIZATION', 1
    UNION ALL SELECT 'form',              'TEAM',         1
    UNION ALL SELECT 'form',              'PERSONAL',     0
) v ON v.slug = md.slug;

INSERT INTO template_modules (template_id, module_id, created_at)
SELECT t.id, m.id, NOW()
FROM (
    SELECT 'tournament'        AS module_slug, 'sports'       AS template_slug
    UNION ALL SELECT 'event',             'community'
    UNION ALL SELECT 'event',             'sports'
    UNION ALL SELECT 'budget',            'company'
    UNION ALL SELECT 'budget',            'sports'
    UNION ALL SELECT 'school_attendance', 'school'
    UNION ALL SELECT 'timetable',         'school'
    UNION ALL SELECT 'recruitment',       'sports'
    UNION ALL SELECT 'recruitment',       'community'
    UNION ALL SELECT 'committee',         'apartment'
    UNION ALL SELECT 'committee',         'neighborhood'
    UNION ALL SELECT 'committee',         'community'
    UNION ALL SELECT 'form',              'company'
) map
JOIN module_definitions m ON m.slug = map.module_slug
JOIN team_templates     t ON t.slug = map.template_slug;
