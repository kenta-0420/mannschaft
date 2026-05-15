package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.PostingIdentityListResponse;
import com.mannschaft.app.village.entity.UserVillageNicknameEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.UserVillageNicknameRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link PostingIdentityService} 単体テスト（F17.1 Phase 1 B9）。
 *
 * <p>カバー観点（§4.6 / §5.4 / §6.3）:</p>
 * <ul>
 *   <li>USER 一致 → 検証 OK / 非一致 → VILLAGE_040</li>
 *   <li>TEAM ADMIN かつ村メンバー → 検証 OK</li>
 *   <li>TEAM 非 ADMIN → VILLAGE_040</li>
 *   <li>TEAM ADMIN だが村メンバーでない → VILLAGE_040</li>
 *   <li>ORGANIZATION ADMIN かつ村メンバー → 検証 OK</li>
 *   <li>ORGANIZATION 非 ADMIN → VILLAGE_040</li>
 *   <li>listIdentities: 村人で USER + ADMIN なチーム/組織が返る</li>
 *   <li>listIdentities: 非村人 → VILLAGE_007</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PostingIdentityService 単体テスト")
class PostingIdentityServiceTest {

    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000001");
    private static final Long ACTOR_USER_ID = 100L;
    private static final Long OTHER_USER_ID = 200L;
    private static final Long TEAM_ID = 567L;
    private static final Long ORG_ID = 89L;

    @Mock
    private VillageRepository villageRepository;
    @Mock
    private VillageMembershipRepository membershipRepository;
    @Mock
    private UserVillageNicknameRepository nicknameRepository;
    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private TeamRepository teamRepository;
    @Mock
    private OrganizationRepository organizationRepository;

    @InjectMocks
    private PostingIdentityService service;

    private VillageEntity freeVillage;

