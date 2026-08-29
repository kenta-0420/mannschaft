package com.mannschaft.app.payment.connect;

import com.mannschaft.app.recruitment.RecruitmentScopeType;
import org.springframework.stereotype.Component;

/**
 * F22.1 謝礼決済: 受領主体（payee）の scope マッピングを 1 クラスに集約する。
 *
 * <p>{@link ScopeKind}（connect_accounts / escrow_transactions の主体種別）と
 * {@link RecruitmentScopeType}（札のスコープ種別）の対応を一元管理する。
 * マッピングが複数箇所に散らばると齟齬（ORG↔ORGANIZATION のゆれ等）の温床になるため、
 * 設計書 03 §3' 解決事項1・02 §2 の意図に従い本クラスへ集約する。</p>
 *
 * <ul>
 *   <li>{@code ScopeKind.ORG} ↔ {@code RecruitmentScopeType.ORGANIZATION}</li>
 *   <li>{@code ScopeKind.TEAM} ↔ {@code RecruitmentScopeType.TEAM}</li>
 *   <li>{@code ScopeKind.USER} は {@code users.id} に独立（札スコープに対応しない）</li>
 * </ul>
 *
 * <p>認可ヘルパが要求する scopeType 文字列（{@code "TEAM"}/{@code "ORGANIZATION"}）も本クラスで供給する
 * （AccessControlService の引数取り違え防止・設計書 03 §3）。</p>
 */
@Component
public class PayeeScopeResolver {

    /** {@link AccessControlService} 等が要求する TEAM スコープ文字列。 */
    public static final String SCOPE_TYPE_TEAM = "TEAM";

    /** {@link AccessControlService} 等が要求する ORGANIZATION スコープ文字列。 */
    public static final String SCOPE_TYPE_ORGANIZATION = "ORGANIZATION";

    /**
     * {@link ScopeKind} を募集スコープ種別へ写す。
     *
     * @param scopeKind 受領主体種別
     * @return TEAM/ORG に対応する {@link RecruitmentScopeType}
     * @throws IllegalArgumentException USER は札スコープに対応しないため変換不可
     */
    public RecruitmentScopeType toRecruitmentScopeType(ScopeKind scopeKind) {
        return switch (scopeKind) {
            case TEAM -> RecruitmentScopeType.TEAM;
            case ORG -> RecruitmentScopeType.ORGANIZATION;
            case USER -> throw new IllegalArgumentException(
                    "USER は札スコープに対応しません（users.id 独立）: " + scopeKind);
        };
    }

    /**
     * 募集スコープ種別を {@link ScopeKind} へ写す。
     *
     * @param scopeType 募集スコープ種別
     * @return TEAM/ORG に対応する {@link ScopeKind}
     */
    public ScopeKind fromRecruitmentScopeType(RecruitmentScopeType scopeType) {
        return switch (scopeType) {
            case TEAM -> ScopeKind.TEAM;
            case ORGANIZATION -> ScopeKind.ORG;
            case PERSONAL -> throw new IllegalArgumentException(
                    "PERSONAL 札主は Phase 5 まで Connect 受領主体へ変換できません: " + scopeType);
        };
    }

    /**
     * 認可ヘルパに渡す scopeType 文字列を返す。
     *
     * @param scopeKind 受領主体種別（TEAM/ORG）
     * @return {@code "TEAM"} または {@code "ORGANIZATION"}
     * @throws IllegalArgumentException USER は scope 認可の対象でない（本人固定）ため変換不可
     */
    public String toAccessControlScopeType(ScopeKind scopeKind) {
        return switch (scopeKind) {
            case TEAM -> SCOPE_TYPE_TEAM;
            case ORG -> SCOPE_TYPE_ORGANIZATION;
            case USER -> throw new IllegalArgumentException(
                    "USER は scope 認可の対象ではありません（本人固定）: " + scopeKind);
        };
    }
}
