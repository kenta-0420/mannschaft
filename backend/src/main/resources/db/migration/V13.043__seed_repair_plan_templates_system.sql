-- F08.8 Phase 1: 修繕周期マスタの SYSTEM seed
-- 国土交通省「マンション修繕積立金ガイドライン（令和5年度改訂）」別紙2 準拠。
-- 全テナント共通の参照データ（scope_type='SYSTEM', scope_id=NULL, organization_id=NULL）。
-- 戸あたり単価は概算（実額はマンション規模・地域・工法で大きく変動するため目安）。

INSERT INTO repair_plan_templates
    (id, organization_id, scope_type, scope_id, category, cycle_years, unit_cost_per_dwelling, source_reference, created_at, updated_at)
VALUES
    (UUID_TO_BIN(UUID()), NULL, 'SYSTEM', NULL, '外壁塗装',            12, 150000, '国交省R5 マンション修繕積立金ガイドライン 別紙2', NOW(), NOW()),
    (UUID_TO_BIN(UUID()), NULL, 'SYSTEM', NULL, '屋上防水',            12, 120000, '国交省R5 マンション修繕積立金ガイドライン 別紙2', NOW(), NOW()),
    (UUID_TO_BIN(UUID()), NULL, 'SYSTEM', NULL, 'シーリング打ち替え',    12, 80000,  '国交省R5 マンション修繕積立金ガイドライン 別紙2', NOW(), NOW()),
    (UUID_TO_BIN(UUID()), NULL, 'SYSTEM', NULL, '鉄部塗装',            6,  40000,  '国交省R5 マンション修繕積立金ガイドライン 別紙2', NOW(), NOW()),
    (UUID_TO_BIN(UUID()), NULL, 'SYSTEM', NULL, '給水管更新',          25, 350000, '国交省R5 マンション修繕積立金ガイドライン 別紙2', NOW(), NOW()),
    (UUID_TO_BIN(UUID()), NULL, 'SYSTEM', NULL, '排水管更新',          25, 300000, '国交省R5 マンション修繕積立金ガイドライン 別紙2', NOW(), NOW()),
    (UUID_TO_BIN(UUID()), NULL, 'SYSTEM', NULL, '受水槽更新',          25, 80000,  '国交省R5 マンション修繕積立金ガイドライン 別紙2', NOW(), NOW()),
    (UUID_TO_BIN(UUID()), NULL, 'SYSTEM', NULL, 'エレベーター更新',     25, 400000, '国交省R5 マンション修繕積立金ガイドライン 別紙2', NOW(), NOW()),
    (UUID_TO_BIN(UUID()), NULL, 'SYSTEM', NULL, '消防設備更新',        20, 60000,  '国交省R5 マンション修繕積立金ガイドライン 別紙2', NOW(), NOW()),
    (UUID_TO_BIN(UUID()), NULL, 'SYSTEM', NULL, '電気設備更新',        25, 120000, '国交省R5 マンション修繕積立金ガイドライン 別紙2', NOW(), NOW()),
    (UUID_TO_BIN(UUID()), NULL, 'SYSTEM', NULL, 'ガス設備更新',        30, 80000,  '国交省R5 マンション修繕積立金ガイドライン 別紙2', NOW(), NOW()),
    (UUID_TO_BIN(UUID()), NULL, 'SYSTEM', NULL, '機械式駐車場更新',     20, 250000, '国交省R5 マンション修繕積立金ガイドライン 別紙2', NOW(), NOW()),
    (UUID_TO_BIN(UUID()), NULL, 'SYSTEM', NULL, 'インターホン更新',     15, 30000,  '国交省R5 マンション修繕積立金ガイドライン 別紙2', NOW(), NOW()),
    (UUID_TO_BIN(UUID()), NULL, 'SYSTEM', NULL, '共用部照明LED化',     10, 25000,  '国交省R5 マンション修繕積立金ガイドライン 別紙2', NOW(), NOW()),
    (UUID_TO_BIN(UUID()), NULL, 'SYSTEM', NULL, '外構・植栽',          15, 35000,  '国交省R5 マンション修繕積立金ガイドライン 別紙2', NOW(), NOW());
