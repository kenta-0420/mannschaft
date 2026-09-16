package com.mannschaft.app.provisioning.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.provisioning.dto.ProvisioningInvitationResponse;
import com.mannschaft.app.provisioning.dto.ProvisioningOrganizationCreateRequest;
import com.mannschaft.app.provisioning.dto.ProvisioningTeamCreateRequest;
import com.mannschaft.app.provisioning.service.ProvisioningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 柱②-2: 販促プロビジョニング SYSTEM_ADMIN 向け API。
 *
 * <p>全エンドポイントは {@code /api/v1/system-admin/**} 配下であり、
 * {@code SecurityConfig} の包括ルール（{@code hasRole("SYSTEM_ADMIN")}）で一層目の認可を担う（AC1）。
 * 二層目の細粒度認可は {@link ProvisioningService} 側で行う（AC2）。</p>
 *
 * <p>本 PR では試練（受け入れテスト）のみを設置する。実装は後続 PR（出陣）で行う。</p>
 */
@RestController
@RequestMapping("/api/v1/system-admin/provisioning")
@Tag(name = "販促プロビジョニング（SYSTEM_ADMIN）")
@RequiredArgsConstructor
public class SystemAdminProvisioningController {

    private final ProvisioningService provisioningService;

    @PostMapping("/organizations")
    @Operation(summary = "組織をPROVISIONED状態で事前作成し、管理予定者へADMIN招待を送る")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功",
            content = @io.swagger.v3.oas.annotations.media.Content(
                    schema = @io.swagger.v3.oas.annotations.media.Schema(
                            implementation = ProvisioningInvitationResponse.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "柱③-A: 同名候補が存在し確認が必要（confirmDuplicate 未指定、または"
                    + " fingerprint 不一致＝確認後に新たな同名が出現）。候補一覧・fingerprint を返す",
            content = @io.swagger.v3.oas.annotations.media.Content(
                    schema = @io.swagger.v3.oas.annotations.media.Schema(
                            implementation = com.mannschaft.app.common.duplicatename
                                    .DuplicateNameConfirmationErrorResponse.class)))
    public ResponseEntity<ProvisioningInvitationResponse> createOrganization(
            @Valid @RequestBody ProvisioningOrganizationCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(provisioningService.createOrganization(SecurityUtils.getCurrentUserId(), request));
    }

    @PostMapping("/teams")
    @Operation(summary = "チームをPROVISIONED状態で事前作成し、管理予定者へADMIN招待を送る")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功",
            content = @io.swagger.v3.oas.annotations.media.Content(
                    schema = @io.swagger.v3.oas.annotations.media.Schema(
                            implementation = ProvisioningInvitationResponse.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "柱③-A: 同名候補が存在し確認が必要（confirmDuplicate 未指定、または"
                    + " fingerprint 不一致＝確認後に新たな同名が出現）。候補一覧・fingerprint を返す",
            content = @io.swagger.v3.oas.annotations.media.Content(
                    schema = @io.swagger.v3.oas.annotations.media.Schema(
                            implementation = com.mannschaft.app.common.duplicatename
                                    .DuplicateNameConfirmationErrorResponse.class)))
    public ResponseEntity<ProvisioningInvitationResponse> createTeam(
            @Valid @RequestBody ProvisioningTeamCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(provisioningService.createTeam(SecurityUtils.getCurrentUserId(), request));
    }

    @GetMapping("/invitations")
    @Operation(summary = "プロビジョニング招待の一覧を取得する")
    public ResponseEntity<List<ProvisioningInvitationResponse>> list() {
        return ResponseEntity.ok(provisioningService.list(SecurityUtils.getCurrentUserId()));
    }

    @PostMapping("/invitations/{invitationId}/resend")
    @Operation(summary = "招待を再送する（旧トークンは失効し、新しいトークンを発行する）")
    public ResponseEntity<ProvisioningInvitationResponse> resend(@PathVariable UUID invitationId) {
        return ResponseEntity.ok(provisioningService.resend(SecurityUtils.getCurrentUserId(), invitationId));
    }

    @PostMapping("/invitations/{invitationId}/cancel")
    @Operation(summary = "招待を取消す")
    public ResponseEntity<Void> cancel(@PathVariable UUID invitationId) {
        provisioningService.cancel(SecurityUtils.getCurrentUserId(), invitationId);
        return ResponseEntity.noContent().build();
    }
}
