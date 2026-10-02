package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.ContractKind;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.PlanPriceBandScopeKind;
import com.mannschaft.app.common.BusinessException;

/**
 * F20.1 API 層の文字列→enum パースヘルパ（設計書 02 §0）。
 *
 * <p>API 表現は {@code "USER" | "TEAM" | "ORG"}・{@code "PLAN" | "ADDON"}。
 * 不正値は {@code ENTITLEMENT_009}（scopeKind・400）/{@code ENTITLEMENT_014}（contractKind・400）で拒否する
 * （症状を隠さず明示エラー）。{@code AccessControlService} の scopeType 文字列は
 * {@code ORG → "ORGANIZATION"} へ綴り変換する（membership ドメインの綴りに合わせる）。</p>
 */
final class BillingApiSupport {

    private BillingApiSupport() {
    }

    /** {@code "USER" | "TEAM" | "ORG"} を {@link EntitlementScopeKind} にパースする（不正は 400）。 */
    static EntitlementScopeKind parseScopeKind(String raw) {
        if (raw == null) {
            throw new BusinessException(EntitlementErrorCode.INVALID_SCOPE_KIND);
        }
        try {
            return EntitlementScopeKind.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(EntitlementErrorCode.INVALID_SCOPE_KIND, ex);
        }
    }

    /** {@code "PLAN" | "ADDON"} を {@link ContractKind} にパースする（不正は 400）。 */
    static ContractKind parseContractKind(String raw) {
        if (raw == null) {
            throw new BusinessException(EntitlementErrorCode.INVALID_CONTRACT_KIND);
        }
        try {
            return ContractKind.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(EntitlementErrorCode.INVALID_CONTRACT_KIND, ex);
        }
    }

    /**
     * {@link EntitlementScopeKind} を {@code AccessControlService} の scopeType 文字列へ変換する。
     * {@code USER} は per-scope ロール判定の対象外（本人固定）のため null を返す。
     */
    static String toAccessScopeType(EntitlementScopeKind scopeKind) {
        return switch (scopeKind) {
            case TEAM -> "TEAM";
            case ORG -> "ORGANIZATION";
            case USER -> null;
        };
    }

    /**
     * {@link EntitlementScopeKind} を {@link PlanPriceBandScopeKind} へ変換する。
     * {@code USER} はバンドを持たないため null を返す（呼び出し側で 400 に倒す）。
     */
    static PlanPriceBandScopeKind toBandScope(EntitlementScopeKind scopeKind) {
        return switch (scopeKind) {
            case TEAM -> PlanPriceBandScopeKind.TEAM;
            case ORG -> PlanPriceBandScopeKind.ORG;
            case USER -> null;
        };
    }
}
