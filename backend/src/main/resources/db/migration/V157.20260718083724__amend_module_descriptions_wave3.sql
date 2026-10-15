-- V157.20260718083724__amend_module_descriptions_wave3.sql
-- モジュールカタログ Wave3（B群吸収整理）: 既存6モジュールの description 追補。
-- 方針: 新規seed行は作らない。吸収された機能（digest/skill/receipt/proxyvote/residencestatus/faq）を
--       吸収先モジュールの説明に括弧/中黒で追記し、カタログUIでの探索性を高める。
-- CONCAT追記＋NOT LIKE ガードで冪等（二重適用されても増殖しない）。既存値に依存しない安全な更新。
-- 採番: origin/main 最大 major=156 の次として major=157（Flyway採番規則）。

UPDATE module_definitions SET description = CONCAT(description, '（ダイジェスト生成を含む）'), updated_at = NOW()
  WHERE slug = 'timeline' AND description NOT LIKE '%ダイジェスト%';
UPDATE module_definitions SET description = CONCAT(description, '（スキル紹介を含む）'), updated_at = NOW()
  WHERE slug = 'member_intro' AND description NOT LIKE '%スキル紹介%';
UPDATE module_definitions SET description = CONCAT(description, '（領収書発行を含む）'), updated_at = NOW()
  WHERE slug = 'payment' AND description NOT LIKE '%領収書%';
UPDATE module_definitions SET description = CONCAT(description, '（代理投票・委任状を含む）'), updated_at = NOW()
  WHERE slug = 'voting' AND description NOT LIKE '%代理投票%';
UPDATE module_definitions SET description = CONCAT(description, '・平時の居住状況確認'), updated_at = NOW()
  WHERE slug = 'safety_check' AND description NOT LIKE '%平時の居住状況%';
UPDATE module_definitions SET description = CONCAT(description, '・FAQ'), updated_at = NOW()
  WHERE slug = 'knowledge_base' AND description NOT LIKE '%FAQ%';
