-- ゲーミフィケーション（gamification）モジュールを ORGANIZATION レベルで利用不可にする。
--
-- 背景（CMP-260918-0024）: `module_level_availability` には gamification の行が一切無く、
-- ModuleService.isLevelAvailable は「レコードが無い場合は制約なし＝利用可」とみなすため、
-- ORGANIZATION スコープでもレベルチェックを素通りしていた。
-- しかしゲーミフィケーション（ポイント・バッジ・ランキング）は backend/src/main/java/com/mannschaft/app/gamification/
-- 配下の全コントローラが `/api/v1/teams/{teamId}/gamification/...` のチームスコープ専用で実装されており、
-- 組織スコープの API は存在しない。組織 ADMIN が機能設定画面からトグルを有効化しても実体が無く、
-- サイドバー導線を辿ると 404 になっていた（マスター裁可: ゲーミフィケーションはチーム固有機能とし組織へは広げない）。
--
-- 冪等性: 該当行が既に存在する環境では ON DUPLICATE KEY（uq_module_level）で UPDATE、
-- 行が無い環境では INSERT となる。
INSERT INTO module_level_availability (module_id, level, is_available, note, created_at, updated_at)
SELECT md.id, 'ORGANIZATION', 0, 'ゲーミフィケーションはチーム固有機能のため組織スコープでは利用不可（CMP-260918-0024）', NOW(), NOW()
FROM module_definitions md
WHERE md.slug = 'gamification'
ON DUPLICATE KEY UPDATE
    is_available = 0,
    note = 'ゲーミフィケーションはチーム固有機能のため組織スコープでは利用不可（CMP-260918-0024）',
    updated_at = NOW();
