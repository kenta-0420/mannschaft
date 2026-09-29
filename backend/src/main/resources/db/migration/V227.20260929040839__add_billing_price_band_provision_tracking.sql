-- =====================================================================
-- 価格改定戦役（price-revisions）2本目: band別 provision_attempts・updated_at 追加
-- =====================================================================
-- 正本: .claude/campaigns/price-rev-plan-v3.md 決定5
--
-- billing_price_versions は既に provision_attempts を持つ（V196）が band 単位の
-- リトライ回数を追えないため billing_price_band_versions にも同カラムを追加する。
-- 両テーブルとも updated_at が無く滞留走査（放置中の PROVISIONING/PROVISION_FAILED band の検出）が
-- 組めないため合わせて追加する。
-- =====================================================================

ALTER TABLE billing_price_band_versions
    ADD COLUMN provision_attempts INT NOT NULL DEFAULT 0 AFTER provision_error_code,
    ADD COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) AFTER created_at;

ALTER TABLE billing_price_versions
    ADD COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) AFTER created_at;

-- 単一 future 予約制限（マスター裁可・第6版）: 同一 (product_kind, product_key, scope_kind) につき
-- future（DRAFT/PROVISIONING/PROVISION_FAILED/READY/SCHEDULED）の行は同時に1本まで。
-- PROVISIONING/PROVISION_FAILED は DRAFT→READY の途中状態で retry/reconcile により READY へ戻りうるため
-- future として数える（御裁可 2026-09-24）。半開区間の日時重なりではなく状態そのものを
-- キーにするため、生成列 + UNIQUE KEY で表現する（MySQL は NULL を複数許容するため
-- ACTIVE/RETIRED 行は制約の対象外になる）。
ALTER TABLE billing_price_versions
    ADD COLUMN future_reservation_key VARCHAR(200)
        GENERATED ALWAYS AS (
            CASE WHEN status IN ('DRAFT', 'PROVISIONING', 'PROVISION_FAILED', 'READY', 'SCHEDULED')
                 THEN CONCAT(product_kind, '|', product_key, '|', scope_kind)
                 ELSE NULL END
        ) STORED,
    ADD UNIQUE KEY uk_bpv_single_future (future_reservation_key);

-- 取り消し（CANCELLED・2026-09-24 御裁可）: DRAFT/READY/PROVISION_FAILED の revision を取り消して future 枠を
-- 解放する終端状態。上の生成列 future_reservation_key の対象（5状態）には含めない＝枠を占有しない。
-- V196 の状態 CHECK（chk_bpv_status / chk_bpbv_status）を CANCELLED 込みで張り直す。
ALTER TABLE billing_price_versions
    DROP CHECK chk_bpv_status,
    ADD CONSTRAINT chk_bpv_status CHECK (
        status IN ('DRAFT', 'PROVISIONING', 'PROVISION_FAILED', 'READY', 'SCHEDULED', 'ACTIVE', 'RETIRED', 'CANCELLED')
    );

ALTER TABLE billing_price_band_versions
    DROP CHECK chk_bpbv_status,
    ADD CONSTRAINT chk_bpbv_status CHECK (
        status IN ('DRAFT', 'PROVISIONING', 'PROVISION_FAILED', 'READY', 'SCHEDULED', 'ACTIVE', 'RETIRED', 'CANCELLED')
    );
