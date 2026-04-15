package com.mannschaft.app.organization;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.dto.CreateCustomFieldRequest;
import com.mannschaft.app.organization.dto.CreateOfficerRequest;
import com.mannschaft.app.organization.dto.CustomFieldResponse;
import com.mannschaft.app.organization.dto.OfficerResponse;
import com.mannschaft.app.organization.dto.OrganizationProfileResponse;
import com.mannschaft.app.organization.dto.ReorderRequest;
import com.mannschaft.app.organization.dto.UpdateOfficerRequest;
import com.mannschaft.app.organization.dto.UpdateOrgProfileRequest;
import com.mannschaft.app.organization.entity.OrganizationCustomFieldEntity;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.entity.OrganizationOfficerEntity;
import com.mannschaft.app.organization.repository.OrganizationCustomFieldRepository;
import com.mannschaft.app.organization.repository.OrganizationOfficerRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.organization.service.OrganizationExtendedProfileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link OrganizationExtendedProfileService} の単体テスト。
 * 拡張プロフィール・役員・カスタムフィールドのCRUDを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationExtendedProfileService 単体テスト")
class OrganizationExtendedProfileServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long ORG_ID = 10L;
    private static final Long OFFICER_ID = 100L;
    private static final Long FIELD_ID = 200L;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private OrganizationOfficerRepository officerRepository;

    @Mock
    private OrganizationCustomFieldRepository customFieldRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private OrganizationExtendedProfileService service;

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
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            OrganizationEntity org = buildOrg(OrganizationEntity.Visibility.PUBLIC, null);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));
            given(organizationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            UpdateOrgProfileRequest req = new UpdateOrgProfileRequest();
            req.setHomepageUrl("https://example.com");
            req.setEstablishedDate(LocalDate.of(2000, 1, 1));
            req.setEstablishedDatePrecision(EstablishedDatePrecision.FULL);
            req.setPhilosophy("チーム理念");

            // Act
            ApiResponse<OrganizationProfileResponse> result = service.updateProfile(USER_ID, ORG_ID, req);

            // Assert
            assertThat(result.getData()).isNotNull();
            verify(organizationRepository).save(any());
        }

        @Test
        @DisplayName("homepage_url が http/https 以外の場合 ORG_040 をスローする")
        void 不正なURL_ORG040() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            OrganizationEntity org = buildOrg(OrganizationEntity.Visibility.PUBLIC, null);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));

            UpdateOrgProfileRequest req = new UpdateOrgProfileRequest();
            req.setHomepageUrl("ftp://invalid.com");

            // Act & Assert
            assertThatThrownBy(() -> service.updateProfile(USER_ID, ORG_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_040"));
        }

        @Test
        @DisplayName("established_date だけ指定して precision なしの場合 ORG_045 をスローする")
        void 日付のみ指定でprecisionなし_ORG045() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            OrganizationEntity org = buildOrg(OrganizationEntity.Visibility.PUBLIC, null);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));

            UpdateOrgProfileRequest req = new UpdateOrgProfileRequest();
            req.setEstablishedDate(LocalDate.of(2000, 1, 1));
            // establishedDatePrecision は null

            // Act & Assert
            assertThatThrownBy(() -> service.updateProfile(USER_ID, ORG_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_045"));
        }

        @Test
        @DisplayName("established_date_precision だけ指定して date なしの場合 ORG_045 をスローする")
        void precisionのみ指定で日付なし_ORG045() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            OrganizationEntity org = buildOrg(OrganizationEntity.Visibility.PUBLIC, null);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));

            UpdateOrgProfileRequest req = new UpdateOrgProfileRequest();
            // establishedDate は null
            req.setEstablishedDatePrecision(EstablishedDatePrecision.YEAR);

            // Act & Assert
            assertThatThrownBy(() -> service.updateProfile(USER_ID, ORG_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_045"));
        }

        @Test
        @DisplayName("philosophy に HTML タグが含まれる場合 ORG_046 をスローする")
        void philosophyにHTMLタグ_ORG046() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            OrganizationEntity org = buildOrg(OrganizationEntity.Visibility.PUBLIC, null);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));

            UpdateOrgProfileRequest req = new UpdateOrgProfileRequest();
            req.setPhilosophy("<script>alert('xss')</script>");

            // Act & Assert
            assertThatThrownBy(() -> service.updateProfile(USER_ID, ORG_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_046"));
        }

        @Test
        @DisplayName("philosophy が 2001 文字の場合 ORG_046 をスローする")
        void philosophy2001文字_ORG046() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            OrganizationEntity org = buildOrg(OrganizationEntity.Visibility.PUBLIC, null);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));

            UpdateOrgProfileRequest req = new UpdateOrgProfileRequest();
            req.setPhilosophy("あ".repeat(2001));

            // Act & Assert
            assertThatThrownBy(() -> service.updateProfile(USER_ID, ORG_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_046"));
        }

        @Test
        @DisplayName("権限がない場合 ORG_048 をスローする")
        void 権限なし_ORG048() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

            UpdateOrgProfileRequest req = new UpdateOrgProfileRequest();

            // Act & Assert
            assertThatThrownBy(() -> service.updateProfile(USER_ID, ORG_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_048"));
        }

        @Test
        @DisplayName("組織が存在しない場合 ORG_001 をスローする")
        void 組織が存在しない_ORG001() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.empty());

            UpdateOrgProfileRequest req = new UpdateOrgProfileRequest();

            // Act & Assert
            assertThatThrownBy(() -> service.updateProfile(USER_ID, ORG_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_001"));
        }
    }

    // ========================================
    // getOfficers
    // ========================================

    @Nested
    @DisplayName("getOfficers")
    class GetOfficers {

        @Test
        @DisplayName("PRIVATE 組織かつ非メンバーの場合 ORG_048 をスローする")
        void PRIVATE組織非メンバー_ORG048() {
            // Arrange
            OrganizationEntity org = buildOrg(OrganizationEntity.Visibility.PRIVATE, null);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));
            given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

            // Act & Assert
            assertThatThrownBy(() -> service.getOfficers(USER_ID, ORG_ID, false))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_048"));
        }

        @Test
        @DisplayName("profile_visibility.officers=false かつ非メンバーの場合、空リストを返す")
        void officers可視性false非メンバー_空リスト() {
            // Arrange
            ProfileVisibility visibility = new ProfileVisibility(true, true, true, false, true);
            OrganizationEntity org = buildOrg(OrganizationEntity.Visibility.PUBLIC, visibility);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));
            given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

            // Act
            ApiResponse<List<OfficerResponse>> result = service.getOfficers(USER_ID, ORG_ID, false);

            // Assert
            assertThat(result.getData()).isEmpty();
        }

        @Test
        @DisplayName("visibilityPreview=true かつ ADMIN 以外の場合 ORG_048 をスローする")
        void visibilityPreviewtrueでADMIN以外_ORG048() {
            // Arrange
            OrganizationEntity org = buildOrg(OrganizationEntity.Visibility.PUBLIC, null);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));
            given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

            // Act & Assert
            assertThatThrownBy(() -> service.getOfficers(USER_ID, ORG_ID, true))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_048"));
        }

        @Test
        @DisplayName("visibilityPreview=true かつ ADMIN の場合、is_visible=false の役員も含めて全件返す")
        void visibilityPreviewtrueでADMIN_全件返却() {
            // Arrange
            OrganizationEntity org = buildOrg(OrganizationEntity.Visibility.PUBLIC, null);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));
            given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);

            OrganizationOfficerEntity visibleOfficer = buildOfficer(OFFICER_ID, ORG_ID, "田中", 1, true);
            OrganizationOfficerEntity hiddenOfficer = buildOfficer(OFFICER_ID + 1, ORG_ID, "鈴木", 2, false);
            given(officerRepository.findByOrganizationIdOrderByDisplayOrderAsc(ORG_ID))
                    .willReturn(List.of(visibleOfficer, hiddenOfficer));

            // Act
            ApiResponse<List<OfficerResponse>> result = service.getOfficers(USER_ID, ORG_ID, true);

            // Assert
            assertThat(result.getData()).hasSize(2);
        }

        @Test
        @DisplayName("通常取得: is_visible=false の役員は非メンバーにフィルタリングされる")
        void 通常取得で非表示役員がフィルタリング() {
            // Arrange
            OrganizationEntity org = buildOrg(OrganizationEntity.Visibility.PUBLIC, null);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));
            given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

            OrganizationOfficerEntity visibleOfficer = buildOfficer(OFFICER_ID, ORG_ID, "田中", 1, true);
            OrganizationOfficerEntity hiddenOfficer = buildOfficer(OFFICER_ID + 1, ORG_ID, "鈴木", 2, false);
            given(officerRepository.findByOrganizationIdOrderByDisplayOrderAsc(ORG_ID))
                    .willReturn(List.of(visibleOfficer, hiddenOfficer));

            // Act
            ApiResponse<List<OfficerResponse>> result = service.getOfficers(USER_ID, ORG_ID, false);

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
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(
                    buildOrg(OrganizationEntity.Visibility.PUBLIC, null)));
            given(officerRepository.countByOrganizationId(ORG_ID)).willReturn(0);

            OrganizationOfficerEntity existing = buildOfficer(OFFICER_ID, ORG_ID, "田中", 3, true);
            given(officerRepository.findByOrganizationIdOrderByDisplayOrderAsc(ORG_ID))
                    .willReturn(List.of(existing));
            given(officerRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            CreateOfficerRequest req = new CreateOfficerRequest();
            req.setName("佐藤");
            req.setTitle("監督");

            // Act
            ApiResponse<OfficerResponse> result = service.createOfficer(USER_ID, ORG_ID, req);

            // Assert
            assertThat(result.getData()).isNotNull();
            assertThat(result.getData().getDisplayOrder()).isEqualTo(4);
        }

        @Test
        @DisplayName("50件を超える場合 ORG_041 をスローする")
        void 上限超過_ORG041() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(
                    buildOrg(OrganizationEntity.Visibility.PUBLIC, null)));
            given(officerRepository.countByOrganizationId(ORG_ID)).willReturn(50);

            CreateOfficerRequest req = new CreateOfficerRequest();
            req.setName("佐藤");
            req.setTitle("監督");

            // Act & Assert
            assertThatThrownBy(() -> service.createOfficer(USER_ID, ORG_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_041"));
        }

        @Test
        @DisplayName("name に HTML タグが含まれる場合 ORG_046 をスローする")
        void nameにHTMLタグ_ORG046() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(
                    buildOrg(OrganizationEntity.Visibility.PUBLIC, null)));
            given(officerRepository.countByOrganizationId(ORG_ID)).willReturn(0);

            CreateOfficerRequest req = new CreateOfficerRequest();
            req.setName("<b>悪意のある名前</b>");
            req.setTitle("監督");

            // Act & Assert
            assertThatThrownBy(() -> service.createOfficer(USER_ID, ORG_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_046"));
        }

        @Test
        @DisplayName("権限がない場合 ORG_048 をスローする")
        void 権限なし_ORG048() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

            CreateOfficerRequest req = new CreateOfficerRequest();
            req.setName("佐藤");
            req.setTitle("監督");

            // Act & Assert
            assertThatThrownBy(() -> service.createOfficer(USER_ID, ORG_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_048"));
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
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(
                    buildOrg(OrganizationEntity.Visibility.PUBLIC, null)));
            OrganizationOfficerEntity officer = buildOfficer(OFFICER_ID, ORG_ID, "田中", 1, true);
            given(officerRepository.findById(OFFICER_ID)).willReturn(Optional.of(officer));
            given(officerRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            UpdateOfficerRequest req = new UpdateOfficerRequest();
            req.setName("新田中");
            req.setTitle("コーチ");

            // Act
            ApiResponse<OfficerResponse> result = service.updateOfficer(USER_ID, ORG_ID, OFFICER_ID, req);

            // Assert
            assertThat(result.getData()).isNotNull();
        }

        @Test
        @DisplayName("別の組織の役員を更新しようとする場合 ORG_050 をスローする")
        void 別組織の役員更新_ORG050() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(
                    buildOrg(OrganizationEntity.Visibility.PUBLIC, null)));

            // 別の組織に属する役員を返す
            Long OTHER_ORG_ID = 999L;
            OrganizationOfficerEntity officer = buildOfficer(OFFICER_ID, OTHER_ORG_ID, "田中", 1, true);
            given(officerRepository.findById(OFFICER_ID)).willReturn(Optional.of(officer));

            UpdateOfficerRequest req = new UpdateOfficerRequest();
            req.setName("新田中");

            // Act & Assert
            assertThatThrownBy(() -> service.updateOfficer(USER_ID, ORG_ID, OFFICER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_050"));
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
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(
                    buildOrg(OrganizationEntity.Visibility.PUBLIC, null)));
            OrganizationOfficerEntity officer = buildOfficer(OFFICER_ID, ORG_ID, "田中", 1, true);
            given(officerRepository.findById(OFFICER_ID)).willReturn(Optional.of(officer));

            // Act
            service.deleteOfficer(USER_ID, ORG_ID, OFFICER_ID);

            // Assert
            verify(officerRepository).delete(officer);
        }

        @Test
        @DisplayName("役員が存在しない場合 ORG_050 をスローする")
        void 役員が存在しない_ORG050() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(
                    buildOrg(OrganizationEntity.Visibility.PUBLIC, null)));
            given(officerRepository.findById(OFFICER_ID)).willReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> service.deleteOfficer(USER_ID, ORG_ID, OFFICER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_050"));
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
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(
                    buildOrg(OrganizationEntity.Visibility.PUBLIC, null)));

            OrganizationOfficerEntity officer1 = buildOfficer(OFFICER_ID, ORG_ID, "田中", 1, true);
            OrganizationOfficerEntity officer2 = buildOfficer(OFFICER_ID + 1, ORG_ID, "鈴木", 2, true);
            given(officerRepository.findByOrganizationIdOrderByDisplayOrderAsc(ORG_ID))
                    .willReturn(List.of(officer1, officer2));

            ReorderRequest req = buildReorderRequest(
                    List.of(OFFICER_ID, OFFICER_ID + 1),
                    List.of(2, 1));

            // Act
            service.reorderOfficers(USER_ID, ORG_ID, req);

            // Assert
            verify(officerRepository).saveAll(any());
        }

        @Test
        @DisplayName("陳腐化リクエスト（IDs 不一致）の場合 ORG_042 をスローする")
        void 陳腐化リクエスト_ORG042() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(
                    buildOrg(OrganizationEntity.Visibility.PUBLIC, null)));

            OrganizationOfficerEntity officer1 = buildOfficer(OFFICER_ID, ORG_ID, "田中", 1, true);
            given(officerRepository.findByOrganizationIdOrderByDisplayOrderAsc(ORG_ID))
                    .willReturn(List.of(officer1));

            // 存在しない ID を含むリクエスト
            ReorderRequest req = buildReorderRequest(
                    List.of(OFFICER_ID, 999L),
                    List.of(1, 2));

            // Act & Assert
            assertThatThrownBy(() -> service.reorderOfficers(USER_ID, ORG_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_042"));
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
            OrganizationEntity org = buildOrg(OrganizationEntity.Visibility.PUBLIC, null);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));
            given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(customFieldRepository.findByOrganizationIdOrderByDisplayOrderAsc(ORG_ID))
                    .willReturn(List.of());

            // Act
            ApiResponse<List<CustomFieldResponse>> result = service.getCustomFields(USER_ID, ORG_ID, false);

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
        @DisplayName("20件を超える場合 ORG_043 をスローする")
        void 上限超過_ORG043() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(
                    buildOrg(OrganizationEntity.Visibility.PUBLIC, null)));
            given(customFieldRepository.countByOrganizationId(ORG_ID)).willReturn(20);

            CreateCustomFieldRequest req = new CreateCustomFieldRequest();
            req.setLabel("項目名");
            req.setValue("値");

            // Act & Assert
            assertThatThrownBy(() -> service.createCustomField(USER_ID, ORG_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_043"));
        }
    }

    // ========================================
    // reorderCustomFields
    // ========================================

    @Nested
    @DisplayName("reorderCustomFields")
    class ReorderCustomFields {

        @Test
        @DisplayName("陳腐化リクエスト（IDs 不一致）の場合 ORG_044 をスローする")
        void 陳腐化リクエスト_ORG044() {
            // Arrange
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(
                    buildOrg(OrganizationEntity.Visibility.PUBLIC, null)));

            OrganizationCustomFieldEntity field = buildCustomField(FIELD_ID, ORG_ID, "項目", "値", 1, true);
            given(customFieldRepository.findByOrganizationIdOrderByDisplayOrderAsc(ORG_ID))
                    .willReturn(List.of(field));

            // 存在しない ID を含むリクエスト
            ReorderRequest req = buildReorderRequest(
                    List.of(FIELD_ID, 999L),
                    List.of(1, 2));

            // Act & Assert
            assertThatThrownBy(() -> service.reorderCustomFields(USER_ID, ORG_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_044"));
        }
    }

    // ========================================
    // テストヘルパー（private）
    // ========================================

    /**
     * OrganizationEntity のテスト用ビルダー。
     */
    private OrganizationEntity buildOrg(OrganizationEntity.Visibility visibility, ProfileVisibility profileVisibility) {
        return OrganizationEntity.builder()
                .name("テスト組織")
                .orgType(OrganizationEntity.OrgType.COMMUNITY)
                .visibility(visibility)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(false)
                .profileVisibility(profileVisibility)
                .build();
    }

    /**
     * OrganizationOfficerEntity のテスト用ビルダー。
     * BaseEntity の id は ReflectionTestUtils でセットする。
     */
    private OrganizationOfficerEntity buildOfficer(Long id, Long orgId, String name, int displayOrder, boolean isVisible) {
        OrganizationOfficerEntity officer = OrganizationOfficerEntity.builder()
                .organizationId(orgId)
                .name(name)
                .title("監督")
                .displayOrder(displayOrder)
                .isVisible(isVisible)
                .build();
        ReflectionTestUtils.setField(officer, "id", id);
        return officer;
    }

    /**
     * OrganizationCustomFieldEntity のテスト用ビルダー。
     * BaseEntity の id は ReflectionTestUtils でセットする。
     */
    private OrganizationCustomFieldEntity buildCustomField(Long id, Long orgId, String label, String value, int displayOrder, boolean isVisible) {
        OrganizationCustomFieldEntity field = OrganizationCustomFieldEntity.builder()
                .organizationId(orgId)
                .label(label)
                .value(value)
                .displayOrder(displayOrder)
                .isVisible(isVisible)
                .build();
        ReflectionTestUtils.setField(field, "id", id);
        return field;
    }

    /**
     * ReorderRequest のテスト用ビルダー。
     */
    private ReorderRequest buildReorderRequest(List<Long> ids, List<Integer> orders) {
        ReorderRequest req = new ReorderRequest();
        List<ReorderRequest.OrderItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            ReorderRequest.OrderItem item = new ReorderRequest.OrderItem();
            item.setId(ids.get(i));
            item.setDisplayOrder(orders.get(i));
            items.add(item);
        }
        req.setOrders(items);
        return req;
    }
}
