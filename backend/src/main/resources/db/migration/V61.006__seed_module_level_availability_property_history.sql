-- F09.13 物件履歴台帳 モジュール×レベル別利用可否シードデータ
-- ORGANIZATION/TEAM レベルで利用可、PERSONAL は不可
INSERT INTO module_level_availability (module_id, level, is_available, note, created_at, updated_at)
SELECT id, 'ORGANIZATION', 1, '管理組合・自治会の主用途', NOW(), NOW()
FROM module_definitions WHERE slug = 'property_history';

INSERT INTO module_level_availability (module_id, level, is_available, note, created_at, updated_at)
SELECT id, 'TEAM', 1, '小規模物件・共用施設管理', NOW(), NOW()
FROM module_definitions WHERE slug = 'property_history';

INSERT INTO module_level_availability (module_id, level, is_available, note, created_at, updated_at)
SELECT id, 'PERSONAL', 0, '個人レベル不要', NOW(), NOW()
FROM module_definitions WHERE slug = 'property_history';
