package com.mannschaft.app.incident.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.incident.service.IncidentService.IncidentResponse;
import com.mannschaft.app.incident.service.MaintenanceScheduleService;
import com.mannschaft.app.incident.service.MaintenanceScheduleService.CreateMaintenanceScheduleRequest;
import com.mannschaft.app.incident.service.MaintenanceScheduleService.MaintenanceScheduleResponse;
import com.mannschaft.app.incident.service.MaintenanceScheduleService.UpdateMaintenanceScheduleRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
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
 * メンテナンススケジュール管理コントローラー。
 * 定期メンテナンスの作成・一覧取得・更新・削除・手動トリガーを提供する。
 */
@RestController
@RequestMapping("/api/maintenance-schedules")
@RequiredArgsConstructor
public class MaintenanceScheduleController {

    private final MaintenanceScheduleService maintenanceScheduleService;

    /**
     * メンテナンススケジュールを作成する。
     * 認可: ADMIN 相当
     */
    @PostMapping
    public ResponseEntity<ApiResponse<MaintenanceScheduleResponse>> createSchedule(
            @Validated @RequestBody CreateMaintenanceScheduleRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        MaintenanceScheduleResponse response =
                maintenanceScheduleService.createSchedule(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * スコープに紐づくスケジュール一覧を取得する。
     * 認可: ADMIN 相当
     */
    @GetMapping
    public ApiResponse<List<MaintenanceScheduleResponse>> listSchedules(
            @RequestParam String scopeType,
            @RequestParam Long scopeId) {
        List<MaintenanceScheduleResponse> response =
                maintenanceScheduleService.listSchedules(scopeType, scopeId);
        return ApiResponse.of(response);
    }

    /**
     * メンテナンススケジュールを更新する。
     * 認可: ADMIN 相当
     */
    @PutMapping("/{id}")
    public ApiResponse<MaintenanceScheduleResponse> updateSchedule(
            @PathVariable Long id,
            @Validated @RequestBody UpdateMaintenanceScheduleRequest request) {
        MaintenanceScheduleResponse response =
                maintenanceScheduleService.updateSchedule(id, request);
        return ApiResponse.of(response);
    }

    /**
     * メンテナンススケジュールを論理削除する。
     * 認可: ADMIN 相当
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSchedule(@PathVariable Long id) {
        maintenanceScheduleService.deleteSchedule(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * メンテナンススケジュールを手動トリガーし、インシデントを即時生成する。
     * 認可: ADMIN 相当
     */
    @PostMapping("/{id}/trigger")
    public ApiResponse<IncidentResponse> triggerManually(@PathVariable Long id) {
        IncidentResponse response = maintenanceScheduleService.triggerManually(id);
        return ApiResponse.of(response);
    }
}
