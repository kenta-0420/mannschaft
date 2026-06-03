package com.mannschaft.app.payment.connect;

import com.mannschaft.app.recruitment.RecruitmentScopeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T6: payee/scope マッピングの単体テスト（設計書 02 §2 / 03 §3'）。
 *
 * <p>{@code ScopeKind.ORG ↔ RecruitmentScopeType.ORGANIZATION}、TEAM↔TEAM、
 * USER は users.id 独立であることを 1 クラス集約の {@link PayeeScopeResolver} で固定する。</p>
 */
@DisplayName("PayeeScopeResolver マッピング単体テスト（T6）")
class PayeeScopeResolverTest {

    private final PayeeScopeResolver resolver = new PayeeScopeResolver();

    @Test
    @DisplayName("ScopeKind.ORG ↔ RecruitmentScopeType.ORGANIZATION")
    void orgMapsToOrganization() {
        assertThat(resolver.toRecruitmentScopeType(ScopeKind.ORG))
                .isEqualTo(RecruitmentScopeType.ORGANIZATION);
        assertThat(resolver.fromRecruitmentScopeType(RecruitmentScopeType.ORGANIZATION))
                .isEqualTo(ScopeKind.ORG);
    }

    @Test
    @DisplayName("ScopeKind.TEAM ↔ RecruitmentScopeType.TEAM")
    void teamMapsToTeam() {
        assertThat(resolver.toRecruitmentScopeType(ScopeKind.TEAM))
                .isEqualTo(RecruitmentScopeType.TEAM);
        assertThat(resolver.fromRecruitmentScopeType(RecruitmentScopeType.TEAM))
                .isEqualTo(ScopeKind.TEAM);
    }

    @Test
    @DisplayName("USER は札スコープに対応しない（変換不可）")
    void userHasNoRecruitmentScope() {
        assertThatThrownBy(() -> resolver.toRecruitmentScopeType(ScopeKind.USER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.toAccessControlScopeType(ScopeKind.USER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("認可スコープ文字列: TEAM→\"TEAM\" / ORG→\"ORGANIZATION\"")
    void accessControlScopeType() {
        assertThat(resolver.toAccessControlScopeType(ScopeKind.TEAM)).isEqualTo("TEAM");
        assertThat(resolver.toAccessControlScopeType(ScopeKind.ORG)).isEqualTo("ORGANIZATION");
    }
}
