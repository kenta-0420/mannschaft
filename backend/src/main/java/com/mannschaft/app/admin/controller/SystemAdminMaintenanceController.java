package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.dto.CreateMaintenanceScheduleRequest;
import com.mannschaft.app.admin.dto.MaintenanceScheduleResponse;
import com.mannschaft.app.admin.dto.UpdateMaintenanceScheduleRequest;
import com.mannschaft.app.admin.service.MaintenanceScheduleService;
import com.mannschaft.app.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * システム管理者向けメンテナンススケジュールコントローラー。
 */
@RestController
@RequestMapping("/api/v1/system-admin/maintenance-schedules")
@Tag(name = "システム管理 - メンテナンス", description = "F10.1 メンテナンススケジュール管理API")
@RequiredArgsConstructor
public class SystemAdminMaintenanceController {

    private final MaintenanceScheduleService maintenanceService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * メンテナンススケジュール一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "メンテナンススケジュール一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<MaintenanceScheduleResponse>>> getAllSchedules() {
        List<MaintenanceScheduleResponse> schedules = maintenanceService.getAllSchedules();
        return ResponseEntity.ok(ApiResponse.of(schedules));
    }

    /**
     * メンテナンススケジュール詳細を取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "メンテナンススケジュール詳細取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<MaintenanceScheduleResponse>> getSchedule(@PathVariable Long id) {
        MaintenanceScheduleResponse response = maintenanceService.getSchedule(id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * メンテナンススケジュールを作成する。
     */
    @PostMapping
    @Operation(summary = "メンテナンススケジュール作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<MaintenanceScheduleResponse>> createSchedule(
            @Valid @RequestBody CreateMaintenanceScheduleRequest request) {
        MaintenanceScheduleResponse response = maintenanceService.createSchedule(request, getCurrentUserId());
        return ResponseEntity.created(URI.create("/api/v1/system-admin/maintenance-schedules/" + response.getId()))
                .body(ApiResponse.of(response));
    }

    /**
     * メンテナンススケジュールを更新する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "メンテナンススケジュール更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<MaintenanceScheduleResponse>> updateSchedule(
            @PathVariable Long id,
            @Valid @RequestBody UpdateMaintenanceScheduleRequest request) {
        MaintenanceScheduleResponse response = maintenanceService.updateSchedule(id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * メンテナンススケジュールを削除（キャンセル）する。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "メンテナンススケジュール削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteSchedule(@PathVariable Long id) {
        maintenanceService.deleteSchedule(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * メンテナンスを手動で開始する。
     */
    @PostMapping("/{id}/activate")
    @Operation(summary = "メンテナンス手動開始")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "開始成功")
    public ResponseEntity<ApiResponse<MaintenanceScheduleResponse>> activateSchedule(@PathVariable Long id) {
        MaintenanceScheduleResponse response = maintenanceService.activate(id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * メンテナンスを完了にする。
     */
    @PatchMapping("/{id}/complete")
    @Operation(summary = "メンテナンス完了")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "完了成功")
    public ResponseEntity<ApiResponse<MaintenanceScheduleResponse>> completeSchedule(@PathVariable Long id) {
        MaintenanceScheduleResponse response = maintenanceService.complete(id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
