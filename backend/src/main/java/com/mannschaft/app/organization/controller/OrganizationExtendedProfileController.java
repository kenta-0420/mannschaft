package com.mannschaft.app.organization.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.organization.dto.CreateCustomFieldRequest;
import com.mannschaft.app.organization.dto.CreateOfficerRequest;
import com.mannschaft.app.organization.dto.CustomFieldResponse;
import com.mannschaft.app.organization.dto.OfficerResponse;
import com.mannschaft.app.organization.dto.OrganizationProfileResponse;
import com.mannschaft.app.organization.dto.ReorderRequest;
import com.mannschaft.app.organization.dto.UpdateCustomFieldRequest;
import com.mannschaft.app.organization.dto.UpdateOfficerRequest;
import com.mannschaft.app.organization.dto.UpdateOrgProfileRequest;
import com.mannschaft.app.organization.service.OrganizationExtendedProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 組織拡張プロフィールコントローラー。
 * 拡張プロフィール・役員・カスタムフィールドの CRUD エンドポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1/organizations")
@Tag(name = "組織拡張プロフィール")
@RequiredArgsConstructor
public class OrganizationExtendedProfileController {

    private final OrganizationExtendedProfileService extendedProfileService;

    // ========================================
    // 拡張プロフィール
    // ========================================

    /**
     * 組織の拡張プロフィールを更新する。
     */
    @PatchMapping("/{id}/profile")
    @Operation(summary = "組織拡張プロフィール更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<OrganizationProfileResponse>> updateProfile(
            @PathVariable Long id,
            @Valid @RequestBody UpdateOrgProfileRequest req) {
        return ResponseEntity.ok(
                extendedProfileService.updateProfile(SecurityUtils.getCurrentUserId(), id, req));
    }

    // ========================================
    // 役員
    // ========================================

    /**
     * 組織の役員一覧を取得する。
     */
    @GetMapping("/{id}/officers")
    @Operation(summary = "組織役員一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<OfficerResponse>>> getOfficers(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "false") boolean visibilityPreview) {
        return ResponseEntity.ok(
                extendedProfileService.getOfficers(SecurityUtils.getCurrentUserId(), id, visibilityPreview));
    }

    /**
     * 組織に役員を追加する。
     */
    @PostMapping("/{id}/officers")
    @Operation(summary = "組織役員追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<OfficerResponse>> createOfficer(
            @PathVariable Long id,
            @Valid @RequestBody CreateOfficerRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(extendedProfileService.createOfficer(SecurityUtils.getCurrentUserId(), id, req));
    }

    /**
     * 組織の役員を更新する。
     */
    @PatchMapping("/{id}/officers/{officerId}")
    @Operation(summary = "組織役員更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<OfficerResponse>> updateOfficer(
            @PathVariable Long id,
            @PathVariable Long officerId,
            @Valid @RequestBody UpdateOfficerRequest req) {
        return ResponseEntity.ok(
                extendedProfileService.updateOfficer(SecurityUtils.getCurrentUserId(), id, officerId, req));
    }

    /**
     * 組織の役員を削除する。
     */
    @DeleteMapping("/{id}/officers/{officerId}")
    @Operation(summary = "組織役員削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteOfficer(
            @PathVariable Long id,
            @PathVariable Long officerId) {
        extendedProfileService.deleteOfficer(SecurityUtils.getCurrentUserId(), id, officerId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 組織の役員表示順を並び替える。
     */
    @PutMapping("/{id}/officers/reorder")
    @Operation(summary = "組織役員並び替え")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "並び替え成功")
    public ResponseEntity<Void> reorderOfficers(
            @PathVariable Long id,
            @Valid @RequestBody ReorderRequest req) {
        extendedProfileService.reorderOfficers(SecurityUtils.getCurrentUserId(), id, req);
        return ResponseEntity.noContent().build();
    }

    // ========================================
    // カスタムフィールド
    // ========================================

    /**
     * 組織のカスタムフィールド一覧を取得する。
     */
    @GetMapping("/{id}/custom-fields")
    @Operation(summary = "組織カスタムフィールド一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<CustomFieldResponse>>> getCustomFields(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "false") boolean visibilityPreview) {
        return ResponseEntity.ok(
                extendedProfileService.getCustomFields(SecurityUtils.getCurrentUserId(), id, visibilityPreview));
    }

    /**
     * 組織にカスタムフィールドを追加する。
     */
    @PostMapping("/{id}/custom-fields")
    @Operation(summary = "組織カスタムフィールド追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<CustomFieldResponse>> createCustomField(
            @PathVariable Long id,
            @Valid @RequestBody CreateCustomFieldRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(extendedProfileService.createCustomField(SecurityUtils.getCurrentUserId(), id, req));
    }

    /**
     * 組織のカスタムフィールドを更新する。
     */
    @PatchMapping("/{id}/custom-fields/{fieldId}")
    @Operation(summary = "組織カスタムフィールド更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<CustomFieldResponse>> updateCustomField(
            @PathVariable Long id,
            @PathVariable Long fieldId,
            @Valid @RequestBody UpdateCustomFieldRequest req) {
        return ResponseEntity.ok(
                extendedProfileService.updateCustomField(SecurityUtils.getCurrentUserId(), id, fieldId, req));
    }

    /**
     * 組織のカスタムフィールドを削除する。
     */
    @DeleteMapping("/{id}/custom-fields/{fieldId}")
    @Operation(summary = "組織カスタムフィールド削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteCustomField(
            @PathVariable Long id,
            @PathVariable Long fieldId) {
        extendedProfileService.deleteCustomField(SecurityUtils.getCurrentUserId(), id, fieldId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 組織のカスタムフィールド表示順を並び替える。
     */
    @PutMapping("/{id}/custom-fields/reorder")
    @Operation(summary = "組織カスタムフィールド並び替え")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "並び替え成功")
    public ResponseEntity<Void> reorderCustomFields(
            @PathVariable Long id,
            @Valid @RequestBody ReorderRequest req) {
        extendedProfileService.reorderCustomFields(SecurityUtils.getCurrentUserId(), id, req);
        return ResponseEntity.noContent().build();
    }
}
