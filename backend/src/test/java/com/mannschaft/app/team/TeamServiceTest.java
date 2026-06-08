package com.mannschaft.app.team;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.dto.CreateTeamRequest;
import com.mannschaft.app.team.dto.TeamResponse;
import com.mannschaft.app.team.dto.UpdateTeamRequest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import com.mannschaft.app.team.repository.TeamBlockRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.team.service.TeamShiftSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("TeamService 単体テスト")
class TeamServiceTest {

    @Mock private TeamRepository teamRepository;
    @Mock private TeamBlockRepository teamBlockRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private UserRepository userRepository;
    @Mock private TeamFriendRepository teamFriendRepository;
    @Mock private TeamShiftSettingsService teamShiftSettingsService;
    @Mock private MembershipService membershipService;
    @Mock private MembershipRepository membershipRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @InjectMocks private TeamService service;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final UUID TEAM_PUBLIC_ID = UUID.randomUUID();

    @Nested
    @DisplayName("createTeam")
    class CreateTeam {

        @Test
        @DisplayName("正常系: チームが作成され作成者がADMINになる")
        void 作成_正常_保存() {
            // Given
            CreateTeamRequest req = new CreateTeamRequest("テストチーム", "sports", "東京都", "渋谷区", null, null, null);
            RoleEntity adminRole = RoleEntity.builder().name("ADMIN").build();
            try {
                var field = adminRole.getClass().getSuperclass().getDeclaredField("id");
                field.setAccessible(true);
                field.set(adminRole, 1L);
            } catch (Exception ignored) {}
            given(roleRepository.findByName("ADMIN")).willReturn(Optional.of(adminRole));
            given(userRoleRepository.save(any(UserRoleEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(teamFriendRepository.countFriendsByTeamId(any())).willReturn(0L);
            // F00.5 Phase 5: SUPPORTER カウントを memberships 経由に切替
            given(membershipRepository.countActiveByScopeAndRoleKind(any(), any(), any())).willReturn(0L);

            // When
            ApiResponse<TeamResponse> result = service.createTeam(USER_ID, req);

            // Then
            assertThat(result.getData().getBasicInfo().name()).isEqualTo("テストチーム");
            verify(teamRepository).save(any(TeamEntity.class));
            verify(userRoleRepository).save(any(UserRoleEntity.class));
            // F00.5 認可基盤根治: memberships にも MEMBER として入会させる（join 経由）
            org.mockito.ArgumentCaptor<MembershipCreateRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(MembershipCreateRequest.class);
            verify(membershipService).join(captor.capture());
            MembershipCreateRequest joinReq = captor.getValue();
            assertThat(joinReq.getUserId()).isEqualTo(USER_ID);
            assertThat(joinReq.getScopeType()).isEqualTo(ScopeType.TEAM);
            assertThat(joinReq.getRoleKind()).isEqualTo(RoleKind.MEMBER);
            assertThat(joinReq.getSource()).isEqualTo("TEAM_CREATE");
        }
    }

    @Nested
    @DisplayName("getTeam")
    class GetTeam {

        @Test
        @DisplayName("異常系: チーム不在でTEAM_001例外")
        void 取得_不在_例外() {
            // Given
            given(teamRepository.findByPublicId(TEAM_PUBLIC_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.getTeam(TEAM_PUBLIC_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_001"));
        }
    }

    /**
     * F15.4 Phase 5-α: 未ログイン公開エンドポイント用 {@code getPublicTeam}。
     * 設計書 §4.3 のステータスマッピング（不在 / 削除 / archived / 非 PUBLIC → 404）を確認する。
     */
    @Nested
    @DisplayName("getPublicTeam (F15.4 Phase 5-α)")
    class GetPublicTeam {

        @Test
        @DisplayName("正常系: PUBLIC かつ未 archive かつ未削除なら DTO を返す")
        void 公開取得_正常() {
            TeamEntity team = TeamEntity.builder()
                    .name("公開店舗")
                    .template("salon")
                    .visibility(TeamEntity.Visibility.PUBLIC)
                    .supporterEnabled(true)
                    .build();
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));

            var dto = service.getPublicTeam(TEAM_ID);

            assertThat(dto).isNotNull();
            assertThat(dto.name()).isEqualTo("公開店舗");
            assertThat(dto.template()).isEqualTo("salon");
        }

        @Test
        @DisplayName("異常系: チーム不在 → TEAM_001（404 にマップ）")
        void 公開取得_不在_404() {
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getPublicTeam(TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_001"));
        }

        @Test
        @DisplayName("異常系: archived チーム → TEAM_001（マスター裁可: 一律 404）")
        void 公開取得_archived_404() {
            TeamEntity team = TeamEntity.builder()
                    .name("凍結店舗")
                    .template("salon")
                    .visibility(TeamEntity.Visibility.PUBLIC)
                    .supporterEnabled(true)
                    .build();
            team.archive();
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));

            assertThatThrownBy(() -> service.getPublicTeam(TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_001"));
        }

        @Test
        @DisplayName("異常系: 論理削除済み → TEAM_001（@SQLRestriction 抜けの安全網）")
        void 公開取得_deleted_404() {
            TeamEntity team = TeamEntity.builder()
                    .name("削除店舗")
                    .template("salon")
                    .visibility(TeamEntity.Visibility.PUBLIC)
                    .supporterEnabled(true)
                    .build();
            team.softDelete();
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));

            assertThatThrownBy(() -> service.getPublicTeam(TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_001"));
        }

        @Test
        @DisplayName("異常系: visibility=ORGANIZATION_ONLY → TEAM_001（IDOR 対策で 404）")
        void 公開取得_organizationOnly_404() {
            TeamEntity team = TeamEntity.builder()
                    .name("組織内店舗")
                    .template("salon")
                    .visibility(TeamEntity.Visibility.ORGANIZATION_ONLY)
                    .supporterEnabled(true)
                    .build();
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));

            assertThatThrownBy(() -> service.getPublicTeam(TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_001"));
        }

        @Test
        @DisplayName("異常系: visibility=PRIVATE → TEAM_001（IDOR 対策で 404）")
        void 公開取得_private_404() {
            TeamEntity team = TeamEntity.builder()
                    .name("非公開店舗")
                    .template("salon")
                    .visibility(TeamEntity.Visibility.PRIVATE)
                    .supporterEnabled(false)
                    .build();
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));

            assertThatThrownBy(() -> service.getPublicTeam(TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_001"));
        }
    }

    @Nested
    @DisplayName("archiveTeam")
    class ArchiveTeam {

        @Test
        @DisplayName("異常系: 既にアーカイブ済みでTEAM_002例外")
        void アーカイブ_既済_例外() {
            // Given
            TeamEntity team = TeamEntity.builder().name("テスト").template("sports")
                    .visibility(TeamEntity.Visibility.PRIVATE).build();
            team.archive(); // archivedAtをセット
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));

            // When / Then
            assertThatThrownBy(() -> service.archiveTeam(TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_002"));
        }
    }

    @Nested
    @DisplayName("updateTeam")
    class UpdateTeam {

        @Test
        @DisplayName("正常系: F15.4 Phase 5-β - mapEmbedUrl が保存されレスポンスに含まれる")
        void 更新_mapEmbedUrl_保存() {
            // Given
            TeamEntity team = TeamEntity.builder()
                    .name("テスト").template("sports")
                    .visibility(TeamEntity.Visibility.PUBLIC)
                    .supporterEnabled(false)
                    .build();
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));
            given(teamRepository.save(any(TeamEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(teamFriendRepository.countFriendsByTeamId(any())).willReturn(0L);
            given(membershipRepository.countActiveByScopeAndRoleKind(any(), any(), any())).willReturn(0L);

            String embedUrl = "https://www.google.com/maps/embed?pb=!1m18!1m12!1m3!1d12345";
            UpdateTeamRequest req = new UpdateTeamRequest(
                    null, null, null, null, null, null, null, null, null,
                    embedUrl, 1L);

            // When
            ApiResponse<TeamResponse> result = service.updateTeam(TEAM_ID, req);

            // Then
            assertThat(result.getData().getMetadata().mapEmbedUrl()).isEqualTo(embedUrl);
            verify(teamRepository).save(any(TeamEntity.class));
        }

        @Test
        @DisplayName("正常系: mapEmbedUrl=null の場合は既存値が保持される")
        void 更新_mapEmbedUrl_null時既存維持() {
            // Given
            TeamEntity team = TeamEntity.builder()
                    .name("テスト").template("sports")
                    .visibility(TeamEntity.Visibility.PUBLIC)
                    .supporterEnabled(false)
                    .mapEmbedUrl("https://www.google.com/maps/embed?pb=existing")
                    .build();
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));
            given(teamRepository.save(any(TeamEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(teamFriendRepository.countFriendsByTeamId(any())).willReturn(0L);
            given(membershipRepository.countActiveByScopeAndRoleKind(any(), any(), any())).willReturn(0L);

            UpdateTeamRequest req = new UpdateTeamRequest(
                    "新名称", null, null, null, null, null, null, null, null,
                    null, 1L);

            // When
            ApiResponse<TeamResponse> result = service.updateTeam(TEAM_ID, req);

            // Then
            assertThat(result.getData().getMetadata().mapEmbedUrl()).isEqualTo("https://www.google.com/maps/embed?pb=existing");
        }
    }

    @Nested
    @DisplayName("followTeam")
    class FollowTeam {

        @Test
        @DisplayName("異常系: ブロックされている場合TEAM_004例外")
        void フォロー_ブロック_例外() {
            // Given
            TeamEntity team = TeamEntity.builder().name("テスト").template("sports")
                    .visibility(TeamEntity.Visibility.PRIVATE).build();
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));
            given(teamBlockRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> service.followTeam(USER_ID, TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_004"));
        }

        @Test
        @DisplayName("異常系: 既にSUPPORTERとして所属している場合TEAM_003例外")
        void フォロー_既所属_例外() {
            // Given
            TeamEntity team = TeamEntity.builder().name("テスト").template("sports")
                    .visibility(TeamEntity.Visibility.PRIVATE).build();
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));
            given(teamBlockRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).willReturn(false);
            // F00.5 Phase 5: memberships ベースの重複チェック
            given(membershipRepository.existsActiveByUserAndScopeAndRoleKind(
                    USER_ID, ScopeType.TEAM, TEAM_ID, RoleKind.SUPPORTER)).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> service.followTeam(USER_ID, TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_003"));
        }

        @Test
        @DisplayName("正常系: memberships に SUPPORTER として入会される")
        void フォロー_正常_入会() {
            // Given
            TeamEntity team = TeamEntity.builder().name("テスト").template("sports")
                    .visibility(TeamEntity.Visibility.PRIVATE).build();
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));
            given(teamBlockRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).willReturn(false);
            given(membershipRepository.existsActiveByUserAndScopeAndRoleKind(
                    USER_ID, ScopeType.TEAM, TEAM_ID, RoleKind.SUPPORTER)).willReturn(false);

            // When
            service.followTeam(USER_ID, TEAM_ID);

            // Then
            verify(membershipService).join(any(MembershipCreateRequest.class));
        }
    }
}
