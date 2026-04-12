package com.mannschaft.app.recruitment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.dto.CreateFromTemplateRequest;
import com.mannschaft.app.recruitment.dto.RecruitmentListingResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentTemplateCreateRequest;
import com.mannschaft.app.recruitment.dto.RecruitmentTemplateResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentTemplateUpdateRequest;
import com.mannschaft.app.recruitment.service.RecruitmentListingService;
import com.mannschaft.app.recruitment.service.RecruitmentTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F03.11 募集型予約: テンプレート管理 Controller (§9.5)。
 *
 * <p>チーム・組織それぞれのテンプレート CRUD と
 * テンプレートから募集枠を生成するエンドポイントを提供する。</p>
 */
@RestController
@Tag(name = "F03.11 募集型予約 / テンプレート", description = "募集テンプレートの管理・募集枠への適用")
@RequiredArgsConstructor
public class RecruitmentTemplateController {

    private final RecruitmentTemplateService templateService;
    private final RecruitmentListingService listingService;

    // ===========================================
    // チーム スコープ
    // ===========================================

    @PostMapping("/api/v1/teams/{teamId}/recruitment-templates")
    @Operation(summary = "チームの募集テンプレート作成", description = "ADMIN/DEPUTY_ADMIN 権限が必要")
    public ResponseEntity<ApiResponse<RecruitmentTemplateResponse>> createForTeam(
            @PathVariable Long teamId,
            @Valid @RequestBody RecruitmentTemplateCreateRequest request) {
        RecruitmentTemplateResponse response = templateService.create(
                RecruitmentScopeType.TEAM, teamId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @GetMapping("/api/v1/teams/{teamId}/recruitment-templates")
    @Operation(summary = "チームの募集テンプレート一覧", description = "MEMBER 以上の権限が必要")
    public ResponseEntity<PagedResponse<RecruitmentTemplateResponse>> listForTeam(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<RecruitmentTemplateResponse> result = templateService.listByScope(
                RecruitmentScopeType.TEAM, teamId, SecurityUtils.getCurrentUserId(),
                PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @PostMapping("/api/v1/teams/{teamId}/recruitment-listings/from-template")
    @Operation(summary = "チームの募集テンプレートから募集枠作成", description = "ADMIN/DEPUTY_ADMIN 権限が必要")
    public ResponseEntity<ApiResponse<RecruitmentListingResponse>> createFromTemplateForTeam(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateFromTemplateRequest request) {
        RecruitmentListingResponse response = listingService.createFromTemplate(
                RecruitmentScopeType.TEAM, teamId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    // ===========================================
    // 組織 スコープ
    // ===========================================

    @PostMapping("/api/v1/organizations/{orgId}/recruitment-templates")
    @Operation(summary = "組織の募集テンプレート作成", description = "ADMIN/DEPUTY_ADMIN 権限が必要")
    public ResponseEntity<ApiResponse<RecruitmentTemplateResponse>> createForOrg(
            @PathVariable Long orgId,
            @Valid @RequestBody RecruitmentTemplateCreateRequest request) {
        RecruitmentTemplateResponse response = templateService.create(
                RecruitmentScopeType.ORGANIZATION, orgId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @GetMapping("/api/v1/organizations/{orgId}/recruitment-templates")
    @Operation(summary = "組織の募集テンプレート一覧", description = "MEMBER 以上の権限が必要")
    public ResponseEntity<PagedResponse<RecruitmentTemplateResponse>> listForOrg(
            @PathVariable Long orgId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<RecruitmentTemplateResponse> result = templateService.listByScope(
                RecruitmentScopeType.ORGANIZATION, orgId, SecurityUtils.getCurrentUserId(),
                PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @PostMapping("/api/v1/organizations/{orgId}/recruitment-listings/from-template")
    @Operation(summary = "組織の募集テンプレートから募集枠作成", description = "ADMIN/DEPUTY_ADMIN 権限が必要")
    public ResponseEntity<ApiResponse<RecruitmentListingResponse>> createFromTemplateForOrg(
            @PathVariable Long orgId,
            @Valid @RequestBody CreateFromTemplateRequest request) {
        RecruitmentListingResponse response = listingService.createFromTemplate(
                RecruitmentScopeType.ORGANIZATION, orgId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    // ===========================================
    // テンプレート個別操作（スコープ横断）
    // ===========================================

    @GetMapping("/api/v1/recruitment-templates/{id}")
    @Operation(summary = "募集テンプレート詳細取得", description = "MEMBER 以上の権限が必要")
    public ResponseEntity<ApiResponse<RecruitmentTemplateResponse>> getTemplate(
            @PathVariable Long id) {
        RecruitmentTemplateResponse response = templateService.getTemplate(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PatchMapping("/api/v1/recruitment-templates/{id}")
    @Operation(summary = "募集テンプレート編集", description = "ADMIN/DEPUTY_ADMIN 権限が必要")
    public ResponseEntity<ApiResponse<RecruitmentTemplateResponse>> updateTemplate(
            @PathVariable Long id,
            @Valid @RequestBody RecruitmentTemplateUpdateRequest request) {
        RecruitmentTemplateResponse response = templateService.update(id, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PostMapping("/api/v1/recruitment-templates/{id}/archive")
    @Operation(summary = "募集テンプレート論理削除（アーカイブ）", description = "ADMIN/DEPUTY_ADMIN 権限が必要")
    public ResponseEntity<Void> archiveTemplate(@PathVariable Long id) {
        templateService.archive(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
