package com.mannschaft.app.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.dto.CreateOrganizationRequest;
import com.mannschaft.app.organization.dto.OrganizationResponse;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.organization.repository.OrganizationSlugHistoryRepository;
import com.mannschaft.app.organization.service.OrganizationHierarchyService;
import com.mannschaft.app.organization.service.OrganizationMembershipService;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 組織作成時のユーザー任意 slug（村方式統一）に関する単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationService ユーザー任意 slug 単体テスト")
class OrganizationSlugServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long ADMIN_ROLE_ID = 100L;

    @Mock private OrganizationRepository organizationRepository;
    @Mock private OrganizationSlugHistoryRepository organizationSlugHistoryRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private InviteTokenRepository inviteTokenRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private OrganizationMembershipService organizationMembershipService;
    @Mock private OrganizationHierarchyService organizationHierarchyService;
    @Mock private MembershipService membershipService;
    @InjectMocks private OrganizationService organizationService;

    private void givenCreateScaffold(String name) {
        lenient().when(organizationRepository.existsByName(name)).thenReturn(false);
        RoleEntity adminRole = RoleEntity.builder()
                .id(ADMIN_ROLE_ID).name("ADMIN").displayName("管理者").priority(2).isSystem(true).build();
        lenient().when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(adminRole));
        lenient().when(organizationRepository.save(any(OrganizationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(userRoleRepository.save(any(UserRoleEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("createOrganization: slug 指定あり")
    class WithUserSlug {

        @Test
        @DisplayName("有効な slug を指定すると採用される")
        void 有効slug採用() {
            givenCreateScaffold("テスト組織");
            given(organizationRepository.existsBySlugAndDeletedAtIsNull("my-org")).willReturn(false);

            CreateOrganizationRequest req = new CreateOrganizationRequest(
                    "テスト組織", "SCHOOL", null, null, null, null, "my-org");

            ApiResponse<OrganizationResponse> result = organizationService.createOrganization(USER_ID, req);

            assertThat(result.getData().getSlug()).isEqualTo("my-org");
        }

        @Test
        @DisplayName("形式不正 slug は ORG_060")
        void 形式不正() {
            givenCreateScaffold("テスト組織");

            CreateOrganizationRequest req = new CreateOrganizationRequest(
                    "テスト組織", "SCHOOL", null, null, null, null, "Bad_Slug");

            assertThatThrownBy(() -> organizationService.createOrganization(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_060"));
        }

        @Test
        @DisplayName("予約語 slug は ORG_061")
        void 予約語() {
            givenCreateScaffold("テスト組織");

            CreateOrganizationRequest req = new CreateOrganizationRequest(
                    "テスト組織", "SCHOOL", null, null, null, null, "settings");

            assertThatThrownBy(() -> organizationService.createOrganization(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_061"));
        }

        @Test
        @DisplayName("重複 slug は ORG_062")
        void 重複() {
            givenCreateScaffold("テスト組織");
            given(organizationRepository.existsBySlugAndDeletedAtIsNull("taken-org")).willReturn(true);

            CreateOrganizationRequest req = new CreateOrganizationRequest(
                    "テスト組織", "SCHOOL", null, null, null, null, "taken-org");

            assertThatThrownBy(() -> organizationService.createOrganization(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_062"));
        }
    }

    @Nested
    @DisplayName("createOrganization: slug 未指定（後方互換）")
    class WithoutUserSlug {

        @Test
        @DisplayName("slug 未指定なら名前から自動生成される")
        void 自動生成フォールバック() {
            givenCreateScaffold("My Org");
            given(organizationRepository.existsBySlugAndDeletedAtIsNull("my-org")).willReturn(false);

            CreateOrganizationRequest req = new CreateOrganizationRequest(
                    "My Org", "SCHOOL", null, null, null, null, null);

            ApiResponse<OrganizationResponse> result = organizationService.createOrganization(USER_ID, req);

            assertThat(result.getData().getSlug()).isEqualTo("my-org");
        }
    }

    @Nested
    @DisplayName("checkSlugAvailability")
    class Availability {

        @Test
        @DisplayName("有効・未使用 slug は available=true")
        void 利用可能() {
            given(organizationRepository.existsBySlugAndDeletedAtIsNull("free-org")).willReturn(false);

            var res = organizationService.checkSlugAvailability("free-org");

            assertThat(res.available()).isTrue();
            assertThat(res.reason()).isNull();
        }

        @Test
        @DisplayName("重複は SLUG_ALREADY_TAKEN")
        void 重複は利用不可() {
            given(organizationRepository.existsBySlugAndDeletedAtIsNull("taken")).willReturn(true);

            var res = organizationService.checkSlugAvailability("taken");

            assertThat(res.available()).isFalse();
            assertThat(res.reason()).isEqualTo("SLUG_ALREADY_TAKEN");
        }

        @Test
        @DisplayName("予約語は SLUG_RESERVED")
        void 予約語は利用不可() {
            var res = organizationService.checkSlugAvailability("search");

            assertThat(res.available()).isFalse();
            assertThat(res.reason()).isEqualTo("SLUG_RESERVED");
        }

        @Test
        @DisplayName("形式不正は SLUG_INVALID_FORMAT")
        void 形式不正は利用不可() {
            var res = organizationService.checkSlugAvailability("ab");

            assertThat(res.available()).isFalse();
            assertThat(res.reason()).isEqualTo("SLUG_INVALID_FORMAT");
        }
    }
}
