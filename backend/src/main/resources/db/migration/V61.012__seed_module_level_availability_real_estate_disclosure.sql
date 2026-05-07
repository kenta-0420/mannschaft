-- F09.14 重要事項説明書出力 モジュール×レベル別利用可否シードデータ
-- ORGANIZATION のみ利用可（マンション管理組合の重説書）。TEAM/PERSONAL は対象外。
INSERT INTO module_level_availability (module_id, level, is_available, note, created_at, updated_at)
SELECT id, 'ORGANIZATION', 1, 'マンション管理組合での重説書出力（主用途）', NOW(), NOW()
FROM module_definitions WHERE slug = 'real_estate_disclosure';

INSERT INTO module_level_availability (module_id, level, is_available, note, created_at, updated_at)
SELECT id, 'TEAM', 0, '対象外（重説書はマンション全体管理組合の文書）', NOW(), NOW()
FROM module_definitions WHERE slug = 'real_estate_disclosure';

INSERT INTO module_level_availability (module_id, level, is_available, note, created_at, updated_at)
SELECT id, 'PERSONAL', 0, '対象外（個人レベルでは法定書類用途なし）', NOW(), NOW()
FROM module_definitions WHERE slug = 'real_estate_disclosure';
