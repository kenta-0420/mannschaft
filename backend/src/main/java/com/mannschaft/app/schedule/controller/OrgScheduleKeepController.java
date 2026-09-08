package com.mannschaft.app.schedule.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.config.OrgScopeId;
import com.mannschaft.app.schedule.authz.ScheduleKeepScope;
import com.mannschaft.app.schedule.dto.ConvertScheduleKeepRequest;
import com.mannschaft.app.schedule.dto.ConvertScheduleKeepResponse;
import com.mannschaft.app.schedule.dto.CreateScheduleKeepRequest;
import com.mannschaft.app.schedule.dto.ReorderScheduleKeepsRequest;
import com.mannschaft.app.schedule.dto.ScheduleKeepResponse;
import com.mannschaft.app.schedule.service.ScheduleKeepService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 組織スコープのキープ（日付未定の予定）コントローラー（F03.17 §4.1・第三陣）。
 *
 * <p>{@code TeamScheduleKeepController} と同形。認可は {@code ScheduleKeepService} 経由で
 * {@code ScheduleKeepAccessGuard} を必ず通す。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgPublicId}/schedule-keeps")
@Tag(name = "組織キープ管理", description = "F03.17 組織スコープのキープ（日付未定の予定）CRUD")
@RequiredArgsConstructor
public class OrgScheduleKeepController {

    private final ScheduleKeepService scheduleKeepService;

    @PostMapping
    @Operation(summary = "組織キープ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ScheduleKeepResponse>> create(
            @PathVariable OrgScopeId orgPublicId,
            @RequestBody CreateScheduleKeepRequest request) {
        Long orgId = orgPublicId.value();
        ScheduleKeepResponse response = scheduleKeepService.create(
                ScheduleKeepScope.organization(orgId), request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @GetMapping
    @Operation(summary = "組織キープ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ScheduleKeepResponse>>> list(
            @PathVariable OrgScopeId orgPublicId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long orgId = orgPublicId.value();
        List<ScheduleKeepResponse> response = scheduleKeepService.list(
                ScheduleKeepScope.organization(orgId), status, page, size, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @GetMapping("/{keepId}")
    @Operation(summary = "組織キープ詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ScheduleKeepResponse>> get(
            @PathVariable OrgScopeId orgPublicId,
            @PathVariable UUID keepId) {
        Long orgId = orgPublicId.value();
        ScheduleKeepResponse response = scheduleKeepService.get(
                ScheduleKeepScope.organization(orgId), keepId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PatchMapping("/{keepId}")
    @Operation(summary = "組織キープ更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ScheduleKeepResponse>> update(
            @PathVariable OrgScopeId orgPublicId,
            @PathVariable UUID keepId,
            @RequestBody Map<String, Object> body) {
        Long orgId = orgPublicId.value();
        ScheduleKeepResponse response = scheduleKeepService.update(
                ScheduleKeepScope.organization(orgId), keepId, body, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @DeleteMapping("/{keepId}")
    @Operation(summary = "組織キープ削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "削除成功")
    public ResponseEntity<Void> delete(
            @PathVariable OrgScopeId orgPublicId,
            @PathVariable UUID keepId) {
        Long orgId = orgPublicId.value();
        scheduleKeepService.delete(ScheduleKeepScope.organization(orgId), keepId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{keepId}/convert")
    @Operation(summary = "組織キープを予定へ変換")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "変換成功")
    public ResponseEntity<ApiResponse<ConvertScheduleKeepResponse>> convert(
            @PathVariable OrgScopeId orgPublicId,
            @PathVariable UUID keepId,
            @RequestBody ConvertScheduleKeepRequest request) {
        Long orgId = orgPublicId.value();
        ConvertScheduleKeepResponse response = scheduleKeepService.convert(
                ScheduleKeepScope.organization(orgId), keepId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PostMapping("/reorder")
    @Operation(summary = "組織キープの並び替え")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "並び替え成功")
    public ResponseEntity<Void> reorder(
            @PathVariable OrgScopeId orgPublicId,
            @RequestBody ReorderScheduleKeepsRequest request) {
        Long orgId = orgPublicId.value();
        scheduleKeepService.reorder(
                ScheduleKeepScope.organization(orgId), request.getOrderedIds(), SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/by-schedule/{scheduleId}")
    @Operation(summary = "予定から由来キープを逆引き")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ScheduleKeepResponse>> getByConvertedSchedule(
            @PathVariable OrgScopeId orgPublicId,
            @PathVariable Long scheduleId) {
        Long orgId = orgPublicId.value();
        ScheduleKeepResponse response = scheduleKeepService.getByConvertedSchedule(
                ScheduleKeepScope.organization(orgId), scheduleId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PostMapping("/{keepId}/archive")
    @Operation(summary = "組織キープをアーカイブ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "アーカイブ成功")
    public ResponseEntity<ApiResponse<ScheduleKeepResponse>> archive(
            @PathVariable OrgScopeId orgPublicId,
            @PathVariable UUID keepId) {
        Long orgId = orgPublicId.value();
        ScheduleKeepResponse response = scheduleKeepService.archive(
                ScheduleKeepScope.organization(orgId), keepId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PostMapping("/{keepId}/restore")
    @Operation(summary = "組織キープのアーカイブ解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "復帰成功")
    public ResponseEntity<ApiResponse<ScheduleKeepResponse>> restore(
            @PathVariable OrgScopeId orgPublicId,
            @PathVariable UUID keepId) {
        Long orgId = orgPublicId.value();
        ScheduleKeepResponse response = scheduleKeepService.restore(
                ScheduleKeepScope.organization(orgId), keepId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PostMapping("/{keepId}/revert")
    @Operation(summary = "組織キープの変換取消")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取消成功")
    public ResponseEntity<ApiResponse<ScheduleKeepResponse>> revert(
            @PathVariable OrgScopeId orgPublicId,
            @PathVariable UUID keepId) {
        Long orgId = orgPublicId.value();
        ScheduleKeepResponse response = scheduleKeepService.revert(
                ScheduleKeepScope.organization(orgId), keepId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