    @BeforeEach
    void setUp() {
        freeVillage = VillageEntity.builder()
                .slug("test-village")
                .name("テスト村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .memberCountCache(0L)
                .build();
        freeVillage.setId(VILLAGE_ID);
    }

    private VillageMembershipEntity userMember() {
        return VillageMembershipEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(VillageSubjectType.USER)
                .subjectId(ACTOR_USER_ID)
                .role(VillageRole.VILLAGER)
                .joinedAt(LocalDateTime.now())
                .build();
    }

    private VillageMembershipEntity teamMember(Long teamId) {
        return VillageMembershipEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(VillageSubjectType.TEAM)
                .subjectId(teamId)
                .role(VillageRole.VILLAGER)
                .joinedAt(LocalDateTime.now())
                .build();
    }

    private VillageMembershipEntity orgMember(Long orgId) {
        return VillageMembershipEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(VillageSubjectType.ORGANIZATION)
                .subjectId(orgId)
                .role(VillageRole.VILLAGER)
                .joinedAt(LocalDateTime.now())
                .build();
    }

    // ========================================================================
    // validatePostingIdentity
    // ========================================================================

    @Test
    @DisplayName("USER: subjectId が actor と一致 → 通る")
    void validate_user_match_ok() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID))
                .willReturn(Optional.of(userMember()));

        // 例外が出ないこと
        service.validatePostingIdentity(ACTOR_USER_ID, VILLAGE_ID,
                VillageSubjectType.USER, ACTOR_USER_ID);
    }

    @Test
    @DisplayName("USER: 他人の subjectId 指定 → VILLAGE_040")
    void validate_user_mismatch_forbidden() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID))
                .willReturn(Optional.of(userMember()));

        assertThatThrownBy(() -> service.validatePostingIdentity(
                ACTOR_USER_ID, VILLAGE_ID, VillageSubjectType.USER, OTHER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.VILLAGE_POSTING_IDENTITY_FORBIDDEN);
    }

    @Test
    @DisplayName("TEAM: actor が ADMIN かつ村メンバー → 通る")
    void validate_team_admin_member_ok() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID))
                .willReturn(Optional.of(userMember()));
        given(userRoleRepository.countTeamAdminByUserIdAndTeamId(ACTOR_USER_ID, TEAM_ID))
                .willReturn(1L);
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.TEAM, TEAM_ID))
                .willReturn(Optional.of(teamMember(TEAM_ID)));

        service.validatePostingIdentity(ACTOR_USER_ID, VILLAGE_ID,
                VillageSubjectType.TEAM, TEAM_ID);
    }

    @Test
    @DisplayName("TEAM: actor が ADMIN でない → VILLAGE_040")
    void validate_team_notAdmin_forbidden() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID))
                .willReturn(Optional.of(userMember()));
        given(userRoleRepository.countTeamAdminByUserIdAndTeamId(ACTOR_USER_ID, TEAM_ID))
                .willReturn(0L);

        assertThatThrownBy(() -> service.validatePostingIdentity(
                ACTOR_USER_ID, VILLAGE_ID, VillageSubjectType.TEAM, TEAM_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.VILLAGE_POSTING_IDENTITY_FORBIDDEN);
    }

    @Test
    @DisplayName("TEAM: ADMIN だがチームが村のメンバーでない → VILLAGE_040")
    void validate_team_notVillageMember_forbidden() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID))
                .willReturn(Optional.of(userMember()));
        given(userRoleRepository.countTeamAdminByUserIdAndTeamId(ACTOR_USER_ID, TEAM_ID))
                .willReturn(1L);
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.TEAM, TEAM_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.validatePostingIdentity(
                ACTOR_USER_ID, VILLAGE_ID, VillageSubjectType.TEAM, TEAM_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.VILLAGE_POSTING_IDENTITY_FORBIDDEN);
    }

    @Test
    @DisplayName("ORGANIZATION: actor が ADMIN かつ村メンバー → 通る")
    void validate_org_admin_member_ok() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID))
                .willReturn(Optional.of(userMember()));
        given(userRoleRepository.findAdminUserIdsByOrganizationId(ORG_ID))
                .willReturn(List.of(ACTOR_USER_ID));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.of(orgMember(ORG_ID)));

        service.validatePostingIdentity(ACTOR_USER_ID, VILLAGE_ID,
                VillageSubjectType.ORGANIZATION, ORG_ID);
    }

    @Test
    @DisplayName("ORGANIZATION: actor が ADMIN でない → VILLAGE_040")
    void validate_org_notAdmin_forbidden() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID))
                .willReturn(Optional.of(userMember()));
        given(userRoleRepository.findAdminUserIdsByOrganizationId(ORG_ID))
                .willReturn(List.of(OTHER_USER_ID));

        assertThatThrownBy(() -> service.validatePostingIdentity(
                ACTOR_USER_ID, VILLAGE_ID, VillageSubjectType.ORGANIZATION, ORG_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.VILLAGE_POSTING_IDENTITY_FORBIDDEN);
    }

    @Test
    @DisplayName("非村人ユーザーは validate でも listIdentities でも NOT_MEMBER (404)")
    void validate_notVillageMember_404() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.validatePostingIdentity(
                ACTOR_USER_ID, VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.NOT_MEMBER);
    }

    // ========================================================================
    // listIdentities
    // ========================================================================

    @Test
    @DisplayName("listIdentities: USER + ADMIN チーム + ADMIN 組織すべてが返る")
    void list_userAndTeamAndOrg_returnsAll() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        // 自分が村の USER メンバー
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID))
                .willReturn(Optional.of(userMember()));
        // ニックネーム
        UserVillageNicknameEntity nick = UserVillageNicknameEntity.builder()
                .userId(ACTOR_USER_ID).nickname("山田太郎")
                .lastChangedAt(LocalDateTime.now()).changeCountThisMonth(0L).build();
        given(nicknameRepository.findByUserIdAndVillageIdIsNull(ACTOR_USER_ID))
                .willReturn(Optional.of(nick));

        // 村メンバー: TEAM 567 と ORG 89
        Page<VillageMembershipEntity> page = new PageImpl<>(List.of(
                userMember(), teamMember(TEAM_ID), orgMember(ORG_ID)));
        given(membershipRepository.findByVillageIdAndLeftAtIsNullOrderByJoinedAtAsc(
                eq(VILLAGE_ID), any(Pageable.class))).willReturn(page);

        // actor のチーム所属
        UserRoleEntity teamRole = UserRoleEntity.builder()
                .userId(ACTOR_USER_ID).teamId(TEAM_ID).roleId(1L).build();
        given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(ACTOR_USER_ID))
                .willReturn(List.of(teamRole));
        given(userRoleRepository.countTeamAdminByUserIdAndTeamId(ACTOR_USER_ID, TEAM_ID))
                .willReturn(1L);
        TeamEntity team = TeamEntity.builder().name("ABC整骨院").build();
        given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));

        // actor の組織所属
        UserRoleEntity orgRole = UserRoleEntity.builder()
                .userId(ACTOR_USER_ID).organizationId(ORG_ID).roleId(1L).build();
        given(userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(ACTOR_USER_ID))
                .willReturn(List.of(orgRole));
        given(userRoleRepository.findAdminUserIdsByOrganizationId(ORG_ID))
                .willReturn(List.of(ACTOR_USER_ID));
        OrganizationEntity org = OrganizationEntity.builder().name("ヘルスケア協会").build();
        given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));

        PostingIdentityListResponse res = service.listIdentities(ACTOR_USER_ID, VILLAGE_ID);

        assertThat(res.identities()).hasSize(3);
        assertThat(res.identities().get(0).subjectType()).isEqualTo(VillageSubjectType.USER);
        assertThat(res.identities().get(0).displayName()).isEqualTo("山田太郎");
        assertThat(res.identities().get(1).subjectType()).isEqualTo(VillageSubjectType.TEAM);
        assertThat(res.identities().get(1).displayName()).isEqualTo("ABC整骨院");
        assertThat(res.identities().get(2).subjectType()).isEqualTo(VillageSubjectType.ORGANIZATION);
        assertThat(res.identities().get(2).displayName()).isEqualTo("ヘルスケア協会");
    }

    @Test
    @DisplayName("listIdentities: 非村人 → NOT_MEMBER (404)")
    void list_notVillageMember_404() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.listIdentities(ACTOR_USER_ID, VILLAGE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.NOT_MEMBER);
    }
}
