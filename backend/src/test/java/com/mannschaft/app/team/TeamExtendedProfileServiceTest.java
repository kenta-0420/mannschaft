package com.mannschaft.app.team;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.EstablishedDatePrecision;
import com.mannschaft.app.organization.ProfileVisibility;
import com.mannschaft.app.team.dto.CreateTeamCustomFieldRequest;
import com.mannschaft.app.team.dto.CreateTeamOfficerRequest;
import com.mannschaft.app.team.dto.TeamCustomFieldResponse;
import com.mannschaft.app.team.dto.TeamOfficerResponse;
import com.mannschaft.app.team.dto.TeamProfileResponse;
import com.mannschaft.app.team.dto.TeamReorderRequest;
import com.mannschaft.app.team.dto.UpdateTeamOfficerRequest;
import com.mannschaft.app.team.dto.UpdateTeamProfileRequest;
import com.mannschaft.app.team.entity.TeamCustomFieldEntity;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOfficerEntity;
import com.mannschaft.app.team.repository.TeamCustomFieldRepository;
import com.mannschaft.app.team.repository.TeamOfficerRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.service.TeamExtendedProfileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link TeamExtendedProfileService} の単体テスト。
 * 拡張プロフィール・役員・カスタムフィールドのCRUDを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamExtendedProfileService 単体テスト")
class TeamExtendedProfileServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long OFFICER_ID = 100L;
    private static final Long FIELD_ID = 200L;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private TeamOfficerRepository officerRepository;

    @Mock
    private TeamCustomFieldRepository customFieldRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private TeamExtendedProfileService service;

    // ========================================
    // updateProfile
    // ========================================

    @Nested
    @DisplayName("updateProfile")
    class UpdateProfile {

        @Test
        @DisplayName("URL・established_date・philosophy をすべて指定して正常更新できる")
        void 正常更新() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            TeamEntity team = buildTeam(TeamEntity.Visibility.PUBLIC, null);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));
            given(teamRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            UpdateTeamProfileRequest req = new UpdateTeamProfileRequest();
            req.setHomepageUrl("https://example.com");
            req.setEstablishedDate(LocalDate.of(2000, 1, 1));
            req.setEstablishedDatePrecision(EstablishedDatePrecision.FULL);
            req.setPhilosophy("チーム理念");

            // Act
            ApiResponse<TeamProfileResponse> result = service.updateProfile(USER_ID, TEAM_ID, req);

            // Assert
            assertThat(result.getData()).isNotNull();
            verify(teamRepository).save(any());
        }

        @Test
        @DisplayName("homepage_url が http/https 以外の場合 TEAM_040 をスローする")
        void 不正なURL_TEAM040() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            TeamEntity team = buildTeam(TeamEntity.Visibility.PUBLIC, null);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));

            UpdateTeamProfileRequest req = new UpdateTeamProfileRequest();
            req.setHomepageUrl("ftp://invalid.com");

            // Act & Assert
            assertThatThrownBy(() -> service.updateProfile(USER_ID, TEAM_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_040"));
        }

        @Test
        @DisplayName("established_date だけ指定して precision なしの場合 TEAM_045 をスローする")
        void 日付のみ指定でprecisionなし_TEAM045() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            TeamEntity team = buildTeam(TeamEntity.Visibility.PUBLIC, null);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));

            UpdateTeamProfileRequest req = new UpdateTeamProfileRequest();
            req.setEstablishedDate(LocalDate.of(2000, 1, 1));
            // establishedDatePrecision は null

            // Act & Assert
            assertThatThrownBy(() -> service.updateProfile(USER_ID, TEAM_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_045"));
        }

        @Test
        @DisplayName("established_date_precision だけ指定して date なしの場合 TEAM_045 をスローする")
        void precisionのみ指定で日付なし_TEAM045() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            TeamEntity team = buildTeam(TeamEntity.Visibility.PUBLIC, null);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));

            UpdateTeamProfileRequest req = new UpdateTeamProfileRequest();
            // establishedDate は null
            req.setEstablishedDatePrecision(EstablishedDatePrecision.YEAR);

            // Act & Assert
            assertThatThrownBy(() -> service.updateProfile(USER_ID, TEAM_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_045"));
        }

        @Test
        @DisplayName("philosophy に HTML タグが含まれる場合 TEAM_046 をスローする")
        void philosophyにHTMLタグ_TEAM046() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            TeamEntity team = buildTeam(TeamEntity.Visibility.PUBLIC, null);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));

            UpdateTeamProfileRequest req = new UpdateTeamProfileRequest();
            req.setPhilosophy("<script>alert('xss')</script>");

            // Act & Assert
            assertThatThrownBy(() -> service.updateProfile(USER_ID, TEAM_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_046"));
        }

        @Test
        @DisplayName("philosophy が 2001 文字の場合 TEAM_046 をスローする")
        void philosophy2001文字_TEAM046() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            TeamEntity team = buildTeam(TeamEntity.Visibility.PUBLIC, null);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));

            UpdateTeamProfileRequest req = new UpdateTeamProfileRequest();
            req.setPhilosophy("あ".repeat(2001));

            // Act & Assert
            assertThatThrownBy(() -> service.updateProfile(USER_ID, TEAM_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_046"));
        }

        @Test
        @DisplayName("権限がない場合 TEAM_048 をスローする")
        void 権限なし_TEAM048() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            UpdateTeamProfileRequest req = new UpdateTeamProfileRequest();

            // Act & Assert
            assertThatThrownBy(() -> service.updateProfile(USER_ID, TEAM_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_048"));
        }

        @Test
        @DisplayName("チームが存在しない場合 TEAM_001 をスローする")
        void チームが存在しない_TEAM001() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.empty());

            UpdateTeamProfileRequest req = new UpdateTeamProfileRequest();

            // Act & Assert
            assertThatThrownBy(() -> service.updateProfile(USER_ID, TEAM_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_001"));
        }
    }

    // ========================================
    // getOfficers
    // ========================================

    @Nested
    @DisplayName("getOfficers")
    class GetOfficers {

        @Test
        @DisplayName("PRIVATE チームかつ非メンバーの場合 TEAM_048 をスローする")
        void PRIVATE_TEAM非メンバー_TEAM048() {
            // Arrange
            TeamEntity team = buildTeam(TeamEntity.Visibility.PRIVATE, null);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            // Act & Assert
            assertThatThrownBy(() -> service.getOfficers(USER_ID, TEAM_ID, false))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_048"));
        }

        @Test
        @DisplayName("profile_visibility.officers=false かつ非メンバーの場合、空リストを返す")
        void officers可視性false非メンバー_空リスト() {
            // Arrange
            ProfileVisibility visibility = new ProfileVisibility(true, true, true, false, true);
            TeamEntity team = buildTeam(TeamEntity.Visibility.PUBLIC, visibility);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            // Act
            ApiResponse<List<TeamOfficerResponse>> result = service.getOfficers(USER_ID, TEAM_ID, false);

            // Assert
            assertThat(result.getData()).isEmpty();
        }

        @Test
        @DisplayName("visibilityPreview=true かつ ADMIN 以外の場合 TEAM_048 をスローする")
        void visibilityPreviewtrueでADMIN以外_TEAM048() {
            // Arrange
            TeamEntity team = buildTeam(TeamEntity.Visibility.PUBLIC, null);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            // Act & Assert
            assertThatThrownBy(() -> service.getOfficers(USER_ID, TEAM_ID, true))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_048"));
        }

        @Test
        @DisplayName("visibilityPreview=true かつ ADMIN の場合、is_visible=false の役員も含めて全件返す")
        void visibilityPreviewtrueでADMIN_全件返却() {
            // Arrange
            TeamEntity team = buildTeam(TeamEntity.Visibility.PUBLIC, null);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            TeamOfficerEntity visibleOfficer = buildOfficer(OFFICER_ID, TEAM_ID, "田中", 1, true);
            TeamOfficerEntity hiddenOfficer = buildOfficer(OFFICER_ID + 1, TEAM_ID, "鈴木", 2, false);
            given(officerRepository.findByTeamIdOrderByDisplayOrderAsc(TEAM_ID))
                    .willReturn(List.of(visibleOfficer, hiddenOfficer));

            // Act
            ApiResponse<List<TeamOfficerResponse>> result = service.getOfficers(USER_ID, TEAM_ID, true);

            // Assert
            assertThat(result.getData()).hasSize(2);
        }

        @Test
        @DisplayName("通常取得: is_visible=false の役員は非メンバーにフィルタリングされる")
        void 通常取得で非表示役員がフィルタリング() {
            // Arrange
            TeamEntity team = buildTeam(TeamEntity.Visibility.PUBLIC, null);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            TeamOfficerEntity visibleOfficer = buildOfficer(OFFICER_ID, TEAM_ID, "田中", 1, true);
            TeamOfficerEntity hiddenOfficer = buildOfficer(OFFICER_ID + 1, TEAM_ID, "鈴木", 2, false);
            given(officerRepository.findByTeamIdOrderByDisplayOrderAsc(TEAM_ID))
                    .willReturn(List.of(visibleOfficer, hiddenOfficer));

            // Act
            ApiResponse<List<TeamOfficerResponse>> result = service.getOfficers(USER_ID, TEAM_ID, false);

            // Assert
            assertThat(result.getData()).hasSize(1);
            assertThat(result.getData().get(0).getName()).isEqualTo("田中");
        }
    }

    // ========================================
    // createOfficer
    // ========================================

    @Nested
    @DisplayName("createOfficer")
    class CreateOfficer {

        @Test
        @DisplayName("正常追加: displayOrder は既存最大 + 1 となる")
        void 正常追加() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(
                    buildTeam(TeamEntity.Visibility.PUBLIC, null)));
            given(officerRepository.countByTeamId(TEAM_ID)).willReturn(0);

            TeamOfficerEntity existing = buildOfficer(OFFICER_ID, TEAM_ID, "田中", 3, true);
            given(officerRepository.findByTeamIdOrderByDisplayOrderAsc(TEAM_ID))
                    .willReturn(List.of(existing));
            given(officerRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            CreateTeamOfficerRequest req = new CreateTeamOfficerRequest();
            req.setName("佐藤");
            req.setTitle("監督");

            // Act
            ApiResponse<TeamOfficerResponse> result = service.createOfficer(USER_ID, TEAM_ID, req);

            // Assert
            assertThat(result.getData()).isNotNull();
            assertThat(result.getData().getDisplayOrder()).isEqualTo(4);
        }

        @Test
        @DisplayName("50件を超える場合 TEAM_041 をスローする")
        void 上限超過_TEAM041() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(
                    buildTeam(TeamEntity.Visibility.PUBLIC, null)));
            given(officerRepository.countByTeamId(TEAM_ID)).willReturn(50);

            CreateTeamOfficerRequest req = new CreateTeamOfficerRequest();
            req.setName("佐藤");
            req.setTitle("監督");

            // Act & Assert
            assertThatThrownBy(() -> service.createOfficer(USER_ID, TEAM_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_041"));
        }

        @Test
        @DisplayName("name に HTML タグが含まれる場合 TEAM_046 をスローする")
        void nameにHTMLタグ_TEAM046() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(
                    buildTeam(TeamEntity.Visibility.PUBLIC, null)));
            given(officerRepository.countByTeamId(TEAM_ID)).willReturn(0);

            CreateTeamOfficerRequest req = new CreateTeamOfficerRequest();
            req.setName("<b>悪意のある名前</b>");
            req.setTitle("監督");

            // Act & Assert
            assertThatThrownBy(() -> service.createOfficer(USER_ID, TEAM_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_046"));
        }

        @Test
        @DisplayName("権限がない場合 TEAM_048 をスローする")
        void 権限なし_TEAM048() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            CreateTeamOfficerRequest req = new CreateTeamOfficerRequest();
            req.setName("佐藤");
            req.setTitle("監督");

            // Act & Assert
            assertThatThrownBy(() -> service.createOfficer(USER_ID, TEAM_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_048"));
        }
    }

    // ========================================
    // updateOfficer
    // ========================================

    @Nested
    @DisplayName("updateOfficer")
    class UpdateOfficer {

        @Test
        @DisplayName("正常更新できる")
        void 正常更新() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(
                    buildTeam(TeamEntity.Visibility.PUBLIC, null)));
            TeamOfficerEntity officer = buildOfficer(OFFICER_ID, TEAM_ID, "田中", 1, true);
            given(officerRepository.findById(OFFICER_ID)).willReturn(Optional.of(officer));
            given(officerRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            UpdateTeamOfficerRequest req = new UpdateTeamOfficerRequest();
            req.setName("新田中");
            req.setTitle("コーチ");

            // Act
            ApiResponse<TeamOfficerResponse> result = service.updateOfficer(USER_ID, TEAM_ID, OFFICER_ID, req);

            // Assert
            assertThat(result.getData()).isNotNull();
        }

        @Test
        @DisplayName("別のチームの役員を更新しようとする場合 TEAM_050 をスローする")
        void 別チームの役員更新_TEAM050() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(
                    buildTeam(TeamEntity.Visibility.PUBLIC, null)));

            // 別のチームに属する役員を返す
            Long OTHER_TEAM_ID = 999L;
            TeamOfficerEntity officer = buildOfficer(OFFICER_ID, OTHER_TEAM_ID, "田中", 1, true);
            given(officerRepository.findById(OFFICER_ID)).willReturn(Optional.of(officer));

            UpdateTeamOfficerRequest req = new UpdateTeamOfficerRequest();
            req.setName("新田中");

            // Act & Assert
            assertThatThrownBy(() -> service.updateOfficer(USER_ID, TEAM_ID, OFFICER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_050"));
        }
    }

    // ========================================
    // deleteOfficer
    // ========================================

    @Nested
    @DisplayName("deleteOfficer")
    class DeleteOfficer {

        @Test
        @DisplayName("正常削除できる")
        void 正常削除() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(
                    buildTeam(TeamEntity.Visibility.PUBLIC, null)));
            TeamOfficerEntity officer = buildOfficer(OFFICER_ID, TEAM_ID, "田中", 1, true);
            given(officerRepository.findById(OFFICER_ID)).willReturn(Optional.of(officer));

            // Act
            service.deleteOfficer(USER_ID, TEAM_ID, OFFICER_ID);

            // Assert
            verify(officerRepository).delete(officer);
        }

        @Test
        @DisplayName("役員が存在しない場合 TEAM_050 をスローする")
        void 役員が存在しない_TEAM050() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(
                    buildTeam(TeamEntity.Visibility.PUBLIC, null)));
            given(officerRepository.findById(OFFICER_ID)).willReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> service.deleteOfficer(USER_ID, TEAM_ID, OFFICER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_050"));
        }
    }

    // ========================================
    // reorderOfficers
    // ========================================

    @Nested
    @DisplayName("reorderOfficers")
    class ReorderOfficers {

        @Test
        @DisplayName("全 ID を網羅したリクエストで正常並び替えができる")
        void 正常並び替え() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(
                    buildTeam(TeamEntity.Visibility.PUBLIC, null)));

            TeamOfficerEntity officer1 = buildOfficer(OFFICER_ID, TEAM_ID, "田中", 1, true);
            TeamOfficerEntity officer2 = buildOfficer(OFFICER_ID + 1, TEAM_ID, "鈴木", 2, true);
            given(officerRepository.findByTeamIdOrderByDisplayOrderAsc(TEAM_ID))
                    .willReturn(List.of(officer1, officer2));

            TeamReorderRequest req = buildReorderRequest(
                    List.of(OFFICER_ID, OFFICER_ID + 1),
                    List.of(2, 1));

            // Act
            service.reorderOfficers(USER_ID, TEAM_ID, req);

            // Assert
            verify(officerRepository).saveAll(any());
        }

        @Test
        @DisplayName("陳腐化リクエスト（IDs 不一致）の場合 TEAM_042 をスローする")
        void 陳腐化リクエスト_TEAM042() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(
                    buildTeam(TeamEntity.Visibility.PUBLIC, null)));

            TeamOfficerEntity officer1 = buildOfficer(OFFICER_ID, TEAM_ID, "田中", 1, true);
            given(officerRepository.findByTeamIdOrderByDisplayOrderAsc(TEAM_ID))
                    .willReturn(List.of(officer1));

            // 存在しない ID を含むリクエスト
            TeamReorderRequest req = buildReorderRequest(
                    List.of(OFFICER_ID, 999L),
                    List.of(1, 2));

            // Act & Assert
            assertThatThrownBy(() -> service.reorderOfficers(USER_ID, TEAM_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_042"));
        }
    }

    // ========================================
    // getCustomFields
    // ========================================

    @Nested
    @DisplayName("getCustomFields")
    class GetCustomFields {

        @Test
        @DisplayName("カスタムフィールドが存在しない場合、空リストを返す")
        void カスタムフィールドが存在しない_空リスト() {
            // Arrange
            TeamEntity team = buildTeam(TeamEntity.Visibility.PUBLIC, null);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(customFieldRepository.findByTeamIdOrderByDisplayOrderAsc(TEAM_ID))
                    .willReturn(List.of());

            // Act
            ApiResponse<List<TeamCustomFieldResponse>> result = service.getCustomFields(USER_ID, TEAM_ID, false);

            // Assert
            assertThat(result.getData()).isEmpty();
        }
    }

    // ========================================
    // createCustomField
    // ========================================

    @Nested
    @DisplayName("createCustomField")
    class CreateCustomField {

        @Test
        @DisplayName("20件を超える場合 TEAM_043 をスローする")
        void 上限超過_TEAM043() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(
                    buildTeam(TeamEntity.Visibility.PUBLIC, null)));
            given(customFieldRepository.countByTeamId(TEAM_ID)).willReturn(20);

            CreateTeamCustomFieldRequest req = new CreateTeamCustomFieldRequest();
            req.setLabel("項目名");
            req.setValue("値");

            // Act & Assert
            assertThatThrownBy(() -> service.createCustomField(USER_ID, TEAM_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_043"));
        }
    }

    // ========================================
    // reorderCustomFields
    // ========================================

    @Nested
    @DisplayName("reorderCustomFields")
    class ReorderCustomFields {

        @Test
        @DisplayName("陳腐化リクエスト（IDs 不一致）の場合 TEAM_044 をスローする")
        void 陳腐化リクエスト_TEAM044() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(
                    buildTeam(TeamEntity.Visibility.PUBLIC, null)));

            TeamCustomFieldEntity field = buildCustomField(FIELD_ID, TEAM_ID, "項目", "値", 1, true);
            given(customFieldRepository.findByTeamIdOrderByDisplayOrderAsc(TEAM_ID))
                    .willReturn(List.of(field));

            // 存在しない ID を含むリクエスト
            TeamReorderRequest req = buildReorderRequest(
                    List.of(FIELD_ID, 999L),
                    List.of(1, 2));

            // Act & Assert
            assertThatThrownBy(() -> service.reorderCustomFields(USER_ID, TEAM_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_044"));
        }
    }

    // ========================================
    // テストヘルパー（private）
    // ========================================

    /**
     * TeamEntity のテスト用ビルダー。
     */
    private TeamEntity buildTeam(TeamEntity.Visibility visibility, ProfileVisibility profileVisibility) {
        return TeamEntity.builder()
                .name("テストチーム")
                .visibility(visibility)
                .supporterEnabled(false)
                .profileVisibility(profileVisibility)
                .build();
    }

    /**
     * TeamOfficerEntity のテスト用ビルダー。
     * BaseEntity の id は ReflectionTestUtils でセットする。
     */
    private TeamOfficerEntity buildOfficer(Long id, Long teamId, String name, int displayOrder, boolean isVisible) {
        TeamOfficerEntity officer = TeamOfficerEntity.builder()
                .teamId(teamId)
                .name(name)
                .title("監督")
                .displayOrder(displayOrder)
                .isVisible(isVisible)
                .build();
        ReflectionTestUtils.setField(officer, "id", id);
        return officer;
    }

    /**
     * TeamCustomFieldEntity のテスト用ビルダー。
     * BaseEntity の id は ReflectionTestUtils でセットする。
     */
    private TeamCustomFieldEntity buildCustomField(Long id, Long teamId, String label, String value, int displayOrder, boolean isVisible) {
        TeamCustomFieldEntity field = TeamCustomFieldEntity.builder()
                .teamId(teamId)
                .label(label)
                .value(value)
                .displayOrder(displayOrder)
                .isVisible(isVisible)
                .build();
        ReflectionTestUtils.setField(field, "id", id);
        return field;
    }

    /**
     * TeamReorderRequest のテスト用ビルダー。
     */
    private TeamReorderRequest buildReorderRequest(List<Long> ids, List<Integer> orders) {
        TeamReorderRequest req = new TeamReorderRequest();
        List<TeamReorderRequest.OrderItem> items = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            TeamReorderRequest.OrderItem item = new TeamReorderRequest.OrderItem();
            item.setId(ids.get(i));
            item.setDisplayOrder(orders.get(i));
            items.add(item);
        }
        req.setOrders(items);
        return req;
    }
}
