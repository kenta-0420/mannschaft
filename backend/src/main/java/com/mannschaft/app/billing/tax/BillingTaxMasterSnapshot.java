package com.mannschaft.app.billing.tax;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * band の {@code tax_master_snapshot}（JSON）の組み立てと読み出し（決定7・決定8）。
 *
 * <p>snapshot は revision create 時に band の {@code effectiveFrom} 時点で解決した税コードマスタ行を
 * 固定したもので、内部 {@code code}・表示名・税率に加えて <b>Stripe 側税コード {@code stripeTaxCode}</b>
 * を保持する。Provision / reconcile は税コードマスタを引き直さず、この snapshot の
 * {@code stripeTaxCode} を Stripe Product の {@code tax_code}（および Product 解決キー）として使う
 * （決定8: Provision 時の再導出は行わない。マスタの {@code stripe_tax_code} が後から更新されても
 * 作成済み revision の税分類は変わらない）。</p>
 *
 * <p>根治治療（2026-09-24・Codex 検分 P1）: 以前は snapshot に {@code stripeTaxCode} を持たず、
 * Provision が内部 {@code code}（{@code JP_STANDARD_10} 等）をそのまま Stripe の {@code tax_code}
 * に渡していた（決定7違反。Stripe は {@code txcd_...} 以外を受け付けない）。</p>
 */
public final class BillingTaxMasterSnapshot {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STRIPE_TAX_CODE = "stripeTaxCode";

    private BillingTaxMasterSnapshot() {
    }

    /** 税コードマスタ行から snapshot JSON を組み立てる（文字列連結ではなく Jackson でエスケープする）。 */
    public static String of(BillingTaxCodeView taxCode) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("code", taxCode.code());
        snapshot.put("displayName", taxCode.displayName());
        snapshot.put("rateBasisPoints", taxCode.rateBasisPoints());
        snapshot.put(STRIPE_TAX_CODE, normalize(taxCode.stripeTaxCode()));
        try {
            return MAPPER.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("税コード snapshot の JSON 化に失敗しました: " + taxCode.code(), e);
        }
    }

    /**
     * snapshot から Stripe 側税コードを読み出す。未設定（キー無し・null・空白）は {@code null}
     * （AC-85: Product に {@code tax_code} を設定しない）。
     *
     * @throws IllegalStateException snapshot が JSON として壊れている場合（握りつぶさない）
     */
    public static String stripeTaxCodeOf(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            throw new IllegalStateException("tax_master_snapshot が空です");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(snapshotJson);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("tax_master_snapshot が JSON として不正です", e);
        }
        JsonNode node = root.get(STRIPE_TAX_CODE);
        if (node == null || node.isNull()) {
            return null;
        }
        return normalize(node.asText());
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
