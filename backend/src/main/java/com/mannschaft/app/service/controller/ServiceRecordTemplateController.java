package com.mannschaft.app.service.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.service.dto.CreateTemplateRequest;
import com.mannschaft.app.service.dto.TemplateResponse;
import com.mannschaft.app.service.dto.UpdateTemplateRequest;
import com.mannschaft.app.service.service.ServiceRecordTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * テンプレートコントローラー。チーム・組織テンプレートのCRUD APIを提供する。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "サービス履歴テンプレート", description = "F07.1 テンプレートCRUD（チーム・組織）")
@RequiredArgsConstructor
public class ServiceRecordTemplateController {

    private final ServiceRecordTemplateService templateService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    // ==================== 24. チームテンプレート一覧 ====================

    /**
     * テンプレート一覧を取得する（チーム固有 + 組織共有を統合）。
     */
    @GetMapping("/teams/{teamId}/service-records/templates")
    @Operation(summary = "テンプレート一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TemplateResponse>>> listTeamTemplates(
            @PathVariable Long teamId,
            @RequestParam(required = false) Long organizationId) {
        // organizationId が指定された場合、組織スコープのテンプレートも含めて返却する
        List<TemplateResponse> response = templateService.listTeamTemplates(teamId, organizationId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ==================== 25. チームテンプレート作成 ====================

    /**
     * チームテンプレートを作成する。
     */
    @PostMapping("/teams/{teamId}/service-records/templates")
    @Operation(summary = "チームテンプレート作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<TemplateResponse>> createTeamTemplate(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateTemplateRequest request) {
        TemplateResponse response = templateService.createTeamTemplate(teamId, getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    // ==================== 26. チームテンプレート詳細 ====================

    /**
     * テンプレート詳細を取得する。
     */
    @GetMapping("/teams/{teamId}/service-records/templates/{id}")
    @Operation(summary = "テンプレート詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<TemplateResponse>> getTeamTemplate(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        TemplateResponse response = templateService.getTeamTemplate(teamId, id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ==================== 27. チームテンプレート更新 ====================

    /**
     * チームテンプレートを更新する。
     */
    @PutMapping("/teams/{teamId}/service-records/templates/{id}")
    @Operation(summary = "チームテンプレート更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<TemplateResponse>> updateTeamTemplate(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody UpdateTemplateRequest request) {
        TemplateResponse response = templateService.updateTeamTemplate(teamId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ==================== 28. チームテンプレート削除 ====================

    /**
     * チームテンプレートを論理削除する。
     */
    @DeleteMapping("/teams/{teamId}/service-records/templates/{id}")
    @Operation(summary = "チームテンプレート削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteTeamTemplate(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        templateService.deleteTeamTemplate(teamId, id);
        return ResponseEntity.noContent().build();
    }

    // ==================== 29. 組織テンプレート一覧 ====================

    /**
     * 組織テンプレート一覧を取得する。
     */
    @GetMapping("/organizations/{orgId}/service-records/templates")
    @Operation(summary = "組織テンプレート一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TemplateResponse>>> listOrgTemplates(
            @PathVariable Long orgId) {
        List<TemplateResponse> response = templateService.listOrgTemplates(orgId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ==================== 30. 組織テンプレート作成 ====================

    /**
     * 組織テンプレートを作成する。
     */
    @PostMapping("/organizations/{orgId}/service-records/templates")
    @Operation(summary = "組織テンプレート作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<TemplateResponse>> createOrgTemplate(
            @PathVariable Long orgId,
            @Valid @RequestBody CreateTemplateRequest request) {
        TemplateResponse response = templateService.createOrgTemplate(orgId, getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    // ==================== 31. 組織テンプレート更新 ====================

    /**
     * 組織テンプレートを更新する。
     */
    @PutMapping("/organizations/{orgId}/service-records/templates/{id}")
    @Operation(summary = "組織テンプレート更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<TemplateResponse>> updateOrgTemplate(
            @PathVariable Long orgId,
            @PathVariable Long id,
            @Valid @RequestBody UpdateTemplateRequest request) {
        TemplateResponse response = templateService.updateOrgTemplate(orgId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ==================== 32. 組織テンプレート削除 ====================

    /**
     * 組織テンプレートを論理削除する。
     */
    @DeleteMapping("/organizations/{orgId}/service-records/templates/{id}")
    @Operation(summary = "組織テンプレート削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteOrgTemplate(
            @PathVariable Long orgId,
            @PathVariable Long id) {
        templateService.deleteOrgTemplate(orgId, id);
        return ResponseEntity.noContent().build();
    }
}
