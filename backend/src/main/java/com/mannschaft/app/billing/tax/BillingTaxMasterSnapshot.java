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
     * snapshot から Stripe 側税コードを読み出す。キーはあるが値が null・空白なら {@code null}
     * （マスタに Stripe 用コードが無い＝AC-85: Product に {@code tax_code} を設定しない）。
     *
     * <p><b>キー自体が欠落している旧形式</b>（2026-09-24 の修正前に作られた band）は「未設定」と区別できない
     * ため null を返さず例外にする（fail-closed。黙って無課税の Product を作らない）。呼び出し元は先に
     * {@link #isLegacyFormat} で判定し、band を PROVISION_FAILED＋{@code TAX_SNAPSHOT_LEGACY_FORMAT} にする。</p>
     *
     * @throws IllegalStateException snapshot が JSON として壊れている・旧形式の場合（握りつぶさない）
     */
    public static String stripeTaxCodeOf(String snapshotJson) {
        JsonNode root = parse(snapshotJson);
        if (!root.has(STRIPE_TAX_CODE)) {
            throw new IllegalStateException("tax_master_snapshot が旧形式です（stripeTaxCode キーが無い）");
        }
        JsonNode node = root.get(STRIPE_TAX_CODE);
        if (node.isNull()) {
            return null;
        }
        return normalize(node.asText());
    }

    /**
     * snapshot が stripeTaxCode キーを持たない旧形式か（修正前に作られた band）。決定8によりマスタから
     * 再導出はしないため、該当 revision は取り消して（{@code POST /price-revisions/{id}/cancel}）作り直す。
     */
    public static boolean isLegacyFormat(String snapshotJson) {
        return !parse(snapshotJson).has(STRIPE_TAX_CODE);
    }

    private static JsonNode parse(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            throw new IllegalStateException("tax_master_snapshot が空です");
        }
        try {
            return MAPPER.readTree(snapshotJson);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("tax_master_snapshot が JSON として不正です", e);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
