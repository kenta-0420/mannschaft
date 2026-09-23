package com.mannschaft.app.membership.service;

import com.mannschaft.app.common.MembershipScopeQueryService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("CMP-1014 所属スコープ列挙の正本窓口")
class MembershipScopeQueryServiceTest {

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private MembershipRepository membershipRepository;

    @InjectMocks
    private MembershipScopeQueryService service;

    @Nested
    @DisplayName("通常のACTIVE所属")
    class ActiveAffiliations {

        @Test
        @DisplayName("TEAMはACTIVE・非削除のroleとcurrent membershipを統合するRepositoryへ委譲する")
        void teamDelegatesToActiveUnion() {
            given(userRoleRepository.findTeamIdsByUserId(7L)).willReturn(List.of(10L, 20L));

            assertThat(service.findActiveTeamIds(7L)).containsExactly(10L, 20L);
            verify(userRoleRepository).findTeamIdsByUserId(7L);
            verify(membershipRepository, never()).findActiveByUserAndScopeType(7L, ScopeType.TEAM);
        }

        @Test
        @DisplayName("ORGANIZATIONはACTIVE・非削除のroleとcurrent membershipを統合するRepositoryへ委譲する")
        void organizationDelegatesToActiveUnion() {
            given(userRoleRepository.findOrganizationIdsByUserId(7L)).willReturn(List.of(30L));

            assertThat(service.findActiveOrganizationIds(7L)).containsExactly(30L);
            verify(userRoleRepository).findOrganizationIdsByUserId(7L);
            verify(membershipRepository, never())
                    .findActiveByUserAndScopeType(7L, ScopeType.ORGANIZATION);
        }
    }

    @Nested
    @DisplayName("current membership直結")
    class CurrentMemberships {

        @Test
        @DisplayName("TEAMはユーザー状態を足さずleft_at未設定の行順と重複を維持する")
        void teamPreservesRepositoryPopulationAndOrder() {
            LocalDateTime newer = LocalDateTime.parse("2026-08-20T12:00:00");
            LocalDateTime older = LocalDateTime.parse("2026-08-19T12:00:00");
            given(membershipRepository.findActiveByUserAndScopeType(7L, ScopeType.TEAM))
                    .willReturn(List.of(
                            membership(10L, newer, RoleKind.MEMBER),
                            membership(10L, older, RoleKind.SUPPORTER)));

            assertThat(service.findCurrentMembershipTeamIds(7L)).containsExactly(10L, 10L);
        }

        @Test
        @DisplayName("表示用DTOはscopeIdとjoinedAtだけを返しEntityを漏らさない")
        void returnsMinimalDisplayProjection() {
            LocalDateTime joinedAt = LocalDateTime.parse("2026-08-20T12:00:00");
            given(membershipRepository.findActiveByUserAndScopeType(7L, ScopeType.ORGANIZATION))
                    .willReturn(List.of(membership(30L, joinedAt, RoleKind.MEMBER)));

            assertThat(service.findCurrentMemberships(7L, ScopeType.ORGANIZATION))
                    .containsExactly(new MembershipScopeQueryService.CurrentMembershipScope(30L, joinedAt));
        }
    }

    @Nested
    @DisplayName("認可済Guardian subject例外")
    class AuthorizedGuardianSubject {

        @Test
        @DisplayName("childUserIdをそのままstatus不問のcurrent TEAM membershipへ渡す")
        void teamUsesChildUserId() {
            given(membershipRepository.findActiveByUserAndScopeType(99L, ScopeType.TEAM))
                    .willReturn(List.of(membership(10L, LocalDateTime.now(), RoleKind.MEMBER)));

            assertThat(service.findCurrentTeamIdsForAuthorizedGuardianSubject(99L))
                    .containsExactly(10L);
            verify(membershipRepository).findActiveByUserAndScopeType(99L, ScopeType.TEAM);
            verify(userRoleRepository, never()).findTeamIdsByUserId(99L);
        }

        @Test
        @DisplayName("childUserIdをそのままstatus不問のcurrent ORGANIZATION membershipへ渡す")
        void organizationUsesChildUserId() {
            given(membershipRepository.findActiveByUserAndScopeType(99L, ScopeType.ORGANIZATION))
                    .willReturn(List.of(membership(30L, LocalDateTime.now(), RoleKind.SUPPORTER)));

            assertThat(service.findCurrentOrganizationIdsForAuthorizedGuardianSubject(99L))
                    .containsExactly(30L);
            verify(membershipRepository)
                    .findActiveByUserAndScopeType(99L, ScopeType.ORGANIZATION);
            verify(userRoleRepository, never()).findOrganizationIdsByUserId(99L);
        }
    }

    private static MembershipEntity membership(Long scopeId, LocalDateTime joinedAt, RoleKind roleKind) {
        return MembershipEntity.builder()
                .userId(7L)
                .scopeType(ScopeType.TEAM)
                .scopeId(scopeId)
                .roleKind(roleKind)
                .joinedAt(joinedAt)
                .build();
    }
}
