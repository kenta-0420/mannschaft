-- =====================================================================
-- 価格改定戦役（price-revisions）: chk_bpbv_active の CANCELLED 対応漏れを是正
-- =====================================================================
-- 正本: 実機E2E で検出（取り消しが常に500になる致命的欠陥）
--
-- V227 は billing_price_versions / billing_price_band_versions の状態一覧 CHECK
-- （chk_bpv_status / chk_bpbv_status）に CANCELLED を追加したが、同じく状態を列挙する
-- billing_price_band_versions.chk_bpbv_active（状態と stripe_price_ref の組み合わせ制約）を
-- 張り直し忘れていた。この CHECK は CANCELLED に言及しないため、band を CANCELLED へ更新する
-- UPDATE が必ず SQL Error 3819 (Check constraint 'chk_bpbv_active' is violated) で失敗し、
-- 価格改定の取り消し API が常に 500 を返す欠陥になっていた。
--
-- 取り消し可能な3状態（DRAFT / READY / PROVISION_FAILED）からの遷移後、
-- stripe_price_ref の有無は取り消し前の状態を引き継ぐ（README/設計書 02 参照）:
--   DRAFT / PROVISION_FAILED（stripe_price_ref は NULL）→ CANCELLED でも NULL のまま
--   READY（stripe_price_ref は非NULL）→ CANCELLED でも Price の参照を保持する
-- そのため CANCELLED は NULL・非NULL の両方を許す必要がある。
-- =====================================================================

ALTER TABLE billing_price_band_versions
    DROP CHECK chk_bpbv_active,
    ADD CONSTRAINT chk_bpbv_active CHECK (
        (status IN ('DRAFT', 'PROVISIONING', 'PROVISION_FAILED') AND stripe_price_ref IS NULL)
        OR (status IN ('READY', 'SCHEDULED', 'ACTIVE', 'RETIRED') AND stripe_price_ref IS NOT NULL)
        OR (status = 'CANCELLED')
    );
