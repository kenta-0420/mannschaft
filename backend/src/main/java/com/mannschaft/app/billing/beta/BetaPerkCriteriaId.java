package com.mannschaft.app.billing.beta;

import java.io.Serializable;
import java.util.Objects;

/**
 * F20.3 {@code beta_perk_criteria} の複合主キー（{@code beta_phase}, {@code grant_kind}）。
 *
 * <p>{@link jakarta.persistence.IdClass} 用の識別子クラス。フィールド名・型は
 * {@link BetaPerkCriteriaEntity} の {@code @Id} フィールドと一致させる（JPA 要件）。
 * マスタ例外（全テナント共通・複合自然キー）ゆえ UUID 化しない（設計書 01 §0 / §2）。</p>
 */
public class BetaPerkCriteriaId implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer betaPhase;
    private GrantKind grantKind;

    public BetaPerkCriteriaId() {
    }

    public BetaPerkCriteriaId(Integer betaPhase, GrantKind grantKind) {
        this.betaPhase = betaPhase;
        this.grantKind = grantKind;
    }

    public Integer getBetaPhase() {
        return betaPhase;
    }

    public GrantKind getGrantKind() {
        return grantKind;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BetaPerkCriteriaId that)) {
            return false;
        }
        return Objects.equals(betaPhase, that.betaPhase) && grantKind == that.grantKind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(betaPhase, grantKind);
    }
}
