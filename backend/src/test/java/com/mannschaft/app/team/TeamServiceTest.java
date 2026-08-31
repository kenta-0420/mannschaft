package com.mannschaft.app.team;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.membership.query.MemberQueryDispatcher;
import com.mannschaft.app.membership.service.ScopeMemberCalendarSettingService;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.AdminRoleMutationLockService;
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
    @Mock private MediaUrlResolver mediaUrlResolver;
    @Mock private MemberQueryDispatcher memberQueryDispatcher;
    @Mock private ScopeMemberCalendarSettingService scopeMemberCalendarSettingService;
    @Mock private AdminRoleMutationLockService adminRoleMutationLockService;
    @InjectMocks private TeamService service;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final String TEAM_SLUG = "test-team";

    @Nested
    @DisplayName("createTeam")
    class CreateTeam {

        @Test
        @DisplayName("正常系: チームが作成され作成者がADMINになる")
        void 作成_正常_保存() {
            // Given
            CreateTeamRequest req = new CreateTeamRequest("テストチーム", "sports", "東京都", "渋谷区", null, null, null, null);
            given(adminRoleMutationLockService.lockAdminRoleIdForCreation(USER_ID)).willReturn(Optional.of(1L));
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
            given(teamRepository.findBySlugAndDeletedAtIsNull(TEAM_SLUG)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.getTeam(TEAM_SLUG))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_001"));
        }

        @Test
        @DisplayName("F09.19.10: numericIdに内部BIGINT IDが返される(Spotlight scopeId解決用)")
        void numericIdに内部BIGINT_IDが返される() {
            // Given: slug は名称由来の非数値文字列（数値化不能な現実のケースを模す）
            TeamEntity team = TeamEntity.builder()
                    .slug(TEAM_SLUG).name("テストチーム").template("sports")
                    .visibility(TeamEntity.Visibility.PUBLIC)
                    .build();
            org.springframework.test.util.ReflectionTestUtils.setField(team, "id", TEAM_ID);
            given(teamRepository.findBySlugAndDeletedAtIsNull(TEAM_SLUG)).willReturn(Optional.of(team));
            given(teamFriendRepository.countFriendsByTeamId(any())).willReturn(0L);
            given(membershipRepository.countActiveByScopeAndRoleKind(any(), any(), any())).willReturn(0L);
            given(userRoleRepository.countByTeamId(any())).willReturn(0L);

            // When
            TeamResponse res = service.getTeam(TEAM_SLUG).getData();

            // Then
            assertThat(res.getNumericId()).isEqualTo(TEAM_ID);
            // id/slug は URL識別子のまま（正準はslug。numericIdはURLに使わない内部連携専用）
            assertThat(res.getId()).isEqualTo(res.getSlug());
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
        @DisplayName("異常系: visibility=GUESTS_AND_ABOVE → TEAM_001（IDOR 対策で 404）")
        void 公開取得_guestsAndAbove_404() {
            TeamEntity team = TeamEntity.builder()
                    .name("組織内店舗")
                    .template("salon")
                    .visibility(TeamEntity.Visibility.GUESTS_AND_ABOVE)
                    .supporterEnabled(true)
                    .build();
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));

            assertThatThrownBy(() -> service.getPublicTeam(TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_001"));
        }

        @Test
        @DisplayName("異常系: visibility=MEMBERS_AND_ABOVE → TEAM_001（IDOR 対策で 404）")
        void 公開取得_membersAndAbove_404() {
            TeamEntity team = TeamEntity.builder()
                    .name("非公開店舗")
                    .template("salon")
                    .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE)
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
                    .visibility(TeamEntity.Visibility.GUESTS_AND_ABOVE).build();
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
        @DisplayName("回帰防止: 既存エンティティをUPDATE_id不変かつ新規行を作らない(toBuilder id欠落INSERT化の根治)")
        void 更新_既存エンティティをUPDATE_id不変かつ新規行を作らない() {
            // Given: findById で取得した id 採番済みの managed entity を模す。
            // BaseEntity#id は setter を持たないため ReflectionTestUtils で採番済み状態を再現する。
            TeamEntity team = TeamEntity.builder()
                    .slug(TEAM_SLUG)
                    .name("旧名称").template("sports")
                    .visibility(TeamEntity.Visibility.PUBLIC)
                    .supporterEnabled(false)
                    .build();
            org.springframework.test.util.ReflectionTestUtils.setField(team, "id", TEAM_ID);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));
            given(teamRepository.save(any(TeamEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(teamFriendRepository.countFriendsByTeamId(any())).willReturn(0L);
            given(membershipRepository.countActiveByScopeAndRoleKind(any(), any(), any())).willReturn(0L);

            UpdateTeamRequest req = new UpdateTeamRequest(
                    "新名称", null, null, null, null, null, null, null, null,
                    null, 1L);

            // When
            service.updateTeam(TEAM_ID, req);

            // Then: save に渡るのは findById で取得した「まさにその」managed entity（別インスタンスではない）。
            // id が保持されているので save は UPDATE になり、新規 INSERT（id=null・slug 一意制約違反500）は起きない。
            org.mockito.ArgumentCaptor<TeamEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(TeamEntity.class);
            verify(teamRepository).save(captor.capture());
            TeamEntity saved = captor.getValue();
            assertThat(saved).isSameAs(team);
            assertThat(saved.getId()).isEqualTo(TEAM_ID); // id 欠落（INSERT 化）が起きていない
            assertThat(saved.getSlug()).isEqualTo(TEAM_SLUG); // slug 据置
            assertThat(saved.getName()).isEqualTo("新名称"); // 部分更新が反映
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
                    .visibility(TeamEntity.Visibility.GUESTS_AND_ABOVE).build();
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
                    .visibility(TeamEntity.Visibility.GUESTS_AND_ABOVE).build();
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
                    .visibility(TeamEntity.Visibility.GUESTS_AND_ABOVE).build();
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

    /**
     * 画像 URL 根治 Phase 1: チーム詳細（toResponse）・公開詳細（getPublicTeam）の
     * icon/banner が生 R2 キーではなく {@link MediaUrlResolver} の解決済み署名付き表示 URL に
     * なることを検証する。{@code mapEmbedUrl} は R2 キーではないため素通し（resolver を通さない）。
     */
    @Nested
    @DisplayName("画像URL解決（MediaUrlResolver 配線）")
    class MediaUrlResolution {

        private static final String ICON_KEY = "team/10/icon/x.png";
        private static final String BANNER_KEY = "team/10/banner/y.png";
        private static final String SIGNED_ICON = "https://signed/icon.png?sig=ic";
        private static final String SIGNED_BANNER = "https://signed/banner.png?sig=bn";
        private static final String MAP_EMBED = "https://www.google.com/maps/embed?pb=!1m18";

        private void stubCommonCounts() {
            given(teamFriendRepository.countFriendsByTeamId(any())).willReturn(0L);
            given(membershipRepository.countActiveByScopeAndRoleKind(any(), any(), any())).willReturn(0L);
            given(userRoleRepository.countByTeamId(any())).willReturn(0L);
        }

        @Test
        @DisplayName("AC-6/AC-10: toResponse の metadata.iconUrl/bannerUrl は解決値・mapEmbedUrl は素通し")
        void toResponse_解決値が乗る_mapEmbedは素通し() {
            // Given
            TeamEntity team = TeamEntity.builder()
                    .slug(TEAM_SLUG).name("画像チーム").template("sports")
                    .visibility(TeamEntity.Visibility.PUBLIC)
                    .iconUrl(ICON_KEY).bannerUrl(BANNER_KEY).mapEmbedUrl(MAP_EMBED)
                    .build();
            given(teamRepository.findBySlugAndDeletedAtIsNull(TEAM_SLUG)).willReturn(Optional.of(team));
            stubCommonCounts();
            given(mediaUrlResolver.resolve(ICON_KEY)).willReturn(SIGNED_ICON);
            given(mediaUrlResolver.resolve(BANNER_KEY)).willReturn(SIGNED_BANNER);

            // When
            TeamResponse res = service.getTeam(TEAM_SLUG).getData();

            // Then: icon/banner は署名付き表示 URL へ解決される
            assertThat(res.getMetadata().iconUrl()).isEqualTo(SIGNED_ICON);
            assertThat(res.getMetadata().bannerUrl()).isEqualTo(SIGNED_BANNER);
            // AC-10: mapEmbedUrl は R2 キーではないため resolver を通さず素通し
            assertThat(res.getMetadata().mapEmbedUrl()).isEqualTo(MAP_EMBED);
            verify(mediaUrlResolver, org.mockito.Mockito.never()).resolve(MAP_EMBED);
        }

        @Test
        @DisplayName("AC-7: DB の icon/banner が null ならレスポンスも null（resolver(null)→null 経由）")
        void toResponse_DBがnullならnull() {
            // Given: icon/banner 未設定（null）
            TeamEntity team = TeamEntity.builder()
                    .slug(TEAM_SLUG).name("画像なしチーム").template("sports")
                    .visibility(TeamEntity.Visibility.PUBLIC)
                    .build();
            given(teamRepository.findBySlugAndDeletedAtIsNull(TEAM_SLUG)).willReturn(Optional.of(team));
            stubCommonCounts();
            // resolver はモック既定で null を返す（resolve(null)→null の縮退を模す）

            // When
            TeamResponse res = service.getTeam(TEAM_SLUG).getData();

            // Then
            assertThat(res.getMetadata().iconUrl()).isNull();
            assertThat(res.getMetadata().bannerUrl()).isNull();
        }

        @Test
        @DisplayName("AC-6(公開詳細): getPublicTeam の icon/banner も解決値・mapEmbedUrl は素通し")
        void getPublicTeam_解決値が乗る() {
            // Given
            TeamEntity team = TeamEntity.builder()
                    .name("公開画像店舗").template("salon")
                    .visibility(TeamEntity.Visibility.PUBLIC)
                    .iconUrl(ICON_KEY).bannerUrl(BANNER_KEY).mapEmbedUrl(MAP_EMBED)
                    .build();
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));
            given(mediaUrlResolver.resolve(ICON_KEY)).willReturn(SIGNED_ICON);
            given(mediaUrlResolver.resolve(BANNER_KEY)).willReturn(SIGNED_BANNER);

            // When
            var dto = service.getPublicTeam(TEAM_ID);

            // Then
            assertThat(dto.iconUrl()).isEqualTo(SIGNED_ICON);
            assertThat(dto.bannerUrl()).isEqualTo(SIGNED_BANNER);
            assertThat(dto.mapEmbedUrl()).isEqualTo(MAP_EMBED);
            verify(mediaUrlResolver, org.mockito.Mockito.never()).resolve(MAP_EMBED);
        }
    }
}
