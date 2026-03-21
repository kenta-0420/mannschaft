package com.mannschaft.app.reservation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.reservation.dto.BlockedTimeRequest;
import com.mannschaft.app.reservation.dto.BlockedTimeResponse;
import com.mannschaft.app.reservation.dto.BusinessHourResponse;
import com.mannschaft.app.reservation.dto.BusinessHoursUpdateRequest;
import com.mannschaft.app.reservation.service.ReservationBusinessHourService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 予約営業時間コントローラー。営業時間・ブロック時間・設定管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/reservation-settings")
@Tag(name = "予約設定管理", description = "F03.4 営業時間・ブロック時間・設定管理")
@RequiredArgsConstructor
public class ReservationBusinessHourController {

    private final ReservationBusinessHourService businessHourService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 営業時間設定を取得する。
     */
    @GetMapping("/business-hours")
    @Operation(summary = "営業時間取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<BusinessHourResponse>>> getBusinessHours(
            @PathVariable Long teamId) {
        List<BusinessHourResponse> hours = businessHourService.getBusinessHours(teamId);
        return ResponseEntity.ok(ApiResponse.of(hours));
    }

    /**
     * 営業時間設定を一括更新する。
     */
    @PutMapping("/business-hours")
    @Operation(summary = "営業時間一括更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<List<BusinessHourResponse>>> updateBusinessHours(
            @PathVariable Long teamId,
            @Valid @RequestBody BusinessHoursUpdateRequest request) {
        List<BusinessHourResponse> hours = businessHourService.updateBusinessHours(teamId, request);
        return ResponseEntity.ok(ApiResponse.of(hours));
    }

    /**
     * ブロック時間一覧を取得する。
     */
    @GetMapping("/blocked-times")
    @Operation(summary = "ブロック時間一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<BlockedTimeResponse>>> listBlockedTimes(
            @PathVariable Long teamId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        List<BlockedTimeResponse> blockedTimes = businessHourService.listBlockedTimes(teamId, from, to);
        return ResponseEntity.ok(ApiResponse.of(blockedTimes));
    }

    /**
     * ブロック時間を作成する。
     */
    @PostMapping("/blocked-times")
    @Operation(summary = "ブロック時間作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<BlockedTimeResponse>> createBlockedTime(
            @PathVariable Long teamId,
            @Valid @RequestBody BlockedTimeRequest request) {
        BlockedTimeResponse response = businessHourService.createBlockedTime(teamId, request, getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * ブロック時間を更新する。
     */
    @PatchMapping("/blocked-times/{blockedId}")
    @Operation(summary = "ブロック時間更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<BlockedTimeResponse>> updateBlockedTime(
            @PathVariable Long teamId,
            @PathVariable Long blockedId,
            @Valid @RequestBody BlockedTimeRequest request) {
        BlockedTimeResponse response = businessHourService.updateBlockedTime(teamId, blockedId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ブロック時間を削除する。
     */
    @DeleteMapping("/blocked-times/{blockedId}")
    @Operation(summary = "ブロック時間削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteBlockedTime(
            @PathVariable Long teamId,
            @PathVariable Long blockedId) {
        businessHourService.deleteBlockedTime(teamId, blockedId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 予約設定概要を取得する。
     */
    @GetMapping
    @Operation(summary = "予約設定概要")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSettings(
            @PathVariable Long teamId) {
        boolean hasBusinessHours = businessHourService.hasBusinessHours(teamId);
        Map<String, Object> settings = Map.of(
                "teamId", teamId,
                "hasBusinessHours", hasBusinessHours
        );
        return ResponseEntity.ok(ApiResponse.of(settings));
    }
}
