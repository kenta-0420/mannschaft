package com.mannschaft.app.reservation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.reservation.dto.CreateSlotTemplateRequest;
import com.mannschaft.app.reservation.dto.DeleteSlotTemplateResponse;
import com.mannschaft.app.reservation.dto.GenerateSlotsRequest;
import com.mannschaft.app.reservation.dto.GenerateSlotsResponse;
import com.mannschaft.app.reservation.dto.SlotTemplateListResponse;
import com.mannschaft.app.reservation.dto.SlotTemplateResponse;
import com.mannschaft.app.reservation.dto.UpdateSlotTemplateRequest;
import com.mannschaft.app.reservation.service.ReservationSlotTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 週間テンプレートコントローラー（F03.4.2 §4）。
 *
 * <p>全 5 エンドポイントとも管理者（ADMIN / DEPUTY_ADMIN・role ベース）専用の self-gate
 * （{@code @PreAuthorize("@accessGuard.isScopeAdmin(...)")}・親 §6 方針）。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/reservation-slot-templates")
@Tag(name = "予約枠週間テンプレート", description = "F03.4.2 週間テンプレートCRUD＋一括生成")
@RequiredArgsConstructor
public class ReservationSlotTemplateController {

    private final ReservationSlotTemplateService templateService;

    /**
     * テンプレ一覧を取得する（曜日・ライン別）。
     */
    @GetMapping
    @Operation(summary = "週間テンプレート一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<SlotTemplateListResponse>> listTemplates(
            @PathVariable Long teamId) {
        return ResponseEntity.ok(ApiResponse.of(templateService.listTemplates(teamId)));
    }

    /**
     * テンプレを作成する（最大500行）。
     */
    @PostMapping
    @Operation(summary = "週間テンプレート作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<SlotTemplateResponse>> createTemplate(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateSlotTemplateRequest request) {
        SlotTemplateResponse response =
                templateService.createTemplate(teamId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * テンプレを部分更新する（isActive 切替・clearLineId を含む）。
     */
    @PatchMapping("/{templateId}")
    @Operation(summary = "週間テンプレート更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<SlotTemplateResponse>> updateTemplate(
            @PathVariable Long teamId,
            @PathVariable UUID templateId,
            @Valid @RequestBody UpdateSlotTemplateRequest request) {
        SlotTemplateResponse response =
                templateService.updateTemplate(teamId, templateId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * テンプレを物理削除する（生成済み枠は SET NULL で残置）。
     */
    @DeleteMapping("/{templateId}")
    @Operation(summary = "週間テンプレート削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "削除成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<DeleteSlotTemplateResponse>> deleteTemplate(
            @PathVariable Long teamId,
            @PathVariable UUID templateId) {
        DeleteSlotTemplateResponse response =
                templateService.deleteTemplate(teamId, templateId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チームの active テンプレ全件を対象に一括生成する（冪等・レートリミット 2回/分/チーム）。
     */
    @PostMapping("/generate")
    @Operation(summary = "週間テンプレート一括生成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "生成成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<GenerateSlotsResponse>> generate(
            @PathVariable Long teamId,
            @Valid @RequestBody GenerateSlotsRequest request) {
        GenerateSlotsResponse response =
                templateService.generate(teamId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
