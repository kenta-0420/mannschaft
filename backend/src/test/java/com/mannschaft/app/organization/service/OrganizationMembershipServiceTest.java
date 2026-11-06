package com.mannschaft.app.organization.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.organization.dto.OrgAllMembersResponse;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MemberDto;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.query.MemberQueryDispatcher;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.membership.service.ScopeMemberCalendarSettingService;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.dto.MemberResponse;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link OrganizationMembershipService} の単体テスト。
 *
 * <p>リファクタリング Phase 5 で OrganizationService から切り出した。
 * テスト内容（assertion・stub）は分割前から変更していない。
 * F00.5 Phase 3: getMembers() は MemberQueryDispatcher 経由で memberships 参照に切替済み。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationMembershipService 単体テスト")
class OrganizationMembershipServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long ORG_ID = 10L;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private TeamOrgMembershipRepository teamOrgMembershipRepository;

    @Mock
    private MemberQueryDispatcher memberQueryDispatcher;

    @Mock
    private MembershipService membershipService;

    @Mock
    private MembershipRepository membershipRepository;

    @Mock
    private MediaUrlResolver mediaUrlResolver;

    @Mock
    private ScopeMemberCalendarSettingService scopeMemberCalendarSettingService;

    @InjectMocks
    private OrganizationMembershipService organizationMembershipService;

    // ========================================
    // getMembers
    // ========================================

    @Nested
    @DisplayName("getMembers")
    class GetMembers {

        @Test
        @DisplayName("メンバー一覧取得_ユーザー情報付きで返される")
        void メンバー一覧取得_ユーザー情報付きで返される() {
            OrganizationEntity org = createOrganization();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));

            // F00.5 Phase 3: MemberQueryDispatcher 経由で memberships 参照
            // CMP-261103-1225: identity を切り出してから hydrate で実体化する
            LocalDateTime joinedAt = LocalDateTime.now().minusMonths(1);
            MemberDto identity = new MemberDto(USER_ID, null, null, "ADMIN", joinedAt);
            MemberDto memberDto = new MemberDto(USER_ID, "yamada", null, "ADMIN", joinedAt);
            given(memberQueryDispatcher.queryMemberIdentities(ORG_ID, ScopeType.ORGANIZATION, null))
                    .willReturn(List.of(identity));
            given(memberQueryDispatcher.hydrate(List.of(identity))).willReturn(List.of(memberDto));

            Pageable pageable = PageRequest.of(0, 10);
            PagedResponse<MemberResponse> response =
                    organizationMembershipService.getMembers(ORG_ID, pageable);

            assertThat(response.getData()).hasSize(1);
            assertThat(response.getData().get(0).getDisplayName()).isEqualTo("yamada");
            assertThat(response.getData().get(0).getRoleName()).isEqualTo("ADMIN");
        }

        @Test
        @DisplayName("メンバー一覧取得_空リストの場合は空で返される")
        void メンバー一覧取得_空リスト返却() {
            OrganizationEntity org = createOrganization();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));

            // Dispatcher が空リストを返す場合
            given(memberQueryDispatcher.queryMemberIdentities(ORG_ID, ScopeType.ORGANIZATION, null))
                    .willReturn(List.of());
            given(memberQueryDispatcher.hydrate(List.of())).willReturn(List.of());

            Pageable pageable = PageRequest.of(0, 10);
            PagedResponse<MemberResponse> response =
                    organizationMembershipService.getMembers(ORG_ID, pageable);

            assertThat(response.getData()).isEmpty();
        }

        @ParameterizedTest(name = "page={0}")
        @CsvSource({"0, 0, 20", "1, 20, 40", "2, 40, 45", "3, 45, 45"})
        @DisplayName("AC-14: 45人・ページサイズ20で、実体化と色解決の対象がそのページの ID に限られる（先頭・中間・末尾・範囲外）")
        void 実体化と色解決はページ内のIDに限る(int page, int from, int to) {
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(createOrganization()));
            List<MemberDto> identities = new ArrayList<>();
            for (long id = 1; id <= 45; id++) {
                identities.add(new MemberDto(id, null, null, "MEMBER", null));
            }
            given(memberQueryDispatcher.queryMemberIdentities(ORG_ID, ScopeType.ORGANIZATION, null))
                    .willReturn(identities);
            List<MemberDto> expectedPage = identities.subList(from, to);
            given(memberQueryDispatcher.hydrate(expectedPage)).willReturn(expectedPage);

            PagedResponse<MemberResponse> res =
                    organizationMembershipService.getMembers(ORG_ID, PageRequest.of(page, 20));

            List<Long> pageIds = expectedPage.stream().map(MemberDto::userId).toList();
            verify(memberQueryDispatcher).hydrate(expectedPage);
            verify(memberQueryDispatcher, never()).queryMembers(any(), any(), any());
            verify(scopeMemberCalendarSettingService)
                    .resolveColors(ScopeType.ORGANIZATION, ORG_ID, pageIds);
            assertThat(res.getData()).extracting(MemberResponse::getUserId).containsExactlyElementsOf(pageIds);
            assertThat(res.getMeta().getTotal()).isEqualTo(45);
            assertThat(res.getMeta().getPage()).isEqualTo(page);
            assertThat(res.getMeta().getTotalPages()).isEqualTo(3);
        }

        @Test
        @DisplayName("組織不在_ORG_001例外")
        void 組織不在_ORG_001例外() {
            given(organizationRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> organizationMembershipService.getMembers(999L, PageRequest.of(0, 10)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_001"));
        }
    }

    // ========================================
    // getAllMembers（画像URL根治Phase2）
    // ========================================

    @Nested
    @DisplayName("getAllMembers")
    class GetAllMembers {

        @Test
        @DisplayName("画像URL根治Phase2_直属メンバーのavatarが署名付き表示URLへ解決される")
        void 直属メンバーのavatarが署名付き表示URLへ解決される() {
            OrganizationEntity org = createOrganization();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));

            Long roleId = 100L;
            UserRoleEntity ur = UserRoleEntity.builder()
                    .userId(USER_ID)
                    .roleId(roleId)
                    .organizationId(ORG_ID)
                    .build();
            given(userRoleRepository.findByOrganizationId(eq(ORG_ID), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(ur)));

            UserEntity user = UserEntity.builder()
                    .email("member@example.com")
                    .passwordHash("hash")
                    .lastName("山田")
                    .firstName("太郎")
                    .displayName("yamada")
                    .avatarUrl("user/1/avatar/raw.png")
                    .build();
            ReflectionTestUtils.setField(user, "id", USER_ID);
            given(userRepository.findAllById(Set.of(USER_ID))).willReturn(List.of(user));

            RoleEntity role = RoleEntity.builder().name("MEMBER").build();
            ReflectionTestUtils.setField(role, "id", roleId);
            given(roleRepository.findAllById(Set.of(roleId))).willReturn(List.of(role));

            given(mediaUrlResolver.resolve("user/1/avatar/raw.png"))
                    .willReturn("https://cdn.example.com/signed/avatar.png");

            List<OrgAllMembersResponse> result =
                    organizationMembershipService.getAllMembers(ORG_ID, "ORGANIZATION");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getIconUrl())
                    .isEqualTo("https://cdn.example.com/signed/avatar.png");
        }

        @Test
        @DisplayName("AC-4/AC-5/AC-6: 直属と複数チームの重複は所属ごとに残し、退会者・実体の無いチーム・不明ロールで例外を出さない")
        void 重複と欠損参照を是正前と同じに扱う() {
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(createOrganization()));
            Long teamA = 501L;
            Long teamB = 502L;
            Long danglingTeam = 503L; // 加盟行はあるが teams に返らない（論理削除・参照切れ）
            Long shared = 1L;
            Long withdrawn = 2L;      // users に返らない（@SQLRestriction）
            Long roleMember = 100L;
            Long unknownRole = 999L;  // roles に無い → ロール名 null（是正前の Optional.map(...).orElse(null) と同じ）
            given(userRoleRepository.findByOrganizationId(eq(ORG_ID), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(
                            userRole(shared, roleMember, null, ORG_ID),
                            userRole(withdrawn, roleMember, null, ORG_ID))));
            given(teamOrgMembershipRepository.findByOrganizationIdAndStatus(ORG_ID, TeamOrgMembershipEntity.Status.ACTIVE))
                    .willReturn(List.of(link(teamA), link(danglingTeam), link(teamB)));
            given(teamRepository.findAllById(Set.of(teamA, danglingTeam, teamB)))
                    .willReturn(List.of(team(teamB, "B"), team(teamA, "A")));
            given(userRoleRepository.findByTeamIdInOrderByTeamIdAscIdAsc(any()))
                    .willReturn(List.of(
                            userRole(shared, roleMember, teamA, null),
                            userRole(shared, unknownRole, teamB, null)));
            UserEntity user = UserEntity.builder().email("s@example.com").passwordHash("h")
                    .lastName("山田").firstName("太郎").displayName("yamada").build();
            ReflectionTestUtils.setField(user, "id", shared);
            given(userRepository.findAllById(Set.of(shared, withdrawn))).willReturn(List.of(user));
            RoleEntity member = RoleEntity.builder().name("MEMBER").build();
            ReflectionTestUtils.setField(member, "id", roleMember);
            given(roleRepository.findAllById(Set.of(roleMember, unknownRole))).willReturn(List.of(member));

            List<OrgAllMembersResponse> result = organizationMembershipService.getAllMembers(ORG_ID, "INDIVIDUAL");

            assertThat(result).extracting(r -> r.getUserId() + "|" + r.getMemberOf().getType() + "|"
                            + r.getMemberOf().getId() + "|" + r.getRole())
                    .containsExactly(
                            shared + "|ORGANIZATION|null|MEMBER",
                            shared + "|TEAM|" + teamA + "|MEMBER",
                            shared + "|TEAM|" + teamB + "|null");
        }

        @Test
        @DisplayName("AC-3: 直属0人・加盟チーム0件なら users・roles・teams を引かない（空 IN を投げない）")
        void 空なら一括取得を呼ばない() {
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(createOrganization()));
            given(userRoleRepository.findByOrganizationId(eq(ORG_ID), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of()));
            given(teamOrgMembershipRepository.findByOrganizationIdAndStatus(ORG_ID, TeamOrgMembershipEntity.Status.ACTIVE))
                    .willReturn(List.of());

            assertThat(organizationMembershipService.getAllMembers(ORG_ID, "INDIVIDUAL")).isEmpty();
            verify(userRepository, never()).findAllById(any());
            verify(roleRepository, never()).findAllById(any());
            verify(teamRepository, never()).findAllById(any());
            verify(userRoleRepository, never()).findByTeamIdInOrderByTeamIdAscIdAsc(any());
        }

        private UserRoleEntity userRole(Long userId, Long roleId, Long teamId, Long orgId) {
            return UserRoleEntity.builder().userId(userId).roleId(roleId).teamId(teamId).organizationId(orgId).build();
        }

        private TeamOrgMembershipEntity link(Long teamId) {
            return TeamOrgMembershipEntity.builder()
                    .teamId(teamId).organizationId(ORG_ID).status(TeamOrgMembershipEntity.Status.ACTIVE).build();
        }

        private TeamEntity team(Long id, String name) {
            TeamEntity t = TeamEntity.builder().name(name).build();
            ReflectionTestUtils.setField(t, "id", id);
            return t;
        }
    }

    // ========================================
    // followOrganization / unfollowOrganization
    // ========================================

    @Nested
    @DisplayName("followOrganization")
    class FollowOrganization {

        @Test
        @DisplayName("正常フォロー_memberships に SUPPORTER として入会される")
        void 正常フォロー_membershipsにSUPPORTERとして入会される() {
            OrganizationEntity org = createOrganization();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));
            // F00.5 Phase 5: memberships ベースの重複チェック
            given(membershipRepository.existsActiveByUserAndScopeAndRoleKind(
                    USER_ID, ScopeType.ORGANIZATION, ORG_ID, RoleKind.SUPPORTER)).willReturn(false);

            organizationMembershipService.followOrganization(USER_ID, ORG_ID);

            verify(membershipService).join(any(MembershipCreateRequest.class));
        }

        @Test
        @DisplayName("既にSUPPORTERとして所属している_ORG_007例外")
        void 既にメンバー_ORG_007例外() {
            OrganizationEntity org = createOrganization();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));
            // F00.5 Phase 5: memberships ベースの重複チェック
            given(membershipRepository.existsActiveByUserAndScopeAndRoleKind(
                    USER_ID, ScopeType.ORGANIZATION, ORG_ID, RoleKind.SUPPORTER)).willReturn(true);

            assertThatThrownBy(() -> organizationMembershipService.followOrganization(USER_ID, ORG_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_007"));
        }

        @Test
        @DisplayName("組織不在_ORG_001例外")
        void 組織不在_ORG_001例外() {
            given(organizationRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> organizationMembershipService.followOrganization(USER_ID, 999L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_001"));
        }
    }

    @Nested
    @DisplayName("unfollowOrganization")
    class UnfollowOrganization {

        @Test
        @DisplayName("正常フォロー解除_memberships から退会される")
        void 正常フォロー解除_membershipsから退会される() {
            OrganizationEntity org = createOrganization();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));
            // F00.5 Phase 5: active な membership がある場合は leave を呼ぶ
            MembershipEntity activeMembership = MembershipEntity.builder()
                    .userId(USER_ID)
                    .scopeType(ScopeType.ORGANIZATION)
                    .scopeId(ORG_ID)
                    .roleKind(RoleKind.SUPPORTER)
                    .build();
            given(membershipRepository.findActiveByUserAndScope(USER_ID, ScopeType.ORGANIZATION, ORG_ID))
                    .willReturn(Optional.of(activeMembership));

            organizationMembershipService.unfollowOrganization(USER_ID, ORG_ID);

            verify(membershipService).leave(any(), any());
        }

        @Test
        @DisplayName("フォロー未登録でも例外なし_何もしない")
        void フォロー未登録_例外なし() {
            OrganizationEntity org = createOrganization();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));
            // active な membership がない場合は何もしない
            given(membershipRepository.findActiveByUserAndScope(USER_ID, ScopeType.ORGANIZATION, ORG_ID))
                    .willReturn(Optional.empty());

            organizationMembershipService.unfollowOrganization(USER_ID, ORG_ID);
            // 例外が発生しないことを確認
        }

        @Test
        @DisplayName("組織不在_ORG_001例外")
        void 組織不在_ORG_001例外() {
            given(organizationRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> organizationMembershipService.unfollowOrganization(USER_ID, 999L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_001"));
        }
    }

    // ========================================
    // ヘルパー
    // ========================================

    private OrganizationEntity createOrganization() {
        return OrganizationEntity.builder()
                .name("テスト組織")
                .orgType(OrganizationEntity.OrgType.SCHOOL)
                .prefecture("東京都")
                .city("渋谷区")
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(false)
                .version(0L)
                .build();
    }
}
