package com.mannschaft.app.village.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** CMP-260827-1808: 取引の外の代表権限判定と、取引の中の一致確認。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("村の主体の代表権限判定（取引の外）")
class VillageSubjectAuthorizationGuardTest {

    private static final Long ACTOR = 10L;
    private static final Long OTHER = 20L;
    private static final Long TEAM_ID = 300L;
    private static final Long ORG_ID = 400L;

    @Mock
    private AccessControlService accessControlService;
    @Mock
    private VillageMembershipRepository membershipRepository;
    @InjectMocks
    private VillageSubjectAuthorizationGuard guard;

    @Test
    @DisplayName("USER: 本人なら許可、他人のIDならVILLAGE_015（role ドメインは呼ばない）")
    void userSubjectIsSelfOnly() {
        assertThatCode(() -> VillageSubjectAuthorization.require(
                guard.judge(ACTOR, VillageSubjectType.USER, ACTOR), ACTOR, VillageSubjectType.USER, ACTOR))
                .doesNotThrowAnyException();
        assertForbidden(guard.judge(ACTOR, VillageSubjectType.USER, OTHER), VillageSubjectType.USER, OTHER);
        org.mockito.Mockito.verify(accessControlService, org.mockito.Mockito.never())
                .isAdminOrAbove(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("TEAM / ORGANIZATION: ADMIN・DEPUTY_ADMIN 判定に従う")
    void teamAndOrganizationFollowAdminOrAbove() {
        when(accessControlService.isAdminOrAbove(ACTOR, TEAM_ID, "TEAM")).thenReturn(true);
        when(accessControlService.isAdminOrAbove(ACTOR, ORG_ID, "ORGANIZATION")).thenReturn(false);

        assertThatCode(() -> VillageSubjectAuthorization.require(
                guard.judge(ACTOR, VillageSubjectType.TEAM, TEAM_ID), ACTOR, VillageSubjectType.TEAM, TEAM_ID))
                .doesNotThrowAnyException();
        assertForbidden(guard.judge(ACTOR, VillageSubjectType.ORGANIZATION, ORG_ID),
                VillageSubjectType.ORGANIZATION, ORG_ID);
    }

    @Test
    @DisplayName("判定した主体と取引の中の主体が違えば、許可済みでもVILLAGE_015")
    void mismatchedSubjectIsForbidden() {
        when(accessControlService.isAdminOrAbove(ACTOR, TEAM_ID, "TEAM")).thenReturn(true);
        VillageSubjectAuthorization teamVerdict = guard.judge(ACTOR, VillageSubjectType.TEAM, TEAM_ID);

        assertForbidden(teamVerdict, VillageSubjectType.TEAM, TEAM_ID + 1);
        assertForbidden(teamVerdict, VillageSubjectType.ORGANIZATION, TEAM_ID);
        assertThatThrownBy(() -> VillageSubjectAuthorization.require(
                teamVerdict, OTHER, VillageSubjectType.TEAM, TEAM_ID))
                .isInstanceOf(BusinessException.class);
        assertForbidden(null, VillageSubjectType.USER, ACTOR);
    }

    @Test
    @DisplayName("退出: 所属の主体で判定し、所属が無ければ常に拒否の判定")
    void membershipVerdictUsesMembershipSubject() {
        UUID present = UUID.randomUUID();
        UUID absent = UUID.randomUUID();
        VillageMembershipEntity membership = VillageMembershipEntity.builder()
                .subjectType(VillageSubjectType.USER).subjectId(ACTOR).build();
        when(membershipRepository.findById(present)).thenReturn(Optional.of(membership));
        when(membershipRepository.findById(absent)).thenReturn(Optional.empty());

        assertThatCode(() -> VillageSubjectAuthorization.require(
                guard.judgeMembership(ACTOR, present), ACTOR, VillageSubjectType.USER, ACTOR))
                .doesNotThrowAnyException();
        assertForbidden(guard.judgeMembership(ACTOR, absent), VillageSubjectType.USER, ACTOR);
    }

    @Test
    @DisplayName("非公開村の可視性に使う SYSTEM_ADMIN 判定も取引の外で済ませ、操作者が違えば使わない")
    void systemAdminJudgmentTravelsWithVerdict() {
        when(accessControlService.isSystemAdmin(ACTOR)).thenReturn(true);
        VillageSubjectAuthorization verdict = guard.judge(ACTOR, VillageSubjectType.USER, ACTOR);

        org.assertj.core.api.Assertions.assertThat(VillageSubjectAuthorization.actorIsSystemAdmin(verdict, ACTOR)).isTrue();
        org.assertj.core.api.Assertions.assertThat(VillageSubjectAuthorization.actorIsSystemAdmin(verdict, OTHER)).isFalse();
        org.assertj.core.api.Assertions.assertThat(VillageSubjectAuthorization.actorIsSystemAdmin(null, ACTOR)).isFalse();
    }

    private static void assertForbidden(VillageSubjectAuthorization verdict, VillageSubjectType type, Long subjectId) {
        assertThatThrownBy(() -> VillageSubjectAuthorization.require(verdict, ACTOR, type, subjectId))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.getErrorCode())
                                .isEqualTo(VillageErrorCode.REPRESENT_FORBIDDEN));
    }
}
