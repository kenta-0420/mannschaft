package com.mannschaft.app.shift.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.shift.dto.AvailabilityDefaultResponse;
import com.mannschaft.app.shift.dto.BulkAvailabilityDefaultRequest;
import com.mannschaft.app.shift.dto.CreateHourlyRateRequest;
import com.mannschaft.app.shift.dto.HourlyRateResponse;
import com.mannschaft.app.shift.service.ShiftAvailabilityService;
import com.mannschaft.app.shift.service.ShiftHourlyRateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.LocalDate;
import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * シフト勤務可能時間・時給コントローラー。デフォルト勤務可能時間と時給設定APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/shifts")
@Tag(name = "シフト勤務可能時間・時給管理", description = "F03.5 デフォルト勤務可能時間と時給設定")
@RequiredArgsConstructor
public class ShiftAvailabilityController {

    private final ShiftAvailabilityService availabilityService;
    private final ShiftHourlyRateService hourlyRateService;


    /**
     * デフォルト勤務可能時間を取得する。
     */
    @GetMapping("/availability")
    @Operation(summary = "デフォルト勤務可能時間取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<AvailabilityDefaultResponse>>> getAvailabilityDefaults(
            @RequestParam Long teamId) {
        List<AvailabilityDefaultResponse> responses = availabilityService
                .getAvailabilityDefaults(SecurityUtils.getCurrentUserId(), teamId);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * デフォルト勤務可能時間を一括設定する。
     */
    @PutMapping("/availability")
    @Operation(summary = "デフォルト勤務可能時間一括設定")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "設定成功")
    public ResponseEntity<ApiResponse<List<AvailabilityDefaultResponse>>> setAvailabilityDefaults(
            @RequestParam Long teamId,
            @Valid @RequestBody BulkAvailabilityDefaultRequest request) {
        List<AvailabilityDefaultResponse> responses = availabilityService
                .setAvailabilityDefaults(SecurityUtils.getCurrentUserId(), teamId, request);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * デフォルト勤務可能時間を削除する。
     */
    @DeleteMapping("/availability")
    @Operation(summary = "デフォルト勤務可能時間削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteAvailabilityDefaults(
            @RequestParam Long teamId) {
        availabilityService.deleteAvailabilityDefaults(SecurityUtils.getCurrentUserId(), teamId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 時給を設定する。
     */
    @PostMapping("/hourly-rate")
    @Operation(summary = "時給設定")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "設定成功")
    public ResponseEntity<ApiResponse<HourlyRateResponse>> createHourlyRate(
            @RequestParam Long teamId,
            @Valid @RequestBody CreateHourlyRateRequest request) {
        HourlyRateResponse response = hourlyRateService.createHourlyRate(teamId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 時給履歴を取得する。
     */
    @GetMapping("/hourly-rate")
    @Operation(summary = "時給履歴取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<HourlyRateResponse>>> listHourlyRates(
            @RequestParam Long teamId,
            @RequestParam Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date != null) {
            HourlyRateResponse rate = hourlyRateService.getEffectiveRate(userId, teamId, date);
            return ResponseEntity.ok(ApiResponse.of(rate != null ? List.of(rate) : List.of()));
        }
        List<HourlyRateResponse> responses = hourlyRateService.listHourlyRates(userId, teamId);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }
}
