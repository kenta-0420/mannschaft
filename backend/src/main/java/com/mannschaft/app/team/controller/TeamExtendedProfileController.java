package com.mannschaft.app.team.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.team.dto.CreateTeamCustomFieldRequest;
import com.mannschaft.app.team.dto.CreateTeamOfficerRequest;
import com.mannschaft.app.team.dto.TeamCustomFieldResponse;
import com.mannschaft.app.team.dto.TeamOfficerResponse;
import com.mannschaft.app.team.dto.TeamProfileResponse;
import com.mannschaft.app.team.dto.TeamReorderRequest;
import com.mannschaft.app.team.dto.UpdateTeamCustomFieldRequest;
import com.mannschaft.app.team.dto.UpdateTeamOfficerRequest;
import com.mannschaft.app.team.dto.UpdateTeamProfileRequest;
import com.mannschaft.app.team.service.TeamExtendedProfileService;
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
 * チーム拡張プロフィールコントローラー。
 * 拡張プロフィール・役員・カスタムフィールドの CRUD エンドポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams")
@Tag(name = "チーム拡張プロフィール")
@RequiredArgsConstructor
public class TeamExtendedProfileController {

    private final TeamExtendedProfileService extendedProfileService;

    // ========================================
    // 拡張プロフィール
    // ========================================

    /**
     * チームの拡張プロフィールを取得する。
     */
    @GetMapping("/{id}/profile")
    @Operation(summary = "チーム拡張プロフィール取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<TeamProfileResponse>> getProfile(
            @PathVariable Long id) {
        return ResponseEntity.ok(
                extendedProfileService.getProfile(SecurityUtils.getCurrentUserId(), id));
    }

    /**
     * チームの拡張プロフィールを更新する。
     */
    @PatchMapping("/{id}/profile")
    @Operation(summary = "チーム拡張プロフィール更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<TeamProfileResponse>> updateProfile(
            @PathVariable Long id,
            @Valid @RequestBody UpdateTeamProfileRequest req) {
        return ResponseEntity.ok(
                extendedProfileService.updateProfile(SecurityUtils.getCurrentUserId(), id, req));
    }

    // ========================================
    // 役員
    // ========================================

    /**
     * チームの役員一覧を取得する。
     */
    @GetMapping("/{id}/officers")
    @Operation(summary = "チーム役員一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TeamOfficerResponse>>> getOfficers(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "false") boolean visibilityPreview) {
        return ResponseEntity.ok(
                extendedProfileService.getOfficers(SecurityUtils.getCurrentUserId(), id, visibilityPreview));
    }

    /**
     * チームに役員を追加する。
     */
    @PostMapping("/{id}/officers")
    @Operation(summary = "チーム役員追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<TeamOfficerResponse>> createOfficer(
            @PathVariable Long id,
            @Valid @RequestBody CreateTeamOfficerRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(extendedProfileService.createOfficer(SecurityUtils.getCurrentUserId(), id, req));
    }

    /**
     * チームの役員を更新する。
     */
    @PatchMapping("/{id}/officers/{officerId}")
    @Operation(summary = "チーム役員更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<TeamOfficerResponse>> updateOfficer(
            @PathVariable Long id,
            @PathVariable Long officerId,
            @Valid @RequestBody UpdateTeamOfficerRequest req) {
        return ResponseEntity.ok(
                extendedProfileService.updateOfficer(SecurityUtils.getCurrentUserId(), id, officerId, req));
    }

    /**
     * チームの役員を削除する。
     */
    @DeleteMapping("/{id}/officers/{officerId}")
    @Operation(summary = "チーム役員削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteOfficer(
            @PathVariable Long id,
            @PathVariable Long officerId) {
        extendedProfileService.deleteOfficer(SecurityUtils.getCurrentUserId(), id, officerId);
        return ResponseEntity.noContent().build();
    }

    /**
     * チームの役員表示順を並び替える。
     */
    @PutMapping("/{id}/officers/reorder")
    @Operation(summary = "チーム役員並び替え")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "並び替え成功")
    public ResponseEntity<Void> reorderOfficers(
            @PathVariable Long id,
            @Valid @RequestBody TeamReorderRequest req) {
        extendedProfileService.reorderOfficers(SecurityUtils.getCurrentUserId(), id, req);
        return ResponseEntity.noContent().build();
    }

    // ========================================
    // カスタムフィールド
    // ========================================

    /**
     * チームのカスタムフィールド一覧を取得する。
     */
    @GetMapping("/{id}/custom-fields")
    @Operation(summary = "チームカスタムフィールド一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TeamCustomFieldResponse>>> getCustomFields(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "false") boolean visibilityPreview) {
        return ResponseEntity.ok(
                extendedProfileService.getCustomFields(SecurityUtils.getCurrentUserId(), id, visibilityPreview));
    }

    /**
     * チームにカスタムフィールドを追加する。
     */
    @PostMapping("/{id}/custom-fields")
    @Operation(summary = "チームカスタムフィールド追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<TeamCustomFieldResponse>> createCustomField(
            @PathVariable Long id,
            @Valid @RequestBody CreateTeamCustomFieldRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(extendedProfileService.createCustomField(SecurityUtils.getCurrentUserId(), id, req));
    }

    /**
     * チームのカスタムフィールドを更新する。
     */
    @PatchMapping("/{id}/custom-fields/{fieldId}")
    @Operation(summary = "チームカスタムフィールド更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<TeamCustomFieldResponse>> updateCustomField(
            @PathVariable Long id,
            @PathVariable Long fieldId,
            @Valid @RequestBody UpdateTeamCustomFieldRequest req) {
        return ResponseEntity.ok(
                extendedProfileService.updateCustomField(SecurityUtils.getCurrentUserId(), id, fieldId, req));
    }

    /**
     * チームのカスタムフィールドを削除する。
     */
    @DeleteMapping("/{id}/custom-fields/{fieldId}")
    @Operation(summary = "チームカスタムフィールド削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteCustomField(
            @PathVariable Long id,
            @PathVariable Long fieldId) {
        extendedProfileService.deleteCustomField(SecurityUtils.getCurrentUserId(), id, fieldId);
        return ResponseEntity.noContent().build();
    }

    /**
     * チームのカスタムフィールド表示順を並び替える。
     */
    @PutMapping("/{id}/custom-fields/reorder")
    @Operation(summary = "チームカスタムフィールド並び替え")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "並び替え成功")
    public ResponseEntity<Void> reorderCustomFields(
            @PathVariable Long id,
            @Valid @RequestBody TeamReorderRequest req) {
        extendedProfileService.reorderCustomFields(SecurityUtils.getCurrentUserId(), id, req);
        return ResponseEntity.noContent().build();
    }
}
