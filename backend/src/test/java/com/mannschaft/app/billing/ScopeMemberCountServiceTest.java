package com.mannschaft.app.billing;

import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.repository.MembershipRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link ScopeMemberCountService} 単体テスト（試練先行）。
 *
 * <p><b>ScopeType 変換の地雷検証</b>: billing の {@link EntitlementScopeKind}（USER/TEAM/ORG）を
 * membership の {@link ScopeType}（ORGANIZATION/TEAM）へ正しく橋渡しする。特に:</p>
 * <ul>
 *   <li>USER → リポジトリを呼ばず常に 1（{@code verifyNoInteractions}）。</li>
 *   <li>ORG → {@code ScopeType.ORGANIZATION} で問い合わせる（{@code valueOf("ORG")} の不一致 500 を封じる）。</li>
 *   <li>TEAM → {@code ScopeType.TEAM}。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScopeMemberCountService 単体テスト（ScopeType 変換の地雷）")
class ScopeMemberCountServiceTest {

    @Mock
    private MembershipRepository membershipRepository;

    @InjectMocks
    private ScopeMemberCountService service;

    @Test
    @DisplayName("USER は常に 1 を返し、membership リポジトリを一切呼ばない")
    void userReturnsOneWithoutRepository() {
        assertThat(service.countActiveMembers(EntitlementScopeKind.USER, 999L)).isEqualTo(1);
        verifyNoInteractions(membershipRepository);
    }

    @Test
    @DisplayName("TEAM は ScopeType.TEAM で countActiveDistinctUsersByScope を呼ぶ")
    void teamUsesScopeTypeTeam() {
        given(membershipRepository.countActiveDistinctUsersByScope(ScopeType.TEAM, 10L)).willReturn(34L);

        assertThat(service.countActiveMembers(EntitlementScopeKind.TEAM, 10L)).isEqualTo(34);
        verify(membershipRepository).countActiveDistinctUsersByScope(ScopeType.TEAM, 10L);
    }

    @Test
    @DisplayName("ORG は ScopeType.ORGANIZATION へ綴り変換して呼ぶ（valueOf(\"ORG\") 不一致 500 を封じる）")
    void orgConvertsToOrganization() {
        given(membershipRepository.countActiveDistinctUsersByScope(ScopeType.ORGANIZATION, 5L)).willReturn(120L);

        assertThat(service.countActiveMembers(EntitlementScopeKind.ORG, 5L)).isEqualTo(120);
        verify(membershipRepository).countActiveDistinctUsersByScope(ScopeType.ORGANIZATION, 5L);
    }
}
