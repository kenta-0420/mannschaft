package com.mannschaft.app.billing;

import com.mannschaft.app.role.service.RoleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 柱③-B 請求担当引継: 引継先候補（＝承諾できる者）の判定（設計書 §5.5・§5.6）の単体テスト。
 *
 * <p>本クラスの結果が<b>承諾者の適格性判定そのもの</b>であるため（{@code BillingPayerHandoverTxService}
 * が {@code isEligibleAcceptor} に委ねている）、候補集合が広がることは即ち認可の穴を意味する。
 * Codex 検分2巡目 P1-1 では ORG 側が ADMIN に加えて DEPUTY_ADMIN まで含んでいたため、
 * DEPUTY_ADMIN が引継を承諾できる状態になっていた。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingPayerHandoverCandidateResolver 単体テスト（引継先候補＝承諾できる者の判定）")
class BillingPayerHandoverCandidateResolverTest {

    private static final Long SCOPE_ID = 42L;
    private static final Long OLD_PAYER = 7L;
    private static final Long ADMIN_USER = 8L;
    private static final Long DEPUTY_ADMIN_USER = 9L;

    @Mock private RoleService roleService;

    @InjectMocks private BillingPayerHandoverCandidateResolver resolver;

    @Test
    @DisplayName("P1-1(2巡目): ORG は ADMIN ロールのみを候補にする（DEPUTY_ADMIN を混ぜる照会を使わない）")
    void orgUsesAdminRoleOnlyQuery() {
        given(roleService.getUserIdsByOrganizationIdAndRoleName(SCOPE_ID, "ADMIN"))
                .willReturn(List.of(OLD_PAYER, ADMIN_USER));

        List<Long> candidates = resolver.candidateAdminUserIds(
                EntitlementScopeKind.ORG, SCOPE_ID, OLD_PAYER);

        assertThat(candidates).containsExactly(ADMIN_USER);
        // getAdminUserIdsByOrganizationId は名前に反して DEPUTY_ADMIN も返すため使ってはならない。
        verify(roleService, never()).getAdminUserIdsByOrganizationId(SCOPE_ID);
    }

    @Test
    @DisplayName("P1-1(2巡目): ORG の DEPUTY_ADMIN は承諾できない（ADMIN 照会に現れないため候補外）")
    void orgDeputyAdminIsNotEligibleAcceptor() {
        // ADMIN ロール限定の照会は DEPUTY_ADMIN を返さない（＝本番クエリの契約）。
        given(roleService.getUserIdsByOrganizationIdAndRoleName(SCOPE_ID, "ADMIN"))
                .willReturn(List.of(OLD_PAYER, ADMIN_USER));

        assertThat(resolver.isEligibleAcceptor(
                EntitlementScopeKind.ORG, SCOPE_ID, OLD_PAYER, DEPUTY_ADMIN_USER))
                .as("DEPUTY_ADMIN は引継を承諾できない（設計書 §5.6 は ADMIN ロールのみ）")
                .isFalse();
        assertThat(resolver.isEligibleAcceptor(
                EntitlementScopeKind.ORG, SCOPE_ID, OLD_PAYER, ADMIN_USER))
                .as("他 ADMIN は承諾できる").isTrue();
    }

    @Test
    @DisplayName("旧 payer 本人は候補に含まれない（自己承諾では支払担当が変わらない）")
    void oldPayerIsExcluded() {
        given(roleService.getUserIdsByTeamIdAndRoleName(SCOPE_ID, "ADMIN"))
                .willReturn(List.of(OLD_PAYER, ADMIN_USER));

        assertThat(resolver.candidateAdminUserIds(EntitlementScopeKind.TEAM, SCOPE_ID, OLD_PAYER))
                .doesNotContain(OLD_PAYER);
        assertThat(resolver.isEligibleAcceptor(
                EntitlementScopeKind.TEAM, SCOPE_ID, OLD_PAYER, OLD_PAYER)).isFalse();
    }

    @Test
    @DisplayName("TEAM も ADMIN ロール名を明示して照会する（ORG と母集合の定義を揃える）")
    void teamUsesAdminRoleNameQuery() {
        given(roleService.getUserIdsByTeamIdAndRoleName(SCOPE_ID, "ADMIN"))
                .willReturn(List.of(ADMIN_USER));

        assertThat(resolver.candidateAdminUserIds(EntitlementScopeKind.TEAM, SCOPE_ID, OLD_PAYER))
                .containsExactly(ADMIN_USER);
        verify(roleService).getUserIdsByTeamIdAndRoleName(SCOPE_ID, "ADMIN");
    }

    @Test
    @DisplayName("候補が居なければ空リスト（§5.5 ①②）。null 返却でも落ちない")
    void noCandidatesYieldsEmptyList() {
        given(roleService.getUserIdsByTeamIdAndRoleName(SCOPE_ID, "ADMIN")).willReturn(null);

        assertThat(resolver.candidateAdminUserIds(EntitlementScopeKind.TEAM, SCOPE_ID, OLD_PAYER))
                .isEmpty();
        assertThat(resolver.isEligibleAcceptor(
                EntitlementScopeKind.TEAM, SCOPE_ID, OLD_PAYER, ADMIN_USER)).isFalse();
    }

    @Test
    @DisplayName("USER スコープは候補ゼロ（引継の概念が無い）")
    void userScopeHasNoCandidates() {
        assertThat(resolver.candidateAdminUserIds(EntitlementScopeKind.USER, SCOPE_ID, OLD_PAYER))
                .isEmpty();
    }
}
