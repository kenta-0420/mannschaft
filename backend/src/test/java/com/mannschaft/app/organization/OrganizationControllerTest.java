package com.mannschaft.app.organization;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.VisibilityErrorCode;
import com.mannschaft.app.organization.controller.OrganizationController;
import com.mannschaft.app.organization.dto.CreateOrganizationRequest;
import com.mannschaft.app.organization.dto.OrganizationResponse;
import com.mannschaft.app.organization.dto.OrganizationSummaryResponse;
import com.mannschaft.app.organization.dto.UpdateOrganizationRequest;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.role.dto.BlockRequest;
import com.mannschaft.app.role.dto.BlockResponse;
import com.mannschaft.app.role.dto.CreateInviteTokenRequest;
import com.mannschaft.app.role.dto.EffectivePermissionsResponse;
import com.mannschaft.app.role.dto.InviteTokenResponse;
import com.mannschaft.app.role.dto.MemberResponse;
import com.mannschaft.app.role.dto.PermissionGroupRequest;
import com.mannschaft.app.role.dto.PermissionGroupResponse;
import com.mannschaft.app.role.dto.RoleChangeRequest;
import com.mannschaft.app.role.dto.UserPermissionGroupAssignRequest;
import com.mannschaft.app.role.service.BlockService;
import com.mannschaft.app.role.service.InviteService;
import com.mannschaft.app.role.service.PermissionGroupService;
import com.mannschaft.app.role.service.RoleService;
import com.mannschaft.app.supporter.dto.FollowStatusResponse;
import com.mannschaft.app.supporter.service.SupporterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link OrganizationController} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationController 単体テスト")
class OrganizationControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long ORG_ID = 10L;
    private static final String ORG_SLUG = "test-org";

    @Mock private OrganizationService organizationService;
    @Mock private RoleService roleService;
    @Mock private AccessControlService accessControlService;
    @Mock private InviteService inviteService;
    @Mock private PermissionGroupService permissionGroupService;
    @Mock private BlockService blockService;
    @Mock private SupporterService supporterService;
    @Mock private ContentVisibilityChecker contentVisibilityChecker;

    @InjectMocks
    private OrganizationController controller;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private OrganizationResponse orgResponse() {
        return OrganizationResponse.builder()
                .id(ORG_SLUG)
                .basicInfo(new OrganizationResponse.OrgBasicInfoDto(
                        "テスト組織", null, null, null))
                .hierarchy(new OrganizationResponse.OrgHierarchyDto("SCHOOL", null))
                .location(new OrganizationResponse.OrgLocationDto("東京都", "渋谷区"))
                .visibility(new OrganizationResponse.OrgVisibilityDto("PUBLIC", "NONE", false))
                .metadata(new OrganizationResponse.OrgMetadataDto(0L, 3, null, null))
                .timestamps(new OrganizationResponse.OrgTimestampsDto(null, LocalDateTime.now()))
                .build();
    }

    @Test
    @DisplayName("createOrganization: 201 Created")
    void createOrganization_201() {
        CreateOrganizationRequest req = new CreateOrganizationRequest(
                "テスト組織", "SCHOOL", "東京都", "渋谷区", "PUBLIC", null, null);
        given(organizationService.createOrganization(USER_ID, req)).willReturn(ApiResponse.of(orgResponse()));
        ResponseEntity<ApiResponse<OrganizationResponse>> resp = controller.createOrganization(req);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("createOrganization: 親組織の指定がなければ認可チェックを行わない（正常系の保全）")
    void createOrganization_親なしは認可チェックしない() {
        CreateOrganizationRequest req = new CreateOrganizationRequest(
                "テスト組織", "SCHOOL", "東京都", "渋谷区", "PUBLIC", null, null);
        given(organizationService.createOrganization(USER_ID, req)).willReturn(ApiResponse.of(orgResponse()));

        controller.createOrganization(req);

        verify(organizationService, never()).assertOrganizationExists(any());
        verify(accessControlService, never()).checkAdminOrAbove(any(), any(), any());
    }

    @Test
    @DisplayName("createOrganization: 親組織を指定すると実在確認と親組織ADMIN確認を行う")
    void createOrganization_親指定は実在確認とADMIN確認を行う() {
        CreateOrganizationRequest req = new CreateOrganizationRequest(
                "テスト子組織", "SCHOOL", "東京都", "渋谷区", "PUBLIC", ORG_ID, null);
        given(organizationService.createOrganization(USER_ID, req)).willReturn(ApiResponse.of(orgResponse()));

        controller.createOrganization(req);

        verify(organizationService).assertOrganizationExists(ORG_ID);
        verify(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
    }

    @Test
    @DisplayName("createOrganization: 親組織のADMINでなければ作成に到達しない")
    void createOrganization_親組織ADMINでなければ作成しない() {
        CreateOrganizationRequest req = new CreateOrganizationRequest(
                "テスト子組織", "SCHOOL", "東京都", "渋谷区", "PUBLIC", ORG_ID, null);
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");

        assertThatThrownBy(() -> controller.createOrganization(req))
                .isInstanceOf(BusinessException.class);

        verify(organizationService, never()).createOrganization(any(), any());
    }

    @Test
    @DisplayName("searchOrganizations: 200 OK")
    void searchOrganizations_200() {
        Pageable pageable = PageRequest.of(0, 10);
        given(organizationService.searchOrganizations("テスト", pageable)).willReturn(
                PagedResponse.of(List.of(new OrganizationSummaryResponse(ORG_SLUG, ORG_SLUG, "テスト", "SCHOOL", "PUBLIC", 1)),
                        new PagedResponse.PageMeta(1L, 0, 10, 1)));
        ResponseEntity<PagedResponse<OrganizationSummaryResponse>> resp = controller.searchOrganizations("テスト", pageable);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("getOrganization: 200 OK（可視性チェック通過時）")
    void getOrganization_200() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        given(organizationService.getOrganization(ORG_SLUG)).willReturn(ApiResponse.of(orgResponse()));
        assertThat(controller.getOrganization(ORG_SLUG).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(contentVisibilityChecker).assertCanView(ReferenceType.ORGANIZATION, ORG_ID, USER_ID);
    }

    @Test
    @DisplayName("getOrganization: 可視性チェックで拒否されたら例外を伝播（非メンバー→403）")
    void getOrganization_visibilityDenied_throws() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        willThrow(new BusinessException(VisibilityErrorCode.VISIBILITY_001))
                .given(contentVisibilityChecker)
                .assertCanView(ReferenceType.ORGANIZATION, ORG_ID, USER_ID);
        assertThatThrownBy(() -> controller.getOrganization(ORG_SLUG))
                .isInstanceOf(BusinessException.class);
        // 可視性で弾かれたら Service の取得本体は呼ばれない
        verify(organizationService, org.mockito.Mockito.never()).getOrganization(ORG_SLUG);
    }

    @Test
    @DisplayName("updateOrganization: 200 OK")
    void updateOrganization_200() {
        UpdateOrganizationRequest req = new UpdateOrganizationRequest(
                "更新", null, null, null, null, null, null, null, null, 0L);
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        given(organizationService.updateOrganization(ORG_ID, req)).willReturn(ApiResponse.of(orgResponse()));
        assertThat(controller.updateOrganization(ORG_SLUG, req).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("deleteOrganization: 204 No Content（checkAdminOrAbove を必ず呼ぶ）")
    void deleteOrganization_204() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        assertThat(controller.deleteOrganization(ORG_SLUG).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
        verify(organizationService).deleteOrganization(ORG_ID, USER_ID);
    }

    @Test
    @DisplayName("getMembers: 200 OK（可視性チェック通過時）")
    void getMembers_200() {
        Pageable pageable = PageRequest.of(0, 10);
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        given(organizationService.getMembers(ORG_ID, pageable)).willReturn(
                PagedResponse.of(List.of(new MemberResponse(USER_ID, "テスト", null, "ADMIN", LocalDateTime.now())),
                        new PagedResponse.PageMeta(1L, 0, 10, 1)));
        assertThat(controller.getMembers(ORG_SLUG, pageable).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(contentVisibilityChecker).assertCanView(ReferenceType.ORGANIZATION, ORG_ID, USER_ID);
    }

    @Test
    @DisplayName("getMembers: 可視性チェックで拒否されたらメンバー一覧を取得しない（非メンバー列挙の遮断）")
    void getMembers_visibilityDenied_throws() {
        Pageable pageable = PageRequest.of(0, 10);
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        willThrow(new BusinessException(VisibilityErrorCode.VISIBILITY_001))
                .given(contentVisibilityChecker)
                .assertCanView(ReferenceType.ORGANIZATION, ORG_ID, USER_ID);
        assertThatThrownBy(() -> controller.getMembers(ORG_SLUG, pageable))
                .isInstanceOf(BusinessException.class);
        verify(organizationService, org.mockito.Mockito.never()).getMembers(ORG_ID, pageable);
    }

    @Test
    @DisplayName("changeRole: 200 OK")
    void changeRole_200() {
        RoleChangeRequest req = new RoleChangeRequest(5L);
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        assertThat(controller.changeRole(ORG_SLUG, 200L, req).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(roleService).changeRole(ORG_ID, "ORGANIZATION", 200L, req, USER_ID);
    }

    @Test
    @DisplayName("removeMember: 204 No Content")
    void removeMember_204() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        assertThat(controller.removeMember(ORG_SLUG, 200L).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(roleService).removeMember(ORG_ID, "ORGANIZATION", 200L, USER_ID);
    }

    @Test
    @DisplayName("archiveOrganization: 200 OK（checkAdminOrAbove を必ず呼ぶ）")
    void archiveOrganization_200() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        assertThat(controller.archiveOrganization(ORG_SLUG).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
        verify(organizationService).archiveOrganization(ORG_ID);
    }

    @Test
    @DisplayName("unarchiveOrganization: 200 OK（checkAdminOrAbove を必ず呼ぶ）")
    void unarchiveOrganization_200() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        assertThat(controller.unarchiveOrganization(ORG_SLUG).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
        verify(organizationService).unarchiveOrganization(ORG_ID);
    }

    @Test
    @DisplayName("restoreOrganization: 204 No Content（checkSystemAdmin を必ず呼ぶ）")
    void restoreOrganization_204() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        assertThat(controller.restoreOrganization(ORG_SLUG).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // 組織 ADMIN 判定（checkAdminOrAbove）ではなく SYSTEM_ADMIN 判定であることを固定する
        verify(accessControlService).checkSystemAdmin(USER_ID);
        verify(organizationService).restoreOrganization(ORG_ID);
    }

    @Test
    @DisplayName("restoreOrganization: SYSTEM_ADMIN でなければ 403（COMMON_002）を送出する")
    void restoreOrganization_403_whenNotSystemAdmin() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkSystemAdmin(USER_ID);
        assertThatThrownBy(() -> controller.restoreOrganization(ORG_SLUG))
                .isInstanceOf(BusinessException.class);
        verify(organizationService, never()).restoreOrganization(any());
    }

    @Test
    @DisplayName("followOrganization: 201 Created（申請/即時承認）")
    void followOrganization_201() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        given(supporterService.follow(USER_ID, "ORGANIZATION", ORG_ID))
                .willReturn(ApiResponse.of(FollowStatusResponse.approved()));
        assertThat(controller.followOrganization(ORG_SLUG).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(supporterService).follow(USER_ID, "ORGANIZATION", ORG_ID);
    }

    @Test
    @DisplayName("unfollowOrganization: 204 No Content")
    void unfollowOrganization_204() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        assertThat(controller.unfollowOrganization(ORG_SLUG).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(supporterService).unfollow(USER_ID, "ORGANIZATION", ORG_ID);
    }

    @Test
    @DisplayName("createInviteToken: 201 Created")
    void createInviteToken_201() {
        CreateInviteTokenRequest req = new CreateInviteTokenRequest(5L, "7d", null);
        InviteTokenResponse tokenResp = new InviteTokenResponse(1L, "token", "ADMIN", null, null, 0, null, LocalDateTime.now());
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        given(inviteService.createInviteToken(ORG_ID, "ORGANIZATION", req, USER_ID)).willReturn(ApiResponse.of(tokenResp));
        assertThat(controller.createInviteToken(ORG_SLUG, req).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("getInviteTokens: 200 OK")
    void getInviteTokens_200() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        given(inviteService.getInviteTokens(ORG_ID, "ORGANIZATION", USER_ID)).willReturn(List.of());
        assertThat(controller.getInviteTokens(ORG_SLUG).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("revokeInviteToken: 204 No Content")
    void revokeInviteToken_204() {
        assertThat(controller.revokeInviteToken(ORG_SLUG, 99L).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(inviteService).revokeInviteToken(99L, USER_ID);
    }

    @Test
    @DisplayName("getPermissionGroups: 200 OK")
    void getPermissionGroups_200() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        given(permissionGroupService.getPermissionGroups(ORG_ID, "ORGANIZATION")).willReturn(List.of());
        assertThat(controller.getPermissionGroups(ORG_SLUG).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("createPermissionGroup: 201 Created")
    void createPermissionGroup_201() {
        PermissionGroupRequest req = new PermissionGroupRequest("グループ", "ADMIN", List.of(1L));
        PermissionGroupResponse resp = new PermissionGroupResponse(1L, "グループ", null, List.of(), LocalDateTime.now());
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        given(permissionGroupService.createPermissionGroup(ORG_ID, "ORGANIZATION", req, USER_ID)).willReturn(ApiResponse.of(resp));
        assertThat(controller.createPermissionGroup(ORG_SLUG, req).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("updatePermissionGroup: 200 OK")
    void updatePermissionGroup_200() {
        PermissionGroupRequest req = new PermissionGroupRequest("更新", "ADMIN", List.of(1L));
        PermissionGroupResponse resp = new PermissionGroupResponse(50L, "更新", null, List.of(), LocalDateTime.now());
        given(permissionGroupService.updatePermissionGroup(50L, req, USER_ID)).willReturn(ApiResponse.of(resp));
        assertThat(controller.updatePermissionGroup(ORG_SLUG, 50L, req).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("deletePermissionGroup: 204 No Content")
    void deletePermissionGroup_204() {
        assertThat(controller.deletePermissionGroup(ORG_SLUG, 50L).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(permissionGroupService).deletePermissionGroup(50L, USER_ID);
    }

    @Test
    @DisplayName("assignUserPermissionGroups: 200 OK")
    void assignUserPermissionGroups_200() {
        UserPermissionGroupAssignRequest req = new UserPermissionGroupAssignRequest(List.of(1L, 2L));
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        assertThat(controller.assignUserPermissionGroups(ORG_SLUG, 200L, req).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(permissionGroupService).assignUserPermissionGroups(200L, ORG_ID, "ORGANIZATION", req, USER_ID);
    }

    @Test
    @DisplayName("getBlocks: 200 OK")
    void getBlocks_200() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        given(blockService.getBlocks(ORG_ID, "ORGANIZATION", USER_ID)).willReturn(List.of());
        assertThat(controller.getBlocks(ORG_SLUG).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("blockUser: 201 Created")
    void blockUser_201() {
        BlockRequest req = new BlockRequest(300L, "スパム");
        BlockResponse resp = new BlockResponse(1L, 300L, "テスト", "ブロッカー", "スパム", LocalDateTime.now());
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        given(blockService.blockUser(ORG_ID, "ORGANIZATION", req, USER_ID)).willReturn(ApiResponse.of(resp));
        assertThat(controller.blockUser(ORG_SLUG, req).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("unblockUser: 204 No Content")
    void unblockUser_204() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        assertThat(controller.unblockUser(ORG_SLUG, 300L).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(blockService).unblockUser(eq(ORG_ID), eq("ORGANIZATION"), eq(300L), any());
    }

    @Test
    @DisplayName("getMyPermissions: 200 OK")
    void getMyPermissions_200() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        given(roleService.resolveEffectivePermissions(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(List.of("PERM_001"));
        given(accessControlService.getRoleName(USER_ID, ORG_ID, "ORGANIZATION")).willReturn("ADMIN");
        ResponseEntity<ApiResponse<EffectivePermissionsResponse>> resp = controller.getMyPermissions(ORG_SLUG);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getData().getRoleName()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("transferOwnership: 200 OK")
    void transferOwnership_200() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        assertThat(controller.transferOwnership(ORG_SLUG, 500L).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(roleService).transferOwnership(ORG_ID, "ORGANIZATION", USER_ID, 500L);
    }

    @Test
    @DisplayName("leaveOrganization: 204 No Content")
    void leaveOrganization_204() {
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
        assertThat(controller.leaveOrganization(ORG_SLUG).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(roleService).leaveScope(USER_ID, ORG_ID, "ORGANIZATION");
    }
}
