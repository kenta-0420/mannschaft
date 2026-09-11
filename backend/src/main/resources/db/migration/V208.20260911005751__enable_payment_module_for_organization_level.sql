-- payment（決済）モジュールを ORGANIZATION レベルでも有効化する。
--
-- 背景: V2.027 のシードは「基本: TEAM=true, ORGANIZATION=false, PERSONAL=false」という
-- 機械的な既定値で全モジュールを投入しており、payment もその既定のまま ORGANIZATION=0 で残っていた。
-- しかし組織の支払い機能は全層で実装済みである（payment_items.organization_id と chk_pi_scope 制約、
-- OrganizationPaymentController / OrganizationPaymentItemController、FE の organizations/[slug]/payments.vue、
-- 設計書 F08.2 の対象レベル「組織 (Organization)」）。
-- ORGANIZATION=0 のままだと ModuleService.toggleOrganizationModule がレベルチェックで TMPL_005 を投げ、
-- 組織 ADMIN が payment を有効化できず、組織サイドバーの領収書導線が永久に表示されない。
--
-- 無料解放: module_definitions.payment は requires_paid_plan=0（V2.024）のため、
-- レベル可否を 1 にするだけで無料機能として解放される（追加の料金設定は不要）。
--
-- 冪等性: 該当行が既に存在する環境では ON DUPLICATE KEY（uq_module_level）で UPDATE、
-- 行が無い環境では INSERT となる。
INSERT INTO module_level_availability (module_id, level, is_available, note, created_at, updated_at)
SELECT md.id, 'ORGANIZATION', 1, '組織の支払い項目・領収書機能（F08.2）で利用', NOW(), NOW()
FROM module_definitions md
WHERE md.slug = 'payment'
ON DUPLICATE KEY UPDATE
    is_available = 1,
    note = '組織の支払い項目・領収書機能（F08.2）で利用',
    updated_at = NOW();
