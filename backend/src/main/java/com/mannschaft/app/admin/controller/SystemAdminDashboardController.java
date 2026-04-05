package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.dto.SystemAdminDashboardResponse;
import com.mannschaft.app.admin.service.SystemAdminDashboardService;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * システム管理者ダッシュボードコントローラー。
 */
@RestController
@RequestMapping("/api/v1/system-admin/dashboard")
@Tag(name = "システム管理 - ダッシュボード", description = "F10.1 システム管理者ダッシュボードAPI")
@RequiredArgsConstructor
public class SystemAdminDashboardController {

    private final SystemAdminDashboardService dashboardService;
    private final OrganizationRepository organizationRepository;
    private final TeamRepository teamRepository;
    private final UserRepository userRepository;
    private final OrganizationService organizationService;

    /**
     * システム管理者ダッシュボード情報を取得する。
     */
    @GetMapping
    @Operation(summary = "システム管理者ダッシュボード取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<SystemAdminDashboardResponse>> getDashboard() {
        SystemAdminDashboardResponse response = dashboardService.getDashboard();
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 全組織一覧を取得する。
     */
    @GetMapping("/organizations")
    @Operation(summary = "全組織一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<Page<OrganizationEntity>>> getOrganizations(Pageable pageable) {
        Page<OrganizationEntity> page = organizationRepository.findAll(pageable);
        return ResponseEntity.ok(ApiResponse.of(page));
    }

    /**
     * 全チーム一覧を取得する。
     */
    @GetMapping("/teams")
    @Operation(summary = "全チーム一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<Page<TeamEntity>>> getTeams(Pageable pageable) {
        Page<TeamEntity> page = teamRepository.findAll(pageable);
        return ResponseEntity.ok(ApiResponse.of(page));
    }

    /**
     * 全ユーザー一覧を取得する。
     */
    @GetMapping("/users")
    @Operation(summary = "全ユーザー一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<Page<UserEntity>>> getUsers(Pageable pageable) {
        Page<UserEntity> page = userRepository.findAll(pageable);
        return ResponseEntity.ok(ApiResponse.of(page));
    }

    /**
     * 組織を凍結する。
     */
    @PatchMapping("/organizations/{organizationId}/freeze")
    @Operation(summary = "組織凍結")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "凍結成功")
    public ResponseEntity<Void> freezeOrganization(@PathVariable Long organizationId) {
        organizationService.archiveOrganization(organizationId);
        return ResponseEntity.ok().build();
    }

    /**
     * 組織の凍結を解除する。
     */
    @PatchMapping("/organizations/{organizationId}/unfreeze")
    @Operation(summary = "組織凍結解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "解除成功")
    public ResponseEntity<Void> unfreezeOrganization(@PathVariable Long organizationId) {
        organizationService.unarchiveOrganization(organizationId);
        return ResponseEntity.ok().build();
    }
}
