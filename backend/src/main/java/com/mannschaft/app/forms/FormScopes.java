package com.mannschaft.app.forms;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;

import java.util.Locale;

/**
 * forms ドメインのスコープ種別正規化ユーティリティ（認可根治戦役 Wave 3 トランシェ B4）。
 *
 * <p>F05.7 の URL は {@code /api/v1/{scopeType}/{scopeId}/...} 形式で、フロントエンドは
 * {@code teams} / {@code organizations}（複数形・小文字。{@code frontend/app/composables/useFormApi.ts}
 * の {@code buildBase} 参照）を渡す。{@code form_templates.scope_type} / {@code form_submissions.scope_type}
 * には受け取った文字列をそのまま保存するため、DB 上は小文字複数形のまま残る。</p>
 *
 * <p>{@link com.mannschaft.app.common.AccessControlService} は
 * {@code ScopeType.valueOf}（{@code TEAM} / {@code ORGANIZATION}）を要求するため、
 * 認可判定に渡す前に本ユーティリティで正準形へ正規化する（{@code workflow} ドメインの
 * {@code WorkflowScopes} と同一パターン）。</p>
 */
public final class FormScopes {

    private FormScopes() {
    }

    /**
     * スコープ種別文字列を認可基盤の正準形（{@code TEAM} / {@code ORGANIZATION}）へ正規化する。
     *
     * @param scopeType パス変数または entity 由来のスコープ種別
     *                  （{@code teams} / {@code team} / {@code TEAM} /
     *                  {@code organizations} / {@code organization} / {@code ORGANIZATION}）
     * @return 正準形スコープ種別
     * @throws BusinessException COMMON_001: 未知のスコープ種別（不正なパスセグメント）
     */
    public static String canonical(String scopeType) {
        if (scopeType != null) {
            switch (scopeType.toLowerCase(Locale.ROOT)) {
                case "teams", "team" -> {
                    return "TEAM";
                }
                case "organizations", "organization" -> {
                    return "ORGANIZATION";
                }
                default -> {
                    // fall through to throw
                }
            }
        }
        throw new BusinessException(CommonErrorCode.COMMON_001);
    }
}
